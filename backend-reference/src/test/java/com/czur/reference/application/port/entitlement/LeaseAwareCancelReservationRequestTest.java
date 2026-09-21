package com.czur.reference.application.port.entitlement;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Lease-aware Reservation 清理请求的范围栅栏和空值契约测试。 */
class LeaseAwareCancelReservationRequestTest {
    private static final Instant CLAIMED_AT = Instant.parse("2026-09-21T10:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-21T10:00:30Z");

    @Test
    void requestAcceptsMatchingScopeAndReservation() {
        CancelReservationRequest cancel = cancel("deployment-a", "subject-a", "reservation-a");
        ExpiredReservationStore.LeaseToken lease = lease(
                "deployment-a", "subject-a", "reservation-a");

        assertDoesNotThrow(() -> new LeaseAwareCancelReservationRequest(cancel, lease));
    }

    @Test
    void requestRejectsDeploymentSubjectOrReservationMismatch() {
        CancelReservationRequest cancel = cancel("deployment-a", "subject-a", "reservation-a");
        assertThrows(IllegalArgumentException.class, () ->
                new LeaseAwareCancelReservationRequest(
                        cancel, lease("deployment-b", "subject-a", "reservation-a")));
        assertThrows(IllegalArgumentException.class, () ->
                new LeaseAwareCancelReservationRequest(
                        cancel, lease("deployment-a", "subject-b", "reservation-a")));
        assertThrows(IllegalArgumentException.class, () ->
                new LeaseAwareCancelReservationRequest(
                        cancel, lease("deployment-a", "subject-a", "reservation-b")));
    }

    @Test
    void requestRejectsNullBusinessRequestOrLease() {
        CancelReservationRequest cancel = cancel("deployment-a", "subject-a", "reservation-a");
        ExpiredReservationStore.LeaseToken lease =
                lease("deployment-a", "subject-a", "reservation-a");
        assertThrows(NullPointerException.class, () ->
                new LeaseAwareCancelReservationRequest(null, lease));
        assertThrows(NullPointerException.class, () ->
                new LeaseAwareCancelReservationRequest(cancel, null));
    }

    private static CancelReservationRequest cancel(
            String deploymentScope, String subjectScope, String reservationId) {
        return new CancelReservationRequest(
                deploymentScope, subjectScope, UsageOperationScopes.CANCEL,
                "cleanup-key", "cleanup-digest", reservationId,
                "meeting-a", 0, "EXPIRED_UNBOUND");
    }

    private static ExpiredReservationStore.LeaseToken lease(
            String deploymentScope, String subjectScope, String reservationId) {
        return new ExpiredReservationStore.LeaseToken(
                deploymentScope, subjectScope, reservationId, "cleanup-worker",
                "opaque-lease-token", CLAIMED_AT, EXPIRES_AT, 1);
    }
}
