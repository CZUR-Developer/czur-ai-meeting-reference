package com.czur.reference.domain.meeting;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeetingAggregateTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-15T01:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-15T01:00:01Z");

    @Test
    void capturesIdentityProfileAndImmutableSnapshot() {
        MeetingAggregate aggregate = aggregate();

        MeetingAggregate.MeetingSnapshot snapshot = aggregate.snapshot();

        assertEquals("meeting-1", snapshot.meetingId());
        assertEquals("default", snapshot.deploymentScope());
        assertEquals("client-session-1", snapshot.clientSessionId());
        assertEquals("meeting-minutes/v1", snapshot.profileId());
        assertEquals(3, snapshot.profileRevision());
        assertEquals(MeetingState.CREATED, snapshot.state());
        assertEquals(CREATED_AT, snapshot.createdAt());
        assertEquals(CREATED_AT, snapshot.updatedAt());
        assertEquals(MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE, snapshot.subjectScope());
        assertNull(snapshot.grantId());
        assertNull(snapshot.grantExpiresAt());
        assertNull(snapshot.usageReservationId());
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
    void restoresDeploymentScopeFromSnapshot() {
        MeetingAggregate original = new MeetingAggregate(
                "meeting-1", "partner-a", "client-session-1", "meeting-minutes/v1", 3, CREATED_AT);

        MeetingAggregate restored = MeetingAggregate.fromSnapshot(original.snapshot());

        assertEquals("partner-a", restored.deploymentScope());
        assertEquals("partner-a", restored.snapshot().deploymentScope());
    }

    @Test
    void ownedSnapshotRequiresAndPreservesCompleteOwnershipFacts() {
        Instant nanos = Instant.parse("2026-09-15T01:00:00.123456789Z");
        Instant expires = Instant.parse("2026-09-15T01:10:00.987654321Z");
        MeetingAggregate aggregate = MeetingAggregate.owned(
                "owned-meeting", "deployment-a", "session-a", "subject-a",
                "grant-a", expires, "reservation-a", "profile", 1, nanos);

        MeetingAggregate.MeetingSnapshot snapshot = aggregate.snapshot();
        MeetingAggregate restored = MeetingAggregate.fromSnapshot(snapshot);

        assertEquals("subject-a", snapshot.subjectScope());
        assertEquals("grant-a", snapshot.grantId());
        assertEquals("reservation-a", snapshot.usageReservationId());
        assertEquals(Instant.parse("2026-09-15T01:00:00.123456Z"), snapshot.createdAt());
        assertEquals(Instant.parse("2026-09-15T01:10:00.987654Z"), snapshot.grantExpiresAt());
        assertEquals(snapshot, restored.snapshot());
    }

    @Test
    void rejectsPartialOwnedSnapshotsAndReservedLegacySubject() {
        assertThrows(IllegalArgumentException.class, () -> new MeetingAggregate.MeetingSnapshot(
                "meeting", "deployment", "session", "subject", "grant", null,
                "reservation", "profile", 1, MeetingState.CREATED, CREATED_AT, CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> new MeetingAggregate.MeetingSnapshot(
                "meeting", "deployment", "session",
                MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE, "grant", CREATED_AT.plusSeconds(1),
                "reservation", "profile", 1, MeetingState.CREATED, CREATED_AT, CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> MeetingAggregate.owned(
                "meeting", "deployment", "session",
                MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE, "grant",
                CREATED_AT.plusSeconds(1), "reservation", "profile", 1, CREATED_AT));
    }

    @Test
    void subMicrosecondGrantWindowFailsClosedAfterDatabasePrecisionNormalization() {
        Instant created = Instant.parse("2026-09-15T01:00:00.123456100Z");
        Instant expiresLessThanOneMicrosecondLater =
                Instant.parse("2026-09-15T01:00:00.123456900Z");

        assertThrows(IllegalArgumentException.class, () -> MeetingAggregate.owned(
                "meeting", "deployment", "session", "subject", "grant",
                expiresLessThanOneMicrosecondLater, "reservation", "profile", 1, created));
    }

    @Test
    void oldAggregateAndSnapshotConstructorsUseDefaultScope() {
        MeetingAggregate aggregate = aggregate();
        MeetingAggregate.MeetingSnapshot legacySnapshot = new MeetingAggregate.MeetingSnapshot(
                "meeting-legacy", "session-legacy", "profile", 1, MeetingState.CREATED,
                CREATED_AT, CREATED_AT);

        assertEquals("default", aggregate.deploymentScope());
        assertEquals("default", legacySnapshot.deploymentScope());
        assertEquals("default", MeetingAggregate.fromSnapshot(legacySnapshot).deploymentScope());
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
