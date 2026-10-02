package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.deletion.ArtifactDeletionTarget;
import com.czur.reference.application.port.ArtifactDeletionPlanStore;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
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

/** V012 删除计划 Store 的隔离 PostgreSQL 16 集成门禁；不连接共享固定端口。 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcArtifactDeletionPlanStoreIntegrationTest {
    private static final List<String> MIGRATIONS = List.of(
            "V001__initial_schema.sql", "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql", "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql", "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql", "V008__durable_input_fact.sql",
            "V009__final_usage_fact.sql", "V010__workflow_step_fencing.sql",
            "V011__finalization_execution_artifact_ready.sql",
            "V012__artifact_deletion_plan.sql");
    private static final String MEETING = "mtg_jdbc_plan_store";
    private static final String CHECKSUM = "sha256:" + "a".repeat(64);
    private PGSimpleDataSource admin;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("REFERENCE_TEST_DATABASE_URL");
        if (url.contains(":55432")) {
            throw new IllegalStateException("integration test refuses shared 55432 database");
        }
        admin = postgres(null);
        schema = "deletion_plan_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        dataSource = postgres(schema);
        for (String migration : MIGRATIONS) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute(readMigration(migration));
            }
        }
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO meeting (meeting_id, deployment_scope, client_session_id,
                    subject_scope, profile_id, profile_revision, state, aggregate_version,
                    created_at, updated_at)
                VALUES (?, 'deployment-test', 'session-test', 'LEGACY_UNOWNED',
                    'profile-test', 1, 'CREATED', 0, ?, ?)
                """)) {
            statement.setString(1, MEETING);
            statement.setTimestamp(2, java.sql.Timestamp.from(Instant.parse("2026-09-29T08:00:00Z")));
            statement.setTimestamp(3, java.sql.Timestamp.from(Instant.parse("2026-09-29T08:00:00Z")));
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
    void firstReplayConflictAndFreshConnectionReadBack() throws Exception {
        ArtifactDeletionPlan candidate = plan("artifact-plan-a", 1);
        ArtifactDeletionPlan changed = plan("artifact-plan-b", 1);
        try (Connection connection = tx()) {
            JdbcArtifactDeletionPlanStore store = new JdbcArtifactDeletionPlanStore(connection);
            assertEquals(ArtifactDeletionPlanStore.PersistStatus.APPLIED,
                    store.register(candidate).status());
            assertEquals(ArtifactDeletionPlanStore.PersistStatus.REPLAYED,
                    store.register(candidate).status());
            ArtifactDeletionPlanStore.PersistResult conflict = store.register(changed);
            assertEquals(ArtifactDeletionPlanStore.PersistStatus.CONFLICT, conflict.status());
            assertEquals(candidate, conflict.plan().plan());
            connection.commit();
        }
        try (Connection connection = tx()) {
            MeetingPlanAssertions.assertPlan(candidate,
                    new JdbcArtifactDeletionPlanStore(connection).find(MEETING).orElseThrow());
            connection.rollback();
        }
    }

    @Test
    void concurrentSameCandidateIsSerializedByMeetingLock() throws Exception {
        ArtifactDeletionPlan candidate = plan("artifact-plan-concurrent", 2);
        try (Connection first = tx(); Connection second = tx();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            JdbcArtifactDeletionPlanStore firstStore = new JdbcArtifactDeletionPlanStore(first);
            JdbcArtifactDeletionPlanStore secondStore = new JdbcArtifactDeletionPlanStore(second);
            assertEquals(ArtifactDeletionPlanStore.PersistStatus.APPLIED,
                    firstStore.register(candidate).status());
            CountDownLatch started = new CountDownLatch(1);
            Future<ArtifactDeletionPlanStore.PersistResult> replay = executor.submit(() -> {
                started.countDown();
                return secondStore.register(candidate);
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            first.commit();
            assertEquals(ArtifactDeletionPlanStore.PersistStatus.REPLAYED,
                    replay.get(5, TimeUnit.SECONDS).status());
            second.rollback();
        }
    }

    @Test
    void malformedCanonicalFactFailsClosedWithoutSensitiveDiagnostics() throws Exception {
        ArtifactDeletionPlan candidate = plan("artifact-plan-corrupt", 3);
        try (Connection connection = tx()) {
            assertEquals(ArtifactDeletionPlanStore.PersistStatus.APPLIED,
                    new JdbcArtifactDeletionPlanStore(connection).register(candidate).status());
            connection.commit();
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE meeting_deletion_plan DROP CONSTRAINT meeting_deletion_plan_hash_ck");
            statement.execute("UPDATE meeting_deletion_plan SET content_sha256 = decode(repeat('00', 32), 'hex') WHERE meeting_id = '" + MEETING + "'");
        }
        try (Connection connection = tx()) {
            ArtifactDeletionPlanStore.StoreException failure = assertThrows(
                    ArtifactDeletionPlanStore.StoreException.class,
                    () -> new JdbcArtifactDeletionPlanStore(connection).find(MEETING));
            assertEquals(ArtifactDeletionPlanStore.StoreErrorCode.CORRUPT_DATA, failure.code());
            assertTrue(failure.getMessage().indexOf(MEETING) < 0);
            connection.rollback();
        }
    }

    @Test
    void existingPlanRowLockIsClassifiedWithoutLeakingIdentity() throws Exception {
        ArtifactDeletionPlan candidate = plan("artifact-plan-lock", 4);
        try (Connection connection = tx()) {
            assertEquals(ArtifactDeletionPlanStore.PersistStatus.APPLIED,
                    new JdbcArtifactDeletionPlanStore(connection).register(candidate).status());
            connection.commit();
        }
        try (Connection blocker = tx()) {
            try (PreparedStatement statement = blocker.prepareStatement(
                    "SELECT meeting_id FROM meeting_deletion_plan WHERE meeting_id = ? FOR UPDATE")) {
                statement.setString(1, MEETING);
                statement.executeQuery().close();
            }
            try (Connection contender = tx()) {
                ArtifactDeletionPlanStore.StoreException failure = assertThrows(
                        ArtifactDeletionPlanStore.StoreException.class,
                        () -> new JdbcArtifactDeletionPlanStore(contender).register(candidate));
                assertEquals(ArtifactDeletionPlanStore.StoreErrorCode.LOCK_CONFLICT,
                        failure.code());
                assertTrue(failure.getMessage().indexOf(MEETING) < 0);
                contender.rollback();
            }
            blocker.rollback();
        }
    }

    private Connection tx() throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static ArtifactDeletionPlan plan(String artifactId, int revision) {
        WriteIntentSpec spec = new WriteIntentSpec(artifactId, MEETING, "transcript.final",
                "application/json", "zh-CN", revision, 10, CHECKSUM);
        return new ArtifactDeletionPlan(MEETING,
                Instant.parse("2026-09-29T08:00:00.123456Z"), null, null,
                List.of(ArtifactDeletionTarget.fromSpec(spec)));
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
        if (!"V001__initial_schema.sql".equals(name)) {
            return "SELECT 1";
        }
        Path[] candidates = {Path.of("..", "deploy", "migrations", name),
                Path.of("deploy", "migrations", name)};
        for (Path candidate : candidates) if (Files.exists(candidate)) return Files.readString(candidate);
        throw new IllegalStateException("migration file not found");
    }

    private static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }

    private static final class MeetingPlanAssertions {
        static void assertPlan(ArtifactDeletionPlan expected,
                com.czur.reference.application.deletion.MeetingDeletionPlan actual) {
            assertEquals(expected, actual.plan());
            assertEquals(1, actual.items().size());
            assertEquals(0, actual.items().getFirst().ordinal());
            assertEquals(expected.targets().getFirst(), actual.items().getFirst().target());
        }
    }
}
