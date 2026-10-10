package com.czur.reference.application.persistence.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 公共 JDBC 连接上下文：唯一连接池、数据库引擎和固定表映射。
 *
 * <p>{@link #start(DatabaseRuntimeConfig)} 只证明连接及当前 V001 的最小基线可用，
 * 不装配会议 Store，也不证明 Create／Stop／Worker 已支持该引擎。M1 的 MySQL
 * 调用者仅使用本入口；完整会议入口 {@link JdbcDatabaseRuntime} 仍明确限定 PG。
 * 后续持久层适配应复用此上下文的同一数据源，不另建第二连接池或状态机。</p>
 *
 * <p>启动和基线检查均只读，不自动建表、登记迁移或修补结构。MySQL 使用固定
 * {@code ai_trans_*} 表、UTC 会话及 utf8mb4；PG 保留原 URL 和驱动参数语义。
 * 关闭上下文只关闭连接池，重复关闭安全。</p>
 */
public final class JdbcDatabaseContext implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(JdbcDatabaseContext.class.getName());

    private final DatabaseRuntimeConfig config;
    private final HikariDataSource pool;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 接管已经创建的唯一池；供旧 PG runtime 保持原构造入口和生命周期。 */
    JdbcDatabaseContext(DatabaseRuntimeConfig config, HikariDataSource pool) {
        this.config = Objects.requireNonNull(config, "config");
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    /**
     * 创建连接上下文并检查当前 V001 的最小只读基线。
     *
     * @param config 已校验且不含可变连接状态的配置
     * @return 连接及基线通过的上下文；不提供会议业务可用性保证
     * @throws DatabaseStartupException 连接或基线未通过；本方法先关闭已创建的池
     */
    public static JdbcDatabaseContext start(DatabaseRuntimeConfig config) {
        Objects.requireNonNull(config, "config");
        JdbcDatabaseContext context = null;
        try {
            context = new JdbcDatabaseContext(config, createPool(config));
            DatabaseReadiness readiness = context.checkBaselineReadiness();
            if (!readiness.ready()) {
                context.close();
                LOGGER.log(Level.WARNING, "数据库连接基线未通过 engine={0} status={1}",
                        new Object[]{config.engine(), readiness.status()});
                throw new DatabaseStartupException(readiness);
            }
            LOGGER.log(Level.INFO, "数据库连接基线通过 engine={0} pool={1}",
                    new Object[]{config.engine(), config});
            return context;
        } catch (DatabaseStartupException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            if (context != null) {
                context.close();
            }
            LOGGER.log(Level.WARNING, "数据库连接初始化异常 engine={0} exception={1}",
                    new Object[]{config.engine(), failure.getClass().getSimpleName()});
            throw new DatabaseStartupException(
                    DatabaseReadiness.unavailable("数据库连接上下文初始化异常"));
        }
    }

    /**
     * 只读检查连接、V001 登记和固定表存在；MySQL 另核对必要表为 InnoDB。
     * 返回 READY 仅表示连接与最小基线，不表示会议 Store 已完成引擎适配。
     */
    public DatabaseReadiness checkBaselineReadiness() {
        if (closed.get()) {
            return DatabaseReadiness.closed();
        }
        return JdbcDatabaseBaseline.check(pool, engine());
    }

    /** 返回同一池的数据源；连接由调用者关闭，上下文由装配者统一关闭。 */
    public DataSource dataSource() {
        ensureLive();
        return pool;
    }

    /** 返回明确的数据库引擎，供公共持久层选择对应的 SQL／字段映射。 */
    public JdbcDatabaseEngine engine() {
        return config.engine();
    }

    /** 返回固定逻辑表对应的物理名称，不接受任意表名或可配置前缀。 */
    public String table(JdbcDatabaseTable table) {
        return Objects.requireNonNull(table, "table").physicalName(engine());
    }

    /** 返回启动配置；其诊断文本不包含账号、密码或完整连接地址。 */
    public DatabaseRuntimeConfig config() {
        return config;
    }

    /** 只表示连接上下文尚未关闭，不代替数据库探针或会议运行状态。 */
    public boolean isLive() {
        return !closed.get();
    }

    /** 幂等关闭唯一池，不执行数据库 DDL 或业务数据写入。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closePool(pool);
            LOGGER.log(Level.INFO, "数据库连接上下文已关闭 engine={0}", engine());
        }
    }

    /** 两种引擎共用唯一池装配点；旧 PG runtime 不增加额外连接池。 */
    static HikariDataSource createPool(DatabaseRuntimeConfig config) {
        return new HikariDataSource(poolConfiguration(config));
    }

    static HikariConfig poolConfiguration(DatabaseRuntimeConfig config) {
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
        if (config.engine() == JdbcDatabaseEngine.MYSQL) {
            hikari.setDriverClassName(config.engine().driverClassName());
            // Connector/J 的 socketTimeout 为毫秒，pgjdbc 为秒；配置入口仍统一用秒。
            hikari.addDataSourceProperty("socketTimeout", Long.toString(config.socketTimeout().toMillis()));
            hikari.addDataSourceProperty("characterEncoding", "UTF-8");
            hikari.addDataSourceProperty("connectionCollation", "utf8mb4_bin");
            hikari.addDataSourceProperty("connectionTimeZone", "UTC");
            hikari.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
            hikari.addDataSourceProperty("preserveInstants", "true");
        } else {
            hikari.addDataSourceProperty("socketTimeout", Long.toString(config.socketTimeout().toSeconds()));
        }
        return hikari;
    }

    static void closePool(HikariDataSource pool) {
        try {
            pool.close();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "关闭数据库连接池失败 exception={0}",
                    failure.getClass().getSimpleName());
        }
    }

    private void ensureLive() {
        if (closed.get()) {
            throw new IllegalStateException("数据库连接上下文已关闭，不能获取数据源");
        }
    }
}
