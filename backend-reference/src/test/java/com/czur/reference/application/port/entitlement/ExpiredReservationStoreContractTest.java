package com.czur.reference.application.port.entitlement;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 到期预占 claim/release 的批量复制、边界和 fencing 值对象测试。 */
class ExpiredReservationStoreContractTest {
    private static final Instant NOW = Instant.parse("2026-09-17T03:00:00Z");

    @Test
    void claimBatchDefensivelyCopiesAndBindsEveryClaimToRequest() {
        ExpiredReservationStore.ClaimRequest request = new ExpiredReservationStore.ClaimRequest(
                "deployment-a", "worker-a", NOW, Duration.ofSeconds(30), 2);
        ExpiredReservationStore.ClaimedReservation claim = claimed(
                expiredReservation("reservation-a"), request, "token-a", 4);
        ArrayList<ExpiredReservationStore.ClaimedReservation> mutable =
                new ArrayList<>(List.of(claim));

        ExpiredReservationStore.ClaimBatch batch =
                new ExpiredReservationStore.ClaimBatch(request, mutable);
        mutable.clear();

        assertEquals(1, batch.claims().size());
        assertEquals(request.leaseUntil(), batch.claims().get(0).lease().expiresAt());
        assertThrows(UnsupportedOperationException.class, () -> batch.claims().clear());
        assertThrows(IllegalArgumentException.class, () ->
                new ExpiredReservationStore.ClaimBatch(
                        new ExpiredReservationStore.ClaimRequest(
                                "deployment-other", "worker-a", NOW,
                                Duration.ofSeconds(30), 2),
                        List.of(claim)));
        assertThrows(IllegalArgumentException.class, () ->
                new ExpiredReservationStore.ClaimBatch(
                        new ExpiredReservationStore.ClaimRequest(
                                "deployment-a", "worker-a", NOW,
                                Duration.ofSeconds(30), 1),
                        List.of(claim, claimed(
                                expiredReservation("reservation-b"), request, "token-b", 5))));
    }

    @Test
    void claimedReservationMustBeExpiredReservedUnboundAndScopeMatched() {
        ExpiredReservationStore.ClaimRequest request = new ExpiredReservationStore.ClaimRequest(
                "deployment-a", "worker-a", NOW, Duration.ofSeconds(30), 1);
        UsageReservation expired = expiredReservation("reservation-a");
        ExpiredReservationStore.LeaseToken lease = lease(expired, request, "token-a", 1);
        assertEquals(expired,
                new ExpiredReservationStore.ClaimedReservation(expired, lease).reservation());

        UsageReservation notExpired = reservation(
                "reservation-future", NOW.minusSeconds(1), NOW.plusSeconds(1));
        assertThrows(IllegalArgumentException.class, () ->
                new ExpiredReservationStore.ClaimedReservation(
                        notExpired, lease(notExpired, request, "token-future", 1)));

        UsageReservation bound = expired.bind("meeting-expected", NOW.minusSeconds(1), 0);
        assertThrows(IllegalArgumentException.class, () ->
                new ExpiredReservationStore.ClaimedReservation(
                        bound, lease(bound, request, "token-bound", 1)));

        ExpiredReservationStore.LeaseToken wrongScope = new ExpiredReservationStore.LeaseToken(
                "deployment-a", "subject-other", expired.reservationId(), "worker-a",
                "token-other", NOW, NOW.plusSeconds(30), 1);
        assertThrows(IllegalArgumentException.class, () ->
                new ExpiredReservationStore.ClaimedReservation(expired, wrongScope));
    }

    @Test
    void leaseTokenIsOpaqueAndRequiresPositiveFencingVersion() {
        UsageReservation reservation = expiredReservation("reservation-a");
        ExpiredReservationStore.ClaimRequest request = new ExpiredReservationStore.ClaimRequest(
                "deployment-a", "worker-a", NOW, Duration.ofSeconds(30), 1);
        ExpiredReservationStore.LeaseToken lease =
                lease(reservation, request, "sensitive-lease-token", 9);

        assertEquals(9, lease.leaseVersion());
        assertEquals("ExpiredReservationLeaseToken[REDACTED]", lease.toString());
        assertFalse(lease.toString().contains("sensitive-lease-token"));
        assertFalse(lease.isExpiredAt(NOW.plusSeconds(29)));
        assertTrue(lease.isExpiredAt(NOW.plusSeconds(30)));
        assertThrows(IllegalArgumentException.class, () ->
                new ExpiredReservationStore.LeaseToken(
                        "deployment-a", "subject-a", "reservation-a", "worker-a",
                        "token", NOW, NOW.plusSeconds(1), 0));
        assertThrows(IllegalArgumentException.class, () ->
                new ExpiredReservationStore.ClaimRequest(
                        "deployment-a", "worker-a", NOW, Duration.ZERO, 1));
    }

    @Test
    void releaseResultUsesStableStatusesWithoutLeasePayload() {
        UsageReservation reservation = expiredReservation("reservation-a");
        for (ExpiredReservationStore.ReleaseStatus status
                : ExpiredReservationStore.ReleaseStatus.values()) {
            UsageReservation snapshot = status == ExpiredReservationStore.ReleaseStatus.NOT_FOUND
                    ? null : reservation;
            ExpiredReservationStore.ReleaseResult result =
                    new ExpiredReservationStore.ReleaseResult(status, snapshot);
            assertEquals(status, result.status());
            if (status == ExpiredReservationStore.ReleaseStatus.NOT_FOUND) {
                assertNull(result.reservation());
            }
        }
        assertThrows(IllegalArgumentException.class, () ->
                new ExpiredReservationStore.ReleaseResult(
                        ExpiredReservationStore.ReleaseStatus.NOT_FOUND, reservation));
        assertThrows(IllegalArgumentException.class, () ->
                new ExpiredReservationStore.ReleaseResult(
                        ExpiredReservationStore.ReleaseStatus.RELEASED, null));
        assertTrue(Set.of(
                ExpiredReservationStore.ReleaseStatus.RELEASED,
                ExpiredReservationStore.ReleaseStatus.NOT_FOUND,
                ExpiredReservationStore.ReleaseStatus.NOT_OWNER,
                ExpiredReservationStore.ReleaseStatus.LEASE_EXPIRED,
                ExpiredReservationStore.ReleaseStatus.TERMINAL_OR_BOUND).containsAll(
                List.of(ExpiredReservationStore.ReleaseStatus.values())));
    }

    private static ExpiredReservationStore.ClaimedReservation claimed(
            UsageReservation reservation,
            ExpiredReservationStore.ClaimRequest request,
            String token,
            long version) {
        return new ExpiredReservationStore.ClaimedReservation(
                reservation, lease(reservation, request, token, version));
    }

    private static ExpiredReservationStore.LeaseToken lease(
            UsageReservation reservation,
            ExpiredReservationStore.ClaimRequest request,
            String token,
            long version) {
        return new ExpiredReservationStore.LeaseToken(
                reservation.deploymentScope(),
                reservation.subjectScope(),
                reservation.reservationId(),
                request.leaseOwner(),
                token,
                request.now(),
                request.leaseUntil(),
                version);
    }

    private static UsageReservation expiredReservation(String reservationId) {
        return reservation(reservationId, NOW.minusSeconds(60), NOW);
    }

    private static UsageReservation reservation(
            String reservationId, Instant createdAt, Instant expiresAt) {
        MeetingGrant grant = new MeetingGrant(
                "grant-a", "deployment-a", "subject-a", "profile-a", 1,
                Set.of("transcript.realtime"), Set.of("transcript.realtime"),
                60_000, createdAt.minusSeconds(1), expiresAt.plusSeconds(60));
        return UsageReservation.reserved(
                reservationId,
                grant,
                "meeting-expected",
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 1_000),
                createdAt,
                expiresAt);
    }
}
