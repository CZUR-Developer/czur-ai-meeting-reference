package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.deletion.ArtifactDeletionTarget;
import com.czur.reference.application.port.ArtifactDeletionItemUnitOfWork;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.lang.reflect.Proxy;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Isolated PG16 and random file-root gate for the fenced single-item unit. */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcArtifactDeletionItemUnitOfWorkIntegrationTest {
    private static final String MEETING = "mtg_deletion_item_uow";
    private static final String ARTIFACT = "artifact-deletion-item-uow";
    private static final java.time.Instant ACCEPTED =
            java.time.Instant.parse("2026-09-29T08:00:00.123456Z");
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
    private Path root;
    private PersistentFileSystemMockArtifactStore artifactStore;
    private ArtifactDeletionPlan plan;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("REFERENCE_TEST_DATABASE_URL");
        if (url.contains(":55432")) {
            throw new IllegalStateException("integration test refuses shared 55432 database");
        }
        admin = postgres(null);
        schema = "deletion_item_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        dataSource = postgres(schema);
        for (String migration : MIGRATIONS) {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute(readMigration(migration));
            }
        }
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO meeting (meeting_id, deployment_scope, client_session_id,
                    subject_scope, profile_id, profile_revision, state, aggregate_version,
                    created_at, updated_at)
                VALUES (?, 'deployment-test', 'session-test', 'LEGACY_UNOWNED',
                    'profile-test', 1, 'CREATED', 0, ?, ?)
                """)) {
            statement.setString(1, MEETING);
            statement.setTimestamp(2, java.sql.Timestamp.from(ACCEPTED));
            statement.setTimestamp(3, java.sql.Timestamp.from(ACCEPTED));
            statement.executeUpdate();
        }
        root = Files.createTempDirectory("artifact-delete-uow-");
        artifactStore = new PersistentFileSystemMockArtifactStore(root, 4096);
        plan = plan();
        preparePlanAndReadyArtifact();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (admin != null && schema != null) {
            try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + quote(schema) + " CASCADE");
            }
        }
        if (root != null) {
            try (var files = Files.walk(root)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void pendingItemIsDeletedAndNewLeaseReplaysStoreAlreadyDeleted() throws Exception {
        WorkflowStepStore.LeaseToken first = claim(
                java.time.Instant.parse("2026-09-29T08:01:00.000000Z"),
                java.time.Duration.ofMinutes(1));
        ArtifactDeletionItemUnitOfWork unit =
                new JdbcArtifactDeletionItemUnitOfWork(dataSource, artifactStore);
        ArtifactDeletionItemUnitOfWork.Result firstResult = unit.delete(new ArtifactDeletionItemUnitOfWork.DeleteRequest(
                first, 0, plan.targets().getFirst().spec(),
                java.time.Instant.parse("2026-09-29T08:01:30.000000Z")));
        assertEquals(ArtifactDeletionItemUnitOfWork.SuccessCode.DELETED,
                ((ArtifactDeletionItemUnitOfWork.Success) firstResult).code());

        WorkflowStepStore.LeaseToken second = claim(
                java.time.Instant.parse("2026-09-29T08:03:00.000000Z"),
                java.time.Duration.ofMinutes(1));
        ArtifactDeletionItemUnitOfWork.Result replay = unit.delete(new ArtifactDeletionItemUnitOfWork.DeleteRequest(
                second, 0, plan.targets().getFirst().spec(),
                java.time.Instant.parse("2026-09-29T08:03:30.000000Z")));
        assertEquals(ArtifactDeletionItemUnitOfWork.SuccessCode.REPLAYED,
                ((ArtifactDeletionItemUnitOfWork.Success) replay).code());
        assertEquals(ArtifactStore.StoreState.DELETED,
                artifactStore.inspect(plan.targets().getFirst().spec()).state());
    }

    @Test
    void staleOwnerIsRejectedBeforeStoreCallAndExpiredLeaseIsRejected() throws Exception {
        WorkflowStepStore.LeaseToken first = claim(
                java.time.Instant.parse("2026-09-29T08:01:00.000000Z"),
                java.time.Duration.ofMinutes(1));
        ArtifactDeletionItemUnitOfWork unit =
                new JdbcArtifactDeletionItemUnitOfWork(dataSource, artifactStore);
        WorkflowStepStore.LeaseToken forged = new WorkflowStepStore.LeaseToken(
                first.key(), "different-owner", first.token(), first.expiresAt(),
                first.claimedAt(), first.leaseVersion());
        ArtifactDeletionItemUnitOfWork.Result owner = unit.delete(new ArtifactDeletionItemUnitOfWork.DeleteRequest(
                forged, 0, plan.targets().getFirst().spec(),
                java.time.Instant.parse("2026-09-29T08:01:30.000000Z")));
        assertEquals(ArtifactDeletionItemUnitOfWork.ErrorCode.NOT_OWNER,
                ((ArtifactDeletionItemUnitOfWork.Failure) owner).code());
        ArtifactDeletionItemUnitOfWork.Result expired = unit.delete(new ArtifactDeletionItemUnitOfWork.DeleteRequest(
                first, 0, plan.targets().getFirst().spec(),
                java.time.Instant.parse("2026-09-29T08:03:00.000000Z")));
        assertEquals(ArtifactDeletionItemUnitOfWork.ErrorCode.LEASE_EXPIRED,
                ((ArtifactDeletionItemUnitOfWork.Failure) expired).code());
        assertEquals(ArtifactStore.StoreState.READY,
                artifactStore.inspect(plan.targets().getFirst().spec()).state());
    }

    @Test
    void bindingMismatchDoesNotTouchStoreOrItem() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE artifact SET storage_binding = ? WHERE artifact_id = ?")) {
            statement.setString(1, "binding-mismatch");
            statement.setString(2, ARTIFACT);
            statement.executeUpdate();
        }
        WorkflowStepStore.LeaseToken lease = claim(
                java.time.Instant.parse("2026-09-29T08:01:00.000000Z"),
                java.time.Duration.ofMinutes(1));
        ArtifactDeletionItemUnitOfWork.Result result =
                new JdbcArtifactDeletionItemUnitOfWork(dataSource, artifactStore).delete(
                        new ArtifactDeletionItemUnitOfWork.DeleteRequest(
                                lease, 0, plan.targets().getFirst().spec(),
                                java.time.Instant.parse("2026-09-29T08:01:30.000000Z")));
        assertEquals(ArtifactDeletionItemUnitOfWork.ErrorCode.BINDING_MISMATCH,
                ((ArtifactDeletionItemUnitOfWork.Failure) result).code());
        assertEquals(ArtifactStore.StoreState.READY,
                artifactStore.inspect(plan.targets().getFirst().spec()).state());
    }

    @Test
    void commitUnknownCanBeReplayedWithAReclaimedLease() throws Exception {
        WorkflowStepStore.LeaseToken first = claim(
                java.time.Instant.parse("2026-09-29T08:01:00.000000Z"),
                java.time.Duration.ofMinutes(1));
        ArtifactDeletionItemUnitOfWork.DeleteRequest request =
                new ArtifactDeletionItemUnitOfWork.DeleteRequest(
                        first, 0, plan.targets().getFirst().spec(),
                        java.time.Instant.parse("2026-09-29T08:01:30.000000Z"));
        ArtifactDeletionItemUnitOfWork.Result unknown =
                new JdbcArtifactDeletionItemUnitOfWork(
                        new CommitUnknownDataSource(dataSource), artifactStore).delete(request);
        assertEquals(ArtifactDeletionItemUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                ((ArtifactDeletionItemUnitOfWork.Failure) unknown).code());

        WorkflowStepStore.LeaseToken second = claim(
                java.time.Instant.parse("2026-09-29T08:03:00.000000Z"),
                java.time.Duration.ofMinutes(1));
        ArtifactDeletionItemUnitOfWork.Result replay =
                new JdbcArtifactDeletionItemUnitOfWork(dataSource, artifactStore).delete(
                        new ArtifactDeletionItemUnitOfWork.DeleteRequest(
                                second, 0, plan.targets().getFirst().spec(),
                                java.time.Instant.parse("2026-09-29T08:03:30.000000Z")));
        assertEquals(ArtifactDeletionItemUnitOfWork.SuccessCode.REPLAYED,
                ((ArtifactDeletionItemUnitOfWork.Success) replay).code());
    }

    private void preparePlanAndReadyArtifact() throws Exception {
        ArtifactStore.WriteIntentSpec spec = plan.targets().getFirst().spec();
        ArtifactStore.PrepareResult prepared = artifactStore.createWriteIntent(spec);
        byte[] bytes = "delete-uow".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ArtifactStore.StageResult staged = artifactStore.writeContent(
                prepared.intent(), new java.io.ByteArrayInputStream(bytes));
        ArtifactStore.CompleteResult completed = artifactStore.completeWrite(
                prepared.intent(), staged.content());
        try (var connection = tx()) {
            new ArtifactReadyMetadataStoreContract(connection)
                    .persist(completed.artifact());
            new JdbcArtifactDeletionPlanStore(connection).register(plan);
            JdbcWorkflowStepStore.register(connection,
                    com.czur.reference.protocol.json.ArtifactDeletionPlanJson.workflowStep(plan));
            connection.commit();
        }
    }

    private WorkflowStepStore.LeaseToken claim(java.time.Instant now,
            java.time.Duration duration) throws Exception {
        try (var connection = tx()) {
            var result = JdbcWorkflowStepStore.claim(connection,
                    new WorkflowStepStore.ClaimRequest(
                            java.util.Set.of(ArtifactDeletionPlan.STEP_TYPE),
                            "item-test-worker", now, duration, 1));
            assertEquals(1, result.size());
            WorkflowStepStore.LeaseToken lease = result.getFirst().lease();
            connection.commit();
            return lease;
        }
    }

    private java.sql.Connection tx() throws Exception {
        var connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static ArtifactDeletionPlan plan() {
        byte[] bytes = "delete-uow".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        WriteIntentSpec spec = new WriteIntentSpec(
                ARTIFACT, MEETING, "transcript.final", "text/plain", "zh-CN", 1,
                bytes.length, checksum(bytes));
        return new ArtifactDeletionPlan(MEETING, ACCEPTED, null, null,
                List.of(ArtifactDeletionTarget.fromSpec(spec)));
    }

    private static String checksum(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static PGSimpleDataSource postgres(String schema) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(System.getenv("REFERENCE_TEST_DATABASE_URL"));
        String user = System.getenv("REFERENCE_TEST_DATABASE_USER");
        String password = System.getenv("REFERENCE_TEST_DATABASE_PASSWORD");
        if (user != null && !user.isBlank()) source.setUser(user);
        if (password != null) source.setPassword(password);
        if (schema != null) source.setCurrentSchema(schema);
        return source;
    }

    private static String readMigration(String name) throws Exception {
        Path[] candidates = {Path.of("..", "deploy", "migrations", name),
                Path.of("deploy", "migrations", name)};
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) return Files.readString(candidate);
        }
        throw new IllegalStateException("migration file not found");
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** Small adapter keeping the package-private JDBC metadata primitive out of test setup. */
    private static final class ArtifactReadyMetadataStoreContract {
        private final java.sql.Connection connection;
        private ArtifactReadyMetadataStoreContract(java.sql.Connection connection) {
            this.connection = connection;
        }
        private void persist(ArtifactDescriptor descriptor) {
            new JdbcArtifactReadyMetadataStore(connection).persistReady(descriptor);
        }
    }

    /** Simulates a connection whose server commit succeeded but acknowledgement was lost. */
    private static final class CommitUnknownDataSource implements DataSource {
        private final DataSource delegate;
        private CommitUnknownDataSource(DataSource delegate) {
            this.delegate = delegate;
        }
        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("commit")) {
                            connection.commit();
                            throw new SQLException("commit acknowledgement lost");
                        }
                        return method.invoke(connection, args);
                    });
        }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            return getConnection();
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
        @Override public java.io.PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        @Override public void setLogWriter(java.io.PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
        @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    }
}
