package com.czur.reference.application.bootstrap;

import com.czur.reference.application.command.AuthenticatedCompleteMeetingApplicationService;
import com.czur.reference.application.command.AuthenticatedMeetingStopApplicationService;
import com.czur.reference.application.command.AuthenticatedRealtimeTokenRenewApplicationService;
import com.czur.reference.application.command.CompleteMeetingResponseAssembler;
import com.czur.reference.application.command.ReferenceCreateRealtimeSessionAllocator;
import com.czur.reference.application.finalization.MeetingInputReconciler;
import com.czur.reference.application.finalization.MeetingInputReconciliationScan;
import com.czur.reference.application.http.AuthenticatedMeetingHttpContextResolver;
import com.czur.reference.application.http.CreateMeetingHttpRequestDecoder;
import com.czur.reference.application.http.HttpErrorMapper;
import com.czur.reference.application.http.MeetingJdkHttpServer;
import com.czur.reference.application.http.MeetingHttpDispatcher;
import com.czur.reference.application.http.MeetingHttpRuntime;
import com.czur.reference.application.http.MeetingArtifactHttpEndpoint;
import com.czur.reference.application.http.MeetingHeartbeatEndpoint;
import com.czur.reference.application.http.ReferenceMeetingHttpRequestDecoder;
import com.czur.reference.application.http.ReferenceDeploymentCapabilitiesEndpoint;
import com.czur.reference.application.http.ResultEmailEndpoint;
import com.czur.reference.application.http.SmtpResultEmailSender;
import com.czur.reference.application.manifest.ManifestResultAccessPolicy;
import com.czur.reference.application.model.ModelSessionIssuer;
import com.czur.reference.application.model.BailianSummarySessionIssuer;
import com.czur.reference.application.model.SummarySessionIssuer;
import com.czur.reference.application.persistence.jdbc.JdbcDatabaseRuntime;
import com.czur.reference.application.persistence.jdbc.JdbcDatabaseContext;
import com.czur.reference.application.persistence.jdbc.JdbcCompleteMeetingCreateReplayLookup;
import com.czur.reference.application.persistence.jdbc.JdbcCompleteMeetingCreationUnitOfWork;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingQueryPort;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingManifestQueryPort;
import com.czur.reference.application.persistence.jdbc.JdbcArtifactContentQueryPort;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingEventLogPort;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingHeartbeatStore;
import com.czur.reference.application.persistence.jdbc.JdbcModelSessionLanguageLookup;
import com.czur.reference.application.persistence.jdbc.JdbcClientMeetingInputPort;
import com.czur.reference.application.persistence.jdbc.JdbcClientMeetingResultFinalizer;
import com.czur.reference.application.persistence.jdbc.JdbcSpeakerIdentityEnrollmentPort;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider;
import com.czur.reference.application.http.SpeakerIdentityEnrollmentEndpoint;
import com.czur.reference.application.http.SpeakerNameEditEndpoint;
import com.czur.reference.application.http.SpeakerResultAccessPolicy;
import com.czur.reference.application.persistence.jdbc.JdbcSpeakerNameEditPort;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.application.persistence.jdbc.JdbcOwnedMeetingStopUnitOfWork;
import com.czur.reference.application.persistence.jdbc.JdbcRenewRealtimeTokenUnitOfWork;
import com.czur.reference.application.persistence.jdbc.JdbcRealtimeCredentialVerifier;
import com.czur.reference.application.persistence.jdbc.JdbcRealtimeStartFactsLookup;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingStartExecutionUnitOfWork;
import com.czur.reference.application.persistence.jdbc.JdbcPendingMeetingInputPort;
import com.czur.reference.application.persistence.jdbc.DatabaseReadiness;
import com.czur.reference.application.persistence.jdbc.DatabaseStartupException;
import com.czur.reference.application.persistence.jdbc.JdbcEntitlementProvider;
import com.czur.reference.application.persistence.jdbc.JdbcUsageReserveLookup;
import com.czur.reference.application.port.AbandonedMeetingFinalizer;
import com.czur.reference.application.port.ClientMeetingResultFinalizer;
import com.czur.reference.application.port.MeetingIdGenerator;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.MeetingArtifactContentPort;
import com.czur.reference.application.port.MeetingArtifactMediaPort;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.artifact.AudioArtifactBudget;
import com.czur.reference.application.artifact.MeetingBodyArtifactBudget;
import com.czur.reference.application.port.ProductProfileCatalog;
import com.czur.reference.application.realtime.RealtimeAudioIngress;
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
 * 共享会议 HTTP 装配根，拥有唯一 JDBC 池与请求运行时的生命周期。
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
public class MeetingHttpAssembly implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(
            MeetingHttpAssembly.class.getName());

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
    private final MeetingArtifactMediaPort artifactMedia;
    private final EntitlementProvider entitlementProvider;
    private final JdbcClientMeetingResultFinalizer abandonedMeetingFinalizer;
    private final long audioArtifactMaxBytes;
    private final long bodyArtifactMaxBytes;
    private final AtomicBoolean closed;

    private MeetingHttpAssembly(
            JdbcDatabaseRuntime database, JdbcDatabaseContext databaseContext,
            String deploymentScope, MeetingHttpRuntime runtime,
            AuthenticatedMeetingHttpContextResolver contextResolver,
            HttpErrorMapper errors, boolean realtimeRenewEnabled,
            JdbcRealtimeCredentialVerifier realtimeVerifier,
            RealtimeAudioIngress realtimeAudioIngress,
            RealtimeTranscriptSessions realtimeTranscriptSessions,
            MeetingInputReconciler inputReconciler,
            JdbcMeetingEventLogPort eventLog, Clock clock,
            ResultEmailEndpoint resultEmail, MeetingArtifactMediaPort artifactMedia,
            EntitlementProvider entitlementProvider,
            JdbcClientMeetingResultFinalizer abandonedMeetingFinalizer,
            MeetingArtifactHttpEndpoint artifactEndpoint,
            SpeakerIdentityEnrollmentEndpoint speakerEnrollment, SpeakerNameEditEndpoint speakerEditing,
            MeetingHeartbeatEndpoint heartbeat,
            long audioArtifactMaxBytes, long bodyArtifactMaxBytes) {
        this.closed = new AtomicBoolean();
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
        this.artifactMedia = artifactMedia;
        this.dispatcher = new MeetingHttpDispatcher(runtime, errors, contextResolver, resultEmail,
                artifactEndpoint, speakerEnrollment, speakerEditing, heartbeat);
        this.entitlementProvider = entitlementProvider;
        this.abandonedMeetingFinalizer = Objects.requireNonNull(
                abandonedMeetingFinalizer, "abandonedMeetingFinalizer");
        this.audioArtifactMaxBytes = audioArtifactMaxBytes;
        this.bodyArtifactMaxBytes = bodyArtifactMaxBytes;
    }

    /** 薄兼容facade共享同一组资源和关闭标记，不创建第二份运行时或数据库池。 */
    protected MeetingHttpAssembly(MeetingHttpAssembly source) {
        this.database = source.database;
        this.databaseContext = source.databaseContext;
        this.deploymentScope = source.deploymentScope;
        this.runtime = source.runtime;
        this.dispatcher = source.dispatcher;
        this.contextResolver = source.contextResolver;
        this.errors = source.errors;
        this.realtimeRenewEnabled = source.realtimeRenewEnabled;
        this.realtimeVerifier = source.realtimeVerifier;
        this.realtimeAudioIngress = source.realtimeAudioIngress;
        this.realtimeTranscriptSessions = source.realtimeTranscriptSessions;
        this.inputReconciler = source.inputReconciler;
        this.eventLog = source.eventLog;
        this.clock = source.clock;
        this.artifactMedia = source.artifactMedia;
        this.entitlementProvider = source.entitlementProvider;
        this.abandonedMeetingFinalizer = source.abandonedMeetingFinalizer;
        this.audioArtifactMaxBytes = source.audioArtifactMaxBytes;
        this.bodyArtifactMaxBytes = source.bodyArtifactMaxBytes;
        this.closed = source.closed;
    }

    /**
     * 旧参考音频能力的可选装配边界，SDK直连模式不会调用此工厂。
     * 只在数据库readiness后调用一次；工厂复用给定连接池、时钟及会议启动协调器。
     * 返回组件不拥有连接池，后台Worker与默认模型选择由参考应用负责。
     */
    @FunctionalInterface
    public interface LegacyAudioFactory {
        LegacyAudioComponents create(DataSource source, MeetingDeploymentConfig config, Clock clock,
                com.czur.reference.application.port.FinalizationReceiptGenerator receipts,
                RealtimeMeetingStartCoordinator starts);
    }

    /** 同一旧音频链的组件集合；用于配置注入，不提供新的业务状态或线程。 */
    public record LegacyAudioComponents(
            com.czur.reference.application.port.MeetingDeletionPort deletion,
            MeetingInputReconciler inputReconciler, RealtimeAudioIngress audioIngress,
            RealtimeTranscriptSessions transcripts,
            JdbcArtifactContentQueryPort.LegacyArtifactValidator artifactValidator) { }

    /**
     * 可选会后人物插件工厂；仅在唯一数据库就绪后调用一次。实现可复用给定
     * Context 和 Store 创建自己的人员映射适配器，不得另建会议池／状态机。
     * 关闭前宿主先停止请求；工厂创建的插件不持有本次 JDBC 连接。
     */
    @FunctionalInterface
    public interface SpeakerIdentityFactory {
        SpeakerIdentityProvider create(JdbcDatabaseContext database, ArtifactStore artifacts);
    }

    /**
     * 参考部署显式接入旧WSS/Mock能力。共享核心不会选择或构造这些实现。
     * 对外账号、资格、模型、登录入口和旧音频工厂由宿主注入，资源归属保持既有规则。
     */
    public static MeetingHttpAssembly startWithLegacy(
            MeetingDeploymentConfig config, Clock clock, ArtifactStore artifactStore,
            IdentityProvider identityProvider, EntitlementPolicy entitlementPolicy,
            ModelSessionIssuer modelSessionIssuer,
            UnaryOperator<EntitlementProvider> decorator,
            com.czur.reference.application.http.SessionLoginEndpoint login,
            LegacyAudioFactory legacyFactory) {
        Objects.requireNonNull(identityProvider, "identityProvider");
        Objects.requireNonNull(entitlementPolicy, "entitlementPolicy");
        Objects.requireNonNull(decorator, "decorator");
        Objects.requireNonNull(legacyFactory, "legacyFactory");
        return start(config, clock, artifactStore, identityProvider, entitlementPolicy,
                modelSessionIssuer, decorator, login, legacyFactory, false);
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
    public static MeetingHttpAssembly startClientModel(
            MeetingDeploymentConfig config, Clock clock, ArtifactStore artifactStore,
            IdentityProvider identityProvider, EntitlementPolicy entitlementPolicy,
            ModelSessionIssuer modelSessionIssuer,
            UnaryOperator<EntitlementProvider> entitlementProviderDecorator) {
        return startClientModel(config, clock, artifactStore, identityProvider,
                entitlementPolicy, modelSessionIssuer, entitlementProviderDecorator,
                null, null);
    }

    /**
     * SDK 直连模型的可替换宿主装配。
     *
     * <p>登录入口和总结材料发行器都属于宿主插件：参考宿主可以装配预置登录和
     * 默认 Provider，合作伙伴则可传入自己的实现。传入 {@code null} 的登录入口
     * 表示该 HTTP 实例不提供演示登录；传入 {@code null} 的总结发行器表示继续
     * 使用可信配置中的默认实现（当前参考配置为百炼），没有配置时总结能力保持
     * 关闭。会议状态机、权限校验、结果发布不会因替换 Provider 而复制。</p>
     */
    public static MeetingHttpAssembly startClientModel(
            MeetingDeploymentConfig config, Clock clock, ArtifactStore artifactStore,
            IdentityProvider identityProvider, EntitlementPolicy entitlementPolicy,
            ModelSessionIssuer modelSessionIssuer,
            UnaryOperator<EntitlementProvider> entitlementProviderDecorator,
            com.czur.reference.application.http.SessionLoginEndpoint login,
            SummarySessionIssuer summarySessionIssuer) {
        return startClientModel(config, clock, artifactStore, identityProvider,
                entitlementPolicy, modelSessionIssuer, entitlementProviderDecorator,
                login, summarySessionIssuer, null);
    }

    /** 显式启用会后人物扩展；既有重载继续不访问人员库、不虚报跨会议能力。 */
    public static MeetingHttpAssembly startClientModel(
            MeetingDeploymentConfig config, Clock clock, ArtifactStore artifactStore,
            IdentityProvider identityProvider, EntitlementPolicy entitlementPolicy,
            ModelSessionIssuer modelSessionIssuer,
            UnaryOperator<EntitlementProvider> entitlementProviderDecorator,
            com.czur.reference.application.http.SessionLoginEndpoint login,
            SummarySessionIssuer summarySessionIssuer, SpeakerIdentityFactory speakerFactory) {
        return startClientModel(config, clock, artifactStore, identityProvider, entitlementPolicy,
                modelSessionIssuer, entitlementProviderDecorator, login, summarySessionIssuer, speakerFactory, false);
    }

    /** 显式装配人工结果编辑；既有 CZUR/伙伴重载保持关闭，无需新增表或声纹供应商。 */
    public static MeetingHttpAssembly startClientModel(
            MeetingDeploymentConfig config, Clock clock, ArtifactStore artifactStore,
            IdentityProvider identityProvider, EntitlementPolicy entitlementPolicy,
            ModelSessionIssuer modelSessionIssuer,
            UnaryOperator<EntitlementProvider> entitlementProviderDecorator,
            com.czur.reference.application.http.SessionLoginEndpoint login,
            SummarySessionIssuer summarySessionIssuer, SpeakerIdentityFactory speakerFactory,
            boolean speakerEditingEnabled) {
        return startClientModel(config, clock, artifactStore, identityProvider, entitlementPolicy,
                modelSessionIssuer, entitlementProviderDecorator, login, summarySessionIssuer,
                speakerFactory, speakerEditingEnabled, null);
    }

    /**
     * 结果编辑策略独立于会议身份；旧重载保持所有者登录要求。
     * 参考宿主显式选择 resultLink，伙伴可通过 Factory 注入自己的策略。
     */
    public static MeetingHttpAssembly startClientModel(
            MeetingDeploymentConfig config, Clock clock, ArtifactStore artifactStore,
            IdentityProvider identityProvider, EntitlementPolicy entitlementPolicy,
            ModelSessionIssuer modelSessionIssuer,
            UnaryOperator<EntitlementProvider> entitlementProviderDecorator,
            com.czur.reference.application.http.SessionLoginEndpoint login,
            SummarySessionIssuer summarySessionIssuer, SpeakerIdentityFactory speakerFactory,
            boolean speakerEditingEnabled, SpeakerResultAccessPolicy.Factory speakerAccessFactory) {
        Objects.requireNonNull(artifactStore, "artifactStore");
        Objects.requireNonNull(identityProvider, "identityProvider");
        Objects.requireNonNull(entitlementPolicy, "entitlementPolicy");
        Objects.requireNonNull(modelSessionIssuer, "modelSessionIssuer");
        Objects.requireNonNull(entitlementProviderDecorator, "entitlementProviderDecorator");
        return start(config, clock, artifactStore, identityProvider, entitlementPolicy,
                modelSessionIssuer, entitlementProviderDecorator, login, null, true,
                summarySessionIssuer, speakerFactory, speakerEditingEnabled, speakerAccessFactory);
    }

    private static MeetingHttpAssembly start(
            MeetingDeploymentConfig config, Clock clock,
            ArtifactStore artifactStore, IdentityProvider identityProvider,
            EntitlementPolicy entitlementPolicy, ModelSessionIssuer modelSessionIssuer,
            UnaryOperator<EntitlementProvider> entitlementProviderDecorator,
            com.czur.reference.application.http.SessionLoginEndpoint login,
            LegacyAudioFactory legacyFactory, boolean clientModel) {
        return start(config, clock, artifactStore, identityProvider, entitlementPolicy,
                modelSessionIssuer, entitlementProviderDecorator, login, legacyFactory,
                clientModel, null);
    }

    private static MeetingHttpAssembly start(
            MeetingDeploymentConfig config, Clock clock,
            ArtifactStore artifactStore, IdentityProvider identityProvider,
            EntitlementPolicy entitlementPolicy, ModelSessionIssuer modelSessionIssuer,
            UnaryOperator<EntitlementProvider> entitlementProviderDecorator,
            com.czur.reference.application.http.SessionLoginEndpoint login,
            LegacyAudioFactory legacyFactory, boolean clientModel,
            SummarySessionIssuer suppliedSummarySessionIssuer) {
        return start(config, clock, artifactStore, identityProvider, entitlementPolicy,
                modelSessionIssuer, entitlementProviderDecorator, login, legacyFactory,
                clientModel, suppliedSummarySessionIssuer, null);
    }

    private static MeetingHttpAssembly start(
            MeetingDeploymentConfig config, Clock clock,
            ArtifactStore artifactStore, IdentityProvider identityProvider,
            EntitlementPolicy entitlementPolicy, ModelSessionIssuer modelSessionIssuer,
            UnaryOperator<EntitlementProvider> entitlementProviderDecorator,
            com.czur.reference.application.http.SessionLoginEndpoint login,
            LegacyAudioFactory legacyFactory, boolean clientModel,
            SummarySessionIssuer suppliedSummarySessionIssuer, SpeakerIdentityFactory speakerFactory) {
        return start(config, clock, artifactStore, identityProvider, entitlementPolicy, modelSessionIssuer,
                entitlementProviderDecorator, login, legacyFactory, clientModel, suppliedSummarySessionIssuer,
                speakerFactory, false);
    }

    private static MeetingHttpAssembly start(
            MeetingDeploymentConfig config, Clock clock, ArtifactStore artifactStore,
            IdentityProvider identityProvider, EntitlementPolicy entitlementPolicy,
            ModelSessionIssuer modelSessionIssuer, UnaryOperator<EntitlementProvider> entitlementProviderDecorator,
            com.czur.reference.application.http.SessionLoginEndpoint login,
            LegacyAudioFactory legacyFactory, boolean clientModel,
            SummarySessionIssuer suppliedSummarySessionIssuer, SpeakerIdentityFactory speakerFactory,
            boolean speakerEditingEnabled) {
        return start(config, clock, artifactStore, identityProvider, entitlementPolicy,
                modelSessionIssuer, entitlementProviderDecorator, login, legacyFactory, clientModel,
                suppliedSummarySessionIssuer, speakerFactory, speakerEditingEnabled, null);
    }

    private static MeetingHttpAssembly start(
            MeetingDeploymentConfig config, Clock clock, ArtifactStore artifactStore,
            IdentityProvider identityProvider, EntitlementPolicy entitlementPolicy,
            ModelSessionIssuer modelSessionIssuer, UnaryOperator<EntitlementProvider> entitlementProviderDecorator,
            com.czur.reference.application.http.SessionLoginEndpoint login,
            LegacyAudioFactory legacyFactory, boolean clientModel,
            SummarySessionIssuer suppliedSummarySessionIssuer, SpeakerIdentityFactory speakerFactory,
            boolean speakerEditingEnabled, SpeakerResultAccessPolicy.Factory speakerAccessFactory) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        JdbcDatabaseRuntime database = clientModel ? null : JdbcDatabaseRuntime.start(config.database());
        JdbcDatabaseContext databaseContext = clientModel ? JdbcDatabaseContext.start(config.database())
                : database.databaseContext();
        boolean realtimeRenewEnabled = !clientModel && config.realtimeRenewEnabled();
        // 发布、读取、HTTP 响应共用同一部署预算；按最长会议时长推导，不授权无界正文。
        long bodyArtifactMaxBytes = MeetingBodyArtifactBudget
                .forMaxDurationMs(config.runtimeLimits().maxMeetingDurationMs());
        MeetingHttpRuntime runtime = null;
        try {
            if (realtimeRenewEnabled) {
                DatabaseReadiness readiness = database.checkRealtimeCredentialReadiness();
                if (!readiness.ready()) {
                    throw new DatabaseStartupException(readiness);
                }
            }
            DataSource dataSource = databaseContext.dataSource();
            SpeakerIdentityProvider speakerIdentity = speakerFactory == null ? null
                    : Objects.requireNonNull(speakerFactory.create(databaseContext, artifactStore),
                            "speaker identity provider");
            boolean speakerAvailable = speakerIdentity != null && speakerIdentity.descriptor()
                    .supports(CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING);
            if (speakerIdentity != null && !speakerAvailable) {
                throw new IllegalArgumentException("speaker provider must declare cross-meeting identity");
            }
            var registered = new java.util.TreeSet<>(config.mockCapabilities().capabilities());
            registered.remove(CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING);
            if (speakerAvailable) registered.add(CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING);
            CapabilityDescriptor registeredCapabilities = new CapabilityDescriptor(
                    config.mockCapabilities().providerId(), config.mockCapabilities().providerVersion(),
                    config.mockCapabilities().spiVersion(), registered);
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
            IdentityProvider identity = identityProvider;
            EntitlementPolicy policy = entitlementPolicy;
            // 工厂只装配一次；Create、Stop与Worker访问器共用同一装饰实例。
            // 输入Provider仍在唯一会议库保存所有预占／用量操作和绑定事实。
            var jdbcEntitlement = new JdbcEntitlementProvider(databaseContext, policy, clock);
            EntitlementProvider entitlement = Objects.requireNonNull(
                    entitlementProviderDecorator.apply(jdbcEntitlement),
                    "entitlementProviderDecorator result");
            var errors = new HttpErrorMapper(Math.toIntExact(bodyArtifactMaxBytes));
            var referenceLogin = login;
            var decoder = new ReferenceMeetingHttpRequestDecoder(
                    config.audience(), config.clientId());
            var resolver = new AuthenticatedMeetingHttpContextResolver(
                    config.deploymentScope(), config.audience(), config.clientId(),
                    identity, errors);
            // Create 开关只控制新建；关闭后仍可能有先前创建的 V013/V015 会议
            // 等待 Stop 收口，因此同一 Reconciler 独立于该开关。旧 v1 会议没有
            // 完整输入事实，触发结果不会改写已提交 Stop 或权威查询响应。
            ProductProfileCatalog profiles = (id, revision) ->
                    config.profile().id().equals(id)
                            && config.profile().revision() == revision
                            ? Optional.of(config.profile()) : Optional.empty();
            var startCoordinator = new RealtimeMeetingStartCoordinator(
                    config.deploymentScope(), new JdbcRealtimeStartFactsLookup(databaseContext),
                    profiles, registeredCapabilities, config.capabilityLimits(), clock,
                    new JdbcMeetingStartExecutionUnitOfWork(
                            databaseContext, new CapabilityPlanner()));
            LegacyAudioComponents legacy = clientModel ? null : Objects.requireNonNull(
                    legacyFactory.create(dataSource, config, clock, ids::newReceipt, startCoordinator),
                    "legacyFactory result");
            var deletion = legacy == null ? null : legacy.deletion();
            var inputReconciler = legacy == null ? null : legacy.inputReconciler();
            var realtimeIngress = legacy == null ? null : legacy.audioIngress();
            var realtimeTranscripts = legacy == null ? null : legacy.transcripts();
            // 正常 Stop 与弃场收尾共用同一个 Finalizer 实例：结果发布、状态前进和
            // 用量结算边界只有一份实现；宿主不得另造并行状态机。
            var clientResultFinalizer = new JdbcClientMeetingResultFinalizer(
                    databaseContext, ids::newReceipt, clock, artifactStore, entitlement,
                    speakerIdentity, bodyArtifactMaxBytes);
            var stopService = new AuthenticatedMeetingStopApplicationService(
                    config.deploymentScope(), identity, stop, query, inputReconciler,
                    clientResultFinalizer);
            var renewService = realtimeRenewEnabled
                    ? new AuthenticatedRealtimeTokenRenewApplicationService(
                            config.deploymentScope(), identity,
                            new JdbcRenewRealtimeTokenUnitOfWork(dataSource, clock,
                                    config.wssTokenTtl(), new SecureRandom()))
                    : null;
            var realtimeVerifier = clientModel ? null : new JdbcRealtimeCredentialVerifier(
                    dataSource, config.deploymentScope(), clock);

            // append 受理与发布、读取、HTTP 响应共用同一个部署正文预算：超限在
            // 追加时提前拒绝，避免 Stop 才 CONTENT_TOO_LARGE 而永久停在 STOPPING。
            var clientInput = new JdbcClientMeetingInputPort(databaseContext,
                    startCoordinator, clock, artifactStore, bodyArtifactMaxBytes);
            MeetingManifestPort manifest = config.resultLinkTtl()
                    .<MeetingManifestPort>map(ttl -> new JdbcMeetingManifestQueryPort(
                            databaseContext, new ManifestResultAccessPolicy(clock, ttl)))
                    .orElse(null);
            JdbcArtifactContentQueryPort artifactQuery = manifest != null
                    ? new JdbcArtifactContentQueryPort(
                            databaseContext, manifest, artifactStore,
                            legacy == null ? null : legacy.artifactValidator(),
                            bodyArtifactMaxBytes) : null;
            MeetingArtifactContentPort artifact = artifactQuery;
            MeetingArtifactMediaPort artifactMedia = artifactQuery;
            // REST 补拉和后续 WSS 恢复共用 meeting_event；不建立第二套事件事实源。
            var events = new JdbcMeetingEventLogPort(databaseContext);
            // 只替换模型会话材料签发端口。旧 Create.realtime 与 WSS Mock
            // 继续保留既有语义，Android 直连模型的选择由 adapterId 驱动。
            // 模型默认选择属于宿主装配；共享核心仅使用显式注入的发行器。
            ModelSessionIssuer modelSessions = modelSessionIssuer;
            // 百炼只是参考默认实现；核心只持有中性的总结发行 SPI，未来宿主可注入其他 Provider。
            SummarySessionIssuer summarySessions = suppliedSummarySessionIssuer != null
                    ? suppliedSummarySessionIssuer
                    : config.bailianSummarySession()
                            .<SummarySessionIssuer>map(summary -> new BailianSummarySessionIssuer(summary, clock))
                            .orElse(null);
            // 能力发现必须依据最终装配结果；不能仅依据百炼配置隐藏宿主自定义总结 Provider。
            var speakerAccess = !speakerEditingEnabled ? null : speakerAccessFactory == null
                    ? SpeakerResultAccessPolicy.ownerOnly(resolver, errors)
                    : Objects.requireNonNull(speakerAccessFactory.create(resolver, manifest,
                            config.deploymentScope(), errors), "speaker access policy");
            var capabilities = new ReferenceDeploymentCapabilitiesEndpoint(
                    config, errors, summarySessions != null, speakerAvailable, speakerEditingEnabled,
                    speakerAccess == null || speakerAccess.requiresLogin());
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
                        config.trustedEstimate(), profiles, registeredCapabilities,
                        config.capabilityLimits());
                runtime = new MeetingHttpRuntime(query, deletion, decoder, errors, stopService,
                        create, new CreateMeetingHttpRequestDecoder(
                                config.audience(), config.clientId(), config.modelLanguages()), true, manifest,
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
                            login == null ? "EXTERNAL" : "DEPLOYMENT_LOGIN",
                            modelSessionIssuer == null ? "DEFAULT" : "EXTERNAL", clientModel});
            var enrollment = speakerAvailable
                    ? new JdbcSpeakerIdentityEnrollmentPort(databaseContext, artifactStore,
                            speakerIdentity, bodyArtifactMaxBytes) : null;
            return new MeetingHttpAssembly(database, databaseContext, config.deploymentScope(),
                    runtime, resolver, errors,
                    realtimeRenewEnabled, realtimeVerifier, realtimeIngress,
                    realtimeTranscripts, inputReconciler, events, clock,
                    config.resultEmail().map(email -> new ResultEmailEndpoint(query, manifest,
                            new SmtpResultEmailSender(email), errors)).orElse(null),
                    artifactMedia, entitlement, clientResultFinalizer,
                    artifactMedia == null ? null
                            : new MeetingArtifactHttpEndpoint(clientInput, artifactMedia, errors),
                    enrollment == null ? null : new SpeakerIdentityEnrollmentEndpoint(enrollment, errors),
                    speakerEditingEnabled ? new SpeakerNameEditEndpoint(
                            new JdbcSpeakerNameEditPort(databaseContext, artifactStore, enrollment,
                                    bodyArtifactMaxBytes), errors,
                            speakerAccess) : null,
                    // 心跳与查询共用同一连接池和服务端时钟；未开启 Create 也不关闭，
                    // 以便先前创建、仍处于会中的会议继续记录开始与活跃事实。
                    new MeetingHeartbeatEndpoint(query,
                            new JdbcMeetingHeartbeatStore(databaseContext), clock,
                            config.heartbeatIntervalMs(), errors),
                    AudioArtifactBudget.forMaxDurationMs(config.runtimeLimits().maxMeetingDurationMs()),
                    bodyArtifactMaxBytes);
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
     * 返回与统一 Manifest 访问决定共用的媒体流端口；未开启结果链接时为空。
     * 调用方只负责在响应流结束后关闭 {@link MeetingArtifactMediaPort.Found}，不解释
     * Store binding，也不把长音频转成 JSON 或内存字节数组。
     */
    public MeetingArtifactMediaPort artifactMediaPort() { return artifactMedia; }

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
     * 返回与客户端 Stop 共用的弃场收尾端口，供本机进程按心跳窗口收口卡住的会议。
     *
     * <p>实现复用本装配根的唯一数据库上下文、结果发布逻辑和权益 Provider；不
     * 创建第二套会议状态机或连接池。调用方负责串行、周期性地调用，并在本装配
     * 关闭前停止调用。</p>
     */
    public AbandonedMeetingFinalizer abandonedMeetingFinalizer() {
        return abandonedMeetingFinalizer;
    }

    /**
     * 返回与 Stop、弃场共用同一实例的客户端结果收口端口，供待办/冻结用量
     * 扫描器重试。调用方不得替换或关闭该实例；它不拥有连接池。
     */
    public ClientMeetingResultFinalizer clientResultFinalizer() {
        return abandonedMeetingFinalizer;
    }

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
                                || database.checkRealtimeCredentialReadiness().ready()),
                audioArtifactMaxBytes);
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
