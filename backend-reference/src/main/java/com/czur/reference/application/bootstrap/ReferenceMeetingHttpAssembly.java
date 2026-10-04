package com.czur.reference.application.bootstrap;

import com.czur.reference.application.command.AuthenticatedCompleteMeetingApplicationService;
import com.czur.reference.application.command.AuthenticatedMeetingStopApplicationService;
import com.czur.reference.application.command.AuthenticatedRealtimeTokenRenewApplicationService;
import com.czur.reference.application.command.CompleteMeetingResponseAssembler;
import com.czur.reference.application.command.ReferenceCreateRealtimeSessionAllocator;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicy;
import com.czur.reference.application.finalization.DeterministicMockFinalizationPlanner;
import com.czur.reference.application.finalization.MeetingInputReconciler;
import com.czur.reference.application.finalization.MeetingInputReconciliationScan;
import com.czur.reference.application.http.AuthenticatedMeetingHttpContextResolver;
import com.czur.reference.application.http.CreateMeetingHttpRequestDecoder;
import com.czur.reference.application.http.HttpErrorMapper;
import com.czur.reference.application.http.MeetingJdkHttpServer;
import com.czur.reference.application.http.MeetingHttpRuntime;
import com.czur.reference.application.http.ReferenceMeetingHttpRequestDecoder;
import com.czur.reference.application.http.ReferenceDevSessionLoginEndpoint;
import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityProvider;
import com.czur.reference.application.manifest.ManifestResultAccessPolicy;
import com.czur.reference.application.model.ModelSessionIssuer;
import com.czur.reference.application.model.BailianSummarySessionIssuer;
import com.czur.reference.application.model.SampleJsonWsModelSessionIssuer;
import com.czur.reference.application.model.TingwuModelSessionIssuer;
import com.czur.reference.application.persistence.jdbc.JdbcDatabaseRuntime;
import com.czur.reference.application.persistence.jdbc.JdbcCompleteMeetingCreateReplayLookup;
import com.czur.reference.application.persistence.jdbc.JdbcCompleteMeetingCreationUnitOfWork;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingDeletionAdmissionUnitOfWork;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingQueryPort;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingManifestQueryPort;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingArtifactContentQueryPort;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingEventLogPort;
import com.czur.reference.application.persistence.jdbc.JdbcModelSessionLanguageLookup;
import com.czur.reference.application.persistence.jdbc.JdbcClientMeetingInputPort;
import com.czur.reference.application.persistence.jdbc.JdbcClientMeetingResultFinalizer;
import com.czur.reference.application.persistence.jdbc.JdbcOwnedMeetingStopUnitOfWork;
import com.czur.reference.application.persistence.jdbc.JdbcRenewRealtimeTokenUnitOfWork;
import com.czur.reference.application.persistence.jdbc.JdbcRealtimeCredentialVerifier;
import com.czur.reference.application.persistence.jdbc.JdbcRealtimeStartFactsLookup;
import com.czur.reference.application.persistence.jdbc.JdbcRealtimeMockSessionSpecLookup;
import com.czur.reference.application.persistence.jdbc.JdbcRealtimeTranscriptEventPublisher;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingStartExecutionUnitOfWork;
import com.czur.reference.application.persistence.jdbc.JdbcDurableAudioInputPort;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingInputClosureContextPort;
import com.czur.reference.application.persistence.jdbc.JdbcPendingMeetingInputPort;
import com.czur.reference.application.persistence.jdbc.JdbcAcceptedInputFactLookup;
import com.czur.reference.application.persistence.jdbc.JdbcCompletedAudioReadinessPort;
import com.czur.reference.application.persistence.jdbc.JdbcFinalizationAdmissionUnitOfWork;
import com.czur.reference.application.persistence.jdbc.DatabaseReadiness;
import com.czur.reference.application.persistence.jdbc.DatabaseStartupException;
import com.czur.reference.application.persistence.jdbc.JdbcReferenceDevEntitlementProvider;
import com.czur.reference.application.persistence.jdbc.JdbcUsageReserveLookup;
import com.czur.reference.application.port.MeetingIdGenerator;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.MeetingArtifactContentPort;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.ProductProfileCatalog;
import com.czur.reference.application.realtime.RealtimeAudioIngress;
import com.czur.reference.application.realtime.RealtimeMockTranscriptRuntime;
import com.czur.reference.application.realtime.RealtimeTranscriptSessions;
import com.czur.reference.application.realtime.RealtimeMeetingStartCoordinator;
import com.czur.reference.application.wss.MeetingRealtimeWssServer;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.domain.capability.CapabilityPlanner;

import javax.sql.DataSource;
import javax.net.ssl.SSLContext;
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
 * <p>启动前从一份可信配置完成交叉校验；数据库运行时检查唯一 V001 基线登记及
 * 当前物理结构，只有显式启用实时 Token 续期时才额外检查其物理结构。不自动迁移或回退
 * 内存端口。查询、认证 Stop 与 T14 删除受理从同一
 * DataSource 装配。只有可信配置显式开启 Create 才接入 V013 重放、权益与创建
 * 工作单元；关闭时请求在任何身份、权益、V013 写入或会话分配前返回 501。
 * {@code start()} 不自动创建网络监听器；调用方可显式创建 HTTP/WSS listener。
 * 本对象不启动 Worker、模型或存储适配器。</p>
 *
 * <p>调用方应先停止外层 HTTP/WSS 网络接收，再关闭本对象；关闭顺序为 HTTP 运行时、
 * JDBC 池，重复关闭安全。返回的组件供调用方接线，不转移资源所有权。</p>
 */
public final class ReferenceMeetingHttpAssembly implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(
            ReferenceMeetingHttpAssembly.class.getName());

    private final JdbcDatabaseRuntime database;
    private final String deploymentScope;
    private final MeetingHttpRuntime runtime;
    private final AuthenticatedMeetingHttpContextResolver contextResolver;
    private final HttpErrorMapper errors;
    private final boolean realtimeRenewEnabled;
    private final JdbcRealtimeCredentialVerifier realtimeVerifier;
    private final RealtimeAudioIngress realtimeAudioIngress;
    private final RealtimeTranscriptSessions realtimeTranscriptSessions;
    private final MeetingInputReconciler inputReconciler;
    private final JdbcMeetingEventLogPort eventLog;
    private final Clock clock;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ReferenceMeetingHttpAssembly(
            JdbcDatabaseRuntime database, String deploymentScope, MeetingHttpRuntime runtime,
            AuthenticatedMeetingHttpContextResolver contextResolver,
            HttpErrorMapper errors, boolean realtimeRenewEnabled,
            JdbcRealtimeCredentialVerifier realtimeVerifier,
            RealtimeAudioIngress realtimeAudioIngress,
            RealtimeTranscriptSessions realtimeTranscriptSessions,
            MeetingInputReconciler inputReconciler,
            JdbcMeetingEventLogPort eventLog, Clock clock) {
        this.database = database;
        this.deploymentScope = deploymentScope;
        this.runtime = runtime;
        this.contextResolver = contextResolver;
        this.errors = errors;
        this.realtimeRenewEnabled = realtimeRenewEnabled;
        this.realtimeVerifier = realtimeVerifier;
        this.realtimeAudioIngress = realtimeAudioIngress;
        this.realtimeTranscriptSessions = realtimeTranscriptSessions;
        this.inputReconciler = Objects.requireNonNull(inputReconciler, "inputReconciler");
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog");
        this.clock = clock;
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

    /** 使用 Worker 已持有的同一持久 Store 显式开放最终转写读取；不创建第二份存储。 */
    public static ReferenceMeetingHttpAssembly start(
            Map<String, String> environment, Clock clock, ArtifactStore artifactStore) {
        return start(ReferenceMeetingDeploymentConfig.fromEnvironment(environment),
                clock, artifactStore);
    }

    /** 复用调用方已冻结的配置；显式 Create 开关决定是否构造创建依赖。 */
    public static ReferenceMeetingHttpAssembly start(
            ReferenceMeetingDeploymentConfig config, Clock clock) {
        return start(config, clock, null);
    }

    /** Artifact 端口只有在结果 TTL 和持久 Store 均显式提供时才接入。 */
    public static ReferenceMeetingHttpAssembly start(
            ReferenceMeetingDeploymentConfig config, Clock clock,
            ArtifactStore artifactStore) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        JdbcDatabaseRuntime database = JdbcDatabaseRuntime.start(config.database());
        MeetingHttpRuntime runtime = null;
        try {
            if (config.realtimeRenewEnabled()) {
                DatabaseReadiness readiness = database.checkRealtimeCredentialReadiness();
                if (!readiness.ready()) {
                    throw new DatabaseStartupException(readiness);
                }
            }
            DataSource dataSource = database.dataSource();
            SecureRandom random = new SecureRandom();
            RandomIds ids = new RandomIds(random);
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
            var referenceLogin = config.login()
                    .map(login -> new ReferenceDevSessionLoginEndpoint(login, errors, clock))
                    .orElse(null);
            var decoder = new ReferenceMeetingHttpRequestDecoder(
                    config.audience(), config.clientId());
            var resolver = new AuthenticatedMeetingHttpContextResolver(
                    config.deploymentScope(), config.audience(), config.clientId(),
                    identity, errors);
            // Create 开关只控制新建；关闭后仍可能有先前创建的 V013/V015 会议
            // 等待 Stop 收口，因此同一 Reconciler 独立于该开关。旧 v1 会议没有
            // 完整输入事实，触发结果不会改写已提交 Stop 或权威查询响应。
            MeetingInputReconciler inputReconciler = new MeetingInputReconciler(
                    new JdbcMeetingInputClosureContextPort(
                            dataSource, config.deploymentScope()),
                    new JdbcAcceptedInputFactLookup(dataSource),
                    new JdbcCompletedAudioReadinessPort(dataSource),
                    new JdbcFinalizationAdmissionUnitOfWork(dataSource,
                            ids::newReceipt, new DeterministicMockFinalizationPlanner()),
                    clock);
            var stopService = new AuthenticatedMeetingStopApplicationService(
                    config.deploymentScope(), identity, stop, query, inputReconciler,
                    new JdbcClientMeetingResultFinalizer(dataSource, ids::newReceipt,
                            clock, artifactStore));
            var renewService = config.realtimeRenewEnabled()
                    ? new AuthenticatedRealtimeTokenRenewApplicationService(
                            config.deploymentScope(), identity,
                            new JdbcRenewRealtimeTokenUnitOfWork(dataSource, clock,
                                    config.wssTokenTtl(), new SecureRandom()))
                    : null;
            ProductProfileCatalog profiles = (id, revision) ->
                    config.profile().id().equals(id)
                            && config.profile().revision() == revision
                            ? Optional.of(config.profile()) : Optional.empty();
            var realtimeVerifier = new JdbcRealtimeCredentialVerifier(
                    dataSource, config.deploymentScope(), clock);
            var durableAudio = new JdbcDurableAudioInputPort(dataSource,
                    config.deploymentScope());
            var startCoordinator = new RealtimeMeetingStartCoordinator(
                    config.deploymentScope(), new JdbcRealtimeStartFactsLookup(dataSource),
                    profiles, config.mockCapabilities(), config.capabilityLimits(), clock,
                    new JdbcMeetingStartExecutionUnitOfWork(
                            dataSource, new CapabilityPlanner()));
            var realtimeIngress = new RealtimeAudioIngress(startCoordinator, durableAudio);
            var clientInput = new JdbcClientMeetingInputPort(dataSource,
                    startCoordinator, clock, artifactStore);
            var realtimeTranscripts = new RealtimeMockTranscriptRuntime(
                    new JdbcRealtimeMockSessionSpecLookup(dataSource,
                            config.deploymentScope()), durableAudio,
                    new JdbcRealtimeTranscriptEventPublisher(dataSource, clock),
                    config.mockCapabilities(), clock);
            MeetingManifestPort manifest = config.resultLinkTtl()
                    .<MeetingManifestPort>map(ttl -> new JdbcMeetingManifestQueryPort(
                            dataSource, new ManifestResultAccessPolicy(clock, ttl)))
                    .orElse(null);
            MeetingArtifactContentPort artifact = manifest != null
                    ? new JdbcMeetingArtifactContentQueryPort(
                            dataSource, manifest, artifactStore) : null;
            // REST 补拉和后续 WSS 恢复共用 meeting_event；不建立第二套事件事实源。
            var events = new JdbcMeetingEventLogPort(dataSource);
            // 只替换模型会话材料签发端口。旧 Create.realtime 与 WSS Mock
            // 继续保留既有语义，Android 直连模型的选择由 adapterId 驱动。
            ModelSessionIssuer modelSessions = config.sampleJsonWsEndpoint()
                    .<ModelSessionIssuer>map(endpoint ->
                            new SampleJsonWsModelSessionIssuer(endpoint, clock))
                    .orElseGet(() -> config.tingwuModelSession()
                            .<ModelSessionIssuer>map(model ->
                                    new TingwuModelSessionIssuer(model, clock)).orElse(null));
            var summarySessions = config.bailianSummarySession()
                    .map(summary -> new BailianSummarySessionIssuer(summary, clock)).orElse(null);
            var modelSessionLanguages = modelSessions == null && summarySessions == null ? null
                    : new JdbcModelSessionLanguageLookup(dataSource);
            if (config.createEnabled()) {
                // 与查询、Stop、Delete 共用唯一连接池、身份 Provider、Clock 和错误映射。
                // ID 仅由服务端安全随机源生成；首次公开响应由 V013 持久化。
                var policy = new ReferenceDevEntitlementPolicy(
                        config.entitlement(), clock, ids);
                var entitlement = new JdbcReferenceDevEntitlementProvider(
                        dataSource, policy, clock);
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
                                config.audience(), config.clientId()), true, manifest,
                        renewService, artifact, events, clientInput, modelSessions,
                        modelSessionLanguages, summarySessions,
                        referenceLogin);
            } else {
                runtime = new MeetingHttpRuntime(query, deletion, decoder, errors, stopService,
                        null, null, false, manifest, renewService, artifact, events,
                        clientInput, modelSessions, modelSessionLanguages, summarySessions,
                        referenceLogin);
            }
            LOGGER.log(Level.INFO,
                    "reference meeting HTTP 装配完成 createEnabled={0} realtimeRenewEnabled={1}",
                    new Object[]{config.createEnabled(), config.realtimeRenewEnabled()});
            return new ReferenceMeetingHttpAssembly(database, config.deploymentScope(),
                    runtime, resolver, errors,
                    config.realtimeRenewEnabled(), realtimeVerifier, realtimeIngress,
                    realtimeTranscripts, inputReconciler, events, clock);
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
     * 用本装配根的同一连接池和 Reconciler 创建待收口扫描器。
     * 调用方负责串行、周期性地调用 {@code runOnce()}；扫描器不创建线程，
     * 且必须在本装配根关闭之前停止调用。
     */
    public MeetingInputReconciliationScan newInputReconciliationScan(int pageSize) {
        if (closed.get()) {
            throw new IllegalStateException("reference meeting HTTP assembly is closed");
        }
        return new MeetingInputReconciliationScan(
                new JdbcPendingMeetingInputPort(database.dataSource(),
                        deploymentScope), inputReconciler, pageSize);
    }

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
                () -> database.checkReadiness().ready()
                        && (!realtimeRenewEnabled
                                || database.checkRealtimeCredentialReadiness().ready()));
    }

    /**
     * 从同一唯一 JDBC 池装配尚未启动的 TLS 实时入口。V014/V015 必须先完成按需
     * readiness；仅创建未启动的 listener，不自动迁移、启动 Worker 或开放网络流量。
     * 调用方先关闭返回的 WSS，再关闭本装配根及其连接池。
     */
    public MeetingRealtimeWssServer newRealtimeWssServer(
            InetSocketAddress address, SSLContext tls) {
        if (closed.get()) {
            throw new IllegalStateException("reference meeting HTTP assembly is closed");
        }
        DatabaseReadiness credentials = database.checkRealtimeCredentialReadiness();
        if (!credentials.ready()) throw new DatabaseStartupException(credentials);
        DatabaseReadiness audio = database.checkDurableAudioReadiness();
        if (!audio.ready()) throw new DatabaseStartupException(audio);
        return new MeetingRealtimeWssServer(address, tls, realtimeVerifier,
                realtimeAudioIngress, realtimeTranscriptSessions, inputReconciler,
                eventLog, clock);
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

        /** 同一入库事务的 opaque 候选；重放时 Admission 从数据库读取原 receipt。 */
        private String newReceipt() { return next("receipt_"); }

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
