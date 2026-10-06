package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.MeetingCapabilityState;

import static org.junit.Assert.assertEquals;

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
}
