package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.finalization.DeterministicMockFinalizationPlanner;
import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.finalization.FinalizationTerminalOperationMarker;
import com.czur.reference.application.port.FinalizationAdmissionUnitOfWork;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.CompletionRequest;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.FailureRequest;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.TerminalFailure;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingFinalizationStore;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.json.FinalizationCheckpointJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finalization Completion 的 PostgreSQL 16 原子终态与 lease fencing 门禁。 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcFinalizationCompletionUnitOfWorkIntegrationTest {
    private static final List<String> MIGRATIONS = List.of(
            "V001__initial_schema.sql",
            "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql",
            "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql",
            "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql",
            "V008__durable_input_fact.sql",
            "V009__final_usage_fact.sql",
            "V010__workflow_step_fencing.sql",
            "V011__finalization_execution_artifact_ready.sql");
    private static final String DEPLOYMENT = "completion-deployment";
    private static final String SUBJECT = "completion-subject";
    private static final Instant RESERVED_AT = Instant.parse("2026-09-29T00:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-09-29T00:01:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-09-29T00:01:05.123456Z");
    private static final Instant STOPPED_AT = Instant.parse("2026-09-29T00:09:59.123456Z");
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-29T00:10:00.123456Z");

    private PGSimpleDataSource admin;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        admin = postgres(null);
        schema = "finalization_completion_"
                + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        dataSource = postgres(schema);
        for (String migration : MIGRATIONS) {
            applyMigration(migration);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (admin != null && schema != null) {
            try (Connection connection = admin.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + quote(schema) + " CASCADE");
            }
        }
    }

    @Test
    void readyMetadataManifestMeetingAndWorkflowCommitTogetherAndRecover() throws Exception {
        Prepared prepared = prepare("ready", true, "worker-ready");
        CompletionRequest request = new CompletionRequest(
                prepared.claimed().lease(),
                prepared.checkpoint(),
                prepared.descriptors(),
                ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(request);
        var unitOfWork = new JdbcFinalizationCompletionUnitOfWork(dataSource);

        FinalizationCompletionUnitOfWork.Success first = success(
                unitOfWork.complete(request));
        assertEquals(FinalizationCompletionUnitOfWork.SuccessCode.COMPLETED, first.code());
        assertEquals(ManifestStatus.READY, first.manifest().status());
        assertEquals(prepared.descriptors().size(), first.manifest().artifacts().size());

        FinalizationCompletionUnitOfWork.Success replay = success(
                unitOfWork.recoverCompletion(request));
        assertEquals(FinalizationCompletionUnitOfWork.SuccessCode.COMPLETION_REPLAYED,
                replay.code());
        assertEquals(first.manifest(), replay.manifest());
        assertTerminal(prepared.meetingId(), "READY", "READY", "COMPLETED", 0);
        assertEquals(prepared.descriptors().size(), count(
                "SELECT count(*) FROM artifact WHERE meeting_id=?", prepared.meetingId()));
        assertEquals(1, count(
                "SELECT count(*) FROM meeting_manifest_revision WHERE meeting_id=?",
                prepared.meetingId()));
    }

    @Test
    void failureKeepsReadySubsetAndAtomicallyDeadLettersWorkflow() throws Exception {
        ProductProfile profile = twoArtifactProfile();
        Prepared prepared = prepare(
                "failed", false, "worker-failed", profile,
                Set.of("transcript.final", "summary.structured"),
                new CapabilityDescriptor(
                        "completion-full-provider",
                        Set.of("transcript.final", "summary.structured")));
        assertTrue(prepared.descriptors().size() >= 2,
                "failure subset gate requires at least two planned artifacts");
        List<ArtifactDescriptor> readySubset = List.of(
                prepared.descriptors().getFirst());
        FailureRequest request = new FailureRequest(
                prepared.claimed().lease(),
                prepared.checkpoint(),
                readySubset,
                new TerminalFailure(
                        "MODEL", "MODEL_FINALIZATION_FAILED", false,
                        "diag-completion", "model finalization failed"),
                ACCEPTED_AT.plusSeconds(2),
                prepared.claimed().step().attempt());
        persistTerminalMarker(request);
        var unitOfWork = new JdbcFinalizationCompletionUnitOfWork(dataSource);

        FinalizationCompletionUnitOfWork.Success first = success(unitOfWork.fail(request));
        assertEquals(FinalizationCompletionUnitOfWork.SuccessCode.FAILED, first.code());
        assertEquals(ManifestStatus.FAILED, first.manifest().status());
        assertEquals(1, first.manifest().artifacts().stream().filter(reference ->
                reference.status() == com.czur.reference.application.port.MeetingManifest
                        .ArtifactStatus.READY).count());
        assertEquals(prepared.descriptors().size() - 1,
                first.manifest().artifacts().stream().filter(reference ->
                        reference.status()
                                == com.czur.reference.application.port.MeetingManifest
                                .ArtifactStatus.FAILED).count());
        assertEquals(FinalizationCompletionUnitOfWork.SuccessCode.FAILURE_REPLAYED,
                success(unitOfWork.recoverFailure(request)).code());
        assertTerminal(prepared.meetingId(), "FAILED", "FAILED", "DEAD", 1);
        assertEquals(1, count(
                "SELECT count(*) FROM artifact WHERE meeting_id=?", prepared.meetingId()));
    }

    @Test
    void reclaimedLeaseRejectsOldOwnerBeforeAnyTerminalFactIsWritten() throws Exception {
        Prepared old = prepare("stale", true, "worker-old");
        CompletionRequest staleRequest = new CompletionRequest(
                old.claimed().lease(),
                old.checkpoint(),
                old.descriptors(),
                ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(staleRequest);
        List<WorkflowStepStore.ClaimedStep> reclaimed =
                new JdbcWorkflowStepStore(dataSource).claim(
                        new WorkflowStepStore.ClaimRequest(
                                Set.of(FinalizationCheckpoint.STEP_TYPE),
                                "worker-new",
                                old.claimed().lease().expiresAt().plusSeconds(1),
                                Duration.ofSeconds(60),
                                1));
        assertEquals(1, reclaimed.size());

        FinalizationCompletionUnitOfWork.Failure rejected = failure(
                new JdbcFinalizationCompletionUnitOfWork(dataSource)
                        .complete(staleRequest));
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.LEASE_NOT_OWNER,
                rejected.code());
        assertTerminal(old.meetingId(), "FINALIZING", "GENERATING", "LEASED", 0);
        assertEquals(0, count(
                "SELECT count(*) FROM artifact WHERE meeting_id=?", old.meetingId()));
        assertEquals(0, count(
                "SELECT count(*) FROM meeting_manifest_revision WHERE meeting_id=?",
                old.meetingId()));
    }

    @Test
    void recoveryBeforeCommitReturnsNotFoundOnlyForTheExactActiveLease() throws Exception {
        Prepared prepared = prepare("recover-absent", true, "worker-recover");
        CompletionRequest request = new CompletionRequest(
                prepared.claimed().lease(),
                prepared.checkpoint(),
                prepared.descriptors(),
                ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(request);
        var unitOfWork = new JdbcFinalizationCompletionUnitOfWork(dataSource);

        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.COMPLETION_NOT_FOUND,
                failure(unitOfWork.recoverCompletion(request)).code());
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                failure(unitOfWork.recoverFailure(failureRequest(
                        prepared, List.of(), "different terminal kind"))).code());

        Prepared failurePrepared = prepare(
                "recover-absent-failure", false, "worker-recover-failure");
        FailureRequest failureRequest = failureRequest(
                failurePrepared, List.of(), "failure not committed");
        persistTerminalMarker(failureRequest);
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.COMPLETION_NOT_FOUND,
                failure(unitOfWork.recoverFailure(failureRequest)).code());

        JdbcWorkflowStepStore workflow = new JdbcWorkflowStepStore(dataSource);
        List<WorkflowStepStore.ClaimedStep> reclaimed = workflow.claim(
                new WorkflowStepStore.ClaimRequest(
                Set.of(FinalizationCheckpoint.STEP_TYPE),
                "worker-recover-next",
                request.lease().expiresAt(),
                Duration.ofSeconds(60),
                10));
        assertTrue(reclaimed.stream().anyMatch(value -> value.step().key().equals(
                request.lease().key())));
        assertTrue(reclaimed.stream().anyMatch(value -> value.step().key().equals(
                failureRequest.lease().key())));
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.COMPLETION_NOT_FOUND,
                failure(unitOfWork.recoverCompletion(request)).code(),
                "canonical marker must preserve the old request across lease rotation");
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.COMPLETION_NOT_FOUND,
                failure(unitOfWork.recoverFailure(failureRequest)).code(),
                "failure marker must preserve the old request across lease rotation");

        WorkflowStepStore.LeaseToken real = prepared.claimed().lease();
        WorkflowStepStore.LeaseToken forged = new WorkflowStepStore.LeaseToken(
                real.key(), real.owner(), "forged-private-token", real.expiresAt(),
                real.claimedAt(), real.leaseVersion());
        CompletionRequest forgedRequest = new CompletionRequest(
                forged, prepared.checkpoint(), prepared.descriptors(),
                ACCEPTED_AT.plusSeconds(2));
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                failure(unitOfWork.recoverCompletion(forgedRequest)).code());
    }

    @Test
    void preCommitRecoveryRejectsCorruptMarkerAttemptAndPrivateTokenDrift()
            throws Exception {
        var unitOfWork = new JdbcFinalizationCompletionUnitOfWork(dataSource);

        Prepared corrupt = prepare("marker-corrupt", true, "worker-marker-corrupt");
        CompletionRequest corruptRequest = new CompletionRequest(
                corrupt.claimed().lease(), corrupt.checkpoint(), corrupt.descriptors(),
                ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(corruptRequest);
        corruptWorkflowMarkerPayload(corrupt.meetingId());
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                failure(unitOfWork.recoverCompletion(corruptRequest)).code());

        Prepared attempt = prepare("marker-attempt", false, "worker-marker-attempt");
        FailureRequest attemptRequest = failureRequest(
                attempt, List.of(), "attempt must stay frozen");
        persistTerminalMarker(attemptRequest);
        incrementWorkflowAttempt(attempt.meetingId());
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                failure(unitOfWork.recoverFailure(attemptRequest)).code());

        Prepared token = prepare("marker-token", true, "worker-marker-token");
        CompletionRequest tokenRequest = new CompletionRequest(
                token.claimed().lease(), token.checkpoint(), token.descriptors(),
                ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(tokenRequest);
        driftWorkflowPrivateLeaseToken(token.meetingId());
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                failure(unitOfWork.recoverCompletion(tokenRequest)).code());
    }

    @Test
    void recoveryRejectsMetadataOnlyPartialTerminalState() throws Exception {
        Prepared prepared = prepare("metadata-only", true, "worker-metadata-only");
        for (ArtifactDescriptor descriptor : prepared.descriptors()) {
            persistReadyMetadata(descriptor);
        }
        CompletionRequest request = new CompletionRequest(
                prepared.claimed().lease(), prepared.checkpoint(),
                prepared.descriptors(), ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(request);

        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                failure(new JdbcFinalizationCompletionUnitOfWork(dataSource)
                        .recoverCompletion(request)).code());
    }

    @Test
    void writeRejectsCheckpointExternalMeetingMetadataBeforePublishingTerminalFacts()
            throws Exception {
        Prepared prepared = prepare("external-metadata", true, "worker-external");
        insertExternalMetadata(prepared.meetingId());
        CompletionRequest request = new CompletionRequest(
                prepared.claimed().lease(),
                prepared.checkpoint(),
                prepared.descriptors(),
                ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(request);

        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.ARTIFACT_CONFLICT,
                failure(new JdbcFinalizationCompletionUnitOfWork(dataSource)
                        .complete(request)).code());
        assertTerminal(prepared.meetingId(), "FINALIZING", "GENERATING", "LEASED", 0);
        assertEquals(0, count(
                "SELECT count(*) FROM meeting_manifest_revision WHERE meeting_id=?",
                prepared.meetingId()));
    }

    @Test
    void failureWriteRejectsReadyMetadataOmittedByTheRequest() throws Exception {
        Prepared prepared = prepare("omitted-metadata", false, "worker-omitted");
        persistReadyMetadata(prepared.descriptors().getFirst());
        FailureRequest request = failureRequest(
                prepared, List.of(), "original workflow summary");
        persistTerminalMarker(request);

        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.ARTIFACT_CONFLICT,
                failure(new JdbcFinalizationCompletionUnitOfWork(dataSource)
                        .fail(request)).code());
        assertTerminal(prepared.meetingId(), "FINALIZING", "GENERATING", "LEASED", 0);
        assertEquals(0, count(
                "SELECT count(*) FROM meeting_manifest_revision WHERE meeting_id=?",
                prepared.meetingId()));
    }

    @Test
    void failureRecoveryBindsWorkflowSummaryIntoTheStableTerminalIdentity()
            throws Exception {
        Prepared prepared = prepare("failure-identity", false, "worker-identity");
        FailureRequest original = failureRequest(
                prepared, List.of(), "original workflow summary");
        persistTerminalMarker(original);
        var unitOfWork = new JdbcFinalizationCompletionUnitOfWork(dataSource);
        success(unitOfWork.fail(original));

        FailureRequest drifted = failureRequest(
                prepared, List.of(), "different workflow summary");
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                failure(unitOfWork.recoverFailure(drifted)).code());
        assertEquals(FinalizationCompletionUnitOfWork.SuccessCode.FAILURE_REPLAYED,
                success(unitOfWork.recoverFailure(original)).code());
    }

    @Test
    void everyInjectedWriteWindowRollsBackSuccessAndFailureWithoutHalfState()
            throws Exception {
        AtomicInteger ordinal = new AtomicInteger();
        for (JdbcFinalizationCompletionUnitOfWork.FailurePoint point
                : JdbcFinalizationCompletionUnitOfWork.FailurePoint.values()) {
            String successSuffix = "rollback-success-" + ordinal.incrementAndGet();
            Prepared successPrepared = prepare(successSuffix, true, "worker-" + successSuffix);
            CompletionRequest completion = new CompletionRequest(
                    successPrepared.claimed().lease(), successPrepared.checkpoint(),
                    successPrepared.descriptors(), ACCEPTED_AT.plusSeconds(2));
            persistTerminalMarker(completion);
            var failingSuccess = failingAt(point);

            assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.INTERNAL,
                    failure(failingSuccess.complete(completion)).code(),
                    () -> "unexpected success-path failure at " + point);
            assertNoTerminalFacts(successPrepared.meetingId());
            assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.COMPLETION_NOT_FOUND,
                    failure(failingSuccess.recoverCompletion(completion)).code());
            assertEquals(FinalizationCompletionUnitOfWork.SuccessCode.COMPLETED,
                    success(new JdbcFinalizationCompletionUnitOfWork(dataSource)
                            .complete(completion)).code());

            String failureSuffix = "rollback-failure-" + ordinal.incrementAndGet();
            Prepared failurePrepared = prepare(
                    failureSuffix, false, "worker-" + failureSuffix);
            FailureRequest terminalFailure = failureRequest(
                    failurePrepared, List.of(), "rollback failure summary");
            persistTerminalMarker(terminalFailure);
            var failingFailure = failingAt(point);

            assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.INTERNAL,
                    failure(failingFailure.fail(terminalFailure)).code(),
                    () -> "unexpected failure-path classification at " + point);
            assertNoTerminalFacts(failurePrepared.meetingId());
            assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.COMPLETION_NOT_FOUND,
                    failure(failingFailure.recoverFailure(terminalFailure)).code());
            assertEquals(FinalizationCompletionUnitOfWork.SuccessCode.FAILED,
                    success(new JdbcFinalizationCompletionUnitOfWork(dataSource)
                            .fail(terminalFailure)).code());
        }
    }

    @Test
    void commitUnknownAbortsOldConnectionAndRecoversOnFreshReadOnlySnapshot()
            throws Exception {
        Prepared committed = prepare("unknown-committed", true, "worker-unknown-commit");
        CompletionRequest committedRequest = new CompletionRequest(
                committed.claimed().lease(), committed.checkpoint(),
                committed.descriptors(), ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(committedRequest);
        CommitUnknownHarness committedHarness = commitUnknownOnce(
                dataSource, CommitDisposition.COMMITTED);
        var committedUnit = new JdbcFinalizationCompletionUnitOfWork(
                committedHarness.dataSource());

        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                failure(committedUnit.complete(committedRequest)).code());
        assertUnknownConnectionDiscarded(committedHarness);
        assertEquals(FinalizationCompletionUnitOfWork.SuccessCode.COMPLETION_REPLAYED,
                success(committedUnit.recoverCompletion(committedRequest)).code());
        assertReadOnlyRecovery(committedHarness);

        Prepared absent = prepare("unknown-absent", true, "worker-unknown-absent");
        CompletionRequest absentRequest = new CompletionRequest(
                absent.claimed().lease(), absent.checkpoint(),
                absent.descriptors(), ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(absentRequest);
        CommitUnknownHarness absentHarness = commitUnknownOnce(
                dataSource, CommitDisposition.ROLLED_BACK);
        var absentUnit = new JdbcFinalizationCompletionUnitOfWork(
                absentHarness.dataSource());

        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                failure(absentUnit.complete(absentRequest)).code());
        assertUnknownConnectionDiscarded(absentHarness);
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.COMPLETION_NOT_FOUND,
                failure(absentUnit.recoverCompletion(absentRequest)).code());
        assertReadOnlyRecovery(absentHarness);
    }

    @Test
    void recoveryUsesOneRepeatableSnapshotWhenWorkflowChangesMidRead()
            throws Exception {
        Prepared prepared = prepare("repeatable-snapshot", true, "worker-snapshot");
        CompletionRequest request = new CompletionRequest(
                prepared.claimed().lease(), prepared.checkpoint(),
                prepared.descriptors(), ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(request);
        success(new JdbcFinalizationCompletionUnitOfWork(dataSource).complete(request));

        CountDownLatch meetingRead = new CountDownLatch(1);
        CountDownLatch continueRecovery = new CountDownLatch(1);
        var recovering = new JdbcFinalizationCompletionUnitOfWork(
                dataSource,
                ignored -> { },
                point -> {
                    meetingRead.countDown();
                    try {
                        if (!continueRecovery.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("recovery gate timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("recovery gate interrupted");
                    }
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<FinalizationCompletionUnitOfWork.Result> recovered = executor.submit(
                    () -> recovering.recoverCompletion(request));
            assertTrue(meetingRead.await(10, TimeUnit.SECONDS));
            mutateWorkflowDigest(prepared.meetingId());
            continueRecovery.countDown();
            assertEquals(FinalizationCompletionUnitOfWork.SuccessCode.COMPLETION_REPLAYED,
                    success(recovered.get(20, TimeUnit.SECONDS)).code(),
                    "one recovery must not splice a newer workflow into an older meeting snapshot");
        } finally {
            continueRecovery.countDown();
            executor.shutdownNow();
        }

        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.CHECKPOINT_CONFLICT,
                failure(new JdbcFinalizationCompletionUnitOfWork(dataSource)
                        .recoverCompletion(request)).code(),
                "a fresh recovery must observe the committed workflow drift");
    }

    @Test
    void concurrentCompletionAndSuccessVersusFailureEachHaveOneWinner()
            throws Exception {
        Prepared same = prepare("concurrent-same", true, "worker-concurrent-same");
        CompletionRequest sameRequest = new CompletionRequest(
                same.claimed().lease(), same.checkpoint(), same.descriptors(),
                ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(sameRequest);
        List<FinalizationCompletionUnitOfWork.Result> sameResults = concurrently(
                () -> new JdbcFinalizationCompletionUnitOfWork(dataSource)
                        .complete(sameRequest),
                () -> new JdbcFinalizationCompletionUnitOfWork(dataSource)
                        .complete(sameRequest));
        assertEquals(1, sameResults.stream()
                .filter(FinalizationCompletionUnitOfWork.Success.class::isInstance).count());
        assertEquals(1, sameResults.stream()
                .filter(FinalizationCompletionUnitOfWork.Failure.class::isInstance).count());
        assertTerminal(same.meetingId(), "READY", "READY", "COMPLETED", 0);

        Prepared opposed = prepare(
                "concurrent-opposed", true, "worker-concurrent-opposed");
        CompletionRequest ready = new CompletionRequest(
                opposed.claimed().lease(), opposed.checkpoint(), opposed.descriptors(),
                ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(ready);
        FailureRequest failed = failureRequest(
                opposed, List.of(), "concurrent failure summary");
        List<FinalizationCompletionUnitOfWork.Result> opposedResults = concurrently(
                () -> new JdbcFinalizationCompletionUnitOfWork(dataSource).complete(ready),
                () -> new JdbcFinalizationCompletionUnitOfWork(dataSource).fail(failed));
        assertEquals(1, opposedResults.stream()
                .filter(FinalizationCompletionUnitOfWork.Success.class::isInstance).count());
        assertEquals(1, opposedResults.stream()
                .filter(FinalizationCompletionUnitOfWork.Failure.class::isInstance).count());
        assertEquals(1, count(
                "SELECT count(*) FROM meeting_manifest_revision WHERE meeting_id=?",
                opposed.meetingId()));
        assertCoherentTerminalWinner(opposed.meetingId());
    }

    @Test
    void deploymentSchemaWinsOverEveryTemporaryShadowTable() throws Exception {
        Prepared prepared = prepare("temp-shadows", true, "worker-temp-shadows");
        CompletionRequest request = new CompletionRequest(
                prepared.claimed().lease(), prepared.checkpoint(),
                prepared.descriptors(), ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(request);

        assertEquals(FinalizationCompletionUnitOfWork.SuccessCode.COMPLETED,
                success(new JdbcFinalizationCompletionUnitOfWork(
                        allTemporaryShadows(dataSource, schema)).complete(request)).code());
        assertTerminal(prepared.meetingId(), "READY", "READY", "COMPLETED", 0);
    }

    @Test
    void corruptStoredFactAndUnavailableDatabaseHaveDifferentStableErrors()
            throws Exception {
        Prepared corrupt = prepare("corrupt-fact", true, "worker-corrupt-fact");
        corruptExecutionFact(corrupt.meetingId());
        CompletionRequest corruptRequest = new CompletionRequest(
                corrupt.claimed().lease(), corrupt.checkpoint(), corrupt.descriptors(),
                ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(corruptRequest);
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                failure(new JdbcFinalizationCompletionUnitOfWork(dataSource)
                        .complete(corruptRequest)).code());

        DataSource unavailable = unavailableDataSource();
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE,
                failure(new JdbcFinalizationCompletionUnitOfWork(unavailable)
                        .complete(corruptRequest)).code());
    }

    @Test
    void terminalRecoveryRejectsCorruptManifestAndArtifactTypedFacts()
            throws Exception {
        Prepared manifest = prepare(
                "corrupt-manifest", true, "worker-corrupt-manifest");
        CompletionRequest manifestRequest = new CompletionRequest(
                manifest.claimed().lease(), manifest.checkpoint(), manifest.descriptors(),
                ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(manifestRequest);
        var unitOfWork = new JdbcFinalizationCompletionUnitOfWork(dataSource);
        success(unitOfWork.complete(manifestRequest));
        corruptManifestHash(manifest.meetingId());
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                failure(unitOfWork.recoverCompletion(manifestRequest)).code());

        Prepared artifact = prepare(
                "corrupt-artifact", true, "worker-corrupt-artifact");
        CompletionRequest artifactRequest = new CompletionRequest(
                artifact.claimed().lease(), artifact.checkpoint(), artifact.descriptors(),
                ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(artifactRequest);
        success(unitOfWork.complete(artifactRequest));
        corruptArtifactSize(artifact.descriptors().getFirst()
                .intent().spec().artifactId());
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                failure(unitOfWork.recoverCompletion(artifactRequest)).code());
    }

    @Test
    void recoveryRejectsReadyAndReadyWithWarningsMeetingStateCrossTampering()
            throws Exception {
        Prepared ready = prepare("ready-state-drift", true, "worker-ready-drift");
        CompletionRequest readyRequest = new CompletionRequest(
                ready.claimed().lease(), ready.checkpoint(), ready.descriptors(),
                ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(readyRequest);
        var unitOfWork = new JdbcFinalizationCompletionUnitOfWork(dataSource);
        FinalizationCompletionUnitOfWork.Success readyResult = success(
                unitOfWork.complete(readyRequest));
        assertEquals(ManifestStatus.READY, readyResult.manifest().status());
        mutateMeetingState(ready.meetingId(), "READY_WITH_WARNINGS");
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                failure(unitOfWork.recoverCompletion(readyRequest)).code());

        ProductProfile warningProfile = warningProfile();
        Prepared warning = prepare(
                "warning-state-drift", true, "worker-warning-drift", warningProfile,
                Set.of("transcript.final", "speaker.identity"),
                new CapabilityDescriptor(
                        "completion-warning-provider",
                        Set.of("transcript.final", "speaker.session")));
        CompletionRequest warningRequest = new CompletionRequest(
                warning.claimed().lease(), warning.checkpoint(), warning.descriptors(),
                ACCEPTED_AT.plusSeconds(2));
        persistTerminalMarker(warningRequest);
        FinalizationCompletionUnitOfWork.Success warningResult = success(
                unitOfWork.complete(warningRequest));
        assertEquals(ManifestStatus.READY_WITH_WARNINGS,
                warningResult.manifest().status());
        mutateMeetingState(warning.meetingId(), "READY");
        assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                failure(unitOfWork.recoverCompletion(warningRequest)).code());
    }

    @Test
    void leaseAndFailureRecoveryRejectEveryPrivateIdentityDrift() throws Exception {
        Prepared leased = prepare("lease-drift", true, "worker-lease-drift");
        WorkflowStepStore.LeaseToken original = leased.claimed().lease();
        List<WorkflowStepStore.LeaseToken> forgedLeases = List.of(
                new WorkflowStepStore.LeaseToken(
                        original.key(), "other-owner", original.token(),
                        original.expiresAt(), original.claimedAt(), original.leaseVersion()),
                new WorkflowStepStore.LeaseToken(
                        original.key(), original.owner(), original.token(),
                        original.expiresAt().plusSeconds(1), original.claimedAt(),
                        original.leaseVersion()),
                new WorkflowStepStore.LeaseToken(
                        original.key(), original.owner(), original.token(), original.expiresAt(),
                        original.claimedAt().plusNanos(1_000), original.leaseVersion()));
        for (WorkflowStepStore.LeaseToken forged : forgedLeases) {
            CompletionRequest request = new CompletionRequest(
                    forged, leased.checkpoint(), leased.descriptors(),
                    ACCEPTED_AT.plusSeconds(2));
            assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.LEASE_NOT_OWNER,
                    failure(new JdbcFinalizationCompletionUnitOfWork(dataSource)
                            .complete(request)).code());
        }

        Prepared terminal = prepare("failure-drift", false, "worker-failure-drift");
        FailureRequest originalFailure = failureRequest(
                terminal, List.of(), "stable summary");
        persistTerminalMarker(originalFailure);
        var unitOfWork = new JdbcFinalizationCompletionUnitOfWork(dataSource);
        success(unitOfWork.fail(originalFailure));
        List<FailureRequest> drifted = List.of(
                withFailure(terminal, new TerminalFailure(
                        "STORAGE", "MODEL_FINALIZATION_FAILED", false,
                        "diag-completion", "stable summary"),
                        originalFailure.failedAt(), originalFailure.leasedAttempt()),
                withFailure(terminal, new TerminalFailure(
                        "MODEL", "OTHER_CODE", false,
                        "diag-completion", "stable summary"),
                        originalFailure.failedAt(), originalFailure.leasedAttempt()),
                withFailure(terminal, new TerminalFailure(
                        "MODEL", "MODEL_FINALIZATION_FAILED", true,
                        "diag-completion", "stable summary"),
                        originalFailure.failedAt(), originalFailure.leasedAttempt()),
                withFailure(terminal, new TerminalFailure(
                        "MODEL", "MODEL_FINALIZATION_FAILED", false,
                        "other-diagnostic", "stable summary"),
                        originalFailure.failedAt(), originalFailure.leasedAttempt()),
                withFailure(terminal, originalFailure.failure(),
                        originalFailure.failedAt().plusNanos(1_000),
                        originalFailure.leasedAttempt()),
                withFailure(terminal, originalFailure.failure(),
                        originalFailure.failedAt(),
                        originalFailure.leasedAttempt() + 1));
        for (FailureRequest request : drifted) {
            assertEquals(FinalizationCompletionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                    failure(unitOfWork.recoverFailure(request)).code());
        }
    }

    @Test
    void completionLogsAreRedactedAndFailureProbeIsNotPublicApi() throws Exception {
        Prepared prepared = prepare("log-secret-sentinel", false, "worker-log-secret");
        FailureRequest request = failureRequest(
                prepared, List.of(), "workflow-summary-secret-sentinel");
        persistTerminalMarker(request);
        Logger application = Logger.getLogger("com.czur.reference");
        CapturingHandler capture = new CapturingHandler();
        Level previousLevel = application.getLevel();
        application.setLevel(Level.ALL);
        capture.setLevel(Level.ALL);
        application.addHandler(capture);
        try {
            success(new JdbcFinalizationCompletionUnitOfWork(dataSource).fail(request));
        } finally {
            application.removeHandler(capture);
            application.setLevel(previousLevel);
        }
        String logs = capture.content();
        for (String forbidden : List.of(
                prepared.meetingId(), DEPLOYMENT, SUBJECT,
                request.failure().diagnosticId(), request.failure().workflowSummary(),
                request.failure().code())) {
            assertFalse(logs.contains(forbidden),
                    () -> "sensitive Completion value leaked: " + forbidden);
        }

        assertFalse(Modifier.isPublic(
                JdbcFinalizationCompletionUnitOfWork.FailureProbe.class.getModifiers()));
        assertFalse(Modifier.isPublic(
                JdbcFinalizationCompletionUnitOfWork.FailurePoint.class.getModifiers()));
        assertFalse(Modifier.isPublic(
                JdbcFinalizationCompletionUnitOfWork.RecoveryProbe.class.getModifiers()));
        assertFalse(Modifier.isPublic(
                JdbcFinalizationCompletionUnitOfWork.RecoveryPoint.class.getModifiers()));
        assertEquals(1,
                JdbcFinalizationCompletionUnitOfWork.class.getConstructors().length,
                "public API must expose only the DataSource constructor");
    }

    private JdbcFinalizationCompletionUnitOfWork failingAt(
            JdbcFinalizationCompletionUnitOfWork.FailurePoint point) {
        return new JdbcFinalizationCompletionUnitOfWork(
                dataSource,
                reached -> {
                    if (reached == point) {
                        throw new InjectedCompletionFailure();
                    }
                });
    }

    private void assertNoTerminalFacts(String meetingId) throws Exception {
        assertTerminal(meetingId, "FINALIZING", "GENERATING", "LEASED", 0);
        assertEquals(0, count(
                "SELECT count(*) FROM artifact WHERE meeting_id=?", meetingId));
        assertEquals(0, count(
                "SELECT count(*) FROM meeting_manifest_revision WHERE meeting_id=?",
                meetingId));
    }

    private static List<FinalizationCompletionUnitOfWork.Result> concurrently(
            ThrowingOperation firstOperation,
            ThrowingOperation secondOperation) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<FinalizationCompletionUnitOfWork.Result> first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return firstOperation.execute();
            });
            Future<FinalizationCompletionUnitOfWork.Result> second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return secondOperation.execute();
            });
            start.countDown();
            return List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private void assertCoherentTerminalWinner(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT m.state, m.manifest_state, w.status, count(a.artifact_id)
                       FROM meeting m
                       JOIN workflow_step w ON w.meeting_id=m.meeting_id
                       LEFT JOIN artifact a ON a.meeting_id=m.meeting_id
                      WHERE m.meeting_id=?
                      GROUP BY m.state, m.manifest_state, w.status
                     """)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                String state = rows.getString(1);
                String manifest = rows.getString(2);
                String workflow = rows.getString(3);
                int artifacts = rows.getInt(4);
                boolean readyWinner = "READY".equals(state)
                        && "READY".equals(manifest)
                        && "COMPLETED".equals(workflow)
                        && artifacts > 0;
                boolean failureWinner = "FAILED".equals(state)
                        && "FAILED".equals(manifest)
                        && "DEAD".equals(workflow)
                        && artifacts == 0;
                assertTrue(readyWinner || failureWinner,
                        "terminal facts must all belong to the same winner");
                assertFalse(rows.next());
            }
        }
    }

    private void corruptExecutionFact(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE finalization_execution_fact DROP CONSTRAINT "
                    + "finalization_execution_fact_hash_ck");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE finalization_execution_fact
                        SET content_sha256=decode(repeat('00', 32), 'hex')
                      WHERE meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void mutateWorkflowDigest(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE workflow_step
                        SET content_digest='sha256:' || repeat('9', 64)
                      WHERE meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    /**
     * Corrupts only the durable terminal marker payload for a negative recovery test.
     *
     * <p>The helper intentionally exposes no arbitrary SQL fragment: every mutation in this
     * fixture is fixed in source so a caller cannot accidentally turn a test utility into a
     * dynamic SQL execution path.</p>
     */
    private void corruptWorkflowMarkerPayload(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE workflow_step
                        SET error_message=error_message || '='
                      WHERE meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    /** Increments the leased attempt to prove a marker cannot be replayed for another attempt. */
    private void incrementWorkflowAttempt(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE workflow_step
                        SET attempt_count=attempt_count + 1
                      WHERE meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    /** Changes the database-private lease token while preserving its public lease coordinates. */
    private void driftWorkflowPrivateLeaseToken(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE workflow_step
                        SET lease_token=lease_token || '-drift'
                      WHERE meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void corruptManifestHash(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE meeting_manifest_revision DROP CONSTRAINT "
                    + "meeting_manifest_revision_hash_ck");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting_manifest_revision
                        SET content_sha256=decode(repeat('00', 32), 'hex')
                      WHERE meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void corruptArtifactSize(String artifactId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE artifact DROP CONSTRAINT "
                    + "artifact_content_size_ck");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE artifact SET content_size=-1 WHERE artifact_id=?
                     """)) {
            statement.setString(1, artifactId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void mutateMeetingState(String meetingId, String state) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting SET state=? WHERE meeting_id=?
                     """)) {
            statement.setString(1, state);
            statement.setString(2, meetingId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static DataSource unavailableDataSource() {
        return (DataSource) Proxy.newProxyInstance(
                JdbcFinalizationCompletionUnitOfWorkIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        throw new SQLException("sensitive unavailable", "08006");
                    }
                    return method.invoke(proxy, args);
                });
    }

    private static FailureRequest withFailure(
            Prepared prepared,
            TerminalFailure failure,
            Instant failedAt,
            int leasedAttempt) {
        return new FailureRequest(
                prepared.claimed().lease(), prepared.checkpoint(), List.of(), failure,
                failedAt, leasedAttempt);
    }

    private Prepared prepare(String suffix, boolean commitUsage, String leaseOwner)
            throws Exception {
        return prepare(
                suffix,
                commitUsage,
                leaseOwner,
                profile(),
                Set.of("asr"),
                new CapabilityDescriptor(Set.of("asr")));
    }

    private Prepared prepare(
            String suffix,
            boolean commitUsage,
            String leaseOwner,
            ProductProfile productProfile,
            Set<String> requestedCapabilities,
            CapabilityDescriptor modelCapabilities) throws Exception {
        String meetingId = createAdmissibleMeeting(
                suffix, productProfile, requestedCapabilities, modelCapabilities);
        FinalizationAdmissionUnitOfWork.AdmissionRequest admissionRequest =
                admissionRequest(meetingId);
        var admitted = new JdbcFinalizationAdmissionUnitOfWork(
                dataSource,
                () -> "receipt-" + suffix,
                new DeterministicMockFinalizationPlanner())
                .admit(admissionRequest);
        assertTrue(admitted instanceof FinalizationAdmissionUnitOfWork.Success);
        if (commitUsage) {
            commitReservation(meetingId);
        }
        WorkflowStepStore.ClaimedStep claimed =
                new JdbcWorkflowStepStore(dataSource).claim(
                        new WorkflowStepStore.ClaimRequest(
                                Set.of(FinalizationCheckpoint.STEP_TYPE),
                                leaseOwner,
                                ACCEPTED_AT.plusSeconds(1),
                                Duration.ofSeconds(60),
                                1))
                        .getFirst();
        FinalizationCheckpoint checkpoint = FinalizationCheckpointJson.decode(
                claimed.step().canonicalPayload());
        List<ArtifactDescriptor> descriptors = new ArrayList<>();
        for (int index = 0; index < checkpoint.artifacts().size(); index++) {
            FinalizationCheckpoint.ArtifactMaterial material =
                    checkpoint.artifacts().get(index);
            descriptors.add(new ArtifactDescriptor(
                    new WriteIntent(
                            "intent-" + suffix + '-' + index,
                            new StorageBinding("binding-" + suffix + '-' + index),
                            material.spec()),
                    material.claim()));
        }
        return new Prepared(meetingId, checkpoint, claimed, List.copyOf(descriptors));
    }

    private static FailureRequest failureRequest(
            Prepared prepared,
            List<ArtifactDescriptor> readyArtifacts,
            String workflowSummary) {
        return new FailureRequest(
                prepared.claimed().lease(),
                prepared.checkpoint(),
                readyArtifacts,
                new TerminalFailure(
                        "MODEL", "MODEL_FINALIZATION_FAILED", false,
                        "diag-completion", workflowSummary),
                ACCEPTED_AT.plusSeconds(2),
                prepared.claimed().step().attempt());
    }

    private void persistReadyMetadata(ArtifactDescriptor descriptor) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            assertEquals(
                    com.czur.reference.application.port.artifact
                            .ArtifactReadyMetadataStore.PersistStatus.APPLIED,
                    new JdbcArtifactReadyMetadataStore(connection)
                            .persistReady(descriptor).status());
            connection.commit();
        }
    }

    /**
     * 模拟 Worker 的硬性顺序：先以完整 lease CAS durable marker，再进入 Completion。
     * 测试辅助方法本身也通过公共 Store 端口执行，避免测试绕过真实事务语义。
     */
    private void persistTerminalMarker(CompletionRequest request) {
        JdbcWorkflowStepStore store = new JdbcWorkflowStepStore(dataSource);
        var current = store.find(request.lease().key()).orElseThrow();
        FinalizationTerminalOperationMarker marker =
                FinalizationTerminalOperationMarker.forCompletion(
                        request, current.attempt());
        var result = store.persistLeaseMarker(
                new WorkflowStepStore.PersistLeaseMarkerRequest(
                        request.lease(),
                        request.completedAt(),
                        current.errorCode(),
                        current.errorSummary(),
                        marker.markerCode(),
                        marker.encodedSummary()));
        assertTrue(result.status()
                        == WorkflowStepStore.PersistLeaseMarkerStatus.PERSISTED
                        || result.status()
                        == WorkflowStepStore.PersistLeaseMarkerStatus.REPLAYED,
                () -> "completion marker was not persisted: " + result.status());
    }

    private void persistTerminalMarker(FailureRequest request) {
        JdbcWorkflowStepStore store = new JdbcWorkflowStepStore(dataSource);
        var current = store.find(request.lease().key()).orElseThrow();
        FinalizationTerminalOperationMarker marker =
                FinalizationTerminalOperationMarker.forFailure(request);
        var result = store.persistLeaseMarker(
                new WorkflowStepStore.PersistLeaseMarkerRequest(
                        request.lease(),
                        request.failedAt(),
                        current.errorCode(),
                        current.errorSummary(),
                        marker.markerCode(),
                        marker.encodedSummary()));
        assertTrue(result.status()
                        == WorkflowStepStore.PersistLeaseMarkerStatus.PERSISTED
                        || result.status()
                        == WorkflowStepStore.PersistLeaseMarkerStatus.REPLAYED,
                () -> "failure marker was not persisted: " + result.status());
    }

    private void insertExternalMetadata(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO artifact (
                         artifact_id, meeting_id, artifact_type, media_type, language,
                         revision, intent_id, storage_binding, content_size,
                         content_checksum)
                     VALUES (?, ?, ?, ?, NULL, 1, ?, ?, 1, ?)
                     """)) {
            statement.setString(1, "external-artifact-" + meetingId);
            statement.setString(2, meetingId);
            statement.setString(3, "external-type");
            statement.setString(4, "application/octet-stream");
            statement.setString(5, "external-intent-" + meetingId);
            statement.setString(6, "external-binding-" + meetingId);
            statement.setString(7,
                    "sha256:0123456789abcdef0123456789abcdef"
                            + "0123456789abcdef0123456789abcdef");
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static CommitUnknownHarness commitUnknownOnce(
            DataSource delegate,
            CommitDisposition disposition) {
        AtomicBoolean injected = new AtomicBoolean();
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean recoveryReadOnly = new AtomicBoolean();
        AtomicBoolean recoveryRepeatableRead = new AtomicBoolean();
        AtomicBoolean recoveryReadOnlySql = new AtomicBoolean(true);
        AtomicInteger borrowed = new AtomicInteger();
        AtomicReference<Connection> uncertainConnection = new AtomicReference<>();
        AtomicReference<Connection> recoveryConnection = new AtomicReference<>();
        DataSource wrapped = (DataSource) Proxy.newProxyInstance(
                JdbcFinalizationCompletionUnitOfWorkIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) {
                        return method.invoke(delegate, args);
                    }
                    Connection connection = args == null || args.length == 0
                            ? delegate.getConnection()
                            : delegate.getConnection((String) args[0], (String) args[1]);
                    int ordinal = borrowed.incrementAndGet();
                    if (injected.compareAndSet(false, true)) {
                        uncertainConnection.set(connection);
                        return commitUnknownConnection(
                                connection, disposition, aborted, closed);
                    }
                    if (ordinal == 2) {
                        recoveryConnection.set(connection);
                    }
                    return recoveryTrackingConnection(
                            connection,
                            recoveryReadOnly,
                            recoveryRepeatableRead,
                            recoveryReadOnlySql);
                });
        return new CommitUnknownHarness(
                wrapped,
                aborted,
                closed,
                recoveryReadOnly,
                recoveryRepeatableRead,
                recoveryReadOnlySql,
                borrowed,
                uncertainConnection,
                recoveryConnection);
    }

    private static Connection commitUnknownConnection(
            Connection delegate,
            CommitDisposition disposition,
            AtomicBoolean aborted,
            AtomicBoolean closed) {
        return (Connection) Proxy.newProxyInstance(
                JdbcFinalizationCompletionUnitOfWorkIntegrationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("commit")) {
                        if (disposition == CommitDisposition.COMMITTED) {
                            delegate.commit();
                        } else {
                            delegate.rollback();
                        }
                        throw new SQLException("sensitive acknowledgement", "08006");
                    }
                    if (method.getName().equals("abort")) {
                        aborted.set(true);
                        delegate.abort((Executor) args[0]);
                        return null;
                    }
                    if (method.getName().equals("close")) {
                        closed.set(true);
                    }
                    return method.invoke(delegate, args);
                });
    }

    private static Connection recoveryTrackingConnection(
            Connection delegate,
            AtomicBoolean readOnly,
            AtomicBoolean repeatableRead,
            AtomicBoolean readOnlySql) {
        return (Connection) Proxy.newProxyInstance(
                JdbcFinalizationCompletionUnitOfWorkIntegrationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setReadOnly")
                            && args != null && args.length == 1
                            && Boolean.TRUE.equals(args[0])) {
                        readOnly.set(true);
                    }
                    if (method.getName().equals("setTransactionIsolation")
                            && args != null && args.length == 1
                            && ((Integer) args[0]) == Connection.TRANSACTION_REPEATABLE_READ) {
                        repeatableRead.set(true);
                    }
                    if ((method.getName().equals("prepareStatement")
                            || method.getName().equals("prepareCall"))
                            && args != null && args.length > 0
                            && args[0] instanceof String sql) {
                        String normalized = sql.stripLeading().toUpperCase(java.util.Locale.ROOT);
                        if (!readOnly.get()
                                || normalized.startsWith("INSERT")
                                || normalized.startsWith("UPDATE")
                                || normalized.startsWith("DELETE")
                                || normalized.startsWith("MERGE")
                                || normalized.startsWith("CALL")
                                || normalized.contains("FOR UPDATE")) {
                            readOnlySql.set(false);
                        }
                    }
                    return method.invoke(delegate, args);
                });
    }

    private static void assertUnknownConnectionDiscarded(CommitUnknownHarness harness) {
        assertTrue(harness.aborted().get());
        assertTrue(harness.closed().get());
        assertEquals(1, harness.borrowed().get());
    }

    private static void assertReadOnlyRecovery(CommitUnknownHarness harness) {
        assertEquals(2, harness.borrowed().get());
        assertTrue(harness.recoveryReadOnly().get());
        assertTrue(harness.recoveryRepeatableRead().get());
        assertTrue(harness.recoveryReadOnlySql().get(),
                "recovery must not issue DML, locking reads, or reads before readOnly=true");
        assertNotSame(
                harness.uncertainConnection().get(),
                harness.recoveryConnection().get(),
                "commit-unknown recovery must use a fresh physical connection");
    }

    private static DataSource allTemporaryShadows(
            DataSource delegate,
            String deploymentSchema) {
        List<String> tableNames = List.of(
                "meeting",
                "finalization_execution_fact",
                "durable_input_fact",
                "final_usage_fact",
                "workflow_step",
                "artifact",
                "meeting_manifest_revision",
                "usage_reservation");
        return (DataSource) Proxy.newProxyInstance(
                JdbcFinalizationCompletionUnitOfWorkIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) {
                        return method.invoke(delegate, args);
                    }
                    Connection connection = args == null || args.length == 0
                            ? delegate.getConnection()
                            : delegate.getConnection((String) args[0], (String) args[1]);
                    try (Statement statement = connection.createStatement()) {
                        for (String table : tableNames) {
                            statement.execute("CREATE TEMP TABLE " + table
                                    + " (LIKE " + quote(deploymentSchema) + "."
                                    + table + " INCLUDING ALL)");
                        }
                    }
                    return connection;
                });
    }

    private String createAdmissibleMeeting(String suffix) throws Exception {
        return createAdmissibleMeeting(
                suffix,
                profile(),
                Set.of("asr"),
                new CapabilityDescriptor(Set.of("asr")));
    }

    private String createAdmissibleMeeting(
            String suffix,
            ProductProfile productProfile,
            Set<String> requestedCapabilities,
            CapabilityDescriptor modelCapabilities) throws Exception {
        String meetingId = "mtg_completion_" + suffix.replace('-', '_');
        Set<String> grantedCapabilities = new HashSet<>(requestedCapabilities);
        grantedCapabilities.addAll(productProfile.requiredCapabilities());
        grantedCapabilities.addAll(productProfile.fallbackCapabilities().values());
        MeetingGrant grant = new MeetingGrant(
                "grant-" + suffix,
                DEPLOYMENT,
                SUBJECT,
                productProfile.id(),
                productProfile.revision(),
                requestedCapabilities,
                grantedCapabilities,
                3_600_000,
                RESERVED_AT,
                RESERVED_AT.plusSeconds(3_600));
        UsageReservation reservation = UsageReservation.reserved(
                "reservation-" + suffix,
                grant,
                meetingId,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                RESERVED_AT.plusSeconds(1),
                RESERVED_AT.plusSeconds(3_000));
        UsageOperationResult reserve = UsageOperationResult.reserve(
                DEPLOYMENT,
                SUBJECT,
                "reserve-key-" + suffix,
                "reserve-digest-" + suffix,
                grant.expiresAt(),
                AuthorizedProfileSnapshot.from(grant),
                reservation);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcUsageReservationStore.insertReservation(connection, reservation);
            assertTrue(JdbcUsageReservationStore.insertOperation(
                    connection, reserve, RESERVED_AT.plusSeconds(1)));
            connection.commit();
        }
        OwnedMeetingCreationUnitOfWork.Result created =
                new JdbcOwnedMeetingCreationUnitOfWork(
                        dataSource, Clock.fixed(CREATED_AT, ZoneOffset.UTC))
                        .create(new OwnedMeetingCreationUnitOfWork.CreateRequest(
                                DEPLOYMENT,
                                SUBJECT,
                                meetingId,
                                "session-" + suffix,
                                "create-key-" + suffix,
                                "create-digest-" + suffix,
                                reserve));
        assertTrue(created instanceof OwnedMeetingCreationUnitOfWork.Success);

        var started = new JdbcMeetingStartExecutionUnitOfWork(
                dataSource, new CapabilityPlanner()).start(
                new MeetingStartExecutionUnitOfWork.StartRequest(
                        DEPLOYMENT,
                        SUBJECT,
                        meetingId,
                        STARTED_AT,
                        "Completion test",
                        productProfile,
                        requestedCapabilities,
                        modelCapabilities,
                        DeploymentLimits.NONE));
        assertTrue(started instanceof MeetingStartExecutionUnitOfWork.Success);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcMeetingRepository.VersionedMeeting locked =
                    JdbcMeetingRepository.findVersionedForUpdate(connection, meetingId)
                            .orElseThrow();
            MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(locked.snapshot());
            aggregate.apply(MeetingEvent.START, STARTED_AT.plusSeconds(1));
            aggregate.apply(MeetingEvent.STOP_REQUESTED, STOPPED_AT);
            JdbcMeetingRepository.updateOwnedExisting(
                    connection, locked, aggregate.snapshot());
            assertTrue(JdbcMeetingFinalizationStore.requestInputCompletion(
                    connection, meetingId) instanceof MeetingFinalizationStore.Applied);
            connection.commit();
        }
        return meetingId;
    }

    private static ProductProfile profile() {
        return new ProductProfile(
                "profile",
                1,
                Set.of("asr"),
                Set.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("asr", List.of("caption")),
                Map.of("asr", List.of("transcript.final")));
    }

    private static ProductProfile twoArtifactProfile() {
        return new ProductProfile(
                "completion-full-profile",
                1,
                Set.of("transcript.final"),
                Set.of("summary.structured"),
                Map.of("summary.structured", Set.of("transcript.final")),
                Map.of(),
                Map.of(),
                Map.of(
                        "transcript.final", List.of("transcript"),
                        "summary.structured", List.of("summary")),
                Map.of(
                        "transcript.final", List.of("transcript.final"),
                        "summary.structured", List.of("summary.structured")));
    }

    private static ProductProfile warningProfile() {
        return new ProductProfile(
                "completion-warning-profile",
                1,
                Set.of("transcript.final"),
                Set.of("speaker.identity", "speaker.session"),
                Map.of(),
                Map.of(
                        "speaker.identity",
                        ProductProfile.MissingCapabilityPolicy.USE_FALLBACK),
                Map.of("speaker.identity", "speaker.session"),
                Map.of("transcript.final", List.of("transcript")),
                Map.of("transcript.final", List.of("transcript.final")));
    }

    private static FinalizationAdmissionUnitOfWork.AdmissionRequest admissionRequest(
            String meetingId) {
        return new FinalizationAdmissionUnitOfWork.AdmissionRequest(
                DEPLOYMENT,
                SUBJECT,
                meetingId,
                new FinalizationInputFact(
                        meetingId,
                        "binding-" + meetingId,
                        42,
                        60_000,
                        "zh-CN",
                        List.of("en-US"),
                        ACCEPTED_AT,
                        "sha256:input-" + meetingId));
    }

    private void commitReservation(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE usage_reservation
                        SET status='COMMITTED', actual_amount=60000,
                            committed_at=?, updated_at=?, version=version+1
                      WHERE reservation_id=(
                            SELECT usage_reservation_id
                              FROM meeting
                             WHERE meeting_id=?)
                        AND status='RESERVED' AND version=1
                     """)) {
            java.sql.Timestamp committedAt = java.sql.Timestamp.from(
                    ACCEPTED_AT.plusSeconds(1));
            statement.setTimestamp(1, committedAt);
            statement.setTimestamp(2, committedAt);
            statement.setString(3, meetingId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void assertTerminal(
            String meetingId,
            String meetingState,
            String manifestState,
            String workflowState,
            int attempt) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT m.state, m.manifest_state, m.aggregate_version,
                            w.status, w.attempt_count, w.lease_owner,
                            w.lease_token, w.lease_until
                       FROM meeting m
                       JOIN workflow_step w ON w.meeting_id=m.meeting_id
                      WHERE m.meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(meetingState, rows.getString("state"));
                assertEquals(manifestState, rows.getString("manifest_state"));
                assertEquals("FINALIZING".equals(meetingState) ? 4 : 5,
                        rows.getLong("aggregate_version"));
                assertEquals(workflowState, rows.getString("status"));
                assertEquals(attempt, rows.getInt("attempt_count"));
                if (!"LEASED".equals(workflowState)) {
                    assertEquals(null, rows.getString("lease_owner"));
                    assertEquals(null, rows.getString("lease_token"));
                    assertEquals(null, rows.getTimestamp("lease_until"));
                }
                assertFalse(rows.next());
            }
        }
    }

    private int count(String sql, String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static FinalizationCompletionUnitOfWork.Success success(
            FinalizationCompletionUnitOfWork.Result result) {
        assertTrue(result instanceof FinalizationCompletionUnitOfWork.Success,
                () -> "expected success but got " + result);
        return (FinalizationCompletionUnitOfWork.Success) result;
    }

    private static FinalizationCompletionUnitOfWork.Failure failure(
            FinalizationCompletionUnitOfWork.Result result) {
        assertTrue(result instanceof FinalizationCompletionUnitOfWork.Failure,
                () -> "expected failure but got " + result);
        return (FinalizationCompletionUnitOfWork.Failure) result;
    }

    private void applyMigration(String filename) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(readMigration(filename));
        }
    }

    private static String readMigration(String filename) throws IOException {
        Path[] candidates = {
                Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("migration not found: " + filename);
    }

    private static PGSimpleDataSource postgres(String currentSchema) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(required("REFERENCE_TEST_DATABASE_URL"));
        source.setUser(required("REFERENCE_TEST_DATABASE_USER"));
        source.setPassword(required("REFERENCE_TEST_DATABASE_PASSWORD"));
        if (currentSchema != null) {
            source.setCurrentSchema(currentSchema);
        }
        return source;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        FinalizationCompletionUnitOfWork.Result execute() throws Exception;
    }

    private enum CommitDisposition {
        COMMITTED,
        ROLLED_BACK
    }

    private record CommitUnknownHarness(
            DataSource dataSource,
            AtomicBoolean aborted,
            AtomicBoolean closed,
            AtomicBoolean recoveryReadOnly,
            AtomicBoolean recoveryRepeatableRead,
            AtomicBoolean recoveryReadOnlySql,
            AtomicInteger borrowed,
            AtomicReference<Connection> uncertainConnection,
            AtomicReference<Connection> recoveryConnection) {
    }

    private static final class CapturingHandler extends Handler {
        private final StringBuilder content = new StringBuilder();

        @Override
        public void publish(LogRecord record) {
            if (!isLoggable(record)) {
                return;
            }
            content.append(record.getMessage()).append('\n');
            if (record.getParameters() != null) {
                for (Object parameter : record.getParameters()) {
                    content.append(String.valueOf(parameter)).append('\n');
                }
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        String content() {
            return content.toString();
        }
    }

    private static final class InjectedCompletionFailure extends RuntimeException {
    }

    private record Prepared(
            String meetingId,
            FinalizationCheckpoint checkpoint,
            WorkflowStepStore.ClaimedStep claimed,
            List<ArtifactDescriptor> descriptors) {
    }
}
