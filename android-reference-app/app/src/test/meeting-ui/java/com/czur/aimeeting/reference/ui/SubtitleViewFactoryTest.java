package com.czur.aimeeting.reference.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.czur.aimeeting.client.display.DisplayRouter;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import com.czur.aimeeting.reference.meeting.DisplaySnapshotPresentation;
import com.czur.aimeeting.ui.SubtitleTextView;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** 验证App薄工厂将既有快照直接交给同一真实字幕控件，不重算选段和左右路由。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SubtitleViewFactoryTest {
    @Test public void pageAndOverlayUseSameLatestSegmentAndCompletionState() {
        var old = new MeetingDisplaySnapshot.Row("old",
                new TranscriptEvent("old", 2, 0, 100, "zh-CN", "旧段迟到完成", true), null,
                MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.STABLE_DIRECTION);
        var recent = new MeetingDisplaySnapshot.Row("recent",
                new TranscriptEvent("recent", 1, 100, 200, "zh-CN", "下一段初稿", false), null,
                MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.STABLE_DIRECTION);
        var snapshot = new MeetingDisplaySnapshot(false, "zh-CN", "en-US", false,
                List.of(old, recent), "old");
        var pageRow = DisplaySnapshotPresentation.subtitle(old);
        var overlayRow = DisplaySnapshotPresentation.latestSubtitle(snapshot);
        SubtitleTextView page = subtitle(Integer.MAX_VALUE, false);
        SubtitleTextView overlay = subtitle(3, true);
        SubtitleViewFactory.render(page, pageRow.segmentId(), pageRow.text(), pageRow.alpha());
        SubtitleViewFactory.render(overlay, overlayRow.segmentId(), overlayRow.text(), overlayRow.alpha());
        measure(page);
        measure(overlay);

        assertEquals("old", overlay.getParts().get(0).id());
        assertEquals(page.getFullText().toString(), overlay.getFullText().toString());
        assertTrue(page.getParts().get(0).isFinal());
        assertTrue(overlay.getParts().get(0).isFinal());
        assertEquals(1.0f, overlay.getAlpha(), 0.0f);
        assertEquals(3 * overlay.getFixedLineHeightPx(), overlay.getMeasuredHeight());
        SubtitleViewFactory.render(overlay, "", "等待字幕…", 0.8f);
        measure(overlay);
        assertEquals("等待字幕…", overlay.getFullText().toString());
        assertEquals(1, overlay.getParts().size());
        assertFalse(overlay.getParts().get(0).isFinal());
    }

    @Test public void bilingualColumnsKeepIndependentFinalAndCoreSwapDecision() {
        var row = new MeetingDisplaySnapshot.Row("pair",
                new TranscriptEvent("pair", 1, 0, 100, "zh-CN", "原文仍在修订", false),
                new TranslationEvent("translation", "pair", 1, "zh-CN", "en-US", "Translation done", true),
                MeetingDisplaySnapshot.Side.B, DisplayRouter.Basis.STABLE_DIRECTION);
        SubtitleTextView left = subtitle(3, true);
        SubtitleTextView right = subtitle(3, true);
        var normal = DisplaySnapshotPresentation.bilingual(row, false);
        SubtitleViewFactory.render(left, normal.segmentId(), normal.left(), normal.leftAlpha());
        SubtitleViewFactory.render(right, normal.segmentId(), normal.right(), normal.rightAlpha());
        measure(left);
        measure(right);
        assertEquals("Translation done", left.getFullText().toString());
        assertTrue(left.getParts().get(0).isFinal());
        assertFalse(right.getParts().get(0).isFinal());

        var swapped = DisplaySnapshotPresentation.bilingual(row, true);
        SubtitleViewFactory.render(left, swapped.segmentId(), swapped.left(), swapped.leftAlpha());
        SubtitleViewFactory.render(right, swapped.segmentId(), swapped.right(), swapped.rightAlpha());
        measure(left);
        measure(right);
        assertEquals("原文仍在修订", left.getFullText().toString());
        assertFalse(left.getParts().get(0).isFinal());
        assertTrue(right.getParts().get(0).isFinal());
        assertEquals(1, left.getParts().size());
        assertEquals(1.0f, left.getAlpha(), 0.0f);
        assertEquals(1.0f, right.getAlpha(), 0.0f);
    }

    private static SubtitleTextView subtitle(int maxLines, boolean reserve) {
        TextView view = SubtitleViewFactory.create(RuntimeEnvironment.getApplication(), 28, maxLines, reserve);
        view.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
        view.setPadding(0, 0, 0, 0);
        return (SubtitleTextView) view;
    }

    private static void measure(SubtitleTextView view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }
}
