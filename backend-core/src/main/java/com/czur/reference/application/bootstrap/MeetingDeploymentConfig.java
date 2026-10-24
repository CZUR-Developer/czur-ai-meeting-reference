package com.czur.reference.application.bootstrap;

import com.czur.reference.application.command.TrustedCreateRuntimeLimits;
import com.czur.reference.application.entitlement.FixedEntitlementPolicyConfig;
import com.czur.reference.application.finalization.ProductProfileSnapshot;
import com.czur.reference.application.model.BailianSummarySessionConfig;
import com.czur.reference.application.model.TingwuModelSessionConfig;
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
 * 共享会议部署的不可变可信配置；不创建连接池、身份凭据、Token 或网络监听。
 *
 * <p>同一映射只解析一次。数据库沿用原有配置规则；部署范围、单版本
 * Profile、权益、Mock 描述、能力开关和 Create 限制在取得数据库连接前交叉校验。
 * 受信audience/clientId由部署方显式提供。后续Create/Stop解码器和GET/DELETE解析器使用本对象的访问器，不得各自
 * 再读环境变量或从客户端请求取身份参数。
 * 此类只保存分页密钥的私有字节副本，调用方只能取得副本并须负责清零临时数组。
 * 日志和异常不得拼接输入值；本对象的诊断文本也不显示连接串、身份或 Secret。</p>
 */
public class MeetingDeploymentConfig {
    // 首版公开音频仅支持 16 kHz、单声道、16-bit PCM，最短帧 20 ms。
    private static final int MINIMUM_PUBLIC_AUDIO_FRAME_BYTES = 16_000 * 2 * 20 / 1_000;
    private static final String MOCK_TRANSCRIPT_WORKFLOW = "mock.transcript";
    private static final String MOCK_TRANSCRIPT_ARTIFACT = "transcript.final";
    private static final String MODEL_TRANSLATION_WORKFLOW = "model.translation.realtime";
    private static final String MODEL_TRANSLATION_ARTIFACT = "translation.final";
    private static final String MODEL_SUMMARY_WORKFLOW = "model.summary.text";
    private static final String MODEL_SUMMARY_ARTIFACT = "summary.structured";
    private static final String MODEL_SPEAKER_WORKFLOW = "model.speaker.session";
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
    /** Android 直连模型的能力登记；未设置时沿用 P2 Mock 能力配置。 */
    public static final String MODEL_CAPABILITIES = "AI_MEETING_MODEL_CAPABILITIES";
    /** 本部署签发的 Android 模型 Adapter；未设置时沿用 P5-1 听悟开关。 */
    public static final String MODEL_ADAPTER = "AI_MEETING_MODEL_ADAPTER";
    /** 部署方声明的标准语言码，按逗号分隔；发现接口保持配置顺序。 */
    public static final String MODEL_LANGUAGES = "AI_MEETING_MODEL_LANGUAGES";
    public static final String SAMPLE_JSON_WS_ENDPOINT =
            "AI_MEETING_SAMPLE_JSON_WS_ENDPOINT";
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
    private final String deploymentScope;
    private final String audience;
    private final String clientId;
    private final ProductProfile profile;
    private final FixedEntitlementPolicyConfig entitlement;
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
    private final ResultEmailConfig resultEmail;
    private final String modelAdapterId;
    private final TingwuModelSessionConfig tingwuModelSession;
    private final URI sampleJsonWsEndpoint;
    private final BailianSummarySessionConfig bailianSummarySession;
    private final List<String> modelLanguages;

    private MeetingDeploymentConfig(
            DatabaseRuntimeConfig database,
            String deploymentScope, String audience, String clientId,
            ProductProfile profile, FixedEntitlementPolicyConfig entitlement,
            CapabilityDescriptor mockCapabilities, DeploymentLimits capabilityLimits,
            UsageEstimate trustedEstimate, TrustedCreateRuntimeLimits runtimeLimits,
            byte[] pageTokenSecret, URI wssOrigin, Duration wssTokenTtl,
            boolean createEnabled, boolean realtimeRenewEnabled, Duration resultLinkTtl,
            ResultEmailConfig resultEmail, String modelAdapterId,
            TingwuModelSessionConfig tingwuModelSession, URI sampleJsonWsEndpoint,
            BailianSummarySessionConfig bailianSummarySession, List<String> modelLanguages) {
        this.database = database;
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
        this.resultEmail = resultEmail;
        this.modelAdapterId = modelAdapterId;
        this.tingwuModelSession = tingwuModelSession;
        this.sampleJsonWsEndpoint = sampleJsonWsEndpoint;
        this.bailianSummarySession = bailianSummarySession;
        this.modelLanguages = List.copyOf(modelLanguages);
    }

    /** 为参考默认配置的薄包装复用已校验字段；不重复解析或持有原始环境。 */
    protected MeetingDeploymentConfig(MeetingDeploymentConfig config) {
        this.database = config.database;
        this.deploymentScope = config.deploymentScope;
        this.audience = config.audience;
        this.clientId = config.clientId;
        this.profile = config.profile;
        this.entitlement = config.entitlement;
        this.mockCapabilities = config.mockCapabilities;
        this.capabilityLimits = config.capabilityLimits;
        this.trustedEstimate = config.trustedEstimate;
        this.runtimeLimits = config.runtimeLimits;
        this.pageTokenSecret = config.pageTokenSecret.clone();
        this.wssOrigin = config.wssOrigin;
        this.wssTokenTtl = config.wssTokenTtl;
        this.createEnabled = config.createEnabled;
        this.realtimeRenewEnabled = config.realtimeRenewEnabled;
        this.resultLinkTtl = config.resultLinkTtl;
        this.resultEmail = config.resultEmail;
        this.modelAdapterId = config.modelAdapterId;
        this.tingwuModelSession = config.tingwuModelSession;
        this.sampleJsonWsEndpoint = config.sampleJsonWsEndpoint;
        this.bailianSummarySession = config.bailianSummarySession;
        this.modelLanguages = config.modelLanguages;
    }

    /**
     * 读取共享会议配置，不读取演示账号、登录Token或用户密码。
     * 身份实现由装配方通过IdentityProvider注入；本方法只冻结受众和客户端标识。
     */
    public static MeetingDeploymentConfig fromEnvironmentWithExternalIdentity(
            Map<String, String> source, String audience, String clientId) {
        return fromEnvironment(source, trustedIdentityText("audience", audience),
                trustedIdentityText("clientId", clientId));
    }

    private static MeetingDeploymentConfig fromEnvironment(
            Map<String, String> source, String externalAudience, String externalClientId) {
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
        String scope = required(environment, DEPLOYMENT_SCOPE);
        // 默认身份与会议使用同一环境快照；外部身份参数由部署装配提供。
        String audience = externalAudience;
        String clientId = externalClientId;
        String profileId = required(environment, PROFILE_ID);
        int revision = positiveInt(environment, PROFILE_REVISION);
        Set<String> required = capabilities(environment, PROFILE_REQUIRED_CAPABILITIES, true);
        TreeSet<String> optionalValues = new TreeSet<>(capabilities(environment,
                PROFILE_OPTIONAL_CAPABILITIES, false));
        // 本场发言人始终是可请求项；是否启用仍由权益、模型和部署限制共同决定。
        optionalValues.add(CapabilityIds.SPEAKER_DIARIZATION_SESSION);
        Set<String> optional = Collections.unmodifiableSet(optionalValues);
        ProductProfile profile;
        try {
            // P5-5 登记真实模型互译的本场执行能力；客户端标准译文事件和最终结果
            // 已按该冻结能力决定接入。旧 WSS 音频 Worker 不因此具备译文收口。
            Map<String, List<String>> workflows = new HashMap<>();
            workflows.put(CapabilityIds.TRANSCRIPT_REALTIME,
                    List.of(MOCK_TRANSCRIPT_WORKFLOW));
            Map<String, List<String>> artifacts = new HashMap<>();
            artifacts.put(CapabilityIds.TRANSCRIPT_REALTIME,
                    List.of(MOCK_TRANSCRIPT_ARTIFACT));
            if (optional.contains(CapabilityIds.TRANSLATION_REALTIME)) {
                workflows.put(CapabilityIds.TRANSLATION_REALTIME,
                        List.of(MODEL_TRANSLATION_WORKFLOW));
                artifacts.put(CapabilityIds.TRANSLATION_REALTIME,
                        List.of(MODEL_TRANSLATION_ARTIFACT));
            }
            if (optional.contains(CapabilityIds.SUMMARY_STRUCTURED)) {
                workflows.put(CapabilityIds.SUMMARY_STRUCTURED,
                        List.of(MODEL_SUMMARY_WORKFLOW));
                artifacts.put(CapabilityIds.SUMMARY_STRUCTURED,
                        List.of(MODEL_SUMMARY_ARTIFACT));
            }
            workflows.put(CapabilityIds.SPEAKER_DIARIZATION_SESSION,
                    List.of(MODEL_SPEAKER_WORKFLOW));
            artifacts.put(CapabilityIds.SPEAKER_DIARIZATION_SESSION,
                    List.of(MOCK_TRANSCRIPT_ARTIFACT));
            if (optional.contains(CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING)
                    || required.contains(CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING)) {
                workflows.put(CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING,
                        List.of("speaker.identity.postprocess"));
                artifacts.put(CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING,
                        List.of("speaker.identity"));
            }
            Map<String, Set<String>> dependencies = new HashMap<>();
            if (optional.contains(CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING)
                    || required.contains(CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING)) {
                dependencies.put(CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING,
                        Set.of(CapabilityIds.SPEAKER_DIARIZATION_SESSION));
            }
            profile = new ProductProfile(profileId, revision, required, optional,
                    dependencies, Map.of(), Map.of(), workflows, artifacts);
            // Start 与最终收口依赖持久快照中的完整执行映射，不能只校验 Profile ID。
            ProductProfileSnapshot.from(profile);
        } catch (RuntimeException malformed) {
            throw invalid(PROFILE_REQUIRED_CAPABILITIES);
        }
        if (!profile.requiredCapabilities().contains(CapabilityIds.TRANSCRIPT_REALTIME)) {
            throw invalid(PROFILE_REQUIRED_CAPABILITIES);
        }
        if (profile.affectedWorkflowsOf(CapabilityIds.TRANSCRIPT_REALTIME).isEmpty()
                || profile.producedArtifactsOf(CapabilityIds.TRANSCRIPT_REALTIME).isEmpty()) {
            throw invalid(PROFILE_REQUIRED_CAPABILITIES);
        }

        Set<String> supported = capabilities(environment,
                ENTITLEMENT_SUPPORTED_CAPABILITIES, true);
        long maxEntitled = positiveLong(environment, ENTITLEMENT_MAX_DURATION_MS);
        Duration grantTtl = positiveDuration(environment, ENTITLEMENT_GRANT_TTL_MS);
        Duration reservationTtl = positiveDuration(environment, ENTITLEMENT_RESERVATION_TTL_MS);
        FixedEntitlementPolicyConfig entitlement;
        try {
            entitlement = new FixedEntitlementPolicyConfig(scope, profile.id(),
                    profile.revision(), supported, maxEntitled, grantTtl, reservationTtl);
        } catch (RuntimeException malformed) {
            throw invalid(ENTITLEMENT_RESERVATION_TTL_MS);
        }
        if (!profile.allCapabilities().containsAll(supported)
                || !supported.containsAll(profile.requiredCapabilities())) {
            throw invalid(ENTITLEMENT_SUPPORTED_CAPABILITIES);
        }

        String capabilityKey = environment.containsKey(MODEL_CAPABILITIES)
                ? MODEL_CAPABILITIES : MOCK_CAPABILITIES;
        Set<String> mockDeclared = capabilities(environment, capabilityKey, true);
        Set<String> disabled = capabilities(environment, DISABLED_CAPABILITIES, false);
        CapabilityDescriptor mock;
        DeploymentLimits limits;
        try {
            mock = new CapabilityDescriptor(
                    environment.containsKey(MODEL_CAPABILITIES)
                            ? "reference-model" : "reference-mock",
                    "1", 1, mockDeclared);
            limits = new DeploymentLimits(disabled);
        } catch (RuntimeException malformed) {
            throw invalid(capabilityKey);
        }
        if (!mock.capabilities().containsAll(profile.requiredCapabilities())) {
            throw invalid(capabilityKey);
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
                // 可选能力允许因某方缺失而 DISABLE；三方均允许时必须有执行映射。
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
            ResultEmailConfig resultEmail = ResultEmailConfig.fromEnvironment(environment);
            if (resultEmail != null && resultLinkTtl == null) {
                throw invalid(RESULT_LINK_TTL_MS);
            }
            String adapter = environment.get(MODEL_ADAPTER);
            // 未配置时沿用听悟默认；其它 ID 一律视为自定义 Adapter，由启动时的注册表解析。
            // 这里只保留 ID，不再把未知名字当成配置错误——是否可用由装配阶段判定。
            String adapterId = (adapter == null || adapter.isBlank()) ? "tingwu-realtime" : adapter;
            TingwuModelSessionConfig tingwu = null;
            URI sampleEndpoint = null;
            if ("sample-json-ws".equals(adapterId)) {
                sampleEndpoint = sampleJsonWsEndpoint(environment);
            } else if ("tingwu-realtime".equals(adapterId)) {
                tingwu = TingwuModelSessionConfig.fromEnvironment(environment);
                if (adapter != null && tingwu == null) {
                    throw invalid(MODEL_ADAPTER);
                }
            }
            BailianSummarySessionConfig bailian =
                    BailianSummarySessionConfig.fromEnvironment(environment);
            return new MeetingDeploymentConfig(database, scope,
                    audience, clientId, profile, entitlement, mock, limits, estimate,
                    runtimeLimits, secret, wssOrigin, tokenTtl,
                    enabled, renewEnabled, resultLinkTtl, resultEmail, adapterId,
                    tingwu, sampleEndpoint, bailian, modelLanguages(environment));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    public DatabaseRuntimeConfig database() { return database; }
    public String deploymentScope() { return deploymentScope; }
    /** Create/Stop/GET/DELETE 身份验证共用的服务端受众。 */
    public String audience() { return audience; }
    /** Create/Stop/GET/DELETE 身份验证共用的服务端客户端标识。 */
    public String clientId() { return clientId; }
    public ProductProfile profile() { return profile; }
    public FixedEntitlementPolicyConfig entitlement() { return entitlement; }
    public CapabilityDescriptor mockCapabilities() { return mockCapabilities; }
    public DeploymentLimits capabilityLimits() { return capabilityLimits; }
    public UsageEstimate trustedEstimate() { return trustedEstimate; }
    public TrustedCreateRuntimeLimits runtimeLimits() { return runtimeLimits; }
    /** 只供分页 HMAC 编解码器构造使用；返回私有密钥的副本。 */
    public byte[] pageTokenSecretCopy() { return pageTokenSecret.clone(); }
    /** 首次 Create 会话的公开 WSS origin；监听器由装配根的显式工厂另行创建。 */
    public URI wssOrigin() { return wssOrigin; }
    public Duration wssTokenTtl() { return wssTokenTtl; }
    /** 缺省严格为 false，只有显式字符串 true 才允许开放 Create。 */
    public boolean createEnabled() { return createEnabled; }
    /** 缺省为 false；只有显式启用且 V014 readiness 通过才开放续期 REST。 */
    public boolean realtimeRenewEnabled() { return realtimeRenewEnabled; }
    /** 仅显式配置正值 TTL 时开放匿名 Manifest GET；genTime 不是防篡改凭证。 */
    public Optional<Duration> resultLinkTtl() { return Optional.ofNullable(resultLinkTtl); }
    /** 只有部署方显式配置邮件 SMTP 时开放邮箱结果方式。 */
    public Optional<ResultEmailConfig> resultEmail() { return Optional.ofNullable(resultEmail); }
    /** 只有显式配置听悟凭据才开放独立模型会话路由。 */
    /** 本部署使用的模型 Adapter ID；未配置时为听悟默认值。 */
    public String modelAdapterId() { return modelAdapterId; }

    public Optional<TingwuModelSessionConfig> tingwuModelSession() {
        return Optional.ofNullable(tingwuModelSession);
    }
    /** 配置为第二格式样例时返回其 WebSocket 模型端点。 */
    public Optional<URI> sampleJsonWsEndpoint() {
        return Optional.ofNullable(sampleJsonWsEndpoint);
    }
    /** 仅显式启用且注入永久 Key 时装配百炼临时 Key 签发。 */
    public Optional<BailianSummarySessionConfig> bailianSummarySession() {
        return Optional.ofNullable(bailianSummarySession);
    }
    /**
     * 本部署实际开放的标准语言码；App 直接据此生成语言入口，不内置产品语种列表。
     * 更换模型时由部署装配同步声明其语言范围，供应商语言码转换仍归各自 Adapter。
     */
    public List<String> modelLanguages() { return modelLanguages; }

    private static List<String> modelLanguages(Map<String, String> environment) {
        // 保持既有参考部署的中英默认值；产品可通过同一个公开配置项恢复或收窄语种。
        String value = environment.getOrDefault(MODEL_LANGUAGES, "zh-CN,en-US");
        List<String> languages = Arrays.stream(value.split(",", -1)).map(String::trim).toList();
        if (languages.stream().anyMatch(String::isEmpty)
                || languages.stream().distinct().count() != languages.size()) {
            throw invalid(MODEL_LANGUAGES);
        }
        return languages;
    }

    @Override
    public String toString() {
        return "MeetingDeploymentConfig[validated=true, createEnabled="
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

    private static String trustedIdentityText(String name, String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw invalid(name);
        }
        return value;
    }

    private static URI sampleJsonWsEndpoint(Map<String, String> environment) {
        try {
            URI endpoint = URI.create(required(environment, SAMPLE_JSON_WS_ENDPOINT));
            if (!("ws".equals(endpoint.getScheme())
                    || "wss".equals(endpoint.getScheme())) || endpoint.getHost() == null) {
                throw invalid(SAMPLE_JSON_WS_ENDPOINT);
            }
            return endpoint;
        } catch (RuntimeException malformed) {
            throw invalid(SAMPLE_JSON_WS_ENDPOINT);
        }
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
