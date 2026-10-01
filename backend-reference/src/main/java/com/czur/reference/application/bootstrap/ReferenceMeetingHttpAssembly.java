package com.czur.reference.application.bootstrap;

import com.czur.reference.application.command.AuthenticatedMeetingStopApplicationService;
import com.czur.reference.application.http.AuthenticatedMeetingHttpContextResolver;
import com.czur.reference.application.http.HttpErrorMapper;
import com.czur.reference.application.http.MeetingHttpRuntime;
import com.czur.reference.application.http.ReferenceMeetingHttpRequestDecoder;
import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityProvider;
import com.czur.reference.application.persistence.jdbc.JdbcDatabaseRuntime;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingDeletionAdmissionUnitOfWork;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingQueryPort;
import com.czur.reference.application.persistence.jdbc.JdbcOwnedMeetingStopUnitOfWork;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 关闭 Create 的参考会议 HTTP 装配根，拥有唯一 JDBC 池与请求运行时的生命周期。
 *
 * <p>启动前从一份可信配置完成交叉校验；数据库运行时只执行 V001～V013 的只读
 * readiness，不自动迁移或回退内存端口。查询、认证 Stop 与 T14 删除受理从同一
 * DataSource 装配。Create 请求在任何身份、权益、V013 写入或会话分配前返回
 * 501。本对象不创建网络监听器、Worker、模型或存储适配器。</p>
 *
 * <p>调用方应先停止外层网络接收，再关闭本对象；关闭顺序为 HTTP 运行时、
 * JDBC 池，重复关闭安全。返回的组件供调用方接线，不转移资源所有权。</p>
 */
public final class ReferenceMeetingHttpAssembly implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(
            ReferenceMeetingHttpAssembly.class.getName());

    private final JdbcDatabaseRuntime database;
    private final MeetingHttpRuntime runtime;
    private final AuthenticatedMeetingHttpContextResolver contextResolver;
    private final HttpErrorMapper errors;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ReferenceMeetingHttpAssembly(
            JdbcDatabaseRuntime database, MeetingHttpRuntime runtime,
            AuthenticatedMeetingHttpContextResolver contextResolver,
            HttpErrorMapper errors) {
        this.database = database;
        this.runtime = runtime;
        this.contextResolver = contextResolver;
        this.errors = errors;
    }

    /**
     * 从调用方提供的映射仅解析一次可信配置，然后装配关闭态运行时。
     *
     * @param environment 部署注入的配置，调用期间不得并发修改
     * @param clock 身份有效期与 Stop/Delete 事实共用的服务端时钟
     * @return 已通过数据库 readiness、可直接调用的 transport-neutral 装配
     * @throws IllegalArgumentException 配置不合法或 Create 已开启；不创建连接池
     * @throws com.czur.reference.application.persistence.jdbc.DatabaseStartupException
     *         数据库不可用或迁移/物理契约不满足；已创建的池会关闭
     */
    public static ReferenceMeetingHttpAssembly start(
            Map<String, String> environment, Clock clock) {
        return start(ReferenceMeetingDeploymentConfig.fromEnvironment(environment), clock);
    }

    /** 复用调用方已冻结的配置；仅允许 Create 关闭，启用装配留给后续子步。 */
    public static ReferenceMeetingHttpAssembly start(
            ReferenceMeetingDeploymentConfig config, Clock clock) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        if (config.createEnabled()) {
            throw new IllegalArgumentException("Create assembly is not available");
        }

        JdbcDatabaseRuntime database = JdbcDatabaseRuntime.start(config.database());
        MeetingHttpRuntime runtime = null;
        try {
            DataSource dataSource = database.dataSource();
            byte[] pageSecret = config.pageTokenSecretCopy();
            JdbcMeetingQueryPort query;
            try {
                query = new JdbcMeetingQueryPort(dataSource, pageSecret);
            } finally {
                Arrays.fill(pageSecret, (byte) 0);
            }
            var stop = new JdbcOwnedMeetingStopUnitOfWork(dataSource, clock);
            var deletion = new JdbcMeetingDeletionAdmissionUnitOfWork(dataSource, clock);
            var identity = new ReferenceDevIdentityProvider(config.identity(), clock);
            var errors = new HttpErrorMapper();
            var decoder = new ReferenceMeetingHttpRequestDecoder(
                    config.audience(), config.clientId());
            var resolver = new AuthenticatedMeetingHttpContextResolver(
                    config.deploymentScope(), config.audience(), config.clientId(),
                    identity, errors);
            var stopService = new AuthenticatedMeetingStopApplicationService(
                    config.deploymentScope(), identity, stop, query);
            runtime = new MeetingHttpRuntime(query, deletion, decoder, errors, stopService);
            LOGGER.log(Level.INFO, "reference meeting HTTP 关闭态装配完成 createEnabled=false");
            return new ReferenceMeetingHttpAssembly(database, runtime, resolver, errors);
        } catch (RuntimeException | Error failure) {
            try {
                if (runtime != null) {
                    runtime.close();
                }
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                database.close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            LOGGER.log(Level.WARNING, "reference meeting HTTP 装配失败 reason={0}",
                    failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** 返回已装配的 transport-neutral 运行时；调用方不得单独关闭。 */
    public MeetingHttpRuntime runtime() { return runtime; }

    /** 返回 GET/list/DELETE 的可信身份上下文解析器。 */
    public AuthenticatedMeetingHttpContextResolver contextResolver() {
        return contextResolver;
    }

    /** 返回网络入口和运行时共用的唯一错误映射器。 */
    public HttpErrorMapper errorMapper() { return errors; }

    /** 返回已通过启动 readiness 的数据库运行时；调用方不得单独关闭。 */
    public JdbcDatabaseRuntime databaseRuntime() { return database; }

    /** 幂等地先关闭请求入口，再关闭唯一 JDBC 池；不删除任何数据库事实。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                runtime.close();
            } finally {
                database.close();
            }
            LOGGER.log(Level.INFO, "reference meeting HTTP 装配已关闭");
        }
    }
}
