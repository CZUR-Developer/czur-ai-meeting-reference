package com.czur.aimeeting.reference.ui;

import static org.junit.Assert.*;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Rect;
import com.czur.aimeeting.client.display.DisplayRouter;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import com.czur.aimeeting.reference.meeting.MeetingSetup;
import com.czur.aimeeting.ui.DefaultMeetingOverlay;
import com.czur.aimeeting.ui.OverlayController;
import com.czur.aimeeting.ui.SubtitleTextView;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowSettings;

/** App真实工厂：同一投影、四形态、正常回调和国内固定尺寸，不创建音源或会议。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class OverlayViewFactoryTest {
    @Before public void allowOverlay() { ShadowSettings.setCanDrawOverlays(true); }

    @Test public void lateOldRevisionSurvivesMiniAndHiddenThenUsesOriginalCallbacks() {
        AtomicInteger opens = new AtomicInteger(), stops = new AtomicInteger();
        var window = (OverlayViewFactory.DefaultWindow) new OverlayViewFactory().create(
                RuntimeEnvironment.getApplication(), opens::incrementAndGet, () -> fail("单语无交换"), stops::incrementAndGet);
        var initial = single("old revised", false);
        assertTrue(window.show(initial));
        window.render(initial, MeetingSetup.Mode.LIVE_TRANSCRIPT, false, "00:10", true, 42);
        var controller = window.overlay.controller();
        assertEquals(1800, controller.windowSpec().widthPx);
        assertEquals(222, controller.windowSpec().heightPx);
        measure(controller);
        assertEquals("old revised", cell(controller, "overlay.subtitle").getParts().get(0).text().toString());
        assertEquals("old", cell(controller, "overlay.subtitle").getParts().get(0).id());
        assertFalse(cell(controller, "overlay.subtitle").getParts().get(0).isFinal());
        assertEquals(2, cell(controller, "overlay.subtitle").getParts().size());
        assertTrue(cell(controller, "overlay.subtitle").getParts().get(1).isFinal());
        visible(controller, "overlay.stop");
        window.overlay.collapse();
        assertEquals(126, controller.windowSpec().widthPx);
        assertEquals(60, controller.windowSpec().heightPx);
        measure(controller);
        visible(controller, "overlay.restore");
        window.overlay.hide();
        window.render(single("old final while hidden", true), MeetingSetup.Mode.LIVE_TRANSCRIPT,
                false, "00:20", true, 73);
        assertFalse(window.isShowing());
        window.overlay.show(false);
        window.overlay.restore();
        measure(controller);
        assertEquals("old final while hidden", cell(controller, "overlay.subtitle").getParts().get(0).text().toString());
        assertTrue(cell(controller, "overlay.subtitle").getParts().get(0).isFinal());
        assertEquals(73, ((CzurVoiceLevelView) controller.contentView().findViewWithTag("overlay.level")).getLevel());
        visible(controller, "overlay.open").performClick();
        visible(controller, "overlay.stop").performClick();
        assertEquals(1, opens.get());
        assertEquals(1, stops.get());
        window.render(initial, MeetingSetup.Mode.LIVE_TRANSCRIPT, false, "00:20", false, 0);
        assertFalse(visible(controller, "overlay.stop").isEnabled());
        window.dismiss();
        assertEquals(OverlayController.State.RELEASED, controller.state());
    }

    @Test public void normalLargeMiniRestoreAndSwapKeepRoutedIndependentFinal() {
        AtomicInteger swaps = new AtomicInteger();
        var window = (OverlayViewFactory.DefaultWindow) new OverlayViewFactory().create(
                RuntimeEnvironment.getApplication(), () -> { }, swaps::incrementAndGet, () -> { });
        assertTrue(window.show(bilingual(false)));
        window.render(bilingual(false), MeetingSetup.Mode.LIVE_TRANSLATION, true, "00:30", true, 55);
        var controller = window.overlay.controller();
        assertEquals(293, controller.windowSpec().heightPx);
        measure(controller);
        assertTrue(cell(controller, "overlay.left").getParts().isEmpty());
        assertEquals("source", cell(controller, "overlay.right").getParts().get(0).text().toString());
        assertFalse(cell(controller, "overlay.right").getParts().get(0).isFinal());
        assertEquals("译文", cell(controller, "overlay.right-translation").getParts().get(0).text().toString());
        assertTrue(cell(controller, "overlay.right-translation").getParts().get(0).isFinal());
        visible(controller, "overlay.swap").performClick();
        assertEquals(1, swaps.get());
        visible(controller, "overlay.size").performClick();
        assertEquals(1008, controller.windowSpec().heightPx);
        measure(controller);
        visible(controller, "overlay.stop");
        window.overlay.collapse();
        window.render(bilingual(true), MeetingSetup.Mode.LIVE_TRANSLATION, true, "00:40", true, 64);
        window.overlay.restore();
        assertEquals(DefaultMeetingOverlay.TRANSLATION_LARGE, controller.currentFormId());
        measure(controller);
        assertEquals("source", cell(controller, "overlay.history-left").getParts().get(0).text().toString());
        assertFalse(cell(controller, "overlay.history-left").getParts().get(0).isFinal());
        assertEquals("译文", cell(controller, "overlay.history-left-translation").getParts().get(0).text().toString());
        assertTrue(cell(controller, "overlay.history-left-translation").getParts().get(0).isFinal());
        assertNull(controller.contentView().findViewWithTag("overlay.history-right"));
        window.overlay.showNormal();
        measure(controller);
        visible(controller, "overlay.stop");
        window.dismiss();
    }

    @Test public void domesticSimpleModeKeepsSamePairedSideAndOriginalCallbacks() {
        AtomicInteger swaps = new AtomicInteger(), stops = new AtomicInteger();
        var window = (OverlayViewFactory.DefaultWindow) new OverlayViewFactory().create(
                RuntimeEnvironment.getApplication(), () -> { }, swaps::incrementAndGet, stops::incrementAndGet);
        window.show(bilingual(false));
        window.render(bilingual(false), MeetingSetup.Mode.LIVE_TRANSLATION, false,
                "已进行 00:01:30 · 剩余 00:20:00", true, 55);
        var controller = window.overlay.controller();
        measure(controller);
        assertEquals(36, cell(controller, "overlay.right").getFixedLineHeightPx());
        assertEquals(0xFFFFDB10, cell(controller, "overlay.right-translation").getCurrentTextColor());
        var latest = bilingual(false).rows().get(0);
        // 实图暴露的是长文字的实际布局；不能只断言“已设置两行”而继续使用单行样本。
        // 历史与当前句仍为原来的两段输入，简洁裁切只能影响可见尾部，不删改业务内容。
        String olderSource = "earlier source with enough words to wrap across the ordinary window ".repeat(8);
        String olderTranslation = "之前的译文需要覆盖普通窗口的多行排版，保留完整输入并只显示最近内容。".repeat(8);
        var older = new MeetingDisplaySnapshot.Row("older", new TranscriptEvent("older", 1, 0, 50,
                "en-US", olderSource, true), new TranslationEvent("earlier-translation", "older", 1,
                "en-US", "zh-CN", olderTranslation, true), MeetingDisplaySnapshot.Side.B, DisplayRouter.Basis.MODEL_LANGUAGE);
        var history = new MeetingDisplaySnapshot(true, "zh-CN", "en-US", false, List.of(older, latest), "source");
        window.render(history, MeetingSetup.Mode.LIVE_TRANSLATION, false,
                "已进行 00:01:30 · 剩余 00:20:00", true, 55);
        assertEquals(2, cell(controller, "overlay.right").getParts().size());
        assertTrue(cell(controller, "overlay.right").getParts().get(0).isFinal());
        assertFalse(cell(controller, "overlay.right").getParts().get(1).isFinal());
        visible(controller, "overlay.simple").performClick();
        assertEquals(164, controller.windowSpec().heightPx);
        measure(controller);
        assertEquals(View.GONE, controller.contentView().findViewWithTag("overlay.level").getVisibility());
        assertEquals(2, cell(controller, "overlay.right").getMaxVisibleLines());
        assertEquals(2, cell(controller, "overlay.right-translation").getMaxVisibleLines());
        assertEquals(2, cell(controller, "overlay.right").getLineCount());
        assertEquals(2, cell(controller, "overlay.right-translation").getLineCount());
        visible(controller, "overlay.right");
        visible(controller, "overlay.right-translation");
        var sourceCell = cell(controller, "overlay.right");
        var translatedCell = cell(controller, "overlay.right-translation");
        assertTrue("原文不能越过简洁列底部", sourceCell.getBottom() <= ((View) sourceCell.getParent()).getHeight());
        assertTrue("译文不能越过简洁列底部", translatedCell.getBottom() <= ((View) translatedCell.getParent()).getHeight());
        assertEquals(olderSource + "source", sourceCell.getFullText().toString());
        assertEquals(olderTranslation + "译文", translatedCell.getFullText().toString());
        assertEquals("source", cell(controller, "overlay.right").getParts().get(1).text().toString());
        visible(controller, "overlay.collapse").performClick();
        assertEquals(1, swaps.get());
        assertEquals(DefaultMeetingOverlay.TRANSLATION_NORMAL, controller.currentFormId());
        visible(controller, "overlay.stop").performClick();
        assertEquals(1, stops.get());
        visible(controller, "overlay.size").performClick();
        assertEquals(DefaultMeetingOverlay.TRANSLATION_LARGE, controller.currentFormId());
        window.overlay.showNormal();
        assertEquals(293, controller.windowSpec().heightPx);
        measure(controller);
        assertEquals(3, cell(controller, "overlay.right").getMaxVisibleLines());
        assertEquals(3, cell(controller, "overlay.right-translation").getMaxVisibleLines());
        assertEquals(3, cell(controller, "overlay.right").getLineCount());
        assertEquals(3, cell(controller, "overlay.right-translation").getLineCount());
        assertEquals(olderSource + "source", cell(controller, "overlay.right").getFullText().toString());
        assertEquals(olderTranslation + "译文", cell(controller, "overlay.right-translation").getFullText().toString());
        window.dismiss();
    }

    static MeetingDisplaySnapshot single(String oldText, boolean complete) {
        return new MeetingDisplaySnapshot(false, "zh-CN", "en-US", false,
                List.of(new MeetingDisplaySnapshot.Row("old", new TranscriptEvent("old", 2, 0, 100,
                        "zh-CN", oldText, complete), null, MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.MODEL_LANGUAGE),
                        new MeetingDisplaySnapshot.Row("new", new TranscriptEvent("new", 1, 100, 200,
                                "zh-CN", "newer", true), null, MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.MODEL_LANGUAGE)), "old");
    }
    static MeetingDisplaySnapshot bilingual(boolean swapped) {
        return new MeetingDisplaySnapshot(true, "zh-CN", "en-US", swapped,
                List.of(new MeetingDisplaySnapshot.Row("source", new TranscriptEvent("source", 1, 0, 100,
                        "en-US", "source", false), new TranslationEvent("translated", "source", 1,
                        "en-US", "zh-CN", "译文", true), MeetingDisplaySnapshot.Side.B, DisplayRouter.Basis.MODEL_LANGUAGE)), "source");
    }
    static SubtitleTextView cell(OverlayController controller, String tag) { return controller.contentView().findViewWithTag(tag); }
    static void measure(OverlayController controller) {
        View root = (View) controller.contentView().getParent();
        var spec = controller.windowSpec();
        root.measure(View.MeasureSpec.makeMeasureSpec(spec.widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(spec.heightPx, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, spec.widthPx, spec.heightPx);
    }
    static View visible(OverlayController controller, String tag) {
        ViewGroup panel = (ViewGroup) controller.contentView();
        View target = panel.findViewWithTag(tag);
        assertNotNull(target);
        assertTrue(tag, target.getWidth() > 0 && target.getHeight() > 0);
        Rect rect = new Rect(0, 0, target.getWidth(), target.getHeight());
        panel.offsetDescendantRectToMyCoords(target, rect);
        assertTrue(tag + " outside fixed height: " + rect, rect.top >= 0 && rect.bottom <= panel.getHeight());
        assertTrue(tag + " outside fixed width: " + rect, rect.left >= 0 && rect.right <= panel.getWidth());
        return target;
    }
}
