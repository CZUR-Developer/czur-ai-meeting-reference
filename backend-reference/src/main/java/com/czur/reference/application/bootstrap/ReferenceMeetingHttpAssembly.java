package com.czur.reference.application.bootstrap;

import com.czur.reference.application.command.AuthenticatedCompleteMeetingApplicationService;
import com.czur.reference.application.command.AuthenticatedMeetingStopApplicationService;
import com.czur.reference.application.command.CompleteMeetingResponseAssembler;
import com.czur.reference.application.command.ReferenceCreateRealtimeSessionAllocator;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicy;
import com.czur.reference.application.http.AuthenticatedMeetingHttpContextResolver;
import com.czur.reference.application.http.CreateMeetingHttpRequestDecoder;
import com.czur.reference.application.http.HttpErrorMapper;
import com.czur.reference.application.http.MeetingJdkHttpServer;
import com.czur.reference.application.http.MeetingHttpRuntime;
import com.czur.reference.application.http.ReferenceMeetingHttpRequestDecoder;
import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityProvider;
import com.czur.reference.application.persistence.jdbc.JdbcDatabaseRuntime;
import com.czur.reference.application.persistence.jdbc.JdbcCompleteMeetingCreateReplayLookup;
import com.czur.reference.application.persistence.jdbc.JdbcCompleteMeetingCreationUnitOfWork;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingDeletionAdmissionUnitOfWork;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingQueryPort;
import com.czur.reference.application.persistence.jdbc.JdbcOwnedMeetingStopUnitOfWork;
import com.czur.reference.application.persistence.jdbc.JdbcReferenceDevEntitlementProvider;
import com.czur.reference.application.persistence.jdbc.JdbcUsageReserveLookup;
import com.czur.reference.application.port.MeetingIdGenerator;
import com.czur.reference.application.port.ProductProfileCatalog;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.domain.capability.CapabilityPlanner;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 参考会议 HTTP 装配根，拥有唯一 JDBC 池与请求运行时的生命周期。
 *
 * <p>启动前从一份可信配置完成交叉校验；数据库运行时只执行 V001～V013 的只读
 * readiness，不自动迁移或回退内存端口。查询、认证 Stop 与 T14 删除受理从同一
 * DataSource 装配。只有可信配置显式开启 Create 才接入 V013 重放、权益与创建
 * 工作单元；关闭时请求在任何身份、权益、V013 写入或会话分配前返回 501。
 * 本对象不创建网络监听器、Worker、模型或存储适配器。</p>
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
     * 从调用方提供的映射仅解析一次可信配置，然后装配请求运行时。
     *
     * @param environment 部署注入的配置，调用期间不得并发修改
     * @param clock 身份有效期与 Stop/Delete 事实共用的服务端时钟
     * @return 已通过数据库 readiness、可直接调用的 transport-neutral 装配
     * @throws IllegalArgumentException 配置不合法；不创建连接池
     * @throws com.czur.reference.application.persistence.jdbc.DatabaseStartupException
     *         数据库不可用或迁移/物理契约不满足；已创建的池会关闭
     */
    public static ReferenceMeetingHttpAssembly start(
            Map<String, String> environment, Clock clock) {
        return start(ReferenceMeetingDeploymentConfig.fromEnvironment(environment), clock);
    }

    /** 复用调用方已冻结的配置；显式 Create 开关决定是否构造创建依赖。 */
    public static ReferenceMeetingHttpAssembly start(
            ReferenceMeetingDeploymentConfig config, Clock clock) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
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
            if (config.createEnabled()) {
                // 与查询、Stop、Delete 共用唯一连接池、身份 Provider、Clock 和错误映射。
                // ID 仅由服务端安全随机源生成；首次公开响应由 V013 持久化。
                SecureRandom random = new SecureRandom();
                RandomIds ids = new RandomIds(random);
                var policy = new ReferenceDevEntitlementPolicy(
                        config.entitlement(), clock, ids);
                var entitlement = new JdbcReferenceDevEntitlementProvider(
                        dataSource, policy, clock);
                ProductProfileCatalog profiles = (id, revision) ->
                        config.profile().id().equals(id)
                                && config.profile().revision() == revision
                                ? Optional.of(config.profile()) : Optional.empty();
                var sessions = new ReferenceCreateRealtimeSessionAllocator(
                        config.wssOrigin(), config.wssTokenTtl(), clock, random);
                var create = new AuthenticatedCompleteMeetingApplicationService(
                        config.deploymentScope(), identity,
                        new JdbcCompleteMeetingCreateReplayLookup(dataSource),
                        new JdbcUsageReserveLookup(dataSource), entitlement, ids,
                        new CompleteMeetingResponseAssembler(new CapabilityPlanner(),
                                sessions, config.runtimeLimits()),
                        new JdbcCompleteMeetingCreationUnitOfWork(dataSource, clock),
                        config.trustedEstimate(), profiles, config.mockCapabilities(),
                        config.capabilityLimits());
                runtime = new MeetingHttpRuntime(query, deletion, decoder, errors, stopService,
                        create, new CreateMeetingHttpRequestDecoder(
                                config.audience(), config.clientId()), true);
            } else {
                runtime = new MeetingHttpRuntime(query, deletion, decoder, errors, stopService);
            }
            LOGGER.log(Level.INFO, "reference meeting HTTP 装配完成 createEnabled={0}",
                    config.createEnabled());
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

    /**
     * 为本装配根创建尚未启动的 JDK HTTP 入口，健康探针读取同一唯一 JDBC 池。
     * 调用方须显式指定受信网络或 loopback 地址，启动及先停网络、后关闭本装配
     * 根的顺序仍由调用方负责。本方法不增加进程入口或后台线程。
     *
     * @throws IOException 监听地址无法绑定；本装配根仍由调用方持有并关闭
     * @throws IllegalStateException 本装配根已经关闭
     */
    public MeetingJdkHttpServer newJdkHttpServer(InetSocketAddress address) throws IOException {
        if (closed.get()) {
            throw new IllegalStateException("reference meeting HTTP assembly is closed");
        }
        return new MeetingJdkHttpServer(address, runtime, errors, contextResolver,
                () -> database.checkReadiness().ready());
    }

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

    /** 仅生成无业务语义的不透明候选 ID；数据库约束负责处理极低概率碰撞。 */
    private static final class RandomIds implements MeetingIdGenerator, EntitlementIdGenerator {
        private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
        private final SecureRandom random;

        private RandomIds(SecureRandom random) { this.random = random; }

        @Override public String newMeetingId() { return next("mtg_"); }
        @Override public String newGrantId() { return next("grant_"); }
        @Override public String newReservationId() { return next("reservation_"); }
        @Override public String newLeaseToken() { return next("lease_"); }

        private String next(String prefix) {
            byte[] bytes = new byte[24];
            random.nextBytes(bytes);
            try {
                return prefix + ENCODER.encodeToString(bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }
}
