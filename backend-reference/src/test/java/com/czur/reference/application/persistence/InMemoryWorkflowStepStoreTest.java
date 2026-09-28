package com.czur.reference.application.persistence;

import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证内存工作流 Store 的 checkpoint 幂等、租约栅栏、重试与并发语义。 */
class InMemoryWorkflowStepStoreTest {
    private static final Instant T0 = Instant.parse("2026-09-22T08:00:00Z");
    private static final String MEETING = "meeting-workflow-sensitive";
    private static final String PAYLOAD = "payload-workflow-sensitive";
    private static final String DIGEST = "digest-workflow-sensitive";

    @Test
    void registerAppliesReplaysSameCheckpointAndConflictsWithoutOverwrite() {
        InMemoryWorkflowStepStore store = store();
        WorkflowStep first = pending("summary", "main", PAYLOAD, DIGEST, T0);
        WorkflowStep sameDefinition = WorkflowStep.pending(
                first.key(), "checkpoint-json", PAYLOAD, DIGEST, T0, T0.plusSeconds(1));
        WorkflowStep differentSchedule = WorkflowStep.pending(
                first.key(), "checkpoint-json", PAYLOAD, DIGEST,
                T0.plusSeconds(10), T0.plusSeconds(1));
        WorkflowStep differentCheckpointType = WorkflowStep.pending(
                first.key(), "checkpoint-binary", PAYLOAD, DIGEST, T0, T0.plusSeconds(1));
        WorkflowStep conflict = pending("summary", "main", "other-payload", DIGEST, T0);

        assertEquals(WorkflowStepStore.RegisterStatus.APPLIED,
                store.register(first).status());
        assertEquals(WorkflowStepStore.RegisterStatus.REPLAYED,
                store.register(sameDefinition).status());
        assertEquals(WorkflowStepStore.RegisterStatus.CONFLICT,
                store.register(differentSchedule).status());
        assertEquals(WorkflowStepStore.RegisterStatus.CONFLICT,
                store.register(differentCheckpointType).status());
        WorkflowStepStore.RegisterResult rejected = store.register(conflict);
        assertEquals(WorkflowStepStore.RegisterStatus.CONFLICT, rejected.status());
        assertEquals(first, rejected.step());
        assertEquals(first, store.find(first.key()).orElseThrow());
    }

    @Test
    void claimAppliesClosedWhitelistReadinessDeterministicOrderAndLimit() {
        InMemoryWorkflowStepStore store = store();
        store.register(pending("summary", "b", PAYLOAD, DIGEST, T0));
        store.register(pending("summary", "a", PAYLOAD, DIGEST, T0));
        store.register(pending("translate", "a", PAYLOAD, DIGEST, T0.minusSeconds(1)));
        store.register(pending("summary", "future", PAYLOAD, DIGEST, T0.plusSeconds(1)));

        assertTrue(store.claim(claim(Set.of(), "empty", T0, 10)).isEmpty());
        List<WorkflowStepStore.ClaimedStep> claimed = store.claim(
                claim(Set.of("summary"), "worker-a", T0, 2));

        assertEquals(List.of("a", "b"), claimed.stream()
                .map(value -> value.step().key().stepKey()).toList());
        assertTrue(claimed.stream().allMatch(value -> value.step().nextAttemptAt() == null));
        assertEquals(WorkflowStep.Status.PENDING,
                store.find(key("translate", "a")).orElseThrow().status());
        assertEquals(WorkflowStep.Status.PENDING,
                store.find(key("summary", "future")).orElseThrow().status());
    }

    @Test
    void expiredLeaseCanBeReclaimedAndOldOwnerIsFencedBeforeAndAfterCompletion() {
        InMemoryWorkflowStepStore store = store();
        WorkflowStep step = pending("summary", "fence", PAYLOAD, DIGEST, T0);
        store.register(step);
        WorkflowStepStore.LeaseToken ownerA = store.claim(
                claim(Set.of("summary"), "owner-a", T0, 1)).getFirst().lease();
        WorkflowStepStore.LeaseToken ownerB = store.claim(new WorkflowStepStore.ClaimRequest(
                Set.of("summary"), "owner-b", T0.plusSeconds(10),
                Duration.ofSeconds(10), 1)).getFirst().lease();

        assertEquals(2, ownerB.leaseVersion());
        assertNotEquals(ownerA.token(), ownerB.token());
        assertEquals(WorkflowStepStore.CompleteStatus.NOT_OWNER,
                store.complete(ownerA, T0.plusSeconds(9)).status());
        assertEquals(WorkflowStepStore.CompleteStatus.COMPLETED,
                store.complete(ownerB, T0.plusSeconds(11)).status());
        assertEquals(null, store.find(step.key()).orElseThrow().nextAttemptAt());
        assertEquals(WorkflowStepStore.CompleteStatus.ALREADY_COMPLETED,
                store.complete(ownerA, T0.plusSeconds(9)).status());
        assertEquals(WorkflowStepStore.FailStatus.ALREADY_COMPLETED,
                store.fail(ownerA, T0.plusSeconds(9), T0.plusSeconds(20),
                        "LATE", "late owner", 3).status());
    }

    @Test
    void renewRotatesTokenAndVersionAndRejectsOldExpiredAndNonExtendingLease() {
        InMemoryWorkflowStepStore store = store();
        store.register(pending("summary", "renew", PAYLOAD, DIGEST, T0));
        WorkflowStepStore.LeaseToken original = store.claim(
                claim(Set.of("summary"), "owner", T0, 1)).getFirst().lease();

        WorkflowStepStore.RenewResult renewed = store.renew(
                original, T0.plusSeconds(5), Duration.ofSeconds(10));
        assertEquals(WorkflowStepStore.RenewStatus.RENEWED, renewed.status());
        assertEquals(2, renewed.lease().leaseVersion());
        assertNotEquals(original.token(), renewed.lease().token());
        assertEquals(WorkflowStepStore.RenewStatus.NOT_OWNER,
                store.renew(original, T0.plusSeconds(6), Duration.ofSeconds(10)).status());
        assertEquals(WorkflowStepStore.RenewStatus.LEASE_EXPIRED,
                store.renew(renewed.lease(), renewed.lease().expiresAt(),
                        Duration.ofSeconds(10)).status());

        InMemoryWorkflowStepStore other = store();
        other.register(pending("summary", "short", PAYLOAD, DIGEST, T0));
        WorkflowStepStore.LeaseToken lease = other.claim(
                claim(Set.of("summary"), "owner", T0, 1)).getFirst().lease();
        assertThrows(IllegalArgumentException.class,
                () -> other.renew(lease, T0.plusSeconds(5), Duration.ofSeconds(4)));
    }

    @Test
    void nonExtendingRenewIsRejectedBeforeActiveMissingOrTerminalStateLookup() {
        InMemoryWorkflowStepStore store = store();
        WorkflowStep step = pending("summary", "renew-parameter", PAYLOAD, DIGEST, T0);
        store.register(step);
        WorkflowStepStore.LeaseToken active = store.claim(
                claim(Set.of("summary"), "owner", T0, 1)).getFirst().lease();
        WorkflowStep activeBefore = store.find(step.key()).orElseThrow();

        IllegalArgumentException activeFailure = assertThrows(
                IllegalArgumentException.class,
                () -> store.renew(
                        active, T0.plusSeconds(5), Duration.ofSeconds(5)));
        assertEquals("renew must extend lease expiry", activeFailure.getMessage());
        assertEquals(activeBefore, store.find(step.key()).orElseThrow());

        WorkflowStepStore.LeaseToken missing = new WorkflowStepStore.LeaseToken(
                key("summary", "missing-renew-parameter"), "owner", "missing-token",
                T0.plusSeconds(10), T0, 1);
        assertThrows(IllegalArgumentException.class,
                () -> store.renew(
                        missing, T0.plusSeconds(5), Duration.ofSeconds(5)));
        assertTrue(store.find(missing.key()).isEmpty());

        assertEquals(WorkflowStepStore.CompleteStatus.COMPLETED,
                store.complete(active, T0.plusSeconds(1)).status());
        WorkflowStep completedBefore = store.find(step.key()).orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> store.renew(
                        active, T0.plusSeconds(5), Duration.ofSeconds(5)));
        assertEquals(completedBefore, store.find(step.key()).orElseThrow());
    }

    @Test
    void failSchedulesRetryThenReachesDeadAndNeverClaimsAgain() {
        InMemoryWorkflowStepStore store = store();
        store.register(pending("summary", "retry", PAYLOAD, DIGEST, T0));
        WorkflowStepStore.LeaseToken first = store.claim(
                claim(Set.of("summary"), "worker", T0, 1)).getFirst().lease();

        WorkflowStepStore.FailResult retry = store.fail(
                first, T0.plusSeconds(1), T0.plusSeconds(20),
                "TEMPORARY", "retry later", 2);
        assertEquals(WorkflowStepStore.FailStatus.RETRY_SCHEDULED, retry.status());
        assertEquals(1, retry.step().attempt());
        assertTrue(store.claim(claim(Set.of("summary"), "worker", T0.plusSeconds(19), 1))
                .isEmpty());

        WorkflowStepStore.LeaseToken second = store.claim(
                claim(Set.of("summary"), "worker", T0.plusSeconds(20), 1))
                .getFirst().lease();
        WorkflowStepStore.FailResult dead = store.fail(
                second, T0.plusSeconds(21), T0.plusSeconds(30),
                "PERMANENT", "stopped", 2);
        assertEquals(WorkflowStepStore.FailStatus.DEAD, dead.status());
        assertEquals(2, dead.step().attempt());
        assertEquals(null, dead.step().nextAttemptAt());
        assertEquals(WorkflowStepStore.FailStatus.ALREADY_DEAD,
                store.fail(second, T0.plusSeconds(21), T0.plusSeconds(30),
                        "AGAIN", "again", 2).status());
        assertTrue(store.claim(claim(Set.of("summary"), "other", T0.plusSeconds(40), 1))
                .isEmpty());
    }

    @Test
    void failRejectsRetryBeforeFailureBeforeStatusAdjudicationAndKeepsState() {
        InMemoryWorkflowStepStore store = store();
        WorkflowStep step = pending("summary", "retry-order", PAYLOAD, DIGEST, T0);
        store.register(step);
        WorkflowStepStore.LeaseToken lease = store.claim(
                claim(Set.of("summary"), "worker", T0, 1)).getFirst().lease();
        WorkflowStep before = store.find(step.key()).orElseThrow();

        IllegalArgumentException leasedFailure = assertThrows(
                IllegalArgumentException.class,
                () -> store.fail(
                        lease, T0.plusSeconds(2), T0.plusSeconds(1),
                        "TEMPORARY", "retry in the past", 2));
        assertEquals("nextAttemptAt must not be before failedAt",
                leasedFailure.getMessage());
        assertEquals(before, store.find(step.key()).orElseThrow());

        WorkflowStepStore.LeaseToken missing = new WorkflowStepStore.LeaseToken(
                key("summary", "missing-retry-order"), "worker", "missing-token",
                T0.plusSeconds(10), T0, 1);
        assertThrows(IllegalArgumentException.class,
                () -> store.fail(
                        missing, T0.plusSeconds(2), T0.plusSeconds(1),
                        "TEMPORARY", "retry in the past", 2));
        assertTrue(store.find(missing.key()).isEmpty());
    }

    @Test
    void completeAndFailRequireFullFenceAndStrictlyUnexpiredTime() {
        InMemoryWorkflowStepStore store = store();
        store.register(pending("summary", "boundary", PAYLOAD, DIGEST, T0));
        WorkflowStepStore.LeaseToken lease = store.claim(
                claim(Set.of("summary"), "owner", T0, 1)).getFirst().lease();
        WorkflowStepStore.LeaseToken wrongOwner = new WorkflowStepStore.LeaseToken(
                lease.key(), "other", lease.token(), lease.expiresAt(),
                lease.claimedAt(), lease.leaseVersion());

        assertEquals(WorkflowStepStore.CompleteStatus.NOT_OWNER,
                store.complete(wrongOwner, T0.plusSeconds(1)).status());
        assertEquals(WorkflowStepStore.FailStatus.NOT_OWNER,
                store.fail(wrongOwner, T0.plusSeconds(1), T0.plusSeconds(20),
                        "ERROR", "safe", 2).status());
        assertEquals(WorkflowStepStore.CompleteStatus.LEASE_EXPIRED,
                store.complete(lease, lease.expiresAt()).status());
        assertEquals(WorkflowStepStore.FailStatus.LEASE_EXPIRED,
                store.fail(lease, lease.expiresAt(), T0.plusSeconds(20),
                        "ERROR", "safe", 2).status());

        WorkflowStepStore.LeaseToken missing = new WorkflowStepStore.LeaseToken(
                key("summary", "missing"), "owner", "token-missing",
                T0.plusSeconds(10), T0, 1);
        assertEquals(WorkflowStepStore.CompleteStatus.NOT_FOUND,
                store.complete(missing, T0.plusSeconds(1)).status());
    }

    @Test
    void forgedClaimTimestampAndOperationBeforeClaimAreRejectedByFence() {
        InMemoryWorkflowStepStore store = store();
        store.register(pending("summary", "timestamp-fence", PAYLOAD, DIGEST, T0));
        WorkflowStepStore.LeaseToken actual = store.claim(
                claim(Set.of("summary"), "owner", T0, 1)).getFirst().lease();
        WorkflowStepStore.LeaseToken forged = new WorkflowStepStore.LeaseToken(
                actual.key(), actual.owner(), actual.token(), actual.expiresAt(),
                actual.claimedAt().minusSeconds(1), actual.leaseVersion());

        // 即使 key、owner、token、版本和过期时间都正确，claimedAt 仍是租约快照的一部分。
        // 不能接受调用方伪造的更早时间，也不能让操作时间回到真正领取之前。
        assertEquals(WorkflowStepStore.RenewStatus.NOT_OWNER,
                store.renew(forged, T0.minusSeconds(1), Duration.ofSeconds(12)).status());
        assertEquals(WorkflowStepStore.CompleteStatus.NOT_OWNER,
                store.complete(forged, T0.minusSeconds(1)).status());
        assertEquals(WorkflowStepStore.FailStatus.NOT_OWNER,
                store.fail(forged, T0.minusSeconds(1), T0.plusSeconds(20),
                        "TEMPORARY", "before claim", 2).status());
        assertEquals(WorkflowStep.Status.LEASED,
                store.find(actual.key()).orElseThrow().status());
    }

    @Test
    void concurrentClaimHasExactlyOneWinner() throws Exception {
        InMemoryWorkflowStepStore store = store();
        store.register(pending("summary", "concurrent", PAYLOAD, DIGEST, T0));
        int workers = 16;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<List<WorkflowStepStore.ClaimedStep>>> futures = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                int worker = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return store.claim(claim(
                            Set.of("summary"), "worker-" + worker, T0, 1));
                }));
            }
            ready.await();
            start.countDown();
            int winners = 0;
            for (Future<List<WorkflowStepStore.ClaimedStep>> future : futures) {
                winners += future.get().size();
            }
            assertEquals(1, winners);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void batchClaimDoesNotPartiallyApplyWhenNthTokenGenerationFails() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryWorkflowStepStore store = new InMemoryWorkflowStepStore(() -> {
            int call = calls.incrementAndGet();
            if (call == 2) {
                throw new IllegalStateException("injected-token-failure");
            }
            return "token-" + call;
        });
        WorkflowStep first = pending("summary", "a", PAYLOAD, DIGEST, T0);
        WorkflowStep second = pending("summary", "b", PAYLOAD, DIGEST, T0);
        store.register(first);
        store.register(second);

        assertThrows(IllegalStateException.class,
                () -> store.claim(claim(Set.of("summary"), "worker", T0, 2)));
        assertEquals(first, store.find(first.key()).orElseThrow());
        assertEquals(second, store.find(second.key()).orElseThrow());
        assertEquals(WorkflowStep.Status.PENDING,
                store.find(first.key()).orElseThrow().status());
        assertEquals(0, store.find(first.key()).orElseThrow().leaseVersion());
    }

    @Test
    void logsAndValueDiagnosticsDoNotExposeCheckpointMeetingOrLeaseSecrets() {
        Logger logger = Logger.getLogger(InMemoryWorkflowStepStore.class.getName());
        StringBuilder output = new StringBuilder();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                output.append(record.getMessage())
                        .append(Arrays.toString(record.getParameters()));
            }

            @Override public void flush() { }
            @Override public void close() { }
        };
        handler.setLevel(Level.ALL);
        Level previous = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            InMemoryWorkflowStepStore store = store();
            WorkflowStep step = pending("summary", "secret", PAYLOAD, DIGEST, T0);
            store.register(step);
            WorkflowStepStore.ClaimedStep claimed = store.claim(
                    claim(Set.of("summary"), "owner-sensitive", T0, 1)).getFirst();
            store.complete(claimed.lease(), T0.plusSeconds(1));
            String diagnostics = output + " " + step + " " + claimed;
            for (String secret : new String[]{
                    MEETING, PAYLOAD, DIGEST, "owner-sensitive", claimed.lease().token()}) {
                assertFalse(diagnostics.contains(secret));
            }
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }

    private static InMemoryWorkflowStepStore store() {
        AtomicInteger tokens = new AtomicInteger();
        return new InMemoryWorkflowStepStore(() -> "opaque-token-" + tokens.incrementAndGet());
    }

    private static WorkflowStep pending(
            String type,
            String stepKey,
            String payload,
            String digest,
            Instant nextAt) {
        return WorkflowStep.pending(
                key(type, stepKey), "checkpoint-json", payload, digest, nextAt, T0);
    }

    private static WorkflowStep.Key key(String type, String stepKey) {
        return new WorkflowStep.Key(MEETING, type, stepKey);
    }

    private static WorkflowStepStore.ClaimRequest claim(
            Set<String> types,
            String owner,
            Instant now,
            int limit) {
        return new WorkflowStepStore.ClaimRequest(
                types, owner, now, Duration.ofSeconds(10), limit);
    }
}
