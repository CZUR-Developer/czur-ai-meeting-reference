package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.assertEquals;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import org.junit.Test;

/** 布局切换只改变呈现；会议显示快照仍是同一份。 */
public final class OverlayLayoutStateTest {
    @Test
    public void subtitleMiniRestoresWithoutDiscardingNewTranscript() {
        OverlayLayoutState layout = new OverlayLayoutState(false);
        MeetingDisplayState display = new MeetingDisplayState();
        display.start(0L);
        layout.collapse();
        assertEquals(OverlayLayoutState.Type.MINI, layout.current());
        display.apply(new TranscriptEvent("part", 1, 0, 100, "zh-CN", "收起期间", true));
        layout.restore();
        assertEquals(OverlayLayoutState.Type.SUBTITLE, layout.current());
        assertEquals("收起期间", display.transcript());
        layout.showLarge();
        assertEquals(OverlayLayoutState.Type.SUBTITLE, layout.current());
    }

    @Test
    public void translationNormalLargeAndMiniRestoreKeepRoutedRows() {
        OverlayLayoutState layout = new OverlayLayoutState(true);
        MeetingDisplayState display = new MeetingDisplayState();
        display.start(0L, true, "zh-CN", "en-US");
        layout.showLarge();
        assertEquals(OverlayLayoutState.Type.TRANSLATION_LARGE, layout.current());
        layout.collapse();
        display.apply(new TranscriptEvent("source", 1, 0, 100, "en-US", "Hello", true));
        display.apply(new TranslationEvent("translated", "source", 1,
                "en-US", "zh-CN", "你好", true));
        layout.restore();
        assertEquals(OverlayLayoutState.Type.TRANSLATION_LARGE, layout.current());
        assertEquals("你好", display.latestBilingualRow().left());
        assertEquals("Hello", display.latestBilingualRow().right());
        layout.showNormal();
        assertEquals(OverlayLayoutState.Type.TRANSLATION_NORMAL, layout.current());
        assertEquals("Hello", display.latestBilingualRow().right());
    }
}
