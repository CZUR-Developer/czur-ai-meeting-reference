package com.czur.aimeeting.reference.meeting

import com.czur.aimeeting.client.MeetingCapabilityState
import com.czur.aimeeting.client.result.ResultOptions
import org.junit.Assert.*
import org.junit.Test

/** 会前快照的能力、语言和结果方式保持正交。 */
class MeetingSetupTest {
    @Test fun transcriptKeepsChosenSourceAndWebResultWithoutTargetLanguage() {
        val setup = MeetingSetup.transcript("en-US", "https://result.example.test/index.html")
        assertEquals(listOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SPEAKER), setup.requestedCapabilities())
        assertEquals(emptyList<String>(), setup.targetLanguages())
        assertEquals("en-US", setup.sourceLanguage())
        assertEquals(MeetingSetup.ResultDelivery.WEB, setup.resultDelivery())
    }
    @Test fun translationRequestsTranslationAndSelectedTarget() {
        val setup = MeetingSetup(MeetingSetup.Mode.LIVE_TRANSLATION, "zh-CN", "en-US", false, MeetingSetup.ResultDelivery.TRACELESS, "")
        assertEquals(listOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION, MeetingCapabilityState.SPEAKER), setup.requestedCapabilities())
        assertEquals(listOf("en-US"), setup.targetLanguages())
        assertEquals(MeetingSetup.ResultDelivery.TRACELESS, setup.resultDelivery())
    }
    @Test fun translationCanIndependentlyRequestSummary() {
        val setup = MeetingSetup(MeetingSetup.Mode.LIVE_TRANSLATION, "ja-JP", "en-US", false, MeetingSetup.ResultDelivery.WEB,
            "https://result.example.test", emptyList(), true)
        assertTrue(setup.summaryEnabled()); assertTrue(setup.toOptions().summaryEnabled())
        assertEquals(listOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION, MeetingCapabilityState.SUMMARY,
            MeetingCapabilityState.SPEAKER), setup.requestedCapabilities())
        assertEquals(listOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION, MeetingCapabilityState.SUMMARY), setup.toOptions().requiredCapabilities())
        assertEquals(listOf("en-US"), setup.targetLanguages())
    }
    @Test fun subtitleSummaryIsIndependentOfResultDelivery() {
        val setup = MeetingSetup(MeetingSetup.Mode.LIVE_TRANSCRIPT, "en-US", "zh-CN", true, MeetingSetup.ResultDelivery.WEB,
            "https://result.example.test", emptyList(), true)
        assertTrue(setup.summaryEnabled())
        assertEquals(listOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SUMMARY, MeetingCapabilityState.SPEAKER), setup.requestedCapabilities())
        assertEquals(emptyList<String>(), setup.targetLanguages()); assertTrue(setup.toOptions().presentationCapture())
    }
    @Test fun oldModeAndConstructorsKeepTheirSummaryMeaning() {
        val minutes = MeetingSetup(MeetingSetup.Mode.MEETING_MINUTES, "zh-CN", "en-US", false, MeetingSetup.ResultDelivery.WEB, "https://result.example.test")
        assertTrue(minutes.summaryEnabled()); assertTrue(minutes.toOptions().summaryEnabled())
        assertFalse(MeetingSetup.transcript("zh-CN", "https://result.example.test").summaryEnabled())
        assertEquals(listOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SUMMARY, MeetingCapabilityState.SPEAKER), minutes.requestedCapabilities())
    }
    @Test fun newStringActionAndOldNoDeliveryKeepIndependentSummaryPolicy() {
        val setup = MeetingSetup(MeetingSetup.Mode.LIVE_TRANSLATION, "zh-CN", "en-US", false, ResultOptions.action("example.notice"), "", emptyList(), true)
        assertEquals("example.notice", setup.toOptions().resultOptions().actionId()); assertTrue(setup.toOptions().summaryEnabled())
        val old = MeetingSetup(MeetingSetup.Mode.LIVE_TRANSLATION, "zh-CN", "en-US", false, MeetingSetup.ResultDelivery.TRACELESS, "", emptyList(), true)
        assertEquals(ResultOptions.Policy.NO_ACTIVE_DELIVERY, old.toOptions().resultOptions().policy()); assertTrue(old.toOptions().summaryEnabled())
    }
}
