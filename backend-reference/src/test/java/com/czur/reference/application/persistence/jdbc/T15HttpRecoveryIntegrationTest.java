package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.bootstrap.ReferenceMeetingDeploymentConfig;
import com.czur.reference.application.bootstrap.ReferenceMeetingHttpAssembly;
import com.czur.reference.application.deletion.MeetingDeletionPlan;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicy;
import com.czur.reference.application.finalization.DeterministicMockFinalizationPlanner;
import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.finalization.MockFinalizationWorker;
import com.czur.reference.application.manifest.ManifestResultAccessPolicy;
import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.application.port.FinalizationAdmissionUnitOfWork;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.protocol.json.FinalizationCheckpointJson;
import com.czur.reference.protocol.json.FinalizationExecutionFactJson;
import com.czur.reference.protocol.json.MeetingManifestJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.czur.reference.application.persistence.jdbc.T15HttpRecoveryFixtures.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 04-04 两次独立 Maven/JVM 门禁；A 不删除本次专属数据库事实或 Artifact 目录。
 * 只由专属脚本注入随机 PG16 地址、私有状态目录和本次持有的 Artifact 根运行。
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class T15HttpRecoveryIntegrationTest {
    private static final Clock HTTP_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @EnabledIfEnvironmentVariable(named = "T15_HTTP_RECOVERY_STAGE", matches = "A")
    void phaseAPublishesPersistentArtifactOverRealHttp() throws Exception {
        assertTrue(Files.isDirectory(artifactRoot()));
        var artifacts = new PersistentFileSystemMockArtifactStore(artifactRoot(), 4096);
        ReferenceMeetingDeploymentConfig trusted =
                ReferenceMeetingDeploymentConfig.fromEnvironment(config());
        State state;
        try (var assembly = ReferenceMeetingHttpAssembly.start(trusted, HTTP_CLOCK);
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            DataSource source = assembly.databaseRuntime().dataSource();
            var created = exchange(port, "POST", "/v1/meetings", createBody(),
                    authorization(CREATE_KEY, true));
            assertEquals(201, created.status());
            assertTrue(created.body().contains("\"state\":\"CREATED\""));
            String meetingId = createdMeetingId(source);
            CreateMeetingFactStore.Record first = createFact(source, meetingId);
            assertEquals(Set.of("transcript.realtime"),
                    first.response().grantedCapabilities());
            assertEquals(CapabilityDecision.Action.ENABLE,
                    first.response().capabilityDecisions().getFirst().action());
            assertEquals(List.of("mock.transcript"),
                    first.response().capabilityDecisions().getFirst().affectedWorkflows());
            assertEquals(List.of("transcript.final"),
                    first.response().capabilityDecisions().getFirst().producedArtifacts());

            var started = assertInstanceOf(MeetingStartExecutionUnitOfWork.Success.class,
                    new JdbcMeetingStartExecutionUnitOfWork(source, new CapabilityPlanner())
                            .startCompleteCreate(new MeetingStartExecutionUnitOfWork.StartRequest(
                                    SCOPE, SUBJECT, meetingId, NOW, null, trusted.profile(),
                                    Set.of("transcript.realtime"), trusted.mockCapabilities(),
                                    trusted.capabilityLimits())));
            assertEquals(MeetingStartExecutionUnitOfWork.SuccessCode.STARTED, started.code());
            var execution = executionFact(source, meetingId);
            assertEquals(first.response().capabilityDecisions(),
                    execution.capabilityPlan().decisions());

            String path = "/v1/meetings/" + meetingId;
            var stopped = exchange(port, "POST", path + ":stop", "",
                    authorization(STOP_KEY, false));
            assertEquals(202, stopped.status());
            assertTrue(stopped.body().contains("\"state\":\"STOPPING\""));
            assertEquals(0, count(source, "workflow_step"));

            FinalizationInputFact input = input(meetingId);
            var admitted = assertInstanceOf(FinalizationAdmissionUnitOfWork.Success.class,
                    new JdbcFinalizationAdmissionUnitOfWork(source,
                            () -> "receipt-" + UUID.randomUUID(),
                            new DeterministicMockFinalizationPlanner())
                            .admit(new FinalizationAdmissionUnitOfWork.AdmissionRequest(
                                    SCOPE, SUBJECT, meetingId, input)));
            assertEquals(FinalizationAdmissionUnitOfWork.SuccessCode.ADMITTED, admitted.code());
            WorkflowStep queued = new JdbcWorkflowStepStore(source)
                    .find(admitted.workflowKey()).orElseThrow();
            FinalizationCheckpoint checkpoint =
                    FinalizationCheckpointJson.decode(queued.canonicalPayload());
            assertEquals(FinalizationCheckpoint.STEP_TYPE, queued.key().stepType());
            assertEquals(FinalizationExecutionFactJson.sha256(execution),
                    checkpoint.executionFactDigest());
            assertEquals(FinalizationCheckpoint.inputFactDigest(input),
                    checkpoint.inputFactDigest());
            assertEquals(1, checkpoint.artifacts().size());
            assertEquals("transcript.final",
                    checkpoint.artifacts().getFirst().spec().artifactType());

            Clock workerClock = Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC);
            EntitlementIdGenerator ids = new EntitlementIdGenerator() {
                @Override public String newGrantId() { return "grant_" + UUID.randomUUID(); }
                @Override public String newReservationId() {
                    return "reservation_" + UUID.randomUUID();
                }
                @Override public String newLeaseToken() { return "lease_" + UUID.randomUUID(); }
            };
            var entitlement = new JdbcReferenceDevEntitlementProvider(source,
                    new ReferenceDevEntitlementPolicy(trusted.entitlement(), workerClock, ids),
                    workerClock);
            var worker = new MockFinalizationWorker(
                    new JdbcWorkflowStepStore(source), artifacts, entitlement,
                    new JdbcFinalizationCompletionUnitOfWork(source),
                    new MockFinalizationWorker.Config("t15-http-recovery-a", workerClock,
                            Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ZERO, 1, 3));
            var completed = worker.runOnce();
            assertEquals(1, completed.claimedCount());
            assertEquals(MockFinalizationWorker.ItemStatus.COMPLETED,
                    completed.items().getFirst().status());
            assertTrue(worker.runOnce().idle());
            assertEquals(WorkflowStep.Status.COMPLETED,
                    new JdbcWorkflowStepStore(source).find(admitted.workflowKey())
                            .orElseThrow().status());

            var found = assertInstanceOf(MeetingManifestPort.Found.class,
                    new JdbcMeetingManifestQueryPort(source,
                            new ManifestResultAccessPolicy(HTTP_CLOCK, Duration.ofMinutes(1)))
                            .get(new MeetingManifestPort.Request(meetingId, -1)));
            var manifest = found.manifest();
            assertEquals(ManifestStatus.READY, manifest.status());
            assertEquals(1, manifest.artifacts().size());
            assertEquals("transcript.final", manifest.artifacts().getFirst().type());
            var publicManifest = exchange(port, "GET", path + "/manifest?genTime=-1", "", "");
            assertEquals(200, publicManifest.status());
            assertEquals(MeetingManifestJson.encode(manifest), publicManifest.body());

            var metadata = artifactMetadata(source, meetingId);
            assertEquals(1, metadata.size());
            var descriptor = metadata.getFirst().artifact();
            assertEquals(manifest.artifacts().getFirst().artifactId(),
                    descriptor.intent().spec().artifactId());
            byte[] physical;
            try (ArtifactStore.ReadHandle read = artifacts.openRead(
                    descriptor.intent().binding())) {
                physical = read.content().readAllBytes();
                assertEquals(descriptor, read.artifact());
            }
            assertTrue(physical.length > 0);
            assertArrayEquals(checkpoint.artifacts().getFirst().canonicalContentBytes(), physical);
            assertEquals(manifest.artifacts().getFirst().size(), physical.length);
            assertEquals(manifest.artifacts().getFirst().checksum(), sha256(physical));
            assertEquals(descriptor.content().checksum(), sha256(physical));
            assertEquals(1, count(source, "meeting"));
            assertEquals(1, count(source, "meeting_create_fact"));
            assertEquals(1, count(source, "usage_reservation"));
            assertEquals(2, count(source, "usage_operation"));
            assertEquals(1, count(source, "workflow_step"));
            assertEquals(1, count(source, "artifact"));
            assertEquals(1, count(source, "meeting_manifest_revision"));
            assertEquals(0, count(source, "meeting_deletion_plan"));

            state = new State(meetingId, created.bodyBytes().length,
                    sha256(created.bodyBytes()), descriptor.intent().spec().artifactId(),
                    physical.length, sha256(physical),
                    sha256(admitted.finalization().finalizationReceipt()),
                    postmasterStart(source), ProcessHandle.current().pid());
            assertTrue(network.shutdown(Duration.ofSeconds(3)).clean());
        }
        // 只有网络监听器与唯一 JDBC 池都关闭后才标记 A 阶段可交接。
        writeState(state);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "T15_HTTP_RECOVERY_STAGE", matches = "B")
    void phaseBReopensArtifactAndReplaysAfterDatabaseRestart() throws Exception {
        State previous = readState();
        assertNotEquals(previous.phasePid(), ProcessHandle.current().pid(),
                "B must execute in a different JVM");
        assertFalse(ProcessHandle.of(previous.phasePid()).map(ProcessHandle::isAlive)
                        .orElse(false), "A JVM must exit before B starts");
        assertTrue(Files.isDirectory(artifactRoot()));
        var artifacts = new PersistentFileSystemMockArtifactStore(artifactRoot(), 4096);
        Clock resumedClock = Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC);
        try (var assembly = ReferenceMeetingHttpAssembly.start(config(), resumedClock);
             var network = assembly.newJdkHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            network.start();
            int port = network.address().getPort();
            DataSource source = assembly.databaseRuntime().dataSource();
            assertTrue(postmasterStart(source).isAfter(previous.postmasterStart()),
                    "PostgreSQL postmaster must restart on the retained volume");
            assertEquals(previous.meetingId(), createdMeetingId(source));

            // 首个业务操作是新 Store 对 A 的 READY binding 做物理读回；不得先重跑 Worker/DELETE。
            var metadata = artifactMetadata(source, previous.meetingId());
            assertEquals(1, metadata.size());
            var descriptor = metadata.getFirst().artifact();
            assertEquals(previous.artifactId(), descriptor.intent().spec().artifactId());
            assertEquals(previous.artifactSize(), descriptor.content().size());
            assertEquals(previous.artifactSha256(), descriptor.content().checksum());
            byte[] physical;
            try (ArtifactStore.ReadHandle read = artifacts.openRead(
                    descriptor.intent().binding())) {
                physical = read.content().readAllBytes();
                assertEquals(descriptor, read.artifact());
            }
            assertEquals(previous.artifactSize(), physical.length);
            assertEquals(previous.artifactSha256(), sha256(physical));

            var found = assertInstanceOf(MeetingManifestPort.Found.class,
                    new JdbcMeetingManifestQueryPort(source,
                            new ManifestResultAccessPolicy(resumedClock, Duration.ofMinutes(1)))
                            .get(new MeetingManifestPort.Request(previous.meetingId(), -1)));
            var manifest = found.manifest();
            assertEquals(ManifestStatus.READY, manifest.status());
            assertEquals(1, manifest.artifacts().size());
            assertEquals(previous.artifactId(), manifest.artifacts().getFirst().artifactId());
            assertEquals(previous.artifactSize(), manifest.artifacts().getFirst().size());
            assertEquals(previous.artifactSha256(), manifest.artifacts().getFirst().checksum());
            assertEquals("transcript.final", manifest.artifacts().getFirst().type());

            String path = "/v1/meetings/" + previous.meetingId();
            var replay = exchange(port, "POST", "/v1/meetings", createBody(),
                    authorization(CREATE_KEY, true));
            assertEquals(201, replay.status());
            assertEquals(previous.createLength(), replay.bodyBytes().length);
            assertEquals(previous.createSha256(), sha256(replay.bodyBytes()));
            assertEquals(1, count(source, "meeting_create_fact"));
            assertEquals(1, count(source, "usage_reservation"));
            assertEquals(2, count(source, "usage_operation"));

            var execution = executionFact(source, previous.meetingId());
            var workflow = finalizationWorkflow(source, previous.meetingId());
            assertEquals(WorkflowStep.Status.COMPLETED, workflow.status());
            var checkpoint = FinalizationCheckpointJson.decode(workflow.canonicalPayload());
            assertEquals(FinalizationExecutionFactJson.sha256(execution),
                    checkpoint.executionFactDigest());
            assertEquals(1, checkpoint.artifacts().size());
            assertArrayEquals(checkpoint.artifacts().getFirst().canonicalContentBytes(), physical);
            assertEquals(1, count(source, "workflow_step"));
            assertEquals(1, count(source, "durable_input_fact"));
            assertEquals(1, count(source, "final_usage_fact"));
            assertEquals(1, count(source, "artifact"));
            assertEquals(1, count(source, "meeting_manifest_revision"));
            String receipt = finalizationReceipt(source, previous.meetingId());
            assertEquals(previous.receiptSha256(), sha256(receipt));

            var publicManifest = exchange(port, "GET", path + "/manifest?genTime=-1", "", "");
            assertEquals(200, publicManifest.status());
            assertEquals(MeetingManifestJson.encode(manifest), publicManifest.body());
            var meeting = exchange(port, "GET", path, "", authorization(null, false));
            assertEquals(200, meeting.status());
            assertTrue(meeting.body().contains(receipt));
            var stopReplay = exchange(port, "POST", path + ":stop", "",
                    authorization(STOP_KEY, false));
            assertEquals(200, stopReplay.status());
            assertTrue(stopReplay.body().contains(receipt));

            var entitlement = new JdbcReferenceDevEntitlementProvider(source,
                    new ReferenceDevEntitlementPolicy(
                            ReferenceMeetingDeploymentConfig.fromEnvironment(config())
                                    .entitlement(), resumedClock, new EntitlementIdGenerator() {
                                @Override public String newGrantId() { return "grant_" + UUID.randomUUID(); }
                                @Override public String newReservationId() {
                                    return "reservation_" + UUID.randomUUID();
                                }
                                @Override public String newLeaseToken() {
                                    return "lease_" + UUID.randomUUID();
                                }
                            }), resumedClock);
            var worker = new MockFinalizationWorker(new JdbcWorkflowStepStore(source), artifacts,
                    entitlement, new JdbcFinalizationCompletionUnitOfWork(source),
                    new MockFinalizationWorker.Config("t15-http-recovery-b", resumedClock,
                            Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ZERO, 1, 3));
            assertTrue(worker.runOnce().idle(), "completed workflow must not republish");
            assertEquals(1, count(source, "artifact"));
            assertEquals(1, count(source, "meeting_manifest_revision"));
            assertEquals(2, count(source, "usage_operation"));

            var deleted = exchange(port, "DELETE", path, "",
                    authorization("t15-http-recovery-delete-key-123456", false));
            assertEquals(202, deleted.status());
            assertEquals(202, exchange(port, "DELETE", path, "",
                    authorization("t15-http-recovery-delete-key-123456", false)).status());
            assertEquals(1, count(source, "meeting_deletion_plan"));
            var deletionPlan = deletionPlan(source, previous.meetingId());
            assertEquals(MeetingDeletionPlan.PlanStatus.PENDING, deletionPlan.status());
            assertEquals(1, deletionPlan.items().size());
            assertEquals(MeetingDeletionPlan.ItemStatus.PENDING,
                    deletionPlan.items().getFirst().status());
            assertEquals(previous.artifactId(),
                    deletionPlan.items().getFirst().target().spec().artifactId());
            assertEquals(2, count(source, "meeting_manifest_revision"));
            assertEquals(1, count(source, "artifact"));
            assertEquals(2, count(source, "usage_operation"));
            assertEquals(403, exchange(port, "GET", path + "/manifest?genTime=-1", "", "")
                    .status());
            assertTrue(network.shutdown(Duration.ofSeconds(3)).clean());
        }
    }

    static FinalizationInputFact input(String meetingId) throws Exception {
        return new FinalizationInputFact(meetingId, "mock-input-binding-" + meetingId,
                3, 2_000, "zh-CN", List.of(), NOW.plusSeconds(1),
                sha256("mock-input".getBytes(StandardCharsets.UTF_8)));
    }

    private static CreateMeetingFactStore.Record createFact(DataSource source, String meetingId)
            throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            var fact = new JdbcCreateMeetingFactStore(connection)
                    .findByMeetingId(meetingId).orElseThrow();
            connection.rollback();
            return fact;
        }
    }

    private static com.czur.reference.application.finalization.FinalizationExecutionFact
            executionFact(DataSource source, String meetingId) throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            var fact = new JdbcFinalizationExecutionFactStore(connection)
                    .find(meetingId).orElseThrow();
            connection.rollback();
            return fact;
        }
    }

    static List<ArtifactReadyMetadataStore.ReadyMetadata> artifactMetadata(
            DataSource source, String meetingId) throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            var metadata = new JdbcArtifactReadyMetadataStore(connection)
                    .findByMeetingId(meetingId);
            connection.rollback();
            return metadata;
        }
    }

    private static WorkflowStep finalizationWorkflow(DataSource source, String meetingId)
            throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT step_type, step_key FROM workflow_step WHERE meeting_id=?")) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new AssertionError("finalization Workflow missing");
                var key = new WorkflowStep.Key(meetingId, rows.getString(1), rows.getString(2));
                if (rows.next()) throw new AssertionError("finalization Workflow duplicated");
                assertEquals(FinalizationCheckpoint.STEP_TYPE, key.stepType());
                return new JdbcWorkflowStepStore(source).find(key).orElseThrow();
            }
        }
    }

    private static String finalizationReceipt(DataSource source, String meetingId)
            throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT finalization_receipt_id FROM meeting WHERE meeting_id=?")) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new AssertionError("meeting missing");
                String receipt = rows.getString(1);
                if (rows.next()) throw new AssertionError("meeting duplicated");
                assertTrue(receipt != null && !receipt.isBlank());
                return receipt;
            }
        }
    }

    private static MeetingDeletionPlan deletionPlan(DataSource source, String meetingId)
            throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            var plan = new JdbcArtifactDeletionPlanStore(connection).find(meetingId).orElseThrow();
            connection.rollback();
            return plan;
        }
    }
}
