package com.czur.aimeeting.client

import org.json.JSONException
import org.json.JSONObject
import java.util.Collections

/** 只按 Create capabilityDecisions 冻结本场可用能力，不从厂商或权益集合推断。 */
class MeetingCapabilityState private constructor(effectiveCapabilities: Map<String, String>) {
    private val effectiveCapabilities = Collections.unmodifiableMap(HashMap(effectiveCapabilities))
    /**
     * 降级能力不冒充原能力；实时能力仍须有本场 Adapter 实现。
     * 纪要（服务端生成的 summary.server 与设备直连兼容期的 summary.structured）和
     * 跨会议身份都不要求实时 Adapter 声明；调用方仍需核对部署、本场请求及各自执行前提，
     * 不能只凭本方法启用。纪要的两种能力各按自己的决策判定，不互相兜底。
     */
    fun canUse(capability: String, adapterRealtimeCapabilities: Set<String>): Boolean {
        val effective = effectiveCapabilities[capability] ?: return false
        if (capability != effective) return false
        return capability == SUMMARY || capability == SUMMARY_SERVER ||
            capability == SPEAKER_IDENTITY_CROSS_MEETING || adapterRealtimeCapabilities.contains(capability)
    }
    companion object {
        const val TRANSCRIPT = "transcript.realtime"
        const val TRANSLATION = "translation.realtime"
        const val SUMMARY = "summary.structured"
        /**
         * 服务端按客户端上报的语言列表生成纪要。该能力在部署里是可选（optional），
         * 只有本场 Create 显式请求并得到 ENABLE 才会受理 summary:request；
         * 它与设备直连模式兼容期的 [SUMMARY] 并存，客户端不自行生成正文。
         */
        const val SUMMARY_SERVER = "summary.server"
        const val SPEAKER = "speaker.diarization.session"
        /** 服务端用本场发言人与已发布录音生成持久身份，不属于模型实时解析能力。 */
        const val SPEAKER_IDENTITY_CROSS_MEETING = "speaker.identity.cross_meeting"

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
