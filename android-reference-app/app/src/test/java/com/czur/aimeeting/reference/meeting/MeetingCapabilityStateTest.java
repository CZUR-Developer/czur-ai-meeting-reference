package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.MeetingCapabilityState;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;

/** 核对后端决策与本地 Adapter 的交集，避免把已授权或未请求误当成本场可用。 */
public final class MeetingCapabilityStateTest {
    @Test
    public void hidesUnavailableAndNotRequestedFeatures() throws Exception {
        MeetingCapabilityState state = MeetingCapabilityState.fromCreateResponse(new JSONObject("{"
                + "\"grantedCapabilities\":[\"transcript.realtime\",\"translation.realtime\"],"
                + "\"capabilityDecisions\":["
                + "{\"capability\":\"transcript.realtime\",\"availability\":\"AVAILABLE\"},"
                + "{\"capability\":\"translation.realtime\",\"availability\":\"AVAILABLE\","
                + "\"reasonCode\":\"NOT_REQUESTED\"},"
                + "{\"capability\":\"summary.structured\",\"availability\":\"UNAVAILABLE\","
                + "\"reasonCode\":\"PROVIDER_CAPABILITY_MISSING\"}]}"));
        assertTrue(state.canUse(MeetingCapabilityState.TRANSCRIPT,
                Set.of(MeetingCapabilityState.TRANSCRIPT)));
        assertFalse(state.canUse(MeetingCapabilityState.TRANSLATION,
                Set.of(MeetingCapabilityState.TRANSLATION)));
        assertFalse(state.canUse(MeetingCapabilityState.SUMMARY, Set.of()));
    }

    @Test
    public void realtimeFeatureNeedsBothBackendAndLocalAdapter() throws Exception {
        MeetingCapabilityState state = MeetingCapabilityState.fromCreateResponse(new JSONObject("{"
                + "\"capabilityDecisions\":["
                + "{\"capability\":\"translation.realtime\",\"availability\":\"AVAILABLE\"},"
                + "{\"capability\":\"summary.structured\",\"availability\":\"AVAILABLE\"}]}"));
        assertFalse(state.canUse(MeetingCapabilityState.TRANSLATION,
                Set.of(MeetingCapabilityState.TRANSCRIPT)));
        assertTrue(state.canUse(MeetingCapabilityState.TRANSLATION,
                Set.of(MeetingCapabilityState.TRANSLATION)));
        // 总结是后端会后产物，不要求 Android 实时模型 Adapter 解析。
        assertTrue(state.canUse(MeetingCapabilityState.SUMMARY, Set.of()));
    }

    @Test
    public void degradedTranslationDoesNotPretendTranscriptFallbackIsTranslation() throws Exception {
        MeetingCapabilityState state = MeetingCapabilityState.fromCreateResponse(new JSONObject("{"
                + "\"capabilityDecisions\":[{\"capability\":\"translation.realtime\","
                + "\"availability\":\"DEGRADED\","
                + "\"fallbackCapability\":\"transcript.realtime\"}]}"));
        assertFalse(state.canUse(MeetingCapabilityState.TRANSLATION,
                Set.of(MeetingCapabilityState.TRANSCRIPT)));
        assertFalse(state.canUse(MeetingCapabilityState.TRANSLATION, Set.of()));
    }

    @Test
    public void sessionSpeakerNeedsBothFrozenDecisionAndLocalAdapter() throws Exception {
        MeetingCapabilityState available = MeetingCapabilityState.fromCreateResponse(
                new JSONObject("{\"capabilityDecisions\":[{\"capability\":\"speaker.diarization.session\","
                        + "\"availability\":\"AVAILABLE\"}]}"));
        assertTrue(available.canUse(MeetingCapabilityState.SPEAKER,
                Set.of(MeetingCapabilityState.SPEAKER)));
        assertFalse(available.canUse(MeetingCapabilityState.SPEAKER,
                Set.of(MeetingCapabilityState.TRANSCRIPT)));

        MeetingCapabilityState missing = MeetingCapabilityState.fromCreateResponse(
                new JSONObject("{\"capabilityDecisions\":[]}"));
        assertFalse(missing.canUse(MeetingCapabilityState.SPEAKER,
                Set.of(MeetingCapabilityState.SPEAKER)));
    }
}
