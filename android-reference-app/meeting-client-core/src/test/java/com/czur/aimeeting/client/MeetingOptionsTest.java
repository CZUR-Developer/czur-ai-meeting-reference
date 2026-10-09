package com.czur.aimeeting.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.czur.aimeeting.client.result.ResultOptions;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** 只核对正常会前快照及既有PUBLIC签名；不依赖Android、不启动录音或会议。 */
public final class MeetingOptionsTest {
    @Test public void allLegacyConstructorsKeepReferenceCreateDefaults() {
        var seven = new MeetingOptions(MeetingOptions.Mode.MEETING_MINUTES, "zh-CN", "en-US",
                false, MeetingOptions.ResultDelivery.WEB, "https://result.example.test", List.of());
        var eightLegacy = new MeetingOptions(MeetingOptions.Mode.LIVE_TRANSLATION, "zh-CN", "en-US",
                false, MeetingOptions.ResultDelivery.EMAIL, "https://result.example.test", List.of(), true);
        var action = ResultOptions.action("partner.notice");
        var eightAction = new MeetingOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT, "zh-CN", "en-US",
                false, action, "https://result.example.test", List.of(), false);
        var nine = new MeetingOptions(MeetingOptions.Mode.MEETING_MINUTES, "zh-CN", "en-US",
                false, MeetingOptions.ResultDelivery.LOCAL, "https://result.example.test", List.of(), false, action);

        for (MeetingOptions options : List.of(seven, eightLegacy, eightAction, nine)) {
            assertEquals("参考会议", options.title());
            assertEquals("Asia/Shanghai", options.timezone());
            assertFalse(options.retainAudio());
        }
        assertTrue(seven.summaryEnabled());
        assertTrue(eightLegacy.summaryEnabled());
        assertFalse(eightAction.summaryEnabled());
        assertTrue(nine.summaryEnabled());
        assertSame(action, nine.resultOptions());
        assertEquals(MeetingOptions.ResultDelivery.LOCAL, nine.resultDelivery());
    }

    @Test public void createParameterCopyKeepsTheFullImmutableBusinessSnapshot() {
        var emails = new ArrayList<>(List.of("fixture@example.test"));
        var action = ResultOptions.action("partner.notice");
        var original = new MeetingOptions(MeetingOptions.Mode.LIVE_TRANSLATION, "en-US", "zh-CN",
                true, MeetingOptions.ResultDelivery.EMAIL, "https://result.example.test", emails, true, action);
        emails.clear();

        MeetingOptions copied = original.withCreateParameters("客户会议", "Europe/Paris", true);

        assertNotSame(original, copied);
        assertEquals("参考会议", original.title());
        assertEquals("Asia/Shanghai", original.timezone());
        assertFalse(original.retainAudio());
        assertEquals("客户会议", copied.title());
        assertEquals("Europe/Paris", copied.timezone());
        assertTrue(copied.retainAudio());
        assertEquals(original.mode(), copied.mode());
        assertEquals(original.sourceLanguage(), copied.sourceLanguage());
        assertEquals(original.targetLanguage(), copied.targetLanguage());
        assertEquals(original.presentationCapture(), copied.presentationCapture());
        assertEquals(original.resultDelivery(), copied.resultDelivery());
        assertEquals(original.resultWebUrl(), copied.resultWebUrl());
        assertEquals(List.of("fixture@example.test"), copied.resultEmails());
        assertThrows(UnsupportedOperationException.class, () -> copied.resultEmails().clear());
        assertEquals(original.summaryEnabled(), copied.summaryEnabled());
        assertSame(action, copied.resultOptions());
        assertEquals(original.requestedCapabilities(), copied.requestedCapabilities());
        assertEquals(original.targetLanguages(), copied.targetLanguages());
    }
}
