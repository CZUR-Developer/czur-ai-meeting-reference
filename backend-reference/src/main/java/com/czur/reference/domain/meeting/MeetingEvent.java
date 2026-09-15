package com.czur.reference.domain.meeting;

/**
 * Domain events/commands that may advance a meeting lifecycle.
 */
public enum MeetingEvent {
    AUTHORIZE,
    START,
    STOP_REQUESTED,
    INPUT_ACCEPTED,
    READY,
    READY_WITH_WARNINGS,
    FAIL,
    CANCEL,
    DELETE_REQUESTED,
    DELETED
}
