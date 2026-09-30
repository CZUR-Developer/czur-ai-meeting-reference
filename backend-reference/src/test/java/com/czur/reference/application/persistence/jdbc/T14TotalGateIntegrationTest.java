package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.deletion.ArtifactDeletionTarget;
import com.czur.reference.application.deletion.ArtifactDeletionWorker;
import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.finalization.MockFinalizationWorker;
import com.czur.reference.application.entitlement.ExpiredReservationCleanupWorker;
import com.czur.reference.application.outbox.OutboxDispatcher;
import com.czur.reference.application.port.OutboxStore;
import com.czur.reference.application.port.ArtifactDeletionPlanStore;
import com.czur.reference.application.deletion.MeetingDeletionPlan;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.ExpiredReservationStore;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.protocol.json.ArtifactDeletionPlanJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-T14-11 total gate: a fresh deploy migration chain survives one real PostgreSQL
 * stop/start in two independent Maven/JVM phases.  The first JVM leaves a pending
 * Outbox event, an active leased event, and an expired unbound Reservation in
 * PostgreSQL; the second JVM runs the production Dispatcher and cleanup Worker against
 * those durable facts and reclaims the expired lease with a single winner.
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "T14_TOTAL_STATE_FILE", matches = ".+")
class T14TotalGateIntegrationTest {
    private static final String STATE_ENV = "T14_TOTAL_STATE_FILE";
    private static final String OUTBOX_EVENT_ID = "t14-total-outbox-event";
    private static final String OUTBOX_EVENT_TYPE = "T14TotalEvent";
    private static final String UNKNOWN_OUTBOX_EVENT_ID = "t14-total-unregistered-event";
    private static final String UNKNOWN_OUTBOX_EVENT_TYPE = "T14TotalUnregisteredEvent";
    private static final String ACTIVE_OUTBOX_EVENT_ID = "t14-total-active-event";
    private static final String ACTIVE_OUTBOX_EVENT_TYPE = "T14TotalActiveEvent";
    private static final String ACTIVE_LEASE_OWNER = "t14-total-owner-a";
    private static final Duration ACTIVE_LEASE_DURATION = Duration.ofSeconds(10);
    private static final String RESERVATION_ID = "reservation-t14-total-cleanup";
    private static final String DEPLOYMENT_SCOPE = "t14-total-deployment";
    private static final String SUBJECT_SCOPE = "t14-total-subject";
    private static final String EXPECTED_MEETING_ID = "meeting-t14-total-cleanup";
    private static final String FINALIZATION_SUFFIX = "total-finalization";
    private static final String FINALIZATION_MEETING_ID = "mtg_cross_jvm_total_finalization";
    private static final String DELETION_MEETING_ID = "mtg_cross_jvm_total_deletion";
    private static final String DELETION_OWNER_A = "t14-total-deletion-owner-a";
    private static final String DELETION_OWNER_B = "t14-total-deletion-owner-b";
    private static final Instant DELETION_ACCEPTED_AT =
            T14WorkerCrossJvmTestSupport.ACCEPTED_AT;
    private static final byte[] DELETION_CONTENT =
            "t14-total-deletion-content".getBytes(StandardCharsets.UTF_8);
    private static final Instant FIXTURE_NOW = Instant.parse("2026-09-30T04:00:00Z");
    private static final Set<String> MIGRATIONS = Set.of(
            "V001__initial_schema", "V002__outbox_lease_fencing",
            "V003__identity_entitlement", "V004__meeting_event_envelope",
            "V005__meeting_finalization_fact", "V006__meeting_read_model",
            "V007__meeting_manifest_revision", "V008__durable_input_fact",
            "V009__final_usage_fact", "V010__workflow_step_fencing",
            "V011__finalization_execution_artifact_ready",
            "V012__artifact_deletion_plan");
    private static final Set<String> STATE_KEYS = Set.of(
            "phase", "pidA", "startA", "pidB", "startB", "postmasterStart",
            "migrationCount", "outboxEventId", "reservationId", "activeEventId",
            "activeLeaseOwner", "activeLeaseToken", "activeLeaseExpiresAt",
            "activeLeaseClaimedAt", "activeLeaseVersion", "deletionMeetingId",
            "deletionArtifactRoot", "deletionStepKey", "deletionLeaseOwner",
            "deletionLeaseToken", "deletionLeaseExpiresAt", "deletionLeaseClaimedAt",
            "deletionLeaseVersion");
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<String> EMPTY_FACT_TABLES = Set.of(
            "meeting", "outbox_event", "usage_reservation", "workflow_step",
            "meeting_manifest_revision", "finalization_execution_fact", "artifact",
            "meeting_deletion_plan", "artifact_deletion_item");

    @Test
    void prepareBeforeRestart() throws Exception {
        rejectReservedEndpoint();
        Path state = stateFile();
        if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("state file already exists");
        }
        try (JdbcDatabaseRuntime runtime = startRuntime()) {
            assertTrue(runtime.checkReadiness().ready(),
                    "fresh V001-V012 database must pass readiness");
            assertEquals(MIGRATIONS, migrationVersions(runtime.dataSource()));
            assertAllFactTablesExist(runtime.dataSource());
            assertEmptyBusinessFacts(runtime.dataSource());
            seedOutboxEvent(runtime.dataSource());
            seedExpiredReservation(runtime.dataSource());
            prepareFinalizationLease(runtime.dataSource());
            DeletionPrepared deletion = prepareDeletionLease(runtime.dataSource());
            JdbcOutboxStore outbox = new JdbcOutboxStore(runtime.dataSource());
            assertEquals(OutboxStore.EventStatus.PENDING,
                    outbox.find(OUTBOX_EVENT_ID).orElseThrow().status());
            assertEquals(OutboxStore.EventStatus.PENDING,
                    outbox.find(UNKNOWN_OUTBOX_EVENT_ID).orElseThrow().status());
            OutboxStore.LeaseToken activeLease = claimActiveLease(outbox);
            writeState(state, Map.ofEntries(
                    Map.entry("phase", "PREPARED"),
                    Map.entry("pidA", Long.toString(ProcessHandle.current().pid())),
                    Map.entry("startA", Long.toString(ManagementFactory.getRuntimeMXBean().getStartTime())),
                    Map.entry("pidB", "UNSET"),
                    Map.entry("startB", "UNSET"),
                    Map.entry("postmasterStart", postmasterStart(runtime.dataSource()).toString()),
                    Map.entry("migrationCount", Integer.toString(MIGRATIONS.size())),
                    Map.entry("outboxEventId", OUTBOX_EVENT_ID),
                    Map.entry("reservationId", RESERVATION_ID),
                    Map.entry("activeEventId", activeLease.eventId()),
                    Map.entry("activeLeaseOwner", activeLease.owner()),
                    Map.entry("activeLeaseToken", activeLease.token()),
                    Map.entry("activeLeaseExpiresAt", activeLease.expiresAt().toString()),
                    Map.entry("activeLeaseClaimedAt", activeLease.claimedAt().toString()),
                    Map.entry("activeLeaseVersion", Long.toString(activeLease.leaseVersion())),
                    Map.entry("deletionMeetingId", deletion.meetingId()),
                    Map.entry("deletionArtifactRoot", deletion.artifactRoot().toString()),
                    Map.entry("deletionStepKey", deletion.lease().key().stepKey()),
                    Map.entry("deletionLeaseOwner", deletion.lease().owner()),
                    Map.entry("deletionLeaseToken", deletion.lease().token()),
                    Map.entry("deletionLeaseExpiresAt", deletion.lease().expiresAt().toString()),
                    Map.entry("deletionLeaseClaimedAt", deletion.lease().claimedAt().toString()),
                    Map.entry("deletionLeaseVersion", Long.toString(deletion.lease().leaseVersion()))));
        }
    }

    @Test
    void verifyAfterRestart() throws Exception {
        rejectReservedEndpoint();
        Map<String, String> expected = readState(stateFile());
        assertEquals("PREPARED", expected.get("phase"));
        assertEquals(OUTBOX_EVENT_ID, expected.get("outboxEventId"));
        assertEquals(RESERVATION_ID, expected.get("reservationId"));
        assertEquals(ACTIVE_OUTBOX_EVENT_ID, expected.get("activeEventId"));
        assertEquals(ACTIVE_LEASE_OWNER, expected.get("activeLeaseOwner"));
        assertEquals(DELETION_MEETING_ID, expected.get("deletionMeetingId"));
        assertEquals(DELETION_OWNER_A, expected.get("deletionLeaseOwner"));
        assertDifferentJvm(expected.get("pidA"), expected.get("startA"));
        try (JdbcDatabaseRuntime runtime = startRuntime()) {
            assertTrue(runtime.checkReadiness().ready(),
                    "V001-V012 readiness must survive PostgreSQL restart");
            DataSource dataSource = runtime.dataSource();
            Instant before = Instant.parse(expected.get("postmasterStart"));
            Instant after = postmasterStart(dataSource);
            assertTrue(after.isAfter(before), "postmaster start time must advance after restart");
            assertEquals(MIGRATIONS, migrationVersions(dataSource));
            assertEquals(Integer.toString(MIGRATIONS.size()), expected.get("migrationCount"));
            assertAllFactTablesExist(dataSource);
            runOutboxDispatcher(dataSource);
            runExpiredReservationCleanup(dataSource);
            JdbcOutboxStore outbox = new JdbcOutboxStore(dataSource);
            assertEquals(OutboxStore.EventStatus.PUBLISHED,
                    outbox.find(OUTBOX_EVENT_ID).orElseThrow().status());
            OutboxStore.OutboxEvent unknown = outbox.find(UNKNOWN_OUTBOX_EVENT_ID).orElseThrow();
            assertEquals(OutboxStore.EventStatus.PENDING, unknown.status());
            assertEquals(0, unknown.attemptCount());
            assertEquals(null, unknown.leaseOwner());
            assertEquals(null, unknown.leaseUntil());
            assertEquals(null, unknown.publishedAt());
            assertEquals(null, unknown.lastError());
            assertEquals(UsageReservation.State.CANCELLED,
                    findReservation(dataSource).orElseThrow().state());
            assertCleanupOperationFacts(dataSource);
            OutboxStore.LeaseToken activeLease = new OutboxStore.LeaseToken(
                    expected.get("activeEventId"),
                    expected.get("activeLeaseOwner"),
                    expected.get("activeLeaseToken"),
                    Instant.parse(expected.get("activeLeaseExpiresAt")),
                    Instant.parse(expected.get("activeLeaseClaimedAt")),
                    Long.parseLong(expected.get("activeLeaseVersion")));
            reclaimActiveLease(dataSource, activeLease);
            assertEquals(OutboxStore.EventStatus.PUBLISHED,
                    outbox.find(ACTIVE_OUTBOX_EVENT_ID).orElseThrow().status());
            runFinalizationWorker(dataSource);
            assertFinalizationFacts(dataSource);
            ArtifactDeletionLease deletionLease = deletionLease(expected);
            reclaimDeletionLease(dataSource, deletionLease);
            assertDeletionFacts(dataSource, deletionLease.meetingId(), deletionLease.artifactRoot());
            writeState(stateFile(), Map.ofEntries(
                    Map.entry("phase", "VERIFIED"),
                    Map.entry("pidA", expected.get("pidA")),
                    Map.entry("startA", expected.get("startA")),
                    Map.entry("pidB", Long.toString(ProcessHandle.current().pid())),
                    Map.entry("startB", Long.toString(ManagementFactory.getRuntimeMXBean().getStartTime())),
                    Map.entry("postmasterStart", after.toString()),
                    Map.entry("migrationCount", Integer.toString(MIGRATIONS.size())),
                    Map.entry("outboxEventId", OUTBOX_EVENT_ID),
                    Map.entry("reservationId", RESERVATION_ID),
                    Map.entry("activeEventId", ACTIVE_OUTBOX_EVENT_ID),
                    Map.entry("activeLeaseOwner", ACTIVE_LEASE_OWNER),
                    Map.entry("activeLeaseToken", "REDACTED"),
                    Map.entry("activeLeaseExpiresAt", activeLease.expiresAt().toString()),
                    Map.entry("activeLeaseClaimedAt", activeLease.claimedAt().toString()),
                    Map.entry("activeLeaseVersion", Long.toString(activeLease.leaseVersion())),
                    Map.entry("deletionMeetingId", deletionLease.meetingId()),
                    Map.entry("deletionArtifactRoot", deletionLease.artifactRoot().toString()),
                    Map.entry("deletionStepKey", deletionLease.lease().key().stepKey()),
                    Map.entry("deletionLeaseOwner", DELETION_OWNER_A),
                    Map.entry("deletionLeaseToken", "REDACTED"),
                    Map.entry("deletionLeaseExpiresAt", deletionLease.lease().expiresAt().toString()),
                    Map.entry("deletionLeaseClaimedAt", deletionLease.lease().claimedAt().toString()),
                    Map.entry("deletionLeaseVersion", Long.toString(deletionLease.lease().leaseVersion()))));
        }
    }

    private static void seedOutboxEvent(DataSource dataSource) {
        JdbcOutboxStore outbox = new JdbcOutboxStore(dataSource);
        for (String[] fixture : List.of(
                new String[]{OUTBOX_EVENT_ID, OUTBOX_EVENT_TYPE},
                new String[]{UNKNOWN_OUTBOX_EVENT_ID, UNKNOWN_OUTBOX_EVENT_TYPE},
                new String[]{ACTIVE_OUTBOX_EVENT_ID, ACTIVE_OUTBOX_EVENT_TYPE})) {
            OutboxStore.OutboxEvent event = OutboxStore.OutboxEvent.pending(
                    fixture[0],
                    "T14Total",
                    "aggregate-t14-total",
                    fixture[1],
                    "{\"fixture\":\"t14-total\"}",
                    FIXTURE_NOW.minusSeconds(1),
                    FIXTURE_NOW.minusSeconds(1));
            OutboxStore.AppendResult result = outbox.append(event);
            assertEquals(OutboxStore.AppendStatus.APPENDED, result.status());
        }
    }

    private static OutboxStore.LeaseToken claimActiveLease(JdbcOutboxStore outbox) {
        List<OutboxStore.ClaimedEvent> claimed = outbox.claim(new OutboxStore.ClaimRequest(
                Set.of(ACTIVE_OUTBOX_EVENT_TYPE),
                ACTIVE_LEASE_OWNER,
                FIXTURE_NOW,
                ACTIVE_LEASE_DURATION,
                1));
        assertEquals(1, claimed.size());
        OutboxStore.LeaseToken lease = claimed.get(0).lease();
        assertEquals(ACTIVE_OUTBOX_EVENT_ID, lease.eventId());
        assertEquals(ACTIVE_LEASE_OWNER, lease.owner());
        assertEquals(FIXTURE_NOW, lease.claimedAt());
        assertEquals(FIXTURE_NOW.plus(ACTIVE_LEASE_DURATION), lease.expiresAt());
        assertTrue(lease.leaseVersion() > 0);
        return lease;
    }

    private static void reclaimActiveLease(
            DataSource dataSource, OutboxStore.LeaseToken oldLease) throws Exception {
        Instant reclaimAt = FIXTURE_NOW.plus(ACTIVE_LEASE_DURATION).plusSeconds(1);
        JdbcOutboxStore outbox = new JdbcOutboxStore(dataSource);
        assertEquals(OutboxStore.PublishStatus.LEASE_EXPIRED,
                outbox.markPublished(oldLease, reclaimAt).status());
        assertEquals(OutboxStore.FailureStatus.LEASE_EXPIRED,
                outbox.markFailed(oldLease, reclaimAt, reclaimAt.plusSeconds(1),
                        "t14-total-stale-owner", 3).status());

        OutboxDispatcher first = activeDispatcher(dataSource, "t14-total-owner-b", reclaimAt);
        OutboxDispatcher second = activeDispatcher(dataSource, "t14-total-owner-c", reclaimAt);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<OutboxDispatcher.RunResult> firstResult = executor.submit(() -> {
                start.await();
                return first.runOnce();
            });
            Future<OutboxDispatcher.RunResult> secondResult = executor.submit(() -> {
                start.await();
                return second.runOnce();
            });
            start.countDown();
            List<OutboxDispatcher.RunResult> results = List.of(
                    firstResult.get(), secondResult.get());
            assertEquals(1, results.stream()
                    .mapToInt(OutboxDispatcher.RunResult::claimedCount).sum());
            assertEquals(1, results.stream()
                    .filter(result -> result.claimedCount() == 0 && result.items().isEmpty())
                    .count());
            assertEquals(1, results.stream()
                    .flatMap(result -> result.items().stream())
                    .filter(item -> item.status() == OutboxDispatcher.ItemStatus.PUBLISHED)
                    .count());
            assertEquals(OutboxStore.PublishStatus.ALREADY_PUBLISHED,
                    outbox.markPublished(oldLease, reclaimAt.plusSeconds(1)).status());
            assertEquals(OutboxStore.FailureStatus.ALREADY_PUBLISHED,
                    outbox.markFailed(oldLease, reclaimAt.plusSeconds(1),
                            reclaimAt.plusSeconds(2), "t14-total-late-owner", 3).status());
        } finally {
            executor.shutdownNow();
        }
    }

    private static OutboxDispatcher activeDispatcher(
            DataSource dataSource, String owner, Instant now) {
        return new OutboxDispatcher(
                new JdbcOutboxStore(dataSource),
                List.of(new OutboxDispatcher.HandlerRegistration(
                        ACTIVE_OUTBOX_EVENT_TYPE, (event, context) -> {
                            assertEquals(ACTIVE_OUTBOX_EVENT_ID, event.eventId());
                            assertEquals(ACTIVE_OUTBOX_EVENT_TYPE, event.eventType());
                        })),
                new OutboxDispatcher.Config(
                        owner,
                        java.time.Clock.fixed(now, ZoneOffset.UTC),
                        Duration.ofSeconds(30),
                        Duration.ZERO,
                        1,
                        3));
    }

    private static void seedExpiredReservation(DataSource dataSource) throws Exception {
        MeetingGrant grant = new MeetingGrant(
                "grant-t14-total",
                DEPLOYMENT_SCOPE,
                SUBJECT_SCOPE,
                "t14-profile",
                1,
                Set.of("transcript.final"),
                Set.of("transcript.final"),
                3_600_000,
                FIXTURE_NOW.minusSeconds(120),
                FIXTURE_NOW.plusSeconds(3_600));
        UsageReservation reservation = UsageReservation.reserved(
                RESERVATION_ID,
                grant,
                EXPECTED_MEETING_ID,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                FIXTURE_NOW.minusSeconds(60),
                FIXTURE_NOW.minusSeconds(10));
        UsageOperationResult reserve = UsageOperationResult.reserve(
                DEPLOYMENT_SCOPE,
                SUBJECT_SCOPE,
                "reserve-key-t14-total",
                "reserve-digest-t14-total",
                grant.expiresAt(),
                AuthorizedProfileSnapshot.from(grant),
                reservation);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcUsageReservationStore.insertReservation(connection, reservation);
            if (!JdbcUsageReservationStore.insertOperation(connection, reserve, FIXTURE_NOW)) {
                throw new IllegalStateException("T14 total reserve fixture already exists");
            }
            connection.commit();
        }
    }

    /**
     * 通过正式建会/Stop/Admission 链建立一条持久 Finalization checkpoint，再只领取
     * 其 Workflow lease 留给数据库重启后的正式 Worker。这里不手工插入 workflow
     * payload，确保恢复阶段确实消费数据库中的 canonical checkpoint。
     */
    private static void prepareFinalizationLease(DataSource dataSource) throws Exception {
        T14WorkerCrossJvmTestSupport.Prepared prepared =
                T14WorkerCrossJvmTestSupport.prepare(
                        dataSource, finalizationArtifactRoot(), FINALIZATION_SUFFIX);
        List<WorkflowStepStore.ClaimedStep> claimed = new JdbcWorkflowStepStore(dataSource)
                .claim(new WorkflowStepStore.ClaimRequest(
                        Set.of(FinalizationCheckpoint.STEP_TYPE),
                        "t14-total-finalizer-a",
                        T14WorkerCrossJvmTestSupport.FIRST_CLAIM_AT,
                        T14WorkerCrossJvmTestSupport.LEASE_DURATION,
                        1));
        assertEquals(1, claimed.size());
        assertEquals(prepared.meetingId(), claimed.get(0).step().key().meetingId());
        assertEquals(FINALIZATION_MEETING_ID, prepared.meetingId());
        assertEquals(WorkflowStep.Status.LEASED, claimed.get(0).step().status());
    }

    private static void runFinalizationWorker(DataSource dataSource) throws Exception {
        Instant recoveryAt = T14WorkerCrossJvmTestSupport.RECOVERY_CLAIM_AT;
        MockFinalizationWorker worker = new MockFinalizationWorker(
                new JdbcWorkflowStepStore(dataSource),
                new PersistentFileSystemMockArtifactStore(
                        finalizationArtifactRoot(), T14WorkerCrossJvmTestSupport.ARTIFACT_LIMIT),
                T14WorkerCrossJvmTestSupport.entitlementProvider(
                        dataSource, Clock.fixed(recoveryAt, ZoneOffset.UTC)),
                new JdbcFinalizationCompletionUnitOfWork(dataSource),
                new MockFinalizationWorker.Config(
                        "t14-total-finalizer-b",
                        Clock.fixed(recoveryAt, ZoneOffset.UTC),
                        T14WorkerCrossJvmTestSupport.LEASE_DURATION,
                        Duration.ofSeconds(5),
                        T14WorkerCrossJvmTestSupport.RETRY_DELAY,
                        1,
                        3));
        MockFinalizationWorker.RunResult result = worker.runOnce();
        assertEquals(1, result.claimedCount());
        assertEquals(1, result.items().size());
        assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED,
                result.items().get(0).status());
    }

    private static void assertFinalizationFacts(DataSource dataSource) throws Exception {
        assertEquals("READY", scalar(dataSource,
                "SELECT state FROM meeting WHERE meeting_id=?", FINALIZATION_MEETING_ID));
        assertEquals("COMMITTED", scalar(dataSource,
                "SELECT status FROM usage_reservation WHERE expected_meeting_id=?",
                FINALIZATION_MEETING_ID));
        assertEquals(1L, countForMeeting(dataSource, "final_usage_fact", FINALIZATION_MEETING_ID));
        assertEquals(1L, countForMeeting(
                dataSource, "finalization_execution_fact", FINALIZATION_MEETING_ID));
        assertEquals(1L, countForMeeting(
                dataSource, "meeting_manifest_revision", FINALIZATION_MEETING_ID));
        assertEquals(2L, countForMeeting(dataSource, "artifact", FINALIZATION_MEETING_ID));
        assertEquals(1L, countForMeeting(dataSource, "workflow_step", FINALIZATION_MEETING_ID));
        assertEquals("COMPLETED", scalar(dataSource,
                "SELECT status FROM workflow_step WHERE meeting_id=? AND step_type=?",
                FINALIZATION_MEETING_ID, FinalizationCheckpoint.STEP_TYPE));
        Path root = finalizationArtifactRoot().resolve("objects");
        assertEquals(2L, countSuffix(root, ".blob"));
        assertEquals(2L, countSuffix(root, ".ready"));
    }

    private static Path finalizationArtifactRoot() {
        return stateFile().getParent().resolve("finalization-artifacts").toAbsolutePath().normalize();
    }

    /**
     * 建立 V012 删除入口的正式持久事实，然后只领取删除 Workflow lease 留给重启后
     * 的 ArtifactDeletionWorker。Artifact/plan/item/workflow 均经正式 JDBC Store，
     * 测试不把 canonical plan 或存储 binding 写入状态文件。
     */
    private static DeletionPrepared prepareDeletionLease(DataSource dataSource) throws Exception {
        Path artifactRoot = deletionArtifactRoot();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting (meeting_id, deployment_scope, client_session_id,
                         subject_scope, profile_id, profile_revision, state, aggregate_version,
                         finalization_receipt_id, input_state, manifest_state,
                         created_at, updated_at)
                     VALUES (?, 't14-total-deployment', ?, 'LEGACY_UNOWNED', 'profile-cross', 1,
                         'DELETION_PENDING', 0, 'receipt-t14-total-deletion', 'INPUT_ACCEPTED',
                         'DELETION_PENDING', ?, ?)
                     """)) {
            statement.setString(1, DELETION_MEETING_ID);
            statement.setString(2, "session-" + DELETION_MEETING_ID);
            statement.setTimestamp(3, java.sql.Timestamp.from(DELETION_ACCEPTED_AT));
            statement.setTimestamp(4, java.sql.Timestamp.from(DELETION_ACCEPTED_AT));
            assertEquals(1, statement.executeUpdate());
        }

        PersistentFileSystemMockArtifactStore artifacts =
                new PersistentFileSystemMockArtifactStore(artifactRoot, 4096);
        WriteIntentSpec spec = new WriteIntentSpec(
                "artifact-t14-total-deletion-" + DELETION_MEETING_ID,
                DELETION_MEETING_ID,
                "transcript.final",
                "application/octet-stream",
                "zh-CN",
                1,
                DELETION_CONTENT.length,
                checksum(DELETION_CONTENT));
        ArtifactStore.WriteIntent intent = artifacts.createWriteIntent(spec).intent();
        ArtifactStore.StageResult staged = artifacts.writeContent(
                intent, new ByteArrayInputStream(DELETION_CONTENT));
        ArtifactStore.ArtifactDescriptor descriptor = artifacts.completeWrite(
                intent, staged.content()).artifact();
        ArtifactDeletionPlan plan = new ArtifactDeletionPlan(
                DELETION_MEETING_ID,
                DELETION_ACCEPTED_AT,
                null,
                null,
                List.of(ArtifactDeletionTarget.fromSpec(spec)));
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.APPLIED,
                    new JdbcArtifactReadyMetadataStore(connection).persistReady(descriptor).status());
            assertEquals(ArtifactDeletionPlanStore.PersistStatus.APPLIED,
                    new JdbcArtifactDeletionPlanStore(connection).register(plan).status());
            connection.commit();
        }
        JdbcWorkflowStepStore workflow = new JdbcWorkflowStepStore(dataSource);
        assertEquals(WorkflowStepStore.RegisterStatus.APPLIED,
                workflow.register(ArtifactDeletionPlanJson.workflowStep(plan)).status());
        List<WorkflowStepStore.ClaimedStep> claimed = workflow.claim(
                new WorkflowStepStore.ClaimRequest(
                        Set.of(ArtifactDeletionPlan.STEP_TYPE),
                        DELETION_OWNER_A,
                        T14WorkerCrossJvmTestSupport.FIRST_CLAIM_AT,
                        T14WorkerCrossJvmTestSupport.LEASE_DURATION,
                        1));
        assertEquals(1, claimed.size());
        assertEquals(WorkflowStep.Status.LEASED, claimed.getFirst().step().status());
        return new DeletionPrepared(DELETION_MEETING_ID, artifactRoot, claimed.getFirst().lease());
    }

    private static ArtifactDeletionLease deletionLease(Map<String, String> expected) {
        String meetingId = expected.get("deletionMeetingId");
        Path root = Path.of(expected.get("deletionArtifactRoot")).toAbsolutePath().normalize();
        assertEquals(deletionArtifactRoot(), root);
        WorkflowStep.Key key = new WorkflowStep.Key(
                meetingId, ArtifactDeletionPlan.STEP_TYPE, expected.get("deletionStepKey"));
        WorkflowStepStore.LeaseToken lease = new WorkflowStepStore.LeaseToken(
                key,
                expected.get("deletionLeaseOwner"),
                expected.get("deletionLeaseToken"),
                Instant.parse(expected.get("deletionLeaseExpiresAt")),
                Instant.parse(expected.get("deletionLeaseClaimedAt")),
                Long.parseLong(expected.get("deletionLeaseVersion")));
        assertEquals(T14WorkerCrossJvmTestSupport.RECOVERY_CLAIM_AT, lease.expiresAt());
        return new ArtifactDeletionLease(meetingId, root, lease);
    }

    private static void reclaimDeletionLease(
            DataSource dataSource, ArtifactDeletionLease deletion) throws Exception {
        Instant recoveryAt = T14WorkerCrossJvmTestSupport.RECOVERY_CLAIM_AT;
        JdbcWorkflowStepStore workflow = new JdbcWorkflowStepStore(dataSource);
        assertEquals(WorkflowStepStore.CompleteStatus.LEASE_EXPIRED,
                workflow.complete(deletion.lease(), recoveryAt).status());
        assertEquals(WorkflowStepStore.FailStatus.LEASE_EXPIRED,
                workflow.fail(deletion.lease(), recoveryAt, recoveryAt.plusSeconds(1),
                        "T14_STALE_OWNER", "T14_STALE_OWNER", 3).status());

        ArtifactDeletionWorker worker = new ArtifactDeletionWorker(
                workflow,
                deletionPlanStore(dataSource),
                new JdbcArtifactDeletionItemUnitOfWork(
                        dataSource,
                        new PersistentFileSystemMockArtifactStore(
                                deletion.artifactRoot(), T14WorkerCrossJvmTestSupport.ARTIFACT_LIMIT)),
                new JdbcArtifactDeletionTerminalUnitOfWork(dataSource),
                new ArtifactDeletionWorker.Config(
                        DELETION_OWNER_B,
                        Clock.fixed(recoveryAt, ZoneOffset.UTC),
                        T14WorkerCrossJvmTestSupport.LEASE_DURATION,
                        Duration.ofSeconds(5),
                        T14WorkerCrossJvmTestSupport.RETRY_DELAY,
                        1,
                        3));
        ArtifactDeletionWorker.RunResult result = worker.runOnce();
        assertEquals(1, result.claimedCount());
        assertEquals(1, result.items().size());
        assertEquals(ArtifactDeletionWorker.ItemStatus.COMPLETED,
                result.items().getFirst().status());
        assertEquals(WorkflowStepStore.CompleteStatus.ALREADY_COMPLETED,
                workflow.complete(deletion.lease(), recoveryAt.plusSeconds(1)).status());
        assertEquals(WorkflowStepStore.FailStatus.ALREADY_COMPLETED,
                workflow.fail(deletion.lease(), recoveryAt.plusSeconds(1),
                        recoveryAt.plusSeconds(2), "T14_STALE_OWNER", "T14_STALE_OWNER", 3).status());
    }

    /** Test-only adapter: production plan Store is connection-scoped by design. */
    private static ArtifactDeletionPlanStore deletionPlanStore(DataSource dataSource) {
        return new ArtifactDeletionPlanStore() {
            @Override
            public Optional<MeetingDeletionPlan> find(String meetingId) {
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    Optional<MeetingDeletionPlan> result =
                            new JdbcArtifactDeletionPlanStore(connection).find(meetingId);
                    connection.rollback();
                    return result;
                } catch (ArtifactDeletionPlanStore.StoreException failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new ArtifactDeletionPlanStore.StoreException(
                            ArtifactDeletionPlanStore.StoreErrorCode.STORAGE_UNAVAILABLE);
                }
            }

            @Override
            public PersistResult register(ArtifactDeletionPlan candidate) {
                throw new UnsupportedOperationException("deletion gate is read-only");
            }
        };
    }

    private static void assertDeletionFacts(
            DataSource dataSource, String meetingId, Path artifactRoot) throws Exception {
        assertEquals(1L, countForMeeting(dataSource, "meeting", meetingId));
        assertEquals("DELETED", scalar(dataSource,
                "SELECT state FROM meeting WHERE meeting_id=?", meetingId));
        assertEquals("DELETED", scalar(dataSource,
                "SELECT manifest_state FROM meeting WHERE meeting_id=?", meetingId));
        assertEquals("COMPLETED", scalar(dataSource,
                "SELECT status FROM meeting_deletion_plan WHERE meeting_id=?", meetingId));
        assertEquals(1L, countForMeeting(dataSource, "meeting_deletion_plan", meetingId));
        assertEquals(1L, countForMeeting(dataSource, "artifact_deletion_item", meetingId));
        assertEquals("DELETED", scalar(dataSource,
                "SELECT status FROM artifact_deletion_item WHERE meeting_id=? AND ordinal=0",
                meetingId));
        assertEquals(0L, countForMeeting(dataSource, "artifact", meetingId));
        assertEquals("COMPLETED", scalar(dataSource,
                "SELECT status FROM workflow_step WHERE meeting_id=? AND step_type=?",
                meetingId, ArtifactDeletionPlan.STEP_TYPE));
        assertEquals(1L, countForMeeting(dataSource, "workflow_step", meetingId));
        WorkflowStep workflow = new JdbcWorkflowStepStore(dataSource)
                .find(new WorkflowStep.Key(
                        meetingId,
                        ArtifactDeletionPlan.STEP_TYPE,
                        scalar(dataSource,
                                "SELECT step_key FROM workflow_step WHERE meeting_id=? "
                                        + "AND step_type=?",
                                meetingId, ArtifactDeletionPlan.STEP_TYPE)))
                .orElseThrow();
        assertNull(workflow.leaseOwner());
        assertNull(workflow.leaseUntil());
        assertNull(workflow.nextAttemptAt());
        assertEquals(1L, count(dataSource,
                "SELECT count(*) FROM outbox_event "
                        + "WHERE aggregate_id=? AND event_type='MeetingDeleted'", meetingId));
        Path tombstones = artifactRoot.resolve("tombstones");
        assertTrue(Files.isDirectory(tombstones, LinkOption.NOFOLLOW_LINKS));
        assertEquals(1L, countSuffix(tombstones, ".deleted"));
        assertEquals(0L, countSuffix(artifactRoot.resolve("objects"), ".ready"));
        assertEquals(0L, countSuffix(artifactRoot.resolve("objects"), ".blob"));
    }

    private static Path deletionArtifactRoot() {
        return stateFile().getParent().resolve("deletion-artifacts").toAbsolutePath().normalize();
    }

    private static String checksum(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record DeletionPrepared(
            String meetingId, Path artifactRoot, WorkflowStepStore.LeaseToken lease) {
    }

    private record ArtifactDeletionLease(
            String meetingId, Path artifactRoot, WorkflowStepStore.LeaseToken lease) {
    }

    private static long countForMeeting(
            DataSource dataSource, String table, String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + quote(table) + " WHERE meeting_id=?")) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static long count(DataSource dataSource, String sql, String value)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static String scalar(DataSource dataSource, String sql, String... values)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setString(index + 1, values[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private static long countSuffix(Path directory, String suffix) throws Exception {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(suffix)).count();
        }
    }

    private static void runOutboxDispatcher(DataSource dataSource) {
        OutboxDispatcher dispatcher = new OutboxDispatcher(
                new JdbcOutboxStore(dataSource),
                List.of(new OutboxDispatcher.HandlerRegistration(
                        OUTBOX_EVENT_TYPE, (event, context) -> {
                            assertEquals(OUTBOX_EVENT_ID, event.eventId());
                            assertEquals(OUTBOX_EVENT_TYPE, event.eventType());
                        })),
                new OutboxDispatcher.Config(
                        "t14-total-dispatcher",
                        java.time.Clock.fixed(FIXTURE_NOW, ZoneOffset.UTC),
                        Duration.ofMinutes(1),
                        Duration.ZERO,
                        10,
                        3));
        OutboxDispatcher.RunResult result = dispatcher.runOnce();
        assertEquals(1, result.claimedCount());
        assertEquals(1, result.items().size());
        assertEquals(OutboxDispatcher.ItemStatus.PUBLISHED, result.items().get(0).status());
    }

    private static void runExpiredReservationCleanup(DataSource dataSource) {
        ExpiredReservationStore store = new JdbcExpiredReservationStore(
                dataSource,
                new EntitlementIdGenerator() {
                    @Override
                    public String newGrantId() {
                        return "unused-t14-total-grant";
                    }

                    @Override
                    public String newReservationId() {
                        return "unused-t14-total-reservation";
                    }

                    @Override
                    public String newLeaseToken() {
                        return "t14-total-lease-token";
                    }
                });
        ExpiredReservationCleanupWorker worker = new ExpiredReservationCleanupWorker(
                store,
                T14WorkerCrossJvmTestSupport.entitlementProvider(
                        dataSource, java.time.Clock.fixed(FIXTURE_NOW, ZoneOffset.UTC)),
                new ExpiredReservationCleanupWorker.Config(
                        DEPLOYMENT_SCOPE,
                        "t14-total-cleaner",
                        java.time.Clock.fixed(FIXTURE_NOW, ZoneOffset.UTC),
                        Duration.ofMinutes(1),
                        10,
                        "t14-total-cleanup:",
                        "EXPIRED_UNBOUND"));
        ExpiredReservationCleanupWorker.RunResult result = worker.runOnce();
        assertEquals(1, result.claimedCount());
        assertEquals(1, result.items().size());
        assertEquals(ExpiredReservationCleanupWorker.ItemStatus.CANCELLED,
                result.items().get(0).status());
    }

    private static Optional<UsageReservation> findReservation(DataSource dataSource)
            throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            return JdbcUsageReservationStore.findReservation(
                    connection, DEPLOYMENT_SCOPE, SUBJECT_SCOPE, RESERVATION_ID);
        }
    }

    private static void assertCleanupOperationFacts(DataSource dataSource) throws Exception {
        List<OperationFact> facts = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                             "SELECT operation_scope, idempotency_key, request_digest, "
                             + "reservation_id, result_body::text "
                             + "FROM usage_operation "
                             + "WHERE deployment_scope = ? AND subject_scope = ? "
                             + "AND reservation_id = ? ORDER BY operation_scope, idempotency_key")) {
            statement.setString(1, DEPLOYMENT_SCOPE);
            statement.setString(2, SUBJECT_SCOPE);
            statement.setString(3, RESERVATION_ID);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    facts.add(new OperationFact(
                            rows.getString("operation_scope"),
                            rows.getString("idempotency_key"),
                            rows.getString("request_digest"),
                            rows.getString("reservation_id"),
                            rows.getString("result_body")));
                }
            }
        }
        assertEquals(2, facts.size(), "reservation must have exactly reserve and cancel facts");
        assertEquals(Set.of(UsageOperationScopes.RESERVE, UsageOperationScopes.CANCEL),
                facts.stream().map(OperationFact::operationScope).collect(java.util.stream.Collectors.toSet()));
        assertEquals(facts.size(), facts.stream().map(OperationFact::idempotencyKey).distinct().count());
        assertEquals(facts.size(), facts.stream().map(OperationFact::requestDigest).distinct().count());
        assertTrue(facts.stream().allMatch(fact -> RESERVATION_ID.equals(fact.reservationId())));

        List<OperationFact> cleanup = facts.stream()
                .filter(fact -> UsageOperationScopes.CANCEL.equals(fact.operationScope()))
                .toList();
        assertEquals(1, cleanup.size(), "cleanup must have one cancel operation");
        assertEquals("t14-total-cleanup:" + RESERVATION_ID, cleanup.get(0).idempotencyKey());
        assertTrue(!cleanup.get(0).idempotencyKey().isBlank());
        assertTrue(!cleanup.get(0).requestDigest().isBlank());
        assertTrue(!cleanup.get(0).resultBody().isBlank());
    }

    private record OperationFact(
            String operationScope,
            String idempotencyKey,
            String requestDigest,
            String reservationId,
            String resultBody) {
    }

    private static void assertDifferentJvm(String pidA, String startA) {
        long currentPid = ProcessHandle.current().pid();
        long currentStart = ManagementFactory.getRuntimeMXBean().getStartTime();
        assertFalse(Long.toString(currentPid).equals(pidA)
                        && Long.toString(currentStart).equals(startA),
                "worker phase must run in a different JVM");
    }

    private static JdbcDatabaseRuntime startRuntime() {
        return JdbcDatabaseRuntime.start(DatabaseRuntimeConfig.of(
                required("REFERENCE_TEST_DATABASE_URL"),
                required("REFERENCE_TEST_DATABASE_USER"),
                required("REFERENCE_TEST_DATABASE_PASSWORD"),
                Duration.ofSeconds(10), Duration.ofSeconds(5), 4, 1));
    }

    private static Set<String> migrationVersions(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT version FROM schema_migrations ORDER BY version");
             ResultSet rows = statement.executeQuery()) {
            Set<String> actual = new HashSet<>();
            while (rows.next()) {
                if (!actual.add(rows.getString(1))) {
                    throw new IllegalStateException("duplicate migration registration");
                }
            }
            return Set.copyOf(actual);
        }
    }

    private static void assertAllFactTablesExist(DataSource dataSource) throws Exception {
        String placeholders = String.join(",", EMPTY_FACT_TABLES.stream().map(value -> "?").toList());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema=current_schema() AND table_name IN ("
                             + placeholders + ")")) {
            int index = 1;
            for (String table : EMPTY_FACT_TABLES) {
                statement.setString(index++, table);
            }
            try (ResultSet rows = statement.executeQuery()) {
                Set<String> actual = new HashSet<>();
                while (rows.next()) {
                    actual.add(rows.getString(1));
                }
                assertEquals(EMPTY_FACT_TABLES, actual);
            }
        }
    }

    private static void assertEmptyBusinessFacts(DataSource dataSource) throws Exception {
        for (String table : EMPTY_FACT_TABLES) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT count(*) FROM " + quote(table));
                 ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0L, rows.getLong(1), "fresh table must be empty: " + table);
            }
        }
    }

    private static Instant postmasterStart(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT pg_postmaster_start_time()");
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getTimestamp(1).toInstant();
        }
    }

    private static Path stateFile() {
        Path path = Path.of(required(STATE_ENV));
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("state file must be absolute");
        }
        return path.normalize();
    }

    private static void writeState(Path path, Map<String, String> values) throws Exception {
        if (!values.keySet().equals(STATE_KEYS)) {
            throw new IllegalArgumentException("state keys are not exact");
        }
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw new IllegalStateException("state parent is unsafe");
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(path)) {
                throw new IllegalStateException("state file is unsafe");
            }
        } else {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE_PERMISSIONS));
        }
        String content = "phase=" + fixedValue(values.get("phase")) + '\n'
                + "pidA=" + fixedValue(values.get("pidA")) + '\n'
                + "startA=" + fixedValue(values.get("startA")) + '\n'
                + "pidB=" + fixedValue(values.get("pidB")) + '\n'
                + "startB=" + fixedValue(values.get("startB")) + '\n'
                + "postmasterStart=" + fixedValue(values.get("postmasterStart")) + '\n'
                + "migrationCount=" + fixedValue(values.get("migrationCount")) + '\n'
                + "outboxEventId=" + fixedValue(values.get("outboxEventId")) + '\n'
                + "reservationId=" + fixedValue(values.get("reservationId")) + '\n'
                + "activeEventId=" + fixedValue(values.get("activeEventId")) + '\n'
                + "activeLeaseOwner=" + fixedValue(values.get("activeLeaseOwner")) + '\n'
                + "activeLeaseToken=" + fixedValue(values.get("activeLeaseToken")) + '\n'
                + "activeLeaseExpiresAt=" + fixedValue(values.get("activeLeaseExpiresAt")) + '\n'
                + "activeLeaseClaimedAt=" + fixedValue(values.get("activeLeaseClaimedAt")) + '\n'
                + "activeLeaseVersion=" + fixedValue(values.get("activeLeaseVersion")) + '\n'
                + "deletionMeetingId=" + fixedValue(values.get("deletionMeetingId")) + '\n'
                + "deletionArtifactRoot=" + fixedValue(values.get("deletionArtifactRoot")) + '\n'
                + "deletionStepKey=" + fixedValue(values.get("deletionStepKey")) + '\n'
                + "deletionLeaseOwner=" + fixedValue(values.get("deletionLeaseOwner")) + '\n'
                + "deletionLeaseToken=" + fixedValue(values.get("deletionLeaseToken")) + '\n'
                + "deletionLeaseExpiresAt=" + fixedValue(values.get("deletionLeaseExpiresAt")) + '\n'
                + "deletionLeaseClaimedAt=" + fixedValue(values.get("deletionLeaseClaimedAt")) + '\n'
                + "deletionLeaseVersion=" + fixedValue(values.get("deletionLeaseVersion")) + '\n';
        Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.setPosixFilePermissions(path, PRIVATE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Linux acceptance scripts perform the exact mode check.
        }
    }

    private static Map<String, String> readState(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path) || Files.size(path) > 4096) {
            throw new IllegalStateException("state file is unsafe");
        }
        try {
            assertEquals(PRIVATE_PERMISSIONS, Files.getPosixFilePermissions(path));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX development filesystems still receive structural checks.
        }
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator <= 0 || separator != line.lastIndexOf('=')) {
                throw new IllegalStateException("state line is malformed");
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!STATE_KEYS.contains(key) || value.isBlank() || values.put(key, value) != null) {
                throw new IllegalStateException("state keys are not exact");
            }
        }
        if (!values.keySet().equals(STATE_KEYS)) {
            throw new IllegalStateException("state keys are incomplete");
        }
        return Map.copyOf(values);
    }

    private static String fixedValue(String value) {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('=') >= 0) {
            throw new IllegalArgumentException("state value is invalid");
        }
        return value;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static void rejectReservedEndpoint() {
        String url = required("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException("reserved database endpoint is forbidden");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
