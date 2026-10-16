package com.czur.aimeeting.reference.meeting
import com.czur.aimeeting.client.MeetingCapabilityState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

import java.util.Set
import org.json.JSONObject
import org.junit.Test
/** 核对后端决策与本地 Adapter 的交集，避免把已授权或未请求误当成本场可用。 */
class MeetingCapabilityStateTest {
    @Test fun hidesUnavailableAndNotRequestedFeatures() {
        val state = MeetingCapabilityState.fromCreateResponse(JSONObject("{"
                + "\"grantedCapabilities\":[\"transcript.realtime\",\"translation.realtime\"],"
                + "\"capabilityDecisions\":["
                + "{\"capability\":\"transcript.realtime\",\"availability\":\"AVAILABLE\"},"
                + "{\"capability\":\"translation.realtime\",\"availability\":\"AVAILABLE\","
                + "\"reasonCode\":\"NOT_REQUESTED\"},"
                + "{\"capability\":\"summary.structured\",\"availability\":\"UNAVAILABLE\","
                + "\"reasonCode\":\"PROVIDER_CAPABILITY_MISSING\"}]}"))
        assertTrue(state.canUse(MeetingCapabilityState.TRANSCRIPT,
                setOf(MeetingCapabilityState.TRANSCRIPT)))
        assertFalse(state.canUse(MeetingCapabilityState.TRANSLATION,
                setOf(MeetingCapabilityState.TRANSLATION)))
        assertFalse(state.canUse(MeetingCapabilityState.SUMMARY, setOf()))
    }

    @Test fun realtimeFeatureNeedsBothBackendAndLocalAdapter() {
        val state = MeetingCapabilityState.fromCreateResponse(JSONObject("{"
                + "\"capabilityDecisions\":["
                + "{\"capability\":\"translation.realtime\",\"availability\":\"AVAILABLE\"},"
                + "{\"capability\":\"summary.structured\",\"availability\":\"AVAILABLE\"}]}"))
        assertFalse(state.canUse(MeetingCapabilityState.TRANSLATION,
                setOf(MeetingCapabilityState.TRANSCRIPT)))
        assertTrue(state.canUse(MeetingCapabilityState.TRANSLATION,
                setOf(MeetingCapabilityState.TRANSLATION)))
        // 总结是后端会后产物，不要求 Android 实时模型 Adapter 解析。
        assertTrue(state.canUse(MeetingCapabilityState.SUMMARY, setOf()))
    }

    @Test fun degradedTranslationDoesNotPretendTranscriptFallbackIsTranslation() {
        val state = MeetingCapabilityState.fromCreateResponse(JSONObject("{"
                + "\"capabilityDecisions\":[{\"capability\":\"translation.realtime\","
                + "\"availability\":\"DEGRADED\","
                + "\"fallbackCapability\":\"transcript.realtime\"}]}"))
        assertFalse(state.canUse(MeetingCapabilityState.TRANSLATION,
                setOf(MeetingCapabilityState.TRANSCRIPT)))
        assertFalse(state.canUse(MeetingCapabilityState.TRANSLATION, setOf()))
    }

    @Test fun sessionSpeakerNeedsBothFrozenDecisionAndLocalAdapter() {
        val available = MeetingCapabilityState.fromCreateResponse(
                JSONObject("{\"capabilityDecisions\":[{\"capability\":\"speaker.diarization.session\","
                        + "\"availability\":\"AVAILABLE\"}]}"))
        assertTrue(available.canUse(MeetingCapabilityState.SPEAKER,
                setOf(MeetingCapabilityState.SPEAKER)))
        assertFalse(available.canUse(MeetingCapabilityState.SPEAKER,
                setOf(MeetingCapabilityState.TRANSCRIPT)))
        val missing = MeetingCapabilityState.fromCreateResponse(
                JSONObject("{\"capabilityDecisions\":[]}"))
        assertFalse(missing.canUse(MeetingCapabilityState.SPEAKER,
                setOf(MeetingCapabilityState.SPEAKER)))
    }
}
