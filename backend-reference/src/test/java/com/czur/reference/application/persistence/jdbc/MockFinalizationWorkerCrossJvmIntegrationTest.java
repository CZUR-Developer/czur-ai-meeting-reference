package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.finalization.FinalizationTerminalOperationMarker;
import com.czur.reference.application.manifest.FinalizationManifestFactory;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.TerminalFailure;
import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.protocol.json.FinalizationCheckpointJson;
import com.czur.reference.protocol.json.FinalizationExecutionFactJson;
import com.czur.reference.protocol.json.MeetingManifestJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-T14-09-10 Mock 最终收口 Worker 的真实双 JVM 恢复门禁。
 *
 * <p>每个故障窗口使用独立随机 schema、同一持久 Artifact 文件根和三个顺序启动的
 * 子 JVM。父进程只负责 V001～V011 与权威业务事实准备；JVM-A 由真实 Worker 执行
 * 到指定线性化点后 {@code halt(47)}，JVM-B 从同一数据库和文件根自主 reclaim 或
 * 观察终态，JVM-C 再证明终态运行 idle 且数据库/文件快照逐字不变。</p>
 *
 * <p>门禁同时验证旧 lease 在新 owner 活跃期的 renew、complete、fail、marker
 * persist 和 marker recover 全部返回 NOT_OWNER。测试不使用内存 Store、同 JVM
 * CrashSignal、父进程手工 claim/complete/fail，也不把 checkpoint 或模拟正文放进
 * 进程环境。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class MockFinalizationWorkerCrossJvmIntegrationTest {
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
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(60);
    private static final Set<String> ARTIFACT_DIRECTORIES = Set.of(
            "intents", "staging", "objects", "tombstones", "locks");

    @TempDir
    Path temporaryDirectory;

    @Test
    void fiveRealProcessBoundariesRecoverExactlyOnceAndRemainTerminallyIdle()
            throws Exception {
        rejectReservedDatabaseEndpoint();
        for (Scenario scenario : Scenario.values()) {
            runScenario(scenario);
        }
    }

    private void runScenario(Scenario scenario) throws Exception {
        String schema = "worker_cross_jvm_"
                + UUID.randomUUID().toString().replace("-", "");
        Path scenarioRoot = temporaryDirectory.resolve(scenario.cliName).toAbsolutePath();
        Path artifactRoot = scenarioRoot.resolve("artifacts");
        Path controlFile = scenarioRoot.resolve("old-lease.bin");
        Files.createDirectories(scenarioRoot);
        DataSource dataSource = null;
        T14WorkerCrossJvmTestSupport.Prepared prepared = null;
        try {
            createSchema(schema);
            dataSource = postgres(schema);
            applyMigrations(dataSource);
            prepared = T14WorkerCrossJvmTestSupport.prepare(
                    dataSource, artifactRoot, scenario.cliName);

            ProbeResult crashed = runProbe(
                    schema, artifactRoot, controlFile, "crash", scenario.cliName);
            assertEquals(47, crashed.exitCode(), crashed.output());
            assertTrue(crashed.output().contains(
                    "PROBE_CRASH scenario=" + scenario.cliName
                            + " checkpoint=" + scenario.crashCheckpoint),
                    crashed.output());
            assertFalse(crashed.output().contains("PROBE_FAILED"), crashed.output());
            assertPrivateControlFile(controlFile);
            assertCrashBoundary(dataSource, prepared, scenario);
            List<String> staleLeaseSecrets = readActiveLeaseSecrets(
                    dataSource, prepared.meetingId());

            ProbeResult recovered = runProbe(
                    schema, artifactRoot, controlFile, "recover", scenario.cliName);
            assertEquals(0, recovered.exitCode(), recovered.output());
            String expectedRecovery = scenario == Scenario.COMPLETION_COMMITTED
                    ? "result=IDLE fences=NOT_APPLICABLE"
                    : "result=" + (scenario.failure
                            ? "FAILED_DEAD" : "COMPLETED")
                            + " fences=NOT_OWNER";
            assertTrue(recovered.output().contains(
                    "PROBE_RECOVERED scenario=" + scenario.cliName
                            + " " + expectedRecovery), recovered.output());
            assertTerminalFacts(dataSource, prepared, scenario);
            String stableSnapshot = terminalSnapshot(dataSource, prepared)
                    + '\n' + fileSnapshot(artifactRoot);

            ProbeResult idle = runProbe(
                    schema, artifactRoot, controlFile, "verify-idle", scenario.cliName);
            assertEquals(0, idle.exitCode(), idle.output());
            assertTrue(idle.output().contains(
                    "PROBE_IDLE scenario=" + scenario.cliName
                            + " result=IDLE snapshot=UNCHANGED"), idle.output());
            assertEquals(stableSnapshot,
                    terminalSnapshot(dataSource, prepared)
                            + '\n' + fileSnapshot(artifactRoot));

            assertRedacted(
                    crashed.output() + recovered.output() + idle.output(),
                    schema,
                    artifactRoot,
                    controlFile,
                    prepared,
                    staleLeaseSecrets);
        } finally {
            // 任一清理动作失败也不能跳过后续资源。嵌套 finally 保证控制文件、
            // schema 和场景文件根始终分别尝试精确回收。
            try {
                Files.deleteIfExists(controlFile);
            } finally {
                try {
                    dropSchema(schema);
                    assertSchemaAbsent(schema);
                } finally {
                    deleteScenarioTree(scenarioRoot);
                    assertFalse(Files.exists(
                            scenarioRoot, LinkOption.NOFOLLOW_LINKS));
                }
            }
        }
    }

    private static void assertCrashBoundary(
            DataSource dataSource,
            T14WorkerCrossJvmTestSupport.Prepared prepared,
            Scenario scenario) throws Exception {
        WorkflowStep workflow = new JdbcWorkflowStepStore(dataSource)
                .find(prepared.checkpoint().workflowKey()).orElseThrow();
        if (scenario == Scenario.COMPLETION_COMMITTED) {
            assertEquals(WorkflowStep.Status.COMPLETED, workflow.status());
            assertEquals("READY", scalar(dataSource,
                    "SELECT state FROM meeting WHERE meeting_id=?",
                    prepared.meetingId()));
            return;
        }
        assertEquals(WorkflowStep.Status.LEASED, workflow.status());
        assertEquals(1, workflow.leaseVersion());
        assertEquals(T14WorkerCrossJvmTestSupport.FIRST_CLAIM_AT,
                workflow.updatedAt());
        assertEquals(T14WorkerCrossJvmTestSupport.RECOVERY_CLAIM_AT,
                workflow.leaseUntil());
        assertEquals(0, countForMeeting(dataSource, "artifact", prepared.meetingId()));
        assertEquals(0, countForMeeting(
                dataSource, "meeting_manifest_revision", prepared.meetingId()));

        long readyFiles = countSuffix(prepared.artifactRoot().resolve("objects"), ".ready");
        long expectedReady = scenario == Scenario.ARTIFACT_PARTIAL ? 1 : 2;
        assertEquals(expectedReady, readyFiles);
        assertEquals(expectedReady,
                countSuffix(prepared.artifactRoot().resolve("objects"), ".blob"));
        assertEquals(scenario.commitExpectedAfterCrash ? 1 : 0,
                countCommitOperations(dataSource, prepared));
        if (scenario == Scenario.SUCCESS_MARKER) {
            assertEquals(FinalizationTerminalOperationMarker.SUCCESS_CODE,
                    workflow.errorCode());
            assertNotNull(workflow.errorSummary());
        } else if (scenario == Scenario.FAILURE_MARKER) {
            assertEquals(FinalizationTerminalOperationMarker.FAILURE_CODE,
                    workflow.errorCode());
            assertNotNull(workflow.errorSummary());
        } else {
            assertEquals(null, workflow.errorCode());
            assertEquals(null, workflow.errorSummary());
        }
    }

    /**
     * 核对跨进程恢复后的全部权威赢家及 Artifact 正文，而不是只相信子进程状态行。
     */
    private static void assertTerminalFacts(
            DataSource dataSource,
            T14WorkerCrossJvmTestSupport.Prepared prepared,
            Scenario scenario) throws Exception {
        boolean failure = scenario.failure;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT m.state, m.manifest_state, m.input_state,
                            m.finalization_receipt_id,
                            w.status, w.attempt_count, w.lease_owner, w.lease_token,
                            w.lease_until, w.next_attempt_at,
                            r.status AS reservation_state
                       FROM meeting m
                       JOIN workflow_step w ON w.meeting_id=m.meeting_id
                       JOIN usage_reservation r
                         ON r.deployment_scope=m.deployment_scope
                        AND r.subject_scope=m.subject_scope
                        AND r.reservation_id=m.usage_reservation_id
                      WHERE m.meeting_id=?
                     """)) {
            statement.setString(1, prepared.meetingId());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(failure ? "FAILED" : "READY", rows.getString("state"));
                assertEquals(failure ? "FAILED" : "READY",
                        rows.getString("manifest_state"));
                assertEquals("INPUT_ACCEPTED", rows.getString("input_state"));
                assertEquals(prepared.receipt(),
                        rows.getString("finalization_receipt_id"));
                assertEquals(failure ? "DEAD" : "COMPLETED",
                        rows.getString("status"));
                assertEquals(failure ? 1 : 0, rows.getInt("attempt_count"));
                assertEquals(null, rows.getString("lease_owner"));
                assertEquals(null, rows.getString("lease_token"));
                assertEquals(null, rows.getTimestamp("lease_until"));
                assertEquals(null, rows.getTimestamp("next_attempt_at"));
                assertEquals(failure ? "RESERVED" : "COMMITTED",
                        rows.getString("reservation_state"));
                assertFalse(rows.next());
            }
        }

        assertEquals(1, count(dataSource, "meeting"));
        assertEquals(1, countReceipt(dataSource, prepared.receipt()));
        assertEquals(1, countForMeeting(
                dataSource, "durable_input_fact", prepared.meetingId()));
        assertEquals(1, countForMeeting(
                dataSource, "final_usage_fact", prepared.meetingId()));
        assertEquals(1, countForMeeting(
                dataSource, "finalization_execution_fact", prepared.meetingId()));
        assertEquals(1, countForMeeting(
                dataSource, "workflow_step", prepared.meetingId()));
        assertEquals(1, countForMeeting(
                dataSource, "meeting_manifest_revision", prepared.meetingId()));
        assertEquals(prepared.checkpoint().artifacts().size(),
                countForMeeting(dataSource, "artifact", prepared.meetingId()));
        assertEquals(failure ? 0 : 1,
                countCommitOperations(dataSource, prepared));

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            assertEquals(prepared.input(), JdbcDurableInputFactStore.find(
                    connection, prepared.meetingId()).orElseThrow());
            FinalUsageFact usage = JdbcFinalUsageFactStore.find(
                    connection, prepared.meetingId()).orElseThrow();
            assertEquals(prepared.checkpoint().finalUsageFact(), usage);

            // V011 Store 会同时核对 canonical JSON、JSONB 镜像、SHA-256 和领域
            // 构造器；这里再与 prepare 阶段冻结的正式值对象逐字段比较，避免只凭
            // meetingId 或行数把漂移后的执行计划判为恢复成功。
            FinalizationExecutionFact execution =
                    new JdbcFinalizationExecutionFactStore(connection)
                            .find(prepared.meetingId()).orElseThrow();
            assertEquals(prepared.executionFact(), execution);
            assertEquals(FinalizationExecutionFactJson.encode(prepared.executionFact()),
                    canonicalExecutionFact(dataSource, prepared.meetingId()));
            assertEquals(prepared.executionFact(), FinalizationExecutionFactJson.decode(
                    canonicalExecutionFact(dataSource, prepared.meetingId())));

            List<ArtifactReadyMetadataStore.ReadyMetadata> metadata =
                    new JdbcArtifactReadyMetadataStore(connection)
                            .findByMeetingId(prepared.meetingId());
            assertReadyMetadata(prepared, metadata);

            MeetingManifest manifest = new JdbcManifestFactStore(connection)
                    .findLatest(prepared.meetingId()).orElseThrow();
            MeetingManifest expectedManifest = failure
                    ? FinalizationManifestFactory.failed(
                            prepared.executionFact(),
                            prepared.input(),
                            prepared.checkpoint().finalUsageFact(),
                            prepared.checkpoint(),
                            metadata,
                            expectedTerminalFailure(),
                            T14WorkerCrossJvmTestSupport.FIRST_CLAIM_AT)
                    : FinalizationManifestFactory.ready(
                            prepared.executionFact(),
                            prepared.input(),
                            prepared.checkpoint().finalUsageFact(),
                            prepared.checkpoint(),
                            metadata);
            assertEquals(expectedManifest, manifest);
            String expectedManifestJson = MeetingManifestJson.encode(expectedManifest);
            assertEquals(expectedManifestJson,
                    canonicalManifest(dataSource, prepared.meetingId()));
            assertEquals(expectedManifest,
                    MeetingManifestJson.decode(expectedManifestJson));

            assertUsageFacts(connection, prepared, scenario);
            connection.rollback();
        }

        assertArtifactFiles(prepared);
    }

    /**
     * 逐个核对 V011 READY metadata 的完整 descriptor 与数据库生成时间。
     *
     * <p>Artifact ID 的数据库读取顺序与 checkpoint 顺序不构成协议，因此按稳定
     * artifactId 建表后比较；descriptor 的 record equality 会覆盖 spec、intentId、
     * storage binding、content size 和 checksum 全部字段。recordedAt 则继续作为
     * Manifest 工厂输入，并由后续完整 Manifest equality 证明没有被替换。</p>
     */
    private static void assertReadyMetadata(
            T14WorkerCrossJvmTestSupport.Prepared prepared,
            List<ArtifactReadyMetadataStore.ReadyMetadata> metadata) throws Exception {
        assertEquals(prepared.checkpoint().artifacts().size(), metadata.size());
        Map<String, ArtifactReadyMetadataStore.ReadyMetadata> byArtifactId =
                new HashMap<>();
        for (ArtifactReadyMetadataStore.ReadyMetadata value : metadata) {
            assertEquals(0, value.recordedAt().getNano() % 1_000);
            String artifactId = value.artifact().intent().spec().artifactId();
            assertEquals(null, byArtifactId.put(artifactId, value));
        }
        for (FinalizationCheckpoint.ArtifactMaterial material
                : prepared.checkpoint().artifacts()) {
            ArtifactReadyMetadataStore.ReadyMetadata actual =
                    byArtifactId.get(material.spec().artifactId());
            assertNotNull(actual);
            ArtifactStore.PrepareResult expectedIntent =
                    new PersistentFileSystemMockArtifactStore(
                            prepared.artifactRoot(),
                            T14WorkerCrossJvmTestSupport.ARTIFACT_LIMIT)
                            .createWriteIntent(material.spec());
            assertEquals(ArtifactStore.PrepareStatus.REPLAYED,
                    expectedIntent.status());
            // 正式文件 Store 根据 spec 恢复出的 intent 同时冻结 intentId 和 storage
            // binding；与 descriptor 整体比较可覆盖 artifact 表的全部非时间字段。
            assertEquals(expectedIntent.intent(), actual.artifact().intent());
            assertEquals(material.claim(), actual.artifact().content());
        }
        assertEquals(prepared.checkpoint().artifacts().stream()
                        .map(material -> material.spec().artifactId())
                        .collect(java.util.stream.Collectors.toSet()),
                byArtifactId.keySet());
    }

    /**
     * 使用正式 Reservation Store 与 usage_operation codec 核对完整权益事实。
     * Reservation 的每个字段由值对象 equality 覆盖；operation 还逐项核对范围、
     * operation scope、幂等键、摘要、Reservation 外键、canonical result 和创建时间。
     */
    private static void assertUsageFacts(
            Connection connection,
            T14WorkerCrossJvmTestSupport.Prepared prepared,
            Scenario scenario) throws Exception {
        CommitUsageRequest commit = prepared.checkpoint().commitUsageRequest();
        assertEquals(prepared.boundReservation().reservationId(),
                commit.reservationId());
        assertEquals(prepared.boundReservation().version(), commit.expectedVersion());
        assertEquals(prepared.checkpoint().finalUsageFact().actual(), commit.actual());

        UsageReservation expectedReservation;
        UsageOperationResult expectedCommit = null;
        if (scenario.failure) {
            expectedReservation = prepared.boundReservation();
        } else {
            Instant commitAt = scenario.commitAt();
            expectedReservation = prepared.boundReservation().commit(
                    commit.actual(), commitAt, commit.expectedVersion());
            expectedCommit = UsageOperationResult.terminal(
                    commit.deploymentScope(),
                    commit.subjectScope(),
                    commit.operationScope(),
                    commit.idempotencyKey(),
                    commit.requestDigest(),
                    expectedReservation);
        }
        assertEquals(expectedReservation, JdbcUsageReservationStore.findReservation(
                connection,
                T14WorkerCrossJvmTestSupport.DEPLOYMENT,
                T14WorkerCrossJvmTestSupport.SUBJECT,
                prepared.boundReservation().reservationId()).orElseThrow());

        assertUsageOperation(
                connection,
                prepared.reserveOperation(),
                T14WorkerCrossJvmTestSupport.RESERVED_AT.plusSeconds(1));
        if (expectedCommit != null) {
            assertUsageOperation(connection, expectedCommit, scenario.commitAt());
        }
        assertEquals(scenario.failure ? 1 : 2,
                countUsageOperations(connection));
    }

    private static void assertUsageOperation(
            Connection connection,
            UsageOperationResult expected,
            Instant expectedCreatedAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT deployment_scope, subject_scope, operation_scope,
                       idempotency_key, request_digest, reservation_id,
                       result_body::text AS result_body, created_at
                  FROM usage_operation
                 WHERE deployment_scope=? AND subject_scope=?
                   AND operation_scope=? AND idempotency_key=?
                """)) {
            statement.setString(1, expected.deploymentScope());
            statement.setString(2, expected.subjectScope());
            statement.setString(3, expected.operationScope());
            statement.setString(4, expected.idempotencyKey());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(expected.deploymentScope(),
                        rows.getString("deployment_scope"));
                assertEquals(expected.subjectScope(), rows.getString("subject_scope"));
                assertEquals(expected.operationScope(),
                        rows.getString("operation_scope"));
                assertEquals(expected.idempotencyKey(),
                        rows.getString("idempotency_key"));
                assertEquals(expected.requestDigest(),
                        rows.getString("request_digest"));
                assertEquals(expected.reservation().reservationId(),
                        rows.getString("reservation_id"));
                UsageOperationResult decoded = UsageOperationResultJson.decode(
                        rows.getString("result_body"),
                        expected.deploymentScope(),
                        expected.subjectScope(),
                        expected.operationScope(),
                        expected.idempotencyKey(),
                        expected.requestDigest());
                assertEquals(expected, decoded);
                assertEquals(UsageOperationResultJson.encode(expected),
                        UsageOperationResultJson.encode(decoded));
                assertEquals(expectedCreatedAt,
                        rows.getTimestamp("created_at").toInstant());
                assertFalse(rows.next());
            }
        }
    }

    private static int countUsageOperations(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*) FROM usage_operation
                 WHERE deployment_scope=? AND subject_scope=?
                """)) {
            statement.setString(1, T14WorkerCrossJvmTestSupport.DEPLOYMENT);
            statement.setString(2, T14WorkerCrossJvmTestSupport.SUBJECT);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                int count = rows.getInt(1);
                assertFalse(rows.next());
                return count;
            }
        }
    }

    private static TerminalFailure expectedTerminalFailure() {
        return new TerminalFailure(
                "INTERNAL",
                "ENTITLEMENT_INTERNAL",
                false,
                "ENTITLEMENT_INTERNAL",
                "ENTITLEMENT_COMMIT_FAILED");
    }

    private static String canonicalExecutionFact(
            DataSource dataSource,
            String meetingId) throws Exception {
        return scalar(dataSource,
                "SELECT canonical_json FROM finalization_execution_fact WHERE meeting_id=?",
                meetingId);
    }

    private static String canonicalManifest(
            DataSource dataSource,
            String meetingId) throws Exception {
        return scalar(dataSource, """
                SELECT canonical_json FROM meeting_manifest_revision
                 WHERE meeting_id=? AND manifest_revision=1
                """, meetingId);
    }

    private static void assertArtifactFiles(
            T14WorkerCrossJvmTestSupport.Prepared prepared) throws Exception {
        Path root = prepared.artifactRoot();
        try (var paths = Files.list(root)) {
            assertEquals(ARTIFACT_DIRECTORIES,
                    paths.map(path -> path.getFileName().toString())
                            .collect(java.util.stream.Collectors.toSet()));
        }
        int count = prepared.checkpoint().artifacts().size();
        assertDirectory(root.resolve("intents"), count, ".intent");
        assertDirectory(root.resolve("staging"), count, ".verified");
        assertDirectory(root.resolve("objects"), count, ".blob", ".ready");
        assertDirectory(root.resolve("tombstones"), 0);
        assertDirectory(root.resolve("locks"), count, ".lock");

        ArtifactStore store = new PersistentFileSystemMockArtifactStore(
                root, T14WorkerCrossJvmTestSupport.ARTIFACT_LIMIT);
        for (FinalizationCheckpoint.ArtifactMaterial material
                : prepared.checkpoint().artifacts()) {
            ArtifactStore.PrepareResult preparedIntent =
                    store.createWriteIntent(material.spec());
            assertEquals(ArtifactStore.PrepareStatus.REPLAYED,
                    preparedIntent.status());
            ArtifactStore.StatResult stat = store.stat(
                    preparedIntent.intent().binding());
            assertEquals(ArtifactStore.StoreState.READY, stat.state());
            assertNotNull(stat.artifact());
            assertEquals(material.spec(), stat.artifact().intent().spec());
            assertEquals(material.claim(), stat.artifact().content());
            try (ArtifactStore.ReadHandle read = store.openRead(
                    preparedIntent.intent().binding())) {
                assertTrue(java.util.Arrays.equals(
                        material.canonicalContentBytes(),
                        read.content().readAllBytes()));
            }
        }
    }

    private static void assertDirectory(
            Path directory,
            int expectedPerSuffix,
            String... allowedSuffixes) throws Exception {
        try (var paths = Files.list(directory)) {
            List<Path> files = paths.toList();
            assertEquals((long) expectedPerSuffix * allowedSuffixes.length, files.size());
            for (Path file : files) {
                assertTrue(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS));
                assertFalse(Files.isSymbolicLink(file));
                String name = file.getFileName().toString();
                assertFalse(name.endsWith(".tmp"));
                assertFalse(name.endsWith(".part"));
                assertTrue(java.util.Arrays.stream(allowedSuffixes)
                        .anyMatch(name::endsWith));
            }
            for (String suffix : allowedSuffixes) {
                assertEquals(expectedPerSuffix, files.stream()
                        .filter(path -> path.getFileName().toString().endsWith(suffix))
                        .count());
            }
        }
    }

    private static String terminalSnapshot(
            DataSource dataSource,
            T14WorkerCrossJvmTestSupport.Prepared prepared) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT jsonb_build_object(
                         'meeting', (SELECT to_jsonb(m) FROM meeting m
                             WHERE m.meeting_id=?),
                         'input', (SELECT to_jsonb(i) FROM durable_input_fact i
                             WHERE i.meeting_id=?),
                         'usage', (SELECT to_jsonb(u) FROM final_usage_fact u
                             WHERE u.meeting_id=?),
                         'execution', (SELECT to_jsonb(e)
                             FROM finalization_execution_fact e WHERE e.meeting_id=?),
                         'workflow', (SELECT to_jsonb(w) FROM workflow_step w
                             WHERE w.meeting_id=?),
                         'manifests', (SELECT coalesce(
                             jsonb_agg(to_jsonb(r) ORDER BY r.manifest_revision),
                             '[]'::jsonb)
                             FROM meeting_manifest_revision r WHERE r.meeting_id=?),
                         'artifacts', (SELECT coalesce(
                             jsonb_agg(to_jsonb(a) ORDER BY a.artifact_id), '[]'::jsonb)
                             FROM artifact a WHERE a.meeting_id=?),
                         'reservation', (SELECT to_jsonb(r) FROM usage_reservation r
                             WHERE r.deployment_scope=? AND r.subject_scope=?
                               AND r.reservation_id=?),
                         'operations', (SELECT coalesce(
                             jsonb_agg(to_jsonb(o) ORDER BY o.operation_scope,
                                 o.idempotency_key), '[]'::jsonb)
                             FROM usage_operation o
                             WHERE o.deployment_scope=? AND o.subject_scope=?))::text
                     """)) {
            int index = 1;
            for (int count = 0; count < 7; count++) {
                statement.setString(index++, prepared.meetingId());
            }
            statement.setString(index++, T14WorkerCrossJvmTestSupport.DEPLOYMENT);
            statement.setString(index++, T14WorkerCrossJvmTestSupport.SUBJECT);
            statement.setString(index++, prepared.boundReservation().reservationId());
            statement.setString(index++, T14WorkerCrossJvmTestSupport.DEPLOYMENT);
            statement.setString(index, T14WorkerCrossJvmTestSupport.SUBJECT);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                String snapshot = rows.getString(1);
                assertFalse(rows.next());
                return snapshot;
            }
        }
    }

    private static String fileSnapshot(Path root) throws Exception {
        List<String> facts = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
                if (path.equals(root)) {
                    continue;
                }
                Path relative = root.relativize(path);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    facts.add("D " + relative);
                } else {
                    byte[] bytes = Files.readAllBytes(path);
                    facts.add("F " + relative + ' ' + bytes.length + ' '
                            + HexFormat.of().formatHex(
                                    MessageDigest.getInstance("SHA-256").digest(bytes)));
                }
            }
        }
        return String.join("\n", facts);
    }

    private static ProbeResult runProbe(
            String schema,
            Path artifactRoot,
            Path controlFile,
            String action,
            String scenario) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(
                java,
                "-cp",
                classpath,
                MockFinalizationWorkerCrossJvmProcessProbe.class.getName(),
                action,
                scenario).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("T14_WORKER_CROSS_JVM_DATABASE_URL", scopedUrl(schema));
        environment.put("T14_WORKER_CROSS_JVM_ARTIFACT_ROOT", artifactRoot.toString());
        environment.put("T14_WORKER_CROSS_JVM_CONTROL_FILE", controlFile.toString());
        environment.put("REFERENCE_TEST_DATABASE_USER",
                requiredEnvironment("REFERENCE_TEST_DATABASE_USER"));
        environment.put("REFERENCE_TEST_DATABASE_PASSWORD",
                requiredEnvironment("REFERENCE_TEST_DATABASE_PASSWORD"));
        Process process = builder.start();
        if (!process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS),
                    "timed-out probe could not be reaped");
            throw new AssertionError("probe timed out: " + action + '/' + scenario);
        }
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProbeResult(process.exitValue(), output);
    }

    private static void assertRedacted(
            String output,
            String schema,
            Path artifactRoot,
            Path controlFile,
            T14WorkerCrossJvmTestSupport.Prepared prepared,
            List<String> staleLeaseSecrets) throws Exception {
        List<String> protectedValues = new ArrayList<>(List.of(
                requiredEnvironment("REFERENCE_TEST_DATABASE_URL"),
                scopedUrl(schema),
                requiredEnvironment("REFERENCE_TEST_DATABASE_USER"),
                requiredEnvironment("REFERENCE_TEST_DATABASE_PASSWORD"),
                schema,
                artifactRoot.toString(),
                controlFile.toString(),
                prepared.meetingId(),
                prepared.receipt(),
                prepared.input().inputBinding(),
                prepared.input().contentDigest(),
                prepared.boundReservation().reservationId(),
                T14WorkerCrossJvmTestSupport.DEPLOYMENT,
                T14WorkerCrossJvmTestSupport.SUBJECT,
                prepared.checkpoint().workflowKey().stepKey(),
                FinalizationCheckpointJson.encode(prepared.checkpoint()),
                FinalizationCheckpointJson.sha256(prepared.checkpoint()),
                prepared.checkpoint().commitUsageRequest().idempotencyKey(),
                prepared.checkpoint().commitUsageRequest().requestDigest()));
        protectedValues.addAll(staleLeaseSecrets);
        ArtifactStore store = new PersistentFileSystemMockArtifactStore(
                artifactRoot, T14WorkerCrossJvmTestSupport.ARTIFACT_LIMIT);
        for (FinalizationCheckpoint.ArtifactMaterial material
                : prepared.checkpoint().artifacts()) {
            protectedValues.add(material.spec().artifactId());
            protectedValues.add(material.claim().checksum());
            protectedValues.add(material.canonicalContent());
            protectedValues.add(store.createWriteIntent(
                    material.spec()).intent().binding().value());
        }
        assertFalse(output.contains("jdbc:"), "probe output leaked a JDBC URL");
        for (String protectedValue : protectedValues) {
            assertFalse(output.contains(protectedValue),
                    "probe output leaked a protected value");
        }
    }

    /**
     * 在 JVM-B 接管前读取旧 lease 和 marker 私有字段，只用于输出泄露反例。
     * 返回值不参与恢复、claim 或任何业务断言。
     */
    private static List<String> readActiveLeaseSecrets(
            DataSource dataSource,
            String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT lease_owner, lease_token, error_code, error_message
                       FROM workflow_step
                      WHERE meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                List<String> secrets = new ArrayList<>();
                for (int index = 1; index <= 4; index++) {
                    String value = rows.getString(index);
                    if (value != null) {
                        secrets.add(value);
                    }
                }
                assertFalse(rows.next());
                return List.copyOf(secrets);
            }
        }
    }

    private static void applyMigrations(DataSource dataSource) throws Exception {
        for (String migration : MIGRATIONS) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(readMigration(migration));
            }
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

    private static void createSchema(String schema) throws Exception {
        try (Connection connection = postgres(null).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
    }

    private static void dropSchema(String schema) throws Exception {
        try (Connection connection = postgres(null).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + quote(schema) + " CASCADE");
        }
    }

    private static void assertSchemaAbsent(String schema) throws Exception {
        try (Connection connection = postgres(null).getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM information_schema.schemata WHERE schema_name=?")) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
                assertFalse(rows.next());
            }
        }
    }

    private static PGSimpleDataSource postgres(String schema) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(requiredEnvironment("REFERENCE_TEST_DATABASE_URL"));
        source.setUser(requiredEnvironment("REFERENCE_TEST_DATABASE_USER"));
        source.setPassword(requiredEnvironment("REFERENCE_TEST_DATABASE_PASSWORD"));
        if (schema != null) {
            source.setCurrentSchema(schema);
        }
        return source;
    }

    private static String scopedUrl(String schema) {
        String base = requiredEnvironment("REFERENCE_TEST_DATABASE_URL");
        return base + (base.contains("?") ? '&' : '?') + "currentSchema=" + schema;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static void rejectReservedDatabaseEndpoint() {
        String url = requiredEnvironment("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException(
                    "cross JVM gate refuses the reserved PostgreSQL endpoint");
        }
    }

    private static String scalar(
            DataSource dataSource,
            String sql,
            String parameter) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                String result = rows.getString(1);
                assertFalse(rows.next());
                return result;
            }
        }
    }

    private static int countForMeeting(
            DataSource dataSource,
            String table,
            String meetingId) throws Exception {
        if (!Set.of(
                "durable_input_fact",
                "final_usage_fact",
                "finalization_execution_fact",
                "workflow_step",
                "meeting_manifest_revision",
                "artifact").contains(table)) {
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

    private static int count(DataSource dataSource, String table) throws Exception {
        if (!Set.of("meeting").contains(table)) {
            throw new IllegalArgumentException("unexpected table");
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static int countReceipt(DataSource dataSource, String receipt)
            throws Exception {
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

    private static int countCommitOperations(
            DataSource dataSource,
            T14WorkerCrossJvmTestSupport.Prepared prepared) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*)
                       FROM usage_operation
                      WHERE deployment_scope=? AND subject_scope=?
                        AND operation_scope=? AND idempotency_key=?
                     """)) {
            statement.setString(1, T14WorkerCrossJvmTestSupport.DEPLOYMENT);
            statement.setString(2, T14WorkerCrossJvmTestSupport.SUBJECT);
            statement.setString(3, UsageOperationScopes.COMMIT);
            statement.setString(
                    4, prepared.checkpoint().commitUsageRequest().idempotencyKey());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static long countSuffix(Path directory, String suffix) throws Exception {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(suffix))
                    .count();
        }
    }

    private static void assertPrivateControlFile(Path controlFile) throws Exception {
        assertTrue(Files.isRegularFile(controlFile, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.isSymbolicLink(controlFile));
        assertTrue(Files.size(controlFile) > 0 && Files.size(controlFile) <= 4096);
        try {
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(controlFile));
        } catch (UnsupportedOperationException ignored) {
            // Linux 总门禁会执行精确权限断言；非 POSIX 文件系统保留结构检查。
        }
    }

    private void deleteScenarioTree(Path scenarioRoot) throws Exception {
        Path normalizedRoot = scenarioRoot.toAbsolutePath().normalize();
        Path normalizedTemp = temporaryDirectory.toAbsolutePath().normalize();
        if (normalizedRoot.equals(normalizedTemp)
                || !normalizedRoot.startsWith(normalizedTemp)) {
            throw new IllegalStateException("refusing to delete an unowned test path");
        }
        if (!Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(normalizedRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private enum Scenario {
        ARTIFACT_PARTIAL("artifact-partial", "FIRST_ARTIFACT_READY", false, false),
        ENTITLEMENT_COMMITTED(
                "entitlement-committed", "ENTITLEMENT_COMMITTED", true, false),
        SUCCESS_MARKER(
                "success-marker", "SUCCESS_MARKER_PERSISTED", true, false),
        COMPLETION_COMMITTED(
                "completion-committed", "COMPLETION_COMMITTED", true, false),
        FAILURE_MARKER(
                "failure-marker", "FAILURE_MARKER_PERSISTED", false, true);

        private final String cliName;
        private final String crashCheckpoint;
        private final boolean commitExpectedAfterCrash;
        private final boolean failure;

        Scenario(
                String cliName,
                String crashCheckpoint,
                boolean commitExpectedAfterCrash,
                boolean failure) {
            this.cliName = cliName;
            this.crashCheckpoint = crashCheckpoint;
            this.commitExpectedAfterCrash = commitExpectedAfterCrash;
            this.failure = failure;
        }

        private Instant commitAt() {
            if (failure) {
                throw new IllegalStateException(
                        "failure scenario has no entitlement commit time");
            }
            return this == ARTIFACT_PARTIAL
                    ? T14WorkerCrossJvmTestSupport.RECOVERY_CLAIM_AT
                    : T14WorkerCrossJvmTestSupport.FIRST_CLAIM_AT;
        }
    }

    private record ProbeResult(int exitCode, String output) {
    }
}
