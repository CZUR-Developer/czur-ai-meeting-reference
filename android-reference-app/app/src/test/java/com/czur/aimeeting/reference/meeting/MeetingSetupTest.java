package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.MeetingCapabilityState;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** 会前快照的能力、语言与结果方式保持正交。 */
public final class MeetingSetupTest {
    @Test
    public void transcriptKeepsChosenSourceAndWebResultWithoutTargetLanguage() {
        MeetingSetup setup = MeetingSetup.transcript("en-US",
                "https://result.example.test/index.html");
        assertEquals(List.of(MeetingCapabilityState.TRANSCRIPT,
                MeetingCapabilityState.SPEAKER), setup.requestedCapabilities());
        assertEquals(List.of(), setup.targetLanguages());
        assertEquals("en-US", setup.sourceLanguage());
        assertEquals(MeetingSetup.ResultDelivery.WEB, setup.resultDelivery());
    }

    @Test
    public void translationRequestsTranslationAndSelectedTarget() {
        MeetingSetup setup = new MeetingSetup(MeetingSetup.Mode.LIVE_TRANSLATION, "zh-CN",
                "en-US", false, MeetingSetup.ResultDelivery.TRACELESS, "");
        assertEquals(List.of(MeetingCapabilityState.TRANSCRIPT,
                MeetingCapabilityState.TRANSLATION, MeetingCapabilityState.SPEAKER),
                setup.requestedCapabilities());
        assertEquals(List.of("en-US"), setup.targetLanguages());
        assertEquals(MeetingSetup.ResultDelivery.TRACELESS, setup.resultDelivery());
    }

    @Test public void translationCanIndependentlyRequestSummary() {
        MeetingSetup setup = new MeetingSetup(MeetingSetup.Mode.LIVE_TRANSLATION, "ja-JP",
                "en-US", false, MeetingSetup.ResultDelivery.WEB, "https://result.example.test",
                List.of(), true);
        assertTrue(setup.summaryEnabled());
        assertTrue(setup.toOptions().summaryEnabled());
        assertEquals(List.of(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION,
                MeetingCapabilityState.SUMMARY, MeetingCapabilityState.SPEAKER), setup.requestedCapabilities());
        assertEquals(List.of(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION,
                MeetingCapabilityState.SUMMARY), setup.toOptions().requiredCapabilities());
        assertEquals(List.of("en-US"), setup.targetLanguages());
    }

    @Test public void subtitleSummaryIsIndependentOfResultDelivery() {
        MeetingSetup setup = new MeetingSetup(MeetingSetup.Mode.LIVE_TRANSCRIPT, "en-US",
                "zh-CN", true, MeetingSetup.ResultDelivery.WEB, "https://result.example.test",
                List.of(), true);
        assertTrue(setup.summaryEnabled());
        assertEquals(List.of(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SUMMARY,
                MeetingCapabilityState.SPEAKER), setup.requestedCapabilities());
        assertEquals(List.of(), setup.targetLanguages());
        assertTrue(setup.toOptions().presentationCapture());
    }

    @Test public void oldModeAndConstructorsKeepTheirSummaryMeaning() {
        MeetingSetup minutes = new MeetingSetup(MeetingSetup.Mode.MEETING_MINUTES, "zh-CN",
                "en-US", false, MeetingSetup.ResultDelivery.WEB, "https://result.example.test");
        assertTrue(minutes.summaryEnabled());
        assertTrue(minutes.toOptions().summaryEnabled());
        assertFalse(MeetingSetup.transcript("zh-CN", "https://result.example.test").summaryEnabled());
        assertEquals(List.of(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SUMMARY,
                MeetingCapabilityState.SPEAKER), minutes.requestedCapabilities());
    }
}
