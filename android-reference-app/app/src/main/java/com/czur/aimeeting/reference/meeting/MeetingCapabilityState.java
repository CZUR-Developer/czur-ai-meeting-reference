package com.czur.aimeeting.reference.meeting;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 创建会议时后端冻结的可用能力。只从公开 capabilityDecisions 读取，不从厂商名称
 * 或权益集合推断功能；后者可能包含已授权但本场模型无法执行的能力。
 */
public final class MeetingCapabilityState {
    public static final String TRANSCRIPT = "transcript.realtime";
    public static final String TRANSLATION = "translation.realtime";
    public static final String SUMMARY = "summary.structured";
    public static final String SPEAKER = "speaker.diarization.session";

    private final Map<String, String> effectiveCapabilities;

    private MeetingCapabilityState(Map<String, String> effectiveCapabilities) {
        this.effectiveCapabilities = Collections.unmodifiableMap(
                new HashMap<>(effectiveCapabilities));
    }

    public static MeetingCapabilityState fromCreateResponse(JSONObject response)
            throws JSONException {
        Map<String, String> effective = new HashMap<>();
        JSONArray decisions = response.optJSONArray("capabilityDecisions");
        if (decisions != null) {
            for (int index = 0; index < decisions.length(); index++) {
                JSONObject decision = decisions.getJSONObject(index);
                String availability = decision.getString("availability");
                // 公开响应没有 action。未请求的可选项即便供应商支持，也仍应关闭。
                if ("NOT_REQUESTED".equals(decision.optString("reasonCode"))) continue;
                String capability = decision.getString("capability");
                if ("AVAILABLE".equals(availability)) {
                    effective.put(capability, capability);
                } else if ("DEGRADED".equals(availability)) {
                    String fallback = decision.optString("fallbackCapability");
                    if (!fallback.isEmpty()) effective.put(capability, fallback);
                }
            }
        }
        return new MeetingCapabilityState(effective);
    }

    /**
     * 只显示该能力本身的入口。若后端降级到了另一能力，不能把字幕冒充实时翻译；
     * App 还必须有本地实时协议实现。会后纪要由后端生成，无须 Adapter 解析。
     */
    public boolean canUse(String capability, Set<String> adapterRealtimeCapabilities) {
        String effective = effectiveCapabilities.get(capability);
        if (effective == null) return false;
        if (!capability.equals(effective)) return false;
        return SUMMARY.equals(capability) || adapterRealtimeCapabilities.contains(capability);
    }
}
