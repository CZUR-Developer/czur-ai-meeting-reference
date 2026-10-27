package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.MeetingUnitOfWork;
import com.czur.reference.application.port.CreateMeetingFactStore;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 旧的完整会议数据库运行时入口（已停用）。
 *
 * <p>参考交付只支持 MySQL 5.7 的客户端直连模型入口
 * （{@code MeetingHttpAssembly.startClientModel} 与 {@link JdbcDatabaseContext}）。
 * 本类代表更早的完整会议运行时装配，当前没有适配 MySQL 的实现：
 * {@link #start(DatabaseRuntimeConfig)} 在创建连接池之前无条件抛出
 * {@link DatabaseStartupException}，{@link #open(DatabaseRuntimeConfig)} 是同一
 * 行为的显式别名。</p>
 *
 * <p>该入口已停用，调用即抛；不会创建连接池、不会执行迁移或结构检查，也不会
 * 返回可用运行时。调用方应改用 {@link JdbcDatabaseContext} 和客户端会议入口。</p>
 */
public final class JdbcDatabaseRuntime implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(JdbcDatabaseRuntime.class.getName());

    private final DatabaseRuntimeConfig config;
    private final JdbcDatabaseContext databaseContext;
    private final HikariDataSource dataSource;
    private final JdbcMeetingUnitOfWork meetingUnitOfWork;
    private final JdbcOutboxStore outboxStore;
    private final JdbcWorkflowStepStore workflowStepStore;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private JdbcDatabaseRuntime(DatabaseRuntimeConfig config, HikariDataSource dataSource) {
        this.config = config;
        this.databaseContext = new JdbcDatabaseContext(config, dataSource);
        this.dataSource = dataSource;
        this.meetingUnitOfWork = new JdbcMeetingUnitOfWork(dataSource);
        this.outboxStore = new JdbcOutboxStore(dataSource);
        this.workflowStepStore = new JdbcWorkflowStepStore(dataSource);
    }

    /**
     * 按当前进程环境调用旧的完整运行时入口。
     *
     * <p>该入口已停用，调用即抛；配置校验仍由
     * {@link DatabaseRuntimeConfig#fromEnvironment()} 先行完成，不会创建连接池。</p>
     *
     * @throws IllegalArgumentException 配置非法
     * @throws DatabaseStartupException 无条件抛出，提示改用客户端会议入口
     */
    public static JdbcDatabaseRuntime start() {
        return start(DatabaseRuntimeConfig.fromEnvironment());
    }

    /**
     * 用显式配置调用旧的完整运行时入口。
     *
     * <p>该入口已停用，调用即抛；本方法不创建连接池，也不执行任何数据库检查。</p>
     *
     * @param config 已校验的数据库配置
     * @throws DatabaseStartupException 无条件抛出，提示改用客户端会议入口
     */
    public static JdbcDatabaseRuntime start(DatabaseRuntimeConfig config) {
        Objects.requireNonNull(config, "config");
        throw new DatabaseStartupException(DatabaseReadiness.invalidConfiguration(
                "完整会议 JDBC 运行时未适配 MySQL；请使用 ReferenceClientMeetingProcess 入口"));
    }

    /** 兼容装配代码的显式别名；行为与 {@link #start(DatabaseRuntimeConfig)} 一致。 */
    public static JdbcDatabaseRuntime open(DatabaseRuntimeConfig config) {
        return start(config);
    }

    /**
     * 旧的完整运行时 readiness 入口。
     *
     * <p>该入口已停用：完整运行时实例不可获得，本方法调用即抛，不执行任何数据库
     * 探针或结构检查。</p>
     *
     * @throws UnsupportedOperationException 无条件抛出
     */
    public DatabaseReadiness checkReadiness() {
        throw disabled();
    }

    /**
     * 旧的实时凭据 readiness 入口。
     *
     * <p>该入口已停用，调用即抛；实时凭据的结构检查由客户端会议入口负责。</p>
     *
     * @throws UnsupportedOperationException 无条件抛出
     */
    public DatabaseReadiness checkRealtimeCredentialReadiness() {
        throw disabled();
    }

    /**
     * 旧的持久音频 readiness 入口。
     *
     * <p>该入口已停用，调用即抛；本方法不读取音频业务行。</p>
     *
     * @throws UnsupportedOperationException 无条件抛出
     */
    public DatabaseReadiness checkDurableAudioReadiness() {
        throw disabled();
    }

    /**
     * liveness 只表示运行时对象尚未关闭，不代表数据库当前可连接。
     *
     * @return 未调用 close 前为 true
     */
    public boolean isLive() {
        return !closed.get();
    }

    /** liveness 的语义别名，便于装配层使用不绑定 HTTP 的健康模型。 */
    public boolean liveness() {
        return isLive();
    }

    /** 返回运行时持有的数据源；运行时关闭后禁止继续装配业务组件。 */
    public DataSource dataSource() {
        ensureLive();
        return dataSource;
    }

    /** 旧全功能运行时持有的同一 Context；不新建池。 */
    public JdbcDatabaseContext databaseContext() {
        ensureLive();
        return databaseContext;
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

    /**
     * 为调用方已经取得的显式事务连接创建 V013 事实原语。
     *
     * <p>该方法只装配 connection-scoped store，不从池中获取连接；调用方仍负责
     * {@code setAutoCommit(false)}、提交/回滚和关闭连接。</p>
     */
    public CreateMeetingFactStore createMeetingFactStore(Connection connection) {
        ensureLive();
        return new JdbcCreateMeetingFactStore(connection);
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
        databaseContext.close();
        LOGGER.log(Level.INFO, "数据库运行时已关闭");
    }

    private void ensureLive() {
        if (closed.get()) {
            throw new IllegalStateException("数据库运行时已关闭，不能获取业务组件");
        }
    }

    private static UnsupportedOperationException disabled() {
        return new UnsupportedOperationException(
                "该入口已停用：完整会议 JDBC 运行时未适配 MySQL，请使用客户端会议入口");
    }
}
