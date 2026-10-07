package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.client.display.DisplayRouter;
import java.util.List;

import static org.junit.Assert.assertEquals;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import org.junit.Test;

/** 布局切换只改变呈现；会议显示快照仍是同一份。 */
public final class OverlayLayoutStateTest {
    @Test
    public void subtitleMiniRestoresWithoutDiscardingNewTranscript() {
        OverlayLayoutState layout = new OverlayLayoutState(false);
        layout.collapse();
        assertEquals(OverlayLayoutState.Type.MINI, layout.current());
        MeetingDisplaySnapshot display = new MeetingDisplaySnapshot(false, "zh-CN", "en-US",
                false, List.of(new MeetingDisplaySnapshot.Row("part",
                        new TranscriptEvent("part", 1, 0, 100, "zh-CN", "收起期间", true),
                        null, MeetingDisplaySnapshot.Side.A, DisplayRouter.Basis.MODEL_LANGUAGE)), "part");
        layout.restore();
        assertEquals(OverlayLayoutState.Type.SUBTITLE, layout.current());
        assertEquals("收起期间", DisplaySnapshotPresentation.latestSubtitle(display).text());
        layout.showLarge();
        assertEquals(OverlayLayoutState.Type.SUBTITLE, layout.current());
    }

    @Test
    public void translationNormalLargeAndMiniRestoreKeepRoutedRows() {
        OverlayLayoutState layout = new OverlayLayoutState(true);
        layout.showLarge();
        assertEquals(OverlayLayoutState.Type.TRANSLATION_LARGE, layout.current());
        layout.collapse();
        MeetingDisplaySnapshot display = new MeetingDisplaySnapshot(true, "zh-CN", "en-US",
                false, List.of(new MeetingDisplaySnapshot.Row("source",
                        new TranscriptEvent("source", 1, 0, 100, "en-US", "Hello", true),
                        new TranslationEvent("translated", "source", 1, "en-US", "zh-CN", "你好", true),
                        MeetingDisplaySnapshot.Side.B, DisplayRouter.Basis.MODEL_LANGUAGE)), "source");
        layout.restore();
        assertEquals(OverlayLayoutState.Type.TRANSLATION_LARGE, layout.current());
        assertEquals("你好", DisplaySnapshotPresentation.bilingual(display.rows().get(0), display.swapped()).left());
        assertEquals("Hello", DisplaySnapshotPresentation.bilingual(display.rows().get(0), display.swapped()).right());
        layout.showNormal();
        assertEquals(OverlayLayoutState.Type.TRANSLATION_NORMAL, layout.current());
        assertEquals("Hello", DisplaySnapshotPresentation.bilingual(display.rows().get(0), display.swapped()).right());
    }
}
