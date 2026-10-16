package com.czur.aimeeting.reference.meeting

import org.junit.Assert.*
import org.junit.Test
import com.czur.aimeeting.client.display.DisplayRouter
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.model.TranscriptEvent
import com.czur.aimeeting.model.TranslationEvent

/** 只验证 SDK 事实如何呈现，不复制路由算法。 */
class DisplaySnapshotPresentationTest {
    @Test fun domesticSpeakerColumnKeepsOriginalAndTranslationTogetherAcrossSwap() {
        val paired = MeetingDisplaySnapshot.Row("one", source("one", "Hello", false), TranslationEvent("tr", "one", 1, "en-US", "zh-CN", "你好", true),
            MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.STABLE_DIRECTION)
        val left = DisplaySnapshotPresentation.speakerColumn(paired, false)
        assertEquals(MeetingDisplaySnapshot.Side.A, left.side()); assertEquals("Hello", left.source()); assertEquals("你好", left.translated())
        assertEquals(.8f, left.sourceAlpha(), 0f); assertEquals(1f, left.translatedAlpha(), 0f)
        val right = DisplaySnapshotPresentation.speakerColumn(paired, true)
        assertEquals(MeetingDisplaySnapshot.Side.B, right.side()); assertEquals(left.source(), right.source()); assertEquals(left.translated(), right.translated())
        assertEquals(MeetingDisplaySnapshot.Side.A, paired.sourceSide())
    }
    @Test fun unresolvedSpeakerColumnDoesNotGuessPhysicalSide() {
        val undecided = MeetingDisplaySnapshot.Row("pending", null, TranslationEvent("tr", "pending", 1, "en-US", "zh-CN", "你好", false),
            MeetingDisplaySnapshot.Side.UNRESOLVED, DisplayRouter.Basis.LANGUAGE_UNRESOLVED)
        val rendered = DisplaySnapshotPresentation.speakerColumn(undecided, true)
        assertEquals(MeetingDisplaySnapshot.Side.UNRESOLVED, rendered.side()); assertEquals("", rendered.source()); assertEquals("你好", rendered.translated())
        assertEquals(.8f, rendered.translatedAlpha(), 0f)
    }
    @Test fun emptyAndPartialFinalKeepSubtitlePresentation() {
        assertEquals("等待字幕…", DisplaySnapshotPresentation.latestSubtitle(snapshot(emptyList(), null, false))!!.text())
        val partial = row("one", "初稿", false, MeetingDisplaySnapshot.Side.A)
        assertEquals(.8f, DisplaySnapshotPresentation.subtitle(partial)!!.alpha(), 0f)
        val complete = row("one", "完整文字", true, MeetingDisplaySnapshot.Side.A)
        assertEquals("完整文字", DisplaySnapshotPresentation.subtitle(complete)!!.text())
        assertEquals(1f, DisplaySnapshotPresentation.subtitle(complete)!!.alpha(), 0f)
    }
    @Test fun semanticSideWinsWithoutInferringLanguageFromText() {
        val english = MeetingDisplaySnapshot.Row("one", source("one", "Hello", false), TranslationEvent("tr", "one", 1, "en-US", "zh-CN", "你好", true),
            MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.STABLE_DIRECTION)
        val rendered = DisplaySnapshotPresentation.speakerColumn(english, false)
        assertEquals(MeetingDisplaySnapshot.Side.A, rendered.side()); assertFalse(rendered.languageFallback())
        assertEquals("en-US", english.transcript()!!.language())
        assertEquals("English", DisplaySnapshotPresentation.leftLabel(snapshot(listOf(english), "one", true)))
        assertEquals("中文", DisplaySnapshotPresentation.rightLabel(snapshot(listOf(english), "one", true)))
    }
    @Test fun sourceBStaysRightAndSwapMovesWholePairLeft() {
        val paired = row("right", "右侧原文", true, MeetingDisplaySnapshot.Side.B)
        assertEquals(MeetingDisplaySnapshot.Side.B, DisplaySnapshotPresentation.speakerColumn(paired, false).side())
        assertEquals(MeetingDisplaySnapshot.Side.A, DisplaySnapshotPresentation.speakerColumn(paired, true).side())
        assertEquals("右侧原文", DisplaySnapshotPresentation.speakerColumn(paired, true).source())
        assertEquals(MeetingDisplaySnapshot.Side.B, paired.sourceSide())
    }
    @Test fun translationFirstKeepsOriginalEmptyOnItsSpeakerSide() {
        val paired = MeetingDisplaySnapshot.Row("one", null, TranslationEvent("tr", "one", 1, "en-US", "zh-CN", "你好", false),
            MeetingDisplaySnapshot.Side.B, DisplayRouter.Basis.MODEL_LANGUAGE)
        assertNull(DisplaySnapshotPresentation.subtitle(paired))
        val rendered = DisplaySnapshotPresentation.speakerColumn(paired, false)
        assertEquals(MeetingDisplaySnapshot.Side.B, rendered.side()); assertEquals("", rendered.source()); assertEquals("你好", rendered.translated())
        assertEquals(.8f, rendered.translatedAlpha(), 0f)
    }
    @Test fun lateOldSegmentIsLatestSubtitleWhileHistoryOrderStaysStable() {
        val first = row("first", "旧段最新修订", true, MeetingDisplaySnapshot.Side.A)
        val second = row("second", "下一段", false, MeetingDisplaySnapshot.Side.B)
        val snapshot = snapshot(listOf(first, second), "first", false)
        assertEquals("旧段最新修订", DisplaySnapshotPresentation.latestSubtitle(snapshot)!!.text())
        assertEquals("second", snapshot.rows()[1].segmentId()); assertEquals("下一段", DisplaySnapshotPresentation.speakerColumn(second, false).source())
    }
    private fun snapshot(rows: List<MeetingDisplaySnapshot.Row>, latest: String?, swapped: Boolean) = MeetingDisplaySnapshot(true, "zh-CN", "en-US", swapped, rows, latest)
    private fun row(id: String, text: String, isFinal: Boolean, side: MeetingDisplaySnapshot.Side) = MeetingDisplaySnapshot.Row(id, source(id, text, isFinal), null, side, DisplayRouter.Basis.MODEL_LANGUAGE)
    private fun source(id: String, text: String, isFinal: Boolean) = TranscriptEvent(id, 1, 0, 100, "en-US", text, isFinal)
}
