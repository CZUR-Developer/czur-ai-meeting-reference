package com.czur.aimeeting.client

import com.czur.aimeeting.client.result.ResultOptions
import org.junit.Assert.*
import org.junit.Test

/** 正常会前快照及原公开构造器，不启动 Android、音源或会议。 */
class MeetingOptionsTest {
    @Test fun allLegacyConstructorsKeepReferenceCreateDefaults() {
        val seven = MeetingOptions(MeetingOptions.Mode.MEETING_MINUTES, "zh-CN", "en-US",
            false, MeetingOptions.ResultDelivery.WEB, "https://result.example.test", emptyList())
        val eightLegacy = MeetingOptions(MeetingOptions.Mode.LIVE_TRANSLATION, "zh-CN", "en-US",
            false, MeetingOptions.ResultDelivery.EMAIL, "https://result.example.test", emptyList(), true)
        val action = ResultOptions.action("partner.notice")
        val eightAction = MeetingOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT, "zh-CN", "en-US",
            false, action, "https://result.example.test", emptyList(), false)
        val nine = MeetingOptions(MeetingOptions.Mode.MEETING_MINUTES, "zh-CN", "en-US",
            false, MeetingOptions.ResultDelivery.LOCAL, "https://result.example.test", emptyList(), false, action)
        for (options in listOf(seven, eightLegacy, eightAction, nine)) {
            assertEquals("参考会议", options.title()); assertEquals("Asia/Shanghai", options.timezone())
            assertFalse(options.retainAudio())
        }
        assertTrue(seven.summaryEnabled()); assertTrue(eightLegacy.summaryEnabled())
        assertFalse(eightAction.summaryEnabled()); assertTrue(nine.summaryEnabled())
        assertSame(action, nine.resultOptions()); assertEquals(MeetingOptions.ResultDelivery.LOCAL, nine.resultDelivery())
    }
    @Test fun createParameterCopyKeepsTheFullImmutableBusinessSnapshot() {
        val emails = arrayListOf("fixture@example.test")
        val action = ResultOptions.action("partner.notice")
        val original = MeetingOptions(MeetingOptions.Mode.LIVE_TRANSLATION, "en-US", "zh-CN",
            true, MeetingOptions.ResultDelivery.EMAIL, "https://result.example.test", emails, true, action)
        emails.clear()
        val copied = original.withCreateParameters("客户会议", "Europe/Paris", true)
        assertNotSame(original, copied); assertEquals("参考会议", original.title())
        assertEquals("Asia/Shanghai", original.timezone()); assertFalse(original.retainAudio())
        assertEquals("客户会议", copied.title()); assertEquals("Europe/Paris", copied.timezone()); assertTrue(copied.retainAudio())
        assertEquals(original.mode(), copied.mode()); assertEquals(original.sourceLanguage(), copied.sourceLanguage())
        assertEquals(original.targetLanguage(), copied.targetLanguage()); assertEquals(original.presentationCapture(), copied.presentationCapture())
        assertEquals(original.resultDelivery(), copied.resultDelivery()); assertEquals(original.resultWebUrl(), copied.resultWebUrl())
        assertEquals(listOf("fixture@example.test"), copied.resultEmails())
        assertThrows(UnsupportedOperationException::class.java) { (copied.resultEmails() as MutableList<String>).clear() }
        assertEquals(original.summaryEnabled(), copied.summaryEnabled()); assertSame(action, copied.resultOptions())
        assertEquals(original.requestedCapabilities(), copied.requestedCapabilities()); assertEquals(original.targetLanguages(), copied.targetLanguages())
    }
}
