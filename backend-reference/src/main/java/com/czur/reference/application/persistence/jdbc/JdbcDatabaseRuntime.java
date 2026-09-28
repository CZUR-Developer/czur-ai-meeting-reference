package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.MeetingUnitOfWork;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PostgreSQL 数据库运行时边界。
 *
 * <p>启动时创建唯一的 HikariCP 连接池，执行只读 {@code SELECT 1} 探针并检查
 * {@code schema_migrations} 中的 V001～V011，再以只读 catalog 查询核对 V007
 * Manifest revision、V008 durable input fact、V009 final usage fact 与 V010
 * workflow step，以及 V011 execution fact/READY artifact 表的精确物理契约。
 * 任一检查失败都会关闭已经创建的池并抛出
 * {@link DatabaseStartupException}；本类没有 InMemory fallback，也不会自动执行
 * 迁移或修补半成品表。成功启动后，所有 JDBC Repository、会议工作单元和提交后的
 * Outbox Worker 端口都共享同一个 {@link DataSource}。</p>
 *
 * <p>本类只负责数据库装配和生命周期，不实现 HTTP、Gateway、Worker 轮询、认证、
 * 权益、模型或存储逻辑。{@link #outboxStore()} 暴露的实现用于提交后的短事务操作，
 * 会议命令仍应通过 {@link #meetingUnitOfWork()} 原子提交快照、幂等记录和 Outbox。</p>
 */
public final class JdbcDatabaseRuntime implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(JdbcDatabaseRuntime.class.getName());
    private static final String MIGRATION_QUERY = """
            SELECT version
              FROM schema_migrations
              WHERE version IN (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final Set<String> REQUIRED_MIGRATIONS = Set.of(
            "V001__initial_schema",
            "V002__outbox_lease_fencing",
            "V003__identity_entitlement",
            "V004__meeting_event_envelope",
            "V005__meeting_finalization_fact",
            "V006__meeting_read_model",
            "V007__meeting_manifest_revision",
            "V008__durable_input_fact",
            "V009__final_usage_fact",
            "V010__workflow_step_fencing",
            "V011__finalization_execution_artifact_ready");
    private static final List<ColumnContract> MANIFEST_REVISION_COLUMNS = List.of(
            new ColumnContract("meeting_id", "text", true, null),
            new ColumnContract("manifest_revision", "integer", true, null),
            new ColumnContract("previous_revision", "integer", false, null),
            new ColumnContract("canonical_json", "text", true, null),
            new ColumnContract("manifest_json", "jsonb", true, null),
            new ColumnContract("content_sha256", "bytea", true, null),
            new ColumnContract(
                    "recorded_at", "timestamp with time zone", true, "CURRENT_TIMESTAMP"));
    private static final String MANIFEST_CHAIN_CHECK = """
            CHECK (manifest_revision = 1 AND previous_revision IS NULL
            OR manifest_revision > 1 AND previous_revision IS NOT NULL
            AND previous_revision = (manifest_revision - 1))
            """;
    private static final String MANIFEST_DOCUMENT_CHECK = """
            CHECK (jsonb_typeof(manifest_json) = 'object'::text
            AND manifest_json ?& ARRAY['specVersion'::text, 'meetingId'::text,
            'manifestRevision'::text, 'profile'::text, 'title'::text, 'status'::text,
            'startedAt'::text, 'endedAt'::text, 'durationMs'::text, 'languages'::text,
            'artifacts'::text, 'warnings'::text]
            AND (manifest_json - ARRAY['specVersion'::text, 'meetingId'::text,
            'manifestRevision'::text, 'profile'::text, 'title'::text, 'status'::text,
            'startedAt'::text, 'endedAt'::text, 'durationMs'::text, 'languages'::text,
            'artifacts'::text, 'warnings'::text]) = '{}'::jsonb
            AND jsonb_typeof(manifest_json -> 'meetingId'::text) = 'string'::text
            AND (manifest_json ->> 'meetingId'::text) = meeting_id
            AND jsonb_typeof(manifest_json -> 'manifestRevision'::text) = 'number'::text
            AND (manifest_json ->> 'manifestRevision'::text) ~ '^[1-9][0-9]*$'::text
            AND ((manifest_json ->> 'manifestRevision'::text)::numeric) <= 2147483647::numeric
            AND ((manifest_json ->> 'manifestRevision'::text)::numeric) = manifest_revision::numeric
            AND jsonb_typeof(manifest_json -> 'status'::text) = 'string'::text
            AND ((manifest_json ->> 'status'::text) = ANY (ARRAY['READY'::text,
            'READY_WITH_WARNINGS'::text, 'FAILED'::text, 'DELETION_PENDING'::text,
            'DELETED'::text])) AND canonical_json::jsonb = manifest_json)
            """;
    private static final String MANIFEST_HASH_CHECK = """
            CHECK (octet_length(content_sha256) = 32
            AND content_sha256 = sha256(convert_to(canonical_json, 'UTF8'::name)))
            """;
    private static final List<ColumnContract> DURABLE_INPUT_FACT_COLUMNS = List.of(
            new ColumnContract("meeting_id", "text", true, null),
            new ColumnContract("input_binding", "text", true, null),
            new ColumnContract("last_accepted_sequence", "bigint", true, null),
            new ColumnContract("audio_duration_ms", "bigint", true, null),
            new ColumnContract("source_language", "text", true, null),
            new ColumnContract("target_languages", "text[]", true, null),
            new ColumnContract("accepted_at", "timestamp with time zone", true, null),
            new ColumnContract("content_digest", "text", true, null));
    private static final List<ColumnContract> FINAL_USAGE_FACT_COLUMNS = List.of(
            new ColumnContract("meeting_id", "text", true, null),
            new ColumnContract("actual_unit", "text", true, null),
            new ColumnContract("actual_amount", "bigint", true, null),
            new ColumnContract("source_input_binding", "text", true, null),
            new ColumnContract("source_input_digest", "text", true, null),
            new ColumnContract("source_accepted_at", "timestamp with time zone", true, null));
    private static final String FINAL_USAGE_UNIT_CHECK =
            "CHECK (actual_unit = 'MEETING_DURATION_MS'::text)";
    private static final String FINAL_USAGE_AMOUNT_CHECK =
            "CHECK (actual_amount >= 0)";
    private static final String FINAL_USAGE_BINDING_CHECK =
            "CHECK (length(btrim(source_input_binding)) > 0)";
    private static final String FINAL_USAGE_DIGEST_CHECK =
            "CHECK (length(btrim(source_input_digest)) > 0)";
    private static final List<ColumnContract> WORKFLOW_STEP_COLUMNS = List.of(
            new ColumnContract("meeting_id", "text", true, null),
            new ColumnContract("step_type", "text", true, null),
            new ColumnContract("step_key", "text", true, null),
            new ColumnContract("checkpoint_type", "text", true, null),
            new ColumnContract("canonical_payload", "text", true, null),
            new ColumnContract("content_digest", "text", true, null),
            new ColumnContract("initial_available_at", "timestamp with time zone", true, null),
            new ColumnContract("status", "text", true, null),
            new ColumnContract("attempt_count", "integer", true, "0"),
            new ColumnContract("next_attempt_at", "timestamp with time zone", false, null),
            new ColumnContract("lease_owner", "text", false, null),
            new ColumnContract("lease_until", "timestamp with time zone", false, null),
            new ColumnContract("lease_token", "text", false, null),
            new ColumnContract("lease_version", "bigint", true, "0"),
            new ColumnContract("error_code", "text", false, null),
            new ColumnContract("error_message", "text", false, null),
            new ColumnContract("updated_at", "timestamp with time zone", true, null));
    private static final String WORKFLOW_STEP_STATUS_CHECK =
            "CHECK (status = ANY (ARRAY['PENDING'::text, 'LEASED'::text, "
                    + "'COMPLETED'::text, 'DEAD'::text]))";
    private static final String WORKFLOW_STEP_DEFINITION_CHECK =
            "CHECK (length(btrim(checkpoint_type)) > 0 "
                    + "AND length(btrim(canonical_payload)) > 0 "
                    + "AND length(btrim(content_digest)) > 0)";
    private static final String WORKFLOW_STEP_LEASE_TOKEN_CHECK =
            "CHECK (lease_token IS NULL OR length(btrim(lease_token)) > 0)";
    private static final String WORKFLOW_STEP_ATTEMPT_CHECK =
            "CHECK (attempt_count >= 0)";
    private static final String WORKFLOW_STEP_FACTS_CHECK = """
            CHECK (lease_version >= 0
            AND (status = 'PENDING'::text AND next_attempt_at IS NOT NULL
            AND lease_owner IS NULL AND lease_until IS NULL AND lease_token IS NULL
            OR status = 'LEASED'::text AND next_attempt_at IS NULL
            AND lease_owner IS NOT NULL AND length(btrim(lease_owner)) > 0
            AND lease_until IS NOT NULL AND lease_token IS NOT NULL
            AND lease_version > 0 AND lease_until > updated_at
            OR status = 'COMPLETED'::text AND next_attempt_at IS NULL
            AND lease_owner IS NULL AND lease_until IS NULL AND lease_token IS NULL
            AND error_code IS NULL AND error_message IS NULL
            OR status = 'DEAD'::text AND next_attempt_at IS NULL
            AND lease_owner IS NULL AND lease_until IS NULL AND lease_token IS NULL
            AND error_code IS NOT NULL AND error_message IS NOT NULL)
            AND (error_code IS NULL AND error_message IS NULL
            OR error_code IS NOT NULL AND error_message IS NOT NULL
            AND length(btrim(error_code)) > 0 AND length(btrim(error_message)) > 0))
            """;
    private static final List<ColumnContract> FINALIZATION_EXECUTION_FACT_COLUMNS = List.of(
            new ColumnContract("meeting_id", "text", true, null),
            new ColumnContract("canonical_json", "text", true, null),
            new ColumnContract("execution_fact_json", "jsonb", true, null),
            new ColumnContract("content_sha256", "bytea", true, null));
    private static final String FINALIZATION_EXECUTION_DOCUMENT_CHECK = """
            CHECK (jsonb_typeof(execution_fact_json) = 'object'::text
            AND execution_fact_json ?& ARRAY['specVersion'::text, 'meetingId'::text,
            'startedAt'::text, 'title'::text, 'productProfile'::text, 'capabilityPlan'::text]
            AND (execution_fact_json - ARRAY['specVersion'::text, 'meetingId'::text,
            'startedAt'::text, 'title'::text, 'productProfile'::text, 'capabilityPlan'::text]) = '{}'::jsonb
            AND jsonb_typeof(execution_fact_json -> 'specVersion'::text) = 'string'::text
            AND (execution_fact_json ->> 'specVersion'::text) = 'finalization-execution-fact/v1'::text
            AND jsonb_typeof(execution_fact_json -> 'meetingId'::text) = 'string'::text
            AND (execution_fact_json ->> 'meetingId'::text) = meeting_id
            AND jsonb_typeof(execution_fact_json -> 'startedAt'::text) = 'string'::text
            AND (execution_fact_json ->> 'startedAt'::text) ~
            '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\\.[0-9]{1,6})?Z$'::text
            AND (jsonb_typeof(execution_fact_json -> 'title'::text) = ANY (ARRAY['string'::text, 'null'::text]))
            AND jsonb_typeof(execution_fact_json -> 'productProfile'::text) = 'object'::text
            AND jsonb_typeof(execution_fact_json -> 'capabilityPlan'::text) = 'object'::text
            AND canonical_json::jsonb = execution_fact_json)
            """;
    private static final String FINALIZATION_EXECUTION_HASH_CHECK = """
            CHECK (octet_length(content_sha256) = 32
            AND content_sha256 = sha256(convert_to(canonical_json, 'UTF8'::name)))
            """;
    private static final List<ColumnContract> READY_ARTIFACT_COLUMNS = List.of(
            new ColumnContract("artifact_id", "text", true, null),
            new ColumnContract("meeting_id", "text", true, null),
            new ColumnContract("artifact_type", "text", true, null),
            new ColumnContract("media_type", "text", true, null),
            new ColumnContract("language", "text", false, null),
            new ColumnContract("revision", "integer", true, null),
            new ColumnContract("intent_id", "text", true, null),
            new ColumnContract("storage_binding", "text", true, null),
            new ColumnContract("content_size", "bigint", true, null),
            new ColumnContract("content_checksum", "text", true, null),
            new ColumnContract(
                    "recorded_at", "timestamp with time zone", true, "CURRENT_TIMESTAMP"));
    /** Java 21 {@code Character.isWhitespace} / {@code String.isBlank} 使用的完整码点集。 */
    private static final String JAVA_21_BLANK_SQL =
            "concat(chr(9), chr(10), chr(11), chr(12), chr(13), "
                    + "chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), "
                    + "chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), "
                    + "chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), "
                    + "chr(8232), chr(8233), chr(8287), chr(12288))";
    private static final String READY_ARTIFACT_TEXT_CHECK =
            "CHECK (" + hasNonBlankSql("artifact_id")
                    + " AND octet_length(convert_to(artifact_id, 'UTF8'::name)) <= 4096"
                    + " AND " + hasNonBlankSql("meeting_id")
                    + " AND octet_length(convert_to(meeting_id, 'UTF8'::name)) <= 4096"
                    + " AND " + hasNonBlankSql("artifact_type")
                    + " AND octet_length(convert_to(artifact_type, 'UTF8'::name)) <= 4096"
                    + " AND " + hasNonBlankSql("media_type")
                    + " AND octet_length(convert_to(media_type, 'UTF8'::name)) <= 4096"
                    + " AND " + hasNonBlankSql("intent_id")
                    + " AND octet_length(convert_to(intent_id, 'UTF8'::name)) <= 4096"
                    + " AND " + hasNonBlankSql("storage_binding")
                    + " AND octet_length(convert_to(storage_binding, 'UTF8'::name)) <= 4096"
                    + " AND " + hasNonBlankSql("content_checksum")
                    + " AND octet_length(convert_to(content_checksum, 'UTF8'::name)) <= 4096)";
    private static final String READY_ARTIFACT_LANGUAGE_CHECK =
            "CHECK (language IS NULL OR " + hasNonBlankSql("language")
                    + " AND octet_length(convert_to(language, 'UTF8'::name)) <= 4096)";
    private static final String READY_ARTIFACT_REVISION_CHECK =
            "CHECK (revision > 0)";
    private static final String READY_ARTIFACT_SIZE_CHECK =
            "CHECK (content_size >= 0)";
    private static final String READY_ARTIFACT_CHECKSUM_CHECK =
            "CHECK (content_checksum ~ '^sha256:[0-9a-f]{64}$'::text)";

    private final DatabaseRuntimeConfig config;
    private final HikariDataSource dataSource;
    private final JdbcMeetingUnitOfWork meetingUnitOfWork;
    private final JdbcOutboxStore outboxStore;
    private final JdbcWorkflowStepStore workflowStepStore;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private JdbcDatabaseRuntime(DatabaseRuntimeConfig config, HikariDataSource dataSource) {
        this.config = config;
        this.dataSource = dataSource;
        this.meetingUnitOfWork = new JdbcMeetingUnitOfWork(dataSource);
        this.outboxStore = new JdbcOutboxStore(dataSource);
        this.workflowStepStore = new JdbcWorkflowStepStore(dataSource);
    }

    /**
     * 按当前进程环境启动数据库运行时。
     *
     * @return 已通过连接和迁移 readiness 的运行时
     * @throws IllegalArgumentException 配置非法，且不会创建连接池
     * @throws DatabaseStartupException 数据库不可用或迁移未登记；失败时池已关闭
     */
    public static JdbcDatabaseRuntime start() {
        return start(DatabaseRuntimeConfig.fromEnvironment());
    }

    /**
     * 用显式配置启动数据库运行时。
     *
     * @param config 已校验的数据库配置
     * @return 可供应用装配使用的数据库运行时
     * @throws DatabaseStartupException readiness 未通过；创建的池会先关闭
     */
    public static JdbcDatabaseRuntime start(DatabaseRuntimeConfig config) {
        Objects.requireNonNull(config, "config");
        HikariDataSource pool = null;
        JdbcDatabaseRuntime runtime = null;
        try {
            pool = createPool(config);
            runtime = new JdbcDatabaseRuntime(config, pool);
            DatabaseReadiness readiness = runtime.checkReadiness();
            if (!readiness.ready()) {
                LOGGER.log(Level.WARNING, "数据库运行时启动 readiness 失败 status={0}", readiness.status());
                runtime.close();
                throw new DatabaseStartupException(readiness);
            }
            LOGGER.log(Level.INFO, "数据库运行时启动成功 pool={0}", config);
            return runtime;
        } catch (DatabaseStartupException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            if (runtime != null) {
                runtime.close();
            } else if (pool != null) {
                closeQuietly(pool);
            }
            DatabaseReadiness readiness = DatabaseReadiness.unavailable(
                    "数据库运行时初始化异常：" + failure.getClass().getSimpleName());
            LOGGER.log(Level.WARNING, "数据库运行时启动异常 status={0} exception={1}",
                    new Object[]{readiness.status(), failure.getClass().getSimpleName()});
            throw new DatabaseStartupException(readiness);
        }
    }

    /** 兼容装配代码的显式别名；仍然执行同样的启动 readiness。 */
    public static JdbcDatabaseRuntime open(DatabaseRuntimeConfig config) {
        return start(config);
    }

    /**
     * 执行一次只读连接、探针和迁移登记检查。
     *
     * <p>该方法不会修改数据库，不会自动补登记，也不会把暂时不可用写成 liveness
     * 失败。应用可按需周期调用此方法，但本任务不创建轮询线程或 HTTP 健康端点。</p>
     */
    public DatabaseReadiness checkReadiness() {
        if (closed.get()) {
            return DatabaseReadiness.closed();
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement probe = connection.prepareStatement("SELECT 1");
                 ResultSet result = probe.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    return DatabaseReadiness.unavailable("只读数据库探针返回异常结果");
                }
            }
            Set<String> present = new HashSet<>();
            try (PreparedStatement migrations = connection.prepareStatement(MIGRATION_QUERY)) {
                migrations.setString(1, "V001__initial_schema");
                migrations.setString(2, "V002__outbox_lease_fencing");
                migrations.setString(3, "V003__identity_entitlement");
                migrations.setString(4, "V004__meeting_event_envelope");
                migrations.setString(5, "V005__meeting_finalization_fact");
                migrations.setString(6, "V006__meeting_read_model");
                migrations.setString(7, "V007__meeting_manifest_revision");
                // P2-T09 以前的开发脚本可能只把 V001/V002 登记为短版本；继续
                // 识别这两个已存在别名，但新迁移只接受完整不可歧义的文件名。
                migrations.setString(8, "V001");
                migrations.setString(9, "V002");
                migrations.setString(10, "V008__durable_input_fact");
                migrations.setString(11, "V009__final_usage_fact");
                migrations.setString(12, "V010__workflow_step_fencing");
                migrations.setString(13, "V011__finalization_execution_artifact_ready");
                try (ResultSet rows = migrations.executeQuery()) {
                    while (rows.next()) {
                        String version = rows.getString(1);
                        if ("V001".equals(version)) {
                            present.add("V001__initial_schema");
                        } else if ("V002".equals(version)) {
                            present.add("V002__outbox_lease_fencing");
                        } else {
                            present.add(version);
                        }
                    }
                }
            }
            Set<String> missing = new HashSet<>(REQUIRED_MIGRATIONS);
            missing.removeAll(present);
            if (!missing.isEmpty()) {
                LOGGER.log(Level.WARNING, "数据库 readiness 缺少迁移登记 count={0}", missing.size());
                return DatabaseReadiness.migrationMissing(missing);
            }
            if (!hasExactMeetingIdentitySchema(connection)) {
                DatabaseReadiness invalid = DatabaseReadiness.schemaInvalid(
                        "meeting identity catalog contract mismatch");
                LOGGER.log(Level.WARNING,
                        "数据库 readiness 物理契约校验失败 status={0}", invalid.status());
                return invalid;
            }
            if (!hasExactManifestRevisionSchema(connection)) {
                DatabaseReadiness invalid = DatabaseReadiness.schemaInvalid(
                        "meeting_manifest_revision catalog contract mismatch");
                LOGGER.log(Level.WARNING,
                        "数据库 readiness 物理契约校验失败 status={0}", invalid.status());
                return invalid;
            }
            if (!hasExactDurableInputFactSchema(connection)) {
                DatabaseReadiness invalid = DatabaseReadiness.schemaInvalid(
                        "durable_input_fact catalog contract mismatch");
                LOGGER.log(Level.WARNING,
                        "数据库 readiness 物理契约校验失败 status={0}", invalid.status());
                return invalid;
            }
            if (!hasExactFinalUsageFactSchema(connection)) {
                DatabaseReadiness invalid = DatabaseReadiness.schemaInvalid(
                        "final_usage_fact catalog contract mismatch");
                LOGGER.log(Level.WARNING,
                        "数据库 readiness 物理契约校验失败 status={0}", invalid.status());
                return invalid;
            }
            if (!hasExactWorkflowStepSchema(connection)) {
                DatabaseReadiness invalid = DatabaseReadiness.schemaInvalid(
                        "workflow_step V010 catalog contract mismatch");
                LOGGER.log(Level.WARNING,
                        "数据库 readiness 物理契约校验失败 status={0}", invalid.status());
                return invalid;
            }
            if (!hasExactFinalizationExecutionFactSchema(connection)) {
                DatabaseReadiness invalid = DatabaseReadiness.schemaInvalid(
                        "finalization_execution_fact V011 catalog contract mismatch");
                LOGGER.log(Level.WARNING,
                        "数据库 readiness 物理契约校验失败 status={0}", invalid.status());
                return invalid;
            }
            if (!hasExactReadyArtifactSchema(connection)) {
                DatabaseReadiness invalid = DatabaseReadiness.schemaInvalid(
                        "artifact READY V011 catalog contract mismatch");
                LOGGER.log(Level.WARNING,
                        "数据库 readiness 物理契约校验失败 status={0}", invalid.status());
                return invalid;
            }
            LOGGER.log(Level.FINE, "数据库 readiness 检查通过");
            return DatabaseReadiness.success();
        } catch (SQLException failure) {
            if (isMissingMigrationTable(failure)) {
                return DatabaseReadiness.migrationMissing(REQUIRED_MIGRATIONS);
            }
            LOGGER.log(Level.WARNING, "数据库 readiness 连接失败 exception={0}",
                    failure.getClass().getSimpleName());
            return DatabaseReadiness.unavailable("数据库连接或只读探针不可用");
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "数据库 readiness 检查异常 exception={0}",
                    failure.getClass().getSimpleName());
            return DatabaseReadiness.unavailable("数据库 readiness 检查异常");
        }
    }

    /**
     * liveness 只表示运行时对象尚未关闭，不代表数据库当前可连接。
     *
     * @return 未调用 close 前为 true；数据库临时不可用时仍可能为 true
     */
    public boolean isLive() {
        return !closed.get();
    }

    /** liveness 的语义别名，便于装配层使用不绑定 HTTP 的健康模型。 */
    public boolean liveness() {
        return isLive();
    }

    /** 返回唯一共享连接池的数据源；运行时关闭后禁止继续装配业务组件。 */
    public DataSource dataSource() {
        ensureLive();
        return dataSource;
    }

    /** 返回使用共享连接池的会议命令工作单元。 */
    public JdbcMeetingUnitOfWork meetingUnitOfWork() {
        ensureLive();
        return meetingUnitOfWork;
    }

    /** 返回提交后 Outbox 短事务适配器，不在会议工作单元中执行 Worker 轮询。 */
    public JdbcOutboxStore outboxStore() {
        ensureLive();
        return outboxStore;
    }

    /** 返回使用共享连接池的工作流步骤租约栅栏适配器。 */
    public JdbcWorkflowStepStore workflowStepStore() {
        ensureLive();
        return workflowStepStore;
    }

    /** 工作单元的端口类型别名，避免调用方复制第二套事务装配。 */
    public MeetingUnitOfWork unitOfWork() {
        return meetingUnitOfWork();
    }

    /** 返回启动时使用的不可变配置；配置本身不含可变连接状态。 */
    public DatabaseRuntimeConfig config() {
        return config;
    }

    /**
     * 幂等关闭共享连接池。关闭后 liveness 立即为 false，重复 close 不报错；支持
     * try-with-resources。关闭不影响数据库中的业务数据，也不触发迁移或删除操作。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(dataSource);
        LOGGER.log(Level.INFO, "数据库运行时已关闭");
    }

    private void ensureLive() {
        if (closed.get()) {
            throw new IllegalStateException("数据库运行时已关闭，不能获取业务组件");
        }
    }

    private static HikariDataSource createPool(DatabaseRuntimeConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.url());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setConnectionTimeout(config.connectionTimeout().toMillis());
        hikari.setValidationTimeout(config.validationTimeout().toMillis());
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setMinimumIdle(config.minimumIdle());
        hikari.setPoolName("czur-meeting-db");
        hikari.setReadOnly(false);
        return new HikariDataSource(hikari);
    }

    private static boolean isMissingMigrationTable(SQLException failure) {
        return "42P01".equals(failure.getSQLState());
    }

    /**
     * 核对所有 V011 外键依赖的 V001 Meeting 父身份。
     *
     * <p>{@code meeting_id} 必须仍是第 1 列的 {@code TEXT NOT NULL}，不能有
     * default/identity/generated 语义，并必须由 validated、非延迟的
     * {@code meeting_pkey} 直接作为单列主键。仅用 nullable UNIQUE 让子外键
     * 可以创建，仍然不是可接受的父契约。</p>
     */
    private static boolean hasExactMeetingIdentitySchema(Connection connection)
            throws SQLException {
        Long meetingTable = relationOid(connection, "meeting");
        if (meetingTable == null || !isStandalonePermanentTable(connection, meetingTable)) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT format_type(a.atttypid, a.atttypmod) AS data_type,
                       a.attnotnull, a.attidentity, a.attgenerated,
                       pg_get_expr(d.adbin, d.adrelid) AS default_expression
                  FROM pg_attribute a
                  LEFT JOIN pg_attrdef d
                    ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                 WHERE a.attrelid = ?
                   AND a.attnum = 1
                   AND a.attname = 'meeting_id'
                   AND NOT a.attisdropped
                """)) {
            statement.setLong(1, meetingTable);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()
                        || !"text".equals(rows.getString("data_type"))
                        || !rows.getBoolean("attnotnull")
                        || !"".equals(rows.getString("attidentity"))
                        || !"".equals(rows.getString("attgenerated"))
                        || rows.getString("default_expression") != null
                        || rows.next()) {
                    return false;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT convalidated, condeferrable, condeferred,
                       coalesce(array_to_string(conkey, ','), '') AS source_keys,
                       pg_get_constraintdef(oid, TRUE) AS definition
                  FROM pg_constraint
                 WHERE conrelid = ?
                   AND conname = 'meeting_pkey'
                   AND contype = 'p'
                """)) {
            statement.setLong(1, meetingTable);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        && rows.getBoolean("convalidated")
                        && !rows.getBoolean("condeferrable")
                        && !rows.getBoolean("condeferred")
                        && "1".equals(rows.getString("source_keys"))
                        && "PRIMARY KEY (meeting_id)".equals(rows.getString("definition"))
                        && !rows.next();
            }
        }
    }

    /**
     * 只读核对 V007 Manifest revision 表的冻结物理契约。
     *
     * <p>此探针不查询业务行，也不依赖测试数据。它精确要求当前 schema 中存在普通
     * 表、七列的顺序/类型/可空性/默认值、一个复合主键、两个 RESTRICT 外键，以及
     * 三个已经 validated 且定义匹配的 CHECK。只登记迁移、创建半表，或用同名
     * {@code CHECK (true)} 替换约束都会返回 false；方法不会自动建表或修复对象。</p>
     */
    private static boolean hasExactManifestRevisionSchema(Connection connection)
            throws SQLException {
        Long revisionTable = relationOid(connection, "meeting_manifest_revision");
        Long meetingTable = relationOid(connection, "meeting");
        if (revisionTable == null || meetingTable == null
                || !isStandalonePermanentTable(connection, revisionTable)) {
            return false;
        }

        List<ColumnContract> actualColumns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.attname,
                       format_type(a.atttypid, a.atttypmod) AS data_type,
                       a.attnotnull,
                       pg_get_expr(d.adbin, d.adrelid) AS default_expression
                  FROM pg_attribute a
                  LEFT JOIN pg_attrdef d
                    ON d.adrelid = a.attrelid
                   AND d.adnum = a.attnum
                 WHERE a.attrelid = ?
                   AND a.attnum > 0
                   AND NOT a.attisdropped
                 ORDER BY a.attnum
                """)) {
            statement.setLong(1, revisionTable);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    actualColumns.add(new ColumnContract(
                            rows.getString("attname"),
                            rows.getString("data_type"),
                            rows.getBoolean("attnotnull"),
                            rows.getString("default_expression")));
                }
            }
        }
        if (!MANIFEST_REVISION_COLUMNS.equals(actualColumns)) {
            return false;
        }

        List<ConstraintContract> constraints = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conname,
                       contype,
                       convalidated,
                       coalesce(array_to_string(conkey, ','), '') AS source_keys,
                       confrelid::bigint AS referenced_relation,
                       coalesce(array_to_string(confkey, ','), '') AS target_keys,
                       confdeltype,
                       pg_get_constraintdef(oid, TRUE) AS definition
                  FROM pg_constraint
                 WHERE conrelid = ?
                 ORDER BY conname
                """)) {
            statement.setLong(1, revisionTable);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    constraints.add(new ConstraintContract(
                            rows.getString("conname"),
                            rows.getString("contype"),
                            rows.getBoolean("convalidated"),
                            rows.getString("source_keys"),
                            rows.getLong("referenced_relation"),
                            rows.getString("target_keys"),
                            rows.getString("confdeltype"),
                            rows.getString("definition")));
                }
            }
        }
        if (constraints.size() != 6) {
            return false;
        }
        return containsExactCheck(
                        constraints,
                        "meeting_manifest_revision_chain_ck",
                        MANIFEST_CHAIN_CHECK)
                && containsExactCheck(
                        constraints,
                        "meeting_manifest_revision_document_ck",
                        MANIFEST_DOCUMENT_CHECK)
                && containsExactCheck(
                        constraints,
                        "meeting_manifest_revision_hash_ck",
                        MANIFEST_HASH_CHECK)
                && constraints.contains(ConstraintContract.foreignKey(
                        "meeting_manifest_revision_meeting_fk", "1", meetingTable, "1"))
                && constraints.contains(ConstraintContract.primaryKey(
                        "meeting_manifest_revision_pkey", "1,2"))
                && constraints.contains(ConstraintContract.foreignKey(
                        "meeting_manifest_revision_previous_fk",
                        "1,3", revisionTable, "1,2"));
    }

    /**
     * 只读核对 V008 durable input fact 表的冻结物理契约。
     *
     * <p>该探针只检查 schema 对象，不读取业务行，也不自动创建或修补表。它要求
     * 八列按迁移顺序存在、单列主键和 meeting 的 RESTRICT 外键存在，以及六个
     * 已校验的基本字段约束仍保留关键语义。数组约束按关键片段核对，避免把
     * PostgreSQL 对复杂表达式的无害括号重排当成结构漂移。</p>
     */
    private static boolean hasExactDurableInputFactSchema(Connection connection)
            throws SQLException {
        Long factTable = relationOid(connection, "durable_input_fact");
        Long meetingTable = relationOid(connection, "meeting");
        if (factTable == null || meetingTable == null
                || !isStandalonePermanentTable(connection, factTable)) {
            return false;
        }

        List<ColumnContract> actualColumns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.attname,
                       format_type(a.atttypid, a.atttypmod) AS data_type,
                       a.attnotnull,
                       pg_get_expr(d.adbin, d.adrelid) AS default_expression
                  FROM pg_attribute a
                  LEFT JOIN pg_attrdef d
                    ON d.adrelid = a.attrelid
                   AND d.adnum = a.attnum
                 WHERE a.attrelid = ?
                   AND a.attnum > 0
                   AND NOT a.attisdropped
                 ORDER BY a.attnum
                """)) {
            statement.setLong(1, factTable);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    actualColumns.add(new ColumnContract(
                            rows.getString("attname"),
                            rows.getString("data_type"),
                            rows.getBoolean("attnotnull"),
                            rows.getString("default_expression")));
                }
            }
        }
        if (!DURABLE_INPUT_FACT_COLUMNS.equals(actualColumns)) {
            return false;
        }

        List<ConstraintContract> constraints = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conname,
                       contype,
                       convalidated,
                       coalesce(array_to_string(conkey, ','), '') AS source_keys,
                       confrelid::bigint AS referenced_relation,
                       coalesce(array_to_string(confkey, ','), '') AS target_keys,
                       confdeltype,
                       pg_get_constraintdef(oid, TRUE) AS definition
                  FROM pg_constraint
                 WHERE conrelid = ?
                 ORDER BY conname
                """)) {
            statement.setLong(1, factTable);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    constraints.add(new ConstraintContract(
                            rows.getString("conname"),
                            rows.getString("contype"),
                            rows.getBoolean("convalidated"),
                            rows.getString("source_keys"),
                            rows.getLong("referenced_relation"),
                            rows.getString("target_keys"),
                            rows.getString("confdeltype"),
                            rows.getString("definition")));
                }
            }
        }
        if (constraints.size() != 8
                || !constraints.contains(ConstraintContract.primaryKey(
                        "durable_input_fact_pkey", "1"))
                || !constraints.contains(ConstraintContract.foreignKey(
                        "durable_input_fact_meeting_fk", "1", meetingTable, "1"))) {
            return false;
        }
        return containsCheckFragment(
                        constraints, "durable_input_fact_sequence_ck", "last_accepted_sequence >= 0")
                && containsCheckFragment(
                        constraints, "durable_input_fact_duration_ck", "audio_duration_ms >= 0")
                && containsCheckFragment(
                        constraints, "durable_input_fact_input_binding_ck",
                        "length(btrim(input_binding)) > 0")
                && containsCheckFragment(
                        constraints, "durable_input_fact_source_language_ck",
                        "length(btrim(source_language)) > 0")
                && containsCheckFragment(
                        constraints, "durable_input_fact_target_languages_ck",
                        "array_position(target_languages")
                && containsCheckFragment(
                        constraints, "durable_input_fact_content_digest_ck",
                        "length(btrim(content_digest)) > 0");
    }

    /**
     * 只读核对 V009 final usage fact 表的冻结物理契约。
     *
     * <p>该探针精确检查六列、一对一主键、meeting 的 RESTRICT 外键，以及首版
     * 标准单位、非负金额和来源字段非空约束。它不读取业务行，也不创建或修复
     * schema 对象。</p>
     */
    private static boolean hasExactFinalUsageFactSchema(Connection connection)
            throws SQLException {
        Long factTable = relationOid(connection, "final_usage_fact");
        Long meetingTable = relationOid(connection, "meeting");
        if (factTable == null || meetingTable == null
                || !isStandalonePermanentTable(connection, factTable)) {
            return false;
        }

        List<ColumnContract> actualColumns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.attname,
                       format_type(a.atttypid, a.atttypmod) AS data_type,
                       a.attnotnull,
                       pg_get_expr(d.adbin, d.adrelid) AS default_expression
                  FROM pg_attribute a
                  LEFT JOIN pg_attrdef d
                    ON d.adrelid = a.attrelid
                   AND d.adnum = a.attnum
                 WHERE a.attrelid = ?
                   AND a.attnum > 0
                   AND NOT a.attisdropped
                 ORDER BY a.attnum
                """)) {
            statement.setLong(1, factTable);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    actualColumns.add(new ColumnContract(
                            rows.getString("attname"),
                            rows.getString("data_type"),
                            rows.getBoolean("attnotnull"),
                            rows.getString("default_expression")));
                }
            }
        }
        if (!FINAL_USAGE_FACT_COLUMNS.equals(actualColumns)) {
            return false;
        }

        List<ConstraintContract> constraints = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conname,
                       contype,
                       convalidated,
                       coalesce(array_to_string(conkey, ','), '') AS source_keys,
                       confrelid::bigint AS referenced_relation,
                       coalesce(array_to_string(confkey, ','), '') AS target_keys,
                       confdeltype,
                       pg_get_constraintdef(oid, TRUE) AS definition
                  FROM pg_constraint
                 WHERE conrelid = ?
                 ORDER BY conname
                """)) {
            statement.setLong(1, factTable);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    constraints.add(new ConstraintContract(
                            rows.getString("conname"),
                            rows.getString("contype"),
                            rows.getBoolean("convalidated"),
                            rows.getString("source_keys"),
                            rows.getLong("referenced_relation"),
                            rows.getString("target_keys"),
                            rows.getString("confdeltype"),
                            rows.getString("definition")));
                }
            }
        }
        if (constraints.size() != 6
                || !constraints.contains(ConstraintContract.primaryKey(
                        "final_usage_fact_pkey", "1"))
                || !constraints.contains(ConstraintContract.foreignKey(
                        "final_usage_fact_meeting_fk", "1", meetingTable, "1"))) {
            return false;
        }
        return containsExactCheck(
                        constraints, "final_usage_fact_actual_unit_ck",
                        FINAL_USAGE_UNIT_CHECK)
                && containsExactCheck(
                        constraints, "final_usage_fact_actual_amount_ck",
                        FINAL_USAGE_AMOUNT_CHECK)
                && containsExactCheck(
                        constraints, "final_usage_fact_source_input_binding_ck",
                        FINAL_USAGE_BINDING_CHECK)
                && containsExactCheck(
                        constraints, "final_usage_fact_source_input_digest_ck",
                        FINAL_USAGE_DIGEST_CHECK);
    }

    /**
     * 只读核对 V010 workflow_step 的新增 checkpoint、租约列和关闭式 CHECK。
     * 不读取业务行、不创建或修补表；同名半表、可空定义或缺少状态约束均不通过。
     */
    private static boolean hasExactWorkflowStepSchema(Connection connection)
            throws SQLException {
        Long table = relationOid(connection, "workflow_step");
        Long meetingTable = relationOid(connection, "meeting");
        if (table == null || meetingTable == null
                || !isStandalonePermanentTable(connection, table)
                || !isStandalonePermanentTable(connection, meetingTable)) {
            return false;
        }
        if (!hasExactWorkflowStepIdentity(connection, table)) {
            return false;
        }
        List<ColumnContract> actual = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS data_type,
                       a.attnotnull, pg_get_expr(d.adbin, d.adrelid) AS default_expression
                  FROM pg_attribute a
                  LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                 WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
                   AND a.attname IN ('meeting_id','step_type','step_key','checkpoint_type',
                       'canonical_payload','content_digest','initial_available_at','status','attempt_count',
                       'next_attempt_at','lease_owner','lease_until','lease_token',
                       'lease_version','error_code','error_message','updated_at')
                 ORDER BY array_position(ARRAY['meeting_id','step_type','step_key',
                       'checkpoint_type','canonical_payload','content_digest','initial_available_at','status',
                       'attempt_count','next_attempt_at','lease_owner','lease_until',
                       'lease_token','lease_version','error_code','error_message',
                       'updated_at']::name[], a.attname)
                """)) {
            statement.setLong(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    actual.add(new ColumnContract(rows.getString("attname"),
                            rows.getString("data_type"), rows.getBoolean("attnotnull"),
                            rows.getString("default_expression")));
                }
            }
        }
        if (!actual.equals(WORKFLOW_STEP_COLUMNS)) return false;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conname, contype, convalidated,
                       coalesce(array_to_string(conkey, ','), '') AS source_keys,
                       confrelid::bigint AS referenced_relation,
                       coalesce(array_to_string(confkey, ','), '') AS target_keys,
                       confdeltype,
                       pg_get_constraintdef(oid, TRUE) AS definition
                  FROM pg_constraint WHERE conrelid = ?
                """)) {
            statement.setLong(1, table);
            Set<String> checks = new HashSet<>();
            boolean stableKeyUnique = false;
            boolean meetingForeignKey = false;
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString("conname");
                    String type = rows.getString("contype");
                    boolean validated = rows.getBoolean("convalidated");
                    String definition = normalizeDefinition(rows.getString("definition"));
                    if ("c".equals(type) && validated) {
                        if ("workflow_step_status_ck".equals(name)
                                && definition.equals(normalizeDefinition(
                                WORKFLOW_STEP_STATUS_CHECK))) {
                            checks.add("status");
                        }
                        if ("workflow_step_definition_ck".equals(name)
                                && definition.equals(normalizeDefinition(
                                WORKFLOW_STEP_DEFINITION_CHECK))) {
                            checks.add("definition");
                        }
                        if ("workflow_step_lease_token_ck".equals(name)
                                && definition.equals(normalizeDefinition(
                                WORKFLOW_STEP_LEASE_TOKEN_CHECK))) {
                            checks.add("token");
                        }
                        if ("workflow_step_attempt_count_check".equals(name)
                                && definition.equals(normalizeDefinition(
                                WORKFLOW_STEP_ATTEMPT_CHECK))) {
                            checks.add("attempt");
                        }
                        if ("workflow_step_facts_ck".equals(name)
                                && definition.equals(normalizeDefinition(
                                WORKFLOW_STEP_FACTS_CHECK))) {
                            checks.add("facts");
                        }
                    }
                    if ("workflow_step_key_uq".equals(name)
                            && "u".equals(type)
                            && validated
                            && "2,3,4".equals(rows.getString("source_keys"))
                            && definition.equals(
                            "UNIQUE (meeting_id, step_type, step_key)")) {
                        stableKeyUnique = true;
                    }
                    if ("workflow_step_meeting_id_fkey".equals(name)
                            && "f".equals(type)
                            && validated
                            && "2".equals(rows.getString("source_keys"))
                            && rows.getLong("referenced_relation") == meetingTable
                            && "1".equals(rows.getString("target_keys"))
                            && "a".equals(rows.getString("confdeltype"))
                            && definition.equals(
                            "FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id)")) {
                        meetingForeignKey = true;
                    }
                }
            }
            return checks.size() == 5 && stableKeyUnique && meetingForeignKey;
        }
    }

    /**
     * 只读核对 V011 最终收口执行事实的四列与四个约束。
     *
     * <p>document CHECK 必须关闭根字段并完整约束 specVersion、meetingId、微秒
     * startedAt、nullable title、Profile/Plan 对象及 canonical JSON 镜像；hash CHECK
     * 必须继续使用 PostgreSQL 16 核心 SHA-256。缺表、view、额外列/约束、NOT VALID
     * 或同名弱 CHECK 均返回 false，且本探针不读取业务行。</p>
     */
    private static boolean hasExactFinalizationExecutionFactSchema(Connection connection)
            throws SQLException {
        Long factTable = relationOid(connection, "finalization_execution_fact");
        Long meetingTable = relationOid(connection, "meeting");
        if (factTable == null || meetingTable == null
                || !isStandalonePermanentTable(connection, factTable)
                || !isStandalonePermanentTable(connection, meetingTable)) {
            return false;
        }

        List<ColumnContract> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS data_type,
                       a.attnotnull, pg_get_expr(d.adbin, d.adrelid) AS default_expression
                  FROM pg_attribute a
                  LEFT JOIN pg_attrdef d
                    ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                 WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
                 ORDER BY a.attnum
                """)) {
            statement.setLong(1, factTable);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(new ColumnContract(
                            rows.getString("attname"), rows.getString("data_type"),
                            rows.getBoolean("attnotnull"),
                            rows.getString("default_expression")));
                }
            }
        }
        if (!FINALIZATION_EXECUTION_FACT_COLUMNS.equals(columns)) {
            return false;
        }

        List<ConstraintContract> constraints = readConstraints(connection, factTable);
        return constraints.size() == 4
                && constraints.contains(ConstraintContract.primaryKey(
                        "finalization_execution_fact_pkey", "1"))
                && constraints.contains(ConstraintContract.foreignKey(
                        "finalization_execution_fact_meeting_fk",
                        "1", meetingTable, "1"))
                && containsExactCheck(
                        constraints,
                        "finalization_execution_fact_document_ck",
                        FINALIZATION_EXECUTION_DOCUMENT_CHECK)
                && containsExactCheck(
                        constraints,
                        "finalization_execution_fact_hash_ck",
                        FINALIZATION_EXECUTION_HASH_CHECK);
    }

    /**
     * 只读核对 V011 READY-only artifact 的十一列与十个约束。
     *
     * <p>存在一行即表示 READY，因此不接受 status、正文、checkpoint、Profile、租约、
     * Manifest、URL、路径、凭据或开放式 metadata 列。三个唯一身份、RESTRICT 外键、
     * 五个完整且 validated 的 CHECK 及 recorded_at 默认值必须全部精确匹配。</p>
     */
    private static boolean hasExactReadyArtifactSchema(Connection connection)
            throws SQLException {
        Long artifactTable = relationOid(connection, "artifact");
        Long meetingTable = relationOid(connection, "meeting");
        if (artifactTable == null || meetingTable == null
                || !isStandalonePermanentTable(connection, artifactTable)
                || !isStandalonePermanentTable(connection, meetingTable)) {
            return false;
        }

        List<ColumnContract> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS data_type,
                       a.attnotnull, pg_get_expr(d.adbin, d.adrelid) AS default_expression
                  FROM pg_attribute a
                  LEFT JOIN pg_attrdef d
                    ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                 WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
                 ORDER BY a.attnum
                """)) {
            statement.setLong(1, artifactTable);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(new ColumnContract(
                            rows.getString("attname"), rows.getString("data_type"),
                            rows.getBoolean("attnotnull"),
                            rows.getString("default_expression")));
                }
            }
        }
        if (!READY_ARTIFACT_COLUMNS.equals(columns)) {
            return false;
        }

        List<ConstraintContract> constraints = readConstraints(connection, artifactTable);
        return constraints.size() == 10
                && constraints.contains(ConstraintContract.primaryKey(
                        "artifact_pkey", "1"))
                && constraints.contains(ConstraintContract.foreignKey(
                        "artifact_meeting_fk", "2", meetingTable, "1"))
                && constraints.contains(ConstraintContract.uniqueKey(
                        "artifact_revision_uq", "2,3,6",
                        "UNIQUE (meeting_id, artifact_type, revision)"))
                && constraints.contains(ConstraintContract.uniqueKey(
                        "artifact_intent_id_uq", "7", "UNIQUE (intent_id)"))
                && constraints.contains(ConstraintContract.uniqueKey(
                        "artifact_storage_binding_uq", "8",
                        "UNIQUE (storage_binding)"))
                && containsExactCheck(
                        constraints, "artifact_text_ck", READY_ARTIFACT_TEXT_CHECK)
                && containsExactCheck(
                        constraints, "artifact_language_ck", READY_ARTIFACT_LANGUAGE_CHECK)
                && containsExactCheck(
                        constraints, "artifact_revision_ck", READY_ARTIFACT_REVISION_CHECK)
                && containsExactCheck(
                        constraints, "artifact_content_size_ck", READY_ARTIFACT_SIZE_CHECK)
                && containsExactCheck(
                        constraints,
                        "artifact_content_checksum_ck",
                        READY_ARTIFACT_CHECKSUM_CHECK);
    }

    /** 读取指定当前-schema 表的完整约束 catalog；调用方必须再锁定约束总数。 */
    private static List<ConstraintContract> readConstraints(
            Connection connection,
            long table) throws SQLException {
        List<ConstraintContract> constraints = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conname, contype, convalidated,
                       coalesce(array_to_string(conkey, ','), '') AS source_keys,
                       confrelid::bigint AS referenced_relation,
                       coalesce(array_to_string(confkey, ','), '') AS target_keys,
                       confdeltype,
                       pg_get_constraintdef(oid, TRUE) AS definition
                  FROM pg_constraint
                 WHERE conrelid = ?
                 ORDER BY conname
                """)) {
            statement.setLong(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    constraints.add(new ConstraintContract(
                            rows.getString("conname"),
                            rows.getString("contype"),
                            rows.getBoolean("convalidated"),
                            rows.getString("source_keys"),
                            rows.getLong("referenced_relation"),
                            rows.getString("target_keys"),
                            rows.getString("confdeltype"),
                            rows.getString("definition")));
                }
            }
        }
        return constraints;
    }

    /**
     * 核对 Store 省略 {@code step_id} 写入时依赖的 V001 identity 生成能力。
     *
     * <p>{@code attidentity='d'} 是 PostgreSQL 对
     * {@code GENERATED BY DEFAULT AS IDENTITY} 的物理标记。仅保留 bigint/NOT NULL
     * 而执行过 {@code DROP IDENTITY} 的列无法完成 Store INSERT，必须判定 schema
     * 不可用。surrogate 主键本身不参与 Store 的幂等、查找或 fencing；
     * {@code workflow_step_claim_v010_idx} 也只是性能索引，二者不作为本 readiness
     * 的正确性门禁。</p>
     */
    private static boolean hasExactWorkflowStepIdentity(
            Connection connection,
            long table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT format_type(a.atttypid, a.atttypmod) AS data_type,
                       a.attnotnull, a.attidentity, a.attgenerated,
                       pg_get_expr(d.adbin, d.adrelid) AS default_expression
                  FROM pg_attribute a
                  LEFT JOIN pg_attrdef d
                    ON d.adrelid = a.attrelid
                   AND d.adnum = a.attnum
                 WHERE a.attrelid = ?
                   AND a.attname = 'step_id'
                   AND a.attnum > 0
                   AND NOT a.attisdropped
                """)) {
            statement.setLong(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        && "bigint".equals(rows.getString("data_type"))
                        && rows.getBoolean("attnotnull")
                        && "d".equals(rows.getString("attidentity"))
                        && "".equals(rows.getString("attgenerated"))
                        && rows.getString("default_expression") == null
                        && !rows.next();
            }
        }
    }

    /**
     * CHECK 的 {@code conkey} 是 PostgreSQL 从表达式推导出的引用列集合，不是契约
     * 输入，并可能随等价表达式内部遍历变化。物理门禁因此精确比较名称、类型、
     * validated 状态和完整定义，列级语义已经包含在完整定义中。
     */
    private static boolean containsExactCheck(
            List<ConstraintContract> constraints,
            String name,
            String definition) {
        String normalized = normalizeDefinition(definition);
        return constraints.stream().anyMatch(constraint ->
                constraint.name().equals(name)
                        && constraint.type().equals("c")
                        && constraint.validated()
                && constraint.definition().equals(normalized));
    }

    private static boolean containsCheckFragment(
            List<ConstraintContract> constraints,
            String name,
            String fragment) {
        String normalizedFragment = normalizeDefinition(fragment);
        return constraints.stream().anyMatch(constraint ->
                constraint.name().equals(name)
                        && constraint.type().equals("c")
                        && constraint.validated()
                        && constraint.definition().contains(normalizedFragment));
    }

    /** 返回当前 schema 中的精确关系 OID；不沿 search_path 接受其它 schema。 */
    private static Long relationOid(Connection connection, String relation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.oid::bigint
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = current_schema()
                   AND c.relname = ?
                """)) {
            statement.setString(1, relation);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    /**
     * 只接受当前 schema 中独立的普通持久表。
     *
     * <p>partition leaf 虽然也是 {@code relkind='r'}，但不能冒充独立事实表；
     * UNLOGGED/TEMP 表不满足崩溃恢复承诺；传统 {@code INHERITS} 的子表又不
     * 继承父表 PK/UQ/FK，会让普通父表查询看到绕过约束的行。因此同时
     * 排除分区、非持久和任何父/子继承关系。</p>
     */
    private static boolean isStandalonePermanentTable(
            Connection connection,
            long relationOid)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.relkind = 'r'
                       AND c.relpersistence = 'p'
                       AND NOT c.relispartition
                       AND NOT EXISTS (
                           SELECT 1 FROM pg_inherits i
                            WHERE i.inhrelid = c.oid OR i.inhparent = c.oid)
                  FROM pg_class c
                 WHERE c.oid = ?
                """)) {
            statement.setLong(1, relationOid);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static String normalizeDefinition(String definition) {
        return definition == null ? "" : definition.replaceAll("\\s+", " ").trim();
    }

    /**
     * 生成与 Java 21 {@link String#isBlank()} 对齐的 PostgreSQL 非空白表达式。
     *
     * <p>不使用 {@code btrim(text)}，因为它默认只移除 U+0020，会把
     * TAB/LF/CR 等 Java 空白字符错当成有效业务身份。这个方法只用于构造
     * 启动时的固定 catalog 指纹，不接收数据库或外部输入。</p>
     */
    private static String hasNonBlankSql(String column) {
        return "length(translate(" + column + ", " + JAVA_21_BLANK_SQL
                + ", ''::text)) > 0";
    }

    private record ColumnContract(
            String name,
            String dataType,
            boolean notNull,
            String defaultExpression) {
    }

    private record ConstraintContract(
            String name,
            String type,
            boolean validated,
            String sourceKeys,
            long referencedRelation,
            String targetKeys,
            String deleteAction,
            String definition) {

        private ConstraintContract {
            definition = normalizeDefinition(definition);
        }

        private static ConstraintContract primaryKey(String name, String sourceKeys) {
            String definition = switch (name) {
                case "meeting_manifest_revision_pkey" ->
                        "PRIMARY KEY (meeting_id, manifest_revision)";
                case "durable_input_fact_pkey" -> "PRIMARY KEY (meeting_id)";
                case "final_usage_fact_pkey" -> "PRIMARY KEY (meeting_id)";
                case "finalization_execution_fact_pkey" -> "PRIMARY KEY (meeting_id)";
                case "artifact_pkey" -> "PRIMARY KEY (artifact_id)";
                default -> throw new IllegalArgumentException("unknown primary key contract");
            };
            return new ConstraintContract(
                    name, "p", true, sourceKeys, 0L, "", " ", definition);
        }

        private static ConstraintContract foreignKey(
                String name,
                String sourceKeys,
                long referencedRelation,
                String targetKeys) {
            String definition = switch (name) {
                case "meeting_manifest_revision_meeting_fk" ->
                        "FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT";
                case "meeting_manifest_revision_previous_fk" -> """
                        FOREIGN KEY (meeting_id, previous_revision)
                        REFERENCES meeting_manifest_revision(meeting_id, manifest_revision)
                        ON DELETE RESTRICT
                        """;
                case "durable_input_fact_meeting_fk" ->
                        "FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT";
                case "final_usage_fact_meeting_fk" ->
                        "FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT";
                case "finalization_execution_fact_meeting_fk" ->
                        "FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT";
                case "artifact_meeting_fk" ->
                        "FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT";
                default -> throw new IllegalArgumentException("unknown foreign key contract");
            };
            return new ConstraintContract(
                    name, "f", true, sourceKeys, referencedRelation, targetKeys, "r", definition);
        }

        private static ConstraintContract uniqueKey(
                String name,
                String sourceKeys,
                String definition) {
            return new ConstraintContract(
                    name, "u", true, sourceKeys, 0L, "", " ", definition);
        }
    }

    private static void closeQuietly(HikariDataSource pool) {
        try {
            pool.close();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "关闭数据库连接池失败 exception={0}",
                    failure.getClass().getSimpleName());
        }
    }
}
