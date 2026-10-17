package com.czur.aimeeting.reference.meeting

import org.junit.Assert.*
import org.junit.Test

/** 用可控单调时间验证原分钟阈值，设备不必真实等待。 */
class MeetingTimeRuleTest {
    @Test fun usesCreateLimitAndRemindsOnceRemainingReachesTwentyMinutes() {
        val rule = MeetingTimeRule(1000L, 60 * MINUTE)
        assertEquals(60 * MINUTE, rule.at(1000L).remainingMs())
        assertFalse(rule.at(1000L + 39 * MINUTE).limitWarning()); assertTrue(rule.at(1000L + 40 * MINUTE).limitWarning())
        assertEquals(MeetingTimeRule.StopCause.TIME_LIMIT, rule.at(1000L + 60 * MINUTE).stopCause())
        assertEquals(0L, rule.at(1000L + 60 * MINUTE).remainingMs())
        assertFalse(MeetingTimeRule(0L, 20 * MINUTE).at(0L).limitWarning())
        assertEquals("01:00:00", MeetingTimeRule.format(60 * MINUTE))
    }
    @Test fun warnsAfterFourMinutesEvenBeforeFirstTextAndStopsAfterFive() {
        val empty = MeetingTimeRule(0L, 60 * MINUTE)
        assertTrue(empty.at(4 * MINUTE).noTranscriptWarning()); assertEquals(MeetingTimeRule.StopCause.NO_TRANSCRIPT, empty.at(5 * MINUTE).stopCause())
        val speaking = MeetingTimeRule(0L, 60 * MINUTE)
        speaking.onTranscript(10L, "你好")
        assertFalse(speaking.at(10L + 4 * MINUTE - 1).noTranscriptWarning())
        assertTrue(speaking.at(10L + 4 * MINUTE).noTranscriptWarning())
        assertEquals(MINUTE, speaking.at(10L + 4 * MINUTE).noTranscriptRemainingMs())
        speaking.onTranscriptActivity(10L + 4 * MINUTE + 1000L)
        assertFalse(speaking.at(10L + 4 * MINUTE + 1000L).noTranscriptWarning())
        assertEquals(MeetingTimeRule.StopCause.NONE, speaking.at(10L + 5 * MINUTE).stopCause())
        assertEquals(MeetingTimeRule.StopCause.NO_TRANSCRIPT, speaking.at(10L + 9 * MINUTE + 1000L).stopCause())
    }
    @Test fun emptyRevisionDoesNotResetIdleClockAndTimeLimitWins() {
        val rule = MeetingTimeRule(0L, 3 * MINUTE)
        rule.onTranscript(100L, "内容"); rule.onTranscript(MINUTE, "  ")
        assertEquals(MeetingTimeRule.StopCause.TIME_LIMIT, rule.at(3 * MINUTE).stopCause())
        assertEquals(0L, rule.at(3 * MINUTE).remainingMs())
    }
    @Test fun clearsOnlyVisibleTextAfterTenSecondsAndFreshTranscriptRestartsTheInterval() {
        val rule = MeetingTimeRule(1000L, 60 * MINUTE)
        rule.onTranscriptActivity(2000L)
        assertFalse(rule.at(12_000L).clearVisibleText())
        assertTrue(rule.at(12_001L).clearVisibleText())
        assertFalse(rule.at(12_001L).noTranscriptWarning())
        assertEquals(MeetingTimeRule.StopCause.NONE, rule.at(12_001L).stopCause())
        rule.onTranscriptActivity(15_000L)
        assertFalse(rule.at(25_000L).clearVisibleText())
        assertTrue(rule.at(25_001L).clearVisibleText())
        assertTrue(rule.at(15_000L + 4 * MINUTE).noTranscriptWarning())
        assertEquals(MeetingTimeRule.StopCause.NO_TRANSCRIPT, rule.at(15_000L + 5 * MINUTE).stopCause())
    }
    companion object { private const val MINUTE = 60_000L }
}
