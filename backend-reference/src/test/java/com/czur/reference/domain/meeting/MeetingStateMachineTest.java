package com.czur.reference.domain.meeting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeetingStateMachineTest {
    @Test
    void followsTheCanonicalSuccessfulLifecycle() {
        MeetingStateMachine machine = new MeetingStateMachine();

        machine.apply(MeetingEvent.AUTHORIZE);
        machine.apply(MeetingEvent.START);
        machine.apply(MeetingEvent.START);
        machine.apply(MeetingEvent.STOP_REQUESTED);
        machine.apply(MeetingEvent.INPUT_ACCEPTED);
        machine.apply(MeetingEvent.READY);
        machine.apply(MeetingEvent.DELETE_REQUESTED);
        machine.apply(MeetingEvent.DELETED);

        assertEquals(MeetingState.DELETED, machine.state());
    }

    @Test
    void repeatedStopIsIdempotentAfterStopWasAccepted() {
        MeetingStateMachine machine = new MeetingStateMachine(MeetingState.RUNNING);

        assertEquals(MeetingState.STOPPING, machine.apply(MeetingEvent.STOP_REQUESTED));
        assertEquals(MeetingState.STOPPING, machine.apply(MeetingEvent.STOP_REQUESTED));
        assertEquals(MeetingState.FINALIZING, machine.apply(MeetingEvent.INPUT_ACCEPTED));
        assertEquals(MeetingState.FINALIZING, machine.apply(MeetingEvent.STOP_REQUESTED));
    }

    @Test
    void rejectsEventsThatBreakTheLifecycle() {
        MeetingStateMachine machine = new MeetingStateMachine();

        assertFalse(machine.canApply(MeetingEvent.READY));
        InvalidMeetingTransitionException error = assertThrows(
                InvalidMeetingTransitionException.class,
                () -> machine.apply(MeetingEvent.READY));
        assertEquals(MeetingState.CREATED, error.state());
        assertEquals(MeetingEvent.READY, error.event());
    }

    @Test
    void allowsCancellationBeforeFinalizationButNotAfterward() {
        MeetingStateMachine machine = new MeetingStateMachine(MeetingState.RUNNING);
        assertEquals(MeetingState.CANCELLED, machine.apply(MeetingEvent.CANCEL));
        assertThrows(InvalidMeetingTransitionException.class, () -> machine.apply(MeetingEvent.READY));
    }

    @Test
    void warningIsASeparateSuccessfulTerminalState() {
        MeetingStateMachine machine = new MeetingStateMachine(MeetingState.FINALIZING);
        assertEquals(MeetingState.READY_WITH_WARNINGS, machine.apply(MeetingEvent.READY_WITH_WARNINGS));
        assertEquals(MeetingState.DELETION_PENDING, machine.apply(MeetingEvent.DELETE_REQUESTED));
    }
}
