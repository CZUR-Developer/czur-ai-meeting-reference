package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.finalization.DeterministicMockFinalizationPlanner.ArtifactWritePlan;
import com.czur.reference.application.finalization.DeterministicMockFinalizationPlanner.Plan;
import com.czur.reference.application.port.DurableInputFactStore;
import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.artifact.ArtifactStore.PrepareStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.StoreState;
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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
 * P2-T14-08-12 最终用量、Workflow fencing 与文件系统 Artifact 的联合恢复门禁。
 *
 * <p>测试只接受脚本提供的隔离 PostgreSQL，在随机 schema 中应用 V001-V010。
 * 第一个真实子 JVM 在 Artifact 全部 READY 后直接 {@code halt(47)}，故意留下
 * LEASED workflow；第二个子 JVM 从 V008 重读输入并完成过期 lease 恢复；第三个
 * 子 JVM 以相同逻辑 identity、漂移的显式执行映射验证 Workflow/Artifact 冲突。
 * 命令行只携带 action，数据库、文件根和旧 lease 均不出现在 argv 或输出中。</p>
 *
 * <p>此门禁不发布 Manifest、不写 V001 Artifact 元数据、不改变 Meeting、不会调用
 * Entitlement usage，也不把 Storage READY 解释为 Meeting READY。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class T14FinalizationRecoveryIntegrationTest {
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
            "V010__workflow_step_fencing.sql");
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final long ARTIFACT_LIMIT = 1024 * 1024;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(45);

    @TempDir
    Path temporaryDirectory;

    @Test
    void crashAfterReadyRecoversOnceAndDriftConflictsWithoutCrossingBoundaries()
            throws Exception {
        rejectReservedDatabaseEndpoint();
        String schema = createSchema();
        Path artifactRoot = temporaryDirectory.resolve("artifacts").toAbsolutePath();
        Path controlFile = temporaryDirectory.resolve("lease-control.bin").toAbsolutePath();
        try {
            PGSimpleDataSource dataSource = postgres(schema);
            insertFinalizingAcceptedMeeting(dataSource);
            persistDurableInput(dataSource);

            Plan plan = T14FinalizationRecoveryProcessProbe.derive(
                    T14FinalizationRecoveryProcessProbe.fixtureInput(), false);
            assertEquals(2, plan.artifacts().size());
            assertEquals(2, plan.artifacts().stream()
                    .map(value -> value.spec().artifactId()).distinct().count());
            assertEquals(2, plan.artifacts().stream()
                    .map(value -> value.claim().checksum()).distinct().count());

            ForbiddenSnapshot baseline = forbiddenSnapshot(dataSource);
            assertEquals("FINALIZING", meetingState(dataSource).state());
            assertEquals("INPUT_ACCEPTED", meetingState(dataSource).inputState());
            assertEquals("NOT_STARTED", meetingState(dataSource).manifestState());
            assertEquals(1, count(dataSource, "durable_input_fact"));
            assertEquals(0, count(dataSource, "final_usage_fact"));
            assertEquals(0, count(dataSource, "workflow_step"));

            ProbeResult crashed = runProbe(
                    schema, artifactRoot, controlFile, "crash-after-ready");
            assertEquals(47, crashed.exitCode(), crashed.output());
            assertTrue(crashed.output().contains(
                    "PROBE_CRASH_READY workflow=LEASED artifacts=READY"),
                    crashed.output());
            assertFalse(crashed.output().contains("PROBE_OK"), crashed.output());
            assertRedacted(crashed.output(), schema, artifactRoot, controlFile, plan);

            assertEquals(baseline, forbiddenSnapshot(dataSource));
            assertEquals(1, count(dataSource, "final_usage_fact"));
            assertEquals(plan.finalUsage(), readFinalUsage(dataSource));
            assertLeasedWorkflow(dataSource, plan);
            List<StorageBinding> bindings = assertArtifactsReady(artifactRoot, plan);
            assertEquals(2, bindings.stream().map(StorageBinding::value).distinct().count());
            assertObjectMultiplicity(artifactRoot, plan.artifacts().size());
            assertPrivateControlFile(controlFile);

            ProbeResult recovered = runProbe(
                    schema, artifactRoot, controlFile, "recover");
            assertEquals(0, recovered.exitCode(), recovered.output());
            assertTrue(recovered.output().contains(
                    "PROBE_RECOVERED usage=REPLAYED artifacts=REPLAYED"
                            + " staleBefore=NOT_OWNER workflow=COMPLETED"
                            + " staleAfter=ALREADY_COMPLETED"), recovered.output());
            assertTrue(recovered.output().contains("PROBE_OK action=recover"),
                    recovered.output());
            assertRedacted(recovered.output(), schema, artifactRoot, controlFile, plan);

            assertFalse(Files.exists(controlFile, LinkOption.NOFOLLOW_LINKS));
            assertEquals(baseline, forbiddenSnapshot(dataSource));
            assertEquals(1, count(dataSource, "final_usage_fact"));
            assertEquals(plan.finalUsage(), readFinalUsage(dataSource));
            assertCompletedWorkflow(dataSource, plan);
            assertEquals(bindings, assertArtifactsReady(artifactRoot, plan));
            assertObjectMultiplicity(artifactRoot, plan.artifacts().size());

            RecoverySnapshot beforeDrift = recoverySnapshot(dataSource, artifactRoot);
            ProbeResult drift = runProbe(
                    schema, artifactRoot, controlFile, "verify-drift");
            assertEquals(0, drift.exitCode(), drift.output());
            assertTrue(drift.output().contains(
                    "PROBE_DRIFT workflow=CONFLICT artifacts=CONFLICT"),
                    drift.output());
            assertTrue(drift.output().contains("PROBE_OK action=verify-drift"),
                    drift.output());
            assertRedacted(drift.output(), schema, artifactRoot, controlFile, plan);

            assertEquals(beforeDrift, recoverySnapshot(dataSource, artifactRoot));
            assertEquals(baseline, forbiddenSnapshot(dataSource));
            assertFalse(Files.exists(controlFile, LinkOption.NOFOLLOW_LINKS));
            assertCompletedWorkflow(dataSource, plan);
            assertEquals(plan.finalUsage(), readFinalUsage(dataSource));
            assertEquals(bindings, assertArtifactsReady(artifactRoot, plan));
        } finally {
            try {
                Files.deleteIfExists(controlFile);
            } finally {
                dropSchema(schema);
            }
        }
    }

    private static void insertFinalizingAcceptedMeeting(DataSource dataSource)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting(
                         meeting_id, deployment_scope, client_session_id, subject_scope,
                         profile_id, profile_revision, state, aggregate_version,
                         finalization_receipt_id, created_at, updated_at,
                         input_state, manifest_state)
                     VALUES (?, 't14-recovery-deployment', 't14-recovery-session',
                             'LEGACY_UNOWNED', 'meeting-minutes/t14-mock-v1', 4,
                             'FINALIZING', 7, 'receipt-t14-recovery', ?, ?,
                             'INPUT_ACCEPTED', 'NOT_STARTED')
                     """)) {
            statement.setString(1, T14FinalizationRecoveryProcessProbe.MEETING_ID);
            statement.setTimestamp(2, Timestamp.from(
                    T14FinalizationRecoveryProcessProbe.ACCEPTED_AT.minusSeconds(60)));
            statement.setTimestamp(
                    3, Timestamp.from(T14FinalizationRecoveryProcessProbe.ACCEPTED_AT));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void persistDurableInput(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockMeeting(connection);
                var persisted = JdbcDurableInputFactStore.persist(
                        connection, T14FinalizationRecoveryProcessProbe.fixtureInput());
                assertEquals(DurableInputFactStore.PersistStatus.APPLIED, persisted.status());
                connection.commit();
            } catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void lockMeeting(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT meeting_id FROM meeting WHERE meeting_id=? FOR UPDATE")) {
            statement.setString(1, T14FinalizationRecoveryProcessProbe.MEETING_ID);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(T14FinalizationRecoveryProcessProbe.MEETING_ID,
                        rows.getString(1));
                assertFalse(rows.next());
            }
        }
    }

    private static FinalUsageFact readFinalUsage(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                FinalUsageFact fact = JdbcFinalUsageFactStore.find(
                                connection,
                                T14FinalizationRecoveryProcessProbe.MEETING_ID)
                        .orElseThrow();
                connection.rollback();
                return fact;
            } catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void assertLeasedWorkflow(DataSource dataSource, Plan plan) {
        WorkflowStep stored = new JdbcWorkflowStepStore(dataSource)
                .find(plan.workflowStep().key()).orElseThrow();
        assertWorkflowDefinition(plan.workflowStep(), stored);
        assertEquals(WorkflowStep.Status.LEASED, stored.status());
        assertEquals("worker-a", stored.leaseOwner());
        assertEquals(1, stored.leaseVersion());
        assertEquals(T14FinalizationRecoveryProcessProbe.FIRST_CLAIM_AT,
                stored.updatedAt());
        assertEquals(T14FinalizationRecoveryProcessProbe.FIRST_CLAIM_AT.plus(
                T14FinalizationRecoveryProcessProbe.LEASE_DURATION), stored.leaseUntil());
        assertEquals(0, stored.attempt());
    }

    private static void assertCompletedWorkflow(DataSource dataSource, Plan plan) {
        WorkflowStep stored = new JdbcWorkflowStepStore(dataSource)
                .find(plan.workflowStep().key()).orElseThrow();
        assertWorkflowDefinition(plan.workflowStep(), stored);
        assertEquals(WorkflowStep.Status.COMPLETED, stored.status());
        assertEquals(2, stored.leaseVersion());
        assertEquals(T14FinalizationRecoveryProcessProbe.FIRST_CLAIM_AT
                        .plus(T14FinalizationRecoveryProcessProbe.LEASE_DURATION)
                        .plusSeconds(1),
                stored.updatedAt());
        assertEquals(0, stored.attempt());
    }

    private static void assertWorkflowDefinition(WorkflowStep expected, WorkflowStep actual) {
        assertEquals(expected.key(), actual.key());
        assertEquals(expected.checkpointType(), actual.checkpointType());
        assertEquals(expected.canonicalPayload(), actual.canonicalPayload());
        assertEquals(expected.contentDigest(), actual.contentDigest());
    }

    private static List<StorageBinding> assertArtifactsReady(Path root, Plan plan)
            throws Exception {
        PersistentFileSystemMockArtifactStore store =
                new PersistentFileSystemMockArtifactStore(root, ARTIFACT_LIMIT);
        List<StorageBinding> bindings = new ArrayList<>();
        for (ArtifactWritePlan artifact : plan.artifacts()) {
            var prepared = store.createWriteIntent(artifact.spec());
            assertEquals(PrepareStatus.REPLAYED, prepared.status());
            assertEquals(artifact.spec(), prepared.intent().spec());
            var stat = store.stat(prepared.intent().binding());
            assertEquals(StoreState.READY, stat.state());
            assertNotNull(stat.artifact());
            assertEquals(artifact.spec(), stat.artifact().intent().spec());
            assertEquals(artifact.claim(), stat.artifact().content());
            try (var read = store.openRead(prepared.intent().binding())) {
                assertEquals(artifact.claim(), read.artifact().content());
                assertTrue(java.util.Arrays.equals(
                        artifact.canonicalContent().getBytes(StandardCharsets.UTF_8),
                        read.content().readAllBytes()));
            }
            bindings.add(prepared.intent().binding());
        }
        return List.copyOf(bindings);
    }

    private static void assertObjectMultiplicity(Path root, int artifactCount)
            throws Exception {
        try (var files = Files.list(root.resolve("objects"))) {
            List<Path> objects = files.toList();
            assertEquals(artifactCount, objects.stream()
                    .filter(path -> path.getFileName().toString().endsWith(".blob"))
                    .count());
            assertEquals(artifactCount, objects.stream()
                    .filter(path -> path.getFileName().toString().endsWith(".ready"))
                    .count());
            assertEquals(artifactCount * 2L, objects.size());
        }
    }

    private static void assertPrivateControlFile(Path controlFile) throws Exception {
        assertTrue(Files.isRegularFile(controlFile, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.isSymbolicLink(controlFile));
        assertEquals(PRIVATE_FILE_PERMISSIONS,
                Files.getPosixFilePermissions(controlFile, LinkOption.NOFOLLOW_LINKS));
    }

    private static ForbiddenSnapshot forbiddenSnapshot(DataSource dataSource)
            throws Exception {
        return new ForbiddenSnapshot(
                singleRowJson(dataSource, "SELECT to_jsonb(m)::text FROM meeting m"),
                count(dataSource, "meeting_manifest_revision"),
                count(dataSource, "artifact"),
                count(dataSource, "usage_operation"),
                count(dataSource, "usage_reservation"));
    }

    private static RecoverySnapshot recoverySnapshot(DataSource dataSource, Path root)
            throws Exception {
        return new RecoverySnapshot(
                rowsJson(dataSource, "SELECT to_jsonb(w)::text FROM workflow_step w"
                        + " ORDER BY meeting_id, step_type, step_key"),
                rowsJson(dataSource, "SELECT to_jsonb(f)::text FROM final_usage_fact f"
                        + " ORDER BY meeting_id"),
                rowsJson(dataSource, "SELECT to_jsonb(d)::text FROM durable_input_fact d"
                        + " ORDER BY meeting_id"),
                forbiddenSnapshot(dataSource),
                fileInventory(root));
    }

    private static MeetingStateRow meetingState(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT state, input_state, manifest_state
                       FROM meeting
                      WHERE meeting_id=?
                     """)) {
            statement.setString(1, T14FinalizationRecoveryProcessProbe.MEETING_ID);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                MeetingStateRow result = new MeetingStateRow(
                        rows.getString(1), rows.getString(2), rows.getString(3));
                assertFalse(rows.next());
                return result;
            }
        }
    }

    private static String singleRowJson(DataSource dataSource, String sql) throws Exception {
        List<String> rows = rowsJson(dataSource, sql);
        assertEquals(1, rows.size());
        return rows.getFirst();
    }

    private static List<String> rowsJson(DataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            List<String> result = new ArrayList<>();
            while (rows.next()) {
                result.add(rows.getString(1));
            }
            return List.copyOf(result);
        }
    }

    private static long count(DataSource dataSource, String table) throws Exception {
        if (!Set.of(
                "durable_input_fact", "final_usage_fact", "workflow_step",
                "meeting_manifest_revision", "artifact", "usage_operation",
                "usage_reservation").contains(table)) {
            throw new IllegalArgumentException("unexpected table");
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rows.next());
            long result = rows.getLong(1);
            assertFalse(rows.next());
            return result;
        }
    }

    private static List<FileFact> fileInventory(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            List<FileFact> result = new ArrayList<>();
            for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
                Path relative = root.relativize(path);
                if (relative.toString().isEmpty()) {
                    continue;
                }
                assertFalse(Files.isSymbolicLink(path));
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    result.add(new FileFact(relative.toString(), "directory", 0, ""));
                } else {
                    assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
                    byte[] content = Files.readAllBytes(path);
                    result.add(new FileFact(
                            relative.toString(), "file", content.length, checksum(content)));
                }
            }
            return List.copyOf(result);
        }
    }

    private static String checksum(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static ProbeResult runProbe(
            String schema,
            Path artifactRoot,
            Path controlFile,
            String action) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(
                java, "-cp", classpath,
                T14FinalizationRecoveryProcessProbe.class.getName(), action)
                .redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put(
                "T14_FINALIZATION_RECOVERY_DATABASE_URL", scopedUrl(schema));
        environment.put(
                "T14_FINALIZATION_RECOVERY_ARTIFACT_ROOT", artifactRoot.toString());
        environment.put(
                "T14_FINALIZATION_RECOVERY_CONTROL_FILE", controlFile.toString());
        environment.put("REFERENCE_TEST_DATABASE_USER",
                requiredEnvironment("REFERENCE_TEST_DATABASE_USER"));
        environment.put("REFERENCE_TEST_DATABASE_PASSWORD",
                requiredEnvironment("REFERENCE_TEST_DATABASE_PASSWORD"));
        Process process = builder.start();
        if (!process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("probe timed out: " + action);
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
            Plan plan) {
        List<String> protectedValues = new ArrayList<>(List.of(
                requiredEnvironment("REFERENCE_TEST_DATABASE_URL"),
                scopedUrl(schema),
                requiredEnvironment("REFERENCE_TEST_DATABASE_USER"),
                requiredEnvironment("REFERENCE_TEST_DATABASE_PASSWORD"),
                schema,
                artifactRoot.toString(),
                controlFile.toString(),
                T14FinalizationRecoveryProcessProbe.MEETING_ID,
                T14FinalizationRecoveryProcessProbe.fixtureInput().inputBinding(),
                T14FinalizationRecoveryProcessProbe.fixtureInput().contentDigest(),
                plan.workflowStep().key().stepKey(),
                plan.workflowStep().canonicalPayload(),
                plan.workflowStep().contentDigest()));
        for (ArtifactWritePlan artifact : plan.artifacts()) {
            protectedValues.add(artifact.spec().artifactId());
            protectedValues.add(artifact.claim().checksum());
            protectedValues.add(artifact.canonicalContent());
        }
        for (String protectedValue : protectedValues) {
            assertFalse(output.contains(protectedValue),
                    "probe output leaked a protected value");
        }
    }

    private static String createSchema() throws Exception {
        String schema = "t14_finalization_recovery_"
                + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = postgres(null).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        PGSimpleDataSource dataSource = postgres(schema);
        try {
            for (String migration : MIGRATIONS) {
                try (Connection connection = dataSource.getConnection();
                     Statement statement = connection.createStatement()) {
                    statement.execute(readMigration(migration));
                }
                try (Connection connection = dataSource.getConnection();
                     PreparedStatement statement = connection.prepareStatement(
                             "INSERT INTO schema_migrations(version) VALUES (?)")) {
                    statement.setString(1, migration.substring(0, migration.length() - 4));
                    assertEquals(1, statement.executeUpdate());
                }
            }
            return schema;
        } catch (Throwable failure) {
            dropSchema(schema);
            throw failure;
        }
    }

    private static void dropSchema(String schema) throws Exception {
        try (Connection connection = postgres(null).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS "
                    + quoteIdentifier(schema) + " CASCADE");
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
        return base + (base.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private static void rejectReservedDatabaseEndpoint() {
        String url = requiredEnvironment("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalArgumentException("reserved database endpoint is forbidden");
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static String readMigration(String filename) throws IOException {
        for (Path candidate : List.of(
                Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename))) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("migration file not found: " + filename);
    }

    private static String quoteIdentifier(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private record ProbeResult(int exitCode, String output) {
    }

    private record MeetingStateRow(String state, String inputState, String manifestState) {
    }

    private record ForbiddenSnapshot(
            String meetingRow,
            long manifestRevisionCount,
            long artifactMetadataCount,
            long usageOperationCount,
            long usageReservationCount) {
    }

    private record RecoverySnapshot(
            List<String> workflowRows,
            List<String> finalUsageRows,
            List<String> durableInputRows,
            ForbiddenSnapshot forbidden,
            List<FileFact> files) {
    }

    private record FileFact(String relativePath, String type, long size, String checksum) {
    }
}
