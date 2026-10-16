package com.czur.aimeeting.reference.ui

import android.view.View
import android.view.ViewGroup
import com.czur.aimeeting.client.display.DisplayRouter
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.model.TranscriptEvent
import com.czur.aimeeting.model.TranslationEvent
import com.czur.aimeeting.reference.meeting.DisplaySnapshotPresentation
import com.czur.aimeeting.ui.SubtitleTextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 验证App薄工厂将既有快照直接交给同一真实字幕控件，不重算选段和左右路由。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubtitleViewFactoryTest {
    @Test fun domesticHistoryAndCurrentPartsKeepFixedLineHeightAndIndependentFinal() {
        val text = subtitle(3, true)
        SubtitleViewFactory.setLineHeightPx(text, 36)
        SubtitleViewFactory.renderParts(text, listOf(
            DisplaySnapshotPresentation.SubtitleRow("history", "已完成。", 1f),
            DisplaySnapshotPresentation.SubtitleRow("current", "当前还在修订", 0.8f)))
        measure(text)
        assertEquals(36, text.getFixedLineHeightPx())
        assertEquals("已完成。当前还在修订", text.getFullText().toString())
        assertEquals(2, text.getParts().size)
        assertTrue(text.getParts()[0].isFinal())
        assertFalse(text.getParts()[1].isFinal())
        assertEquals(108, text.measuredHeight)
        SubtitleViewFactory.renderParts(text, listOf(
            DisplaySnapshotPresentation.SubtitleRow("history", "已完成。", 1f),
            DisplaySnapshotPresentation.SubtitleRow("current", "修订完成", 1f)))
        measure(text)
        assertEquals("已完成。修订完成", text.getFullText().toString())
        assertEquals(2, text.getParts().size)
        assertTrue(text.getParts()[1].isFinal())
    }

    @Test fun pageAndOverlayUseSameLatestSegmentAndCompletionState() {
        val old = MeetingDisplaySnapshot.Row("old",
            TranscriptEvent("old", 2, 0, 100, "zh-CN", "旧段迟到完成", true), null,
            MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.STABLE_DIRECTION)
        val recent = MeetingDisplaySnapshot.Row("recent",
            TranscriptEvent("recent", 1, 100, 200, "zh-CN", "下一段初稿", false), null,
            MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.STABLE_DIRECTION)
        val snapshot = MeetingDisplaySnapshot(false, "zh-CN", "en-US", false, listOf(old, recent), "old")
        val pageRow = DisplaySnapshotPresentation.subtitle(old)!!
        val overlayRow = DisplaySnapshotPresentation.latestSubtitle(snapshot)!!
        val page = subtitle(Int.MAX_VALUE, false)
        val overlay = subtitle(3, true)
        SubtitleViewFactory.render(page, pageRow.segmentId(), pageRow.text(), pageRow.alpha())
        SubtitleViewFactory.render(overlay, overlayRow.segmentId(), overlayRow.text(), overlayRow.alpha())
        measure(page)
        measure(overlay)

        assertEquals("old", overlay.getParts()[0].id())
        assertEquals(page.getFullText().toString(), overlay.getFullText().toString())
        assertTrue(page.getParts()[0].isFinal())
        assertTrue(overlay.getParts()[0].isFinal())
        assertEquals(1f, overlay.alpha, 0f)
        assertEquals(3 * overlay.getFixedLineHeightPx(), overlay.measuredHeight)
        SubtitleViewFactory.render(overlay, "", "等待字幕…", 0.8f)
        measure(overlay)
        assertEquals("等待字幕…", overlay.getFullText().toString())
        assertEquals(1, overlay.getParts().size)
        assertFalse(overlay.getParts()[0].isFinal())
    }

    @Test fun bilingualColumnsKeepIndependentFinalAndCoreSwapDecision() {
        val row = MeetingDisplaySnapshot.Row("pair",
            TranscriptEvent("pair", 1, 0, 100, "zh-CN", "原文仍在修订", false),
            TranslationEvent("translation", "pair", 1, "zh-CN", "en-US", "Translation done", true),
            MeetingDisplaySnapshot.Side.B, DisplayRouter.Basis.STABLE_DIRECTION)
        val original = subtitle(3, true)
        val translated = subtitle(3, true)
        val normal = DisplaySnapshotPresentation.speakerColumn(row, false)
        assertEquals(MeetingDisplaySnapshot.Side.B, normal.side())
        SubtitleViewFactory.render(original, normal.segmentId(), normal.source(), normal.sourceAlpha())
        SubtitleViewFactory.render(translated, normal.segmentId(), normal.translated(), normal.translatedAlpha())
        measure(original)
        measure(translated)
        assertEquals("Translation done", translated.getFullText().toString())
        assertTrue(translated.getParts()[0].isFinal())
        assertFalse(original.getParts()[0].isFinal())

        val swapped = DisplaySnapshotPresentation.speakerColumn(row, true)
        assertEquals(MeetingDisplaySnapshot.Side.A, swapped.side())
        assertEquals(normal.source(), swapped.source())
        assertEquals(normal.translated(), swapped.translated())
        assertEquals(1, original.getParts().size)
        assertEquals(1f, original.alpha, 0f)
        assertEquals(1f, translated.alpha, 0f)
    }

    private fun subtitle(maxLines: Int, reserve: Boolean): SubtitleTextView {
        val view = SubtitleViewFactory.create(RuntimeEnvironment.getApplication(), 28f, maxLines, reserve)
        view.layoutParams = ViewGroup.LayoutParams(-1, -2)
        view.setPadding(0, 0, 0, 0)
        return view as SubtitleTextView
    }

    private fun measure(view: SubtitleTextView) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}
