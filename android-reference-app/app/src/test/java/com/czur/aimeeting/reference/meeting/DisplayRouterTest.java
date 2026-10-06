package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.display.MeetingDisplayState;

import com.czur.aimeeting.client.display.DisplayRouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import org.junit.Test;

/** P5 仅用可控输入验多参数路由；实体方向角准确性由 P6 设备测试证明。 */
public final class DisplayRouterTest {
    @Test
    public void definiteDirectionWinsOverModelLanguageAndAmbiguousAngleFallsBack() {
        DisplayRouter router = new DisplayRouter("zh-CN", "en-US");
        router.updateStableAngle(220f);
        DisplayRouter.Route byDirection = router.route("one", "en-US", "Hello");
        assertEquals(1, byDirection.side());
        assertEquals(DisplayRouter.Basis.STABLE_DIRECTION, byDirection.basis());
        router.updateStableAngle(300f);
        assertEquals(2, router.route("two", "zh-CN", "你好").side());
        router.updateStableAngle(270f);
        DisplayRouter.Route center = router.route("three", "en-US", "Hello");
        assertEquals(2, center.side());
        assertTrue(center.languageFallback());
        router.updateStableAngle(90f);
        assertEquals(1, router.route("four", "zh-CN", "你好").side());
    }

    @Test
    public void segmentDirectionWinsAndRevisionKeepsFirstSide() {
        DisplayRouter router = new DisplayRouter("zh-CN", "en-US");
        router.updateStableAngle(220f);
        router.setSegmentDirection("segment", 2);
        assertEquals(DisplayRouter.Basis.SEGMENT_DIRECTION,
                router.route("segment", "zh-CN", "你好").basis());
        router.updateStableAngle(300f);
        router.setSegmentDirection("segment", 1);
        assertEquals(2, router.route("segment", "zh-CN", "你好，修订").side());
    }

    @Test
    public void missingAngleUsesMarkedLanguageFallbackAndUnknownUsesText() {
        DisplayRouter router = new DisplayRouter("zh-CN", "en-US");
        router.updateStableAngle(-1f);
        DisplayRouter.Route byText = router.route("english", "und", "Hello");
        assertEquals(2, byText.side());
        assertEquals(DisplayRouter.Basis.TEXT_LANGUAGE, byText.basis());
        router.updateStableAngle(361f);
        assertEquals(DisplayRouter.Basis.MODEL_LANGUAGE,
                router.route("chinese", "zh-CN", "你好").basis());
        assertEquals(DisplayRouter.Basis.LANGUAGE_UNRESOLVED,
                router.route("digits", "und", "123").basis());
        assertEquals(0, router.route("digits", "und", "123").side());
        assertNull(router.existingRoute("digits"));
    }

    @Test
    public void translationCanArriveFirstAndLaterRevisionsStayOnSameSide() {
        MeetingDisplayState state = new MeetingDisplayState();
        state.start(0L, true, "zh-CN", "en-US");
        state.apply(new TranslationEvent("tr", "source", 1, "en-US", "zh-CN", "你", false));
        assertEquals(2, state.route("source").side());
        state.apply(new TranscriptEvent("source", 1, 0, 100, "und", "Hello", false));
        state.apply(new TranscriptEvent("source", 2, 0, 200, "zh-CN", "你好", true));
        assertEquals(2, state.route("source").side());
        assertEquals("你好", state.latestBilingualRow().right());
        assertEquals("你", state.latestBilingualRow().left());
        assertTrue(state.latestBilingualRow().languageFallback());
    }

    @Test
    public void unknownNumericPartialWaitsForLaterLanguageBeforeLocking() {
        MeetingDisplayState state = new MeetingDisplayState();
        state.start(0L, true, "zh-CN", "en-US");
        state.updateStableDirection(-1f);
        state.apply(new TranscriptEvent("numbers", 1, 0, 100, "und", "123", false));
        assertNull(state.route("numbers"));
        assertEquals("语言识别中：123", state.latestBilingualRow().left());
        assertEquals("", state.latestBilingualRow().right());
        state.apply(new TranscriptEvent("numbers", 2, 0, 200, "en-US", "123 people", false));
        assertEquals(2, state.route("numbers").side());
        assertEquals("123 people", state.latestBilingualRow().right());
        state.updateStableDirection(220f);
        state.apply(new TranscriptEvent("numbers", 3, 0, 300, "en-US", "123 people.", true));
        assertEquals(2, state.route("numbers").side());
    }

    @Test
    public void unknownPartialCanUseLaterSegmentDirection() {
        DisplayRouter router = new DisplayRouter("zh-CN", "en-US");
        assertEquals(0, router.route("numbers", "und", "123").side());
        router.setSegmentDirection("numbers", 2);
        assertEquals(DisplayRouter.Basis.SEGMENT_DIRECTION,
                router.route("numbers", "und", "123").basis());
        assertEquals(2, router.existingRoute("numbers").side());
    }
}
