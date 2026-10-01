package com.czur.reference.application.bootstrap;

import com.czur.reference.application.command.TrustedCreateRuntimeLimits;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicyConfig;
import com.czur.reference.application.finalization.MockFinalizationArtifactContent;
import com.czur.reference.application.finalization.ProductProfileSnapshot;
import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityConfig;
import com.czur.reference.application.persistence.jdbc.DatabaseRuntimeConfig;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 单一参考部署的不可变可信配置；不创建连接池、身份凭据、Token 或网络监听。
 *
 * <p>同一映射只解析一次。数据库与演示身份沿用原有配置规则；部署范围、单版本
 * Profile、权益、Mock 描述、能力开关和 Create 限制在取得数据库连接前交叉校验。
 * 受信 audience/clientId 直接取自身份配置使用的同两个键，后续 Create/Stop
 * 解码器和 GET/DELETE 解析器必须使用本对象的访问器，不得各自再读环境变量。
 * 此类只保存分页密钥的私有字节副本，调用方只能取得副本并须负责清零临时数组。
 * 日志和异常不得拼接输入值；本对象的诊断文本也不显示连接串、身份或 Secret。</p>
 */
public final class ReferenceMeetingDeploymentConfig {
    // 首版公开音频仅支持 16 kHz、单声道、16-bit PCM，最短帧 20 ms。
    private static final int MINIMUM_PUBLIC_AUDIO_FRAME_BYTES = 16_000 * 2 * 20 / 1_000;
    private static final String MOCK_TRANSCRIPT_WORKFLOW = "mock.transcript";
    private static final String MOCK_TRANSCRIPT_ARTIFACT = "transcript.final";
    public static final String DEPLOYMENT_SCOPE = "AI_MEETING_DEPLOYMENT_SCOPE";
    public static final String PROFILE_ID = "AI_MEETING_PROFILE_ID";
    public static final String PROFILE_REVISION = "AI_MEETING_PROFILE_REVISION";
    public static final String PROFILE_REQUIRED_CAPABILITIES =
            "AI_MEETING_PROFILE_REQUIRED_CAPABILITIES";
    public static final String PROFILE_OPTIONAL_CAPABILITIES =
            "AI_MEETING_PROFILE_OPTIONAL_CAPABILITIES";
    public static final String ENTITLEMENT_SUPPORTED_CAPABILITIES =
            "AI_MEETING_ENTITLEMENT_SUPPORTED_CAPABILITIES";
    public static final String ENTITLEMENT_MAX_DURATION_MS =
            "AI_MEETING_ENTITLEMENT_MAX_DURATION_MS";
    public static final String ENTITLEMENT_GRANT_TTL_MS =
            "AI_MEETING_ENTITLEMENT_GRANT_TTL_MS";
    public static final String ENTITLEMENT_RESERVATION_TTL_MS =
            "AI_MEETING_ENTITLEMENT_RESERVATION_TTL_MS";
    public static final String MOCK_CAPABILITIES = "AI_MEETING_MOCK_CAPABILITIES";
    public static final String DISABLED_CAPABILITIES = "AI_MEETING_DISABLED_CAPABILITIES";
    public static final String ESTIMATED_DURATION_MS = "AI_MEETING_ESTIMATED_DURATION_MS";
    public static final String MAX_MEETING_DURATION_MS = "AI_MEETING_MAX_MEETING_DURATION_MS";
    public static final String MAX_AUDIO_FRAME_BYTES = "AI_MEETING_MAX_AUDIO_FRAME_BYTES";
    public static final String EVENT_REPLAY_WINDOW = "AI_MEETING_EVENT_REPLAY_WINDOW";
    public static final String MAX_REPLAY_AUDIO_MS = "AI_MEETING_MAX_REPLAY_AUDIO_MS";
    public static final String PAGE_TOKEN_SECRET = "AI_MEETING_PAGE_TOKEN_SECRET";
    public static final String WSS_ORIGIN = "AI_MEETING_WSS_ORIGIN";
    public static final String WSS_TOKEN_TTL_MS = "AI_MEETING_WSS_TOKEN_TTL_MS";
    public static final String CREATE_ENABLED = "AI_MEETING_CREATE_ENABLED";
    public static final String REALTIME_RENEW_ENABLED = "AI_MEETING_REALTIME_RENEW_ENABLED";
    public static final String RESULT_LINK_TTL_MS = "AI_MEETING_RESULT_LINK_TTL_MS";

    private final DatabaseRuntimeConfig database;
    private final ReferenceDevIdentityConfig identity;
    private final String deploymentScope;
    private final String audience;
    private final String clientId;
    private final ProductProfile profile;
    private final ReferenceDevEntitlementPolicyConfig entitlement;
    private final CapabilityDescriptor mockCapabilities;
    private final DeploymentLimits capabilityLimits;
    private final UsageEstimate trustedEstimate;
    private final TrustedCreateRuntimeLimits runtimeLimits;
    private final byte[] pageTokenSecret;
    private final URI wssOrigin;
    private final Duration wssTokenTtl;
    private final boolean createEnabled;
    private final boolean realtimeRenewEnabled;
    private final Duration resultLinkTtl;

    private ReferenceMeetingDeploymentConfig(
            DatabaseRuntimeConfig database,
            ReferenceDevIdentityConfig identity,
            String deploymentScope, String audience, String clientId,
            ProductProfile profile, ReferenceDevEntitlementPolicyConfig entitlement,
            CapabilityDescriptor mockCapabilities, DeploymentLimits capabilityLimits,
            UsageEstimate trustedEstimate, TrustedCreateRuntimeLimits runtimeLimits,
            byte[] pageTokenSecret, URI wssOrigin, Duration wssTokenTtl,
            boolean createEnabled, boolean realtimeRenewEnabled, Duration resultLinkTtl) {
        this.database = database;
        this.identity = identity;
        this.deploymentScope = deploymentScope;
        this.audience = audience;
        this.clientId = clientId;
        this.profile = profile;
        this.entitlement = entitlement;
        this.mockCapabilities = mockCapabilities;
        this.capabilityLimits = capabilityLimits;
        this.trustedEstimate = trustedEstimate;
        this.runtimeLimits = runtimeLimits;
        this.pageTokenSecret = pageTokenSecret.clone();
        this.wssOrigin = wssOrigin;
        this.wssTokenTtl = wssTokenTtl;
        this.createEnabled = createEnabled;
        this.realtimeRenewEnabled = realtimeRenewEnabled;
        this.resultLinkTtl = resultLinkTtl;
    }

    /**
     * 从一份调用方提供的映射创建可信配置；不读取进程环境、不访问数据库或网络。
     *
     * @param source 必需值和 Secret 均由部署方注入的映射，不会被修改或保存；
     *               调用期间调用方不得并发修改它
     * @return 已完成本地与跨组件一致性校验的不可变配置
     * @throws IllegalArgumentException 缺失、格式、范围或能力链不自洽；消息仅含字段名
     */
    public static ReferenceMeetingDeploymentConfig fromEnvironment(
            Map<String, String> source) {
        Objects.requireNonNull(source, "source");
        final Map<String, String> environment;
        try {
            // 先取得私有快照，后续 DB、身份与应用字段只从这同一份事实读取。
            // 并发修改或 null 项不能留下半配置；调用方仍须在复制期间停止写入。
            environment = Map.copyOf(new HashMap<>(source));
        } catch (RuntimeException malformed) {
            throw invalid("environment");
        }
        DatabaseRuntimeConfig database = DatabaseRuntimeConfig.fromEnvironment(environment);
        ReferenceDevIdentityConfig identity = ReferenceDevIdentityConfig.fromEnvironment(environment);
        if (!identity.enabled()) {
            throw invalid(ReferenceDevIdentityConfig.ENABLED);
        }
        String scope = required(environment, DEPLOYMENT_SCOPE);
        // IdentityConfig 已用同一映射验证这两个必填字段；不需要修改其包内 API。
        String audience = required(environment, ReferenceDevIdentityConfig.AUDIENCE);
        String clientId = required(environment, ReferenceDevIdentityConfig.CLIENT_ID);
        String profileId = required(environment, PROFILE_ID);
        int revision = positiveInt(environment, PROFILE_REVISION);
        Set<String> required = capabilities(environment, PROFILE_REQUIRED_CAPABILITIES, true);
        Set<String> optional = capabilities(environment, PROFILE_OPTIONAL_CAPABILITIES, false);
        ProductProfile profile;
        try {
            profile = new ProductProfile(profileId, revision, required, optional,
                    Map.of(), Map.of(), Map.of(),
                    Map.of(CapabilityIds.TRANSCRIPT_REALTIME,
                            List.of(MOCK_TRANSCRIPT_WORKFLOW)),
                    Map.of(CapabilityIds.TRANSCRIPT_REALTIME,
                            List.of(MOCK_TRANSCRIPT_ARTIFACT)));
            // Start 与最终收口依赖持久快照中的完整执行映射，不能只校验 Profile ID。
            ProductProfileSnapshot.from(profile);
        } catch (RuntimeException malformed) {
            throw invalid(PROFILE_REQUIRED_CAPABILITIES);
        }
        if (!profile.requiredCapabilities().contains(CapabilityIds.TRANSCRIPT_REALTIME)) {
            throw invalid(PROFILE_REQUIRED_CAPABILITIES);
        }
        if (profile.affectedWorkflowsOf(CapabilityIds.TRANSCRIPT_REALTIME).isEmpty()
                || profile.producedArtifactsOf(CapabilityIds.TRANSCRIPT_REALTIME).isEmpty()
                || !MockFinalizationArtifactContent.ARTIFACT_TYPES.contains(
                        MOCK_TRANSCRIPT_ARTIFACT)) {
            throw invalid(PROFILE_REQUIRED_CAPABILITIES);
        }

        Set<String> supported = capabilities(environment,
                ENTITLEMENT_SUPPORTED_CAPABILITIES, true);
        long maxEntitled = positiveLong(environment, ENTITLEMENT_MAX_DURATION_MS);
        Duration grantTtl = positiveDuration(environment, ENTITLEMENT_GRANT_TTL_MS);
        Duration reservationTtl = positiveDuration(environment, ENTITLEMENT_RESERVATION_TTL_MS);
        ReferenceDevEntitlementPolicyConfig entitlement;
        try {
            entitlement = new ReferenceDevEntitlementPolicyConfig(scope, profile.id(),
                    profile.revision(), supported, maxEntitled, grantTtl, reservationTtl);
        } catch (RuntimeException malformed) {
            throw invalid(ENTITLEMENT_RESERVATION_TTL_MS);
        }
        if (!profile.allCapabilities().containsAll(supported)
                || !supported.containsAll(profile.requiredCapabilities())) {
            throw invalid(ENTITLEMENT_SUPPORTED_CAPABILITIES);
        }

        Set<String> mockDeclared = capabilities(environment, MOCK_CAPABILITIES, true);
        Set<String> disabled = capabilities(environment, DISABLED_CAPABILITIES, false);
        CapabilityDescriptor mock;
        DeploymentLimits limits;
        try {
            mock = new CapabilityDescriptor("reference-mock", "1", 1, mockDeclared);
            limits = new DeploymentLimits(disabled);
        } catch (RuntimeException malformed) {
            throw invalid(MOCK_CAPABILITIES);
        }
        if (!mock.capabilities().containsAll(profile.requiredCapabilities())) {
            throw invalid(MOCK_CAPABILITIES);
        }
        for (String capability : profile.requiredCapabilities()) {
            if (!limits.permits(capability)) {
                throw invalid(DISABLED_CAPABILITIES);
            }
            if (profile.affectedWorkflowsOf(capability).isEmpty()
                    || profile.producedArtifactsOf(capability).isEmpty()) {
                throw invalid(PROFILE_REQUIRED_CAPABILITIES);
            }
        }
        for (String capability : profile.optionalCapabilities()) {
            if (supported.contains(capability) && mock.supports(capability)
                    && limits.permits(capability)
                    && (profile.affectedWorkflowsOf(capability).isEmpty()
                    || profile.producedArtifactsOf(capability).isEmpty())) {
                // 当前参考实现仅给必选 ASR 冻结执行映射。可选能力允许因某方缺失
                // 而 DISABLE，但不能在三方都声明可用时伪造一个无法产出结果的 ENABLE。
                throw invalid(PROFILE_OPTIONAL_CAPABILITIES);
            }
        }

        long estimateMs = positiveLong(environment, ESTIMATED_DURATION_MS);
        long maxMeetingMs = positiveLong(environment, MAX_MEETING_DURATION_MS);
        if (estimateMs > maxEntitled || estimateMs > maxMeetingMs) {
            throw invalid(ESTIMATED_DURATION_MS);
        }
        UsageEstimate estimate = new UsageEstimate(UsageUnit.MEETING_DURATION_MS, estimateMs);
        int maxAudioFrameBytes = positiveInt(environment, MAX_AUDIO_FRAME_BYTES);
        if (maxAudioFrameBytes < MINIMUM_PUBLIC_AUDIO_FRAME_BYTES) {
            throw invalid(MAX_AUDIO_FRAME_BYTES);
        }
        int eventReplayWindow = positiveInt(environment, EVENT_REPLAY_WINDOW);
        Long maxReplayAudioMs = optionalNonNegativeLong(environment, MAX_REPLAY_AUDIO_MS);
        TrustedCreateRuntimeLimits runtimeLimits;
        try {
            runtimeLimits = new TrustedCreateRuntimeLimits(maxMeetingMs, maxAudioFrameBytes,
                    eventReplayWindow, maxReplayAudioMs);
        } catch (RuntimeException malformed) {
            throw invalid(MAX_AUDIO_FRAME_BYTES);
        }

        String secretText = required(environment, PAGE_TOKEN_SECRET);
        byte[] secret = secretText.getBytes(StandardCharsets.UTF_8);
        try {
            if (secret.length < 32) {
                throw invalid(PAGE_TOKEN_SECRET);
            }
            URI wssOrigin = wssOrigin(environment);
            Duration tokenTtl = positiveDuration(environment, WSS_TOKEN_TTL_MS);
            boolean enabled = strictBoolean(environment.get(CREATE_ENABLED), CREATE_ENABLED);
            boolean renewEnabled = strictBoolean(
                    environment.get(REALTIME_RENEW_ENABLED), REALTIME_RENEW_ENABLED);
            // 缺项保持旧部署行为：仅新 Manifest 路由关闭，不影响既有会议入口。
            Duration resultLinkTtl = environment.containsKey(RESULT_LINK_TTL_MS)
                    ? positiveDuration(environment, RESULT_LINK_TTL_MS) : null;
            return new ReferenceMeetingDeploymentConfig(database, identity, scope,
                    audience, clientId, profile, entitlement, mock, limits, estimate,
                    runtimeLimits, secret, wssOrigin, tokenTtl,
                    enabled, renewEnabled, resultLinkTtl);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    public DatabaseRuntimeConfig database() { return database; }
    public ReferenceDevIdentityConfig identity() { return identity; }
    public String deploymentScope() { return deploymentScope; }
    /** Create/Stop/GET/DELETE 身份验证共用的服务端受众。 */
    public String audience() { return audience; }
    /** Create/Stop/GET/DELETE 身份验证共用的服务端客户端标识。 */
    public String clientId() { return clientId; }
    public ProductProfile profile() { return profile; }
    public ReferenceDevEntitlementPolicyConfig entitlement() { return entitlement; }
    public CapabilityDescriptor mockCapabilities() { return mockCapabilities; }
    public DeploymentLimits capabilityLimits() { return capabilityLimits; }
    public UsageEstimate trustedEstimate() { return trustedEstimate; }
    public TrustedCreateRuntimeLimits runtimeLimits() { return runtimeLimits; }
    /** 只供分页 HMAC 编解码器构造使用；返回私有密钥的副本。 */
    public byte[] pageTokenSecretCopy() { return pageTokenSecret.clone(); }
    /** 目前仅用于首次会话描述；15-03/04 不提供可连接的 WSS 监听器。 */
    public URI wssOrigin() { return wssOrigin; }
    public Duration wssTokenTtl() { return wssTokenTtl; }
    /** 缺省严格为 false，只有显式字符串 true 才允许开放 Create。 */
    public boolean createEnabled() { return createEnabled; }
    /** 缺省为 false；只有显式启用且 V014 readiness 通过才开放续期 REST。 */
    public boolean realtimeRenewEnabled() { return realtimeRenewEnabled; }
    /** 仅显式配置正值 TTL 时开放匿名 Manifest GET；genTime 不是防篡改凭证。 */
    public Optional<Duration> resultLinkTtl() { return Optional.ofNullable(resultLinkTtl); }

    @Override
    public String toString() {
        return "ReferenceMeetingDeploymentConfig[validated=true, createEnabled="
                + createEnabled + ", realtimeRenewEnabled=" + realtimeRenewEnabled
                + ", secrets=REDACTED, identity=REDACTED]";
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw invalid(key);
        }
        return value;
    }

    private static int positiveInt(Map<String, String> environment, String key) {
        long number = positiveLong(environment, key);
        if (number > Integer.MAX_VALUE) {
            throw invalid(key);
        }
        return (int) number;
    }

    private static long positiveLong(Map<String, String> environment, String key) {
        String value = required(environment, key);
        try {
            long number = Long.parseLong(value);
            if (number > 0) {
                return number;
            }
        } catch (NumberFormatException ignored) {
            // 只报告键名，不回显动态配置内容。
        }
        throw invalid(key);
    }

    private static Long optionalNonNegativeLong(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null) {
            return null;
        }
        try {
            long number = Long.parseLong(required(environment, key));
            if (number >= 0) {
                return number;
            }
        } catch (NumberFormatException ignored) {
            // 只报告键名，不回显动态配置内容。
        }
        throw invalid(key);
    }

    private static Duration positiveDuration(Map<String, String> environment, String key) {
        return Duration.ofMillis(positiveLong(environment, key));
    }

    private static Set<String> capabilities(Map<String, String> environment,
            String key, boolean required) {
        String value = environment.get(key);
        if (value == null && !required) {
            return Set.of();
        }
        if (value == null || value.isBlank()) {
            throw invalid(key);
        }
        TreeSet<String> capabilities = new TreeSet<>();
        for (String part : value.split(",", -1)) {
            String capability = part.trim();
            if (capability.isEmpty() || !capability.matches("[a-z][a-z0-9_.-]*")
                    || !capabilities.add(capability)) {
                throw invalid(key);
            }
        }
        return Collections.unmodifiableSet(capabilities);
    }

    private static URI wssOrigin(Map<String, String> environment) {
        try {
            URI origin = new URI(required(environment, WSS_ORIGIN));
            String path = origin.getRawPath();
            if (!"wss".equalsIgnoreCase(origin.getScheme())
                    || origin.getHost() == null || origin.getHost().isBlank()
                    || origin.getUserInfo() != null || origin.getFragment() != null
                    || origin.getQuery() != null
                    || (path != null && !path.isEmpty() && !"/".equals(path))) {
                throw invalid(WSS_ORIGIN);
            }
            return origin;
        } catch (URISyntaxException malformed) {
            throw invalid(WSS_ORIGIN);
        }
    }

    private static boolean strictBoolean(String value, String field) {
        if (value == null) {
            return false;
        }
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw invalid(field);
    }

    private static IllegalArgumentException invalid(String key) {
        return new IllegalArgumentException("参考会议部署配置 " + key + " 非法");
    }
}
