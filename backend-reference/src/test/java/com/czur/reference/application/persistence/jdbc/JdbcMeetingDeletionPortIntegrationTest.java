package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingCommandEventFactory;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.finalization.FinalizationCommitPlan;
import com.czur.reference.application.finalization.FinalizationOperationIdentity;
import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.finalization.MockFinalizationArtifactContent;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.application.port.entitlement.UsageActual;
import com.czur.reference.application.manifest.ManifestDeletionRevisionFactory;
import com.czur.reference.application.manifest.ManifestResultAccessPolicy;
import com.czur.reference.application.port.DeleteRequest;
import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestFactStore;
import com.czur.reference.application.port.ManifestPublicationUnitOfWork;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.OutboxStore;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.json.FinalizationCheckpointJson;
import com.czur.reference.protocol.json.FinalizationExecutionFactJson;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.capability.ProductProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会议删除受理的 PostgreSQL 16 原子性、并发和故障语义门禁。
 *
 * <p>每个用例创建并删除自己的 schema，执行 V001～V007；数据库实例由测试命令
 * 显式注入，测试不会连接、挂载或清理任何已有开发卷。故障代理只包装本用例取得
 * 的连接，用于验证每个写点、commit/rollback 和连接恢复边界。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcMeetingDeletionPortIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-20T08:00:00Z");
    private static final Instant DELETE_CLOCK =
            Instant.parse("2026-09-20T09:00:00.123456789Z");
    private static final Instant DELETE_AT = DELETE_CLOCK.truncatedTo(ChronoUnit.MICROS);
    private static final String DEPLOYMENT = "delete-deployment";
    private static final String SUBJECT = "delete-subject";

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createSchemaAndMigrate() throws Exception {
        adminDataSource = postgres(null);
        schema = "delete_uow_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        dataSource = postgres(schema);
        for (String migration : List.of(
                "V001__initial_schema.sql",
                "V002__outbox_lease_fencing.sql",
                "V003__identity_entitlement.sql",
                "V004__meeting_event_envelope.sql",
                "V005__meeting_finalization_fact.sql",
                "V006__meeting_read_model.sql",
                "V007__meeting_manifest_revision.sql")) {
            applyMigration(migration);
        }
    }

    @AfterEach
    void dropSchema() throws Exception {
        if (adminDataSource == null || schema == null) {
            return;
        }
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS "
                    + quoteIdentifier(schema) + " CASCADE");
        }
    }

    @Test
    void lockedAccessDenialPrecedesSensitiveParsingAndIdempotencyRead() throws Exception {
        createOwned("outer", MeetingState.READY, "other-deployment", SUBJECT);
        createOwned("other-subject", MeetingState.READY, DEPLOYMENT, "other-subject");
        insertLegacy("legacy", MeetingState.READY, DEPLOYMENT);

        // 外域行故意写入非法状态；若 Repository 在归属检查前解析状态，本用例会
        // 返回 STORAGE_FAILURE 而不是统一 AccessDenied。
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE meeting DROP CONSTRAINT meeting_state_ck");
            statement.execute("UPDATE meeting SET state='private-broken-state' "
                    + "WHERE meeting_id='meeting-outer'");
        }

        DataSource guarded = faultInjectingDataSource(
                dataSource, FaultPoint.FAIL_IF_IDEMPOTENCY_READ,
                new FaultObservation());
        JdbcMeetingDeletionPort port = port(guarded, DELETE_CLOCK);

        assertInstanceOf(DeleteResult.AccessDenied.class,
                port.delete(request("meeting-outer", "outer-key", "outer-digest")));
        assertInstanceOf(DeleteResult.AccessDenied.class,
                port.delete(request("meeting-other-subject", "subject-key", "subject-digest")));
        assertInstanceOf(DeleteResult.AccessDenied.class,
                port.delete(request("meeting-legacy", "legacy-key", "legacy-digest")));
        assertInstanceOf(DeleteResult.NotFound.class,
                port.delete(request("meeting-missing", "missing-key", "missing-digest")));
    }

    /** T14-10-05：严格早期取消只注册零目标计划和删除 Workflow。 */
    @Test
    void admissionUnitFreezesStrictEarlyEmptyPlanAndWorkflowAtomically() throws Exception {
        for (String migration : List.of(
                "V008__durable_input_fact.sql",
                "V009__final_usage_fact.sql",
                "V010__workflow_step_fencing.sql",
                "V011__finalization_execution_artifact_ready.sql",
                "V012__artifact_deletion_plan.sql")) {
            applyMigration(migration);
        }
        String meetingId = "meeting-admission-early";
        createOwned("admission-early", MeetingState.CANCELLED, DEPLOYMENT, SUBJECT);
        // 早期取消没有最终收口；测试夹具的通用状态映射默认使用 FAILED，
        // 这里显式恢复冻结契约要求的 NOT_STARTED/无 Manifest 组合。
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE meeting SET manifest_state='NOT_STARTED' WHERE meeting_id=?")) {
            statement.setString(1, meetingId);
            assertEquals(1, statement.executeUpdate());
        }

        DeleteResult.Accepted result = assertInstanceOf(
                DeleteResult.Accepted.class,
                new JdbcMeetingDeletionPort(
                        dataSource, Clock.fixed(DELETE_AT, ZoneOffset.UTC), true)
                        .delete(request(meetingId, "admission-early-key", "admission-early-digest")));
        assertEquals(MeetingState.DELETION_PENDING, result.meeting().state());
        assertEquals(1, deletionEventCount(meetingId));
        try (Connection connection = transaction();
             PreparedStatement plan = connection.prepareStatement(
                     "SELECT target_count, status FROM meeting_deletion_plan WHERE meeting_id=?");
             PreparedStatement workflow = connection.prepareStatement(
                     "SELECT status, step_type FROM workflow_step WHERE meeting_id=?")) {
            plan.setString(1, meetingId);
            try (var rows = plan.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt("target_count"));
                assertEquals("PENDING", rows.getString("status"));
            }
            workflow.setString(1, meetingId);
            try (var rows = workflow.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("PENDING", rows.getString("status"));
                assertEquals("artifact-deletion", rows.getString("step_type"));
            }
            assertFalse(workflow.getMoreResults());
            connection.rollback();
        }
    }

    /** READY 会议必须按完整 finalization checkpoint 冻结全部删除目标。 */
    @Test
    void admissionUnitFreezesReadyCheckpointTargetsAndAllLocalFactsAtomically()
            throws Exception {
        for (String migration : List.of(
                "V008__durable_input_fact.sql",
                "V009__final_usage_fact.sql",
                "V010__workflow_step_fencing.sql",
                "V011__finalization_execution_artifact_ready.sql",
                "V012__artifact_deletion_plan.sql")) {
            applyMigration(migration);
        }
        String meetingId = "mtg_admission_ready";
        String suffix = "admission-ready";
        createOwnedWithMeetingId(meetingId, suffix, MeetingState.READY, DEPLOYMENT, SUBJECT);
        FinalizationInputFact input = new FinalizationInputFact(
                meetingId, "input-binding-ready", 7, 300_000,
                "zh-CN", List.of("en-US"), CREATED_AT.plusSeconds(2),
                "input-content-digest-ready");
        String inputDigest = FinalizationCheckpoint.inputFactDigest(input);
        FinalizationExecutionFact execution = executionFact(meetingId, suffix);
        String executionDigest = FinalizationExecutionFactJson.sha256(execution);
        byte[] content = new MockFinalizationArtifactContent(
                "transcript.final", executionDigest.substring(7), "zh-CN", 1,
                inputDigest.substring(7)).canonicalBytes();
        String checksum = "sha256:" + java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
        String artifactId = FinalizationCheckpoint.artifactId(
                meetingId, "transcript.final", 1);
        WriteIntentSpec spec = new WriteIntentSpec(
                artifactId, meetingId, "transcript.final",
                MockFinalizationArtifactContent.MEDIA_TYPE, "zh-CN", 1,
                content.length, checksum);
        FinalizationCheckpoint.ArtifactMaterial material =
                new FinalizationCheckpoint.ArtifactMaterial(
                        spec, content, new ContentClaim(content.length, checksum));
        FinalizationOperationIdentity identity = FinalizationOperationIdentity.commit(
                DEPLOYMENT, SUBJECT, meetingId, "reservation-admission-ready", 1,
                new UsageActual(UsageUnit.MEETING_DURATION_MS, 300_000),
                input.inputBinding(), input.contentDigest(), input.acceptedAt());
        FinalizationCommitPlan commitPlan = FinalizationCommitPlan.restore(
                meetingId, DEPLOYMENT, SUBJECT, identity.operationScope(),
                identity.idempotencyKey(), identity.requestDigest(),
                "reservation-admission-ready", 1, UsageUnit.MEETING_DURATION_MS,
                300_000, input.inputBinding(), input.contentDigest(), input.acceptedAt());
        FinalizationCheckpoint checkpoint = new FinalizationCheckpoint(
                meetingId, input.inputBinding(), inputDigest, executionDigest,
                List.of(material), commitPlan);
        WorkflowStep finalizationWorkflow = FinalizationCheckpointJson.workflowStep(checkpoint);

        try (Connection connection = transaction()) {
            assertEquals(com.czur.reference.application.port.DurableInputFactStore.PersistStatus.APPLIED,
                    JdbcDurableInputFactStore.persist(connection, input).status());
            assertEquals(com.czur.reference.application.port.FinalizationExecutionFactStore.PersistStatus.APPLIED,
                    new JdbcFinalizationExecutionFactStore(connection).persist(execution).status());
            assertEquals(com.czur.reference.application.port.FinalUsageFactStore.PersistStatus.APPLIED,
                    JdbcFinalUsageFactStore.persist(connection, checkpoint.finalUsageFact()).status());
            assertEquals(WorkflowStepStore.RegisterStatus.APPLIED,
                    JdbcWorkflowStepStore.register(connection, finalizationWorkflow).status());
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE workflow_step SET status='COMPLETED', next_attempt_at=NULL "
                            + "WHERE meeting_id=? AND step_type=? AND step_key=?")) {
                update.setString(1, meetingId);
                update.setString(2, finalizationWorkflow.key().stepType());
                update.setString(3, finalizationWorkflow.key().stepKey());
                assertEquals(1, update.executeUpdate());
            }
            ArtifactDescriptor descriptor = new ArtifactDescriptor(
                    new WriteIntent("intent-admission-ready", new StorageBinding(
                            "binding-admission-ready"), spec),
                    new ContentClaim(content.length, checksum));
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.APPLIED,
                    new JdbcArtifactReadyMetadataStore(connection)
                            .persistReady(descriptor).status());
            connection.commit();
        }
        appendManifest(readyManifest(meetingId, checkpoint),
                new ManifestFactStore.ExpectedPreviousRevision.None());

        DeleteResult.Accepted result = assertInstanceOf(
                DeleteResult.Accepted.class,
                new JdbcMeetingDeletionPort(
                        dataSource, Clock.fixed(DELETE_AT, ZoneOffset.UTC), true)
                        .delete(request(meetingId, "admission-ready-key", "admission-ready-digest")));
        assertEquals(MeetingState.DELETION_PENDING, result.meeting().state());
        assertEquals(2, manifestRevisionCount(meetingId));
        assertEquals(1, deletionEventCount(meetingId));
        try (Connection connection = transaction();
             PreparedStatement plan = connection.prepareStatement(
                     "SELECT target_count FROM meeting_deletion_plan WHERE meeting_id=?");
             PreparedStatement items = connection.prepareStatement(
                     "SELECT count(*) FROM artifact_deletion_item WHERE meeting_id=?");
             PreparedStatement workflow = connection.prepareStatement(
                     "SELECT status FROM workflow_step WHERE meeting_id=? AND step_type='artifact-deletion'")) {
            plan.setString(1, meetingId);
            try (var rows = plan.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            items.setString(1, meetingId);
            try (var rows = items.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            workflow.setString(1, meetingId);
            try (var rows = workflow.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("PENDING", rows.getString(1));
            }
            connection.rollback();
        }
    }

    @Test
    void admissionUnitFreezesReadyWithWarningsCheckpointTargets() throws Exception {
        applyDeletionAdmissionMigrations();
        AdmissionFixture fixture = prepareAdmissionFixture(
                "mtg_admission_warn", "admission-warn",
                MeetingState.READY_WITH_WARNINGS, ManifestStatus.READY_WITH_WARNINGS,
                WorkflowStep.Status.COMPLETED, true);
        DeleteResult.Accepted result = assertInstanceOf(DeleteResult.Accepted.class,
                new JdbcMeetingDeletionPort(dataSource, Clock.fixed(DELETE_AT, ZoneOffset.UTC), true)
                        .delete(request(fixture.meetingId(), "admission-warn-key", "admission-warn-digest")));
        assertEquals(MeetingState.DELETION_PENDING, result.meeting().state());
        assertEquals(1, deletionPlanTargetCount(fixture.meetingId()));
        assertEquals(1, deletionItemCount(fixture.meetingId()));
    }

    @Test
    void admissionUnitFreezesFailedCheckpointIncludingMissingReadyArtifact() throws Exception {
        applyDeletionAdmissionMigrations();
        AdmissionFixture fixture = prepareAdmissionFixture(
                "mtg_admission_failed", "admission-failed",
                MeetingState.FAILED, ManifestStatus.FAILED,
                WorkflowStep.Status.DEAD, false);
        DeleteResult.Accepted result = assertInstanceOf(DeleteResult.Accepted.class,
                new JdbcMeetingDeletionPort(dataSource, Clock.fixed(DELETE_AT, ZoneOffset.UTC), true)
                        .delete(request(fixture.meetingId(), "admission-failed-key", "admission-failed-digest")));
        assertEquals(MeetingState.DELETION_PENDING, result.meeting().state());
        assertEquals(1, deletionPlanTargetCount(fixture.meetingId()),
                "未 READY 的 checkpoint 目标也必须进入删除计划");
        assertEquals(1, deletionItemCount(fixture.meetingId()));
    }

    @Test
    void exactlyFourInitialStatesAreAcceptedAndAllOthersAreRejected() throws Exception {
        for (MeetingState state : List.of(
                MeetingState.READY,
                MeetingState.READY_WITH_WARNINGS,
                MeetingState.FAILED,
                MeetingState.CANCELLED)) {
            String suffix = "allowed-" + state.name().toLowerCase();
            createOwned(suffix, state, DEPLOYMENT, SUBJECT);
            DeleteResult.Accepted accepted = assertInstanceOf(
                    DeleteResult.Accepted.class,
                    port(dataSource, DELETE_CLOCK).delete(request(
                            "meeting-" + suffix, "key-" + suffix, "digest-" + suffix)));
            assertEquals(MeetingState.DELETION_PENDING, accepted.meeting().state());
            assertEquals(ManifestStatus.DELETION_PENDING,
                    accepted.meeting().manifestState());
            assertEquals(1, deletionEventCount("meeting-" + suffix));
        }

        List<MeetingState> rejected = List.of(
                MeetingState.CREATED,
                MeetingState.AUTHORIZED,
                MeetingState.STARTING,
                MeetingState.RUNNING,
                MeetingState.STOPPING,
                MeetingState.FINALIZING,
                MeetingState.DELETION_PENDING,
                MeetingState.DELETED);
        for (MeetingState state : rejected) {
            String suffix = "rejected-" + state.name().toLowerCase();
            createOwned(suffix, state, DEPLOYMENT, SUBJECT);
            assertConflict(
                    port(dataSource, DELETE_CLOCK).delete(request(
                            "meeting-" + suffix, "key-" + suffix, "digest-" + suffix)),
                    DeleteResult.ConflictReason.INVALID_MEETING_STATE);
            assertEquals(0, deletionIdempotencyCount("key-" + suffix));
            assertEquals(0, deletionEventCount("meeting-" + suffix));
        }

        createOwned("rejected-unknown", MeetingState.CREATED, DEPLOYMENT, SUBJECT);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE meeting DROP CONSTRAINT meeting_state_ck");
            statement.execute("UPDATE meeting SET state='UNKNOWN' "
                    + "WHERE meeting_id='meeting-rejected-unknown'");
        }
        assertConflict(
                port(dataSource, DELETE_CLOCK).delete(request(
                        "meeting-rejected-unknown", "key-unknown", "digest-unknown")),
                DeleteResult.ConflictReason.INVALID_MEETING_STATE);
    }

    @Test
    void firstAcceptanceAllowsNonDeletionManifestStatesAndRejectsCrossDeletionFacts()
            throws Exception {
        for (ManifestStatus manifest : List.of(
                ManifestStatus.NOT_STARTED,
                ManifestStatus.GENERATING,
                ManifestStatus.READY,
                ManifestStatus.READY_WITH_WARNINGS,
                ManifestStatus.FAILED)) {
            String suffix = "manifest-allowed-" + manifest.name().toLowerCase();
            createOwned(suffix, MeetingState.READY, DEPLOYMENT, SUBJECT);
            updateManifest("meeting-" + suffix, manifest);
            assertInstanceOf(DeleteResult.Accepted.class,
                    port(dataSource, DELETE_CLOCK).delete(request(
                            "meeting-" + suffix,
                            "key-" + suffix,
                            "digest-" + suffix)));
        }

        for (ManifestStatus manifest : List.of(
                ManifestStatus.DELETION_PENDING,
                ManifestStatus.DELETED)) {
            String suffix = "manifest-cross-" + manifest.name().toLowerCase();
            createOwned(suffix, MeetingState.READY, DEPLOYMENT, SUBJECT);
            updateManifest("meeting-" + suffix, manifest);
            assertConflict(
                    port(dataSource, DELETE_CLOCK).delete(request(
                            "meeting-" + suffix,
                            "key-" + suffix,
                            "digest-" + suffix)),
                    DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);
            assertEquals(0, deletionIdempotencyCount("key-" + suffix));
            assertEquals(0, deletionEventCount("meeting-" + suffix));
        }
    }

    @Test
    void ownedMalformedLockedFactsMapToSnapshotInconsistentBeforeIdempotencyRead()
            throws Exception {
        for (String suffix : List.of(
                "bad-enum", "null-profile-revision", "null-aggregate-version",
                "half-ownership", "bad-receipt")) {
            createOwned(suffix, MeetingState.READY, DEPLOYMENT, SUBJECT);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE meeting DROP CONSTRAINT meeting_state_ck");
            statement.execute("ALTER TABLE meeting DROP CONSTRAINT "
                    + "meeting_profile_revision_check");
            statement.execute("ALTER TABLE meeting ALTER COLUMN profile_revision DROP NOT NULL");
            statement.execute("ALTER TABLE meeting DROP CONSTRAINT "
                    + "meeting_aggregate_version_check");
            statement.execute("ALTER TABLE meeting ALTER COLUMN aggregate_version DROP NOT NULL");
            statement.execute("ALTER TABLE meeting DROP CONSTRAINT "
                    + "meeting_finalization_receipt_ck");
            statement.execute("UPDATE meeting SET state='BROKEN_STATE' "
                    + "WHERE meeting_id='meeting-bad-enum'");
            statement.execute("UPDATE meeting SET profile_revision=NULL "
                    + "WHERE meeting_id='meeting-null-profile-revision'");
            statement.execute("UPDATE meeting SET aggregate_version=NULL "
                    + "WHERE meeting_id='meeting-null-aggregate-version'");
            statement.execute("UPDATE meeting SET grant_id=NULL "
                    + "WHERE meeting_id='meeting-half-ownership'");
            statement.execute("UPDATE meeting SET input_state='INPUT_OPEN', "
                    + "finalization_receipt_id='illegal-receipt' "
                    + "WHERE meeting_id='meeting-bad-receipt'");
        }

        DataSource guarded = faultInjectingDataSource(
                dataSource, FaultPoint.FAIL_IF_IDEMPOTENCY_READ,
                new FaultObservation());
        for (String suffix : List.of(
                "bad-enum", "null-profile-revision", "null-aggregate-version",
                "half-ownership", "bad-receipt")) {
            assertConflict(
                    port(guarded, DELETE_CLOCK).delete(request(
                            "meeting-" + suffix,
                            "key-" + suffix,
                            "digest-" + suffix)),
                    DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);
            assertEquals(0, deletionIdempotencyCount("key-" + suffix));
            assertEquals(0, deletionEventCount("meeting-" + suffix));
        }
    }

    @Test
    void firstAcceptanceReplayDigestConflictAndFactTimesAreStable() throws Exception {
        createOwned("stable", MeetingState.READY, DEPLOYMENT, SUBJECT);
        DeleteRequest request = request("meeting-stable", "key-stable", "digest-stable");
        AtomicInteger clockReads = new AtomicInteger();
        Clock countingClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                clockReads.incrementAndGet();
                return DELETE_CLOCK;
            }
        };
        JdbcMeetingDeletionPort port = new JdbcMeetingDeletionPort(dataSource, countingClock);

        DeleteResult.Accepted first = assertInstanceOf(
                DeleteResult.Accepted.class, port.delete(request));
        assertEquals(DELETE_AT, first.meeting().updatedAt());
        assertEquals(1, clockReads.get());
        DeletionDbFacts facts = facts("meeting-stable");
        assertEquals(MeetingState.DELETION_PENDING, facts.state());
        assertEquals(ManifestStatus.DELETION_PENDING, facts.manifestState());
        assertEquals(DELETE_AT, facts.updatedAt());
        assertEquals(1, facts.aggregateVersion());
        assertEquals(InputState.INPUT_ACCEPTED, facts.inputState());
        assertEquals("setup-receipt-stable", facts.receipt());
        assertEquals(1, deletionIdempotencyCount("key-stable"));
        assertEquals(202, deletionResponseStatus("key-stable"));
        assertEquals(DELETE_AT, deletionCreatedAt("key-stable"));
        assertEquals(DELETE_AT, deletionOutboxTime("meeting-stable", "occurred_at"));
        assertEquals(DELETE_AT, deletionOutboxTime("meeting-stable", "available_at"));

        DeleteResult.Replayed replay = assertInstanceOf(
                DeleteResult.Replayed.class, port.delete(request));
        assertEquals(first.meeting(), replay.meeting());
        assertEquals(1, clockReads.get(), "重放不得读取时钟");
        assertEquals(1, deletionEventCount("meeting-stable"));
        assertEquals(0, manifestRevisionCount("meeting-stable"),
                "没有旧完整 Manifest 时不得虚构删除正文");

        assertConflict(port.delete(request(
                        "meeting-stable", "key-stable", "digest-other")),
                DeleteResult.ConflictReason.REQUEST_DIGEST_MISMATCH);
        assertEquals(1, clockReads.get(), "摘要冲突不得读取时钟");
    }

    @Test
    void malformedStatusBodyMeetingAndCurrentSnapshotFailClosed() throws Exception {
        // 摘要必须先比较：即使正文损坏，不同摘要仍只返回摘要冲突。
        accept("bad-body", "key-bad-body", "digest-bad-body");
        mutateIdempotency("key-bad-body",
                "response_body='{" + "\"private-attacker\":true}'::jsonb");
        assertConflict(port(dataSource, DELETE_CLOCK).delete(request(
                        "meeting-bad-body", "key-bad-body", "different-digest")),
                DeleteResult.ConflictReason.REQUEST_DIGEST_MISMATCH);
        assertConflict(port(dataSource, DELETE_CLOCK).delete(request(
                        "meeting-bad-body", "key-bad-body", "digest-bad-body")),
                DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);

        accept("bad-status", "key-bad-status", "digest-bad-status");
        mutateIdempotency("key-bad-status", "response_status=200");
        assertConflict(port(dataSource, DELETE_CLOCK).delete(request(
                        "meeting-bad-status", "key-bad-status", "digest-bad-status")),
                DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);

        accept("bad-meeting", "key-bad-meeting", "digest-bad-meeting");
        mutateIdempotency("key-bad-meeting", "response_body=jsonb_set(response_body, "
                + "'{meetingId}', '\"meeting-other\"'::jsonb)");
        assertConflict(port(dataSource, DELETE_CLOCK).delete(request(
                        "meeting-bad-meeting", "key-bad-meeting", "digest-bad-meeting")),
                DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);

        accept("bad-current", "key-bad-current", "digest-bad-current");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting SET updated_at = updated_at + interval '1 second'
                      WHERE meeting_id = 'meeting-bad-current'
                     """)) {
            assertEquals(1, statement.executeUpdate());
        }
        assertConflict(port(dataSource, DELETE_CLOCK).delete(request(
                        "meeting-bad-current", "key-bad-current", "digest-bad-current")),
                DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);
    }

    @Test
    void replayAfterWorkerDeletionReturnsFirstPendingSnapshot() throws Exception {
        DeleteResult.Accepted first = accept(
                "worker-deleted", "key-worker-deleted", "digest-worker-deleted");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting
                        SET state='DELETED', manifest_state='DELETED',
                            updated_at=?, aggregate_version=aggregate_version+1
                      WHERE meeting_id='meeting-worker-deleted'
                     """)) {
            statement.setTimestamp(1, Timestamp.from(DELETE_AT.plusSeconds(5)));
            assertEquals(1, statement.executeUpdate());
        }

        DeleteResult.Replayed replay = assertInstanceOf(
                DeleteResult.Replayed.class,
                port(dataSource, DELETE_CLOCK).delete(request(
                        "meeting-worker-deleted",
                        "key-worker-deleted",
                        "digest-worker-deleted")));
        assertEquals(first.meeting(), replay.meeting());
        assertEquals(MeetingState.DELETION_PENDING, replay.meeting().state());

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting SET finalization_receipt_id='changed-receipt'
                      WHERE meeting_id='meeting-worker-deleted'
                     """)) {
            assertEquals(1, statement.executeUpdate());
        }
        assertConflict(port(dataSource, DELETE_CLOCK).delete(request(
                        "meeting-worker-deleted",
                        "key-worker-deleted",
                        "digest-worker-deleted")),
                DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);
    }

    @Test
    void concurrentSameAndDifferentKeysAndCrossMeetingScopeAreDeterministic()
            throws Exception {
        createOwned("same-key", MeetingState.READY, DEPLOYMENT, SUBJECT);
        DeleteRequest same = request(
                "meeting-same-key", "key-same", "digest-same");
        List<DeleteResult> sameResults = concurrently(8,
                () -> port(dataSource, DELETE_CLOCK).delete(same));
        assertEquals(1, sameResults.stream()
                .filter(DeleteResult.Accepted.class::isInstance).count());
        assertEquals(7, sameResults.stream()
                .filter(DeleteResult.Replayed.class::isInstance).count());
        assertEquals(1, deletionEventCount("meeting-same-key"));

        createOwned("different-key", MeetingState.READY, DEPLOYMENT, SUBJECT);
        AtomicInteger keySequence = new AtomicInteger();
        List<DeleteResult> different = concurrently(8, () -> {
            int index = keySequence.incrementAndGet();
            return port(dataSource, DELETE_CLOCK).delete(request(
                    "meeting-different-key", "key-different-" + index,
                    "digest-different-" + index));
        });
        assertEquals(1, different.stream()
                .filter(DeleteResult.Accepted.class::isInstance).count());
        assertEquals(7, different.stream()
                .filter(result -> result instanceof DeleteResult.Conflict conflict
                        && conflict.reason()
                        == DeleteResult.ConflictReason.INVALID_MEETING_STATE).count());
        assertEquals(1, deletionEventCount("meeting-different-key"));

        createOwned("cross-a", MeetingState.READY, DEPLOYMENT, SUBJECT);
        createOwned("cross-b", MeetingState.READY, DEPLOYMENT, SUBJECT);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<DeleteResult> first = executor.submit(() -> {
                start.await();
                return port(dataSource, DELETE_CLOCK).delete(request(
                        "meeting-cross-a", "key-cross", "digest-cross"));
            });
            Future<DeleteResult> second = executor.submit(() -> {
                start.await();
                return port(dataSource, DELETE_CLOCK).delete(request(
                        "meeting-cross-b", "key-cross", "digest-cross"));
            });
            start.countDown();
            List<DeleteResult> cross = List.of(first.get(), second.get());
            assertEquals(1, cross.stream()
                    .filter(DeleteResult.Accepted.class::isInstance).count());
            assertEquals(1, cross.stream()
                    .filter(result -> result instanceof DeleteResult.Conflict conflict
                            && conflict.reason()
                            == DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT).count());
        }
        assertEquals(1, deletionEventCount("meeting-cross-a")
                + deletionEventCount("meeting-cross-b"));
    }

    @Test
    void completeManifestDeletionAppendsOnePreservingRevisionAndRevokesQuery()
            throws Exception {
        String suffix = "manifest-closed-loop";
        String meetingId = "mtg_manifest_closed_loop";
        createOwnedWithMeetingId(
                meetingId, suffix, MeetingState.READY, DEPLOYMENT, SUBJECT);
        MeetingManifest source = manifest(meetingId, 1, ManifestStatus.READY);
        appendManifest(source, new ManifestFactStore.ExpectedPreviousRevision.None());

        DeleteRequest request = request(meetingId, "key-manifest-loop", "digest-manifest-loop");
        DeleteResult.Accepted accepted = assertInstanceOf(
                DeleteResult.Accepted.class,
                port(dataSource, DELETE_CLOCK).delete(request));

        assertEquals(MeetingState.DELETION_PENDING, accepted.meeting().state());
        assertEquals(2, manifestRevisionCount(meetingId));
        assertEquals(
                ManifestDeletionRevisionFactory.deletionPendingAfter(source),
                latestManifest(meetingId));
        assertEquals(1, deletionEventCount(meetingId));
        assertEquals(1, deletionIdempotencyCount("key-manifest-loop"));
        assertInstanceOf(
                MeetingManifestPort.AccessDenied.class,
                new JdbcMeetingManifestQueryPort(
                        dataSource,
                        new ManifestResultAccessPolicy(
                                Clock.fixed(DELETE_AT, ZoneOffset.UTC),
                                Duration.ofDays(1)))
                        .get(new MeetingManifestPort.Request(meetingId, -1)));

        DeleteResult.Replayed replay = assertInstanceOf(
                DeleteResult.Replayed.class,
                port(dataSource, DELETE_CLOCK.plusSeconds(30)).delete(request));
        assertEquals(accepted.meeting(), replay.meeting());
        assertEquals(2, manifestRevisionCount(meetingId));
        assertEquals(1, deletionEventCount(meetingId));
        assertEquals(1, deletionIdempotencyCount("key-manifest-loop"));
    }

    @Test
    void latestManifestMustMatchLockedMeetingBeforeAnyDeletionFactIsWritten()
            throws Exception {
        String suffix = "manifest-mismatch";
        String meetingId = "mtg_manifest_mismatch";
        createOwnedWithMeetingId(
                meetingId, suffix, MeetingState.READY, DEPLOYMENT, SUBJECT);
        MeetingManifest mismatchedStatus = manifest(
                meetingId, 1, ManifestStatus.READY_WITH_WARNINGS);
        appendManifest(
                mismatchedStatus,
                new ManifestFactStore.ExpectedPreviousRevision.None());

        assertConflict(
                port(dataSource, DELETE_CLOCK).delete(request(
                        meetingId, "key-manifest-mismatch", "digest-manifest-mismatch")),
                DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);
        assertEquals(MeetingState.READY, facts(meetingId).state());
        assertEquals(1, manifestRevisionCount(meetingId));
        assertEquals(0, deletionIdempotencyCount("key-manifest-mismatch"));
        assertEquals(0, deletionEventCount(meetingId));
    }

    @Test
    void publishAndDeleteSerializeOnMeetingRowWithoutLosingARevision()
            throws Exception {
        String suffix = "publish-delete-race";
        String meetingId = "mtg_publish_delete_race";
        createOwnedWithMeetingId(
                meetingId, suffix, MeetingState.READY, DEPLOYMENT, SUBJECT);
        appendManifest(
                manifest(meetingId, 1, ManifestStatus.READY),
                new ManifestFactStore.ExpectedPreviousRevision.None());

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ManifestPublicationUnitOfWork.PublishResult> publication =
                    executor.submit(() -> {
                        start.await();
                        return new JdbcManifestPublicationUnitOfWork(
                                dataSource,
                                Clock.fixed(DELETE_AT.minusNanos(1_000), ZoneOffset.UTC))
                                .publish(new ManifestPublicationUnitOfWork.PublishRequest(
                                        meetingId,
                                        expectedFacts(suffix),
                                        manifest(meetingId, 2, ManifestStatus.READY)));
                    });
            Future<DeleteResult> deletion = executor.submit(() -> {
                start.await();
                return port(dataSource, DELETE_CLOCK).delete(request(
                        meetingId, "key-publish-delete", "digest-publish-delete"));
            });
            start.countDown();

            ManifestPublicationUnitOfWork.PublishResult publicationResult = publication.get();
            assertInstanceOf(DeleteResult.Accepted.class, deletion.get());
            assertTrue(publicationResult instanceof ManifestPublicationUnitOfWork.PublishApplied
                            || publicationResult instanceof ManifestPublicationUnitOfWork.Conflict,
                    () -> "unexpected publication result: " + publicationResult);
        }

        MeetingManifest latest = latestManifest(meetingId);
        assertEquals(ManifestStatus.DELETION_PENDING, latest.status());
        assertTrue(latest.manifestRevision() == 2 || latest.manifestRevision() == 3);
        assertEquals(latest.manifestRevision(), manifestRevisionCount(meetingId));
        assertEquals(MeetingState.DELETION_PENDING, facts(meetingId).state());
        assertEquals(1, deletionIdempotencyCount("key-publish-delete"));
        assertEquals(1, deletionEventCount(meetingId));
    }

    @Test
    void manifestAppendFailuresAndCommitUnknownKeepAllFactsAtomic()
            throws Exception {
        for (FaultPoint fault : List.of(
                FaultPoint.BEFORE_MANIFEST_INSERT,
                FaultPoint.AFTER_MANIFEST_INSERT)) {
            String suffix = "manifest-fault-" + fault.name().toLowerCase();
            String meetingId = "mtg_" + suffix.replace('-', '_');
            createOwnedWithMeetingId(
                    meetingId, suffix, MeetingState.READY, DEPLOYMENT, SUBJECT);
            appendManifest(
                    manifest(meetingId, 1, ManifestStatus.READY),
                    new ManifestFactStore.ExpectedPreviousRevision.None());

            assertUnavailable(
                    port(faultInjectingDataSource(
                                    dataSource, fault, new FaultObservation()), DELETE_CLOCK)
                            .delete(request(
                                    meetingId,
                                    "key-" + suffix,
                                    "digest-" + suffix)),
                    DeleteResult.UnavailableReason.STORAGE_FAILURE,
                    false);
            assertEquals(MeetingState.READY, facts(meetingId).state());
            assertEquals(1, manifestRevisionCount(meetingId));
            assertEquals(0, deletionIdempotencyCount("key-" + suffix));
            assertEquals(0, deletionEventCount(meetingId));
        }

        for (FaultPoint fault : List.of(FaultPoint.BEFORE_COMMIT, FaultPoint.AFTER_COMMIT)) {
            String suffix = "manifest-commit-" + fault.name().toLowerCase();
            String meetingId = "mtg_" + suffix.replace('-', '_');
            createOwnedWithMeetingId(
                    meetingId, suffix, MeetingState.READY, DEPLOYMENT, SUBJECT);
            appendManifest(
                    manifest(meetingId, 1, ManifestStatus.READY),
                    new ManifestFactStore.ExpectedPreviousRevision.None());
            FaultObservation observation = new FaultObservation();

            assertUnavailable(
                    port(faultInjectingDataSource(dataSource, fault, observation), DELETE_CLOCK)
                            .delete(request(
                                    meetingId,
                                    "key-" + suffix,
                                    "digest-" + suffix)),
                    DeleteResult.UnavailableReason.OUTCOME_UNKNOWN,
                    true);
            assertTrue(observation.abortAttempted.get());
            if (fault == FaultPoint.BEFORE_COMMIT) {
                assertEquals(MeetingState.READY, facts(meetingId).state());
                assertEquals(1, manifestRevisionCount(meetingId));
                assertEquals(0, deletionEventCount(meetingId));
            } else {
                assertEquals(MeetingState.DELETION_PENDING, facts(meetingId).state());
                assertEquals(2, manifestRevisionCount(meetingId));
                assertEquals(1, deletionEventCount(meetingId));
            }
        }
    }

    @Test
    void everyWritePointFailureRollsBackAllThreeFacts() throws Exception {
        List<FaultPoint> faults = List.of(
                FaultPoint.BEFORE_IDEMPOTENCY_INSERT,
                FaultPoint.AFTER_IDEMPOTENCY_INSERT,
                FaultPoint.BEFORE_MEETING_UPDATE,
                FaultPoint.AFTER_MEETING_UPDATE,
                FaultPoint.BEFORE_OUTBOX_INSERT,
                FaultPoint.AFTER_OUTBOX_INSERT);
        for (FaultPoint fault : faults) {
            String suffix = "fault-" + fault.name().toLowerCase();
            createOwned(suffix, MeetingState.READY, DEPLOYMENT, SUBJECT);
            FaultObservation observation = new FaultObservation();
            DeleteResult.Unavailable result = assertInstanceOf(
                    DeleteResult.Unavailable.class,
                    port(faultInjectingDataSource(dataSource, fault, observation), DELETE_CLOCK)
                            .delete(request(
                                    "meeting-" + suffix,
                                    "key-" + suffix,
                                    "digest-" + suffix)));
            assertTrue(result.retryable());
            assertEquals(DeleteResult.UnavailableReason.TRANSIENT_STORAGE_FAILURE,
                    result.reason());
            assertEquals(MeetingState.READY, facts("meeting-" + suffix).state());
            assertEquals(0, deletionIdempotencyCount("key-" + suffix));
            assertEquals(0, deletionEventCount("meeting-" + suffix));
            assertTrue(observation.restoreAutoCommitAttempted.get());
        }
    }

    @Test
    void sqlDataExceptionsBeforeCommitBecomeSnapshotConflictsAndRollback()
            throws Exception {
        TestLogHandler logs = new TestLogHandler();
        Logger logger = Logger.getLogger(JdbcMeetingDeletionPort.class.getName());
        logger.addHandler(logs);
        try {
            for (FaultPoint fault : List.of(
                    FaultPoint.DATA_EXCEPTION_MEETING_LOCK,
                    FaultPoint.DATA_EXCEPTION_IDEMPOTENCY_READ)) {
                String suffix = "data-exception-" + fault.name().toLowerCase();
                createOwned(suffix, MeetingState.READY, DEPLOYMENT, SUBJECT);
                FaultObservation observation = new FaultObservation();
                assertConflict(
                        port(faultInjectingDataSource(dataSource, fault, observation),
                                DELETE_CLOCK).delete(request(
                                "meeting-" + suffix,
                                "private-key-" + suffix,
                                "private-digest-" + suffix)),
                        DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);
                assertTrue(observation.rollbackAttempted.get());
                assertTrue(observation.restoreAutoCommitAttempted.get());
                assertFalse(observation.abortAttempted.get());
                assertEquals(MeetingState.READY, facts("meeting-" + suffix).state());
                assertEquals(0, deletionIdempotencyCount("private-key-" + suffix));
                assertEquals(0, deletionEventCount("meeting-" + suffix));
            }
        } finally {
            logger.removeHandler(logs);
        }

        String diagnostic = logs.text();
        assertTrue(diagnostic.contains("SNAPSHOT_INCONSISTENT"));
        assertTrue(diagnostic.contains("22000"));
        assertTrue(diagnostic.contains("MEETING_LOCK_AND_ACCESS_CHECK"));
        assertTrue(diagnostic.contains("IDEMPOTENCY_READ"));
        for (String forbidden : List.of(
                "private-dynamic-failure-detail",
                "private-key-data-exception",
                "private-digest-data-exception",
                DEPLOYMENT,
                SUBJECT)) {
            assertFalse(diagnostic.contains(forbidden),
                    () -> "SQL 数据异常日志泄露动态文本: " + forbidden);
        }
    }

    @Test
    void deletionCasPrimitiveRejectsUnprovedTransitionsBeforePreparingUpdate()
            throws Exception {
        createOwned("primitive-running", MeetingState.RUNNING, DEPLOYMENT, SUBJECT);
        assertDeletionPrimitiveRejected(
                "primitive-running",
                MeetingState.RUNNING,
                ManifestStatus.NOT_STARTED,
                DELETE_AT,
                null);

        createOwned("primitive-manifest", MeetingState.READY, DEPLOYMENT, SUBJECT);
        updateManifest("meeting-primitive-manifest", ManifestStatus.DELETION_PENDING);
        assertDeletionPrimitiveRejected(
                "primitive-manifest",
                MeetingState.READY,
                ManifestStatus.DELETION_PENDING,
                DELETE_AT,
                null);

        createOwned("primitive-backward", MeetingState.READY, DEPLOYMENT, SUBJECT);
        assertDeletionPrimitiveRejected(
                "primitive-backward",
                MeetingState.READY,
                ManifestStatus.READY,
                CREATED_AT,
                null);

        createOwned("primitive-forged", MeetingState.READY, DEPLOYMENT, SUBJECT);
        assertDeletionPrimitiveRejected(
                "primitive-forged",
                MeetingState.READY,
                ManifestStatus.READY,
                DELETE_AT,
                "forged-profile");
    }

    @Test
    void preexistingOutboxReplayAndConflictRejectAndRollback() throws Exception {
        for (boolean sameMessage : List.of(true, false)) {
            String suffix = sameMessage ? "outbox-replay" : "outbox-conflict";
            createOwned(suffix, MeetingState.READY, DEPLOYMENT, SUBJECT);
            DeletionDbFacts before = facts("meeting-" + suffix);
            MeetingStatus pending = new MeetingStatus(
                    "meeting-" + suffix,
                    MeetingState.DELETION_PENDING,
                    new FinalizationFact(before.inputState(), before.receipt()),
                    ManifestStatus.DELETION_PENDING,
                    DELETE_AT);
            OutboxStore.OutboxEvent expected =
                    MeetingCommandEventFactory.meetingDeletionRequested(DEPLOYMENT, pending);
            OutboxStore.OutboxEvent inserted = sameMessage
                    ? expected
                    : OutboxStore.OutboxEvent.pending(
                            expected.eventId(), expected.aggregateType(), expected.aggregateId(),
                            expected.eventType(), expected.payload().replace(
                                    "DELETION_PENDING", "DELETED"),
                            expected.occurredAt(), expected.availableAt());
            assertEquals(OutboxStore.AppendStatus.APPENDED,
                    new JdbcOutboxStore(dataSource).append(inserted).status());

            assertConflict(port(dataSource, DELETE_CLOCK).delete(request(
                            "meeting-" + suffix,
                            "key-" + suffix,
                            "digest-" + suffix)),
                    DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);
            assertEquals(MeetingState.READY, facts("meeting-" + suffix).state());
            assertEquals(0, deletionIdempotencyCount("key-" + suffix));
            assertEquals(1, deletionEventCount("meeting-" + suffix));
        }
    }

    @Test
    void clockFailuresBackwardTimeAndCasZeroAreClosedWithoutPartialFacts()
            throws Exception {
        createOwned("clock-runtime", MeetingState.READY, DEPLOYMENT, SUBJECT);
        Clock brokenClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                throw new IllegalStateException("private-clock-detail");
            }
        };
        assertUnavailable(
                new JdbcMeetingDeletionPort(dataSource, brokenClock).delete(request(
                        "meeting-clock-runtime", "key-clock-runtime", "digest-clock-runtime")),
                DeleteResult.UnavailableReason.STORAGE_FAILURE,
                false);

        createOwned("clock-backward", MeetingState.READY, DEPLOYMENT, SUBJECT);
        assertConflict(
                port(dataSource, CREATED_AT.minusSeconds(1)).delete(request(
                        "meeting-clock-backward", "key-clock-backward", "digest-clock-backward")),
                DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);
        assertEquals(0, deletionIdempotencyCount("key-clock-backward"));
        assertEquals(0, deletionEventCount("meeting-clock-backward"));

        createOwned("cas-zero", MeetingState.READY, DEPLOYMENT, SUBJECT);
        assertConflict(
                port(faultInjectingDataSource(
                                dataSource, FaultPoint.CAS_ZERO, new FaultObservation()),
                        DELETE_CLOCK).delete(request(
                                "meeting-cas-zero", "key-cas-zero", "digest-cas-zero")),
                DeleteResult.ConflictReason.SNAPSHOT_INCONSISTENT);
        assertEquals(MeetingState.READY, facts("meeting-cas-zero").state());
        assertEquals(0, deletionIdempotencyCount("key-cas-zero"));
        assertEquals(0, deletionEventCount("meeting-cas-zero"));
    }

    @Test
    void commitRollbackRestoreAndCloseFaultsPreserveOutcomeAndRedaction()
            throws Exception {
        String privateMarker = "private-dynamic-failure-detail";
        TestLogHandler logs = new TestLogHandler();
        Logger logger = Logger.getLogger(JdbcMeetingDeletionPort.class.getName());
        logger.addHandler(logs);
        try {
            createOwned("commit-before", MeetingState.READY, DEPLOYMENT, SUBJECT);
            FaultObservation before = new FaultObservation();
            assertUnavailable(port(faultInjectingDataSource(
                                    dataSource, FaultPoint.BEFORE_COMMIT, before), DELETE_CLOCK)
                            .delete(request(
                                    "meeting-commit-before", "private-key-before",
                                    "private-digest-before")),
                    DeleteResult.UnavailableReason.OUTCOME_UNKNOWN, true);
            assertFalse(before.restoreAutoCommitAttempted.get());
            assertTrue(before.abortAttempted.get());
            assertEquals(MeetingState.READY, facts("meeting-commit-before").state());

            createOwned("commit-after", MeetingState.READY, DEPLOYMENT, SUBJECT);
            FaultObservation after = new FaultObservation();
            assertUnavailable(port(faultInjectingDataSource(
                                    dataSource, FaultPoint.AFTER_COMMIT, after), DELETE_CLOCK)
                            .delete(request(
                                    "meeting-commit-after", "private-key-after",
                                    "private-digest-after")),
                    DeleteResult.UnavailableReason.OUTCOME_UNKNOWN, true);
            assertFalse(after.restoreAutoCommitAttempted.get());
            assertTrue(after.abortAttempted.get());
            assertEquals(MeetingState.DELETION_PENDING,
                    facts("meeting-commit-after").state());

            createOwned("commit-runtime", MeetingState.READY, DEPLOYMENT, SUBJECT);
            assertUnavailable(port(faultInjectingDataSource(
                                    dataSource, FaultPoint.RUNTIME_AFTER_COMMIT,
                                    new FaultObservation()), DELETE_CLOCK)
                            .delete(request(
                                    "meeting-commit-runtime", "key-runtime",
                                    "digest-runtime")),
                    DeleteResult.UnavailableReason.OUTCOME_UNKNOWN, true);

            for (FaultPoint rollbackFault : List.of(
                    FaultPoint.ROLLBACK_SQL, FaultPoint.ROLLBACK_RUNTIME)) {
                String suffix = rollbackFault.name().toLowerCase();
                createOwned(suffix, MeetingState.CREATED, DEPLOYMENT, SUBJECT);
                FaultObservation observation = new FaultObservation();
                assertUnavailable(port(faultInjectingDataSource(
                                        dataSource, rollbackFault, observation), DELETE_CLOCK)
                                .delete(request(
                                        "meeting-" + suffix,
                                        "key-" + suffix,
                                        "digest-" + suffix)),
                        DeleteResult.UnavailableReason.OUTCOME_UNKNOWN,
                        true);
                assertFalse(observation.restoreAutoCommitAttempted.get());
                assertTrue(observation.abortAttempted.get());
                assertTrue(observation.closeAttempted.get());
            }

            createOwned("restore-runtime", MeetingState.CREATED, DEPLOYMENT, SUBJECT);
            assertConflict(port(faultInjectingDataSource(
                                    dataSource, FaultPoint.RESTORE_AUTOCOMMIT_RUNTIME,
                                    new FaultObservation()), DELETE_CLOCK)
                            .delete(request(
                                    "meeting-restore-runtime", "key-restore",
                                    "digest-restore")),
                    DeleteResult.ConflictReason.INVALID_MEETING_STATE);

            createOwned("close-runtime", MeetingState.CREATED, DEPLOYMENT, SUBJECT);
            assertConflict(port(faultInjectingDataSource(
                                    dataSource, FaultPoint.CLOSE_RUNTIME,
                                    new FaultObservation()), DELETE_CLOCK)
                            .delete(request(
                                    "meeting-close-runtime", "key-close",
                                    "digest-close")),
                    DeleteResult.ConflictReason.INVALID_MEETING_STATE);
        } finally {
            logger.removeHandler(logs);
        }

        String diagnostic = logs.text();
        for (String forbidden : List.of(
                privateMarker,
                "private-key-before",
                "private-digest-before",
                DEPLOYMENT,
                SUBJECT,
                "setup-receipt-commit-before",
                System.getenv("REFERENCE_TEST_DATABASE_URL"))) {
            if (forbidden != null) {
                assertFalse(diagnostic.contains(forbidden),
                        () -> "日志泄露敏感文本: " + forbidden);
            }
        }
    }

    private DeleteResult.Accepted accept(String suffix, String key, String digest)
            throws Exception {
        createOwned(suffix, MeetingState.READY, DEPLOYMENT, SUBJECT);
        return assertInstanceOf(DeleteResult.Accepted.class,
                port(dataSource, DELETE_CLOCK).delete(request(
                        "meeting-" + suffix, key, digest)));
    }

    private JdbcMeetingDeletionPort port(DataSource source, Instant instant) {
        return new JdbcMeetingDeletionPort(
                source, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private DeleteRequest request(String meetingId, String key, String digest) {
        return new DeleteRequest(context(DEPLOYMENT, SUBJECT), meetingId, key, digest);
    }

    private static MeetingCommandContext context(String deployment, String subject) {
        return new MeetingCommandContext(
                deployment,
                new VerifiedIdentity(
                        new Subject(subject),
                        Set.of("ROLE_USER"),
                        Set.of(MeetingPermissions.DELETE),
                        CREATED_AT.minusSeconds(60),
                        DELETE_AT.plusSeconds(600),
                        "credential-reference"));
    }

    private MeetingAggregate.MeetingSnapshot createOwned(
            String suffix,
            MeetingState state,
            String deployment,
            String subject) throws Exception {
        return createOwnedWithMeetingId(
                "meeting-" + suffix, suffix, state, deployment, subject);
    }

    private MeetingAggregate.MeetingSnapshot createOwnedWithMeetingId(
            String meetingId,
            String suffix,
            MeetingState state,
            String deployment,
            String subject) throws Exception {
        Instant grantExpiresAt = DELETE_AT.plusSeconds(600);
        MeetingGrant grant = new MeetingGrant(
                "grant-" + suffix,
                deployment,
                subject,
                "profile-owned",
                7,
                Set.of("transcript.realtime"),
                Set.of("transcript.realtime"),
                3_600_000,
                CREATED_AT.minusSeconds(20),
                grantExpiresAt);
        UsageReservation reservation = UsageReservation.reserved(
                "reservation-" + suffix,
                grant,
                meetingId,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                CREATED_AT.minusSeconds(10),
                grantExpiresAt.minusMillis(1));
        UsageOperationResult reserve = UsageOperationResult.reserve(
                deployment,
                subject,
                "reserve-key-" + suffix,
                "reserve-digest-" + suffix,
                grantExpiresAt,
                AuthorizedProfileSnapshot.from(grant),
                reservation);
        try (Connection connection = transaction()) {
            JdbcUsageReservationStore.insertReservation(connection, reservation);
            assertTrue(JdbcUsageReservationStore.insertOperation(
                    connection, reserve, CREATED_AT.minusSeconds(10)));
            connection.commit();
        }
        var created = new JdbcOwnedMeetingCreationUnitOfWork(
                dataSource, Clock.fixed(CREATED_AT, ZoneOffset.UTC))
                .create(new OwnedMeetingCreationUnitOfWork.CreateRequest(
                        deployment,
                        subject,
                        meetingId,
                        "session-" + suffix,
                        "create-key-" + suffix,
                        "create-digest-" + suffix,
                        reserve));
        assertTrue(created instanceof OwnedMeetingCreationUnitOfWork.Success,
                () -> "owned setup failed: " + created);

        if (state != MeetingState.CREATED) {
            InputState inputState = inputStateFor(state);
            String receipt = inputState == InputState.INPUT_ACCEPTED
                    ? "setup-receipt-" + suffix : null;
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         UPDATE meeting
                            SET state=?, manifest_state=?, updated_at=?,
                                input_state=?, finalization_receipt_id=?
                          WHERE meeting_id=?
                         """)) {
                statement.setString(1, state.name());
                statement.setString(2, manifestFor(state).name());
                statement.setTimestamp(3, Timestamp.from(CREATED_AT.plusSeconds(1)));
                statement.setString(4, inputState.name());
                statement.setString(5, receipt);
                statement.setString(6, meetingId);
                assertEquals(1, statement.executeUpdate());
            }
        }
        return new JdbcMeetingRepository(dataSource).find(meetingId).orElseThrow();
    }

    private ManifestPublicationUnitOfWork.ExpectedMeetingFacts expectedFacts(String suffix) {
        return new ManifestPublicationUnitOfWork.ExpectedMeetingFacts(
                DEPLOYMENT,
                SUBJECT,
                "session-" + suffix,
                "profile-owned",
                7,
                FinalizationFact.accepted("setup-receipt-" + suffix));
    }

    private static MeetingManifest manifest(
            String meetingId,
            int revision,
            ManifestStatus status) {
        Instant endedAt = CREATED_AT.plusSeconds(300);
        return new MeetingManifest(
                "0.1",
                meetingId,
                revision,
                new MeetingManifest.Profile("profile-owned", 7),
                "Deletion transaction test",
                status,
                CREATED_AT,
                endedAt,
                300_000,
                new MeetingManifest.Languages("zh-CN", List.of("en-US")),
                List.of(new MeetingManifest.ArtifactRef(
                        "art_transcript_" + meetingId.substring(4),
                        "transcript.final",
                        "application/json",
                        "zh-CN",
                        1,
                        128,
                        "sha256:" + "ab".repeat(32),
                        MeetingManifest.ArtifactStatus.READY,
                        endedAt,
                        null,
                        null)),
                List.of(new MeetingManifest.Warning(
                        "OPTIONAL_SUMMARY_SKIPPED",
                        "summary",
                        "ai_meeting.summary_skipped",
                        null)));
    }

    private static MeetingManifest readyManifest(
            String meetingId, FinalizationCheckpoint checkpoint) {
        FinalizationCheckpoint.ArtifactMaterial material = checkpoint.artifacts().getFirst();
        WriteIntentSpec spec = material.spec();
        Instant endedAt = CREATED_AT.plusSeconds(300);
        return new MeetingManifest(
                "0.1", meetingId, 1,
                new MeetingManifest.Profile("profile-owned", 7),
                "Deletion transaction test", ManifestStatus.READY,
                CREATED_AT, endedAt, 300_000,
                new MeetingManifest.Languages("zh-CN", List.of("en-US")),
                List.of(new MeetingManifest.ArtifactRef(
                        spec.artifactId(), spec.artifactType(), spec.mediaType(),
                        spec.language(), spec.revision(), spec.expectedSize(),
                        spec.expectedChecksum(), MeetingManifest.ArtifactStatus.READY,
                        endedAt, null, null)),
                List.of());
    }

    private void applyDeletionAdmissionMigrations() throws Exception {
        for (String migration : List.of(
                "V008__durable_input_fact.sql",
                "V009__final_usage_fact.sql",
                "V010__workflow_step_fencing.sql",
                "V011__finalization_execution_artifact_ready.sql",
                "V012__artifact_deletion_plan.sql")) {
            applyMigration(migration);
        }
    }

    private AdmissionFixture prepareAdmissionFixture(
            String meetingId,
            String suffix,
            MeetingState meetingState,
            ManifestStatus manifestStatus,
            WorkflowStep.Status workflowStatus,
            boolean persistMetadata) throws Exception {
        createOwnedWithMeetingId(meetingId, suffix, meetingState, DEPLOYMENT, SUBJECT);
        FinalizationInputFact input = new FinalizationInputFact(
                meetingId, "input-binding-" + suffix, 7, 300_000,
                "zh-CN", List.of("en-US"), CREATED_AT.plusSeconds(2),
                "input-content-digest-" + suffix);
        String inputDigest = FinalizationCheckpoint.inputFactDigest(input);
        FinalizationExecutionFact execution = executionFact(meetingId, suffix);
        String executionDigest = FinalizationExecutionFactJson.sha256(execution);
        byte[] content = new MockFinalizationArtifactContent(
                "transcript.final", executionDigest.substring(7), "zh-CN", 1,
                inputDigest.substring(7)).canonicalBytes();
        String checksum = "sha256:" + java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
        WriteIntentSpec spec = new WriteIntentSpec(
                FinalizationCheckpoint.artifactId(meetingId, "transcript.final", 1),
                meetingId, "transcript.final", MockFinalizationArtifactContent.MEDIA_TYPE,
                "zh-CN", 1, content.length, checksum);
        FinalizationCheckpoint checkpoint = new FinalizationCheckpoint(
                meetingId, input.inputBinding(), inputDigest, executionDigest,
                List.of(new FinalizationCheckpoint.ArtifactMaterial(
                        spec, content, new ContentClaim(content.length, checksum))),
                FinalizationCommitPlan.restore(
                        meetingId, DEPLOYMENT, SUBJECT,
                        FinalizationOperationIdentity.OPERATION_SCOPE,
                        FinalizationOperationIdentity.commit(
                                DEPLOYMENT, SUBJECT, meetingId,
                                "reservation-" + suffix, 1,
                                new UsageActual(UsageUnit.MEETING_DURATION_MS, 300_000),
                                input.inputBinding(), input.contentDigest(), input.acceptedAt())
                                .idempotencyKey(),
                        FinalizationOperationIdentity.commit(
                                DEPLOYMENT, SUBJECT, meetingId,
                                "reservation-" + suffix, 1,
                                new UsageActual(UsageUnit.MEETING_DURATION_MS, 300_000),
                                input.inputBinding(), input.contentDigest(), input.acceptedAt())
                                .requestDigest(),
                        "reservation-" + suffix, 1, UsageUnit.MEETING_DURATION_MS,
                        300_000, input.inputBinding(), input.contentDigest(), input.acceptedAt()));
        WorkflowStep workflow = FinalizationCheckpointJson.workflowStep(checkpoint);
        try (Connection connection = transaction()) {
            assertEquals(com.czur.reference.application.port.DurableInputFactStore.PersistStatus.APPLIED,
                    JdbcDurableInputFactStore.persist(connection, input).status());
            assertEquals(com.czur.reference.application.port.FinalizationExecutionFactStore.PersistStatus.APPLIED,
                    new JdbcFinalizationExecutionFactStore(connection).persist(execution).status());
            assertEquals(com.czur.reference.application.port.FinalUsageFactStore.PersistStatus.APPLIED,
                    JdbcFinalUsageFactStore.persist(connection, checkpoint.finalUsageFact()).status());
            assertEquals(WorkflowStepStore.RegisterStatus.APPLIED,
                    JdbcWorkflowStepStore.register(connection, workflow).status());
            String state = workflowStatus == WorkflowStep.Status.DEAD ? "DEAD" : "COMPLETED";
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE workflow_step SET status=?, next_attempt_at=NULL, "
                            + "error_code=?, error_message=? WHERE meeting_id=?")) {
                update.setString(1, state);
                update.setString(2, workflowStatus == WorkflowStep.Status.DEAD ? "MODEL_FAILURE" : null);
                update.setString(3, workflowStatus == WorkflowStep.Status.DEAD ? "stable failure" : null);
                update.setString(4, meetingId);
                assertEquals(1, update.executeUpdate());
            }
            if (persistMetadata) {
                ArtifactDescriptor descriptor = new ArtifactDescriptor(
                        new WriteIntent("intent-" + suffix,
                                new StorageBinding("binding-" + suffix), spec),
                        new ContentClaim(content.length, checksum));
                assertEquals(ArtifactReadyMetadataStore.PersistStatus.APPLIED,
                        new JdbcArtifactReadyMetadataStore(connection).persistReady(descriptor).status());
            }
            connection.commit();
        }
        appendManifest(admissionManifest(meetingId, checkpoint, manifestStatus),
                new ManifestFactStore.ExpectedPreviousRevision.None());
        return new AdmissionFixture(meetingId, checkpoint);
    }

    private static MeetingManifest admissionManifest(
            String meetingId, FinalizationCheckpoint checkpoint, ManifestStatus status) {
        var material = checkpoint.artifacts().getFirst();
        var spec = material.spec();
        Instant endedAt = CREATED_AT.plusSeconds(300);
        MeetingManifest.ArtifactStatus artifactStatus = status == ManifestStatus.FAILED
                ? MeetingManifest.ArtifactStatus.FAILED
                : MeetingManifest.ArtifactStatus.READY;
        MeetingManifest.ArtifactFailure failure = artifactStatus == MeetingManifest.ArtifactStatus.FAILED
                ? new MeetingManifest.ArtifactFailure("MODEL", "MODEL_FAILURE", false, "diagnostic") : null;
        return new MeetingManifest("0.1", meetingId, 1,
                new MeetingManifest.Profile("profile-owned", 7), "Deletion transaction test",
                status, CREATED_AT, endedAt, 300_000,
                new MeetingManifest.Languages("zh-CN", List.of("en-US")),
                List.of(new MeetingManifest.ArtifactRef(
                        spec.artifactId(), spec.artifactType(), spec.mediaType(), spec.language(),
                        spec.revision(), spec.expectedSize(), spec.expectedChecksum(), artifactStatus,
                        endedAt, null, failure)), List.of());
    }

    private static FinalizationExecutionFact executionFact(String meetingId, String suffix) {
        ProductProfile profile = new ProductProfile(
                "profile-owned", 7, Set.of("asr"), Set.of(), Map.of(), Map.of(), Map.of(),
                Map.of("asr", List.of("caption")),
                Map.of("asr", List.of("transcript.final")));
        CapabilityPlan plan = new CapabilityPlan(1, List.of(new CapabilityDecision(
                "asr", CapabilityDecision.Availability.AVAILABLE, true,
                CapabilityDecision.Action.ENABLE, null, List.of("caption"),
                List.of("transcript.final"), null, 1)));
        return FinalizationExecutionFact.freeze(
                meetingId, CREATED_AT, "execution-" + suffix, profile, plan);
    }

    private int deletionPlanTargetCount(String meetingId) throws SQLException {
        try (Connection connection = transaction();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT target_count FROM meeting_deletion_plan WHERE meeting_id=?")) {
            statement.setString(1, meetingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                int count = rows.getInt(1);
                connection.rollback();
                return count;
            }
        }
    }

    private int deletionItemCount(String meetingId) throws SQLException {
        try (Connection connection = transaction();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM artifact_deletion_item WHERE meeting_id=?")) {
            statement.setString(1, meetingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                int count = rows.getInt(1);
                connection.rollback();
                return count;
            }
        }
    }

    private record AdmissionFixture(String meetingId, FinalizationCheckpoint checkpoint) {
    }

    private void appendManifest(
            MeetingManifest manifest,
            ManifestFactStore.ExpectedPreviousRevision previous) throws SQLException {
        try (Connection connection = transaction()) {
            ManifestFactStore.AppendResult result = new JdbcManifestFactStore(connection).append(
                    new ManifestFactStore.AppendRequest(manifest, previous));
            assertEquals(ManifestFactStore.AppendStatus.APPENDED, result.status());
            connection.commit();
        }
    }

    private MeetingManifest latestManifest(String meetingId) throws SQLException {
        try (Connection connection = transaction()) {
            MeetingManifest latest = new JdbcManifestFactStore(connection)
                    .findLatest(meetingId)
                    .orElseThrow();
            connection.rollback();
            return latest;
        }
    }

    private void insertLegacy(String suffix, MeetingState state, String deployment) {
        new JdbcMeetingRepository(dataSource).save(
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        "meeting-" + suffix,
                        deployment,
                        "session-" + suffix,
                        "profile-legacy",
                        1,
                        state,
                        CREATED_AT,
                        CREATED_AT.plusSeconds(1)));
    }

    private static InputState inputStateFor(MeetingState state) {
        return switch (state) {
            case READY, READY_WITH_WARNINGS, FAILED, FINALIZING -> InputState.INPUT_ACCEPTED;
            case CANCELLED, DELETED -> InputState.INPUT_CANCELLED;
            default -> InputState.INPUT_OPEN;
        };
    }

    private static ManifestStatus manifestFor(MeetingState state) {
        return switch (state) {
            case READY -> ManifestStatus.READY;
            case READY_WITH_WARNINGS -> ManifestStatus.READY_WITH_WARNINGS;
            case FAILED, CANCELLED -> ManifestStatus.FAILED;
            case FINALIZING -> ManifestStatus.GENERATING;
            case DELETION_PENDING -> ManifestStatus.DELETION_PENDING;
            case DELETED -> ManifestStatus.DELETED;
            default -> ManifestStatus.NOT_STARTED;
        };
    }

    private void mutateIdempotency(String key, String assignment) throws SQLException {
        String scope = IdempotencyScopeCodec.meetingDelete(DEPLOYMENT, SUBJECT);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE idempotency_record SET " + assignment
                             + " WHERE subject_scope=? AND idempotency_key=?")) {
            statement.setString(1, scope);
            statement.setString(2, key);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void updateManifest(String meetingId, ManifestStatus manifest) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE meeting SET manifest_state=? WHERE meeting_id=?")) {
            statement.setString(1, manifest.name());
            statement.setString(2, meetingId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void assertDeletionPrimitiveRejected(
            String suffix,
            MeetingState expectedState,
            ManifestStatus expectedManifest,
            Instant advancedAt,
            String profileOverride) throws Exception {
        DataSource guarded = faultInjectingDataSource(
                dataSource, FaultPoint.FAIL_IF_MEETING_UPDATE,
                new FaultObservation());
        try (Connection connection = guarded.getConnection()) {
            connection.setAutoCommit(false);
            JdbcMeetingRepository.DeletionFound found = assertInstanceOf(
                    JdbcMeetingRepository.DeletionFound.class,
                    JdbcMeetingRepository.lockForDeletion(
                            connection,
                            "meeting-" + suffix,
                            context(DEPLOYMENT, SUBJECT)));
            JdbcMeetingRepository.DeletionMeeting before = found.meeting();
            MeetingAggregate.MeetingSnapshot snapshot = before.snapshot();
            MeetingAggregate.MeetingSnapshot advanced =
                    new MeetingAggregate.MeetingSnapshot(
                            snapshot.meetingId(),
                            snapshot.deploymentScope(),
                            snapshot.clientSessionId(),
                            snapshot.subjectScope(),
                            snapshot.grantId(),
                            snapshot.grantExpiresAt(),
                            snapshot.usageReservationId(),
                            profileOverride == null ? snapshot.profileId() : profileOverride,
                            snapshot.profileRevision(),
                            MeetingState.DELETION_PENDING,
                            snapshot.createdAt(),
                            advancedAt);
            MeetingStatus accepted = new MeetingStatus(
                    advanced.meetingId(),
                    MeetingState.DELETION_PENDING,
                    before.finalization(),
                    ManifestStatus.DELETION_PENDING,
                    advanced.updatedAt());

            assertThrows(IllegalArgumentException.class,
                    () -> JdbcMeetingRepository.markDeletionPending(
                            connection, before, advanced, accepted));
            connection.rollback();
        }

        DeletionDbFacts after = facts("meeting-" + suffix);
        assertEquals(expectedState, after.state());
        assertEquals(expectedManifest, after.manifestState());
        assertEquals(0, deletionEventCount("meeting-" + suffix));
    }

    private int deletionIdempotencyCount(String key) throws SQLException {
        String scope = IdempotencyScopeCodec.meetingDelete(DEPLOYMENT, SUBJECT);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*) FROM idempotency_record
                      WHERE subject_scope=? AND idempotency_key=?
                     """)) {
            statement.setString(1, scope);
            statement.setString(2, key);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private int deletionResponseStatus(String key) throws SQLException {
        return queryDeleteInt("response_status", key);
    }

    private Instant deletionCreatedAt(String key) throws SQLException {
        String scope = IdempotencyScopeCodec.meetingDelete(DEPLOYMENT, SUBJECT);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT created_at FROM idempotency_record
                      WHERE subject_scope=? AND idempotency_key=?
                     """)) {
            statement.setString(1, scope);
            statement.setString(2, key);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getTimestamp(1).toInstant();
            }
        }
    }

    private int queryDeleteInt(String column, String key) throws SQLException {
        String scope = IdempotencyScopeCodec.meetingDelete(DEPLOYMENT, SUBJECT);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM idempotency_record "
                             + "WHERE subject_scope=? AND idempotency_key=?")) {
            statement.setString(1, scope);
            statement.setString(2, key);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private int deletionEventCount(String meetingId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*) FROM outbox_event
                      WHERE aggregate_id=? AND event_type='MeetingDeletionRequested'
                     """)) {
            statement.setString(1, meetingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private int manifestRevisionCount(String meetingId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM meeting_manifest_revision WHERE meeting_id=?")) {
            statement.setString(1, meetingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private Instant deletionOutboxTime(String meetingId, String column) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM outbox_event "
                             + "WHERE aggregate_id=? "
                             + "AND event_type='MeetingDeletionRequested'")) {
            statement.setString(1, meetingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getTimestamp(1).toInstant();
            }
        }
    }

    private DeletionDbFacts facts(String meetingId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT state, manifest_state, input_state,
                            finalization_receipt_id, updated_at, aggregate_version
                       FROM meeting WHERE meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return new DeletionDbFacts(
                        MeetingState.valueOf(rows.getString("state")),
                        ManifestStatus.valueOf(rows.getString("manifest_state")),
                        InputState.valueOf(rows.getString("input_state")),
                        rows.getString("finalization_receipt_id"),
                        rows.getTimestamp("updated_at").toInstant(),
                        rows.getLong("aggregate_version"));
            }
        }
    }

    private Connection transaction() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static void assertConflict(
            DeleteResult result,
            DeleteResult.ConflictReason reason) {
        assertEquals(reason,
                assertInstanceOf(DeleteResult.Conflict.class, result).reason());
    }

    private static void assertUnavailable(
            DeleteResult result,
            DeleteResult.UnavailableReason reason,
            boolean retryable) {
        DeleteResult.Unavailable unavailable = assertInstanceOf(
                DeleteResult.Unavailable.class, result);
        assertEquals(reason, unavailable.reason());
        assertEquals(retryable, unavailable.retryable());
    }

    private static <T> List<T> concurrently(int count, CheckedSupplier<T> operation)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return operation.get();
                }));
            }
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static DataSource faultInjectingDataSource(
            DataSource delegate,
            FaultPoint faultPoint,
            FaultObservation observation) {
        return new DelegatingDataSource(delegate) {
            @Override
            public Connection getConnection() throws SQLException {
                return faultInjectingConnection(
                        super.getConnection(), faultPoint, observation);
            }

            @Override
            public Connection getConnection(String username, String password)
                    throws SQLException {
                return faultInjectingConnection(
                        super.getConnection(username, password), faultPoint, observation);
            }
        };
    }

    private static Connection faultInjectingConnection(
            Connection delegate,
            FaultPoint faultPoint,
            FaultObservation observation) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if ("prepareStatement".equals(method.getName())
                            && arguments != null
                            && arguments.length > 0
                            && arguments[0] instanceof String sql) {
                        if (faultPoint == FaultPoint.DATA_EXCEPTION_MEETING_LOCK
                                && sql.contains("FROM meeting")
                                && sql.contains("FOR UPDATE")) {
                            throw new SQLException(
                                    "private-dynamic-failure-detail", "22000");
                        }
                        if (faultPoint == FaultPoint.DATA_EXCEPTION_IDEMPOTENCY_READ
                                && sql.contains("FROM idempotency_record")) {
                            throw new SQLException(
                                    "private-dynamic-failure-detail", "22000");
                        }
                        if (faultPoint == FaultPoint.FAIL_IF_IDEMPOTENCY_READ
                                && sql.contains("FROM idempotency_record")) {
                            throw new AssertionError(
                                    "access denial must precede idempotency read");
                        }
                        if (faultPoint == FaultPoint.FAIL_IF_MEETING_UPDATE
                                && sql.contains("UPDATE meeting")
                                && sql.contains("aggregate_version = aggregate_version + 1")) {
                            throw new AssertionError(
                                    "invalid primitive input must not prepare update SQL");
                        }
                        FaultTiming timing = timing(faultPoint, sql);
                        if (timing != FaultTiming.NONE) {
                            PreparedStatement prepared = invokePrepared(
                                    delegate, method, arguments);
                            return faultInjectingStatement(prepared, timing);
                        }
                    }
                    if ("rollback".equals(method.getName())) {
                        observation.rollbackAttempted.set(true);
                        if (faultPoint == FaultPoint.ROLLBACK_SQL) {
                            throw new SQLException(
                                    "private-dynamic-failure-detail", "08006");
                        }
                        if (faultPoint == FaultPoint.ROLLBACK_RUNTIME) {
                            throw new IllegalStateException(
                                    "private-dynamic-failure-detail");
                        }
                    }
                    if ("commit".equals(method.getName())) {
                        if (faultPoint == FaultPoint.BEFORE_COMMIT) {
                            throw new SQLException(
                                    "private-dynamic-failure-detail", "08006");
                        }
                        if (faultPoint == FaultPoint.AFTER_COMMIT) {
                            delegate.commit();
                            throw new SQLException(
                                    "private-dynamic-failure-detail", "08006");
                        }
                        if (faultPoint == FaultPoint.RUNTIME_AFTER_COMMIT) {
                            delegate.commit();
                            throw new IllegalStateException(
                                    "private-dynamic-failure-detail");
                        }
                    }
                    if ("setAutoCommit".equals(method.getName())
                            && arguments != null
                            && arguments.length == 1
                            && Boolean.TRUE.equals(arguments[0])) {
                        observation.restoreAutoCommitAttempted.set(true);
                        if (faultPoint == FaultPoint.RESTORE_AUTOCOMMIT_RUNTIME) {
                            throw new IllegalStateException(
                                    "private-dynamic-failure-detail");
                        }
                    }
                    if ("abort".equals(method.getName())) {
                        observation.abortAttempted.set(true);
                    }
                    if ("close".equals(method.getName())) {
                        observation.closeAttempted.set(true);
                        if (faultPoint == FaultPoint.CLOSE_RUNTIME) {
                            delegate.close();
                            throw new IllegalStateException(
                                    "private-dynamic-failure-detail");
                        }
                    }
                    return invoke(delegate, method, arguments);
                });
    }

    private static PreparedStatement invokePrepared(
            Connection delegate,
            java.lang.reflect.Method method,
            Object[] arguments) throws Throwable {
        return (PreparedStatement) invoke(delegate, method, arguments);
    }

    private static PreparedStatement faultInjectingStatement(
            PreparedStatement delegate,
            FaultTiming timing) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, arguments) -> {
                    if ("executeUpdate".equals(method.getName())) {
                        if (timing == FaultTiming.BEFORE) {
                            throw new SQLException(
                                    "private-dynamic-failure-detail", "08006");
                        }
                        if (timing == FaultTiming.CAS_ZERO) {
                            return 0;
                        }
                        Object result = invoke(delegate, method, arguments);
                        if (timing == FaultTiming.AFTER) {
                            throw new SQLException(
                                    "private-dynamic-failure-detail", "08006");
                        }
                        return result;
                    }
                    return invoke(delegate, method, arguments);
                });
    }

    private static FaultTiming timing(FaultPoint point, String sql) {
        boolean idempotencyInsert = sql.contains("INSERT INTO idempotency_record");
        boolean manifestInsert = sql.contains("INSERT INTO meeting_manifest_revision");
        boolean meetingUpdate = sql.contains("UPDATE meeting")
                && sql.contains("aggregate_version = aggregate_version + 1");
        boolean outboxInsert = sql.contains("INSERT INTO outbox_event");
        if (point == FaultPoint.BEFORE_IDEMPOTENCY_INSERT && idempotencyInsert) {
            return FaultTiming.BEFORE;
        }
        if (point == FaultPoint.AFTER_IDEMPOTENCY_INSERT && idempotencyInsert) {
            return FaultTiming.AFTER;
        }
        if (point == FaultPoint.BEFORE_MANIFEST_INSERT && manifestInsert) {
            return FaultTiming.BEFORE;
        }
        if (point == FaultPoint.AFTER_MANIFEST_INSERT && manifestInsert) {
            return FaultTiming.AFTER;
        }
        if (point == FaultPoint.BEFORE_MEETING_UPDATE && meetingUpdate) {
            return FaultTiming.BEFORE;
        }
        if (point == FaultPoint.AFTER_MEETING_UPDATE && meetingUpdate) {
            return FaultTiming.AFTER;
        }
        if (point == FaultPoint.BEFORE_OUTBOX_INSERT && outboxInsert) {
            return FaultTiming.BEFORE;
        }
        if (point == FaultPoint.AFTER_OUTBOX_INSERT && outboxInsert) {
            return FaultTiming.AFTER;
        }
        if (point == FaultPoint.CAS_ZERO && meetingUpdate) {
            return FaultTiming.CAS_ZERO;
        }
        return FaultTiming.NONE;
    }

    private static Object invoke(
            Object delegate,
            java.lang.reflect.Method method,
            Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException reflected) {
            throw reflected.getCause();
        }
    }

    private void applyMigration(String filename) throws Exception {
        Path[] candidates = {
                Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename)
        };
        Path migration = null;
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                migration = candidate;
                break;
            }
        }
        if (migration == null) {
            throw new IllegalStateException("migration file not found: " + filename);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(migration, StandardCharsets.UTF_8));
        }
    }

    private static PGSimpleDataSource postgres(String currentSchema) {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setUrl(System.getenv("REFERENCE_TEST_DATABASE_URL"));
        String user = System.getenv("REFERENCE_TEST_DATABASE_USER");
        String password = System.getenv("REFERENCE_TEST_DATABASE_PASSWORD");
        if (user != null && !user.isBlank()) {
            postgres.setUser(user);
        }
        if (password != null) {
            postgres.setPassword(password);
        }
        if (currentSchema != null) {
            postgres.setCurrentSchema(currentSchema);
        }
        return postgres;
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private record DeletionDbFacts(
            MeetingState state,
            ManifestStatus manifestState,
            InputState inputState,
            String receipt,
            Instant updatedAt,
            long aggregateVersion) {
    }

    private enum FaultPoint {
        FAIL_IF_IDEMPOTENCY_READ,
        FAIL_IF_MEETING_UPDATE,
        DATA_EXCEPTION_MEETING_LOCK,
        DATA_EXCEPTION_IDEMPOTENCY_READ,
        BEFORE_IDEMPOTENCY_INSERT,
        AFTER_IDEMPOTENCY_INSERT,
        BEFORE_MANIFEST_INSERT,
        AFTER_MANIFEST_INSERT,
        BEFORE_MEETING_UPDATE,
        AFTER_MEETING_UPDATE,
        BEFORE_OUTBOX_INSERT,
        AFTER_OUTBOX_INSERT,
        CAS_ZERO,
        BEFORE_COMMIT,
        AFTER_COMMIT,
        RUNTIME_AFTER_COMMIT,
        ROLLBACK_SQL,
        ROLLBACK_RUNTIME,
        RESTORE_AUTOCOMMIT_RUNTIME,
        CLOSE_RUNTIME
    }

    private enum FaultTiming {
        NONE,
        BEFORE,
        AFTER,
        CAS_ZERO
    }

    private static final class FaultObservation {
        private final AtomicBoolean rollbackAttempted = new AtomicBoolean();
        private final AtomicBoolean restoreAutoCommitAttempted = new AtomicBoolean();
        private final AtomicBoolean abortAttempted = new AtomicBoolean();
        private final AtomicBoolean closeAttempted = new AtomicBoolean();
    }

    private static final class TestLogHandler extends Handler {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void publish(LogRecord record) {
            text.append(record.getMessage()).append('\n');
            Object[] parameters = record.getParameters();
            if (parameters != null) {
                for (Object parameter : parameters) {
                    text.append(parameter).append('\n');
                }
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        String text() {
            return text.toString();
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private static class DelegatingDataSource implements DataSource {
        private final DataSource delegate;

        DelegatingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password)
                throws SQLException {
            return delegate.getConnection(username, password);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
