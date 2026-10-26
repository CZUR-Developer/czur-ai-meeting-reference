package com.czur.reference.application.http;

import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 将 V013 的完整 Create 首次响应事实投影为公开 OpenAPI 响应 JSON。
 *
 * <p>内部事实 JSON 还含 schemaVersion、action、工作流和产物等持久执行字段，
 * 不能直接作为 HTTP body。本投影只输出公开字段；内部 warningCode 映射为
 * reasonCode，未声明的可选字段保持缺省，不输出 JSON null。键和能力顺序固定，
 * 因而相同的持久事实在本版投影的首次返回和跨进程重放时得到相同 UTF-8
 * 字节。未来公开字段若需变化，须保留 V013 v1 对应的本版投影和固定字节样例，
 * 或另行持久化公开响应正文；不能原地改变历史重放的字节格式。</p>
 *
 * <p>调用方只能传入已由 V013 提交或只读回查的响应，不能投影尚未提交的候选
 * Token。本类不查询数据库、不做认证、不决定 HTTP 状态码；异常和诊断文本不
 * 包含响应正文、URL、Token 或会议标识。</p>
 */
public final class CreateMeetingPublicResponseJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private CreateMeetingPublicResponseJson() {
    }

    /**
     * 生成与 {@code CreateMeetingResponse} schema 对齐的确定性 JSON 正文。
     *
     * @param fact V013 已持久化的首次成功响应事实
     * @return 可直接交给 {@link HttpResponse#json(int, String)} 的 JSON
     * @throws NullPointerException 事实为空
     * @throws IllegalStateException JSON 编码失败；不泄露敏感字段
     */
    public static String encode(CreateMeetingResponseFact fact) {
        Objects.requireNonNull(fact, "fact");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("meetingId", fact.meetingId());
        body.put("state", fact.state().name());
        body.put("acceptedProfile", profile(fact));
        body.put("grantedCapabilities", new ArrayList<>(fact.grantedCapabilities()));
        body.put("capabilityDecisions", fact.capabilityDecisions().stream()
                .map(CreateMeetingPublicResponseJson::decision).toList());
        body.put("acceptedAudioProfile", audio(fact.acceptedAudioProfile()));
        body.put("realtime", realtime(fact.realtime()));
        body.put("limits", limits(fact.limits()));
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Create public response encoding failed");
        }
    }

    private static Map<String, Object> profile(CreateMeetingResponseFact fact) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", fact.acceptedProfile().id());
        value.put("revision", fact.acceptedProfile().revision());
        return value;
    }

    private static Map<String, Object> decision(CapabilityDecision decision) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("capability", decision.capability());
        value.put("availability", decision.availability().name());
        if (decision.warningCode() != null) {
            value.put("reasonCode", decision.warningCode());
        }
        value.put("requiredByProfile", decision.requiredByProfile());
        if (decision.fallbackCapability() != null) {
            value.put("fallbackCapability", decision.fallbackCapability());
        }
        return value;
    }

    private static Map<String, Object> audio(AudioProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("encoding", profile.encoding().name());
        value.put("sampleRateHz", profile.sampleRateHz());
        value.put("channelCount", profile.channelCount());
        value.put("frameDurationMs", profile.frameDurationMs());
        return value;
    }

    private static Map<String, Object> realtime(
            CreateMeetingResponseFact.RealtimeSession session) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("url", session.url().toString());
        value.put("subprotocol", session.subprotocol());
        value.put("sessionToken", session.sessionToken());
        value.put("expiresAt", session.expiresAt().toString());
        return value;
    }

    private static Map<String, Object> limits(CreateMeetingResponseFact.Limits limits) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("maxMeetingDurationMs", limits.maxMeetingDurationMs());
        value.put("maxAudioFrameBytes", limits.maxAudioFrameBytes());
        value.put("eventReplayWindow", limits.eventReplayWindow());
        if (limits.maxReplayAudioMs() != null) {
            value.put("maxReplayAudioMs", limits.maxReplayAudioMs());
        }
        value.put("heartbeatIntervalMs", limits.heartbeatIntervalMs());
        return value;
    }
}
