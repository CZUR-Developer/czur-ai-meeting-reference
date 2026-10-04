package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import org.junit.Test;

public final class MeetingDisplayStateTest {
    @Test
    public void revisionAndFinalShareOneDisplayState() {
        MeetingDisplayState state = new MeetingDisplayState();
        state.start(1000L);
        assertEquals("等待字幕…", state.transcript());
        assertEquals("00:00:00", state.elapsed(1000L));
        state.apply(new TranscriptEvent("part-1", 1, 0, 100, "zh-CN", "大家", false));
        assertEquals("大家", state.transcript());
        assertEquals(0.8f, state.transcriptAlpha(), 0.0f);
        state.apply(new TranscriptEvent("part-1", 2, 0, 500, "zh-CN", "大家好", true));
        assertEquals("大家好", state.transcript());
        assertEquals(1.0f, state.transcriptAlpha(), 0.0f);
        state.apply(new TranscriptEvent("part-2", 1, 500, 900, "zh-CN", "欢迎", false));
        assertEquals(2, state.segments().size());
        assertEquals("大家好", state.segments().get(0).text());
        assertEquals("欢迎", state.segments().get(1).text());
        state.apply(new TranscriptEvent("part-2", 2, 500, 900, "zh-CN", "欢迎参加", true));
        assertEquals(2, state.segments().size());
        assertEquals("欢迎参加", state.segments().get(1).text());
        assertEquals(1.0f, state.segments().get(1).alpha(), 0.0f);
        assertEquals("01:01:01", state.elapsed(3662000L));
    }

    @Test
    public void bilingualRowsPairBySourceAndPlaceEnglishSpeechOnRight() {
        MeetingDisplayState state = new MeetingDisplayState();
        state.start(0L);
        state.apply(new TranscriptEvent("speech-1", 1, 0, 100, "en-US", "Hello", false));
        state.apply(new TranslationEvent("translation-1", "speech-1", 1,
                "en-US", "zh-CN", "你", false));
        assertEquals(1, state.bilingualRows().size());
        assertEquals("你", state.bilingualRows().get(0).chinese());
        assertEquals("Hello", state.bilingualRows().get(0).english());
        assertEquals(0.8f, state.bilingualRows().get(0).chineseAlpha(), 0.0f);
        state.apply(new TranslationEvent("translation-1", "speech-1", 2,
                "en-US", "zh-CN", "你好", true));
        state.apply(new TranslationEvent("translation-1", "speech-1", 1,
                "en-US", "zh-CN", "旧译文", false));
        assertEquals("你好", state.bilingualRows().get(0).chinese());
        assertEquals(1.0f, state.bilingualRows().get(0).chineseAlpha(), 0.0f);
        state.apply(new TranscriptEvent("speech-2", 1, 100, 200,
                "zh-CN", "早上好", true));
        state.apply(new TranslationEvent("translation-2", "speech-2", 1,
                "zh-CN", "en-US", "Good morning", true));
        assertEquals("早上好", state.bilingualRows().get(1).chinese());
        assertEquals("Good morning", state.bilingualRows().get(1).english());
        state.start(200L);
        assertEquals(0, state.bilingualRows().size());
        assertFalse(state.hasTranslation());
    }
}
