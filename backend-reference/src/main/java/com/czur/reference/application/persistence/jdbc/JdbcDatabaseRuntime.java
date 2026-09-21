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
 * {@code schema_migrations} 中的 V001～V007，再以只读 catalog 查询核对 V007
 * Manifest revision 表的精确物理契约。任一检查失败都会关闭已经创建的池并抛出
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
             WHERE version IN (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final Set<String> REQUIRED_MIGRATIONS = Set.of(
            "V001__initial_schema",
            "V002__outbox_lease_fencing",
            "V003__identity_entitlement",
            "V004__meeting_event_envelope",
            "V005__meeting_finalization_fact",
            "V006__meeting_read_model",
            "V007__meeting_manifest_revision");
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

    private final DatabaseRuntimeConfig config;
    private final HikariDataSource dataSource;
    private final JdbcMeetingUnitOfWork meetingUnitOfWork;
    private final JdbcOutboxStore outboxStore;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private JdbcDatabaseRuntime(DatabaseRuntimeConfig config, HikariDataSource dataSource) {
        this.config = config;
        this.dataSource = dataSource;
        this.meetingUnitOfWork = new JdbcMeetingUnitOfWork(dataSource);
        this.outboxStore = new JdbcOutboxStore(dataSource);
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
            if (!hasExactManifestRevisionSchema(connection)) {
                DatabaseReadiness invalid = DatabaseReadiness.schemaInvalid(
                        "meeting_manifest_revision catalog contract mismatch");
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
                || !isOrdinaryTable(connection, revisionTable)) {
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

    private static boolean isOrdinaryTable(Connection connection, long relationOid)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT relkind = 'r' FROM pg_class WHERE oid = ?")) {
            statement.setLong(1, relationOid);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static String normalizeDefinition(String definition) {
        return definition == null ? "" : definition.replaceAll("\\s+", " ").trim();
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
            return new ConstraintContract(
                    name, "p", true, sourceKeys, 0L, "", " ",
                    "PRIMARY KEY (meeting_id, manifest_revision)");
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
                default -> throw new IllegalArgumentException("unknown foreign key contract");
            };
            return new ConstraintContract(
                    name, "f", true, sourceKeys, referencedRelation, targetKeys, "r", definition);
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
