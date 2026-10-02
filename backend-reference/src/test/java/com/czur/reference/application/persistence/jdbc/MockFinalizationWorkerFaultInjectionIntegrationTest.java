package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicy;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicyConfig;
import com.czur.reference.application.finalization.DeterministicMockFinalizationPlanner;
import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.finalization.FinalizationTerminalFailureIdentity;
import com.czur.reference.application.finalization.MockFinalizationWorker;
import com.czur.reference.application.port.FinalizationAdmissionUnitOfWork;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.CompletionRequest;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.FailureRequest;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.MeetingFinalizationStore;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.application.port.artifact.ArtifactStoreException;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.CancelReservationRequest;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.protocol.json.FinalizationCheckpointJson;
import com.czur.reference.protocol.json.FinalizationExecutionFactJson;
import com.czur.reference.protocol.json.MeetingManifestJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-T14-09-09 Mock 最终收口 Worker 的联合故障与唯一性门禁。
 *
 * <p>本测试把已经分别验收的真实 JDBC Admission、Workflow、reference-dev 权益、
 * Completion 与持久文件 Artifact Store 串成同一条执行链。故障探针只包裹公共端口，
 * 不绕过 Worker 编排，也不改变生产实现。每个用例使用独立 PostgreSQL schema 和
 * {@link TempDir} 文件根；测试本身不创建后台调度、轮询器或常驻线程。</p>
 *
 * <p>门禁刻意区分“外部调用可能重放”和“权威事实必须唯一”：Artifact、权益和
 * Completion 在确认未知或 Worker 崩溃后可以用同一稳定 identity 再调用，但最终
 * receipt、Artifact metadata、commit usage operation、Manifest revision、Meeting 与
 * Workflow 只能各形成一个相互一致的赢家。所有注入异常正文都带私密诱饵，日志断言
 * 证明编排器只输出稳定 reason code。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class MockFinalizationWorkerFaultInjectionIntegrationTest {
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
    private static final String DEPLOYMENT = "worker-gate-deployment";
    private static final String SUBJECT = "worker-gate-subject";
    private static final Instant RESERVED_AT = Instant.parse("2026-09-29T02:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-09-29T02:01:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-09-29T02:01:05.123456Z");
    private static final Instant STOPPED_AT = Instant.parse("2026-09-29T02:09:59.123456Z");
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-29T02:10:00.123456Z");
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final long ARTIFACT_LIMIT = 1024 * 1024;
    private static final String PRIVATE_DIAGNOSTIC =
            "private://token@artifact/root/meeting-transcript";

    @TempDir
    Path temporaryDirectory;

    private PGSimpleDataSource admin;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        rejectReservedDatabaseEndpoint();
        admin = postgres(null);
        schema = "worker_fault_gate_" + UUID.randomUUID().toString().replace("-", "");
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
    void artifactCreateWriteAndCompleteCommitUnknownRecoverWithOneTerminalFactEach()
            throws Exception {
        for (ArtifactFaultPhase phase : ArtifactFaultPhase.values()) {
            GateFixture fixture = prepare("artifact-" + phase.name().toLowerCase());
            PhaseCommitUnknownArtifactStore faulting =
                    new PhaseCommitUnknownArtifactStore(fixture.artifacts(), phase);

            MockFinalizationWorker.ItemResult first = fixture.worker(
                    "artifact-worker-a", faulting, fixture.entitlement(),
                    fixture.completion(), 3).runOnce().items().getFirst();
            if (phase == ArtifactFaultPhase.COMPLETE) {
                assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED, first.status());
                assertReadyUnique(fixture, 0);
            } else {
                assertEquals(MockFinalizationWorker.ItemStatus.RETRY_SCHEDULED,
                        first.status());
                assertEquals("ARTIFACT_COMMIT_UNKNOWN", first.reasonCode());
                fixture.clock().advance(RETRY_DELAY);
                MockFinalizationWorker.ItemResult recovered = fixture.worker(
                        "artifact-worker-b", faulting, fixture.entitlement(),
                        fixture.completion(), 3).runOnce().items().getFirst();
                assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED,
                        recovered.status());
                assertReadyUnique(fixture, 1);
            }
            assertTrue(faulting.injected(), "fault phase was not reached: " + phase);
            assertArtifactFilesUnique(fixture);
        }
    }

    @Test
    void crashesAfterArtifactIntentStageAndReadyRecoverFromTheSameStableIdentity()
            throws Exception {
        for (ArtifactFaultPhase phase : ArtifactFaultPhase.values()) {
            GateFixture fixture = prepare("artifact-crash-" + phase.name().toLowerCase());
            CrashAfterArtifactPhaseStore crashing =
                    new CrashAfterArtifactPhaseStore(fixture.artifacts(), phase);

            assertThrows(CrashSignal.class, () -> fixture.worker(
                    "artifact-crash-worker-a", crashing, fixture.entitlement(),
                    fixture.completion(), 3).runOnce());
            assertEquals(0, countCommitOperations(fixture));

            fixture.clock().advance(LEASE_DURATION);
            MockFinalizationWorker.ItemResult recovered = fixture.worker(
                    "artifact-crash-worker-b", fixture.artifacts(),
                    fixture.entitlement(), fixture.completion(), 3)
                    .runOnce().items().getFirst();
            assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED,
                    recovered.status());
            assertReadyUnique(fixture, 0);
            assertArtifactFilesUnique(fixture);
        }
    }

    @Test
    void entitlementCommittedButRetryableReplyReplaysSameOperationAndCompletesOnce()
            throws Exception {
        GateFixture fixture = prepare("entitlement-retry-replay");
        CommitUnknownDataSource commitUnknown = commitUnknownOnce(dataSource);
        FaultingEntitlement retryAfterCommit = new FaultingEntitlement(
                fixture.trackExpectedCommit(new JdbcReferenceDevEntitlementProvider(
                        commitUnknown.dataSource(), policy(fixture.clock()), fixture.clock())),
                EntitlementFault.NONE);

        MockFinalizationWorker.ItemResult first = fixture.worker(
                "entitlement-worker-a", fixture.artifacts(), retryAfterCommit,
                fixture.completion(), 3).runOnce().items().getFirst();
        assertEquals(MockFinalizationWorker.ItemStatus.RETRY_SCHEDULED, first.status());
        assertEquals(1, countCommitOperations(fixture));

        fixture.clock().advance(RETRY_DELAY);
        MockFinalizationWorker.ItemResult second = fixture.worker(
                "entitlement-worker-b", fixture.artifacts(), retryAfterCommit,
                fixture.completion(), 3).runOnce().items().getFirst();
        assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED, second.status());
        assertEquals(2, retryAfterCommit.commitCalls());
        assertEquals(1, retryAfterCommit.distinctRequests());
        assertTrue(commitUnknown.committed().get());
        assertTrue(commitUnknown.closed().get());
        assertReadyUnique(fixture, 1);
        assertArtifactFilesUnique(fixture);
    }

    @Test
    void entitlementCommitAckLostAtConfiguredLastAttemptKeepsCommittedUsageAndFailsOnce()
            throws Exception {
        GateFixture fixture = prepare("entitlement-ack-lost-last-attempt");
        CommitUnknownDataSource commitUnknown = commitUnknownOnce(dataSource);
        FaultingEntitlement committedButUnacknowledged = new FaultingEntitlement(
                fixture.trackExpectedCommit(new JdbcReferenceDevEntitlementProvider(
                        commitUnknown.dataSource(), policy(fixture.clock()), fixture.clock())),
                EntitlementFault.NONE);

        MockFinalizationWorker.ItemResult terminal = fixture.worker(
                "entitlement-last-attempt-worker",
                fixture.artifacts(),
                committedButUnacknowledged,
                fixture.completion(),
                1).runOnce().items().getFirst();

        /*
         * 用量提交是独立权威事实：Provider 已经提交后丢失 acknowledgement，Worker
         * 不能撤销或伪造未提交。配置又明确不给下一次自动尝试，因此 Completion
         * 以同一冻结请求发布 FAILED/DEAD；这不是“重复计量”，数据库仍只有一条
         * commit operation。门禁把这个边界写死，防止未来把已提交用量反转。
         */
        assertEquals(MockFinalizationWorker.ItemStatus.FAILED_DEAD, terminal.status());
        assertEquals(1, committedButUnacknowledged.commitCalls());
        assertTrue(commitUnknown.committed().get());
        assertTrue(commitUnknown.closed().get());
        assertFailedUnique(
                fixture, 1, fixture.checkpoint().artifacts().size(), 1);
        assertArtifactFilesUnique(fixture);
    }

    @Test
    void crashAfterEntitlementCommitReplaysTheCommittedOperationWithoutDoubleUsage()
            throws Exception {
        GateFixture fixture = prepare("entitlement-commit-crash");
        CrashAfterEntitlementCommit crashing = new CrashAfterEntitlementCommit(
                fixture.entitlement());

        assertThrows(CrashSignal.class, () -> fixture.worker(
                "entitlement-crash-worker-a", fixture.artifacts(), crashing,
                fixture.completion(), 3).runOnce());
        assertEquals(1, countCommitOperations(fixture));

        fixture.clock().advance(LEASE_DURATION);
        MockFinalizationWorker.ItemResult recovered = fixture.worker(
                "entitlement-crash-worker-b", fixture.artifacts(),
                fixture.entitlement(), fixture.completion(), 3)
                .runOnce().items().getFirst();
        assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED, recovered.status());
        assertEquals(1, crashing.commitCalls());
        assertReadyUnique(fixture, 0);
        assertArtifactFilesUnique(fixture);
    }

    @Test
    void nonRetryableEntitlementFailureAtomicallyPublishesFailedDeadOnlyOnce()
            throws Exception {
        GateFixture fixture = prepare("entitlement-terminal");
        FaultingEntitlement terminal = new FaultingEntitlement(
                fixture.entitlement(), EntitlementFault.NON_RETRYABLE);

        MockFinalizationWorker.ItemResult result = fixture.worker(
                "entitlement-terminal-worker", fixture.artifacts(), terminal,
                fixture.completion(), 3).runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.FAILED_DEAD, result.status());
        assertFailedUnique(fixture, 1, fixture.checkpoint().artifacts().size(), 0);
        assertTrue(fixture.worker(
                "late-healthy-worker", fixture.artifacts(), fixture.entitlement(),
                fixture.completion(), 3).runOnce().idle());
        assertFailedUnique(fixture, 1, fixture.checkpoint().artifacts().size(), 0);
    }

    @Test
    void secondArtifactFailurePreservesOneReadyArtifactAsAnExactManifestSubset()
            throws Exception {
        GateFixture fixture = prepare("partial-artifact-terminal");
        FailSecondArtifactStore partial = new FailSecondArtifactStore(
                fixture.artifacts());

        MockFinalizationWorker.ItemResult result = fixture.worker(
                "partial-artifact-worker", partial, fixture.entitlement(),
                fixture.completion(), 3).runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.FAILED_DEAD, result.status());
        assertFailedUnique(fixture, 1, 1, 0);
        assertPublishedArtifactSubset(fixture, 1);
    }

    @Test
    void successOutcomeUnknownCrossLeaseDoesNotRepeatArtifactsOrEntitlement()
            throws Exception {
        GateFixture fixture = prepare("completion-success-cross-lease");
        UnresolvedOnceCompletion unresolved = new UnresolvedOnceCompletion(
                fixture.completion(), TerminalKind.SUCCESS);
        CountingArtifactStore countedArtifacts = new CountingArtifactStore(
                fixture.artifacts());
        FaultingEntitlement countedEntitlement = new FaultingEntitlement(
                fixture.entitlement(), EntitlementFault.NONE);

        MockFinalizationWorker.ItemResult first = fixture.worker(
                "completion-success-a", countedArtifacts, countedEntitlement,
                unresolved, 3).runOnce().items().getFirst();
        assertEquals(MockFinalizationWorker.ItemStatus.OUTCOME_UNRESOLVED,
                first.status());
        assertEquals(1, unresolved.executeCalls());
        assertEquals(1, unresolved.recoverCalls());
        int artifactCalls = countedArtifacts.businessCalls();
        int entitlementCalls = countedEntitlement.commitCalls();

        fixture.clock().advance(LEASE_DURATION);
        MockFinalizationWorker.ItemResult recovered = fixture.worker(
                "completion-success-b",
                new RejectingArtifactStore(fixture.artifacts()),
                new RejectingEntitlement(), fixture.completion(), 3)
                .runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED, recovered.status());
        assertEquals(artifactCalls, countedArtifacts.businessCalls());
        assertEquals(entitlementCalls, countedEntitlement.commitCalls());
        assertReadyUnique(fixture, 0);
        assertArtifactFilesUnique(fixture);
    }

    @Test
    void failureOutcomeUnknownCrossLeasePreservesFrozenReadySubsetAndDeadIsUnique()
            throws Exception {
        GateFixture fixture = prepare("completion-failure-cross-lease");
        FaultingEntitlement terminal = new FaultingEntitlement(
                fixture.entitlement(), EntitlementFault.NON_RETRYABLE);
        UnresolvedOnceCompletion unresolved = new UnresolvedOnceCompletion(
                fixture.completion(), TerminalKind.FAILURE);

        MockFinalizationWorker.ItemResult first = fixture.worker(
                "completion-failure-a", fixture.artifacts(), terminal,
                unresolved, 3).runOnce().items().getFirst();
        assertEquals(MockFinalizationWorker.ItemStatus.OUTCOME_UNRESOLVED,
                first.status());
        assertEquals(1, unresolved.executeCalls());
        assertEquals(1, unresolved.recoverCalls());

        fixture.clock().advance(LEASE_DURATION);
        MockFinalizationWorker.ItemResult recovered = fixture.worker(
                "completion-failure-b",
                new RejectingArtifactStore(fixture.artifacts()),
                new RejectingEntitlement(), fixture.completion(), 3)
                .runOnce().items().getFirst();

        assertEquals(MockFinalizationWorker.ItemStatus.FAILED_DEAD, recovered.status());
        assertEquals(1, terminal.commitCalls());
        assertFailedUnique(fixture, 1, fixture.checkpoint().artifacts().size(), 0);
    }

    @Test
    void everyRealCompletionWriteWindowRollsBackAndRecoversForSuccessAndFailure()
            throws Exception {
        AtomicInteger ordinal = new AtomicInteger();
        for (JdbcFinalizationCompletionUnitOfWork.FailurePoint point
                : JdbcFinalizationCompletionUnitOfWork.FailurePoint.values()) {
            GateFixture success = prepare(
                    "completion-window-success-" + ordinal.incrementAndGet());
            AtomicBoolean successHit = new AtomicBoolean();
            FinalizationCompletionUnitOfWork successFailureProbe =
                    new JdbcFinalizationCompletionUnitOfWork(dataSource, current -> {
                        if (current == point) {
                            successHit.set(true);
                            throw new InjectedCompletionWindowFailure();
                        }
                    });
            MockFinalizationWorker.ItemResult successStopped = success.worker(
                    "completion-window-success-a-" + ordinal.get(),
                    success.artifacts(), success.entitlement(),
                    successFailureProbe, 3).runOnce().items().getFirst();
            assertTrue(successHit.get(), "success point was not reached: " + point);
            assertEquals(MockFinalizationWorker.ItemStatus.INCONSISTENT,
                    successStopped.status());
            assertEquals("TERMINALIZATION_INTERNAL", successStopped.reasonCode());
            assertPreTerminalUnique(success, 1);

            success.clock().advance(LEASE_DURATION);
            MockFinalizationWorker.ItemResult successRecovered = success.worker(
                    "completion-window-success-b-" + ordinal.get(),
                    new RejectingArtifactStore(success.artifacts()),
                    new RejectingEntitlement(), success.completion(), 3)
                    .runOnce().items().getFirst();
            assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED,
                    successRecovered.status());
            assertReadyUnique(success, 0);

            GateFixture failure = prepare(
                    "completion-window-failure-" + ordinal.incrementAndGet());
            AtomicBoolean failureHit = new AtomicBoolean();
            FinalizationCompletionUnitOfWork failureProbe =
                    new JdbcFinalizationCompletionUnitOfWork(dataSource, current -> {
                        if (current == point) {
                            failureHit.set(true);
                            throw new InjectedCompletionWindowFailure();
                        }
                    });
            FaultingEntitlement terminalEntitlement = new FaultingEntitlement(
                    failure.entitlement(), EntitlementFault.NON_RETRYABLE);
            MockFinalizationWorker.ItemResult failureStopped = failure.worker(
                    "completion-window-failure-a-" + ordinal.get(),
                    failure.artifacts(), terminalEntitlement,
                    failureProbe, 3).runOnce().items().getFirst();
            assertTrue(failureHit.get(), "failure point was not reached: " + point);
            assertEquals(MockFinalizationWorker.ItemStatus.INCONSISTENT,
                    failureStopped.status());
            assertEquals("TERMINALIZATION_INTERNAL", failureStopped.reasonCode());
            assertPreTerminalUnique(failure, 0);

            failure.clock().advance(LEASE_DURATION);
            MockFinalizationWorker.ItemResult failureRecovered = failure.worker(
                    "completion-window-failure-b-" + ordinal.get(),
                    new RejectingArtifactStore(failure.artifacts()),
                    new RejectingEntitlement(), failure.completion(), 3)
                    .runOnce().items().getFirst();
            assertEquals(MockFinalizationWorker.ItemStatus.FAILED_DEAD,
                    failureRecovered.status());
            assertFailedUnique(
                    failure, 1, failure.checkpoint().artifacts().size(), 0);
        }
    }

    @Test
    void crashesAfterDurableMarkerAndAfterCompletionCommitCannotDuplicateOrReverse()
            throws Exception {
        GateFixture markerFixture = prepare("crash-after-marker");
        CrashCompletion markerCrash = new CrashCompletion(
                markerFixture.completion(), TerminalKind.SUCCESS,
                CrashCompletionPoint.BEFORE_DELEGATE);
        assertThrows(CrashSignal.class, () -> markerFixture.worker(
                "marker-crash-worker-a", markerFixture.artifacts(),
                markerFixture.entitlement(), markerCrash, 3).runOnce());
        assertEquals(1, markerCrash.completeCalls());
        markerFixture.clock().advance(LEASE_DURATION);
        MockFinalizationWorker.ItemResult markerRecovered = markerFixture.worker(
                "marker-crash-worker-b",
                new RejectingArtifactStore(markerFixture.artifacts()),
                new RejectingEntitlement(), markerFixture.completion(), 3)
                .runOnce().items().getFirst();
        assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED,
                markerRecovered.status());
        assertReadyUnique(markerFixture, 0);

        GateFixture commitFixture = prepare("crash-after-completion-commit");
        CrashCompletion commitCrash = new CrashCompletion(
                commitFixture.completion(), TerminalKind.SUCCESS,
                CrashCompletionPoint.AFTER_DELEGATE);
        assertThrows(CrashSignal.class, () -> commitFixture.worker(
                "completion-commit-crash-worker", commitFixture.artifacts(),
                commitFixture.entitlement(), commitCrash, 3).runOnce());
        assertEquals(1, commitCrash.completeCalls());
        assertReadyUnique(commitFixture, 0);
        assertTrue(commitFixture.worker(
                "completion-commit-late-worker", commitFixture.artifacts(),
                commitFixture.entitlement(), commitFixture.completion(), 3)
                .runOnce().idle());
        assertReadyUnique(commitFixture, 0);

        GateFixture failureFixture = prepare("crash-after-failure-commit");
        CrashCompletion failureCommitCrash = new CrashCompletion(
                failureFixture.completion(), TerminalKind.FAILURE,
                CrashCompletionPoint.AFTER_DELEGATE);
        FaultingEntitlement terminalEntitlement = new FaultingEntitlement(
                failureFixture.entitlement(), EntitlementFault.NON_RETRYABLE);
        assertThrows(CrashSignal.class, () -> failureFixture.worker(
                "failure-commit-crash-worker", failureFixture.artifacts(),
                terminalEntitlement, failureCommitCrash, 3).runOnce());
        assertEquals(1, failureCommitCrash.failCalls());
        assertFailedUnique(
                failureFixture, 1, failureFixture.checkpoint().artifacts().size(), 0);
        assertTrue(failureFixture.worker(
                "failure-commit-late-worker", failureFixture.artifacts(),
                failureFixture.entitlement(), failureFixture.completion(), 3)
                .runOnce().idle());
        assertFailedUnique(
                failureFixture, 1, failureFixture.checkpoint().artifacts().size(), 0);
    }

    @Test
    void twoWorkersOnTheSameKeyHaveOneClaimWinnerAndOneExternalExecution()
            throws Exception {
        GateFixture fixture = prepare("two-workers");
        BlockingArtifactStore blocking = new BlockingArtifactStore(fixture.artifacts());
        FaultingEntitlement countedEntitlement = new FaultingEntitlement(
                fixture.entitlement(), EntitlementFault.NONE);
        MockFinalizationWorker first = fixture.worker(
                "concurrent-worker-a", blocking, countedEntitlement,
                fixture.completion(), 3);
        MockFinalizationWorker second = fixture.worker(
                "concurrent-worker-b", blocking, countedEntitlement,
                fixture.completion(), 3);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MockFinalizationWorker.RunResult> winner =
                    executor.submit(first::runOnce);
            assertTrue(blocking.awaitEntered(), "first worker did not enter artifact call");
            MockFinalizationWorker.RunResult loser = second.runOnce();
            assertTrue(loser.idle(), "second worker must not share the leased key");
            blocking.release();
            MockFinalizationWorker.RunResult completed = winner.get(20, TimeUnit.SECONDS);
            assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED,
                    completed.items().getFirst().status());
        } finally {
            blocking.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, countedEntitlement.commitCalls());
        assertReadyUnique(fixture, 0);
        assertArtifactFilesUnique(fixture);
    }

    @Test
    void expiredLeaseIsTakenOverAndEveryOldWorkerWriteIsFenced()
            throws Exception {
        GateFixture fixture = prepare("expired-lease-takeover");
        JdbcWorkflowStepStore jdbcWorkflow = new JdbcWorkflowStepStore(dataSource);
        CapturingWorkflowStore oldWorkerStore = new CapturingWorkflowStore(jdbcWorkflow);
        BlockingAfterCommitEntitlement oldEntitlement =
                new BlockingAfterCommitEntitlement(fixture.entitlement());
        BlockingAfterCommitEntitlement takeoverEntitlement =
                new BlockingAfterCommitEntitlement(fixture.entitlement());
        RejectingCompletion oldCompletion = new RejectingCompletion();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<MockFinalizationWorker.RunResult> oldRun = null;
        Future<MockFinalizationWorker.RunResult> takeoverRun = null;
        try {
            oldRun = executor.submit(() -> fixture.worker(
                    "expired-worker-a",
                    oldWorkerStore,
                    fixture.artifacts(),
                    oldEntitlement,
                    oldCompletion,
                    3).runOnce());
            assertTrue(oldEntitlement.awaitCommitted(),
                    "old worker did not reach the post-entitlement gate");
            assertEquals(1, countCommitOperations(fixture));
            assertArtifactFilesUnique(fixture);

            WorkflowStepStore.LeaseToken oldLease = oldWorkerStore.lease();
            assertEquals(fixture.clock().instant(), oldLease.claimedAt());
            fixture.clock().advance(LEASE_DURATION);
            Instant takeoverAt = fixture.clock().instant();
            takeoverRun = executor.submit(() -> fixture.worker(
                    "expired-worker-b",
                    jdbcWorkflow,
                    fixture.artifacts(),
                    takeoverEntitlement,
                    fixture.completion(),
                    3).runOnce());
            assertTrue(takeoverEntitlement.awaitCommitted(),
                    "takeover worker did not reach its post-entitlement gate");
            assertActiveLease(
                    fixture,
                    "expired-worker-b",
                    oldLease.leaseVersion() + 1,
                    takeoverAt);

            WorkflowStepStore.PersistLeaseMarkerRequest oldMarker =
                    new WorkflowStepStore.PersistLeaseMarkerRequest(
                            oldLease,
                            oldLease.claimedAt().plusSeconds(1),
                            null,
                            null,
                            "STALE_TERMINAL_MARKER",
                            "STALE_TERMINAL_MARKER_SUMMARY");
            assertOldLeaseFenced(
                    jdbcWorkflow, oldLease, oldMarker, takeoverAt,
                    WorkflowStepStore.RenewStatus.NOT_OWNER,
                    WorkflowStepStore.FailStatus.NOT_OWNER,
                    WorkflowStepStore.CompleteStatus.NOT_OWNER,
                    WorkflowStepStore.PersistLeaseMarkerStatus.NOT_OWNER);

            takeoverEntitlement.release();
            MockFinalizationWorker.ItemResult recovered = takeoverRun.get(
                    20, TimeUnit.SECONDS).items().getFirst();
            assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED,
                    recovered.status());
            CompletionRequest takeoverCompletion = fixture.completion().lastCompletion();
            assertNotNull(takeoverCompletion);
            assertEquals("expired-worker-b", takeoverCompletion.lease().owner());
            assertEquals(oldLease.leaseVersion() + 1,
                    takeoverCompletion.lease().leaseVersion());
            assertEquals(takeoverAt, takeoverCompletion.lease().claimedAt());

            assertOldLeaseFenced(
                    jdbcWorkflow, oldLease, oldMarker, takeoverAt.plusSeconds(1),
                    WorkflowStepStore.RenewStatus.ALREADY_COMPLETED,
                    WorkflowStepStore.FailStatus.ALREADY_COMPLETED,
                    WorkflowStepStore.CompleteStatus.ALREADY_COMPLETED,
                    WorkflowStepStore.PersistLeaseMarkerStatus.ALREADY_COMPLETED);

            oldEntitlement.release();
            MockFinalizationWorker.ItemResult stopped = oldRun.get(
                    20, TimeUnit.SECONDS).items().getFirst();
            assertEquals(MockFinalizationWorker.ItemStatus.LEASE_EXPIRED,
                    stopped.status());
            assertEquals("LEASE_EXPIRED", stopped.reasonCode());
            assertEquals(0, oldCompletion.calls());
            assertReadyUnique(fixture, 0);
            assertArtifactFilesUnique(fixture);
        } finally {
            oldEntitlement.release();
            takeoverEntitlement.release();
            if (oldRun != null && !oldRun.isDone()) {
                oldRun.cancel(true);
            }
            if (takeoverRun != null && !takeoverRun.isDone()) {
                takeoverRun.cancel(true);
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void assertOldLeaseFenced(
            WorkflowStepStore store,
            WorkflowStepStore.LeaseToken oldLease,
            WorkflowStepStore.PersistLeaseMarkerRequest oldMarker,
            Instant operationAt,
            WorkflowStepStore.RenewStatus renewStatus,
            WorkflowStepStore.FailStatus failStatus,
            WorkflowStepStore.CompleteStatus completeStatus,
            WorkflowStepStore.PersistLeaseMarkerStatus markerStatus) {
        assertEquals(renewStatus,
                store.renew(oldLease, operationAt, LEASE_DURATION).status());
        assertEquals(failStatus,
                store.fail(
                        oldLease,
                        operationAt,
                        operationAt.plus(RETRY_DELAY),
                        "STALE_WORKER",
                        "STALE_WORKER_FENCED",
                        3).status());
        assertEquals(completeStatus,
                store.complete(oldLease, operationAt).status());
        assertEquals(markerStatus,
                store.persistLeaseMarker(oldMarker).status());
        assertEquals(markerStatus,
                store.recoverLeaseMarker(oldMarker).status());
    }

    private void assertActiveLease(
            GateFixture fixture,
            String expectedOwner,
            long expectedLeaseVersion,
            Instant expectedClaimedAt) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT status, attempt_count, lease_owner, lease_token,
                            lease_until, lease_version,
                            updated_at AS claimed_at,
                            updated_at AS workflow_updated_at,
                            next_attempt_at, error_code, error_message
                       FROM workflow_step
                      WHERE meeting_id=?
                     """)) {
            statement.setString(1, fixture.meetingId());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("LEASED", rows.getString("status"));
                assertEquals(0, rows.getInt("attempt_count"));
                assertEquals(expectedOwner, rows.getString("lease_owner"));
                assertNotNull(rows.getString("lease_token"));
                assertEquals(expectedClaimedAt.plus(LEASE_DURATION),
                        rows.getTimestamp("lease_until").toInstant());
                assertEquals(expectedLeaseVersion, rows.getLong("lease_version"));
                assertEquals(expectedClaimedAt,
                        rows.getTimestamp("claimed_at").toInstant());
                assertEquals(expectedClaimedAt,
                        rows.getTimestamp("workflow_updated_at").toInstant());
                assertEquals(null, rows.getTimestamp("next_attempt_at"));
                assertEquals(null, rows.getString("error_code"));
                assertEquals(null, rows.getString("error_message"));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void ordinaryRetriesReachDeadCannotReverseAndDiagnosticsStayRedacted()
            throws Exception {
        CapturingHandler logs = new CapturingHandler();
        Logger logger = Logger.getLogger("com.czur.reference");
        boolean previousParentHandlers = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        logger.addHandler(logs);
        GateFixture fixture = null;
        try {
            fixture = prepare("retry-to-dead-redacted");
            AlwaysUnavailableArtifactStore unavailable =
                    new AlwaysUnavailableArtifactStore(fixture.artifacts());
            for (int attempt = 0; attempt < 3; attempt++) {
                MockFinalizationWorker.ItemResult item = fixture.worker(
                        "retry-worker-" + attempt, unavailable,
                        fixture.entitlement(), fixture.completion(), 3)
                        .runOnce().items().getFirst();
                if (attempt < 2) {
                    assertEquals(MockFinalizationWorker.ItemStatus.RETRY_SCHEDULED,
                            item.status());
                    assertPendingRetryUnique(fixture, attempt + 1);
                    fixture.clock().advance(RETRY_DELAY);
                } else {
                    assertEquals(MockFinalizationWorker.ItemStatus.FAILED_DEAD,
                            item.status());
                }
            }
            assertFailedUnique(fixture, 3, 0, 0);
            String terminalSnapshot = terminalSnapshot(fixture);
            assertTrue(fixture.worker(
                    "post-dead-healthy-worker",
                    fixture.artifacts(),
                    fixture.entitlement(),
                    fixture.completion(),
                    3).runOnce().idle());
            assertEquals(terminalSnapshot, terminalSnapshot(fixture));
            logger.log(Level.WARNING, "redaction throwable traversal probe",
                    new IllegalStateException(
                            "SAFE_OUTER_THROWABLE",
                            new IOException("SAFE_INNER_THROWABLE")));
        } finally {
            logger.removeHandler(logs);
            logger.setUseParentHandlers(previousParentHandlers);
        }

        assertNotNull(fixture);
        assertTrue(logs.content().contains("SAFE_OUTER_THROWABLE"));
        assertTrue(logs.content().contains("SAFE_INNER_THROWABLE"));
        assertTrue(logs.throwablesInspected() >= 2);
        assertDiagnosticsRedacted(logs.content(), fixture);
    }

    private void assertDiagnosticsRedacted(
            String captured,
            GateFixture fixture) throws Exception {
        CommitUsageRequest commit = fixture.checkpoint().commitUsageRequest();
        List<String> sensitive = new ArrayList<>(List.of(
                PRIVATE_DIAGNOSTIC,
                required("REFERENCE_TEST_DATABASE_URL"),
                required("REFERENCE_TEST_DATABASE_PASSWORD"),
                schema,
                fixture.artifactRoot().toString(),
                fixture.meetingId(),
                fixture.receipt(),
                DEPLOYMENT,
                SUBJECT,
                "grant-retry-to-dead-redacted",
                "session-retry-to-dead-redacted",
                "create-key-retry-to-dead-redacted",
                "create-digest-retry-to-dead-redacted",
                "reserve-key-retry-to-dead-redacted",
                "reserve-digest-retry-to-dead-redacted",
                "Worker gate",
                "zh-CN",
                "en-US",
                fixture.input().inputBinding(),
                fixture.input().contentDigest(),
                commit.reservationId(),
                commit.idempotencyKey(),
                commit.requestDigest(),
                FinalizationCheckpointJson.encode(fixture.checkpoint()),
                FinalizationCheckpointJson.sha256(fixture.checkpoint())));
        FailureRequest terminal = fixture.completion().lastFailure();
        assertNotNull(terminal);
        sensitive.add(terminal.lease().owner());
        sensitive.add(terminal.lease().token());
        for (FinalizationCheckpoint.ArtifactMaterial material
                : fixture.checkpoint().artifacts()) {
            ArtifactStore.PrepareResult prepared =
                    fixture.artifacts().createWriteIntent(material.spec());
            sensitive.add(material.spec().artifactId());
            sensitive.add(prepared.intent().intentId());
            sensitive.add(prepared.intent().binding().value());
            sensitive.add(material.claim().checksum());
            sensitive.add(new String(
                    material.canonicalContentBytes(), StandardCharsets.UTF_8));
        }
        for (String value : sensitive) {
            assertFalse(captured.contains(value),
                    () -> "sensitive value reached logs: " + redactForAssertion(value));
        }
        String databaseUser = required("REFERENCE_TEST_DATABASE_USER");
        assertFalse(containsStandaloneToken(captured, databaseUser),
                "database username reached logs as a standalone token");
    }

    private static boolean containsStandaloneToken(String content, String token) {
        return java.util.regex.Pattern.compile(
                "(?<![A-Za-z0-9_.-])" + java.util.regex.Pattern.quote(token)
                        + "(?![A-Za-z0-9_.-])")
                .matcher(content).find();
    }

    private static String redactForAssertion(String value) {
        return "length=" + value.length() + ",sha256=" + sha256Text(value);
    }

    private static String sha256Text(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private GateFixture prepare(String suffix) throws Exception {
        AdmissibleMeeting meeting = createAdmissibleMeeting(suffix);
        String meetingId = meeting.meetingId();
        MutableClock clock = new MutableClock(ACCEPTED_AT.plusSeconds(1));
        FinalizationAdmissionUnitOfWork.AdmissionRequest request =
                admissionRequest(meetingId);
        FinalizationAdmissionUnitOfWork.Result result =
                new JdbcFinalizationAdmissionUnitOfWork(
                        dataSource,
                        () -> "receipt-" + suffix,
                        new DeterministicMockFinalizationPlanner())
                        .admit(request);
        assertTrue(result instanceof FinalizationAdmissionUnitOfWork.Success,
                () -> "admission failed: " + result);
        FinalizationAdmissionUnitOfWork.Success admitted =
                (FinalizationAdmissionUnitOfWork.Success) result;
        WorkflowStep stored = new JdbcWorkflowStepStore(dataSource)
                .find(admitted.workflowKey()).orElseThrow();
        FinalizationCheckpoint checkpoint = FinalizationCheckpointJson.decode(
                stored.canonicalPayload());
        assertEquals(2, checkpoint.artifacts().size(),
                "joint gate requires a READY subset to be observable");
        Path root = temporaryDirectory.resolve(suffix + "-artifacts");
        ArtifactStore artifacts = new PersistentFileSystemMockArtifactStore(
                root, ARTIFACT_LIMIT);
        AtomicReference<UsageOperationResult> expectedCommit = new AtomicReference<>();
        EntitlementProvider entitlement = new ExpectedCommitEntitlement(
                new JdbcReferenceDevEntitlementProvider(dataSource, policy(clock), clock),
                meeting.boundReservation(),
                clock,
                expectedCommit);
        return new GateFixture(
                meetingId,
                admitted.finalization().finalizationReceipt(),
                request.inputFact(),
                admitted.finalUsage(),
                checkpoint,
                clock,
                root,
                artifacts,
                entitlement,
                meeting.boundReservation(),
                expectedCommit,
                new CapturingCompletion(
                        new JdbcFinalizationCompletionUnitOfWork(dataSource)),
                dataSource);
    }

    private AdmissibleMeeting createAdmissibleMeeting(String suffix) throws Exception {
        String normalized = suffix.replace('-', '_').toLowerCase();
        String meetingId = "mtg_worker_gate_" + normalized;
        ProductProfile productProfile = profile();
        Set<String> requested = Set.of("transcript.final", "summary.structured");
        Set<String> granted = new HashSet<>(requested);
        MeetingGrant grant = new MeetingGrant(
                "grant-" + suffix,
                DEPLOYMENT,
                SUBJECT,
                productProfile.id(),
                productProfile.revision(),
                requested,
                granted,
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
        UsageReservation boundReservation = reservation.bind(
                meetingId, CREATED_AT, reservation.version());

        MeetingStartExecutionUnitOfWork.Result started =
                new JdbcMeetingStartExecutionUnitOfWork(
                        dataSource, new CapabilityPlanner())
                        .start(new MeetingStartExecutionUnitOfWork.StartRequest(
                                DEPLOYMENT,
                                SUBJECT,
                                meetingId,
                                STARTED_AT,
                                "Worker gate",
                                productProfile,
                                requested,
                                new CapabilityDescriptor(
                                        "worker-gate-provider",
                                        Set.of("transcript.final", "summary.structured")),
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
        return new AdmissibleMeeting(meetingId, boundReservation);
    }

    private static FinalizationAdmissionUnitOfWork.AdmissionRequest admissionRequest(
            String meetingId) {
        return new FinalizationAdmissionUnitOfWork.AdmissionRequest(
                DEPLOYMENT,
                SUBJECT,
                meetingId,
                new FinalizationInputFact(
                        meetingId,
                        "input-binding-" + meetingId,
                        42,
                        60_000,
                        "zh-CN",
                        List.of("en-US"),
                        ACCEPTED_AT,
                        "sha256:input-digest-" + meetingId));
    }

    private static ProductProfile profile() {
        return new ProductProfile(
                "profile",
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

    private static ReferenceDevEntitlementPolicy policy(Clock clock) {
        return new ReferenceDevEntitlementPolicy(
                new ReferenceDevEntitlementPolicyConfig(
                        DEPLOYMENT,
                        "profile",
                        1,
                        Set.of("transcript.final", "summary.structured"),
                        3_600_000,
                        Duration.ofHours(1),
                        Duration.ofMinutes(30)),
                clock,
                new FixedIds());
    }

    private void assertReadyUnique(GateFixture fixture, int attempt) throws Exception {
        assertMeetingAndWorkflow(
                fixture, "READY", "READY", "COMPLETED", attempt);
        assertEquals(1, countForMeeting("durable_input_fact", fixture.meetingId()));
        assertEquals(1, countForMeeting("final_usage_fact", fixture.meetingId()));
        assertEquals(1, countForMeeting(
                "finalization_execution_fact", fixture.meetingId()));
        assertEquals(1, countForMeeting("workflow_step", fixture.meetingId()));
        assertEquals(1, countForMeeting(
                "meeting_manifest_revision", fixture.meetingId()));
        assertEquals(fixture.checkpoint().artifacts().size(),
                countForMeeting("artifact", fixture.meetingId()));
        assertEquals(1, countCommitOperations(fixture));
        assertEquals(1, countReceipt(fixture.receipt()));
        assertAuthoritativeFacts(
                fixture,
                fixture.checkpoint().artifacts().size(),
                1,
                ManifestStatus.READY);
    }

    private void assertFailedUnique(
            GateFixture fixture,
            int attempt,
            int expectedMetadata,
            int expectedCommitOperations) throws Exception {
        assertMeetingAndWorkflow(
                fixture, "FAILED", "FAILED", "DEAD", attempt);
        assertEquals(1, countForMeeting("durable_input_fact", fixture.meetingId()));
        assertEquals(1, countForMeeting("final_usage_fact", fixture.meetingId()));
        assertEquals(1, countForMeeting(
                "finalization_execution_fact", fixture.meetingId()));
        assertEquals(1, countForMeeting("workflow_step", fixture.meetingId()));
        assertEquals(1, countForMeeting(
                "meeting_manifest_revision", fixture.meetingId()));
        assertEquals(expectedMetadata,
                countForMeeting("artifact", fixture.meetingId()));
        assertEquals(expectedCommitOperations, countCommitOperations(fixture));
        assertEquals(1, countReceipt(fixture.receipt()));
        assertAuthoritativeFacts(
                fixture,
                expectedMetadata,
                expectedCommitOperations,
                ManifestStatus.FAILED);
    }

    /**
     * 对联合门中的每一份权威事实做内容级核对，而不只依赖表的主键数量。
     * PostgreSQL 行、文件 Store descriptor/正文和 Manifest 引用必须由同一份
     * checkpoint 收敛；任何第二 identity、摘要漂移或终态正文漂移都会失败。
     */
    private void assertAuthoritativeFacts(
            GateFixture fixture,
            int expectedReadyMetadata,
            int expectedCommitOperations,
            ManifestStatus expectedManifestStatus) throws Exception {
        assertEquals(fixture.input(), readDurableInput(fixture.meetingId()));
        assertEquals(fixture.finalUsage(), readFinalUsage(fixture.meetingId()));
        assertEquals(fixture.finalUsage(), fixture.checkpoint().finalUsageFact());
        assertExecutionIdentity(fixture);

        WorkflowStep workflow = new JdbcWorkflowStepStore(dataSource)
                .find(fixture.checkpoint().workflowKey()).orElseThrow();
        assertEquals(fixture.checkpoint().workflowKey(), workflow.key());
        assertEquals(FinalizationCheckpoint.CHECKPOINT_TYPE, workflow.checkpointType());
        assertEquals(FinalizationCheckpointJson.encode(fixture.checkpoint()),
                workflow.canonicalPayload());
        assertEquals(FinalizationCheckpointJson.sha256(fixture.checkpoint()),
                workflow.contentDigest());

        Map<String, ArtifactStore.ArtifactDescriptor> expectedReady =
                new java.util.HashMap<>();
        for (int index = 0; index < expectedReadyMetadata; index++) {
            FinalizationCheckpoint.ArtifactMaterial material =
                    fixture.checkpoint().artifacts().get(index);
            ArtifactStore.PrepareResult prepared =
                    fixture.artifacts().createWriteIntent(material.spec());
            assertEquals(ArtifactStore.PrepareStatus.REPLAYED, prepared.status());
            ArtifactStore.StatResult stat = fixture.artifacts().stat(
                    prepared.intent().binding());
            assertEquals(ArtifactStore.StoreState.READY, stat.state());
            assertNotNull(stat.artifact());
            assertEquals(material.spec(), stat.artifact().intent().spec());
            assertEquals(material.claim(), stat.artifact().content());
            try (ArtifactStore.ReadHandle read = fixture.artifacts().openRead(
                    prepared.intent().binding())) {
                assertEquals(stat.artifact(), read.artifact());
                assertTrue(java.util.Arrays.equals(
                        material.canonicalContentBytes(),
                        read.content().readAllBytes()));
            }
            expectedReady.put(material.spec().artifactId(), stat.artifact());
        }
        assertArtifactMetadataRows(fixture, expectedReady);

        MeetingManifest manifest = readManifest(fixture.meetingId());
        assertEquals(fixture.meetingId(), manifest.meetingId());
        assertEquals(1, manifest.manifestRevision());
        assertEquals(expectedManifestStatus, manifest.status());
        assertEquals(fixture.checkpoint().artifacts().size(),
                manifest.artifacts().size());
        assertEquals(expectedReadyMetadata, manifest.artifacts().stream()
                .filter(reference -> reference.status()
                        == MeetingManifest.ArtifactStatus.READY)
                .count());
        if (expectedManifestStatus == ManifestStatus.FAILED) {
            assertEquals(fixture.checkpoint().artifacts().size() - expectedReadyMetadata,
                    manifest.artifacts().stream()
                            .filter(reference -> reference.status()
                                    == MeetingManifest.ArtifactStatus.FAILED)
                            .count());
        }
        for (MeetingManifest.ArtifactRef reference : manifest.artifacts()) {
            FinalizationCheckpoint.ArtifactMaterial material =
                    fixture.checkpoint().artifacts().stream()
                            .filter(candidate -> candidate.spec().artifactId()
                                    .equals(reference.artifactId()))
                            .findFirst().orElseThrow();
            assertEquals(material.spec().artifactType(), reference.type());
            assertEquals(material.spec().mediaType(), reference.mediaType());
            assertEquals(material.spec().language(), reference.language());
            assertEquals(material.spec().revision(), reference.revision());
            assertEquals(material.claim().size(), reference.size());
            assertEquals(material.claim().checksum(), reference.checksum());
        }
        assertManifestCanonicalHash(fixture.meetingId(), manifest);
        assertUsageIdentity(fixture, expectedCommitOperations);
        assertArtifactTreeShape(fixture, expectedReadyMetadata);
    }

    private void assertExecutionIdentity(GateFixture fixture) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            FinalizationExecutionFact execution =
                    new JdbcFinalizationExecutionFactStore(connection)
                            .find(fixture.meetingId()).orElseThrow();
            connection.rollback();
            assertEquals(fixture.meetingId(), execution.meetingId());
            assertEquals(STARTED_AT, execution.startedAt());
            assertEquals("Worker gate", execution.title());
            assertEquals("profile", execution.productProfile().id());
            assertEquals(1, execution.productProfile().revision());
            assertEquals(fixture.checkpoint().executionFactDigest(),
                    FinalizationExecutionFactJson.sha256(execution));

            String canonical = FinalizationExecutionFactJson.encode(execution);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT canonical_json, execution_fact_json::text AS jsonb_text,
                           content_sha256
                      FROM finalization_execution_fact
                     WHERE meeting_id=?
                    """)) {
                statement.setString(1, fixture.meetingId());
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(canonical, rows.getString("canonical_json"));
                    assertEquals(execution, FinalizationExecutionFactJson.decode(
                            rows.getString("jsonb_text")));
                    assertTrue(MessageDigest.isEqual(
                            digest, rows.getBytes("content_sha256")));
                    assertFalse(rows.next());
                }
            }
        }
    }

    private FinalizationInputFact readDurableInput(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            FinalizationInputFact fact = JdbcDurableInputFactStore.find(
                    connection, meetingId).orElseThrow();
            connection.rollback();
            return fact;
        }
    }

    private FinalUsageFact readFinalUsage(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            FinalUsageFact fact = JdbcFinalUsageFactStore.find(
                    connection, meetingId).orElseThrow();
            connection.rollback();
            return fact;
        }
    }

    private MeetingManifest readManifest(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            MeetingManifest manifest = new JdbcManifestFactStore(connection)
                    .findLatest(meetingId).orElseThrow();
            connection.rollback();
            return manifest;
        }
    }

    private void assertArtifactMetadataRows(
            GateFixture fixture,
            Map<String, ArtifactStore.ArtifactDescriptor> expected) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT artifact_id, artifact_type, media_type, language, revision,
                            intent_id, storage_binding, content_size, content_checksum
                       FROM artifact
                      WHERE meeting_id=?
                      ORDER BY artifact_id
                     """)) {
            statement.setString(1, fixture.meetingId());
            Set<String> seenArtifactIds = new HashSet<>();
            Set<String> seenIntentIds = new HashSet<>();
            Set<String> seenBindings = new HashSet<>();
            int rowsRead = 0;
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    rowsRead++;
                    String artifactId = rows.getString("artifact_id");
                    ArtifactStore.ArtifactDescriptor descriptor =
                            expected.get(artifactId);
                    assertNotNull(descriptor);
                    WriteIntent intent = descriptor.intent();
                    WriteIntentSpec spec = intent.spec();
                    assertTrue(seenArtifactIds.add(artifactId));
                    assertTrue(seenIntentIds.add(rows.getString("intent_id")));
                    assertTrue(seenBindings.add(rows.getString("storage_binding")));
                    assertEquals(spec.artifactType(), rows.getString("artifact_type"));
                    assertEquals(spec.mediaType(), rows.getString("media_type"));
                    assertEquals(spec.language(), rows.getString("language"));
                    assertEquals(spec.revision(), rows.getInt("revision"));
                    assertEquals(intent.intentId(), rows.getString("intent_id"));
                    assertEquals(intent.binding().value(),
                            rows.getString("storage_binding"));
                    assertEquals(descriptor.content().size(),
                            rows.getLong("content_size"));
                    assertEquals(descriptor.content().checksum(),
                            rows.getString("content_checksum"));
                }
            }
            assertEquals(expected.size(), rowsRead);
            assertEquals(expected.keySet(), seenArtifactIds);
            assertEquals(expected.size(), seenIntentIds.size());
            assertEquals(expected.size(), seenBindings.size());
        }
    }

    private void assertManifestCanonicalHash(
            String meetingId,
            MeetingManifest manifest) throws Exception {
        String canonical = MeetingManifestJson.encode(manifest);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT manifest_revision, previous_revision, canonical_json,
                            content_sha256
                       FROM meeting_manifest_revision
                      WHERE meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt("manifest_revision"));
                assertEquals(null, rows.getObject("previous_revision"));
                assertEquals(canonical, rows.getString("canonical_json"));
                assertEquals(HexFormat.of().formatHex(digest),
                        HexFormat.of().formatHex(rows.getBytes("content_sha256")));
                assertFalse(rows.next());
            }
        }
    }

    private void assertUsageIdentity(
            GateFixture fixture,
            int expectedCommitOperations) throws Exception {
        CommitUsageRequest request = fixture.checkpoint().commitUsageRequest();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT idempotency_key, request_digest, reservation_id,
                            result_body::text AS result_body
                       FROM usage_operation
                      WHERE deployment_scope=? AND subject_scope=?
                        AND operation_scope=? AND idempotency_key=?
                     """)) {
            statement.setString(1, request.deploymentScope());
            statement.setString(2, request.subjectScope());
            statement.setString(3, UsageOperationScopes.COMMIT);
            statement.setString(4, request.idempotencyKey());
            int rowsRead = 0;
            UsageOperationResult storedResult = null;
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    rowsRead++;
                    assertEquals(request.idempotencyKey(),
                            rows.getString("idempotency_key"));
                    assertEquals(request.requestDigest(),
                            rows.getString("request_digest"));
                    assertEquals(request.reservationId(),
                            rows.getString("reservation_id"));
                    storedResult = UsageOperationResultJson.decode(
                            rows.getString("result_body"),
                            request.deploymentScope(),
                            request.subjectScope(),
                            UsageOperationScopes.COMMIT,
                            request.idempotencyKey(),
                            request.requestDigest());
                }
            }
            assertEquals(expectedCommitOperations, rowsRead);
            if (expectedCommitOperations == 1) {
                assertNotNull(storedResult);
                UsageOperationResult expected = fixture.expectedCommit().get();
                assertNotNull(expected,
                        "test must freeze the complete expected commit result");
                assertEquals(expected, storedResult);
            }
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT status, version, actual_amount
                       FROM usage_reservation
                      WHERE deployment_scope=? AND subject_scope=?
                        AND reservation_id=?
                     """)) {
            statement.setString(1, request.deploymentScope());
            statement.setString(2, request.subjectScope());
            statement.setString(3, request.reservationId());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(expectedCommitOperations == 1 ? "COMMITTED" : "RESERVED",
                        rows.getString("status"));
                assertEquals(request.expectedVersion() + expectedCommitOperations,
                        rows.getLong("version"));
                if (expectedCommitOperations == 1) {
                    assertEquals(request.actual().amount(),
                            rows.getLong("actual_amount"));
                } else {
                    assertEquals(null, rows.getObject("actual_amount"));
                }
                assertFalse(rows.next());
            }
        }
    }

    private void assertPendingRetryUnique(GateFixture fixture, int attempt)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT m.state, m.manifest_state, w.status, w.attempt_count,
                            m.aggregate_version, m.updated_at AS meeting_updated_at,
                            w.lease_owner, w.lease_token, w.lease_until,
                            w.lease_version, w.updated_at AS workflow_updated_at,
                            w.next_attempt_at, w.error_code, w.error_message
                       FROM meeting m
                       JOIN workflow_step w ON w.meeting_id=m.meeting_id
                      WHERE m.meeting_id=?
                     """)) {
            statement.setString(1, fixture.meetingId());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("FINALIZING", rows.getString("state"));
                assertEquals("GENERATING", rows.getString("manifest_state"));
                assertEquals(4, rows.getLong("aggregate_version"));
                assertEquals(ACCEPTED_AT,
                        rows.getTimestamp("meeting_updated_at").toInstant());
                assertEquals("PENDING", rows.getString("status"));
                assertEquals(attempt, rows.getInt("attempt_count"));
                assertEquals(null, rows.getString("lease_owner"));
                assertEquals(null, rows.getString("lease_token"));
                assertEquals(null, rows.getTimestamp("lease_until"));
                assertEquals(attempt, rows.getLong("lease_version"));
                assertEquals(fixture.clock().instant(),
                        rows.getTimestamp("workflow_updated_at").toInstant());
                assertEquals(fixture.clock().instant().plus(RETRY_DELAY),
                        rows.getTimestamp("next_attempt_at").toInstant());
                assertEquals("ARTIFACT_STORAGE_UNAVAILABLE",
                        rows.getString("error_code"));
                assertEquals("ARTIFACT_STORE_OPERATION_FAILED",
                        rows.getString("error_message"));
                assertFalse(rows.next());
            }
        }
        assertEquals(0, countForMeeting(
                "meeting_manifest_revision", fixture.meetingId()));
        assertEquals(0, countForMeeting("artifact", fixture.meetingId()));
        assertEquals(0, countCommitOperations(fixture));
        assertEquals(1, countReceipt(fixture.receipt()));
    }

    private void assertPreTerminalUnique(
            GateFixture fixture,
            int expectedCommitOperations) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT m.state, m.manifest_state, w.status, w.attempt_count,
                            m.aggregate_version, m.updated_at AS meeting_updated_at,
                            w.error_code, w.error_message,
                            w.lease_owner, w.lease_token, w.lease_until,
                            w.lease_version, w.updated_at AS claimed_at,
                            w.updated_at AS workflow_updated_at,
                            w.next_attempt_at
                       FROM meeting m
                       JOIN workflow_step w ON w.meeting_id=m.meeting_id
                      WHERE m.meeting_id=?
                     """)) {
            statement.setString(1, fixture.meetingId());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("FINALIZING", rows.getString("state"));
                assertEquals("GENERATING", rows.getString("manifest_state"));
                assertEquals(4, rows.getLong("aggregate_version"));
                assertEquals(ACCEPTED_AT,
                        rows.getTimestamp("meeting_updated_at").toInstant());
                assertEquals("LEASED", rows.getString("status"));
                assertEquals(0, rows.getInt("attempt_count"));
                assertNotNull(rows.getString("error_code"));
                assertNotNull(rows.getString("error_message"));
                assertNotNull(rows.getString("lease_owner"));
                assertNotNull(rows.getString("lease_token"));
                assertEquals(fixture.clock().instant().plus(LEASE_DURATION),
                        rows.getTimestamp("lease_until").toInstant());
                assertEquals(1, rows.getLong("lease_version"));
                assertEquals(fixture.clock().instant(),
                        rows.getTimestamp("claimed_at").toInstant());
                assertEquals(fixture.clock().instant(),
                        rows.getTimestamp("workflow_updated_at").toInstant());
                assertEquals(null, rows.getTimestamp("next_attempt_at"));
                assertFalse(rows.next());
            }
        }
        assertEquals(0, countForMeeting(
                "meeting_manifest_revision", fixture.meetingId()));
        assertEquals(0, countForMeeting("artifact", fixture.meetingId()));
        assertEquals(expectedCommitOperations, countCommitOperations(fixture));
        assertEquals(1, countReceipt(fixture.receipt()));
        assertArtifactFilesUnique(fixture);
    }

    private void assertMeetingAndWorkflow(
            GateFixture fixture,
            String meetingState,
            String manifestState,
            String workflowState,
            int attempt) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT m.state, m.manifest_state, m.finalization_receipt_id,
                            m.deployment_scope, m.subject_scope, m.profile_id,
                            m.profile_revision, m.input_state,
                            m.usage_reservation_id, m.created_at, m.updated_at,
                            m.aggregate_version,
                            w.status, w.attempt_count, w.lease_owner, w.lease_token,
                            w.lease_until, w.next_attempt_at,
                            w.error_code, w.error_message, w.lease_version,
                            w.updated_at AS workflow_updated_at
                       FROM meeting m
                       JOIN workflow_step w ON w.meeting_id=m.meeting_id
                      WHERE m.meeting_id=?
                     """)) {
            statement.setString(1, fixture.meetingId());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(meetingState, rows.getString("state"));
                assertEquals(manifestState, rows.getString("manifest_state"));
                assertEquals(fixture.receipt(), rows.getString("finalization_receipt_id"));
                assertEquals(DEPLOYMENT, rows.getString("deployment_scope"));
                assertEquals(SUBJECT, rows.getString("subject_scope"));
                assertEquals("profile", rows.getString("profile_id"));
                assertEquals(1, rows.getInt("profile_revision"));
                assertEquals("INPUT_ACCEPTED", rows.getString("input_state"));
                assertEquals(fixture.checkpoint().commitUsageRequest().reservationId(),
                        rows.getString("usage_reservation_id"));
                assertEquals(CREATED_AT, rows.getTimestamp("created_at").toInstant());
                assertEquals(5, rows.getLong("aggregate_version"));
                assertEquals(workflowState, rows.getString("status"));
                assertEquals(attempt, rows.getInt("attempt_count"));
                assertEquals(null, rows.getString("lease_owner"));
                assertEquals(null, rows.getString("lease_token"));
                assertEquals(null, rows.getTimestamp("lease_until"));
                assertEquals(null, rows.getTimestamp("next_attempt_at"));
                if ("COMPLETED".equals(workflowState)) {
                    CompletionRequest operation = fixture.completion().lastCompletion();
                    assertNotNull(operation);
                    assertEquals(fixture.clock().instant(),
                            operation.lease().claimedAt());
                    assertEquals(operation.completedAt(),
                            rows.getTimestamp("updated_at").toInstant());
                    assertEquals(operation.completedAt(),
                            rows.getTimestamp("workflow_updated_at").toInstant());
                    assertEquals(operation.lease().leaseVersion(),
                            rows.getLong("lease_version"));
                    assertEquals(null, rows.getString("error_code"));
                    assertEquals(null, rows.getString("error_message"));
                } else {
                    FailureRequest operation = fixture.completion().lastFailure();
                    assertNotNull(operation);
                    assertEquals(fixture.clock().instant(),
                            operation.lease().claimedAt());
                    assertEquals(operation.failedAt(),
                            rows.getTimestamp("updated_at").toInstant());
                    assertEquals(operation.failedAt(),
                            rows.getTimestamp("workflow_updated_at").toInstant());
                    assertEquals(operation.lease().leaseVersion(),
                            rows.getLong("lease_version"));
                    assertEquals(operation.leasedAttempt() + 1,
                            rows.getInt("attempt_count"));
                    assertEquals(operation.failure().code(),
                            rows.getString("error_code"));
                    assertEquals(FinalizationTerminalFailureIdentity.of(
                                    operation.failure()),
                            rows.getString("error_message"));
                }
                assertFalse(rows.next());
            }
        }
    }

    private int countCommitOperations(GateFixture fixture) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*)
                       FROM usage_operation
                      WHERE deployment_scope=? AND subject_scope=?
                        AND operation_scope=? AND idempotency_key=?
                     """)) {
            statement.setString(1, DEPLOYMENT);
            statement.setString(2, SUBJECT);
            statement.setString(3, UsageOperationScopes.COMMIT);
            statement.setString(
                    4, fixture.checkpoint().commitUsageRequest().idempotencyKey());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private int countForMeeting(String table, String meetingId) throws Exception {
        Set<String> allowed = Set.of(
                "durable_input_fact",
                "final_usage_fact",
                "finalization_execution_fact",
                "workflow_step",
                "meeting_manifest_revision",
                "artifact");
        if (!allowed.contains(table)) {
            throw new IllegalArgumentException("unexpected table");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + table + " WHERE meeting_id=?")) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private int countReceipt(String receipt) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM meeting WHERE finalization_receipt_id=?")) {
            statement.setString(1, receipt);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private void assertArtifactFilesUnique(GateFixture fixture) throws Exception {
        Path objects = fixture.artifactRoot().resolve("objects");
        try (var paths = Files.list(objects)) {
            List<Path> files = paths.toList();
            long count = fixture.checkpoint().artifacts().size();
            assertEquals(count, files.stream()
                    .filter(path -> path.getFileName().toString().endsWith(".blob"))
                    .count());
            assertEquals(count, files.stream()
                    .filter(path -> path.getFileName().toString().endsWith(".ready"))
                    .count());
            assertEquals(count * 2, files.size());
        }
        for (FinalizationCheckpoint.ArtifactMaterial material
                : fixture.checkpoint().artifacts()) {
            ArtifactStore.PrepareResult prepared =
                    fixture.artifacts().createWriteIntent(material.spec());
            assertEquals(ArtifactStore.PrepareStatus.REPLAYED, prepared.status());
            ArtifactStore.StatResult stat = fixture.artifacts().stat(
                    prepared.intent().binding());
            assertEquals(ArtifactStore.StoreState.READY, stat.state());
            assertNotNull(stat.artifact());
            assertEquals(material.claim(), stat.artifact().content());
        }
    }

    private void assertArtifactTreeShape(
            GateFixture fixture,
            int expectedReady) throws Exception {
        Path root = fixture.artifactRoot();
        Set<String> directories = Set.of(
                "intents", "staging", "objects", "tombstones", "locks");
        try (var paths = Files.list(root)) {
            assertEquals(directories, paths.map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet()));
        }
        assertDirectoryShape(root.resolve("intents"), expectedReady, ".intent");
        assertDirectoryShape(root.resolve("staging"), expectedReady, ".verified");
        assertDirectoryShape(root.resolve("objects"), expectedReady, ".blob", ".ready");
        assertDirectoryShape(root.resolve("tombstones"), 0);
        assertDirectoryShape(root.resolve("locks"), expectedReady, ".lock");
    }

    private static void assertDirectoryShape(
            Path directory,
            int expectedPerSuffix,
            String... allowedSuffixes) throws Exception {
        try (var paths = Files.list(directory)) {
            List<Path> files = paths.toList();
            assertEquals((long) expectedPerSuffix * allowedSuffixes.length, files.size());
            for (Path file : files) {
                assertTrue(Files.isRegularFile(file));
                String name = file.getFileName().toString();
                assertTrue(java.util.Arrays.stream(allowedSuffixes)
                        .anyMatch(name::endsWith));
                assertFalse(name.endsWith(".tmp"));
                assertFalse(name.endsWith(".part"));
            }
            for (String suffix : allowedSuffixes) {
                assertEquals(expectedPerSuffix, files.stream()
                        .filter(path -> path.getFileName().toString().endsWith(suffix))
                        .count());
            }
        }
    }

    private void assertPublishedArtifactSubset(
            GateFixture fixture,
            int expectedReady) throws Exception {
        Path objects = fixture.artifactRoot().resolve("objects");
        try (var paths = Files.list(objects)) {
            List<Path> files = paths.toList();
            assertEquals(expectedReady, files.stream()
                    .filter(path -> path.getFileName().toString().endsWith(".blob"))
                    .count());
            assertEquals(expectedReady, files.stream()
                    .filter(path -> path.getFileName().toString().endsWith(".ready"))
                    .count());
            assertEquals(expectedReady * 2L, files.size());
        }
        MeetingManifest manifest;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            manifest = new JdbcManifestFactStore(connection)
                    .findLatest(fixture.meetingId()).orElseThrow();
            connection.rollback();
        }
        assertEquals(expectedReady, manifest.artifacts().stream()
                .filter(reference -> reference.status()
                        == MeetingManifest.ArtifactStatus.READY)
                .count());
        assertEquals(fixture.checkpoint().artifacts().size() - expectedReady,
                manifest.artifacts().stream()
                        .filter(reference -> reference.status()
                                == MeetingManifest.ArtifactStatus.FAILED)
                        .count());
    }

    private String terminalSnapshot(GateFixture fixture) throws Exception {
        String meetingId = fixture.meetingId();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT jsonb_build_object(
                         'meeting', to_jsonb(m),
                         'workflow', to_jsonb(w),
                         'input', (SELECT to_jsonb(i) FROM durable_input_fact i
                             WHERE i.meeting_id=m.meeting_id),
                         'usage', (SELECT to_jsonb(u) FROM final_usage_fact u
                             WHERE u.meeting_id=m.meeting_id),
                         'execution', (SELECT to_jsonb(e)
                             FROM finalization_execution_fact e
                             WHERE e.meeting_id=m.meeting_id),
                         'manifest', (SELECT to_jsonb(r)
                             FROM meeting_manifest_revision r
                             WHERE r.meeting_id=m.meeting_id),
                         'artifacts', (SELECT coalesce(
                             jsonb_agg(to_jsonb(a) ORDER BY a.artifact_id), '[]'::jsonb)
                             FROM artifact a WHERE a.meeting_id=m.meeting_id),
                         'reservation', (SELECT to_jsonb(r)
                             FROM usage_reservation r
                             WHERE r.deployment_scope=m.deployment_scope
                               AND r.subject_scope=m.subject_scope
                               AND r.reservation_id=m.usage_reservation_id),
                         'commitOperation', (SELECT to_jsonb(o)
                             FROM usage_operation o
                             WHERE o.deployment_scope=m.deployment_scope
                               AND o.subject_scope=m.subject_scope
                               AND o.operation_scope=?
                               AND o.idempotency_key=?))::text
                       FROM meeting m
                       JOIN workflow_step w ON w.meeting_id=m.meeting_id
                      WHERE m.meeting_id=?
                     """)) {
            statement.setString(1, UsageOperationScopes.COMMIT);
            statement.setString(2,
                    fixture.checkpoint().commitUsageRequest().idempotencyKey());
            statement.setString(3, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                String databaseSnapshot = rows.getString(1);
                assertFalse(rows.next());
                return databaseSnapshot + "\n" + artifactTreeSnapshot(
                        fixture.artifactRoot());
            }
        }
    }

    private static String artifactTreeSnapshot(Path root) throws Exception {
        List<String> entries = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(root)) {
                    continue;
                }
                String relative = root.relativize(path).toString();
                if (Files.isDirectory(path)) {
                    entries.add("D " + relative);
                } else {
                    byte[] content = Files.readAllBytes(path);
                    entries.add("F " + relative + " " + content.length + " "
                            + HexFormat.of().formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(content)));
                }
            }
        }
        return String.join("\n", entries);
    }

    private void applyMigration(String filename) throws Exception {
        if (!"V001__initial_schema.sql".equals(filename)) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(readMigration(filename));
        }
    }

    private static String readMigration(String filename) throws IOException {
        if (!"V001__initial_schema.sql".equals(filename)) {
            return "SELECT 1";
        }
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

    private static void rejectReservedDatabaseEndpoint() {
        String url = required("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException(
                    "worker fault gate refuses the reserved PostgreSQL endpoint");
        }
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

    private record AdmissibleMeeting(
            String meetingId,
            UsageReservation boundReservation) {
    }

    private record GateFixture(
            String meetingId,
            String receipt,
            FinalizationInputFact input,
            FinalUsageFact finalUsage,
            FinalizationCheckpoint checkpoint,
            MutableClock clock,
            Path artifactRoot,
            ArtifactStore artifacts,
            EntitlementProvider entitlement,
            UsageReservation boundReservation,
            AtomicReference<UsageOperationResult> expectedCommit,
            CapturingCompletion completion,
            DataSource dataSource) {

        private EntitlementProvider trackExpectedCommit(EntitlementProvider delegate) {
            return new ExpectedCommitEntitlement(
                    delegate,
                    boundReservation,
                    clock,
                    expectedCommit);
        }

        private MockFinalizationWorker worker(
                String owner,
                ArtifactStore artifactPort,
                EntitlementProvider entitlementPort,
                FinalizationCompletionUnitOfWork completionPort,
                int maxAttempts) {
            return worker(
                    owner,
                    new JdbcWorkflowStepStore(dataSource),
                    artifactPort,
                    entitlementPort,
                    completionPort,
                    maxAttempts);
        }

        private MockFinalizationWorker worker(
                String owner,
                WorkflowStepStore workflowPort,
                ArtifactStore artifactPort,
                EntitlementProvider entitlementPort,
                FinalizationCompletionUnitOfWork completionPort,
                int maxAttempts) {
            return new MockFinalizationWorker(
                    workflowPort,
                    artifactPort,
                    entitlementPort,
                    completionPort,
                    new MockFinalizationWorker.Config(
                            owner,
                            clock,
                            LEASE_DURATION,
                            Duration.ofSeconds(5),
                            RETRY_DELAY,
                            1,
                            maxAttempts));
        }
    }

    private enum ArtifactFaultPhase {
        CREATE,
        WRITE,
        COMPLETE
    }

    private enum EntitlementFault {
        NONE,
        NON_RETRYABLE
    }

    private enum TerminalKind {
        SUCCESS,
        FAILURE
    }

    private enum CrashCompletionPoint {
        BEFORE_DELEGATE,
        AFTER_DELEGATE
    }

    /**
     * 真实 JDBC commit 已到达 PostgreSQL 后丢失 acknowledgement 的连接替身。
     * Provider 仍走自己的事务、异常分类和幂等读回；探针只在第一次 commit 调用后
     * 抛出 SQLState 08006，不直接合成 Entitlement result。
     */
    private static CommitUnknownDataSource commitUnknownOnce(DataSource delegate) {
        AtomicBoolean injected = new AtomicBoolean();
        AtomicBoolean committed = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        DataSource wrapped = (DataSource) Proxy.newProxyInstance(
                MockFinalizationWorkerFaultInjectionIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("getConnection")) {
                        return invoke(method, delegate, arguments);
                    }
                    Connection connection = arguments == null || arguments.length == 0
                            ? delegate.getConnection()
                            : delegate.getConnection(
                                    (String) arguments[0], (String) arguments[1]);
                    if (!injected.compareAndSet(false, true)) {
                        return connection;
                    }
                    return Proxy.newProxyInstance(
                            MockFinalizationWorkerFaultInjectionIntegrationTest.class
                                    .getClassLoader(),
                            new Class<?>[]{Connection.class},
                            (connectionProxy, connectionMethod, connectionArguments) -> {
                                if (connectionMethod.getName().equals("commit")) {
                                    connection.commit();
                                    committed.set(true);
                                    throw new SQLException(
                                            PRIVATE_DIAGNOSTIC, "08006");
                                }
                                if (connectionMethod.getName().equals("close")) {
                                    closed.set(true);
                                }
                                return invoke(
                                        connectionMethod,
                                        connection,
                                        connectionArguments);
                            });
                });
        return new CommitUnknownDataSource(wrapped, committed, closed);
    }

    private static Object invoke(Method method, Object target, Object[] arguments)
            throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private record CommitUnknownDataSource(
            DataSource dataSource,
            AtomicBoolean committed,
            AtomicBoolean closed) {
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
        public java.util.Optional<WorkflowStep> find(WorkflowStep.Key key) {
            return delegate.find(key);
        }

        @Override
        public List<ClaimedStep> claim(ClaimRequest request) {
            return delegate.claim(request);
        }

        @Override
        public RenewResult renew(
                LeaseToken lease,
                Instant renewedAt,
                Duration leaseDuration) {
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
        public CompleteResult complete(LeaseToken lease, Instant completedAt) {
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
                    lease,
                    failedAt,
                    nextAttemptAt,
                    errorCode,
                    errorSummary,
                    maxAttempts);
        }
    }

    private static final class CapturingWorkflowStore extends ForwardingWorkflowStore {
        private final AtomicReference<LeaseToken> captured = new AtomicReference<>();

        private CapturingWorkflowStore(WorkflowStepStore delegate) {
            super(delegate);
        }

        @Override
        public List<ClaimedStep> claim(ClaimRequest request) {
            List<ClaimedStep> result = super.claim(request);
            if (!result.isEmpty()) {
                captured.set(result.getFirst().lease());
            }
            return result;
        }

        private LeaseToken lease() {
            return java.util.Objects.requireNonNull(captured.get(), "captured lease");
        }
    }

    private static final class PhaseCommitUnknownArtifactStore
            extends ForwardingArtifactStore {
        private final ArtifactFaultPhase phase;
        private final AtomicBoolean injected = new AtomicBoolean();

        private PhaseCommitUnknownArtifactStore(
                ArtifactStore delegate,
                ArtifactFaultPhase phase) {
            super(delegate);
            this.phase = phase;
        }

        @Override
        public PrepareResult createWriteIntent(WriteIntentSpec spec)
                throws ArtifactStoreException {
            PrepareResult result = super.createWriteIntent(spec);
            failOnce(ArtifactFaultPhase.CREATE);
            return result;
        }

        @Override
        public StageResult writeContent(WriteIntent intent, InputStream content)
                throws ArtifactStoreException {
            StageResult result = super.writeContent(intent, content);
            failOnce(ArtifactFaultPhase.WRITE);
            return result;
        }

        @Override
        public CompleteResult completeWrite(WriteIntent intent, ContentClaim claim)
                throws ArtifactStoreException {
            CompleteResult result = super.completeWrite(intent, claim);
            failOnce(ArtifactFaultPhase.COMPLETE);
            return result;
        }

        private void failOnce(ArtifactFaultPhase current) throws ArtifactStoreException {
            if (phase == current && injected.compareAndSet(false, true)) {
                throw new ArtifactStoreException(
                        ArtifactStoreException.Code.COMMIT_UNKNOWN,
                        PRIVATE_DIAGNOSTIC);
            }
        }

        private boolean injected() {
            return injected.get();
        }
    }

    private static final class CrashAfterArtifactPhaseStore
            extends ForwardingArtifactStore {
        private final ArtifactFaultPhase phase;
        private final AtomicBoolean crashed = new AtomicBoolean();

        private CrashAfterArtifactPhaseStore(
                ArtifactStore delegate,
                ArtifactFaultPhase phase) {
            super(delegate);
            this.phase = phase;
        }

        @Override
        public PrepareResult createWriteIntent(WriteIntentSpec spec)
                throws ArtifactStoreException {
            PrepareResult result = super.createWriteIntent(spec);
            crashOnce(ArtifactFaultPhase.CREATE);
            return result;
        }

        @Override
        public StageResult writeContent(WriteIntent intent, InputStream content)
                throws ArtifactStoreException {
            StageResult result = super.writeContent(intent, content);
            crashOnce(ArtifactFaultPhase.WRITE);
            return result;
        }

        @Override
        public CompleteResult completeWrite(WriteIntent intent, ContentClaim claim)
                throws ArtifactStoreException {
            CompleteResult result = super.completeWrite(intent, claim);
            crashOnce(ArtifactFaultPhase.COMPLETE);
            return result;
        }

        private void crashOnce(ArtifactFaultPhase current) {
            if (phase == current && crashed.compareAndSet(false, true)) {
                throw new CrashSignal();
            }
        }
    }

    private static class CountingArtifactStore extends ForwardingArtifactStore {
        private final AtomicInteger businessCalls = new AtomicInteger();

        private CountingArtifactStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public PrepareResult createWriteIntent(WriteIntentSpec spec)
                throws ArtifactStoreException {
            businessCalls.incrementAndGet();
            return super.createWriteIntent(spec);
        }

        @Override
        public StageResult writeContent(WriteIntent intent, InputStream content)
                throws ArtifactStoreException {
            businessCalls.incrementAndGet();
            return super.writeContent(intent, content);
        }

        @Override
        public CompleteResult completeWrite(WriteIntent intent, ContentClaim claim)
                throws ArtifactStoreException {
            businessCalls.incrementAndGet();
            return super.completeWrite(intent, claim);
        }

        private int businessCalls() {
            return businessCalls.get();
        }
    }

    private static final class RejectingArtifactStore extends ForwardingArtifactStore {
        private RejectingArtifactStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public PrepareResult createWriteIntent(WriteIntentSpec spec) {
            throw new AssertionError("terminal marker recovery must not recreate artifacts");
        }

        @Override
        public StageResult writeContent(WriteIntent intent, InputStream content) {
            throw new AssertionError("terminal marker recovery must not rewrite artifacts");
        }

        @Override
        public CompleteResult completeWrite(WriteIntent intent, ContentClaim claim) {
            throw new AssertionError("terminal marker recovery must not republish artifacts");
        }
    }

    private static final class AlwaysUnavailableArtifactStore
            extends ForwardingArtifactStore {
        private AlwaysUnavailableArtifactStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public PrepareResult createWriteIntent(WriteIntentSpec spec)
                throws ArtifactStoreException {
            throw new ArtifactStoreException(
                    ArtifactStoreException.Code.STORAGE_UNAVAILABLE,
                    PRIVATE_DIAGNOSTIC);
        }
    }

    private static final class FailSecondArtifactStore
            extends ForwardingArtifactStore {
        private final AtomicInteger intents = new AtomicInteger();

        private FailSecondArtifactStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public PrepareResult createWriteIntent(WriteIntentSpec spec)
                throws ArtifactStoreException {
            if (intents.incrementAndGet() == 2) {
                throw new ArtifactStoreException(
                        ArtifactStoreException.Code.STORAGE_CONFLICT,
                        PRIVATE_DIAGNOSTIC);
            }
            return super.createWriteIntent(spec);
        }
    }

    /**
     * 只在双 Worker 测试中暂时阻塞第一个显式 runOnce 调用。该线程由测试创建、等待并
     * 在 finally 中关闭，不属于 Worker 实现，也不会形成后台调度链。
     */
    private static final class BlockingArtifactStore extends ForwardingArtifactStore {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicBoolean first = new AtomicBoolean(true);

        private BlockingArtifactStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public PrepareResult createWriteIntent(WriteIntentSpec spec)
                throws ArtifactStoreException {
            if (first.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    if (!released.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("test release gate timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("test release gate interrupted", interrupted);
                }
            }
            return super.createWriteIntent(spec);
        }

        private boolean awaitEntered() throws InterruptedException {
            return entered.await(10, TimeUnit.SECONDS);
        }

        private void release() {
            released.countDown();
        }
    }

    private static final class FaultingEntitlement implements EntitlementProvider {
        private final EntitlementProvider delegate;
        private final EntitlementFault fault;
        private final AtomicInteger commitCalls = new AtomicInteger();
        private final List<CommitUsageRequest> requests = new ArrayList<>();

        private FaultingEntitlement(
                EntitlementProvider delegate,
                EntitlementFault fault) {
            this.delegate = delegate;
            this.fault = fault;
        }

        @Override
        public EntitlementProviderResult<MeetingGrant> authorizeStart(
                AuthorizeStartRequest request) {
            throw new AssertionError("finalization worker must not authorize start");
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> reserveUsage(
                ReserveUsageRequest request) {
            throw new AssertionError("finalization worker must not reserve usage");
        }

        @Override
        public synchronized EntitlementProviderResult<UsageOperationResult> commitUsage(
                CommitUsageRequest request) {
            commitCalls.incrementAndGet();
            requests.add(request);
            if (fault == EntitlementFault.NON_RETRYABLE) {
                return EntitlementProviderResult.failure(new EntitlementProviderError(
                        EntitlementProviderError.Code.INTERNAL, false));
            }
            EntitlementProviderResult<UsageOperationResult> committed =
                    delegate.commitUsage(request);
            return committed;
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> cancelReservation(
                CancelReservationRequest request) {
            throw new AssertionError("finalization worker must not cancel reservation");
        }

        private int commitCalls() {
            return commitCalls.get();
        }

        private synchronized long distinctRequests() {
            return requests.stream().distinct().count();
        }
    }

    /**
     * 在调用真实 Provider 前，用已冻结的 bound Reservation、请求和当前业务时钟
     * 独立构造完整 commit 结果。commit acknowledgement 丢失时也保留首次尝试
     * 的确定时间，供 result_body 解码后做完整值对象等值比较。
     */
    private static final class ExpectedCommitEntitlement implements EntitlementProvider {
        private final EntitlementProvider delegate;
        private final UsageReservation boundReservation;
        private final Clock clock;
        private final AtomicReference<UsageOperationResult> expected;

        private ExpectedCommitEntitlement(
                EntitlementProvider delegate,
                UsageReservation boundReservation,
                Clock clock,
                AtomicReference<UsageOperationResult> expected) {
            this.delegate = delegate;
            this.boundReservation = boundReservation;
            this.clock = clock;
            this.expected = expected;
        }

        @Override
        public EntitlementProviderResult<MeetingGrant> authorizeStart(
                AuthorizeStartRequest request) {
            return delegate.authorizeStart(request);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> reserveUsage(
                ReserveUsageRequest request) {
            return delegate.reserveUsage(request);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> commitUsage(
                CommitUsageRequest request) {
            UsageReservation committed = boundReservation.commit(
                    request.actual(), clock.instant(), request.expectedVersion());
            UsageOperationResult frozen = UsageOperationResult.terminal(
                    request.deploymentScope(),
                    request.subjectScope(),
                    request.operationScope(),
                    request.idempotencyKey(),
                    request.requestDigest(),
                    committed);
            expected.compareAndSet(null, frozen);
            return delegate.commitUsage(request);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> cancelReservation(
                CancelReservationRequest request) {
            return delegate.cancelReservation(request);
        }
    }

    /**
     * 让真实权益提交先完成，再暂停旧 Worker 的调用栈。释放后 Worker 会从
     * commitUsage 返回并执行自己的 ensureLease，因而不是测试手工模拟旧 Worker。
     */
    private static final class BlockingAfterCommitEntitlement
            implements EntitlementProvider {
        private final EntitlementProvider delegate;
        private final CountDownLatch committed = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private BlockingAfterCommitEntitlement(EntitlementProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public EntitlementProviderResult<MeetingGrant> authorizeStart(
                AuthorizeStartRequest request) {
            throw new AssertionError("finalization worker must not authorize start");
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> reserveUsage(
                ReserveUsageRequest request) {
            throw new AssertionError("finalization worker must not reserve usage");
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> commitUsage(
                CommitUsageRequest request) {
            EntitlementProviderResult<UsageOperationResult> result =
                    delegate.commitUsage(request);
            assertTrue(result.isSuccess());
            committed.countDown();
            try {
                if (!released.await(20, TimeUnit.SECONDS)) {
                    throw new AssertionError("post-entitlement gate timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("post-entitlement gate interrupted", interrupted);
            }
            return result;
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> cancelReservation(
                CancelReservationRequest request) {
            throw new AssertionError("finalization worker must not cancel reservation");
        }

        private boolean awaitCommitted() throws InterruptedException {
            return committed.await(20, TimeUnit.SECONDS);
        }

        private void release() {
            released.countDown();
        }
    }

    private static final class RejectingEntitlement implements EntitlementProvider {
        @Override
        public EntitlementProviderResult<MeetingGrant> authorizeStart(
                AuthorizeStartRequest request) {
            throw new AssertionError("terminal marker recovery must not authorize");
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> reserveUsage(
                ReserveUsageRequest request) {
            throw new AssertionError("terminal marker recovery must not reserve");
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> commitUsage(
                CommitUsageRequest request) {
            throw new AssertionError("terminal marker recovery must not commit usage");
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> cancelReservation(
                CancelReservationRequest request) {
            throw new AssertionError("terminal marker recovery must not cancel");
        }
    }

    private static final class CrashAfterEntitlementCommit implements EntitlementProvider {
        private final EntitlementProvider delegate;
        private final AtomicInteger commitCalls = new AtomicInteger();

        private CrashAfterEntitlementCommit(EntitlementProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public EntitlementProviderResult<MeetingGrant> authorizeStart(
                AuthorizeStartRequest request) {
            throw new AssertionError("finalization worker must not authorize start");
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> reserveUsage(
                ReserveUsageRequest request) {
            throw new AssertionError("finalization worker must not reserve usage");
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> commitUsage(
                CommitUsageRequest request) {
            commitCalls.incrementAndGet();
            EntitlementProviderResult<UsageOperationResult> result =
                    delegate.commitUsage(request);
            assertTrue(result.isSuccess());
            throw new CrashSignal();
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> cancelReservation(
                CancelReservationRequest request) {
            throw new AssertionError("finalization worker must not cancel reservation");
        }

        private int commitCalls() {
            return commitCalls.get();
        }
    }

    private static final class CapturingCompletion
            implements FinalizationCompletionUnitOfWork {
        private final FinalizationCompletionUnitOfWork delegate;
        private final AtomicReference<CompletionRequest> completion =
                new AtomicReference<>();
        private final AtomicReference<FailureRequest> failure =
                new AtomicReference<>();

        private CapturingCompletion(FinalizationCompletionUnitOfWork delegate) {
            this.delegate = delegate;
        }

        @Override
        public Result complete(CompletionRequest request) {
            completion.set(request);
            return delegate.complete(request);
        }

        @Override
        public Result fail(FailureRequest request) {
            failure.set(request);
            return delegate.fail(request);
        }

        @Override
        public Result recoverCompletion(CompletionRequest request) {
            completion.set(request);
            return delegate.recoverCompletion(request);
        }

        @Override
        public Result recoverFailure(FailureRequest request) {
            failure.set(request);
            return delegate.recoverFailure(request);
        }

        private CompletionRequest lastCompletion() {
            return completion.get();
        }

        private FailureRequest lastFailure() {
            return failure.get();
        }
    }

    private static final class RejectingCompletion
            implements FinalizationCompletionUnitOfWork {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Result complete(CompletionRequest request) {
            calls.incrementAndGet();
            throw new AssertionError("stale worker must not complete");
        }

        @Override
        public Result fail(FailureRequest request) {
            calls.incrementAndGet();
            throw new AssertionError("stale worker must not fail");
        }

        @Override
        public Result recoverCompletion(CompletionRequest request) {
            calls.incrementAndGet();
            throw new AssertionError("stale worker must not recover completion");
        }

        @Override
        public Result recoverFailure(FailureRequest request) {
            calls.incrementAndGet();
            throw new AssertionError("stale worker must not recover failure");
        }

        private int calls() {
            return calls.get();
        }
    }

    private static final class UnresolvedOnceCompletion
            implements FinalizationCompletionUnitOfWork {
        private final FinalizationCompletionUnitOfWork delegate;
        private final TerminalKind kind;
        private final AtomicBoolean executeInjected = new AtomicBoolean();
        private final AtomicBoolean recoverInjected = new AtomicBoolean();
        private final AtomicInteger executeCalls = new AtomicInteger();
        private final AtomicInteger recoverCalls = new AtomicInteger();

        private UnresolvedOnceCompletion(
                FinalizationCompletionUnitOfWork delegate,
                TerminalKind kind) {
            this.delegate = delegate;
            this.kind = kind;
        }

        @Override
        public Result complete(CompletionRequest request) {
            requireKind(TerminalKind.SUCCESS);
            executeCalls.incrementAndGet();
            if (executeInjected.compareAndSet(false, true)) {
                return new Failure(ErrorCode.OUTCOME_UNKNOWN);
            }
            return delegate.complete(request);
        }

        @Override
        public Result fail(FailureRequest request) {
            requireKind(TerminalKind.FAILURE);
            executeCalls.incrementAndGet();
            if (executeInjected.compareAndSet(false, true)) {
                return new Failure(ErrorCode.OUTCOME_UNKNOWN);
            }
            return delegate.fail(request);
        }

        @Override
        public Result recoverCompletion(CompletionRequest request) {
            requireKind(TerminalKind.SUCCESS);
            recoverCalls.incrementAndGet();
            if (recoverInjected.compareAndSet(false, true)) {
                return new Failure(ErrorCode.STORAGE_UNAVAILABLE);
            }
            return delegate.recoverCompletion(request);
        }

        @Override
        public Result recoverFailure(FailureRequest request) {
            requireKind(TerminalKind.FAILURE);
            recoverCalls.incrementAndGet();
            if (recoverInjected.compareAndSet(false, true)) {
                return new Failure(ErrorCode.STORAGE_UNAVAILABLE);
            }
            return delegate.recoverFailure(request);
        }

        private void requireKind(TerminalKind expected) {
            if (kind != expected) {
                throw new AssertionError("unexpected terminal operation kind");
            }
        }

        private int executeCalls() {
            return executeCalls.get();
        }

        private int recoverCalls() {
            return recoverCalls.get();
        }
    }

    private static final class CrashCompletion
            implements FinalizationCompletionUnitOfWork {
        private final FinalizationCompletionUnitOfWork delegate;
        private final TerminalKind kind;
        private final CrashCompletionPoint point;
        private final AtomicInteger completeCalls = new AtomicInteger();
        private final AtomicInteger failCalls = new AtomicInteger();

        private CrashCompletion(
                FinalizationCompletionUnitOfWork delegate,
                TerminalKind kind,
                CrashCompletionPoint point) {
            this.delegate = delegate;
            this.kind = kind;
            this.point = point;
        }

        @Override
        public Result complete(CompletionRequest request) {
            requireKind(TerminalKind.SUCCESS);
            completeCalls.incrementAndGet();
            if (point == CrashCompletionPoint.BEFORE_DELEGATE) {
                throw new CrashSignal();
            }
            Result result = delegate.complete(request);
            assertTrue(result instanceof Success);
            throw new CrashSignal();
        }

        @Override
        public Result fail(FailureRequest request) {
            requireKind(TerminalKind.FAILURE);
            failCalls.incrementAndGet();
            if (point == CrashCompletionPoint.BEFORE_DELEGATE) {
                throw new CrashSignal();
            }
            Result result = delegate.fail(request);
            assertTrue(result instanceof Success);
            throw new CrashSignal();
        }

        @Override
        public Result recoverCompletion(CompletionRequest request) {
            requireKind(TerminalKind.SUCCESS);
            return delegate.recoverCompletion(request);
        }

        @Override
        public Result recoverFailure(FailureRequest request) {
            requireKind(TerminalKind.FAILURE);
            return delegate.recoverFailure(request);
        }

        private void requireKind(TerminalKind expected) {
            if (kind != expected) {
                throw new AssertionError("unexpected terminal crash operation kind");
            }
        }

        private int completeCalls() {
            return completeCalls.get();
        }

        private int failCalls() {
            return failCalls.get();
        }
    }

    /** 固定、无正文的同 JVM 进程退出替身；真实双 JVM/Runtime.halt 属于 T14-09-10。 */
    private static final class CrashSignal extends RuntimeException {
        private CrashSignal() {
            super(null, null, false, false);
        }
    }

    private static final class InjectedCompletionWindowFailure
            extends RuntimeException {
        private InjectedCompletionWindowFailure() {
            super(null, null, false, false);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        private void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
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
            return current.get();
        }
    }

    private static final class FixedIds implements EntitlementIdGenerator {
        @Override
        public String newGrantId() {
            return "unused-worker-gate-grant";
        }

        @Override
        public String newReservationId() {
            return "unused-worker-gate-reservation";
        }

        @Override
        public String newLeaseToken() {
            return "unused-worker-gate-lease";
        }
    }

    private static final class CapturingHandler extends Handler {
        private final StringBuilder content = new StringBuilder();
        private int throwablesInspected;

        @Override
        public synchronized void publish(LogRecord record) {
            if (!isLoggable(record)) {
                return;
            }
            content.append(record.getMessage()).append('\n');
            if (record.getParameters() != null) {
                for (Object parameter : record.getParameters()) {
                    content.append(String.valueOf(parameter)).append('\n');
                }
            }
            appendThrowable(
                    record.getThrown(),
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        private void appendThrowable(Throwable failure, Set<Throwable> visited) {
            if (failure == null || !visited.add(failure)) {
                return;
            }
            throwablesInspected++;
            content.append(failure.getClass().getName()).append('\n');
            content.append(String.valueOf(failure.getMessage())).append('\n');
            for (Throwable suppressed : failure.getSuppressed()) {
                appendThrowable(suppressed, visited);
            }
            appendThrowable(failure.getCause(), visited);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private synchronized String content() {
            return content.toString();
        }

        private synchronized int throwablesInspected() {
            return throwablesInspected;
        }
    }
}
