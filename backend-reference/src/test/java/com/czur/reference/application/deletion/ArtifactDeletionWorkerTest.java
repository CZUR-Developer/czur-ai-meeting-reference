package com.czur.reference.application.deletion;

import com.czur.reference.application.persistence.InMemoryArtifactDeletionPlanStore;
import com.czur.reference.application.persistence.InMemoryWorkflowStepStore;
import com.czur.reference.application.port.ArtifactDeletionItemUnitOfWork;
import com.czur.reference.application.port.ArtifactDeletionTerminalUnitOfWork;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.protocol.json.ArtifactDeletionPlanJson;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure Java orchestration checks; database and file-root gates live in integration tests. */
class ArtifactDeletionWorkerTest {
    private static final String MEETING = "mtg_artifact_worker";
    private static final Instant T0 = Instant.parse("2026-09-29T08:00:00.000000Z");
    private static final String CHECKSUM = "sha256:" + "a".repeat(64);

    @Test
    void successfulBatchCompletesAllItemsAndNextRunIsIdle() {
        Fixture fixture = new Fixture(2, 3);
        fixture.register(2);

        ArtifactDeletionWorker.RunResult result = fixture.worker().runOnce();
        assertEquals(1, result.claimedCount());
        assertEquals(ArtifactDeletionWorker.ItemStatus.COMPLETED, result.items().get(0).status());
        assertEquals(List.of(0, 1), fixture.items.ordinals);
        assertEquals(1, fixture.terminal.calls);
        assertTrue(fixture.worker().runOnce().idle());
    }

    @Test
    void retryThenDeadUsesWorkflowAttemptAndDoesNotCallTerminal() {
        Fixture fixture = new Fixture(1, 1);
        fixture.register(1);
        fixture.items.failure = new ArtifactDeletionItemUnitOfWork.Failure(
                ArtifactDeletionItemUnitOfWork.ErrorCode.STORE_FAILURE, true);

        ArtifactDeletionWorker.RunResult result = fixture.worker().runOnce();
        assertEquals(ArtifactDeletionWorker.ItemStatus.DEAD, result.items().get(0).status());
        assertEquals(0, fixture.terminal.calls);
        assertEquals(WorkflowStep.Status.DEAD,
                fixture.workflow.find(ArtifactDeletionPlan.workflowKey(MEETING)).orElseThrow().status());
    }

    @Test
    void retryableFailureLeavesPendingForASecondBatch() {
        Fixture fixture = new Fixture(1, 3);
        fixture.register(1);
        fixture.items.failure = new ArtifactDeletionItemUnitOfWork.Failure(
                ArtifactDeletionItemUnitOfWork.ErrorCode.STORE_FAILURE, true);

        ArtifactDeletionWorker.RunResult result = fixture.worker().runOnce();
        assertEquals(ArtifactDeletionWorker.ItemStatus.RETRY_SCHEDULED, result.items().get(0).status());
        assertEquals(WorkflowStep.Status.PENDING,
                fixture.workflow.find(ArtifactDeletionPlan.workflowKey(MEETING)).orElseThrow().status());
        assertTrue(fixture.worker().runOnce().idle());
    }

    @Test
    void renewRotatesFenceAndWorkerUsesTheNewLeaseForItemAndTerminal() {
        Fixture fixture = new Fixture(1, 3);
        fixture.register(1);
        fixture.leaseDuration = Duration.ofSeconds(10);
        fixture.clock = new SequenceClock(T0, T0.plusSeconds(6), T0.plusSeconds(6),
                T0.plusSeconds(7), T0.plusSeconds(7));
        ArtifactDeletionWorker.RunResult result = fixture.worker().runOnce();
        assertEquals(ArtifactDeletionWorker.ItemStatus.COMPLETED, result.items().get(0).status());
        assertEquals(2, fixture.items.leases.get(0).leaseVersion());
        assertEquals(2, fixture.terminal.lease.leaseVersion());
        assertEquals(fixture.items.leases.get(0).token(), fixture.terminal.lease.token());
    }

    @Test
    void leaseFenceLossStopsBeforeTheNextItemOrTerminalWrite() {
        Fixture fixture = new Fixture(2, 3);
        fixture.register(2);
        fixture.items.failure = new ArtifactDeletionItemUnitOfWork.Failure(
                ArtifactDeletionItemUnitOfWork.ErrorCode.NOT_OWNER, false);
        ArtifactDeletionWorker.RunResult result = fixture.worker().runOnce();
        assertEquals(ArtifactDeletionWorker.ItemStatus.LEASE_LOST, result.items().get(0).status());
        assertEquals(List.of(0), fixture.items.ordinals);
        assertEquals(0, fixture.terminal.calls);
    }

    @Test
    void terminalFailureIsRetriedAndCanReachDead() {
        Fixture fixture = new Fixture(0, 1);
        fixture.register(0);
        fixture.terminal.failure = new ArtifactDeletionTerminalUnitOfWork.Failure(
                ArtifactDeletionTerminalUnitOfWork.ErrorCode.STORAGE_FAILURE, true);
        ArtifactDeletionWorker.RunResult result = fixture.worker().runOnce();
        assertEquals(ArtifactDeletionWorker.ItemStatus.DEAD, result.items().get(0).status());
        assertEquals(1, fixture.terminal.calls);
    }

    @Test
    void itemOutcomeUnknownIsReplayedOnTheNextLease() {
        Fixture fixture = new Fixture(1, 3);
        fixture.register(1);
        fixture.items.scripted.add(new ArtifactDeletionItemUnitOfWork.Failure(
                ArtifactDeletionItemUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, true));
        fixture.items.scripted.add(new ArtifactDeletionItemUnitOfWork.Success(
                ArtifactDeletionItemUnitOfWork.SuccessCode.REPLAYED,
                new StorageBinding("binding-replayed")));
        fixture.clock = new SequenceClock(T0, T0, T0, T0, T0, T0.plusSeconds(12),
                T0.plusSeconds(12), T0.plusSeconds(12), T0.plusSeconds(13), T0.plusSeconds(13));
        assertEquals(ArtifactDeletionWorker.ItemStatus.RETRY_SCHEDULED,
                fixture.worker().runOnce().items().get(0).status());
        assertEquals(ArtifactDeletionWorker.ItemStatus.COMPLETED,
                fixture.worker().runOnce().items().get(0).status());
    }

    @Test
    void terminalOutcomeUnknownRecoversBothSuccessAndUnresolvedFailure() {
        Fixture success = new Fixture(0, 3);
        success.register(0);
        success.terminal.failure = new ArtifactDeletionTerminalUnitOfWork.Failure(
                ArtifactDeletionTerminalUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, true);
        success.terminal.recoverSuccess = true;
        assertEquals(ArtifactDeletionWorker.ItemStatus.COMPLETED,
                success.worker().runOnce().items().get(0).status());

        Fixture unresolved = new Fixture(0, 3);
        unresolved.register(0);
        unresolved.terminal.failure = new ArtifactDeletionTerminalUnitOfWork.Failure(
                ArtifactDeletionTerminalUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, true);
        assertEquals(ArtifactDeletionWorker.ItemStatus.RETRY_SCHEDULED,
                unresolved.worker().runOnce().items().get(0).status());
    }

    @Test
    void malformedCheckpointStopsClosedWithoutCallingStores() {
        InMemoryWorkflowStepStore workflow = new InMemoryWorkflowStepStore();
        InMemoryArtifactDeletionPlanStore plans = new InMemoryArtifactDeletionPlanStore();
        WorkflowStep.Key key = ArtifactDeletionPlan.workflowKey(MEETING);
        workflow.register(WorkflowStep.pending(key, ArtifactDeletionPlan.CHECKPOINT_TYPE,
                "{}", "sha256:" + "b".repeat(64), T0.minusSeconds(1), T0.minusSeconds(1)));
        FakeTerminal terminal = new FakeTerminal(workflow);
        ArtifactDeletionWorker worker = new ArtifactDeletionWorker(workflow, plans,
                new FakeItem(), terminal, new ArtifactDeletionWorker.Config(
                        "worker-test", Clock.fixed(T0, ZoneOffset.UTC), Duration.ofSeconds(30),
                        Duration.ofSeconds(5), Duration.ofSeconds(5), 10, 3));
        ArtifactDeletionWorker.ItemResult result = worker.runOnce().items().get(0);
        assertEquals(ArtifactDeletionWorker.ItemStatus.INCONSISTENT, result.status());
        assertEquals(0, terminal.calls);
        assertTrue(result.toString().contains("key=redacted"));
        assertTrue(!result.toString().contains(MEETING));
    }

    @Test
    void workflowStoreRenewReportsAllTerminalAndFenceStatuses() {
        InMemoryWorkflowStepStore store = new InMemoryWorkflowStepStore();
        ArtifactDeletionPlan plan = plan(0);
        WorkflowStep.Key key = plan.workflowKey();
        store.register(ArtifactDeletionPlanJson.workflowStep(plan));
        WorkflowStepStore.LeaseToken lease = store.claim(new WorkflowStepStore.ClaimRequest(
                Set.of(ArtifactDeletionPlan.STEP_TYPE), "owner", T0,
                Duration.ofSeconds(10), 1)).getFirst().lease();
        WorkflowStepStore.LeaseToken forged = new WorkflowStepStore.LeaseToken(
                key, "other", lease.token(), lease.expiresAt(), lease.claimedAt(), lease.leaseVersion());
        assertEquals(WorkflowStepStore.RenewStatus.NOT_OWNER,
                store.renew(forged, T0.plusSeconds(1), Duration.ofSeconds(10)).status());
        assertEquals(WorkflowStepStore.RenewStatus.LEASE_EXPIRED,
                store.renew(lease, T0.plusSeconds(10), Duration.ofSeconds(10)).status());
        WorkflowStepStore.LeaseToken completedLease = store.claim(new WorkflowStepStore.ClaimRequest(
                Set.of(ArtifactDeletionPlan.STEP_TYPE), "owner", T0.plusSeconds(11),
                Duration.ofSeconds(10), 1)).getFirst().lease();
        store.complete(completedLease, T0.plusSeconds(12));
        assertEquals(WorkflowStepStore.RenewStatus.ALREADY_COMPLETED,
                store.renew(completedLease, T0.plusSeconds(12), Duration.ofSeconds(10)).status());

        ArtifactDeletionPlan deadPlan = new ArtifactDeletionPlan(
                "mtg_artifact_worker_dead", T0.minusSeconds(1), null, null, List.of());
        store.register(ArtifactDeletionPlanJson.workflowStep(deadPlan));
        WorkflowStepStore.LeaseToken deadLease = store.claim(new WorkflowStepStore.ClaimRequest(
                Set.of(ArtifactDeletionPlan.STEP_TYPE), "owner", T0,
                Duration.ofSeconds(10), 1)).getLast().lease();
        store.fail(deadLease, T0.plusSeconds(1), T0.plusSeconds(2), "X", "Y", 1);
        assertEquals(WorkflowStepStore.RenewStatus.ALREADY_DEAD,
                store.renew(deadLease, T0.plusSeconds(2), Duration.ofSeconds(10)).status());
    }

    private static ArtifactDeletionPlan plan(int count) {
        List<ArtifactDeletionTarget> targets = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            WriteIntentSpec spec = new WriteIntentSpec(
                    "artifact_worker_" + index, MEETING, "transcript.final", "application/json",
                    "zh-CN", index + 1, 12, CHECKSUM);
            targets.add(ArtifactDeletionTarget.fromSpec(spec));
        }
        return new ArtifactDeletionPlan(MEETING, T0.minusSeconds(1), null, null, targets);
    }

    private static final class Fixture {
        private final InMemoryWorkflowStepStore workflow = new InMemoryWorkflowStepStore();
        private final InMemoryArtifactDeletionPlanStore plans = new InMemoryArtifactDeletionPlanStore();
        private final FakeItem items = new FakeItem();
        private final FakeTerminal terminal = new FakeTerminal(workflow);
        private final int maxAttempts;
        private Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        private Duration leaseDuration = Duration.ofSeconds(30);

        private Fixture(int count, int maxAttempts) {
            items.count = count;
            this.maxAttempts = maxAttempts;
        }

        private void register(int count) {
            ArtifactDeletionPlan plan = plan(count);
            plans.register(plan);
            workflow.register(ArtifactDeletionPlanJson.workflowStep(plan));
        }

        private ArtifactDeletionWorker worker() {
            return new ArtifactDeletionWorker(workflow, plans, items, terminal,
                    new ArtifactDeletionWorker.Config(
                            "worker-test", clock,
                            leaseDuration, Duration.ofSeconds(5), Duration.ofSeconds(5),
                            10, maxAttempts));
        }
    }

    private static final class FakeItem implements ArtifactDeletionItemUnitOfWork {
        private int count;
        private final List<Integer> ordinals = new ArrayList<>();
        private final List<WorkflowStepStore.LeaseToken> leases = new ArrayList<>();
        private final List<Result> scripted = new ArrayList<>();
        private Failure failure;

        @Override
        public Result delete(DeleteRequest request) {
            ordinals.add(request.ordinal());
            leases.add(request.lease());
            if (!scripted.isEmpty()) return scripted.removeFirst();
            if (failure != null) return failure;
            return new Success(SuccessCode.DELETED, new StorageBinding("binding-" + request.ordinal()));
        }
    }

    private static final class FakeTerminal implements ArtifactDeletionTerminalUnitOfWork {
        private final InMemoryWorkflowStepStore workflow;
        private int calls;
        private WorkflowStepStore.LeaseToken lease;
        private Failure failure;
        private boolean recoverSuccess;

        private FakeTerminal(InMemoryWorkflowStepStore workflow) {
            this.workflow = workflow;
        }

        @Override
        public Result complete(CompleteRequest request) {
            calls++;
            lease = request.lease();
            if (failure != null) return failure;
            WorkflowStepStore.CompleteResult completed =
                    workflow.complete(request.lease(), request.completedAt());
            if (completed.status() == WorkflowStepStore.CompleteStatus.COMPLETED) {
                return new Success(SuccessCode.COMPLETED, null);
            }
            return new Failure(ErrorCode.NOT_OWNER, false);
        }

        @Override
        public Result recover(CompleteRequest request) {
            if (recoverSuccess) {
                workflow.complete(request.lease(), request.completedAt());
                return new Success(SuccessCode.REPLAYED, null);
            }
            return new Failure(ErrorCode.OUTCOME_UNKNOWN, true);
        }
    }

    private static final class SequenceClock extends Clock {
        private final Instant[] values;
        private int index;

        private SequenceClock(Instant... values) {
            this.values = values;
        }

        @Override
        public ZoneOffset getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(java.time.ZoneId zone) { return this; }

        @Override
        public Instant instant() {
            return values[Math.min(index++, values.length - 1)];
        }
    }
}
