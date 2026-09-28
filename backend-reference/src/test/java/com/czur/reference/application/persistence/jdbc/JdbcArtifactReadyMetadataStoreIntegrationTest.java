package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore;
import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V011 READY 元数据 Store 的隔离 PostgreSQL 16 门禁；未配置数据库时跳过。 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcArtifactReadyMetadataStoreIntegrationTest {
    private static final String MEETING_ID = "mtg_artifact_store_integration";
    private PGSimpleDataSource admin;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        admin = postgres(null);
        schema = "artifact_store_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        dataSource = postgres(schema);
        apply("V001__initial_schema.sql");
        apply("V011__finalization_execution_artifact_ready.sql");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting (meeting_id, deployment_scope, client_session_id,
                         subject_scope, profile_id, profile_revision, state, aggregate_version,
                         created_at, updated_at)
                     VALUES (?, 'deployment-test', 'session-test', 'LEGACY_UNOWNED',
                         'profile-test', 1, 'CREATED', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                     """)) {
            statement.setString(1, MEETING_ID);
            statement.executeUpdate();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (admin != null && schema != null) {
            try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + quote(schema) + " CASCADE");
            }
        }
    }

    @Test
    void appliedReplayConflictRollbackAndNewConnectionRoundTrip() throws Exception {
        ArtifactDescriptor winner = descriptor("artifact-a", "intent-a", "binding-a", 1);
        try (Connection connection = tx()) {
            JdbcArtifactReadyMetadataStore store = new JdbcArtifactReadyMetadataStore(connection);
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.APPLIED,
                    store.persistReady(winner).status());
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.REPLAYED,
                    store.persistReady(winner).status());
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.CONFLICT,
                    store.persistReady(descriptor("artifact-a", "intent-b", "binding-b", 1)).status());
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.CONFLICT,
                    store.persistReady(descriptor("artifact-b", "intent-a", "binding-c", 1)).status());
            connection.commit();
        }
        try (Connection connection = tx()) {
            assertEquals(winner,
                    new JdbcArtifactReadyMetadataStore(connection)
                            .findByArtifactId("artifact-a").orElseThrow().artifact());
            connection.rollback();
        }
        try (Connection connection = tx()) {
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.APPLIED,
                    new JdbcArtifactReadyMetadataStore(connection)
                            .persistReady(descriptor("artifact-rollback", "intent-r", "binding-r", 2))
                            .status());
            connection.rollback();
        }
        try (Connection connection = tx()) {
            assertTrue(new JdbcArtifactReadyMetadataStore(connection)
                    .findByArtifactId("artifact-rollback").isEmpty());
            connection.rollback();
        }
    }

    @Test
    void rejectsAutoCommitTemporaryShadowAndMalformedRow() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThrows(IllegalStateException.class,
                    () -> new JdbcArtifactReadyMetadataStore(connection)
                            .findByArtifactId("artifact-none"));
        }
        ArtifactDescriptor winner = descriptor("artifact-shadow", "intent-shadow", "binding-shadow", 1);
        try (Connection connection = tx(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE artifact (marker TEXT)");
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.APPLIED,
                    new JdbcArtifactReadyMetadataStore(connection).persistReady(winner).status());
            connection.commit();
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE artifact DROP CONSTRAINT artifact_content_size_ck");
            statement.execute("UPDATE artifact SET content_size = -1 WHERE artifact_id = 'artifact-shadow'");
        }
        try (Connection connection = tx()) {
            assertThrows(JdbcArtifactReadyMetadataStore.StoredFactException.class,
                    () -> new JdbcArtifactReadyMetadataStore(connection)
                            .findByArtifactId("artifact-shadow"));
            connection.rollback();
        }
    }

    @Test
    void detectsDifferentRowsOnDifferentIdentityAxesWithoutWinner() throws Exception {
        ArtifactDescriptor first = descriptor("artifact-axis-a", "intent-axis-a", "binding-axis-a", 1);
        ArtifactDescriptor second = descriptor("artifact-axis-b", "intent-axis-b", "binding-axis-b", 2);
        try (Connection connection = tx()) {
            JdbcArtifactReadyMetadataStore store = new JdbcArtifactReadyMetadataStore(connection);
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.APPLIED, store.persistReady(first).status());
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.APPLIED, store.persistReady(second).status());
            // 同一 meeting/type/revision 轴与 artifactId 轴分别命中不同历史行。
            ArtifactDescriptor candidate = descriptor("artifact-axis-a", "intent-axis-b", "binding-axis-c", 2);
            ArtifactReadyMetadataStore.PersistResult result = store.persistReady(candidate);
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.CONFLICT, result.status());
            assertTrue(result.optionalMetadata().isEmpty());
            connection.rollback();
        }
    }

    @Test
    void rejectsMalformedUtf16BeforeItCanReachTheDriver() throws Exception {
        String malformed = "artifact-" + '\uD800';
        ArtifactDescriptor candidate = descriptor(malformed, "intent-malformed",
                "binding-malformed", 9);
        try (Connection connection = tx()) {
            assertThrows(IllegalArgumentException.class,
                    () -> new JdbcArtifactReadyMetadataStore(connection)
                            .persistReady(candidate));
            connection.rollback();
        }
    }

    @Test
    void rejectsMissingRecordedAtWhenThePhysicalCheckIsTemporarilyRemoved() throws Exception {
        ArtifactDescriptor candidate = descriptor("artifact-recorded-at", "intent-recorded-at",
                "binding-recorded-at", 10);
        try (Connection connection = tx()) {
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.APPLIED,
                    new JdbcArtifactReadyMetadataStore(connection)
                            .persistReady(candidate).status());
            connection.commit();
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE artifact ALTER COLUMN recorded_at DROP NOT NULL");
            statement.execute("UPDATE artifact SET recorded_at = NULL WHERE artifact_id = 'artifact-recorded-at'");
        }
        try (Connection connection = tx()) {
            assertThrows(JdbcArtifactReadyMetadataStore.StoredFactException.class,
                    () -> new JdbcArtifactReadyMetadataStore(connection)
                            .findByArtifactId("artifact-recorded-at"));
            connection.rollback();
        }
    }

    @Test
    void concurrentConnectionsProduceOneCommittedIdentityWinner() throws Exception {
        ArtifactDescriptor first = descriptor("artifact-concurrent-a", "intent-concurrent-a",
                "binding-concurrent-a", 7);
        ArtifactDescriptor second = descriptor("artifact-concurrent-b", "intent-concurrent-b",
                "binding-concurrent-b", 7);
        try (Connection firstConnection = tx(); Connection secondConnection = tx();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            JdbcArtifactReadyMetadataStore firstStore =
                    new JdbcArtifactReadyMetadataStore(firstConnection);
            JdbcArtifactReadyMetadataStore secondStore =
                    new JdbcArtifactReadyMetadataStore(secondConnection);
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.APPLIED,
                    firstStore.persistReady(first).status());
            CountDownLatch started = new CountDownLatch(1);
            Future<ArtifactReadyMetadataStore.PersistResult> loser = executor.submit(() -> {
                started.countDown();
                return secondStore.persistReady(second);
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            firstConnection.commit();
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.CONFLICT,
                    loser.get(5, TimeUnit.SECONDS).status());
            secondConnection.rollback();
        }
        try (Connection connection = tx()) {
            assertEquals(first,
                    new JdbcArtifactReadyMetadataStore(connection)
                            .findByArtifactId("artifact-concurrent-a").orElseThrow().artifact());
            connection.rollback();
        }
    }

    @Test
    void concurrentConnectionsReplayTheSameCommittedDescriptor() throws Exception {
        ArtifactDescriptor candidate = descriptor("artifact-concurrent-same",
                "intent-concurrent-same", "binding-concurrent-same", 8);
        try (Connection firstConnection = tx(); Connection secondConnection = tx();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            JdbcArtifactReadyMetadataStore firstStore =
                    new JdbcArtifactReadyMetadataStore(firstConnection);
            JdbcArtifactReadyMetadataStore secondStore =
                    new JdbcArtifactReadyMetadataStore(secondConnection);
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.APPLIED,
                    firstStore.persistReady(candidate).status());
            CountDownLatch started = new CountDownLatch(1);
            Future<ArtifactReadyMetadataStore.PersistResult> replay = executor.submit(() -> {
                started.countDown();
                return secondStore.persistReady(candidate);
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            firstConnection.commit();
            assertEquals(ArtifactReadyMetadataStore.PersistStatus.REPLAYED,
                    replay.get(5, TimeUnit.SECONDS).status());
            secondConnection.rollback();
        }
    }

    private ArtifactDescriptor descriptor(String artifactId, String intentId,
            String binding, int revision) {
        String checksum = "sha256:" + "a".repeat(64);
        WriteIntentSpec spec = new WriteIntentSpec(
                artifactId, MEETING_ID, "summary.structured", "application/json",
                "zh-CN", revision, 17, checksum);
        return new ArtifactDescriptor(
                new WriteIntent(intentId, new StorageBinding(binding), spec),
                new ContentClaim(17, checksum));
    }

    private Connection tx() throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private void apply(String filename) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(readMigration(filename));
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

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String readMigration(String filename) throws Exception {
        for (Path path : List.of(Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename))) {
            if (Files.isRegularFile(path)) return Files.readString(path, StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("migration not found");
    }

    private static String quote(String value) { return '"' + value.replace("\"", "\"\"") + '"'; }
}
