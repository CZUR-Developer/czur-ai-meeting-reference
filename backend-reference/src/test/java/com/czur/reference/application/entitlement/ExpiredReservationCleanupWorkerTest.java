package com.czur.reference.application.entitlement;

import com.czur.reference.application.port.entitlement.CancelReservationRequest;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.ExpiredReservationStore;
import com.czur.reference.application.port.entitlement.LeaseAwareCancelReservationRequest;
import com.czur.reference.application.port.entitlement.LeaseAwareReservationCanceller;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T14-07 Reservation 清理 Worker 的内存端口门禁。 */
class ExpiredReservationCleanupWorkerTest {
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    void successBuildsStableLeaseAwareCancelAndNeverReleasesLease() {
        UsageReservation reservation = expiredReservation("reservation-success");
        FakeStore store = new FakeStore(claim(reservation, "worker-a", NOW));
        CapturingCanceller canceller = new CapturingCanceller(
                EntitlementProviderResult.success(cancelled(reservation, "cleanup-result")));

        ExpiredReservationCleanupWorker worker = new ExpiredReservationCleanupWorker(
                store, canceller, config(Clock.fixed(NOW, ZoneOffset.UTC)));

        ExpiredReservationCleanupWorker.RunResult result = worker.runOnce();

        assertEquals(1, result.claimedCount());
        assertEquals(ExpiredReservationCleanupWorker.ItemStatus.CANCELLED,
                result.items().get(0).status());
        assertEquals("CANCELLED_OR_REPLAYED", result.items().get(0).reasonCode());
        assertEquals(0, store.releaseCalls.get());
        assertEquals("reservation-cleanup-v1:reservation-success",
                canceller.request.cancel().idempotencyKey());
        assertEquals("meeting-reservation-success",
                canceller.request.cancel().expectedMeetingId());
        assertEquals(0, canceller.request.cancel().expectedVersion());
        assertEquals("EXPIRED_UNBOUND", canceller.request.cancel().reasonCode());
        assertEquals("worker-a", canceller.request.lease().owner());
        assertFalse(canceller.request.cancel().requestDigest().contains("worker-a"));
        assertFalse(canceller.request.cancel().requestDigest().contains("lease-token"));
    }

    @Test
    void retryableFailureReleasesOnlyWhileLeaseIsStillValid() {
        UsageReservation reservation = expiredReservation("reservation-retry");
        FakeStore store = new FakeStore(claim(reservation, "worker-a", NOW));
        CapturingCanceller canceller = new CapturingCanceller(unavailable());
        ExpiredReservationCleanupWorker worker = new ExpiredReservationCleanupWorker(
                store, canceller, config(Clock.fixed(NOW, ZoneOffset.UTC)));

        ExpiredReservationCleanupWorker.RunResult result = worker.runOnce();

        assertEquals(ExpiredReservationCleanupWorker.ItemStatus.RELEASED_FOR_RETRY,
                result.items().get(0).status());
        assertEquals(1, store.releaseCalls.get());
        assertEquals(ExpiredReservationStore.ReleaseStatus.RELEASED,
                store.lastRelease.status());
    }

    @Test
    void retryableFailureAfterLeaseDeadlineDoesNotRelease() {
        UsageReservation reservation = expiredReservation("reservation-expired");
        FakeStore store = new FakeStore(claim(reservation, "worker-a", NOW));
        CapturingCanceller canceller = new CapturingCanceller(unavailable());
        ExpiredReservationCleanupWorker worker = new ExpiredReservationCleanupWorker(
                store, canceller, config(new SequenceClock(NOW, NOW.plusSeconds(30))));

        ExpiredReservationCleanupWorker.RunResult result = worker.runOnce();

        assertEquals(ExpiredReservationCleanupWorker.ItemStatus.LEASE_EXPIRED,
                result.items().get(0).status());
        assertEquals(0, store.releaseCalls.get());
    }

    @Test
    void leaseFenceAndBusinessFailuresHaveDifferentStableCategories() {
        UsageReservation fenceReservation = expiredReservation("reservation-fence");
        FakeStore fenceStore = new FakeStore(claim(fenceReservation, "worker-a", NOW));
        ExpiredReservationCleanupWorker fenceWorker = new ExpiredReservationCleanupWorker(
                fenceStore, new CapturingCanceller(failure(
                        EntitlementProviderError.Code.RESERVATION_STATE_CONFLICT, false)),
                config(Clock.fixed(NOW, ZoneOffset.UTC)));
        assertEquals(ExpiredReservationCleanupWorker.ItemStatus.LEASE_LOST,
                fenceWorker.runOnce().items().get(0).status());
        assertEquals(0, fenceStore.releaseCalls.get());

        UsageReservation businessReservation = expiredReservation("reservation-business");
        FakeStore businessStore = new FakeStore(claim(businessReservation, "worker-a", NOW));
        ExpiredReservationCleanupWorker businessWorker = new ExpiredReservationCleanupWorker(
                businessStore, new CapturingCanceller(failure(
                        EntitlementProviderError.Code.ENTITLEMENT_DENIED, false)),
                config(Clock.fixed(NOW, ZoneOffset.UTC)));
        ExpiredReservationCleanupWorker.ItemResult business =
                businessWorker.runOnce().items().get(0);
        assertEquals(ExpiredReservationCleanupWorker.ItemStatus.BUSINESS_REJECTED,
                business.status());
        assertEquals("PROVIDER_ENTITLEMENT_DENIED", business.reasonCode());
        assertEquals(0, businessStore.releaseCalls.get());
    }

    @Test
    void claimAndReleasePersistenceExceptionsAreNotSwallowed() {
        RuntimeException claimFailure = new IllegalStateException("database unavailable");
        ExpiredReservationStore failingClaimStore = new ExpiredReservationStore() {
            @Override
            public ClaimBatch claimExpired(ClaimRequest request) {
                throw claimFailure;
            }

            @Override
            public ReleaseResult release(LeaseToken lease, Instant releasedAt) {
                throw new AssertionError("release must not be called");
            }
        };
        ExpiredReservationCleanupWorker claimWorker = new ExpiredReservationCleanupWorker(
                failingClaimStore, request -> unavailable(), config(Clock.fixed(NOW, ZoneOffset.UTC)));
        assertSame(claimFailure, assertThrows(RuntimeException.class, claimWorker::runOnce));

        UsageReservation reservation = expiredReservation("reservation-release-failure");
        FakeStore releaseStore = new FakeStore(claim(reservation, "worker-a", NOW));
        RuntimeException releaseFailure = new IllegalStateException("database unavailable");
        releaseStore.releaseFailure = releaseFailure;
        ExpiredReservationCleanupWorker releaseWorker = new ExpiredReservationCleanupWorker(
                releaseStore, request -> unavailable(), config(Clock.fixed(NOW, ZoneOffset.UTC)));
        assertSame(releaseFailure, assertThrows(RuntimeException.class, releaseWorker::runOnce));
    }

    @Test
    void runOnceDoesNotStartBackgroundThread() {
        UsageReservation reservation = expiredReservation("reservation-sync");
        FakeStore store = new FakeStore(claim(reservation, "worker-a", NOW));
        AtomicInteger calls = new AtomicInteger();
        ExpiredReservationCleanupWorker worker = new ExpiredReservationCleanupWorker(
                store, request -> {
                    calls.incrementAndGet();
                    return EntitlementProviderResult.success(cancelled(reservation, "sync-result"));
                }, config(Clock.fixed(NOW, ZoneOffset.UTC)));

        worker.runOnce();

        assertEquals(1, calls.get());
        assertEquals(1, store.claimCalls.get());
        assertEquals(0, store.releaseCalls.get());
    }

    private static ExpiredReservationCleanupWorker.Config config(Clock clock) {
        return new ExpiredReservationCleanupWorker.Config(
                "deployment-a", "worker-a", clock, Duration.ofSeconds(30), 5,
                "reservation-cleanup-v1:", "EXPIRED_UNBOUND");
    }

    private static ExpiredReservationStore.ClaimedReservation claim(
            UsageReservation reservation, String owner, Instant now) {
        ExpiredReservationStore.ClaimRequest request = new ExpiredReservationStore.ClaimRequest(
                reservation.deploymentScope(), owner, now, Duration.ofSeconds(30), 5);
        return new ExpiredReservationStore.ClaimedReservation(
                reservation,
                new ExpiredReservationStore.LeaseToken(
                        reservation.deploymentScope(), reservation.subjectScope(),
                        reservation.reservationId(), owner, "lease-token-" + reservation.reservationId(),
                        request.now(), request.leaseUntil(), 1));
    }

    private static UsageReservation expiredReservation(String reservationId) {
        Instant createdAt = NOW.minusSeconds(60);
        MeetingGrant grant = new MeetingGrant(
                "grant-" + reservationId, "deployment-a", "subject-a", "profile-a", 1,
                Set.of("transcript.realtime"), Set.of("transcript.realtime"),
                60_000, createdAt.minusSeconds(1), NOW.plusSeconds(60));
        return UsageReservation.reserved(
                reservationId, grant, "meeting-success".replace("success", reservationId),
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 1_000), createdAt, NOW);
    }

    private static UsageOperationResult cancelled(UsageReservation reservation, String key) {
        return UsageOperationResult.terminal(
                reservation.deploymentScope(), reservation.subjectScope(),
                "usage.cancel.v1", key, "digest", reservation.cancel(
                        "EXPIRED_UNBOUND", NOW, reservation.version()));
    }

    private static EntitlementProviderResult<UsageOperationResult> unavailable() {
        return failure(EntitlementProviderError.Code.PROVIDER_UNAVAILABLE, true);
    }

    private static EntitlementProviderResult<UsageOperationResult> failure(
            EntitlementProviderError.Code code, boolean retryable) {
        return EntitlementProviderResult.failure(new EntitlementProviderError(code, retryable));
    }

    private static final class CapturingCanceller implements LeaseAwareReservationCanceller {
        private final EntitlementProviderResult<UsageOperationResult> result;
        private LeaseAwareCancelReservationRequest request;

        private CapturingCanceller(EntitlementProviderResult<UsageOperationResult> result) {
            this.result = result;
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> cancelReservationWithLease(
                LeaseAwareCancelReservationRequest request) {
            this.request = request;
            return result;
        }
    }

    private static final class FakeStore implements ExpiredReservationStore {
        private final ClaimBatch batch;
        private final AtomicInteger claimCalls = new AtomicInteger();
        private final AtomicInteger releaseCalls = new AtomicInteger();
        private ReleaseResult lastRelease;
        private RuntimeException releaseFailure;

        private FakeStore(ClaimedReservation claim) {
            ClaimRequest request = new ClaimRequest(
                    claim.lease().deploymentScope(), claim.lease().owner(),
                    claim.lease().claimedAt(), Duration.between(
                            claim.lease().claimedAt(), claim.lease().expiresAt()), 5);
            this.batch = new ClaimBatch(request, List.of(claim));
        }

        @Override
        public ClaimBatch claimExpired(ClaimRequest request) {
            claimCalls.incrementAndGet();
            return batch;
        }

        @Override
        public ReleaseResult release(LeaseToken lease, Instant releasedAt) {
            releaseCalls.incrementAndGet();
            if (releaseFailure != null) {
                throw releaseFailure;
            }
            lastRelease = new ReleaseResult(ReleaseStatus.RELEASED, batch.claims().get(0).reservation());
            return lastRelease;
        }
    }

    private static final class SequenceClock extends Clock {
        private final Instant[] values;
        private int index;

        private SequenceClock(Instant... values) {
            this.values = values;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return values[Math.min(index++, values.length - 1)];
        }
    }
}
