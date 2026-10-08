package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.*;
import com.czur.aimeeting.client.display.DisplayRouter;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import java.util.List;
import org.junit.Test;

/** 只验证SDK决定的事实如何呈现，不复制DOA／语言／修订路由算法。 */
public final class DisplaySnapshotPresentationTest {
    @Test public void domesticSpeakerColumnKeepsOriginalAndTranslationTogetherAcrossSwap() {
        var paired = new MeetingDisplaySnapshot.Row("one", source("one", "Hello", false),
                new TranslationEvent("tr", "one", 1, "en-US", "zh-CN", "你好", true),
                MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.STABLE_DIRECTION);
        var left = DisplaySnapshotPresentation.speakerColumn(paired, false);
        assertEquals(MeetingDisplaySnapshot.Side.A, left.side());
        assertEquals("Hello", left.source());
        assertEquals("你好", left.translated());
        assertEquals(0.8f, left.sourceAlpha(), 0f);
        assertEquals(1f, left.translatedAlpha(), 0f);
        var right = DisplaySnapshotPresentation.speakerColumn(paired, true);
        assertEquals(MeetingDisplaySnapshot.Side.B, right.side());
        assertEquals(left.source(), right.source());
        assertEquals(left.translated(), right.translated());
        assertEquals(MeetingDisplaySnapshot.Side.A, paired.sourceSide());
    }

    @Test public void unresolvedSpeakerColumnDoesNotGuessPhysicalSide() {
        var undecided = new MeetingDisplaySnapshot.Row("pending", null,
                new TranslationEvent("tr", "pending", 1, "en-US", "zh-CN", "你好", false),
                MeetingDisplaySnapshot.Side.UNRESOLVED, DisplayRouter.Basis.LANGUAGE_UNRESOLVED);
        var rendered = DisplaySnapshotPresentation.speakerColumn(undecided, true);
        assertEquals(MeetingDisplaySnapshot.Side.UNRESOLVED, rendered.side());
        assertEquals("", rendered.source());
        assertEquals("你好", rendered.translated());
        assertEquals(0.8f, rendered.translatedAlpha(), 0f);
    }

    @Test public void emptyAndPartialFinalKeepSubtitlePresentation() {
        assertEquals("等待字幕…", DisplaySnapshotPresentation.latestSubtitle(snapshot(List.of(), null, false)).text());
        var partial = row("one", "初稿", false, MeetingDisplaySnapshot.Side.A);
        assertEquals(0.8f, DisplaySnapshotPresentation.subtitle(partial).alpha(), 0f);
        var complete = row("one", "完整文字", true, MeetingDisplaySnapshot.Side.A);
        assertEquals("完整文字", DisplaySnapshotPresentation.subtitle(complete).text());
        assertEquals(1f, DisplaySnapshotPresentation.subtitle(complete).alpha(), 0f);
    }

    @Test public void semanticSideWinsWithoutInferringLanguageFromText() {
        var english = new MeetingDisplaySnapshot.Row("one", source("one", "Hello", false),
                new TranslationEvent("tr", "one", 1, "en-US", "zh-CN", "你好", true),
                MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.STABLE_DIRECTION);
        var rendered = DisplaySnapshotPresentation.speakerColumn(english, false);
        assertEquals(MeetingDisplaySnapshot.Side.A, rendered.side());
        assertFalse(rendered.languageFallback());
        assertEquals("en-US", english.transcript().language());
        assertEquals("English", DisplaySnapshotPresentation.leftLabel(snapshot(List.of(english), "one", true)));
        assertEquals("中文", DisplaySnapshotPresentation.rightLabel(snapshot(List.of(english), "one", true)));
    }

    @Test public void sourceBStaysRightAndSwapMovesWholePairLeft() {
        var paired = row("right", "右侧原文", true, MeetingDisplaySnapshot.Side.B);
        assertEquals(MeetingDisplaySnapshot.Side.B, DisplaySnapshotPresentation.speakerColumn(paired, false).side());
        assertEquals(MeetingDisplaySnapshot.Side.A, DisplaySnapshotPresentation.speakerColumn(paired, true).side());
        assertEquals("右侧原文", DisplaySnapshotPresentation.speakerColumn(paired, true).source());
        assertEquals(MeetingDisplaySnapshot.Side.B, paired.sourceSide());
    }

    @Test public void translationFirstKeepsOriginalEmptyOnItsSpeakerSide() {
        var paired = new MeetingDisplaySnapshot.Row("one", null,
                new TranslationEvent("tr", "one", 1, "en-US", "zh-CN", "你好", false),
                MeetingDisplaySnapshot.Side.B, DisplayRouter.Basis.MODEL_LANGUAGE);
        assertNull(DisplaySnapshotPresentation.subtitle(paired));
        var rendered = DisplaySnapshotPresentation.speakerColumn(paired, false);
        assertEquals(MeetingDisplaySnapshot.Side.B, rendered.side());
        assertEquals("", rendered.source());
        assertEquals("你好", rendered.translated());
        assertEquals(0.8f, rendered.translatedAlpha(), 0f);
    }

    @Test public void lateOldSegmentIsLatestSubtitleWhileHistoryOrderStaysStable() {
        var first = row("first", "旧段最新修订", true, MeetingDisplaySnapshot.Side.A);
        var second = row("second", "下一段", false, MeetingDisplaySnapshot.Side.B);
        var snapshot = snapshot(List.of(first, second), "first", false);
        assertEquals("旧段最新修订", DisplaySnapshotPresentation.latestSubtitle(snapshot).text());
        assertEquals("second", snapshot.rows().get(1).segmentId());
        assertEquals("下一段", DisplaySnapshotPresentation.speakerColumn(second, false).source());
    }

    private static MeetingDisplaySnapshot snapshot(List<MeetingDisplaySnapshot.Row> rows,
            String latest, boolean swapped) {
        return new MeetingDisplaySnapshot(true, "zh-CN", "en-US", swapped, rows, latest);
    }
    private static MeetingDisplaySnapshot.Row row(String id, String text, boolean isFinal,
            MeetingDisplaySnapshot.Side side) {
        return new MeetingDisplaySnapshot.Row(id, source(id, text, isFinal), null,
                side, DisplayRouter.Basis.MODEL_LANGUAGE);
    }
    private static TranscriptEvent source(String id, String text, boolean isFinal) {
        return new TranscriptEvent(id, 1, 0, 100, "en-US", text, isFinal);
    }
}
