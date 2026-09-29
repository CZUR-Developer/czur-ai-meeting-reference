package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.deletion.ArtifactDeletionTarget;
import com.czur.reference.application.port.ArtifactDeletionPlanStore;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.protocol.json.ArtifactDeletionPlanJson;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two real JVM crash boundaries for deletion-item and deletion-terminal transactions. */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcArtifactDeletionWorkerCrossJvmIntegrationTest {
    private static final Instant ACCEPTED = Instant.parse("2026-09-29T10:00:00.123456Z");
    private static final List<String> MIGRATIONS = List.of(
            "V001__initial_schema.sql", "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql", "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql", "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql", "V008__durable_input_fact.sql",
            "V009__final_usage_fact.sql", "V010__workflow_step_fencing.sql",
            "V011__finalization_execution_artifact_ready.sql",
            "V012__artifact_deletion_plan.sql");
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(45);
    private static final byte[] CONTENT = "cross-jvm-deletion".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;

    @Test
    void itemAndTerminalCommitCrashesRecoverExactlyOnceAcrossRealJvmBoundaries()
            throws Exception {
        rejectReservedDatabaseEndpoint();
        runScenario("item", false);
        runScenario("terminal", true);
    }

    private void runScenario(String scenario, boolean terminalCrash) throws Exception {
        String schema = "deletion_cross_jvm_"
                + scenario + '_' + UUID.randomUUID().toString().replace("-", "");
        Path scenarioRoot = temporaryDirectory.resolve(scenario);
        Path artifactRoot = scenarioRoot.resolve("artifacts").toAbsolutePath();
        Path controlFile = scenarioRoot.resolve("lease.bin").toAbsolutePath();
        Files.createDirectories(scenarioRoot);
        try {
            createSchema(schema);
            PGSimpleDataSource dataSource = postgres(schema);
            applyMigrations(dataSource);
            Fixture fixture = prepare(dataSource, artifactRoot, scenario);

            ProbeResult crashed = runProbe(schema, artifactRoot, controlFile,
                    terminalCrash ? "crash-terminal" : "crash-item");
            assertEquals(47, crashed.exitCode(), crashed.output());
            assertTrue(crashed.output().contains("PROBE_CRASH checkpoint=COMMIT"),
                    crashed.output());
            assertPrivateControl(controlFile);
            if (terminalCrash) {
                assertTerminalFacts(dataSource, fixture, true);
            } else {
                assertItemCrashBoundary(dataSource, fixture);
            }

            ProbeResult recovered = runProbe(schema, artifactRoot, controlFile,
                    terminalCrash ? "recover-terminal" : "recover-item");
            assertEquals(0, recovered.exitCode(), recovered.output());
            if (terminalCrash) {
                assertTrue(recovered.output().contains(
                        "PROBE_RECOVERED scenario=terminal result=IDLE"), recovered.output());
                assertTerminalFacts(dataSource, fixture, true);
            } else {
                assertTrue(recovered.output().contains(
                        "PROBE_RECOVERED scenario=item result=COMPLETED"), recovered.output());
                assertTerminalFacts(dataSource, fixture, true);
            }
            assertFalse(Files.exists(controlFile, LinkOption.NOFOLLOW_LINKS));
            assertSingleDeletedTombstone(artifactRoot);
        } finally {
            try {
                Files.deleteIfExists(controlFile);
            } finally {
                try {
                    dropSchema(schema);
                } finally {
                    deleteTree(scenarioRoot);
                }
            }
        }
    }

    private Fixture prepare(DataSource dataSource, Path artifactRoot, String scenario)
            throws Exception {
        String meeting = "mtg_deletion_cross_jvm_" + scenario + '_'
                + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting (meeting_id, deployment_scope, client_session_id,
                         subject_scope, profile_id, profile_revision, state, aggregate_version,
                         finalization_receipt_id, input_state, manifest_state,
                         created_at, updated_at)
                     VALUES (?, 'deployment-cross-jvm', ?, 'LEGACY_UNOWNED', 'profile-cross', 1,
                         'DELETION_PENDING', 0, 'receipt-cross-jvm', 'INPUT_ACCEPTED',
                         'DELETION_PENDING', ?, ?)
                     """)) {
            statement.setString(1, meeting);
            statement.setString(2, "session-" + meeting);
            statement.setTimestamp(3, java.sql.Timestamp.from(ACCEPTED));
            statement.setTimestamp(4, java.sql.Timestamp.from(ACCEPTED));
            statement.executeUpdate();
        }
        Files.createDirectories(artifactRoot);
        PersistentFileSystemMockArtifactStore artifacts =
                new PersistentFileSystemMockArtifactStore(artifactRoot, 4096);
        WriteIntentSpec spec = new WriteIntentSpec(
                "artifact-cross-jvm-" + scenario + '-' + meeting,
                meeting, "transcript.final", "application/octet-stream", "zh-CN",
                1, CONTENT.length, checksum(CONTENT));
        ArtifactStore.WriteIntent intent = artifacts.createWriteIntent(spec).intent();
        ArtifactStore.StageResult staged = artifacts.writeContent(
                intent, new java.io.ByteArrayInputStream(CONTENT));
        ArtifactStore.ArtifactDescriptor descriptor = artifacts.completeWrite(
                intent, staged.content()).artifact();
        ArtifactDeletionPlan plan = new ArtifactDeletionPlan(
                meeting, ACCEPTED, null, null,
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
        return new Fixture(dataSource, meeting, spec, artifactRoot, artifacts);
    }

    private void assertItemCrashBoundary(DataSource source, Fixture fixture) throws Exception {
        assertEquals("DELETION_PENDING", scalar(source,
                "SELECT state FROM meeting WHERE meeting_id=?", fixture.meeting()));
        assertEquals("LEASED", scalar(source,
                "SELECT status FROM workflow_step WHERE meeting_id=?", fixture.meeting()));
        assertEquals("PENDING", scalar(source,
                "SELECT status FROM meeting_deletion_plan WHERE meeting_id=?", fixture.meeting()));
        assertEquals("DELETED", scalar(source,
                "SELECT status FROM artifact_deletion_item WHERE meeting_id=?", fixture.meeting()));
        assertEquals(0, count(source, "SELECT count(*) FROM artifact WHERE meeting_id=?",
                fixture.meeting()));
        assertEquals(0, count(source, "SELECT count(*) FROM outbox_event WHERE event_type='MeetingDeleted'",
                null));
    }

    private void assertTerminalFacts(DataSource source, Fixture fixture, boolean completed)
            throws Exception {
        assertEquals(completed ? "DELETED" : "DELETION_PENDING", scalar(source,
                "SELECT state FROM meeting WHERE meeting_id=?", fixture.meeting()));
        assertEquals(completed ? "COMPLETED" : "PENDING", scalar(source,
                "SELECT status FROM meeting_deletion_plan WHERE meeting_id=?", fixture.meeting()));
        assertEquals(completed ? "COMPLETED" : "LEASED", scalar(source,
                "SELECT status FROM workflow_step WHERE meeting_id=?", fixture.meeting()));
        assertEquals("DELETED", scalar(source,
                "SELECT status FROM artifact_deletion_item WHERE meeting_id=?", fixture.meeting()));
        assertEquals(0, count(source, "SELECT count(*) FROM artifact WHERE meeting_id=?",
                fixture.meeting()));
        assertEquals(completed ? 1 : 0, count(source,
                "SELECT count(*) FROM outbox_event WHERE event_type='MeetingDeleted'", null));
    }

    private static void assertSingleDeletedTombstone(Path root) throws Exception {
        Path tombstones = root.resolve("tombstones");
        assertTrue(Files.isDirectory(tombstones));
        try (var paths = Files.list(tombstones)) {
            assertEquals(1, paths.filter(path -> path.getFileName().toString().endsWith(".deleted"))
                    .count());
        }
    }

    private ProbeResult runProbe(String schema, Path root, Path control, String action)
            throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath,
                JdbcArtifactDeletionWorkerCrossJvmProcessProbe.class.getName(), action)
                .redirectErrorStream(true);
        builder.environment().put("T14_DELETION_CROSS_JVM_DATABASE_URL", scopedUrl(schema));
        builder.environment().put("T14_DELETION_CROSS_JVM_ARTIFACT_ROOT", root.toString());
        builder.environment().put("T14_DELETION_CROSS_JVM_CONTROL_FILE", control.toString());
        builder.environment().put("REFERENCE_TEST_DATABASE_USER", required("REFERENCE_TEST_DATABASE_USER"));
        builder.environment().put("REFERENCE_TEST_DATABASE_PASSWORD", required("REFERENCE_TEST_DATABASE_PASSWORD"));
        Process process = builder.start();
        if (!process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("probe timed out: " + action);
        }
        return new ProbeResult(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static String scalar(DataSource source, String sql, String meeting) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (meeting != null) statement.setString(1, meeting);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private static int count(DataSource source, String sql, String meeting) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (meeting != null) statement.setString(1, meeting);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static PGSimpleDataSource postgres(String schema) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(required("REFERENCE_TEST_DATABASE_URL"));
        source.setUser(required("REFERENCE_TEST_DATABASE_USER"));
        source.setPassword(required("REFERENCE_TEST_DATABASE_PASSWORD"));
        if (schema != null) source.setCurrentSchema(schema);
        return source;
    }

    private static String scopedUrl(String schema) {
        String base = required("REFERENCE_TEST_DATABASE_URL");
        return base + (base.contains("?") ? '&' : '?') + "currentSchema=" + schema;
    }

    private static void createSchema(String schema) throws Exception {
        try (Connection connection = postgres(null).getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema.replace("\"", "\"\"") + "\"");
        }
    }

    private static void dropSchema(String schema) throws Exception {
        try (Connection connection = postgres(null).getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + schema.replace("\"", "\"\"") + "\" CASCADE");
        }
    }

    private static void applyMigrations(DataSource source) throws Exception {
        for (String migration : MIGRATIONS) {
            try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute(readMigration(migration));
            }
        }
    }

    private static String readMigration(String filename) throws IOException {
        for (Path candidate : List.of(Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename))) {
            if (Files.isRegularFile(candidate)) return Files.readString(candidate);
        }
        throw new IllegalStateException("migration not found");
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void assertPrivateControl(Path path) throws Exception {
        assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.isSymbolicLink(path));
        try {
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(path));
        } catch (UnsupportedOperationException ignored) { }
    }

    private static void rejectReservedDatabaseEndpoint() {
        String url = required("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException("reserved database endpoint is forbidden");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String checksum(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Fixture(DataSource dataSource, String meeting, WriteIntentSpec spec,
                           Path artifactRoot, PersistentFileSystemMockArtifactStore artifacts) {
    }

    private record ProbeResult(int exitCode, String output) {
    }
}
