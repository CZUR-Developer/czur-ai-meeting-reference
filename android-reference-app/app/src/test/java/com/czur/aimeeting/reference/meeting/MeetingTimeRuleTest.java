package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** 用可控单调时间直接验证分钟阈值，不让设备等待数分钟。 */
public final class MeetingTimeRuleTest {
    private static final long MINUTE = 60_000L;

    @Test
    public void usesCreateLimitAndRemindsOnceRemainingReachesTwentyMinutes() {
        MeetingTimeRule rule = new MeetingTimeRule(1000L, 60 * MINUTE);
        assertEquals(60 * MINUTE, rule.at(1000L).remainingMs());
        assertFalse(rule.at(1000L + 39 * MINUTE).limitWarning());
        assertTrue(rule.at(1000L + 40 * MINUTE).limitWarning());
        assertEquals(MeetingTimeRule.StopCause.TIME_LIMIT,
                rule.at(1000L + 60 * MINUTE).stopCause());
        assertEquals(0L, rule.at(1000L + 60 * MINUTE).remainingMs());
        assertFalse(new MeetingTimeRule(0L, 20 * MINUTE).at(0L).limitWarning());
        assertEquals("01:00:00", MeetingTimeRule.format(60 * MINUTE));
    }

    @Test
    public void warnsAfterFourMinutesOnlyIfMeetingHadTextAndStopsAfterFive() {
        MeetingTimeRule empty = new MeetingTimeRule(0L, 60 * MINUTE);
        assertFalse(empty.at(4 * MINUTE).noTranscriptWarning());
        assertEquals(MeetingTimeRule.StopCause.NO_TRANSCRIPT,
                empty.at(5 * MINUTE).stopCause());

        MeetingTimeRule speaking = new MeetingTimeRule(0L, 60 * MINUTE);
        speaking.onTranscript(10L, "你好");
        assertFalse(speaking.at(10L + 4 * MINUTE - 1).noTranscriptWarning());
        assertTrue(speaking.at(10L + 4 * MINUTE).noTranscriptWarning());
        assertEquals(MINUTE, speaking.at(10L + 4 * MINUTE).noTranscriptRemainingMs());
        speaking.onTranscript(10L + 4 * MINUTE + 1000L, "Hello");
        assertFalse(speaking.at(10L + 4 * MINUTE + 1000L).noTranscriptWarning());
        assertEquals(MeetingTimeRule.StopCause.NONE,
                speaking.at(10L + 5 * MINUTE).stopCause());
        assertEquals(MeetingTimeRule.StopCause.NO_TRANSCRIPT,
                speaking.at(10L + 9 * MINUTE + 1000L).stopCause());
    }

    @Test
    public void emptyRevisionDoesNotResetIdleClockAndTimeLimitWins() {
        MeetingTimeRule rule = new MeetingTimeRule(0L, 3 * MINUTE);
        rule.onTranscript(100L, "内容");
        rule.onTranscript(MINUTE, "  ");
        assertEquals(MeetingTimeRule.StopCause.TIME_LIMIT,
                rule.at(3 * MINUTE).stopCause());
        assertEquals(0L, rule.at(3 * MINUTE).remainingMs());
    }
}
