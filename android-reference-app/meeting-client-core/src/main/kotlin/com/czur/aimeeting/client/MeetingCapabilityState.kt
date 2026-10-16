package com.czur.aimeeting.client

import org.json.JSONException
import org.json.JSONObject
import java.util.Collections

/** 只按 Create capabilityDecisions 冻结本场可用能力，不从厂商或权益集合推断。 */
class MeetingCapabilityState private constructor(effectiveCapabilities: Map<String, String>) {
    private val effectiveCapabilities = Collections.unmodifiableMap(HashMap(effectiveCapabilities))
    /** 降级能力不冒充原能力；实时能力须有 Adapter 实现，纪要另用总结注册表。 */
    fun canUse(capability: String, adapterRealtimeCapabilities: Set<String>): Boolean {
        val effective = effectiveCapabilities[capability] ?: return false
        if (capability != effective) return false
        return capability == SUMMARY || adapterRealtimeCapabilities.contains(capability)
    }
    companion object {
        const val TRANSCRIPT = "transcript.realtime"
        const val TRANSLATION = "translation.realtime"
        const val SUMMARY = "summary.structured"
        const val SPEAKER = "speaker.diarization.session"

        @JvmStatic @Throws(JSONException::class)
        fun fromCreateResponse(response: JSONObject): MeetingCapabilityState {
            val effective = HashMap<String, String>()
            val decisions = response.optJSONArray("capabilityDecisions")
            if (decisions != null) for (index in 0 until decisions.length()) {
                val decision = decisions.getJSONObject(index)
                val availability = decision.getString("availability")
                // 公开响应没有 action，未请求可选项即使供应商支持也不启用。
                if (decision.optString("reasonCode") == "NOT_REQUESTED") continue
                val capability = decision.getString("capability")
                if (availability == "AVAILABLE") effective[capability] = capability
                else if (availability == "DEGRADED") {
                    val fallback = decision.optString("fallbackCapability")
                    if (fallback.isNotEmpty()) effective[capability] = fallback
                }
            }
            return MeetingCapabilityState(effective)
        }
    }
}
