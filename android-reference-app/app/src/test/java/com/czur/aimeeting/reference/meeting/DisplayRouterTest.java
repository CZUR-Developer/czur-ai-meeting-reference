package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.assertEquals;
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
}
