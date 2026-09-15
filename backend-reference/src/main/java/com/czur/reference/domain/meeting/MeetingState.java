package com.czur.reference.domain.meeting;

/**
 * Canonical meeting lifecycle states defined by the public protocol.
 */
public enum MeetingState {
    UNKNOWN,
    CREATED,
    AUTHORIZED,
    STARTING,
    RUNNING,
    STOPPING,
    FINALIZING,
    READY,
    READY_WITH_WARNINGS,
    FAILED,
    CANCELLED,
    DELETION_PENDING,
    DELETED
}
