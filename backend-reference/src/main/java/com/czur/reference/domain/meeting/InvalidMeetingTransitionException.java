package com.czur.reference.domain.meeting;

/**
 * Raised when a lifecycle event is not valid for the current state.
 */
public final class InvalidMeetingTransitionException extends IllegalStateException {
    private final MeetingState state;
    private final MeetingEvent event;

    public InvalidMeetingTransitionException(MeetingState state, MeetingEvent event) {
        super("Event " + event + " is not valid when meeting is " + state);
        this.state = state;
        this.event = event;
    }

    public MeetingState state() {
        return state;
    }

    public MeetingEvent event() {
        return event;
    }
}
