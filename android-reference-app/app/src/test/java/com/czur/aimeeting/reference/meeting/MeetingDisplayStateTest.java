package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.assertEquals;
import com.czur.aimeeting.model.TranscriptEvent;
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
}
