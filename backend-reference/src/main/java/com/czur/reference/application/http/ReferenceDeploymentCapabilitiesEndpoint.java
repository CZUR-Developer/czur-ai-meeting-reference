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
        this.errors = errors;
        TreeSet<String> available = new TreeSet<>();
        if (config.createEnabled()) {
            available.addAll(config.profile().allCapabilities());
            available.retainAll(config.entitlement().supportedCapabilities());
            available.retainAll(config.mockCapabilities().capabilities());
            available.removeIf(capability -> !config.capabilityLimits().permits(capability));
            if (config.bailianSummarySession().isEmpty()) {
                available.remove(CapabilityIds.SUMMARY_STRUCTURED);
            }
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
        body.put("languages", List.of("zh-CN", "en-US"));
        body.put("audioProfiles", List.of("PCM_S16LE"));
        body.put("limits", publicLimits);
        config.resultLinkTtl().ifPresent(ttl ->
                body.put("resultLinkTtlMs", ttl.toMillis()));
        body.put("resultEmailEnabled", config.resultEmail().isPresent());
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
