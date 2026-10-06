package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.*;
import com.czur.aimeeting.client.display.DisplayRouter;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import java.util.List;
import org.junit.Test;

/** 只验证 SDK 已决定事实的 UI 投影，不复制方向、语种或修订路由算法。 */
public final class DisplaySnapshotPresentationTest {
    @Test public void emptyAndPartialFinalKeepExistingSubtitlePresentation() {
        assertEquals("等待字幕…", DisplaySnapshotPresentation.latestSubtitle(snapshot(List.of(), null, false)).text());
        var partial = row("one", "初稿", false, MeetingDisplaySnapshot.Side.A);
        assertEquals(0.8f, DisplaySnapshotPresentation.subtitle(partial).alpha(), 0.0f);
        var complete = row("one", "完整文字", true, MeetingDisplaySnapshot.Side.A);
        assertEquals("完整文字", DisplaySnapshotPresentation.subtitle(complete).text());
        assertEquals(1.0f, DisplaySnapshotPresentation.subtitle(complete).alpha(), 0.0f);
    }

    @Test public void semanticSideAndSwapWinWithoutInferringLanguageFromText() {
        var row = new MeetingDisplaySnapshot.Row("one", source("one", "Hello", false),
                new TranslationEvent("tr", "one", 1, "en-US", "zh-CN", "你好", true),
                MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.STABLE_DIRECTION);
        var normal = DisplaySnapshotPresentation.bilingual(row, false);
        assertEquals("Hello", normal.left());
        assertEquals("你好", normal.right());
        assertEquals(0.8f, normal.leftAlpha(), 0.0f);
        assertEquals(1.0f, normal.rightAlpha(), 0.0f);
        assertFalse(normal.languageFallback());
        var swapped = DisplaySnapshotPresentation.bilingual(row, true);
        assertEquals("你好", swapped.left());
        assertEquals("Hello", swapped.right());
        assertEquals("English", DisplaySnapshotPresentation.leftLabel(snapshot(List.of(row), "one", true)));
        assertEquals("中文", DisplaySnapshotPresentation.rightLabel(snapshot(List.of(row), "one", true)));
        assertEquals("en-US", row.transcript().language());
    }

    @Test public void unresolvedSideKeepsPlaceholderWithoutChoosingASide() {
        var row = new MeetingDisplaySnapshot.Row("one", source("one", "123", false), null,
                MeetingDisplaySnapshot.Side.UNRESOLVED, DisplayRouter.Basis.LANGUAGE_UNRESOLVED);
        var rendered = DisplaySnapshotPresentation.bilingual(row, true);
        assertEquals("语言识别中：123", rendered.left());
        assertEquals("", rendered.right());
        assertTrue(rendered.languageFallback());
        assertEquals(MeetingDisplaySnapshot.Side.UNRESOLVED, row.sourceSide());
    }

    @Test public void translationFirstCanBeShownWithoutInventingOriginalText() {
        var row = new MeetingDisplaySnapshot.Row("one", null,
                new TranslationEvent("tr", "one", 1, "en-US", "zh-CN", "你好", false),
                MeetingDisplaySnapshot.Side.B, DisplayRouter.Basis.MODEL_LANGUAGE);
        assertNull(DisplaySnapshotPresentation.subtitle(row));
        var rendered = DisplaySnapshotPresentation.bilingual(row, false);
        assertEquals("你好", rendered.left());
        assertEquals("", rendered.right());
        assertEquals(0.8f, rendered.leftAlpha(), 0.0f);
    }

    @Test public void lateOldSegmentIsLatestSubtitleWhileHistoryOrderStaysStable() {
        var first = row("first", "旧段最新修订", true, MeetingDisplaySnapshot.Side.A);
        var second = row("second", "下一段", false, MeetingDisplaySnapshot.Side.B);
        var snapshot = snapshot(List.of(first, second), "first", false);
        assertEquals("旧段最新修订", DisplaySnapshotPresentation.latestSubtitle(snapshot).text());
        assertEquals("second", snapshot.rows().get(1).segmentId());
        assertEquals("下一段", DisplaySnapshotPresentation.bilingual(second, false).right());
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
