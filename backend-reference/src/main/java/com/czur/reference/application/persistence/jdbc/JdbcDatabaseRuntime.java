package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.MeetingUnitOfWork;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PostgreSQL 数据库运行时边界。
 *
 * <p>启动时创建唯一的 HikariCP 连接池，执行只读 {@code SELECT 1} 探针并检查
 * {@code schema_migrations} 中的 V001～V005。任一检查失败都会关闭已经创建的池并
 * 抛出 {@link DatabaseStartupException}；本类没有 InMemory fallback，也不会自动
 * 执行迁移。成功启动后，所有 JDBC Repository、会议工作单元和提交后的 Outbox
 * Worker 端口都共享同一个 {@link DataSource}。</p>
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
             WHERE version IN (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final Set<String> REQUIRED_MIGRATIONS = Set.of(
            "V001__initial_schema",
            "V002__outbox_lease_fencing",
            "V003__identity_entitlement",
            "V004__meeting_event_envelope",
            "V005__meeting_finalization_fact");

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
                // P2-T09 以前的开发脚本可能只把 V001/V002 登记为短版本；继续
                // 识别这两个已存在别名，但新迁移只接受完整不可歧义的文件名。
                migrations.setString(6, "V001");
                migrations.setString(7, "V002");
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

    private static void closeQuietly(HikariDataSource pool) {
        try {
            pool.close();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "关闭数据库连接池失败 exception={0}",
                    failure.getClass().getSimpleName());
        }
    }
}
