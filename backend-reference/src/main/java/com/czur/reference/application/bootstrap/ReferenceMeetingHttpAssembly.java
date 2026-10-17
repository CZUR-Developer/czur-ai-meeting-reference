package com.czur.reference.application.bootstrap;

import com.czur.reference.application.entitlement.FixedEntitlementPolicy;
import com.czur.reference.application.finalization.DeterministicMockFinalizationPlanner;
import com.czur.reference.application.finalization.MeetingInputReconciler;
import com.czur.reference.application.http.HttpErrorMapper;
import com.czur.reference.application.http.ReferenceDevSessionLoginEndpoint;
import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityProvider;
import com.czur.reference.application.model.ModelSessionIssuer;
import com.czur.reference.application.model.SummarySessionIssuer;
import com.czur.reference.application.model.SampleJsonWsModelSessionIssuer;
import com.czur.reference.application.model.TingwuModelSessionIssuer;
import com.czur.reference.application.persistence.jdbc.JdbcAcceptedInputFactLookup;
import com.czur.reference.application.persistence.jdbc.JdbcCompletedAudioReadinessPort;
import com.czur.reference.application.persistence.jdbc.JdbcDurableAudioInputPort;
import com.czur.reference.application.persistence.jdbc.JdbcFinalizationAdmissionUnitOfWork;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingArtifactContentQueryPort;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingDeletionAdmissionUnitOfWork;
import com.czur.reference.application.persistence.jdbc.JdbcMeetingInputClosureContextPort;
import com.czur.reference.application.persistence.jdbc.JdbcRealtimeMockSessionSpecLookup;
import com.czur.reference.application.persistence.jdbc.JdbcRealtimeTranscriptEventPublisher;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.EntitlementPolicy;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.realtime.RealtimeAudioIngress;
import com.czur.reference.application.realtime.RealtimeMockTranscriptRuntime;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 参考应用的默认装配根，保留既有调用入口。
 *
 * <p>演示身份、预置登录、Mock/WSS及默认模型选择只在此类接线；会议状态、HTTP、
 * JDBC连接池及客户端结果都交给共享MeetingHttpAssembly。薄facade继承共享实例的
 * 资源与关闭标记，不会构造第二份状态机或池；调用方仍先停止listener再close。</p>
 */
public final class ReferenceMeetingHttpAssembly extends MeetingHttpAssembly {
    private ReferenceMeetingHttpAssembly(MeetingHttpAssembly core) { super(core); }

    public static ReferenceMeetingHttpAssembly start(Map<String, String> values, Clock clock) {
        return start(ReferenceMeetingDeploymentConfig.fromEnvironment(values), clock, null);
    }
    public static ReferenceMeetingHttpAssembly start(Map<String, String> values,
            Clock clock, ArtifactStore artifacts) {
        return start(ReferenceMeetingDeploymentConfig.fromEnvironment(values), clock, artifacts);
    }
    public static ReferenceMeetingHttpAssembly start(ReferenceMeetingDeploymentConfig config, Clock clock) {
        return start(config, clock, null);
    }

    /** 默认参考实现；配置须显式启用演示身份，旧默认行为保持。 */
    public static ReferenceMeetingHttpAssembly start(ReferenceMeetingDeploymentConfig config,
            Clock clock, ArtifactStore artifacts) {
        Objects.requireNonNull(config, "config");
        if (!config.identity().enabled()) {
            throw new IllegalArgumentException("外部身份配置须显式注入身份和权益策略");
        }
        var identity = new ReferenceDevIdentityProvider(config.identity(), clock);
        var policy = new FixedEntitlementPolicy(config.entitlement(), clock, new RandomEntitlementIds());
        var login = config.login().map(value ->
                new ReferenceDevSessionLoginEndpoint(value, new HttpErrorMapper(), clock)).orElse(null);
        return new ReferenceMeetingHttpAssembly(MeetingHttpAssembly.startWithLegacy(config, clock,
                artifacts, identity, policy, defaultIssuer(config, clock), UnaryOperator.identity(),
                login, ReferenceMeetingHttpAssembly::legacyAudio));
    }

    public static ReferenceMeetingHttpAssembly start(ReferenceMeetingDeploymentConfig config,
            Clock clock, ArtifactStore artifacts, IdentityProvider identity, EntitlementPolicy policy) {
        return start(config, clock, artifacts, identity, policy, null);
    }
    public static ReferenceMeetingHttpAssembly start(ReferenceMeetingDeploymentConfig config,
            Clock clock, ArtifactStore artifacts, IdentityProvider identity, EntitlementPolicy policy,
            ModelSessionIssuer issuer) {
        return start(config, clock, artifacts, identity, policy, issuer, UnaryOperator.identity());
    }

    /** 兼容旧外部账号的参考WSS入口；无演示登录，复用共享会议核心及唯一权益账本。 */
    public static ReferenceMeetingHttpAssembly start(ReferenceMeetingDeploymentConfig config,
            Clock clock, ArtifactStore artifacts, IdentityProvider identity, EntitlementPolicy policy,
            ModelSessionIssuer issuer, UnaryOperator<EntitlementProvider> decorator) {
        return new ReferenceMeetingHttpAssembly(MeetingHttpAssembly.startWithLegacy(config, clock,
                artifacts, identity, policy, issuer == null ? defaultIssuer(config, clock) : issuer,
                decorator, null, ReferenceMeetingHttpAssembly::legacyAudio));
    }

    /** 兼容既有参考调用；生产宿主直接使用中性MeetingHttpAssembly入口。 */
    public static ReferenceMeetingHttpAssembly startClientModel(ReferenceMeetingDeploymentConfig config,
            Clock clock, ArtifactStore artifacts, IdentityProvider identity, EntitlementPolicy policy,
            ModelSessionIssuer issuer, UnaryOperator<EntitlementProvider> decorator) {
        return startClientModel(config, clock, artifacts, identity, policy, issuer, decorator,
                null, null);
    }

    /**
     * 参考宿主的 SDK 直连模型装配扩展点。登录入口、总结发行器和实时模型发行器
     * 均由宿主选择；会议核心只消费接口，不把百炼、听悟或参考预置账号写入流程。
     */
    public static ReferenceMeetingHttpAssembly startClientModel(ReferenceMeetingDeploymentConfig config,
            Clock clock, ArtifactStore artifacts, IdentityProvider identity, EntitlementPolicy policy,
            ModelSessionIssuer issuer, UnaryOperator<EntitlementProvider> decorator,
            com.czur.reference.application.http.SessionLoginEndpoint login,
            SummarySessionIssuer summaryIssuer) {
        return startClientModel(config, clock, artifacts, identity, policy, issuer, decorator,
                login, summaryIssuer, null);
    }

    /** 默认关闭的会后人物扩展；Factory 复用公共装配的唯一 Context 与 Store。 */
    public static ReferenceMeetingHttpAssembly startClientModel(ReferenceMeetingDeploymentConfig config,
            Clock clock, ArtifactStore artifacts, IdentityProvider identity, EntitlementPolicy policy,
            ModelSessionIssuer issuer, UnaryOperator<EntitlementProvider> decorator,
            com.czur.reference.application.http.SessionLoginEndpoint login,
            SummarySessionIssuer summaryIssuer, SpeakerIdentityFactory speakerFactory) {
        return new ReferenceMeetingHttpAssembly(MeetingHttpAssembly.startClientModel(config, clock,
                artifacts, identity, policy, issuer, decorator, login, summaryIssuer, speakerFactory));
    }

    /**
     * 参考本机进程使用的默认模型发行器选择。
     *
     * <p>模型材料仍由部署配置决定；此方法只把参考宿主已经提供的
     * Adapter 装配到公共核心，不把供应商协议下沉到核心。客户端直连
     * 进程和旧 WSS 兼容进程共用这一选择规则。</p>
     */
    static ModelSessionIssuer defaultIssuer(MeetingDeploymentConfig config, Clock clock) {
        return config.sampleJsonWsEndpoint().<ModelSessionIssuer>map(endpoint ->
                new SampleJsonWsModelSessionIssuer(endpoint, clock)).orElseGet(() ->
                config.tingwuModelSession().<ModelSessionIssuer>map(value ->
                        new TingwuModelSessionIssuer(value, clock)).orElse(null));
    }

    /** 将原有Mock音频链原样注册；只读取已有数据源，不建立新池或新增后台线程。 */
    private static LegacyAudioComponents legacyAudio(javax.sql.DataSource source,
            MeetingDeploymentConfig config, Clock clock,
            com.czur.reference.application.port.FinalizationReceiptGenerator receipts,
            com.czur.reference.application.realtime.RealtimeMeetingStartCoordinator starts) {
        var audio = new JdbcDurableAudioInputPort(source, config.deploymentScope());
        var reconciler = new MeetingInputReconciler(
                new JdbcMeetingInputClosureContextPort(source, config.deploymentScope()),
                new JdbcAcceptedInputFactLookup(source), new JdbcCompletedAudioReadinessPort(source),
                new JdbcFinalizationAdmissionUnitOfWork(source, receipts,
                        new DeterministicMockFinalizationPlanner()), clock);
        var transcripts = new RealtimeMockTranscriptRuntime(
                new JdbcRealtimeMockSessionSpecLookup(source, config.deploymentScope()), audio,
                new JdbcRealtimeTranscriptEventPublisher(source, clock), config.mockCapabilities(), clock);
        return new LegacyAudioComponents(new JdbcMeetingDeletionAdmissionUnitOfWork(source, clock),
                reconciler, new RealtimeAudioIngress(starts, audio), transcripts,
                JdbcMeetingArtifactContentQueryPort::matchesLegacy);
    }

    /** 参考默认策略的无业务语义ID；具体授权／预占算法仅由共享策略实现。 */
    static final class RandomEntitlementIds implements EntitlementIdGenerator {
        private final SecureRandom random = new SecureRandom();
        @Override public String newGrantId() { return next("grant_"); }
        @Override public String newReservationId() { return next("reservation_"); }
        @Override public String newLeaseToken() { return next("lease_"); }
        private String next(String prefix) {
            byte[] bytes = new byte[24];
            random.nextBytes(bytes);
            return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }
}
