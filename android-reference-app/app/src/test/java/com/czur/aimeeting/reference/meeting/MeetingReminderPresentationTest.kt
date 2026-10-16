package com.czur.aimeeting.reference.meeting

import org.junit.Assert.*
import org.junit.Test

class MeetingReminderPresentationTest {
    @Test fun twentyMinuteReminderAppearsForTenSecondsOnce() {
        val time = MeetingTimeRule(0L, 60 * MINUTE); time.onTranscript(39 * MINUTE, "仍在转写")
        val presentation = MeetingReminderPresentation()
        assertEquals(MeetingReminderPresentation.Kind.NONE, presentation.at(time.at(40 * MINUTE - 1)).kind())
        assertEquals(MeetingReminderPresentation.Kind.TIME_LIMIT, presentation.at(time.at(40 * MINUTE)).kind())
        assertEquals(MeetingReminderPresentation.Kind.TIME_LIMIT, presentation.at(time.at(40 * MINUTE + 9_999)).kind())
        assertEquals(MeetingReminderPresentation.Kind.NONE, presentation.at(time.at(40 * MINUTE + 10_000)).kind())
        assertEquals(MeetingReminderPresentation.Kind.NONE, presentation.at(time.at(41 * MINUTE)).kind())
    }
    @Test fun confirmClosesTwentyMinuteNoticeImmediately() {
        val time = MeetingTimeRule(0L, 60 * MINUTE); time.onTranscript(39 * MINUTE, "仍在转写")
        val presentation = MeetingReminderPresentation()
        assertEquals(MeetingReminderPresentation.Kind.TIME_LIMIT, presentation.at(time.at(40 * MINUTE)).kind())
        presentation.confirm()
        assertEquals(MeetingReminderPresentation.Kind.NONE, presentation.at(time.at(40 * MINUTE + 1000)).kind())
    }
    @Test fun idleCountdownClosesAsSoonAsNewTranscriptArrives() {
        val time = MeetingTimeRule(0L, 60 * MINUTE); val presentation = MeetingReminderPresentation()
        assertEquals(MeetingReminderPresentation.Kind.NO_TRANSCRIPT, presentation.at(time.at(4 * MINUTE)).kind())
        assertTrue(presentation.at(time.at(4 * MINUTE + 1000)).message().contains("00:00:59"))
        time.onTranscript(4 * MINUTE + 2000, "恢复转写")
        assertEquals(MeetingReminderPresentation.Kind.NONE, presentation.at(time.at(4 * MINUTE + 2000)).kind())
        assertEquals(MeetingReminderPresentation.Kind.NO_TRANSCRIPT, presentation.at(time.at(8 * MINUTE + 2000)).kind())
        assertEquals(MeetingReminderPresentation.Kind.NONE, presentation.at(time.at(9 * MINUTE + 2000)).kind())
    }
    @Test fun confirmClosesCurrentNoticeWithoutSuppressingNextIdleInterval() {
        val time = MeetingTimeRule(0L, 60 * MINUTE); val presentation = MeetingReminderPresentation()
        assertEquals(MeetingReminderPresentation.Kind.NO_TRANSCRIPT, presentation.at(time.at(4 * MINUTE)).kind())
        presentation.confirm()
        assertEquals(MeetingReminderPresentation.Kind.NONE, presentation.at(time.at(4 * MINUTE + 1000)).kind())
        time.onTranscript(4 * MINUTE + 2000, "新转写")
        assertEquals(MeetingReminderPresentation.Kind.NONE, presentation.at(time.at(4 * MINUTE + 2000)).kind())
        assertEquals(MeetingReminderPresentation.Kind.NO_TRANSCRIPT, presentation.at(time.at(8 * MINUTE + 2000)).kind())
    }
    companion object { private const val MINUTE = 60_000L }
}
