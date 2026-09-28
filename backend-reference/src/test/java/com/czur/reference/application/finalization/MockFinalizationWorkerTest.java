package com.czur.reference.application.finalization;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.persistence.InMemoryWorkflowStepStore;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStore.CompleteResult;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.DeleteResult;
import com.czur.reference.application.port.artifact.ArtifactStore.PrepareResult;
import com.czur.reference.application.port.artifact.ArtifactStore.ReadHandle;
import com.czur.reference.application.port.artifact.ArtifactStore.StageResult;
import com.czur.reference.application.port.artifact.ArtifactStore.StatResult;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.application.port.artifact.ArtifactStoreException;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.CancelReservationRequest;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.protocol.json.FinalizationCheckpointJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockFinalizationWorkerTest {
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    @TempDir
    Path tempDirectory;

    @Test
    void runResultRequiresOneStableItemPerClaimAndIdleRunIsExact() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new MockFinalizationWorker.RunResult(1, List.of()));
        assertTrue(new MockFinalizationWorker.RunResult(0, List.of()).idle());

        Fixture idle = fixture(false);
        MockFinalizationWorker.RunResult actual = idle.worker().runOnce();
        assertTrue(actual.idle());
        assertEquals(0, actual.claimedCount());
        assertTrue(actual.items().isEmpty());
    }

    @Test
    void configRejectsSubMicrosecondDurationsBeforeJdbcIsCalled() throws Exception {
        Fixture fixture = fixture(false);
        assertThrows(IllegalArgumentException.class, () ->
                new MockFinalizationWorker.Config(
                        "worker-one", fixture.clock,
                        Duration.ofSeconds(30).plusNanos(1),
                        Duration.ofSeconds(5), RETRY_DELAY, 1, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new MockFinalizationWorker.Config(
                        "worker-one", fixture.clock,
                        LEASE_DURATION,
                        Duration.ofSeconds(5).plusNanos(1), RETRY_DELAY, 1, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new MockFinalizationWorker.Config(
                        "worker-one", fixture.clock,
                        LEASE_DURATION,
                        Duration.ofSeconds(5),
                        RETRY_DELAY.plusNanos(1), 1, 1));
    }

    @Test
    void resultAndConfigDiagnosticsCloseTerminalReasonsAndRedactPrivateValues()
            throws Exception {
        Fixture fixture = fixture(false);
        WorkflowStep.Key key = fixture.checkpoint.workflowKey();

        assertThrows(IllegalArgumentException.class, () ->
                new MockFinalizationWorker.ItemResult(
                        key, MockFinalizationWorker.ItemStatus.COMPLETED, "FAILED"));
        assertThrows(IllegalArgumentException.class, () ->
                new MockFinalizationWorker.ItemResult(
                        key, MockFinalizationWorker.ItemStatus.FAILED_DEAD, "COMPLETED"));

        MockFinalizationWorker.ItemResult diagnostic =
                new MockFinalizationWorker.ItemResult(
                        key,
                        MockFinalizationWorker.ItemStatus.BLOCKED,
                        "PRIVATE_SECRET_REASON");
        assertFalse(diagnostic.toString().contains("PRIVATE_SECRET_REASON"));
        assertTrue(diagnostic.toString().contains("reasonCode=redacted"));

        MockFinalizationWorker.Config config = new MockFinalizationWorker.Config(
                "private-owner-token",
                Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC),
                LEASE_DURATION,
                Duration.ofSeconds(5),
                RETRY_DELAY,
                1,
                1);
        assertFalse(config.toString().contains("private-owner-token"));
        assertFalse(config.toString().contains("2026-09-29T00:00:00Z"));
        assertTrue(config.toString().contains("leaseOwner=redacted"));
        assertTrue(config.toString().contains("clockPresent=true"));
    }

    @Test
    void runOncePublishesArtifactsCommitsUsageCompletesAndLeavesOtherTypesAlone()
            throws Exception {
        Fixture fixture = fixture();
        WorkflowStep unrelated = WorkflowStep.pending(
                new WorkflowStep.Key("unrelated-meeting", "other-step", "other-key"),
                "other-checkpoint/v1",
                "{}",
                "other-digest",
                fixture.clock.instant(),
                fixture.clock.instant());
        fixture.workflow.register(unrelated);

        MockFinalizationWorker.RunResult result = fixture.worker().runOnce();

        assertEquals(1, result.claimedCount());
        assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED,
                result.items().getFirst().status());
        assertEquals(1, fixture.entitlement.commitCalls.get());
        assertEquals(1, fixture.completion.completeCalls.get());
        assertEquals(0, fixture.completion.failCalls.get());
        assertEquals(WorkflowStep.Status.PENDING,
                fixture.workflow.find(unrelated.key()).orElseThrow().status());
        for (FinalizationCheckpoint.ArtifactMaterial material
                : fixture.checkpoint.artifacts()) {
            ArtifactStore.WriteIntent intent = fixture.artifactStore
                    .createWriteIntent(material.spec()).intent();
            assertEquals(ArtifactStore.StoreState.READY,
                    fixture.artifactStore.stat(intent.binding()).state());
        }
    }

    @Test
    void batchProducesExactlyOneResultForEveryClaimedStep() throws Exception {
        Fixture fixture = fixture();
        WorkflowStepStore.ClaimedStep claimed = fixture.workflow.claim(
                new WorkflowStepStore.ClaimRequest(
                        java.util.Set.of(FinalizationCheckpoint.STEP_TYPE),
                        "batch-seed",
                        fixture.clock.instant(),
                        LEASE_DURATION,
                        1)).getFirst();
        WorkflowStepStore twice = new ForwardingWorkflowStore(fixture.workflow) {
            private boolean first = true;

            @Override
            public List<ClaimedStep> claim(ClaimRequest request) {
                if (!first) {
                    return List.of();
                }
                first = false;
                return List.of(claimed, claimed);
            }
        };

        MockFinalizationWorker.RunResult result = fixture.worker(
                twice, fixture.artifactStore, fixture.entitlement,
                fixture.completion, 3).runOnce();

        assertEquals(2, result.claimedCount());
        assertEquals(2, result.items().size());
        assertTrue(result.items().stream().allMatch(item ->
                item.status() == MockFinalizationWorker.ItemStatus.COMPLETED));
    }

    @Test
    void retryableArtifactFailureUsesFixedDelayAndNeverWritesDeadAlone()
            throws Exception {
        Fixture fixture = fixture();
        ArtifactStore unavailable = new ForwardingArtifactStore(fixture.artifactStore) {
            @Override
            public PrepareResult createWriteIntent(WriteIntentSpec spec)
                    throws ArtifactStoreException {
                throw new ArtifactStoreException(
                        ArtifactStoreException.Code.STORAGE_UNAVAILABLE,
                        "artifact-unavailable");
            }
        };
        MockFinalizationWorker worker = fixture.worker(unavailable, fixture.entitlement,
                fixture.completion, 3);

        MockFinalizationWorker.ItemResult item = worker.runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.RETRY_SCHEDULED, item.status());
        WorkflowStep stored = fixture.workflow.find(
                fixture.checkpoint.workflowKey()).orElseThrow();
        assertEquals(WorkflowStep.Status.PENDING, stored.status());
        assertEquals(1, stored.attempt());
        assertEquals(fixture.clock.instant().plus(RETRY_DELAY), stored.nextAttemptAt());
        assertEquals(0, fixture.completion.failCalls.get());
    }

    @Test
    void nonRetryableArtifactFailureUsesAtomicFailureCompletion() throws Exception {
        Fixture fixture = fixture();
        ArtifactStore conflicting = new ForwardingArtifactStore(fixture.artifactStore) {
            @Override
            public PrepareResult createWriteIntent(WriteIntentSpec spec)
                    throws ArtifactStoreException {
                throw new ArtifactStoreException(
                        ArtifactStoreException.Code.STORAGE_CONFLICT,
                        "/private/token=secret/" + "x".repeat(5000));
            }
        };
        MockFinalizationWorker worker = fixture.worker(conflicting, fixture.entitlement,
                fixture.completion, 3);

        MockFinalizationWorker.ItemResult item = worker.runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.FAILED_DEAD, item.status());
        assertEquals(1, fixture.completion.failCalls.get());
        assertFalse(fixture.completion.lastFailure.failure().retryable());
        assertEquals("ARTIFACT_STORAGE_CONFLICT",
                fixture.completion.lastFailure.failure().code());
        assertTrue(fixture.completion.lastFailure.readyArtifacts().isEmpty());
        assertEquals("ARTIFACT_STORAGE_CONFLICT",
                fixture.completion.lastFailure.failure().diagnosticId());
        assertFalse(fixture.completion.lastFailure.toString().contains("secret"));
        assertFalse(item.toString().contains("secret"));
    }

    @Test
    void completionPortsCannotReturnSuccessFromTheWrongTerminalClass()
            throws Exception {
        Fixture completeFixture = fixture();
        completeFixture.completion.completeResults.add(successFailed());

        MockFinalizationWorker.ItemResult wrongComplete =
                completeFixture.worker().runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.INCONSISTENT,
                wrongComplete.status());
        assertEquals("COMPLETION_SUCCESS_CLASS_INVALID", wrongComplete.reasonCode());

        Fixture failFixture = fixture();
        failFixture.completion.failResults.add(successReady());
        ArtifactStore conflicting = new ForwardingArtifactStore(
                failFixture.artifactStore) {
            @Override
            public PrepareResult createWriteIntent(WriteIntentSpec spec)
                    throws ArtifactStoreException {
                throw new ArtifactStoreException(
                        ArtifactStoreException.Code.STORAGE_CONFLICT,
                        "diagnostic-must-not-escape");
            }
        };

        MockFinalizationWorker.ItemResult wrongFailure = failFixture.worker(
                conflicting, failFixture.entitlement, failFixture.completion, 3)
                .runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.INCONSISTENT,
                wrongFailure.status());
        assertEquals("FAILURE_SUCCESS_CLASS_INVALID", wrongFailure.reasonCode());
    }

    @Test
    void retryableFailureAtMaximumAttemptsUsesAtomicFailureCompletion()
            throws Exception {
        Fixture fixture = fixture();
        ArtifactStore unavailable = new ForwardingArtifactStore(fixture.artifactStore) {
            @Override
            public PrepareResult createWriteIntent(WriteIntentSpec spec)
                    throws ArtifactStoreException {
                throw new ArtifactStoreException(
                        ArtifactStoreException.Code.STORAGE_UNAVAILABLE,
                        "artifact-unavailable");
            }
        };
        MockFinalizationWorker worker = fixture.worker(unavailable, fixture.entitlement,
                fixture.completion, 2);

        assertEquals(MockFinalizationWorker.ItemStatus.RETRY_SCHEDULED,
                worker.runOnce().items().getFirst().status());
        fixture.clock.advance(RETRY_DELAY);
        MockFinalizationWorker.ItemResult terminal =
                worker.runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.FAILED_DEAD, terminal.status());
        assertEquals(1, fixture.completion.failCalls.get());
        assertTrue(fixture.completion.lastFailure.failure().retryable());
        assertEquals(1, fixture.completion.lastFailure.leasedAttempt());
    }

    @Test
    void terminalFailurePreservesAlreadyReadyArtifactSubset() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger intents = new AtomicInteger();
        ArtifactStore secondArtifactFails = new ForwardingArtifactStore(
                fixture.artifactStore) {
            @Override
            public PrepareResult createWriteIntent(WriteIntentSpec spec)
                    throws ArtifactStoreException {
                if (intents.incrementAndGet() == 2) {
                    throw new ArtifactStoreException(
                            ArtifactStoreException.Code.STORAGE_CONFLICT,
                            "second-artifact-conflict");
                }
                return delegate.createWriteIntent(spec);
            }
        };

        MockFinalizationWorker.ItemResult item = fixture.worker(
                secondArtifactFails, fixture.entitlement, fixture.completion, 3)
                .runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.FAILED_DEAD, item.status());
        assertEquals(1, fixture.completion.lastFailure.readyArtifacts().size());
    }

    @Test
    void artifactCommitUnknownUsesStatAndContinuesFromReady() throws Exception {
        Fixture fixture = fixture();
        ArtifactStore commitUnknown = new ForwardingArtifactStore(fixture.artifactStore) {
            @Override
            public CompleteResult completeWrite(WriteIntent intent, ContentClaim claim)
                    throws ArtifactStoreException {
                delegate.completeWrite(intent, claim);
                throw new ArtifactStoreException(
                        ArtifactStoreException.Code.COMMIT_UNKNOWN,
                        "artifact-commit-unknown");
            }
        };

        MockFinalizationWorker.ItemResult item = fixture.worker(
                commitUnknown, fixture.entitlement, fixture.completion, 3)
                .runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED, item.status());
        assertEquals(1, fixture.entitlement.commitCalls.get());
        assertEquals(1, fixture.completion.completeCalls.get());
    }

    @Test
    void artifactCommitUnknownWithPendingStateSchedulesRetry() throws Exception {
        Fixture fixture = fixture();
        ArtifactStore pending = new ForwardingArtifactStore(fixture.artifactStore) {
            @Override
            public CompleteResult completeWrite(WriteIntent intent, ContentClaim claim)
                    throws ArtifactStoreException {
                throw new ArtifactStoreException(
                        ArtifactStoreException.Code.COMMIT_UNKNOWN,
                        "unknown-before-ready");
            }
        };

        MockFinalizationWorker.ItemResult item = fixture.worker(
                pending, fixture.entitlement, fixture.completion, 3)
                .runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.RETRY_SCHEDULED, item.status());
        assertEquals("ARTIFACT_COMMIT_UNKNOWN", item.reasonCode());
        assertEquals(0, fixture.entitlement.commitCalls.get());
    }

    @Test
    void artifactCommitUnknownWithDeletedStateFailsClosed() throws Exception {
        Fixture fixture = fixture();
        ArtifactStore deleted = new ForwardingArtifactStore(fixture.artifactStore) {
            @Override
            public CompleteResult completeWrite(WriteIntent intent, ContentClaim claim)
                    throws ArtifactStoreException {
                delegate.delete(intent.binding());
                throw new ArtifactStoreException(
                        ArtifactStoreException.Code.COMMIT_UNKNOWN,
                        "unknown-after-delete");
            }
        };

        MockFinalizationWorker.ItemResult item = fixture.worker(
                deleted, fixture.entitlement, fixture.completion, 3)
                .runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.FAILED_DEAD, item.status());
        assertEquals("ARTIFACT_DELETED_DURING_FINALIZATION",
                fixture.completion.lastFailure.failure().code());
    }

    @Test
    void artifactCommitUnknownRejectsMismatchedReadyDescriptor() throws Exception {
        Fixture fixture = fixture();
        ArtifactStore mismatched = new ForwardingArtifactStore(fixture.artifactStore) {
            private WriteIntent captured;
            private ContentClaim claim;

            @Override
            public CompleteResult completeWrite(WriteIntent intent, ContentClaim content)
                    throws ArtifactStoreException {
                captured = intent;
                claim = content;
                throw new ArtifactStoreException(
                        ArtifactStoreException.Code.COMMIT_UNKNOWN,
                        "unknown-with-mismatched-ready");
            }

            @Override
            public StatResult stat(StorageBinding binding) {
                WriteIntent wrong = new WriteIntent(
                        captured.intentId() + "-wrong", captured.binding(), captured.spec());
                return new StatResult(
                        ArtifactStore.StoreState.READY,
                        new ArtifactStore.ArtifactDescriptor(wrong, claim));
            }
        };

        MockFinalizationWorker.ItemResult item = fixture.worker(
                mismatched, fixture.entitlement, fixture.completion, 3)
                .runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.FAILED_DEAD, item.status());
        assertEquals("ARTIFACT_READY_CONFLICT",
                fixture.completion.lastFailure.failure().code());
    }

    @Test
    void entitlementUnavailableAfterReadyArtifactsSchedulesStableRetry()
            throws Exception {
        Fixture fixture = fixture();
        TestEntitlement unavailable = new TestEntitlement(request ->
                new EntitlementProviderResult.Failure<>(new EntitlementProviderError(
                        EntitlementProviderError.Code.PROVIDER_UNAVAILABLE, true)));

        MockFinalizationWorker.ItemResult item = fixture.worker(
                fixture.artifactStore, unavailable, fixture.completion, 3)
                .runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.RETRY_SCHEDULED, item.status());
        assertEquals("ENTITLEMENT_PROVIDER_UNAVAILABLE", item.reasonCode());
        assertEquals(0, fixture.completion.completeCalls.get());
        for (FinalizationCheckpoint.ArtifactMaterial material
                : fixture.checkpoint.artifacts()) {
            ArtifactStore.WriteIntent intent = fixture.artifactStore
                    .createWriteIntent(material.spec()).intent();
            assertEquals(ArtifactStore.StoreState.READY,
                    fixture.artifactStore.stat(intent.binding()).state());
        }
    }

    @Test
    void entitlementSuccessMustMatchFrozenIdentityMeetingAndVersion() throws Exception {
        for (int drift = 0; drift < 3; drift++) {
            Fixture fixture = fixture();
            int selected = drift;
            TestEntitlement mismatched = new TestEntitlement(request -> {
                UsageOperationResult valid = fixture.committedUsage(request);
                UsageOperationResult changed = switch (selected) {
                    case 0 -> UsageOperationResult.terminal(
                            valid.deploymentScope(), valid.subjectScope(),
                            valid.operationScope(), valid.idempotencyKey() + "-drift",
                            valid.requestDigest(), valid.reservation());
                    case 1 -> UsageOperationResult.terminal(
                            valid.deploymentScope(), valid.subjectScope(),
                            valid.operationScope(), valid.idempotencyKey(),
                            valid.requestDigest(), copyReservation(
                                    valid.reservation(),
                                    valid.reservation().expectedMeetingId(),
                                    valid.reservation().boundMeetingId(),
                                    valid.reservation().version() + 1));
                    case 2 -> UsageOperationResult.terminal(
                            valid.deploymentScope(), valid.subjectScope(),
                            valid.operationScope(), valid.idempotencyKey(),
                            valid.requestDigest(), copyReservation(
                                    valid.reservation(),
                                    "different-meeting",
                                    "different-meeting",
                                    valid.reservation().version()));
                    default -> throw new AssertionError("unexpected drift");
                };
                return new EntitlementProviderResult.Success<>(changed);
            });

            MockFinalizationWorker.ItemResult item = fixture.worker(
                    fixture.artifactStore, mismatched, fixture.completion, 3)
                    .runOnce().items().getFirst();

            assertEquals(MockFinalizationWorker.ItemStatus.FAILED_DEAD, item.status());
            assertEquals("ENTITLEMENT_RESULT_CONFLICT",
                    fixture.completion.lastFailure.failure().code());
        }
    }

    @Test
    void completionUnknownRecoversReadOnlyBeforeReportingSuccess() throws Exception {
        Fixture fixture = fixture();
        fixture.completion.completeResults.add(new FinalizationCompletionUnitOfWork.Failure(
                FinalizationCompletionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN));
        fixture.completion.recoverCompletionResults.add(successReady());

        MockFinalizationWorker.ItemResult item = fixture.worker().runOnce()
                .items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED, item.status());
        assertEquals(1, fixture.completion.completeCalls.get());
        assertEquals(1, fixture.completion.recoverCompletionCalls.get());
        assertEquals(0, fixture.completion.failCalls.get());
    }

    @Test
    void completionNotFoundAfterUnknownKeepsSuccessMarkerWithoutSameRunLoop()
            throws Exception {
        Fixture fixture = fixture();
        fixture.completion.completeResults.add(new FinalizationCompletionUnitOfWork.Failure(
                FinalizationCompletionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN));
        fixture.completion.recoverCompletionResults.add(
                new FinalizationCompletionUnitOfWork.Failure(
                        FinalizationCompletionUnitOfWork.ErrorCode.COMPLETION_NOT_FOUND));

        MockFinalizationWorker.ItemResult item = fixture.worker().runOnce()
                .items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.OUTCOME_UNRESOLVED, item.status());
        assertEquals(1, fixture.completion.completeCalls.get());
        assertEquals(1, fixture.completion.recoverCompletionCalls.get());
        WorkflowStep marked = fixture.workflow.find(
                fixture.checkpoint.workflowKey()).orElseThrow();
        assertEquals(WorkflowStep.Status.LEASED, marked.status());
        assertEquals(FinalizationTerminalOperationMarker.SUCCESS_CODE,
                marked.errorCode());
        assertEquals(0, marked.attempt());
    }

    @Test
    void markerCommitUnknownMustRecoverBeforeCompletionAndUnavailableStopsClosed()
            throws Exception {
        Fixture fixture = fixture();
        AtomicInteger markerRecoverCalls = new AtomicInteger();
        WorkflowStepStore markerUnknown = new ForwardingWorkflowStore(fixture.workflow) {
            @Override
            public PersistLeaseMarkerResult persistLeaseMarker(
                    PersistLeaseMarkerRequest request) {
                PersistLeaseMarkerResult committed = delegate.persistLeaseMarker(request);
                assertEquals(PersistLeaseMarkerStatus.PERSISTED, committed.status());
                return new PersistLeaseMarkerResult(
                        PersistLeaseMarkerStatus.OUTCOME_UNKNOWN, null);
            }

            @Override
            public PersistLeaseMarkerResult recoverLeaseMarker(
                    PersistLeaseMarkerRequest request) {
                markerRecoverCalls.incrementAndGet();
                assertEquals(0, fixture.completion.completeCalls.get(),
                        "Completion must not run before marker recovery is conclusive");
                assertEquals(0, fixture.completion.failCalls.get(),
                        "failure terminalization must not run before marker recovery");
                return new PersistLeaseMarkerResult(
                        PersistLeaseMarkerStatus.STORAGE_UNAVAILABLE, null);
            }
        };

        MockFinalizationWorker.ItemResult item = fixture.worker(
                markerUnknown,
                fixture.artifactStore,
                fixture.entitlement,
                fixture.completion,
                3).runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.OUTCOME_UNRESOLVED,
                item.status());
        assertEquals(WorkflowStepStore.PersistLeaseMarkerStatus.STORAGE_UNAVAILABLE.name(),
                item.reasonCode());
        assertEquals(1, markerRecoverCalls.get());
        assertEquals(0, fixture.completion.completeCalls.get());
        assertEquals(0, fixture.completion.failCalls.get());
        assertEquals(FinalizationTerminalOperationMarker.SUCCESS_CODE,
                fixture.workflow.find(fixture.checkpoint.workflowKey())
                        .orElseThrow().errorCode(),
                "the committed marker must remain durable while recovery is unavailable");
    }

    @Test
    void markerCommitUnknownExecutesCompletionOnlyAfterExactRecoveryReplay()
            throws Exception {
        Fixture fixture = fixture();
        AtomicInteger markerRecoverCalls = new AtomicInteger();
        WorkflowStepStore markerUnknown = new ForwardingWorkflowStore(fixture.workflow) {
            @Override
            public PersistLeaseMarkerResult persistLeaseMarker(
                    PersistLeaseMarkerRequest request) {
                PersistLeaseMarkerResult committed = delegate.persistLeaseMarker(request);
                assertEquals(PersistLeaseMarkerStatus.PERSISTED, committed.status());
                return new PersistLeaseMarkerResult(
                        PersistLeaseMarkerStatus.OUTCOME_UNKNOWN, null);
            }

            @Override
            public PersistLeaseMarkerResult recoverLeaseMarker(
                    PersistLeaseMarkerRequest request) {
                markerRecoverCalls.incrementAndGet();
                assertEquals(0, fixture.completion.completeCalls.get());
                PersistLeaseMarkerResult recovered =
                        delegate.recoverLeaseMarker(request);
                assertEquals(PersistLeaseMarkerStatus.REPLAYED, recovered.status());
                return recovered;
            }
        };

        MockFinalizationWorker.ItemResult item = fixture.worker(
                markerUnknown,
                fixture.artifactStore,
                fixture.entitlement,
                fixture.completion,
                3).runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED, item.status());
        assertEquals(1, markerRecoverCalls.get());
        assertEquals(1, fixture.completion.completeCalls.get());
        assertEquals(0, fixture.completion.failCalls.get());
    }

    @Test
    void failureCommitUnknownUsesReadOnlyRecoveryBeforeReportingDead()
            throws Exception {
        Fixture fixture = fixture();
        fixture.completion.failResults.add(new FinalizationCompletionUnitOfWork.Failure(
                FinalizationCompletionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN));
        fixture.completion.recoverFailureResults.add(successFailed());
        ArtifactStore conflicting = new ForwardingArtifactStore(fixture.artifactStore) {
            @Override
            public PrepareResult createWriteIntent(WriteIntentSpec spec)
                    throws ArtifactStoreException {
                throw new ArtifactStoreException(
                        ArtifactStoreException.Code.STORAGE_CONFLICT,
                        "failure-outcome-unknown");
            }
        };

        MockFinalizationWorker.ItemResult item = fixture.worker(
                conflicting, fixture.entitlement, fixture.completion, 3)
                .runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.FAILED_DEAD, item.status());
        assertEquals(1, fixture.completion.failCalls.get());
        assertEquals(1, fixture.completion.recoverFailureCalls.get());
    }

    @Test
    void unavailableFailureTerminalizationKeepsDurableMarkerWithoutDead()
            throws Exception {
        Fixture fixture = fixture();
        fixture.completion.failResults.add(new FinalizationCompletionUnitOfWork.Failure(
                FinalizationCompletionUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE));
        ArtifactStore conflicting = new ForwardingArtifactStore(fixture.artifactStore) {
            @Override
            public PrepareResult createWriteIntent(WriteIntentSpec spec)
                    throws ArtifactStoreException {
                throw new ArtifactStoreException(
                        ArtifactStoreException.Code.STORAGE_CONFLICT,
                        "terminalization-unavailable");
            }
        };

        MockFinalizationWorker.ItemResult item = fixture.worker(
                conflicting, fixture.entitlement, fixture.completion, 1)
                .runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.OUTCOME_UNRESOLVED, item.status());
        WorkflowStep marked = fixture.workflow.find(
                fixture.checkpoint.workflowKey()).orElseThrow();
        assertEquals(WorkflowStep.Status.LEASED, marked.status());
        assertEquals(FinalizationTerminalOperationMarker.FAILURE_CODE,
                marked.errorCode());
        assertEquals(0, marked.attempt());
    }

    @Test
    void maxAttemptFailureDecisionSurvivesUnavailableCompletionAndCannotReturnReady()
            throws Exception {
        Fixture fixture = fixture();
        TestEntitlement denied = new TestEntitlement(request ->
                new EntitlementProviderResult.Failure<>(new EntitlementProviderError(
                        EntitlementProviderError.Code.PERMISSION_DENIED, false)));
        fixture.completion.failResults.add(new FinalizationCompletionUnitOfWork.Failure(
                FinalizationCompletionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN));
        fixture.completion.recoverFailureResults.add(
                new FinalizationCompletionUnitOfWork.Failure(
                        FinalizationCompletionUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE));
        fixture.completion.recoverFailureResults.add(
                new FinalizationCompletionUnitOfWork.Failure(
                        FinalizationCompletionUnitOfWork.ErrorCode.COMPLETION_NOT_FOUND));

        MockFinalizationWorker.ItemResult first = fixture.worker(
                fixture.artifactStore, denied, fixture.completion, 1)
                .runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.OUTCOME_UNRESOLVED, first.status());
        WorkflowStep marked = fixture.workflow.find(
                fixture.checkpoint.workflowKey()).orElseThrow();
        assertEquals(WorkflowStep.Status.LEASED, marked.status());
        assertEquals(FinalizationTerminalOperationMarker.FAILURE_CODE,
                marked.errorCode());
        FinalizationCompletionUnitOfWork.TerminalFailure frozenFailure =
                fixture.completion.lastFailure.failure();
        List<ArtifactStore.ArtifactDescriptor> frozenArtifacts =
                fixture.completion.lastFailure.readyArtifacts();
        assertEquals(fixture.checkpoint.artifacts().size(), frozenArtifacts.size());

        int firstEntitlementCalls = denied.commitCalls.get();
        fixture.clock.advance(LEASE_DURATION);
        ArtifactStore readOnlyArtifacts = new ForwardingArtifactStore(
                fixture.artifactStore) {
            @Override
            public PrepareResult createWriteIntent(WriteIntentSpec spec) {
                throw new AssertionError("FAILED recovery must not create write intents");
            }

            @Override
            public StageResult writeContent(WriteIntent intent, InputStream content) {
                throw new AssertionError("FAILED recovery must not stage content");
            }

            @Override
            public CompleteResult completeWrite(WriteIntent intent, ContentClaim claim) {
                throw new AssertionError("FAILED recovery must not publish content");
            }

            @Override
            public StatResult stat(StorageBinding binding) {
                throw new AssertionError("FAILED recovery must not stat artifacts");
            }
        };
        TestEntitlement nowAvailable = new TestEntitlement(fixture::committedEntitlement);

        MockFinalizationWorker.ItemResult second = fixture.worker(
                readOnlyArtifacts, nowAvailable, fixture.completion, 1)
                .runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.FAILED_DEAD, second.status());
        assertEquals(firstEntitlementCalls, denied.commitCalls.get());
        assertEquals(0, nowAvailable.commitCalls.get());
        assertEquals(0, fixture.completion.completeCalls.get());
        assertEquals(2, fixture.completion.failCalls.get());
        assertEquals(2, fixture.completion.recoverFailureCalls.get());
        assertEquals(frozenFailure, fixture.completion.lastFailure.failure());
        assertEquals(frozenArtifacts, fixture.completion.lastFailure.readyArtifacts());
    }

    @Test
    void terminalFailureRecoveryPreservesFrozenDescriptorsWithoutReadingArtifactStore()
            throws Exception {
        Fixture fixture = fixture();
        TestEntitlement denied = new TestEntitlement(request ->
                new EntitlementProviderResult.Failure<>(new EntitlementProviderError(
                        EntitlementProviderError.Code.PERMISSION_DENIED, false)));
        fixture.completion.failResults.add(new FinalizationCompletionUnitOfWork.Failure(
                FinalizationCompletionUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE));
        fixture.worker(fixture.artifactStore, denied, fixture.completion, 1)
                .runOnce();
        List<ArtifactStore.ArtifactDescriptor> frozenArtifacts =
                fixture.completion.lastFailure.readyArtifacts();
        fixture.clock.advance(LEASE_DURATION);

        AtomicInteger statCalls = new AtomicInteger();
        ArtifactStore noLongerReady = new ForwardingArtifactStore(fixture.artifactStore) {
            @Override
            public PrepareResult createWriteIntent(WriteIntentSpec spec) {
                throw new AssertionError("FAILED recovery must not recreate artifacts");
            }

            @Override
            public StatResult stat(StorageBinding binding) {
                statCalls.incrementAndGet();
                throw new AssertionError("marker recovery must not stat artifacts");
            }
        };

        MockFinalizationWorker.ItemResult recovered = fixture.worker(
                noLongerReady,
                new TestEntitlement(fixture::committedEntitlement),
                fixture.completion,
                1).runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.FAILED_DEAD, recovered.status());
        assertEquals(0, statCalls.get());
        assertEquals(frozenArtifacts, fixture.completion.lastFailure.readyArtifacts());
    }

    @Test
    void successOutcomeUnknownCrossLeaseDoesNotRepeatArtifactsOrEntitlement()
            throws Exception {
        Fixture fixture = fixture();
        fixture.completion.completeResults.add(
                new FinalizationCompletionUnitOfWork.Failure(
                        FinalizationCompletionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN));
        fixture.completion.recoverCompletionResults.add(
                new FinalizationCompletionUnitOfWork.Failure(
                        FinalizationCompletionUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE));
        fixture.completion.recoverCompletionResults.add(
                new FinalizationCompletionUnitOfWork.Failure(
                        FinalizationCompletionUnitOfWork.ErrorCode.COMPLETION_NOT_FOUND));

        MockFinalizationWorker.ItemResult first = fixture.worker().runOnce()
                .items().getFirst();
        assertEquals(MockFinalizationWorker.ItemStatus.OUTCOME_UNRESOLVED,
                first.status());
        int firstEntitlementCalls = fixture.entitlement.commitCalls.get();
        fixture.clock.advance(LEASE_DURATION);

        ArtifactStore noSecondArtifactPass = new ForwardingArtifactStore(
                fixture.artifactStore) {
            @Override
            public PrepareResult createWriteIntent(WriteIntentSpec spec) {
                throw new AssertionError("success marker recovery must not recreate artifacts");
            }

            @Override
            public StageResult writeContent(WriteIntent intent, InputStream content) {
                throw new AssertionError("success marker recovery must not stage artifacts");
            }

            @Override
            public CompleteResult completeWrite(WriteIntent intent, ContentClaim claim) {
                throw new AssertionError("success marker recovery must not publish artifacts");
            }
        };
        TestEntitlement noSecondEntitlement = new TestEntitlement(request -> {
            throw new AssertionError("success marker recovery must not commit usage");
        });

        MockFinalizationWorker.ItemResult recovered = fixture.worker(
                noSecondArtifactPass,
                noSecondEntitlement,
                fixture.completion,
                3).runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED, recovered.status());
        assertEquals(firstEntitlementCalls, fixture.entitlement.commitCalls.get());
        assertEquals(0, noSecondEntitlement.commitCalls.get());
        assertEquals(2, fixture.completion.completeCalls.get());
        assertEquals(2, fixture.completion.recoverCompletionCalls.get());
        assertEquals(0, fixture.completion.failCalls.get());
    }

    @Test
    void corruptTerminalFailureMarkerFailsClosedBeforeAnyBusinessSideEffect()
            throws Exception {
        Fixture fixture = fixture();
        WorkflowStepStore corruptsMarker = new ForwardingWorkflowStore(fixture.workflow) {
            @Override
            public PersistLeaseMarkerResult persistLeaseMarker(
                    PersistLeaseMarkerRequest request) {
                PersistLeaseMarkerRequest corrupted = new PersistLeaseMarkerRequest(
                        request.lease(),
                        request.markedAt(),
                        request.expectedErrorCode(),
                        request.expectedErrorSummary(),
                        request.markerCode(),
                        request.markerSummary() + "=");
                return delegate.persistLeaseMarker(corrupted);
            }
        };
        AtomicInteger artifactCalls = new AtomicInteger();
        ArtifactStore conflict = new ForwardingArtifactStore(fixture.artifactStore) {
            @Override
            public PrepareResult createWriteIntent(WriteIntentSpec spec)
                    throws ArtifactStoreException {
                artifactCalls.incrementAndGet();
                throw new ArtifactStoreException(
                        ArtifactStoreException.Code.STORAGE_CONFLICT,
                        "force-terminal-failure");
            }
        };
        fixture.completion.failResults.add(new FinalizationCompletionUnitOfWork.Failure(
                FinalizationCompletionUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE));
        MockFinalizationWorker first = fixture.worker(
                corruptsMarker,
                conflict,
                fixture.entitlement,
                fixture.completion,
                1);
        assertEquals(MockFinalizationWorker.ItemStatus.OUTCOME_UNRESOLVED,
                first.runOnce().items().getFirst().status());
        fixture.clock.advance(LEASE_DURATION);

        int entitlementCalls = fixture.entitlement.commitCalls.get();
        MockFinalizationWorker.ItemResult second = first.runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.INCONSISTENT, second.status());
        assertEquals("TERMINAL_OPERATION_MARKER_INVALID", second.reasonCode());
        assertEquals(1, artifactCalls.get());
        assertEquals(entitlementCalls, fixture.entitlement.commitCalls.get());
        assertEquals(1, fixture.completion.failCalls.get());
        assertEquals(0, fixture.completion.completeCalls.get());
    }

    @Test
    void unresolvedCompletionUnknownDoesNotWriteWorkflowFailure() throws Exception {
        Fixture fixture = fixture();
        fixture.completion.completeResults.add(new FinalizationCompletionUnitOfWork.Failure(
                FinalizationCompletionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN));
        fixture.completion.recoverCompletionResults.add(
                new FinalizationCompletionUnitOfWork.Failure(
                        FinalizationCompletionUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE));

        MockFinalizationWorker.ItemResult item = fixture.worker().runOnce()
                .items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.OUTCOME_UNRESOLVED, item.status());
        assertEquals(WorkflowStep.Status.LEASED,
                fixture.workflow.find(fixture.checkpoint.workflowKey())
                        .orElseThrow().status());
        assertEquals(0, fixture.completion.failCalls.get());
    }

    @Test
    void expiredLeaseAfterExternalCommitStopsBeforeCompletion() throws Exception {
        Fixture fixture = fixture();
        TestEntitlement slowEntitlement = new TestEntitlement(request -> {
            EntitlementProviderResult<UsageOperationResult> committed =
                    fixture.committedEntitlement(request);
            fixture.clock.advance(LEASE_DURATION);
            return committed;
        });

        MockFinalizationWorker.ItemResult item = fixture.worker(
                fixture.artifactStore, slowEntitlement, fixture.completion, 3)
                .runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.LEASE_EXPIRED, item.status());
        assertEquals(0, fixture.completion.completeCalls.get());
        assertEquals(0, fixture.completion.failCalls.get());
    }

    @Test
    void nearingExpiryRenewsSynchronouslyBeforeNextExternalOperation()
            throws Exception {
        Fixture fixture = fixture();
        AtomicInteger completedArtifacts = new AtomicInteger();
        ArtifactStore slowFirstArtifact = new ForwardingArtifactStore(fixture.artifactStore) {
            @Override
            public CompleteResult completeWrite(WriteIntent intent, ContentClaim claim)
                    throws ArtifactStoreException {
                CompleteResult result = delegate.completeWrite(intent, claim);
                if (completedArtifacts.incrementAndGet() == 1) {
                    fixture.clock.advance(Duration.ofSeconds(26));
                }
                return result;
            }
        };

        MockFinalizationWorker.ItemResult item = fixture.worker(
                slowFirstArtifact, fixture.entitlement, fixture.completion, 3)
                .runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED, item.status());
        assertNotNull(fixture.completion.lastCompletion);
        assertTrue(fixture.completion.lastCompletion.lease().leaseVersion() > 1);
    }

    @Test
    void renewLeaseLossStopsBeforeTheNextExternalOperation() throws Exception {
        Fixture fixture = fixture();
        WorkflowStepStore losesRenew = new ForwardingWorkflowStore(fixture.workflow) {
            @Override
            public RenewResult renew(
                    LeaseToken lease, Instant renewedAt, Duration leaseDuration) {
                return new RenewResult(
                        RenewStatus.NOT_OWNER,
                        fixture.workflow.find(lease.key()).orElseThrow(),
                        null);
            }
        };
        AtomicInteger completedArtifacts = new AtomicInteger();
        ArtifactStore slowFirstArtifact = new ForwardingArtifactStore(fixture.artifactStore) {
            @Override
            public CompleteResult completeWrite(WriteIntent intent, ContentClaim claim)
                    throws ArtifactStoreException {
                CompleteResult result = delegate.completeWrite(intent, claim);
                if (completedArtifacts.incrementAndGet() == 1) {
                    fixture.clock.advance(Duration.ofSeconds(26));
                }
                return result;
            }
        };

        MockFinalizationWorker.ItemResult item = fixture.worker(
                losesRenew, slowFirstArtifact, fixture.entitlement,
                fixture.completion, 3).runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.LEASE_LOST, item.status());
        assertEquals("NOT_OWNER", item.reasonCode());
        assertEquals(0, fixture.entitlement.commitCalls.get());
    }

    @Test
    void failLeaseLossIsReportedWithoutASecondTerminalWrite() throws Exception {
        Fixture fixture = fixture();
        WorkflowStepStore losesFail = new ForwardingWorkflowStore(fixture.workflow) {
            @Override
            public FailResult fail(
                    LeaseToken lease,
                    Instant failedAt,
                    Instant nextAttemptAt,
                    String errorCode,
                    String errorSummary,
                    int maxAttempts) {
                return new FailResult(
                        FailStatus.NOT_OWNER,
                        fixture.workflow.find(lease.key()).orElseThrow());
            }
        };
        ArtifactStore unavailable = new ForwardingArtifactStore(fixture.artifactStore) {
            @Override
            public PrepareResult createWriteIntent(WriteIntentSpec spec)
                    throws ArtifactStoreException {
                throw new ArtifactStoreException(
                        ArtifactStoreException.Code.STORAGE_UNAVAILABLE,
                        "fail-lease-lost");
            }
        };

        MockFinalizationWorker.ItemResult item = fixture.worker(
                losesFail, unavailable, fixture.entitlement,
                fixture.completion, 3).runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.LEASE_LOST, item.status());
        assertEquals("NOT_OWNER", item.reasonCode());
        assertEquals(0, fixture.completion.failCalls.get());
    }

    @Test
    void corruptPersistedCheckpointIsInconsistentAndCannotCreateHalfTerminal()
            throws Exception {
        Fixture fixture = fixture(false);
        WorkflowStep corrupt = WorkflowStep.pending(
                fixture.checkpoint.workflowKey(),
                FinalizationCheckpoint.CHECKPOINT_TYPE,
                "not-json",
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                fixture.clock.instant(),
                fixture.clock.instant());
        fixture.workflow.register(corrupt);

        MockFinalizationWorker.ItemResult item = fixture.worker().runOnce()
                .items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.INCONSISTENT, item.status());
        assertEquals("CHECKPOINT_INVALID", item.reasonCode());
        assertEquals(WorkflowStep.Status.LEASED,
                fixture.workflow.find(corrupt.key()).orElseThrow().status());
        assertEquals(0, fixture.entitlement.commitCalls.get());
        assertEquals(0, fixture.completion.completeCalls.get());
        assertEquals(0, fixture.completion.failCalls.get());
    }

    private Fixture fixture() throws Exception {
        return fixture(true);
    }

    private Fixture fixture(boolean registerWorkflow) throws Exception {
        DeterministicMockFinalizationPlanner.Plan plan =
                FinalizationCheckpointTest.fixturePlan();
        FinalizationCheckpoint checkpoint = plan.checkpoint();
        MutableClock clock = new MutableClock(
                checkpoint.registeredAt().plusSeconds(1));
        AtomicInteger token = new AtomicInteger();
        InMemoryWorkflowStepStore workflow = new InMemoryWorkflowStepStore(
                () -> "worker-token-" + token.incrementAndGet());
        if (registerWorkflow) {
            workflow.register(plan.workflowStep());
        }
        PersistentFileSystemMockArtifactStore artifacts =
                new PersistentFileSystemMockArtifactStore(
                        tempDirectory.resolve("artifacts-" + token.incrementAndGet()),
                        FinalizationCheckpoint.MAX_ARTIFACT_CONTENT_BYTES);
        TestCompletion completion = new TestCompletion();
        Fixture fixture = new Fixture(
                checkpoint, clock, workflow, artifacts, null, completion);
        TestEntitlement entitlement = new TestEntitlement(fixture::committedEntitlement);
        return new Fixture(
                checkpoint, clock, workflow, artifacts, entitlement, completion);
    }

    private static FinalizationCompletionUnitOfWork.Result successReady() {
        return new FinalizationCompletionUnitOfWork.Success(
                FinalizationCompletionUnitOfWork.SuccessCode.COMPLETED,
                manifest(ManifestStatus.READY));
    }

    private static FinalizationCompletionUnitOfWork.Result successFailed() {
        return new FinalizationCompletionUnitOfWork.Success(
                FinalizationCompletionUnitOfWork.SuccessCode.FAILED,
                manifest(ManifestStatus.FAILED));
    }

    private static MeetingManifest manifest(ManifestStatus status) {
        Instant started = Instant.parse("2026-09-28T08:00:00Z");
        Instant ended = started.plusSeconds(60);
        return new MeetingManifest(
                "1.0",
                "mtg_worker_test",
                1,
                new MeetingManifest.Profile("worker-test", 1),
                null,
                status,
                started,
                ended,
                60_000,
                new MeetingManifest.Languages("zh-CN", List.of()),
                List.of(),
                List.of());
    }

    private static UsageReservation copyReservation(
            UsageReservation source,
            String expectedMeetingId,
            String boundMeetingId,
            long version) {
        return new UsageReservation(
                source.reservationId(),
                source.deploymentScope(),
                source.subjectScope(),
                source.grantId(),
                expectedMeetingId,
                boundMeetingId,
                source.boundAt(),
                source.reservedUsage(),
                source.actualUsage(),
                source.createdAt(),
                source.expiresAt(),
                source.state(),
                version,
                source.committedAt(),
                source.cancelledAt(),
                source.cancellationReasonCode());
    }

    private record Fixture(
            FinalizationCheckpoint checkpoint,
            MutableClock clock,
            InMemoryWorkflowStepStore workflow,
            PersistentFileSystemMockArtifactStore artifactStore,
            TestEntitlement entitlement,
            TestCompletion completion) {

        private MockFinalizationWorker worker() {
            return worker(artifactStore, entitlement, completion, 3);
        }

        private MockFinalizationWorker worker(
                ArtifactStore artifacts,
                EntitlementProvider entitlements,
                FinalizationCompletionUnitOfWork completionPort,
                int maxAttempts) {
            return worker(
                    workflow, artifacts, entitlements, completionPort, maxAttempts);
        }

        private MockFinalizationWorker worker(
                WorkflowStepStore workflowSteps,
                ArtifactStore artifacts,
                EntitlementProvider entitlements,
                FinalizationCompletionUnitOfWork completionPort,
                int maxAttempts) {
            return new MockFinalizationWorker(
                    workflowSteps,
                    artifacts,
                    entitlements,
                    completionPort,
                    new MockFinalizationWorker.Config(
                            "worker-one",
                            clock,
                            LEASE_DURATION,
                            Duration.ofSeconds(5),
                            RETRY_DELAY,
                            10,
                            maxAttempts));
        }

        private EntitlementProviderResult<UsageOperationResult> committedEntitlement(
                CommitUsageRequest request) {
            return new EntitlementProviderResult.Success<>(committedUsage(request));
        }

        private UsageOperationResult committedUsage(CommitUsageRequest request) {
            Instant committedAt = clock.instant();
            Instant createdAt = checkpoint.registeredAt().minusSeconds(120);
            Instant boundAt = checkpoint.registeredAt().minusSeconds(60);
            UsageReservation committed = new UsageReservation(
                    request.reservationId(),
                    request.deploymentScope(),
                    request.subjectScope(),
                    "grant-worker-test",
                    checkpoint.meetingId(),
                    checkpoint.meetingId(),
                    boundAt,
                    new UsageEstimate(request.actual().unit(),
                            Math.max(1L, request.actual().amount())),
                    request.actual(),
                    createdAt,
                    checkpoint.registeredAt().plusSeconds(3600),
                    UsageReservation.State.COMMITTED,
                    request.expectedVersion() + 1,
                    committedAt,
                    null,
                    null);
            return UsageOperationResult.terminal(
                    request.deploymentScope(),
                    request.subjectScope(),
                    request.operationScope(),
                    request.idempotencyKey(),
                    request.requestDigest(),
                    committed);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static final class TestEntitlement implements EntitlementProvider {
        private final Function<CommitUsageRequest,
                EntitlementProviderResult<UsageOperationResult>> commit;
        private final AtomicInteger commitCalls = new AtomicInteger();

        private TestEntitlement(Function<CommitUsageRequest,
                EntitlementProviderResult<UsageOperationResult>> commit) {
            this.commit = commit;
        }

        @Override
        public EntitlementProviderResult<MeetingGrant> authorizeStart(
                AuthorizeStartRequest request) {
            throw new AssertionError("worker must not authorize start");
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> reserveUsage(
                ReserveUsageRequest request) {
            throw new AssertionError("worker must not reserve usage");
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> commitUsage(
                CommitUsageRequest request) {
            commitCalls.incrementAndGet();
            return commit.apply(request);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> cancelReservation(
                CancelReservationRequest request) {
            throw new AssertionError("worker must not cancel reservation");
        }
    }

    private static final class TestCompletion
            implements FinalizationCompletionUnitOfWork {
        private final Queue<Result> completeResults = new ArrayDeque<>();
        private final Queue<Result> failResults = new ArrayDeque<>();
        private final Queue<Result> recoverCompletionResults = new ArrayDeque<>();
        private final Queue<Result> recoverFailureResults = new ArrayDeque<>();
        private final AtomicInteger completeCalls = new AtomicInteger();
        private final AtomicInteger failCalls = new AtomicInteger();
        private final AtomicInteger recoverCompletionCalls = new AtomicInteger();
        private final AtomicInteger recoverFailureCalls = new AtomicInteger();
        private CompletionRequest lastCompletion;
        private FailureRequest lastFailure;

        @Override
        public Result complete(CompletionRequest request) {
            completeCalls.incrementAndGet();
            lastCompletion = request;
            return completeResults.isEmpty() ? successReady() : completeResults.remove();
        }

        @Override
        public Result fail(FailureRequest request) {
            failCalls.incrementAndGet();
            lastFailure = request;
            return failResults.isEmpty() ? successFailed() : failResults.remove();
        }

        @Override
        public Result recoverCompletion(CompletionRequest request) {
            recoverCompletionCalls.incrementAndGet();
            return recoverCompletionResults.isEmpty()
                    ? new Failure(ErrorCode.COMPLETION_NOT_FOUND)
                    : recoverCompletionResults.remove();
        }

        @Override
        public Result recoverFailure(FailureRequest request) {
            recoverFailureCalls.incrementAndGet();
            return recoverFailureResults.isEmpty()
                    ? new Failure(ErrorCode.COMPLETION_NOT_FOUND)
                    : recoverFailureResults.remove();
        }
    }

    private static class ForwardingWorkflowStore implements WorkflowStepStore {
        protected final WorkflowStepStore delegate;

        private ForwardingWorkflowStore(WorkflowStepStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public RegisterResult register(WorkflowStep candidate) {
            return delegate.register(candidate);
        }

        @Override
        public Optional<WorkflowStep> find(WorkflowStep.Key key) {
            return delegate.find(key);
        }

        @Override
        public List<ClaimedStep> claim(ClaimRequest request) {
            return delegate.claim(request);
        }

        @Override
        public RenewResult renew(
                LeaseToken lease, Instant renewedAt, Duration leaseDuration) {
            return delegate.renew(lease, renewedAt, leaseDuration);
        }

        @Override
        public PersistLeaseMarkerResult persistLeaseMarker(
                PersistLeaseMarkerRequest request) {
            return delegate.persistLeaseMarker(request);
        }

        @Override
        public PersistLeaseMarkerResult recoverLeaseMarker(
                PersistLeaseMarkerRequest request) {
            return delegate.recoverLeaseMarker(request);
        }

        @Override
        public WorkflowStepStore.CompleteResult complete(
                LeaseToken lease, Instant completedAt) {
            return delegate.complete(lease, completedAt);
        }

        @Override
        public FailResult fail(
                LeaseToken lease,
                Instant failedAt,
                Instant nextAttemptAt,
                String errorCode,
                String errorSummary,
                int maxAttempts) {
            return delegate.fail(
                    lease, failedAt, nextAttemptAt,
                    errorCode, errorSummary, maxAttempts);
        }
    }

    private static class ForwardingArtifactStore implements ArtifactStore {
        protected final ArtifactStore delegate;

        private ForwardingArtifactStore(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public PrepareResult createWriteIntent(WriteIntentSpec spec)
                throws ArtifactStoreException {
            return delegate.createWriteIntent(spec);
        }

        @Override
        public StageResult writeContent(WriteIntent intent, InputStream content)
                throws ArtifactStoreException {
            return delegate.writeContent(intent, content);
        }

        @Override
        public CompleteResult completeWrite(WriteIntent intent, ContentClaim claim)
                throws ArtifactStoreException {
            return delegate.completeWrite(intent, claim);
        }

        @Override
        public StatResult stat(StorageBinding binding) throws ArtifactStoreException {
            return delegate.stat(binding);
        }

        @Override
        public ReadHandle openRead(StorageBinding binding)
                throws ArtifactStoreException {
            return delegate.openRead(binding);
        }

        @Override
        public DeleteResult delete(StorageBinding binding)
                throws ArtifactStoreException {
            return delegate.delete(binding);
        }
    }
}
