package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.deletion.ArtifactDeletionTarget;
import com.czur.reference.application.port.ArtifactDeletionItemUnitOfWork;
import com.czur.reference.application.port.ArtifactDeletionPlanStore;
import com.czur.reference.application.port.ArtifactDeletionTerminalUnitOfWork;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.protocol.json.ArtifactDeletionPlanJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * First T14-10-09 race gate: the fenced item transaction and terminal transaction
 * contend on the same deletion workflow while using an isolated PostgreSQL schema and
 * a random persistent file root.  The test deliberately invokes the two real UoWs;
 * it does not hand-edit deletion facts to manufacture a winner.
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcArtifactDeletionWorkerRaceIntegrationTest {
    private static final Instant ACCEPTED =
            Instant.parse("2026-09-29T08:00:00.123456Z");
    private static final Instant CLAIMED_AT = ACCEPTED.plusSeconds(1);
    private static final Instant ITEM_AT = ACCEPTED.plusSeconds(2);
    private static final Instant TERMINAL_AT = ACCEPTED.plusSeconds(3);
    private static final byte[] CONTENT = "deletion-race-content".getBytes();
    private static final List<String> MIGRATIONS = List.of(
            "V001__initial_schema.sql", "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql", "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql", "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql", "V008__durable_input_fact.sql",
            "V009__final_usage_fact.sql", "V010__workflow_step_fencing.sql",
            "V011__finalization_execution_artifact_ready.sql",
            "V012__artifact_deletion_plan.sql");

    private PGSimpleDataSource admin;
    private PGSimpleDataSource dataSource;
    private String schema;
    private String meeting;
    private Path root;
    private PersistentFileSystemMockArtifactStore artifactStore;
    private ArtifactDeletionPlan plan;
    private WorkflowStepStore.LeaseToken lease;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("REFERENCE_TEST_DATABASE_URL");
        if (url.contains(":55432")) {
            throw new IllegalStateException("integration test refuses shared 55432 database");
        }
        admin = postgres(null);
        schema = "deletion_race_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        dataSource = postgres(schema);
        for (String migration : MIGRATIONS) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(readMigration(migration));
            }
        }
        meeting = "mtg_deletion_race_" + UUID.randomUUID().toString().replace("-", "");
        insertMeeting();
        root = Files.createTempDirectory("artifact-deletion-race-");
        artifactStore = new PersistentFileSystemMockArtifactStore(root, 4096);
        plan = preparePlanAndWorkflow();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (admin != null && schema != null) {
            try (Connection connection = admin.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + quote(schema) + " CASCADE");
            }
        }
        if (root != null) {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // teardown must not hide the assertion that ran before it
                    }
                });
            }
        }
    }

    @Test
    void itemAndTerminalRaceSerializesAndLeavesExactlyOneTerminalFactSet() throws Exception {
        JdbcArtifactDeletionItemUnitOfWork item =
                new JdbcArtifactDeletionItemUnitOfWork(dataSource, artifactStore);
        JdbcArtifactDeletionTerminalUnitOfWork terminal =
                new JdbcArtifactDeletionTerminalUnitOfWork(dataSource);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ArtifactDeletionItemUnitOfWork.Result> itemFuture = executor.submit(() -> {
                start.await();
                return item.delete(new ArtifactDeletionItemUnitOfWork.DeleteRequest(
                        lease, 0, plan.targets().getFirst().spec(), ITEM_AT));
            });
            Future<ArtifactDeletionTerminalUnitOfWork.Result> terminalFuture = executor.submit(() -> {
                start.await();
                return terminal.complete(new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(
                        lease, TERMINAL_AT));
            });
            start.countDown();

            ArtifactDeletionItemUnitOfWork.Result itemResult =
                    itemFuture.get(10, TimeUnit.SECONDS);
            ArtifactDeletionTerminalUnitOfWork.Result terminalResult =
                    terminalFuture.get(10, TimeUnit.SECONDS);
            assertInstanceOf(ArtifactDeletionItemUnitOfWork.Success.class, itemResult);
            // Terminal can win the Meeting lock first and observe the still-pending item;
            // it must then refuse the terminal transition rather than publish DELETED.
            if (terminalResult instanceof ArtifactDeletionTerminalUnitOfWork.Failure failure) {
                assertEquals(ArtifactDeletionTerminalUnitOfWork.ErrorCode.ITEM_NOT_CONFIRMED,
                        failure.code());
            } else {
                assertInstanceOf(ArtifactDeletionTerminalUnitOfWork.Success.class, terminalResult);
            }
        }

        // A terminal attempt after the item winner must converge to the one terminal set.
        ArtifactDeletionTerminalUnitOfWork.Result completed =
                new JdbcArtifactDeletionTerminalUnitOfWork(dataSource).complete(
                        new ArtifactDeletionTerminalUnitOfWork.CompleteRequest(lease, TERMINAL_AT));
        assertInstanceOf(ArtifactDeletionTerminalUnitOfWork.Success.class, completed);
        assertTrue(
                ((ArtifactDeletionTerminalUnitOfWork.Success) completed).code()
                        == ArtifactDeletionTerminalUnitOfWork.SuccessCode.COMPLETED
                        || ((ArtifactDeletionTerminalUnitOfWork.Success) completed).code()
                        == ArtifactDeletionTerminalUnitOfWork.SuccessCode.REPLAYED);

        assertEquals(1, count("SELECT count(*) FROM meeting WHERE meeting_id=? AND state='DELETED'"));
        assertEquals(1, count("SELECT count(*) FROM meeting_deletion_plan WHERE meeting_id=? AND status='COMPLETED'"));
        assertEquals(1, count("SELECT count(*) FROM workflow_step WHERE meeting_id=? AND step_type='artifact-deletion' AND status='COMPLETED'"));
        assertEquals(1, count("SELECT count(*) FROM artifact_deletion_item WHERE meeting_id=? AND status='DELETED'"));
        assertEquals(0, count("SELECT count(*) FROM artifact WHERE meeting_id=?"));
        // This fixture has no source Manifest; terminalization must not invent a DELETED revision.
        assertEquals(0, count("SELECT count(*) FROM meeting_manifest_revision WHERE meeting_id=?"));
        assertEquals(1, countMeetingDeletedEvent());
        assertEquals(ArtifactStore.StoreState.DELETED,
                artifactStore.inspect(plan.targets().getFirst().spec()).state());
        assertTrue(Files.exists(root.resolve("tombstones")));
        assertEquals(1, regularFileCount(root.resolve("tombstones"), ".deleted"));
    }

    private ArtifactDeletionPlan preparePlanAndWorkflow() throws Exception {
        String artifactId = "artifact-race-" + meeting;
        WriteIntentSpec spec = new WriteIntentSpec(
                artifactId, meeting, "transcript.final", "application/octet-stream",
                "zh-CN", 1, CONTENT.length, checksum(CONTENT));
        ArtifactStore.WriteIntent intent = artifactStore.createWriteIntent(spec).intent();
        ArtifactStore.StageResult staged = artifactStore.writeContent(
                intent, new ByteArrayInputStream(CONTENT));
        ArtifactStore.ArtifactDescriptor descriptor = artifactStore.completeWrite(
                intent, staged.content()).artifact();
        ArtifactDeletionPlan result = new ArtifactDeletionPlan(
                meeting, ACCEPTED, null, null,
                List.of(ArtifactDeletionTarget.fromSpec(spec)));
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.APPLIED,
                    new JdbcArtifactReadyMetadataStore(connection).persistReady(descriptor).status());
            assertEquals(ArtifactDeletionPlanStore.PersistStatus.APPLIED,
                    new JdbcArtifactDeletionPlanStore(connection).register(result).status());
            connection.commit();
        }
        WorkflowStep workflow = ArtifactDeletionPlanJson.workflowStep(result);
        JdbcWorkflowStepStore workflows = new JdbcWorkflowStepStore(dataSource);
        assertEquals(WorkflowStepStore.RegisterStatus.APPLIED, workflows.register(workflow).status());
        lease = workflows.claim(new WorkflowStepStore.ClaimRequest(
                Set.of(ArtifactDeletionPlan.STEP_TYPE), "race-worker", CLAIMED_AT,
                Duration.ofMinutes(5), 1)).getFirst().lease();
        return result;
    }

    private void insertMeeting() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting (meeting_id, deployment_scope, client_session_id,
                         subject_scope, profile_id, profile_revision, state, aggregate_version,
                         finalization_receipt_id, input_state, manifest_state,
                         created_at, updated_at)
                     VALUES (?, 'deployment-race', ?, 'LEGACY_UNOWNED', 'profile-race', 1,
                         'DELETION_PENDING', 0, 'receipt-test', 'INPUT_ACCEPTED',
                         'DELETION_PENDING', ?, ?)
                     """)) {
            statement.setString(1, meeting);
            statement.setString(2, "session-" + meeting);
            statement.setTimestamp(3, java.sql.Timestamp.from(ACCEPTED));
            statement.setTimestamp(4, java.sql.Timestamp.from(ACCEPTED));
            statement.executeUpdate();
        }
    }

    private int count(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, meeting);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private int countMeetingDeletedEvent() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*) FROM outbox_event
                      WHERE aggregate_id=? AND event_type='MeetingDeleted'
                     """)) {
            statement.setString(1, meeting);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static int regularFileCount(Path directory, String suffix) throws Exception {
        try (var paths = Files.list(directory)) {
            return (int) paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix)).count();
        }
    }

    private static String checksum(byte[] value) throws Exception {
        return "sha256:" + java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static PGSimpleDataSource postgres(String schema) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(System.getenv("REFERENCE_TEST_DATABASE_URL"));
        String user = System.getenv("REFERENCE_TEST_DATABASE_USER");
        String password = System.getenv("REFERENCE_TEST_DATABASE_PASSWORD");
        if (user != null && !user.isBlank()) source.setUser(user);
        if (password != null && !password.isBlank()) source.setPassword(password);
        if (schema != null) source.setCurrentSchema(schema);
        return source;
    }

    private static String readMigration(String name) throws Exception {
        Path[] candidates = {Path.of("..", "deploy", "migrations", name), Path.of("deploy", "migrations", name)};
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) return Files.readString(candidate);
        }
        throw new IllegalStateException("migration file not found");
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
