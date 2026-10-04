package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 直接跳到阈值验证弹窗可见期和新转写后的关闭。 */
public final class MeetingReminderPresentationTest {
    private static final long MINUTE = 60_000L;

    @Test public void twentyMinuteReminderAppearsForTenSecondsOnce() {
        MeetingTimeRule time = new MeetingTimeRule(0L, 60 * MINUTE);
        time.onTranscript(39 * MINUTE, "仍在转写");
        MeetingReminderPresentation presentation = new MeetingReminderPresentation();
        assertEquals(MeetingReminderPresentation.Kind.NONE,
                presentation.at(time.at(40 * MINUTE - 1)).kind());
        assertEquals(MeetingReminderPresentation.Kind.TIME_LIMIT,
                presentation.at(time.at(40 * MINUTE)).kind());
        assertEquals(MeetingReminderPresentation.Kind.TIME_LIMIT,
                presentation.at(time.at(40 * MINUTE + 9_999)).kind());
        assertEquals(MeetingReminderPresentation.Kind.NONE,
                presentation.at(time.at(40 * MINUTE + 10_000)).kind());
        assertEquals(MeetingReminderPresentation.Kind.NONE,
                presentation.at(time.at(41 * MINUTE)).kind());
    }

    @Test public void confirmClosesTwentyMinuteNoticeImmediately() {
        MeetingTimeRule time = new MeetingTimeRule(0L, 60 * MINUTE);
        time.onTranscript(39 * MINUTE, "仍在转写");
        MeetingReminderPresentation presentation = new MeetingReminderPresentation();
        assertEquals(MeetingReminderPresentation.Kind.TIME_LIMIT,
                presentation.at(time.at(40 * MINUTE)).kind());
        presentation.confirm();
        assertEquals(MeetingReminderPresentation.Kind.NONE,
                presentation.at(time.at(40 * MINUTE + 1000)).kind());
    }

    @Test public void idleCountdownClosesAsSoonAsNewTranscriptArrives() {
        MeetingTimeRule time = new MeetingTimeRule(0L, 60 * MINUTE);
        MeetingReminderPresentation presentation = new MeetingReminderPresentation();
        assertEquals(MeetingReminderPresentation.Kind.NO_TRANSCRIPT,
                presentation.at(time.at(4 * MINUTE)).kind());
        assertTrue(presentation.at(time.at(4 * MINUTE + 1000)).message()
                .contains("00:00:59"));
        time.onTranscript(4 * MINUTE + 2000, "恢复转写");
        assertEquals(MeetingReminderPresentation.Kind.NONE,
                presentation.at(time.at(4 * MINUTE + 2000)).kind());
        assertEquals(MeetingReminderPresentation.Kind.NO_TRANSCRIPT,
                presentation.at(time.at(8 * MINUTE + 2000)).kind());
        assertEquals(MeetingReminderPresentation.Kind.NONE,
                presentation.at(time.at(9 * MINUTE + 2000)).kind());
    }

    @Test public void confirmClosesCurrentNoticeWithoutSuppressingNextIdleInterval() {
        MeetingTimeRule time = new MeetingTimeRule(0L, 60 * MINUTE);
        MeetingReminderPresentation presentation = new MeetingReminderPresentation();
        assertEquals(MeetingReminderPresentation.Kind.NO_TRANSCRIPT,
                presentation.at(time.at(4 * MINUTE)).kind());
        presentation.confirm();
        assertEquals(MeetingReminderPresentation.Kind.NONE,
                presentation.at(time.at(4 * MINUTE + 1000)).kind());
        time.onTranscript(4 * MINUTE + 2000, "新转写");
        assertEquals(MeetingReminderPresentation.Kind.NONE,
                presentation.at(time.at(4 * MINUTE + 2000)).kind());
        assertEquals(MeetingReminderPresentation.Kind.NO_TRANSCRIPT,
                presentation.at(time.at(8 * MINUTE + 2000)).kind());
    }
}
