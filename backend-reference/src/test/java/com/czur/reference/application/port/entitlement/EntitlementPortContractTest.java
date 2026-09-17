package com.czur.reference.application.port.entitlement;

import com.czur.reference.application.port.identity.Subject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 权益端口、范围隔离和 Reservation 状态机的纯 Java 契约测试。 */
class EntitlementPortContractTest {
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");
    private static final Instant GRANT_EXPIRES_AT = NOW.plusSeconds(600);
    private static final String DEPLOYMENT = "deployment-a";
    private static final String SUBJECT = "subject-42";
    private static final String MEETING = "meeting-1";

    @Test
    void authorizeRequestAndGrantBindDeploymentSubjectProfileAndCapabilities() {
        HashSet<String> requested = new HashSet<>(Set.of("transcript.realtime"));
        AuthorizeStartRequest request = new AuthorizeStartRequest(DEPLOYMENT,
                new Subject(SUBJECT), "profile-basic", 3, requested,
                estimate(), "client-session-1");
        requested.add("summary.structured");

        MeetingGrant grant = grant();
        assertEquals(Set.of("transcript.realtime"), request.requestedCapabilities());
        assertEquals(DEPLOYMENT, grant.deploymentScope());
        assertEquals(SUBJECT, grant.subjectScope());
        assertEquals("profile-basic", grant.profileId());
        assertEquals(3, grant.profileRevision());
        assertEquals(Set.of("transcript.realtime"), grant.requestedCapabilities());
        assertEquals(Set.of("transcript.realtime", "transcript.final"),
                grant.grantedCapabilities());
        assertFalse(grant.isExpiredAt(GRANT_EXPIRES_AT.minusNanos(1)));
        assertTrue(grant.isExpiredAt(GRANT_EXPIRES_AT));
        assertThrows(UnsupportedOperationException.class,
                () -> grant.grantedCapabilities().add("summary.structured"));
    }

    @Test
    void persistentOperationRequestsCarryAllScopesAndRejectCrossScopeGrant() {
        ReserveUsageRequest reserve = reserveRequest(grant());
        CommitUsageRequest commit = new CommitUsageRequest(DEPLOYMENT, SUBJECT,
                UsageOperationScopes.COMMIT, "commit-key", digest("b"), "reservation-1",
                1, new UsageActual(UsageUnit.MEETING_DURATION_MS, 7_000));
        CancelReservationRequest cancel = new CancelReservationRequest(DEPLOYMENT, SUBJECT,
                UsageOperationScopes.CANCEL, "cancel-key", digest("c"), "reservation-1",
                MEETING, 0, "CREATE_ROLLED_BACK");

        assertEquals(UsageOperationScopes.RESERVE, reserve.operationScope());
        assertEquals(UsageOperationScopes.COMMIT, commit.operationScope());
        assertEquals(UsageOperationScopes.CANCEL, cancel.operationScope());
        assertEquals(DEPLOYMENT, reserve.deploymentScope());
        assertEquals(SUBJECT, commit.subjectScope());
        assertThrows(IllegalArgumentException.class, () -> new ReserveUsageRequest(
                "other-deployment", SUBJECT, UsageOperationScopes.RESERVE, "key", digest("d"),
                grant(), estimate(), MEETING, NOW.plusSeconds(300)));
        assertThrows(IllegalArgumentException.class, () -> new CommitUsageRequest(
                DEPLOYMENT, SUBJECT, UsageOperationScopes.RESERVE, "key", digest("e"),
                "reservation-1", 0, new UsageActual(UsageUnit.MEETING_DURATION_MS, 1)));
    }

    @Test
    void reservationBindsOnlyExpectedMeetingBeforeExpiryAndIncrementsVersion() {
        UsageReservation reserved = reservation();

        UsageReservationTransitionException wrongMeeting = assertThrows(
                UsageReservationTransitionException.class,
                () -> reserved.bind("meeting-other", NOW.plusSeconds(1), 0));
        assertEquals(UsageReservationTransitionException.ReasonCode.EXPECTED_MEETING_MISMATCH,
                wrongMeeting.reasonCode());

        UsageReservation bound = reserved.bind(MEETING, NOW.plusSeconds(1), 0);
        assertNotSame(reserved, bound);
        assertFalse(reserved.isBound());
        assertTrue(bound.isBound());
        assertEquals(MEETING, bound.boundMeetingId());
        assertEquals(1, bound.version());

        UsageReservationTransitionException stale = assertThrows(
                UsageReservationTransitionException.class,
                () -> bound.commit(new UsageActual(UsageUnit.MEETING_DURATION_MS, 1),
                        NOW.plusSeconds(2), 0));
        assertEquals(UsageReservationTransitionException.ReasonCode.VERSION_CONFLICT,
                stale.reasonCode());

        UsageReservationTransitionException expired = assertThrows(
                UsageReservationTransitionException.class,
                () -> reserved.bind(MEETING, NOW.plusSeconds(300), 0));
        assertEquals(UsageReservationTransitionException.ReasonCode.RESERVATION_EXPIRED,
                expired.reasonCode());
    }

    @Test
    void boundReservationCanCommitAfterExpiryButCannotBeCancelled() {
        UsageReservation bound = reservation().bind(MEETING, NOW.plusSeconds(1), 0);
        UsageReservation committed = bound.commit(
                new UsageActual(UsageUnit.MEETING_DURATION_MS, 8_000),
                NOW.plusSeconds(900), 1);

        assertEquals(UsageReservation.State.COMMITTED, committed.state());
        assertEquals(2, committed.version());
        assertEquals(8_000, committed.actualUsage().amount());
        assertTrue(committed.committedAt().isAfter(committed.expiresAt()));

        UsageReservationTransitionException cancelBound = assertThrows(
                UsageReservationTransitionException.class,
                () -> bound.cancel("ORPHAN_CLEANUP", NOW.plusSeconds(2), 1));
        assertEquals(UsageReservationTransitionException.ReasonCode.ALREADY_BOUND,
                cancelBound.reasonCode());

        UsageReservationTransitionException terminal = assertThrows(
                UsageReservationTransitionException.class,
                () -> committed.commit(new UsageActual(UsageUnit.MEETING_DURATION_MS, 8_000),
                        NOW.plusSeconds(901), 2));
        assertEquals(UsageReservationTransitionException.ReasonCode.TERMINAL_STATE,
                terminal.reasonCode());
    }

    @Test
    void unboundReservationCanCancelAfterExpiryAndTerminalStatesAreMutuallyExclusive() {
        UsageReservation cancelled = reservation().cancel(
                "EXPIRED_UNBOUND", NOW.plusSeconds(900), 0);

        assertEquals(UsageReservation.State.CANCELLED, cancelled.state());
        assertEquals(1, cancelled.version());
        assertEquals("EXPIRED_UNBOUND", cancelled.cancellationReasonCode());
        assertThrows(UsageReservationTransitionException.class,
                () -> cancelled.bind(MEETING, NOW.plusSeconds(901), 1));
        assertThrows(IllegalArgumentException.class, () -> new UsageReservation(
                "reservation-1", DEPLOYMENT, SUBJECT, "grant-1", MEETING,
                null, null, estimate(), new UsageActual(UsageUnit.MEETING_DURATION_MS, 1),
                NOW, NOW.plusSeconds(300), UsageReservation.State.CANCELLED, 1,
                NOW.plusSeconds(2), NOW.plusSeconds(3), "EXPIRED_UNBOUND"));
    }

    @Test
    void operationResultsAreStableSnapshotsWithExplicitScopes() {
        UsageReservation reserved = reservation();
        UsageOperationResult first = new UsageOperationResult(DEPLOYMENT, SUBJECT,
                UsageOperationScopes.RESERVE, "reserve-key", digest("a"), reserved);
        UsageOperationResult replay = first;

        assertEquals(first, replay);
        assertEquals(first.reservation(), replay.reservation());
        assertEquals(DEPLOYMENT, replay.deploymentScope());
        assertEquals(SUBJECT, replay.subjectScope());
        assertEquals(UsageOperationScopes.RESERVE, replay.operationScope());
        assertThrows(IllegalArgumentException.class, () -> new UsageOperationResult(
                DEPLOYMENT, SUBJECT, UsageOperationScopes.COMMIT, "key", digest("f"), reserved));
    }

    @Test
    void entitlementErrorsExposeStableCodesWithoutBusinessDetails() {
        EntitlementProviderResult<MeetingGrant> result = EntitlementProviderResult.failure(
                new EntitlementProviderError(
                        EntitlementProviderError.Code.ENTITLEMENT_DENIED, false));

        assertFalse(result.isSuccess());
        assertEquals("ai_meeting.entitlement.entitlement_denied",
                result.errorOrThrow().messageKey());
        assertThrows(IllegalStateException.class, result::valueOrThrow);
    }

    private static MeetingGrant grant() {
        return new MeetingGrant("grant-1", DEPLOYMENT, SUBJECT, "profile-basic", 3,
                Set.of("transcript.realtime"),
                Set.of("transcript.realtime", "transcript.final"),
                3_600_000, NOW, GRANT_EXPIRES_AT);
    }

    private static UsageEstimate estimate() {
        return new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000);
    }

    private static ReserveUsageRequest reserveRequest(MeetingGrant grant) {
        return new ReserveUsageRequest(DEPLOYMENT, SUBJECT, UsageOperationScopes.RESERVE,
                "reserve-key", digest("a"), grant, estimate(), MEETING,
                NOW.plusSeconds(300));
    }

    private static UsageReservation reservation() {
        return UsageReservation.reserved("reservation-1", grant(), MEETING, estimate(), NOW,
                NOW.plusSeconds(300));
    }

    private static String digest(String value) {
        return value.repeat(64);
    }
}
