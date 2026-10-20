package com.czur.reference.application.http;

import com.czur.reference.application.bootstrap.MeetingDeploymentConfig;
import com.czur.reference.domain.capability.CapabilityIds;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 现有公开发现协议的单部署静态能力；本场权益仍以 Create 决策为准。 */
public final class ReferenceDeploymentCapabilitiesEndpoint {
    public static final String PATH = "/v1/capabilities";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpResponse response;
    private final HttpErrorMapper errors;

    public ReferenceDeploymentCapabilitiesEndpoint(MeetingDeploymentConfig config,
            HttpErrorMapper errors) {
        this(config, errors, config.bailianSummarySession().isPresent());
    }

    /**
     * 由宿主装配后传入总结发行器是否已注册。
     *
     * <p>默认构造保留参考配置的百炼判断；自定义宿主可以注入其他
     * {@code SummarySessionIssuer}，此时不能再用百炼配置反推总结能力。</p>
     */
    public ReferenceDeploymentCapabilitiesEndpoint(MeetingDeploymentConfig config,
            HttpErrorMapper errors, boolean summarySessionAvailable) {
        this(config, errors, summarySessionAvailable, false);
    }

    /** 后处理人物能力以实际注册的 Provider 为准；不能由实时模型配置冒充。 */
    public ReferenceDeploymentCapabilitiesEndpoint(MeetingDeploymentConfig config,
            HttpErrorMapper errors, boolean summarySessionAvailable, boolean speakerIdentityAvailable) {
        this(config, errors, summarySessionAvailable, speakerIdentityAvailable, false);
    }

    /** 人工结果编辑是部署提供的工具，独立于实时模型和声纹能力。 */
    public ReferenceDeploymentCapabilitiesEndpoint(MeetingDeploymentConfig config,
            HttpErrorMapper errors, boolean summarySessionAvailable, boolean speakerIdentityAvailable,
            boolean speakerEditingEnabled) {
        this.errors = errors;
        TreeSet<String> available = new TreeSet<>();
        if (config.createEnabled()) {
            available.addAll(config.profile().allCapabilities());
            available.retainAll(config.entitlement().supportedCapabilities());
            var registered = new TreeSet<>(config.mockCapabilities().capabilities());
            registered.remove(CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING);
            if (speakerIdentityAvailable) registered.add(CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING);
            available.retainAll(registered);
            available.removeIf(capability -> !config.capabilityLimits().permits(capability));
            if (!summarySessionAvailable) {
                available.remove(CapabilityIds.SUMMARY_STRUCTURED);
            }
            // 与 Create 使用同一 Profile 依赖；移除缺失依赖的能力，直到集合稳定。
            // 例如人物 Provider 已注册，但部署未提供本场发言人时，不能宣传跨场识别。
            boolean removed;
            do {
                removed = available.removeIf(capability -> !available.containsAll(
                        config.profile().dependenciesOf(capability)));
            } while (removed);
        }
        var limits = config.runtimeLimits();
        Map<String, Object> publicLimits = new LinkedHashMap<>();
        publicLimits.put("maxMeetingDurationMs", Math.min(limits.maxMeetingDurationMs(),
                config.entitlement().maxDurationMs()));
        publicLimits.put("maxAudioFrameBytes", limits.maxAudioFrameBytes());
        publicLimits.put("eventReplayWindow", limits.eventReplayWindow());
        if (limits.maxReplayAudioMs() != null) {
            publicLimits.put("maxReplayAudioMs", limits.maxReplayAudioMs());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("specVersion", "v1");
        body.put("capabilities", available);
        body.put("languages", config.modelLanguages());
        body.put("audioProfiles", List.of("PCM_S16LE"));
        body.put("limits", publicLimits);
        config.resultLinkTtl().ifPresent(ttl ->
                body.put("resultLinkTtlMs", ttl.toMillis()));
        body.put("resultEmailEnabled", config.resultEmail().isPresent());
        body.put("resultSpeakerEditingEnabled", speakerEditingEnabled);
        try {
            response = HttpResponse.json(200, JSON.writeValueAsString(body));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("capabilities encoding failed", impossible);
        }
    }

    public HttpResponse handle(HttpRequest request) {
        if (!"GET".equals(request.method()) || !PATH.equals(request.path())
                || !request.query().isEmpty() || !request.body().isEmpty()) {
            return errors.invalidRoute();
        }
        return response;
    }
}
