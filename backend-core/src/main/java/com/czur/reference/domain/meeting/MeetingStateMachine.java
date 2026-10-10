package com.czur.reference.domain.meeting;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure in-memory validator for the canonical meeting lifecycle.
 *
 * <p>Persistence, idempotency records and input-state tracking belong to the
 * application layer. A repeated stop request is therefore represented as a
 * no-op while the meeting is already stopping or finalizing.</p>
 */
public final class MeetingStateMachine {
    private static final Map<MeetingState, Set<MeetingEvent>> ALLOWED = allowedTransitions();

    private MeetingState state;

    public MeetingStateMachine() {
        this(MeetingState.CREATED);
    }

    public MeetingStateMachine(MeetingState initialState) {
        if (initialState == null || initialState == MeetingState.UNKNOWN) {
            throw new IllegalArgumentException("A meeting must start from a concrete state");
        }
        this.state = initialState;
    }

    public MeetingState state() {
        return state;
    }

    /** 仅供聚合从已校验快照恢复，禁止应用层绕过状态转换直接调用。 */
    void restore(MeetingState restoredState) {
        if (restoredState == null || restoredState == MeetingState.UNKNOWN) {
            throw new IllegalArgumentException("restoredState must be concrete");
        }
        state = restoredState;
    }

    public boolean canApply(MeetingEvent event) {
        if (event == null) {
            return false;
        }
        return ALLOWED.getOrDefault(state, Set.of()).contains(event);
    }

    public MeetingState apply(MeetingEvent event) {
        if (!canApply(event)) {
            throw new InvalidMeetingTransitionException(state, event);
        }
        if (event != MeetingEvent.STOP_REQUESTED || (state != MeetingState.STOPPING && state != MeetingState.FINALIZING)) {
            state = nextState(state, event);
        }
        return state;
    }

    private static MeetingState nextState(MeetingState current, MeetingEvent event) {
        return switch (event) {
            case AUTHORIZE -> MeetingState.AUTHORIZED;
            case START -> current == MeetingState.AUTHORIZED ? MeetingState.STARTING : MeetingState.RUNNING;
            case STOP_REQUESTED -> MeetingState.STOPPING;
            case INPUT_ACCEPTED -> MeetingState.FINALIZING;
            case READY -> MeetingState.READY;
            case READY_WITH_WARNINGS -> MeetingState.READY_WITH_WARNINGS;
            case FAIL -> MeetingState.FAILED;
            case CANCEL -> MeetingState.CANCELLED;
            case DELETE_REQUESTED -> MeetingState.DELETION_PENDING;
            case DELETED -> MeetingState.DELETED;
        };
    }

    private static Map<MeetingState, Set<MeetingEvent>> allowedTransitions() {
        EnumMap<MeetingState, Set<MeetingEvent>> transitions = new EnumMap<>(MeetingState.class);
        transitions.put(MeetingState.CREATED, EnumSet.of(MeetingEvent.AUTHORIZE, MeetingEvent.CANCEL));
        transitions.put(MeetingState.AUTHORIZED, EnumSet.of(MeetingEvent.START, MeetingEvent.CANCEL));
        transitions.put(MeetingState.STARTING, EnumSet.of(MeetingEvent.START, MeetingEvent.STOP_REQUESTED, MeetingEvent.FAIL, MeetingEvent.CANCEL));
        transitions.put(MeetingState.RUNNING, EnumSet.of(MeetingEvent.STOP_REQUESTED, MeetingEvent.FAIL, MeetingEvent.CANCEL));
        transitions.put(MeetingState.STOPPING, EnumSet.of(MeetingEvent.STOP_REQUESTED, MeetingEvent.INPUT_ACCEPTED, MeetingEvent.FAIL, MeetingEvent.CANCEL));
        transitions.put(MeetingState.FINALIZING, EnumSet.of(MeetingEvent.STOP_REQUESTED, MeetingEvent.READY, MeetingEvent.READY_WITH_WARNINGS, MeetingEvent.FAIL));
        transitions.put(MeetingState.READY, EnumSet.of(MeetingEvent.DELETE_REQUESTED));
        transitions.put(MeetingState.READY_WITH_WARNINGS, EnumSet.of(MeetingEvent.DELETE_REQUESTED));
        transitions.put(MeetingState.FAILED, EnumSet.of(MeetingEvent.DELETE_REQUESTED));
        transitions.put(MeetingState.CANCELLED, EnumSet.of(MeetingEvent.DELETE_REQUESTED));
        transitions.put(MeetingState.DELETION_PENDING, EnumSet.of(MeetingEvent.DELETED));
        transitions.put(MeetingState.DELETED, Set.of());
        return Map.copyOf(transitions);
    }
}
