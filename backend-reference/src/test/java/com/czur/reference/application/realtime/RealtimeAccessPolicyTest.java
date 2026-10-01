package com.czur.reference.application.realtime;

import com.czur.reference.application.port.InputState;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 冻结持久状态组合与实时写入/只读边界，避免 JDBC 和 WSS 各写一张表。 */
class RealtimeAccessPolicyTest {
    @Test
    void allowsOnlyExplicitInputStatesToWrite() {
        for (MeetingState state : new MeetingState[]{
                MeetingState.CREATED, MeetingState.STARTING, MeetingState.RUNNING}) {
            assertEquals(RealtimeAccessPolicy.Decision.WRITE,
                    RealtimeAccessPolicy.classify(state, InputState.INPUT_OPEN));
        }
        assertEquals(RealtimeAccessPolicy.Decision.WRITE,
                RealtimeAccessPolicy.classify(
                        MeetingState.STOPPING, InputState.INPUT_COMPLETE_PENDING));
    }

    @Test
    void keepsAuthorizedAndDeletedFactsValidButClosedToRealtimeInput() {
        assertEquals(RealtimeAccessPolicy.Decision.DENY,
                RealtimeAccessPolicy.classify(MeetingState.AUTHORIZED, InputState.INPUT_OPEN));
        for (MeetingState state : new MeetingState[]{
                MeetingState.DELETION_PENDING, MeetingState.DELETED}) {
            assertEquals(RealtimeAccessPolicy.Decision.DENY,
                    RealtimeAccessPolicy.classify(state, InputState.INPUT_ACCEPTED));
            assertEquals(RealtimeAccessPolicy.Decision.DENY,
                    RealtimeAccessPolicy.classify(state, InputState.INPUT_CANCELLED));
        }
    }

    @Test
    void readOnlyNeverGrantsAudioOrCompletion() {
        for (MeetingState state : new MeetingState[]{MeetingState.FINALIZING,
                MeetingState.READY, MeetingState.READY_WITH_WARNINGS, MeetingState.FAILED}) {
            assertEquals(RealtimeAccessPolicy.Decision.READ_ONLY,
                    RealtimeAccessPolicy.classify(state, InputState.INPUT_ACCEPTED));
        }
        assertEquals(RealtimeAccessPolicy.Decision.READ_ONLY,
                RealtimeAccessPolicy.classify(
                        MeetingState.CANCELLED, InputState.INPUT_CANCELLED));
    }

    @Test
    void rejectsEveryOtherStatePairAsInconsistent() {
        for (MeetingState state : MeetingState.values()) {
            for (InputState input : InputState.values()) {
                boolean open = input == InputState.INPUT_OPEN && switch (state) {
                    case CREATED, AUTHORIZED, STARTING, RUNNING -> true;
                    default -> false;
                };
                boolean pending = input == InputState.INPUT_COMPLETE_PENDING
                        && state == MeetingState.STOPPING;
                boolean accepted = input == InputState.INPUT_ACCEPTED && switch (state) {
                    case FINALIZING, READY, READY_WITH_WARNINGS, FAILED,
                            DELETION_PENDING, DELETED -> true;
                    default -> false;
                };
                boolean cancelled = input == InputState.INPUT_CANCELLED && switch (state) {
                    case CANCELLED, DELETION_PENDING, DELETED -> true;
                    default -> false;
                };
                if (!open && !pending && !accepted && !cancelled) {
                    assertEquals(RealtimeAccessPolicy.Decision.FACT_INCONSISTENT,
                            RealtimeAccessPolicy.classify(state, input),
                            "unexpected permitted pair " + state + "/" + input);
                }
            }
        }
        assertEquals(RealtimeAccessPolicy.Decision.FACT_INCONSISTENT,
                RealtimeAccessPolicy.classify(MeetingState.FAILED, InputState.INPUT_OPEN));
        assertEquals(RealtimeAccessPolicy.Decision.FACT_INCONSISTENT,
                RealtimeAccessPolicy.classify(
                        MeetingState.RUNNING, InputState.INPUT_COMPLETE_PENDING));
        assertThrows(NullPointerException.class,
                () -> RealtimeAccessPolicy.classify(null, InputState.INPUT_OPEN));
    }
}
