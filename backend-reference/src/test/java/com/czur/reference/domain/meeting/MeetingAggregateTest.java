package com.czur.reference.domain.meeting;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeetingAggregateTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-15T01:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-15T01:00:01Z");

    @Test
    void capturesIdentityProfileAndImmutableSnapshot() {
        MeetingAggregate aggregate = aggregate();

        MeetingAggregate.MeetingSnapshot snapshot = aggregate.snapshot();

        assertEquals("meeting-1", snapshot.meetingId());
        assertEquals("client-session-1", snapshot.clientSessionId());
        assertEquals("meeting-minutes/v1", snapshot.profileId());
        assertEquals(3, snapshot.profileRevision());
        assertEquals(MeetingState.CREATED, snapshot.state());
        assertEquals(CREATED_AT, snapshot.createdAt());
        assertEquals(CREATED_AT, snapshot.updatedAt());
    }

    @Test
    void rejectsBlankIdentityAndInvalidProfile() {
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingAggregate(" ", "session", "profile", 1, CREATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingAggregate("meeting", "", "profile", 1, CREATED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingAggregate("meeting", "session", "profile", 0, CREATED_AT));
        assertThrows(NullPointerException.class,
                () -> new MeetingAggregate("meeting", "session", "profile", 1, null));
    }

    @Test
    void delegatesLifecycleAndKeepsTimeMonotonic() {
        MeetingAggregate aggregate = aggregate();

        assertEquals(MeetingState.AUTHORIZED, aggregate.apply(MeetingEvent.AUTHORIZE, UPDATED_AT));
        assertEquals(MeetingState.AUTHORIZED, aggregate.state());
        assertEquals(UPDATED_AT, aggregate.updatedAt());
        assertThrows(IllegalArgumentException.class,
                () -> aggregate.apply(MeetingEvent.START, CREATED_AT));
    }

    @Test
    void repeatedStopDoesNotAdvanceStateAndTerminalStateRejectsEvents() {
        MeetingAggregate aggregate = new MeetingAggregate(
                "meeting-1", "session-1", "profile", 1, CREATED_AT);
        aggregate.apply(MeetingEvent.AUTHORIZE, UPDATED_AT);
        aggregate.apply(MeetingEvent.START, UPDATED_AT);
        aggregate.apply(MeetingEvent.START, UPDATED_AT);
        aggregate.apply(MeetingEvent.STOP_REQUESTED, UPDATED_AT);
        assertEquals(MeetingState.STOPPING, aggregate.apply(MeetingEvent.STOP_REQUESTED, UPDATED_AT));
        aggregate.apply(MeetingEvent.INPUT_ACCEPTED, UPDATED_AT);
        aggregate.apply(MeetingEvent.READY, UPDATED_AT);
        assertThrows(InvalidMeetingTransitionException.class,
                () -> aggregate.apply(MeetingEvent.STOP_REQUESTED, UPDATED_AT));
    }

    private static MeetingAggregate aggregate() {
        return new MeetingAggregate(
                "meeting-1", "client-session-1", "meeting-minutes/v1", 3, CREATED_AT);
    }
}
