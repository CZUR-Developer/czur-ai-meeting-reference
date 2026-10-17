package com.czur.aimeeting.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 服务端会后能力与实时 Adapter 能力分开，不把降级或未请求决策当作授权。 */
class MeetingCapabilityStateTest {
    @Test fun grantedServerIdentityDoesNotRequireARealtimeAdapterDeclaration() {
        val state = fromDecision(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING, "AVAILABLE")
        assertTrue(state.canUse(MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING, emptySet()))
        assertFalse(fromDecision(MeetingCapabilityState.SPEAKER, "AVAILABLE")
            .canUse(MeetingCapabilityState.SPEAKER, emptySet()))
        assertFalse("未知能力不自动归入服务端会后能力", fromDecision("partner.unknown", "AVAILABLE")
            .canUse("partner.unknown", emptySet()))
    }

    @Test fun unrequestedOrDegradedIdentityDoesNotCountAsCrossMeetingIdentity() {
        val capability = MeetingCapabilityState.SPEAKER_IDENTITY_CROSS_MEETING
        assertFalse(fromDecision(capability, "AVAILABLE", "NOT_REQUESTED").canUse(capability, emptySet()))
        assertFalse(fromDecision(capability, "DEGRADED").canUse(capability, emptySet()))
    }

    private fun fromDecision(capability: String, availability: String, reason: String = ""): MeetingCapabilityState =
        MeetingCapabilityState.fromCreateResponse(JSONObject().put("capabilityDecisions", JSONArray().put(
            JSONObject().put("capability", capability).put("availability", availability)
                .put("reasonCode", reason).put("fallbackCapability", MeetingCapabilityState.SPEAKER))))
}
