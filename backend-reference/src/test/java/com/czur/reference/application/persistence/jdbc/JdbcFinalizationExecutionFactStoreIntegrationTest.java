package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.port.FinalizationExecutionFactStore;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.capability.ProductProfile;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V011 执行事实 Store 的隔离 PostgreSQL 16 门禁；未配置数据库时跳过。 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcFinalizationExecutionFactStoreIntegrationTest {
    private static final String MEETING_ID = "mtg_execution_store_integration";
    private static final Instant STARTED_AT = Instant.parse("2026-09-28T08:00:00.123456Z");
    private PGSimpleDataSource admin;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        admin = postgres(null);
        schema = "execution_store_" + UUID.randomUUID().toString().replace("-", "");
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
                         'profile-test', 1, 'CREATED', 0, ?, ?)
                     """)) {
            statement.setString(1, MEETING_ID);
            statement.setTimestamp(2, java.sql.Timestamp.from(STARTED_AT));
            statement.setTimestamp(3, java.sql.Timestamp.from(STARTED_AT));
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
        FinalizationExecutionFact winner = fact("title-a");
        try (Connection connection = tx()) {
            assertEquals(FinalizationExecutionFactStore.PersistStatus.APPLIED,
                    new JdbcFinalizationExecutionFactStore(connection).persist(fact("rollback")).status());
            connection.rollback();
        }
        try (Connection connection = tx()) {
            JdbcFinalizationExecutionFactStore store = new JdbcFinalizationExecutionFactStore(connection);
            assertEquals(FinalizationExecutionFactStore.PersistStatus.APPLIED,
                    store.persist(winner).status());
            assertEquals(FinalizationExecutionFactStore.PersistStatus.REPLAYED,
                    store.persist(winner).status());
            FinalizationExecutionFactStore.PersistResult conflict = store.persist(fact("title-b"));
            assertEquals(FinalizationExecutionFactStore.PersistStatus.CONFLICT, conflict.status());
            assertEquals(winner, conflict.fact());
            connection.commit();
        }
        try (Connection connection = tx()) {
            assertEquals(winner,
                    new JdbcFinalizationExecutionFactStore(connection).find(MEETING_ID).orElseThrow());
            connection.rollback();
        }
    }

    @Test
    void rejectsAutoCommitTemporaryShadowAndCorruptHash() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThrows(IllegalStateException.class,
                    () -> new JdbcFinalizationExecutionFactStore(connection).find(MEETING_ID));
        }
        FinalizationExecutionFact winner = fact("shadow");
        try (Connection connection = tx(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE finalization_execution_fact (marker TEXT)");
            assertEquals(FinalizationExecutionFactStore.PersistStatus.APPLIED,
                    new JdbcFinalizationExecutionFactStore(connection).persist(winner).status());
            connection.commit();
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE finalization_execution_fact DROP CONSTRAINT finalization_execution_fact_hash_ck");
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE finalization_execution_fact
                       SET canonical_json = ?, execution_fact_json = ?::jsonb
                     WHERE meeting_id = ?
                    """)) {
                String altered = com.czur.reference.protocol.json.FinalizationExecutionFactJson.encode(fact("altered"));
                update.setString(1, altered);
                update.setString(2, altered);
                update.setString(3, MEETING_ID);
                update.executeUpdate();
            }
        }
        try (Connection connection = tx()) {
            assertThrows(JdbcFinalizationExecutionFactStore.StoredFactException.class,
                    () -> new JdbcFinalizationExecutionFactStore(connection).find(MEETING_ID));
            connection.rollback();
        }
    }

    @Test
    void concurrentConnectionsProduceOneCommittedWinner() throws Exception {
        FinalizationExecutionFact first = fact("concurrent-a");
        FinalizationExecutionFact second = fact("concurrent-b");
        try (Connection firstConnection = tx(); Connection secondConnection = tx();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            JdbcFinalizationExecutionFactStore firstStore =
                    new JdbcFinalizationExecutionFactStore(firstConnection);
            JdbcFinalizationExecutionFactStore secondStore =
                    new JdbcFinalizationExecutionFactStore(secondConnection);
            assertEquals(FinalizationExecutionFactStore.PersistStatus.APPLIED,
                    firstStore.persist(first).status());
            CountDownLatch started = new CountDownLatch(1);
            Future<FinalizationExecutionFactStore.PersistResult> loser = executor.submit(() -> {
                started.countDown();
                return secondStore.persist(second);
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            firstConnection.commit();
            assertEquals(FinalizationExecutionFactStore.PersistStatus.CONFLICT,
                    loser.get(5, TimeUnit.SECONDS).status());
            secondConnection.rollback();
        }
        try (Connection connection = tx()) {
            assertEquals(first,
                    new JdbcFinalizationExecutionFactStore(connection).find(MEETING_ID).orElseThrow());
            connection.rollback();
        }
    }

    @Test
    void concurrentConnectionsReplayTheSameCommittedFact() throws Exception {
        FinalizationExecutionFact candidate = fact("concurrent-same");
        try (Connection firstConnection = tx(); Connection secondConnection = tx();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            JdbcFinalizationExecutionFactStore firstStore =
                    new JdbcFinalizationExecutionFactStore(firstConnection);
            JdbcFinalizationExecutionFactStore secondStore =
                    new JdbcFinalizationExecutionFactStore(secondConnection);
            assertEquals(FinalizationExecutionFactStore.PersistStatus.APPLIED,
                    firstStore.persist(candidate).status());
            CountDownLatch started = new CountDownLatch(1);
            Future<FinalizationExecutionFactStore.PersistResult> replay = executor.submit(() -> {
                started.countDown();
                return secondStore.persist(candidate);
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            firstConnection.commit();
            assertEquals(FinalizationExecutionFactStore.PersistStatus.REPLAYED,
                    replay.get(5, TimeUnit.SECONDS).status());
            secondConnection.rollback();
        }
    }

    private FinalizationExecutionFact fact(String title) {
        ProductProfile profile = new ProductProfile(
                "profile-test", 1, Set.of("asr"), Set.of(), Map.of(), Map.of(), Map.of(),
                Map.of("asr", List.of("caption")), Map.of("asr", List.of("transcript")));
        CapabilityPlan plan = new CapabilityPlan(1, List.of(new CapabilityDecision(
                "asr", CapabilityDecision.Availability.AVAILABLE, true,
                CapabilityDecision.Action.ENABLE, null, List.of("caption"),
                List.of("transcript"), null, 1)));
        return FinalizationExecutionFact.freeze(MEETING_ID, STARTED_AT, title, profile, plan);
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
