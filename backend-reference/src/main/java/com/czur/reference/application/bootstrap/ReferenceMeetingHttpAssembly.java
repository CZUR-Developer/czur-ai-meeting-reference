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
import com.czur.reference.application.http.MeetingHttpDispatcher;
import com.czur.reference.application.http.MeetingHttpRuntime;
import com.czur.reference.application.http.ReferenceMeetingHttpRequestDecoder;
import com.czur.reference.application.http.ReferenceDevSessionLoginEndpoint;
import com.czur.reference.application.http.ReferenceDeploymentCapabilitiesEndpoint;
import com.czur.reference.application.http.ResultEmailEndpoint;
import com.czur.reference.application.http.SmtpResultEmailSender;
import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityProvider;
import com.czur.reference.application.manifest.ManifestResultAccessPolicy;
import com.czur.reference.application.model.ModelSessionIssuer;
import com.czur.reference.application.model.BailianSummarySessionIssuer;
import com.czur.reference.application.model.SampleJsonWsModelSessionIssuer;
import com.czur.reference.application.model.TingwuModelSessionIssuer;
import com.czur.reference.application.persistence.jdbc.JdbcDatabaseRuntime;
import com.czur.reference.application.persistence.jdbc.JdbcDatabaseContext;
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
import com.czur.reference.application.port.entitlement.EntitlementPolicy;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.identity.IdentityProvider;
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
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 参考会议 HTTP 装配根，拥有唯一 JDBC 池与请求运行时的生命周期。
 *
 * <p>启动前从一份可信配置完成交叉校验；数据库运行时检查唯一 V001 基线登记及
 * 当前物理结构，只有显式启用实时 Token 续期时才额外检查其物理结构。不自动迁移或回退
 * 内存端口。查询、认证 Stop 与 T14 删除受理从同一
 * DataSource装配。身份与持久权益Provider在装配时共享；可信配置显式开启Create
 * 才接入创建重放与工作单元。关闭时Create请求在身份验证、资格策略调用、预占／
 * 会议写入或会话分配前返回501，其他既有会议请求仍使用同一Provider。
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
    private final JdbcDatabaseContext databaseContext;
    private final String deploymentScope;
    private final MeetingHttpRuntime runtime;
    private final MeetingHttpDispatcher dispatcher;
    private final AuthenticatedMeetingHttpContextResolver contextResolver;
    private final HttpErrorMapper errors;
    private final boolean realtimeRenewEnabled;
    private final JdbcRealtimeCredentialVerifier realtimeVerifier;
    private final RealtimeAudioIngress realtimeAudioIngress;
    private final RealtimeTranscriptSessions realtimeTranscriptSessions;
    private final MeetingInputReconciler inputReconciler;
    private final JdbcMeetingEventLogPort eventLog;
    private final Clock clock;
    private final EntitlementProvider entitlementProvider;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ReferenceMeetingHttpAssembly(
            JdbcDatabaseRuntime database, JdbcDatabaseContext databaseContext,
            String deploymentScope, MeetingHttpRuntime runtime,
            AuthenticatedMeetingHttpContextResolver contextResolver,
            HttpErrorMapper errors, boolean realtimeRenewEnabled,
            JdbcRealtimeCredentialVerifier realtimeVerifier,
            RealtimeAudioIngress realtimeAudioIngress,
            RealtimeTranscriptSessions realtimeTranscriptSessions,
            MeetingInputReconciler inputReconciler,
            JdbcMeetingEventLogPort eventLog, Clock clock,
            ResultEmailEndpoint resultEmail, EntitlementProvider entitlementProvider) {
        this.database = database;
        this.databaseContext = Objects.requireNonNull(databaseContext, "databaseContext");
        this.deploymentScope = deploymentScope;
        this.runtime = runtime;
        this.contextResolver = contextResolver;
        this.errors = errors;
        this.realtimeRenewEnabled = realtimeRenewEnabled;
        this.realtimeVerifier = realtimeVerifier;
        this.realtimeAudioIngress = realtimeAudioIngress;
        this.realtimeTranscriptSessions = realtimeTranscriptSessions;
        this.inputReconciler = inputReconciler;
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog");
        this.clock = clock;
        this.dispatcher = new MeetingHttpDispatcher(runtime, errors, contextResolver, resultEmail);
        this.entitlementProvider = entitlementProvider;
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
        if (!config.identity().enabled()) {
            throw new IllegalArgumentException("外部身份配置须显式注入身份和权益策略");
        }
        return start(config, clock, artifactStore, null, null, null,
                UnaryOperator.identity(), true, false);
    }

    /**
     * 使用部署方身份和资格策略装配同一会议运行时及PostgreSQL账本。
     *
     * <p>Create、Stop、查询／删除、模型材料和续期共用此身份实现。资格策略只判断
     * 许可并生成首次预占快照；装配根将其与会议数据源组合，预占及用量操作仍写入
     * 同一PG库，供建会工作单元绑定。此重载不开放参考预置账号登录，无需伪造演示
     * 凭据。部署方负责自身登录入口，仍可通过普通JAR消费本装配和公开端口。</p>
     *
     * <p>两个实现均可被多个请求线程调用，须支持并发。外部身份／策略持有的资源
     * 由部署方关闭；本装配只拥有会议运行时和PG连接池。调用方先停HTTP／WSS，
     * 再关闭本装配，最后释放其外部账号服务资源。</p>
     *
     * @param config 可信会议运行配置，外部账号部署可使用外部身份配置入口
     * @param clock 身份、会议和权益事实共用的服务端时钟
     * @param artifactStore 持久产物存储；沿用旧装配允许为null的边界
     * @param identityProvider 部署方的凭据验证实现，不能用本地登录状态代替
     * @param entitlementPolicy 部署方的开会资格和首次预占策略，不负责持久账本
     * @return readiness通过的装配；不会自行开放网络监听器
     */
    public static ReferenceMeetingHttpAssembly start(
            ReferenceMeetingDeploymentConfig config, Clock clock,
            ArtifactStore artifactStore, IdentityProvider identityProvider,
            EntitlementPolicy entitlementPolicy) {
        return start(config, clock, artifactStore, identityProvider, entitlementPolicy, null);
    }

    /**
     * 注入部署方模型发行器，复用同一身份、会议事实和PostgreSQL运行时。
     *
     * <p>发行器收到已经验证会议归属的同场ID、首次Create语言和冻结能力决策，
     * 只签发临时直连材料，不接收或转发音频。非null实现优先于配置中的默认
     * sample／听悟发行器，无需再配置参考听悟凭据；配置中的能力声明仍参与
     * Create交集，注入本身不授予翻译或总结能力。</p>
     *
     * <p>发行器须支持并发请求，其资源和生命周期由部署方持有。关闭本装配只
     * 关闭自身HTTP运行时和PG池，不关闭外部发行器；调用方先停外层HTTP／WSS，
     * 再关本装配，最后按自己的资源所有权释放发行器。</p>
     *
     * @param config 已冻结的可信部署配置，不自动迁移数据库
     * @param clock 与身份和会议事实共用的服务端时钟
     * @param artifactStore 部署方持久产物端口，沿用旧装配允许null的边界
     * @param identityProvider 部署方真实凭据校验，不开放参考演示登录
     * @param entitlementPolicy 部署方开会资格策略，持久账本仍由本装配负责
     * @param modelSessionIssuer 部署方模型发行器；null保留原配置默认选择
     * @return readiness通过的装配，不另建网络监听器或模型会话管理器
     */
    public static ReferenceMeetingHttpAssembly start(
            ReferenceMeetingDeploymentConfig config, Clock clock,
            ArtifactStore artifactStore, IdentityProvider identityProvider,
            EntitlementPolicy entitlementPolicy, ModelSessionIssuer modelSessionIssuer) {
        return start(config, clock, artifactStore, identityProvider, entitlementPolicy,
                modelSessionIssuer, UnaryOperator.identity());
    }

    /**
     * 在唯一持久权益Provider外装配部署方结算装饰器，不替换会议账本。
     *
     * <p>工厂在数据库readiness通过后仅调用一次，输入为本装配创建的JDBC
     * Provider，输出实例由Create、客户端Stop收口及{@link #entitlementProvider()}
     * 共享。部署方Worker须复用该访问器，不能另建默认Provider绕过装饰器。
     * 原有start重载使用identity工厂，保留参考及外部身份的既有行为。</p>
     *
     * <p>装饰器须保留authorizeStart只读语义，并将reserve、commit和cancel
     * 委托给输入Provider，以保留PG预占绑定、版本检查和稳定操作重放。需要外部
     * 结算的部署可在commit委托成功后确认同场外部操作；外部未确认时必须返回
     * 失败，不能将本地COMMIT当作外部已结算。重试须复用同一冻结请求和幂等键，
     * 即使PG已重放成功，也不能跳过尚未确认的外部操作。</p>
     *
     * <p>工厂和返回值均不可为null；工厂抛错或返回null时启动失败，已创建的
     * PG池会关闭。装饰器须支持并发调用，其外部资源由部署方管理；关闭本装配
     * 不会代关外部服务资源。工厂只负责装配，不在启动时登记会议或扣费。</p>
     *
     * @param config 可信运行配置，仍使用同一V001基线和PG数据源
     * @param clock 身份、会议和权益事实共用的服务端时钟
     * @param artifactStore 部署方持久产物端口，沿用允许null的边界
     * @param identityProvider 部署方真实身份校验，不开放参考演示登录
     * @param entitlementPolicy 部署方只读资格和首次预占候选策略
     * @param modelSessionIssuer 部署方模型发行器；null保留原默认选择
     * @param entitlementProviderDecorator 输入唯一JDBC Provider并返回共享装饰实例
     * @return readiness通过的装配，不另建会议状态机、账本或网络监听器
     */
    public static ReferenceMeetingHttpAssembly start(
            ReferenceMeetingDeploymentConfig config, Clock clock,
            ArtifactStore artifactStore, IdentityProvider identityProvider,
            EntitlementPolicy entitlementPolicy, ModelSessionIssuer modelSessionIssuer,
            UnaryOperator<EntitlementProvider> entitlementProviderDecorator) {
        Objects.requireNonNull(identityProvider, "identityProvider");
        Objects.requireNonNull(entitlementPolicy, "entitlementPolicy");
        Objects.requireNonNull(entitlementProviderDecorator, "entitlementProviderDecorator");
        return start(config, clock, artifactStore, identityProvider, entitlementPolicy,
                modelSessionIssuer, entitlementProviderDecorator, false, false);
    }

    /**
     * 显式装配 SDK 直连模型的正常客户端会议入口，支持已初始化的 PG／MySQL5.7。
     *
     * <p>与原 start 共用唯一装配核心、创建／Stop 服务、领域状态机和持久权益 Provider。
     * 只拥有一个 Context 池；不构造旧音频 Worker、WSS、删除或实时 Token 续签组件。
     * 这些 HTTP 路由使用既有 501，非 HTTP 访问器明确拒绝，不能借默认 PG Store 冒充可用。
     * Create 的旧 realtime 字段保留协议兼容；实际模型材料来自 modelSession 发行器。</p>
     *
     * <p>身份、资格、模型与存储必须由部署方明确提供；本入口无演示登录、模型或存储兜底。
     * 装饰器只装配一次，Create 与客户端 Stop 共用其返回实例。调用方先停外层 HTTP，
     * 再关闭本装配；外部身份／资格／模型／存储生命周期由调用方管理，不在此关闭。</p>
     *
     * @param config 已冻结的可信配置，启动不自动迁移，Create 开关及能力交集保持
     * @param clock 身份、会议及用量共用的服务端时钟
     * @param artifactStore 部署方持久产物 Store，不另建存储状态机
     * @param identityProvider 真实凭据校验端口，不从请求正文推定所有者
     * @param entitlementPolicy 首次资格／预占策略，公共持久账本仍使用唯一 Context
     * @param modelSessionIssuer 临时直连材料发行器，不接收或转发音频
     * @param entitlementProviderDecorator 唯一 JDBC Provider 的共享结算装饰器
     * @return 启动基线通过的客户端装配，不自动监听网络或运行后台 Worker
     */
    public static ReferenceMeetingHttpAssembly startClientModel(
            ReferenceMeetingDeploymentConfig config, Clock clock, ArtifactStore artifactStore,
            IdentityProvider identityProvider, EntitlementPolicy entitlementPolicy,
            ModelSessionIssuer modelSessionIssuer,
            UnaryOperator<EntitlementProvider> entitlementProviderDecorator) {
        Objects.requireNonNull(artifactStore, "artifactStore");
        Objects.requireNonNull(identityProvider, "identityProvider");
        Objects.requireNonNull(entitlementPolicy, "entitlementPolicy");
        Objects.requireNonNull(modelSessionIssuer, "modelSessionIssuer");
        Objects.requireNonNull(entitlementProviderDecorator, "entitlementProviderDecorator");
        return start(config, clock, artifactStore, identityProvider, entitlementPolicy,
                modelSessionIssuer, entitlementProviderDecorator, false, true);
    }

    private static ReferenceMeetingHttpAssembly start(
            ReferenceMeetingDeploymentConfig config, Clock clock,
            ArtifactStore artifactStore, IdentityProvider identityProvider,
            EntitlementPolicy entitlementPolicy, ModelSessionIssuer modelSessionIssuer,
            UnaryOperator<EntitlementProvider> entitlementProviderDecorator,
            boolean referenceDefaults, boolean clientModel) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        JdbcDatabaseRuntime database = clientModel ? null : JdbcDatabaseRuntime.start(config.database());
        JdbcDatabaseContext databaseContext = clientModel ? JdbcDatabaseContext.start(config.database())
                : database.databaseContext();
        boolean realtimeRenewEnabled = !clientModel && config.realtimeRenewEnabled();
        MeetingHttpRuntime runtime = null;
        try {
            if (realtimeRenewEnabled) {
                DatabaseReadiness readiness = database.checkRealtimeCredentialReadiness();
                if (!readiness.ready()) {
                    throw new DatabaseStartupException(readiness);
                }
            }
            DataSource dataSource = databaseContext.dataSource();
            SecureRandom random = new SecureRandom();
            RandomIds ids = new RandomIds(random);
            byte[] pageSecret = config.pageTokenSecretCopy();
            JdbcMeetingQueryPort query;
            try {
                query = new JdbcMeetingQueryPort(databaseContext, pageSecret);
            } finally {
                Arrays.fill(pageSecret, (byte) 0);
            }
            var stop = new JdbcOwnedMeetingStopUnitOfWork(databaseContext, clock);
            var deletion = clientModel ? null : new JdbcMeetingDeletionAdmissionUnitOfWork(dataSource, clock);
            IdentityProvider identity = referenceDefaults
                    ? new ReferenceDevIdentityProvider(config.identity(), clock)
                    : identityProvider;
            EntitlementPolicy policy = referenceDefaults
                    ? new ReferenceDevEntitlementPolicy(config.entitlement(), clock, ids)
                    : entitlementPolicy;
            // 工厂只装配一次；Create、Stop与Worker访问器共用同一装饰实例。
            // 输入Provider仍在唯一会议库保存所有预占／用量操作和绑定事实。
            var jdbcEntitlement = new JdbcReferenceDevEntitlementProvider(databaseContext, policy, clock);
            EntitlementProvider entitlement = Objects.requireNonNull(
                    entitlementProviderDecorator.apply(jdbcEntitlement),
                    "entitlementProviderDecorator result");
            var errors = new HttpErrorMapper();
            var referenceLogin = referenceDefaults ? config.login()
                    .map(login -> new ReferenceDevSessionLoginEndpoint(login, errors, clock))
                    .orElse(null) : null;
            var capabilities = new ReferenceDeploymentCapabilitiesEndpoint(config, errors);
            var decoder = new ReferenceMeetingHttpRequestDecoder(
                    config.audience(), config.clientId());
            var resolver = new AuthenticatedMeetingHttpContextResolver(
                    config.deploymentScope(), config.audience(), config.clientId(),
                    identity, errors);
            // Create 开关只控制新建；关闭后仍可能有先前创建的 V013/V015 会议
            // 等待 Stop 收口，因此同一 Reconciler 独立于该开关。旧 v1 会议没有
            // 完整输入事实，触发结果不会改写已提交 Stop 或权威查询响应。
            MeetingInputReconciler inputReconciler = clientModel ? null : new MeetingInputReconciler(
                    new JdbcMeetingInputClosureContextPort(
                            dataSource, config.deploymentScope()),
                    new JdbcAcceptedInputFactLookup(dataSource),
                    new JdbcCompletedAudioReadinessPort(dataSource),
                    new JdbcFinalizationAdmissionUnitOfWork(dataSource,
                            ids::newReceipt, new DeterministicMockFinalizationPlanner()),
                    clock);
            var stopService = new AuthenticatedMeetingStopApplicationService(
                    config.deploymentScope(), identity, stop, query, inputReconciler,
                    new JdbcClientMeetingResultFinalizer(databaseContext, ids::newReceipt,
                            clock, artifactStore, entitlement));
            var renewService = realtimeRenewEnabled
                    ? new AuthenticatedRealtimeTokenRenewApplicationService(
                            config.deploymentScope(), identity,
                            new JdbcRenewRealtimeTokenUnitOfWork(dataSource, clock,
                                    config.wssTokenTtl(), new SecureRandom()))
                    : null;
            ProductProfileCatalog profiles = (id, revision) ->
                    config.profile().id().equals(id)
                            && config.profile().revision() == revision
                            ? Optional.of(config.profile()) : Optional.empty();
            var realtimeVerifier = clientModel ? null : new JdbcRealtimeCredentialVerifier(
                    dataSource, config.deploymentScope(), clock);
            var durableAudio = clientModel ? null : new JdbcDurableAudioInputPort(dataSource,
                    config.deploymentScope());
            var startCoordinator = new RealtimeMeetingStartCoordinator(
                    config.deploymentScope(), new JdbcRealtimeStartFactsLookup(databaseContext),
                    profiles, config.mockCapabilities(), config.capabilityLimits(), clock,
                    new JdbcMeetingStartExecutionUnitOfWork(
                            databaseContext, new CapabilityPlanner()));
            var realtimeIngress = clientModel ? null : new RealtimeAudioIngress(startCoordinator, durableAudio);
            var clientInput = new JdbcClientMeetingInputPort(databaseContext,
                    startCoordinator, clock, artifactStore);
            var realtimeTranscripts = clientModel ? null : new RealtimeMockTranscriptRuntime(
                    new JdbcRealtimeMockSessionSpecLookup(dataSource,
                            config.deploymentScope()), durableAudio,
                    new JdbcRealtimeTranscriptEventPublisher(dataSource, clock),
                    config.mockCapabilities(), clock);
            MeetingManifestPort manifest = config.resultLinkTtl()
                    .<MeetingManifestPort>map(ttl -> new JdbcMeetingManifestQueryPort(
                            databaseContext, new ManifestResultAccessPolicy(clock, ttl)))
                    .orElse(null);
            MeetingArtifactContentPort artifact = manifest != null
                    ? new JdbcMeetingArtifactContentQueryPort(
                            databaseContext, manifest, artifactStore) : null;
            // REST 补拉和后续 WSS 恢复共用 meeting_event；不建立第二套事件事实源。
            var events = new JdbcMeetingEventLogPort(databaseContext);
            // 只替换模型会话材料签发端口。旧 Create.realtime 与 WSS Mock
            // 继续保留既有语义，Android 直连模型的选择由 adapterId 驱动。
            ModelSessionIssuer modelSessions = modelSessionIssuer != null ? modelSessionIssuer
                    : config.sampleJsonWsEndpoint()
                    .<ModelSessionIssuer>map(endpoint ->
                            new SampleJsonWsModelSessionIssuer(endpoint, clock))
                    .orElseGet(() -> config.tingwuModelSession()
                            .<ModelSessionIssuer>map(model ->
                                    new TingwuModelSessionIssuer(model, clock)).orElse(null));
            var summarySessions = config.bailianSummarySession()
                    .map(summary -> new BailianSummarySessionIssuer(summary, clock)).orElse(null);
            var modelSessionLanguages = modelSessions == null && summarySessions == null ? null
                    : new JdbcModelSessionLanguageLookup(databaseContext);
            if (config.createEnabled()) {
                // 与查询、Stop、Delete 共用唯一连接池、身份 Provider、Clock 和错误映射。
                // ID 仅由服务端安全随机源生成；首次公开响应由会议创建事实持久化。
                var sessions = new ReferenceCreateRealtimeSessionAllocator(
                        config.wssOrigin(), config.wssTokenTtl(), clock, random);
                var create = new AuthenticatedCompleteMeetingApplicationService(
                        config.deploymentScope(), identity,
                        new JdbcCompleteMeetingCreateReplayLookup(databaseContext),
                        new JdbcUsageReserveLookup(databaseContext), entitlement, ids,
                        new CompleteMeetingResponseAssembler(new CapabilityPlanner(),
                                sessions, config.runtimeLimits()),
                        new JdbcCompleteMeetingCreationUnitOfWork(databaseContext, clock),
                        config.trustedEstimate(), profiles, config.mockCapabilities(),
                        config.capabilityLimits());
                runtime = new MeetingHttpRuntime(query, deletion, decoder, errors, stopService,
                        create, new CreateMeetingHttpRequestDecoder(
                                config.audience(), config.clientId()), true, manifest,
                        renewService, artifact, events, clientInput, modelSessions,
                        modelSessionLanguages, summarySessions,
                        referenceLogin, capabilities);
            } else {
                runtime = new MeetingHttpRuntime(query, deletion, decoder, errors, stopService,
                        null, null, false, manifest, renewService, artifact, events,
                        clientInput, modelSessions, modelSessionLanguages, summarySessions,
                        referenceLogin, capabilities);
            }
            LOGGER.log(Level.INFO,
                    "reference meeting HTTP 装配完成 createEnabled={0} realtimeRenewEnabled={1} identityMode={2} modelIssuerMode={3} clientModel={4}",
                    new Object[]{config.createEnabled(), realtimeRenewEnabled,
                            referenceDefaults ? "REFERENCE" : "EXTERNAL",
                            modelSessionIssuer == null ? "DEFAULT" : "EXTERNAL", clientModel});
            return new ReferenceMeetingHttpAssembly(database, databaseContext, config.deploymentScope(),
                    runtime, resolver, errors,
                    realtimeRenewEnabled, realtimeVerifier, realtimeIngress,
                    realtimeTranscripts, inputReconciler, events, clock,
                    config.resultEmail().map(email -> new ResultEmailEndpoint(query, manifest,
                            new SmtpResultEmailSender(email), errors)).orElse(null), entitlement);
        } catch (RuntimeException | Error failure) {
            try {
                if (runtime != null) {
                    runtime.close();
                }
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                if (database != null) database.close(); else databaseContext.close();
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

    /**
     * 返回JDK及部署方HTTP transport共用的唯一业务分发器。
     *
     * <p>宿主只把已约束的HttpRequest交给handle并写回HttpResponse，不重写路由
     * 或认证选择，不另启第二listener。本访问器不转移运行时／数据库所有权；
     * 调用方仍须先停止所有transport接收，再关闭本装配根。</p>
     */
    public MeetingHttpDispatcher dispatcher() { return dispatcher; }

    /** 返回 GET/list/DELETE 的可信身份上下文解析器。 */
    public AuthenticatedMeetingHttpContextResolver contextResolver() {
        return contextResolver;
    }

    /** 返回网络入口和运行时共用的唯一错误映射器。 */
    public HttpErrorMapper errorMapper() { return errors; }

    /** 返回已通过启动 readiness 的数据库运行时；调用方不得单独关闭。 */
    public JdbcDatabaseRuntime databaseRuntime() {
        requireLegacyRuntime();
        return database;
    }

    /** 所有模式的唯一 Context，供部署方读取同场 StartFacts／最终快照；不得单独关闭。 */
    public JdbcDatabaseContext databaseContext() { return databaseContext; }

    /**
     * 返回与Create和客户端Stop共用的持久权益Provider，供部署方装配既有收口Worker。
     *
     * <p>该实例使用本装配的会议数据源，不另建账本或复制资格策略。调用方不得
     * 关闭或转移连接池所有权；必须在本装配关闭前停止Worker调用。此访问器仅
     * 开放已有权益端口；存在装饰器时返回同一装饰实例，不能绕过外部结算确认。
     * 客户端采音冻结值由用量提交接口提供，真实采音计数由SDK负责。</p>
     */
    public EntitlementProvider entitlementProvider() { return entitlementProvider; }

    /**
     * 用本装配根的同一连接池和 Reconciler 创建待收口扫描器。
     * 调用方负责串行、周期性地调用 {@code runOnce()}；扫描器不创建线程，
     * 且必须在本装配根关闭之前停止调用。
     */
    public MeetingInputReconciliationScan newInputReconciliationScan(int pageSize) {
        requireLegacyRuntime();
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
        return new MeetingJdkHttpServer(address, dispatcher,
                () -> (database == null ? databaseContext.checkBaselineReadiness().ready()
                        : database.checkReadiness().ready())
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
        requireLegacyRuntime();
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
                if (database != null) database.close(); else databaseContext.close();
            }
            LOGGER.log(Level.INFO, "reference meeting HTTP 装配已关闭");
        }
    }

    private void requireLegacyRuntime() {
        if (database == null) {
            throw new UnsupportedOperationException("client model does not expose legacy audio runtime");
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
