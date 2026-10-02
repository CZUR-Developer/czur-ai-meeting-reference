package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.CreateMeetingRequestFact;
import com.czur.reference.application.command.CreateMeetingRequestSnapshot;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.command.MeetingRequestCanonicalizer;
import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V013 Store 与 readiness 的隔离 PostgreSQL 16 门禁；未配置数据库时跳过。 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcCreateMeetingFactStoreIntegrationTest {
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
            "V011__finalization_execution_artifact_ready.sql",
            "V012__artifact_deletion_plan.sql",
            "V013__meeting_create_fact.sql");
    private static final String MEETING_ID = "mtg_create_fact_store_1";
    private static final String ROLLBACK_MEETING_ID = "mtg_create_fact_store_rollback";
    private static final Instant CREATED_AT = Instant.parse("2030-01-01T00:00:00Z");

    private PGSimpleDataSource admin;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        admin = postgres(null);
        schema = "create_fact_store_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        dataSource = postgres(schema);
        for (String migration : MIGRATIONS) {
            apply(migration);
            register(migration);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting (meeting_id, deployment_scope, client_session_id,
                         subject_scope, profile_id, profile_revision, state, aggregate_version,
                         created_at, updated_at)
                     VALUES (?, 'deployment-test', ?, 'LEGACY_UNOWNED',
                         'standard', 1, 'CREATED', 0, ?, ?)
                     """)) {
            statement.setString(1, MEETING_ID);
            statement.setString(2, "session-test");
            statement.setTimestamp(3, java.sql.Timestamp.from(CREATED_AT));
            statement.setTimestamp(4, java.sql.Timestamp.from(CREATED_AT));
            statement.executeUpdate();
            statement.setString(1, ROLLBACK_MEETING_ID);
            statement.setString(2, "session-rollback");
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
    void appliedReplayConflictRollbackAndNewConnectionReadback() throws Exception {
        CreateMeetingFactStore.Record winner = record(MEETING_ID);
        try (Connection connection = tx()) {
            JdbcCreateMeetingFactStore store = new JdbcCreateMeetingFactStore(connection);
            assertEquals(CreateMeetingFactStore.PersistStatus.APPLIED,
                    store.persist(winner).status());
            assertEquals(CreateMeetingFactStore.PersistStatus.REPLAYED,
                    store.persist(winner).status());
            // The idempotency key is unique independently of the meeting FK.  Keep
            // the winner's meeting id (which exists in the fixture) and vary the
            // fact timestamp so the row is a genuine conflict rather than an FK
            // failure or a replay.
            CreateMeetingFactStore.Record conflict = record(
                    MEETING_ID, winner.key(), CREATED_AT.plusSeconds(1));
            assertEquals(CreateMeetingFactStore.PersistStatus.CONFLICT,
                    store.persist(conflict).status());
            connection.commit();
        }
        try (Connection connection = tx()) {
            CreateMeetingFactStore.Record stored = new JdbcCreateMeetingFactStore(connection)
                    .find(winner.scope(), winner.key()).orElseThrow();
            assertEquals(MEETING_ID, stored.response().meetingId());
            assertEquals(winner.createdAt(), stored.createdAt());
            connection.rollback();
        }
        try (Connection connection = tx()) {
            // This key is new, and the response references a second existing
            // fixture meeting row so both V013 foreign keys and its unique
            // meeting_id constraint permit the insert before rollback.
            CreateMeetingFactStore.Record rolledBack = record(
                    ROLLBACK_MEETING_ID, rolledBackKey());
            JdbcCreateMeetingFactStore store = new JdbcCreateMeetingFactStore(connection);
            assertEquals(CreateMeetingFactStore.PersistStatus.APPLIED,
                    store.persist(rolledBack).status());
            connection.rollback();
        }
        try (Connection connection = tx()) {
            assertTrue(new JdbcCreateMeetingFactStore(connection)
                    .find(winner.scope(), winner.key()).isPresent());
            assertFalse(new JdbcCreateMeetingFactStore(connection)
                    .find("scope-create-fact", rolledBackKey()).isPresent());
            connection.rollback();
        }
    }

    @Test
    void rejectsAutoCommitAndFailsClosedForBadResponseHash() throws Exception {
        CreateMeetingFactStore.Record winner = record(MEETING_ID);
        try (Connection connection = dataSource.getConnection()) {
            assertThrows(IllegalStateException.class,
                    () -> new JdbcCreateMeetingFactStore(connection)
                            .find(winner.scope(), winner.key()));
        }
        try (Connection connection = tx()) {
            new JdbcCreateMeetingFactStore(connection).persist(winner);
            connection.commit();
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE meeting_create_fact DROP CONSTRAINT meeting_create_fact_response_ck");
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE meeting_create_fact SET response_sha256 = ?
                     WHERE scope = ? AND idempotency_key = ?
                    """)) {
                update.setBytes(1, new byte[32]);
                update.setString(2, winner.scope());
                update.setString(3, winner.key());
                update.executeUpdate();
            }
        }
        try (Connection connection = tx()) {
            JdbcCreateMeetingFactStore.StoredFactException failure = assertThrows(
                    JdbcCreateMeetingFactStore.StoredFactException.class,
                    () -> new JdbcCreateMeetingFactStore(connection)
                            .find(winner.scope(), winner.key()));
            assertEquals(JdbcCreateMeetingFactStore.FailureKind.CORRUPT_DATA, failure.kind());
            connection.rollback();
        }
    }

    @Test
    void readOnlyRepeatableSnapshotUsesTheSameStrictV013DecoderWithoutRowLock()
            throws Exception {
        CreateMeetingFactStore.Record winner = record(MEETING_ID);
        try (Connection connection = tx()) {
            new JdbcCreateMeetingFactStore(connection).persist(winner);
            connection.commit();
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            var store = new JdbcCreateMeetingFactStore(connection);
            var read = store.findByMeetingIdReadOnly(MEETING_ID).orElseThrow();
            assertEquals(winner.scope(), read.scope());
            assertEquals(winner.response().realtime().sessionToken(),
                    read.response().realtime().sessionToken());
            connection.rollback();
        }
        try (Connection connection = tx()) {
            assertThrows(IllegalStateException.class,
                    () -> new JdbcCreateMeetingFactStore(connection)
                            .findByMeetingIdReadOnly(MEETING_ID));
            connection.rollback();
        }
    }

    @Test
    void readinessRequiresRegisteredBaselineAndPhysicalCreateFact() throws Exception {
        try (JdbcDatabaseRuntime runtime = JdbcDatabaseRuntime.start(runtimeConfig(schema))) {
            assertTrue(runtime.checkReadiness().ready());
            try (Connection connection = tx()) {
                assertTrue(runtime.createMeetingFactStore(connection).getClass()
                        .getSimpleName().contains("JdbcCreateMeetingFactStore"));
                connection.rollback();
            }
        }

        String missingRegistration = createMigratedSchema("create_fact_missing_reg_", 13);
        try {
            try (Connection connection = scopedConnection(missingRegistration);
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM schema_migrations WHERE version = ?")) {
                statement.setString(1, "V001__initial_schema");
                statement.executeUpdate();
            }
            DatabaseStartupException failure = assertThrows(DatabaseStartupException.class,
                    () -> JdbcDatabaseRuntime.start(runtimeConfig(missingRegistration)));
            assertEquals(DatabaseReadiness.Status.MIGRATION_MISSING,
                    failure.readiness().status());
            assertEquals(Set.of("V001__initial_schema"),
                    failure.readiness().missingMigrations());
        } finally {
            dropSchema(missingRegistration);
        }

        String missingPhysical = createMigratedSchema("create_fact_missing_table_", 12);
        try {
            try (Connection connection = scopedConnection(missingPhysical);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE meeting_create_fact CASCADE");
            }
            DatabaseStartupException failure = assertThrows(DatabaseStartupException.class,
                    () -> JdbcDatabaseRuntime.start(runtimeConfig(missingPhysical)));
            assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID,
                    failure.readiness().status());
        } finally {
            dropSchema(missingPhysical);
        }
    }

    private CreateMeetingFactStore.Record record(String meetingId) {
        return record(meetingId, "create-fact-key-1");
    }

    private CreateMeetingFactStore.Record record(String meetingId, String key) {
        return record(meetingId, key, CREATED_AT);
    }

    private CreateMeetingFactStore.Record record(
            String meetingId, String key, Instant createdAt) {
        CreateMeetingRequestSnapshot snapshot = new CreateMeetingRequestSnapshot(
                "session-create-fact", new CreateMeetingRequestSnapshot.ProfileRef("standard", 1),
                new CreateMeetingRequestSnapshot.ClientDescriptor("app", "1", "c", "device"),
                null, "Asia/Shanghai",
                new CreateMeetingRequestSnapshot.LanguageSelection("zh-CN", List.of()),
                AudioProfile.pcm16kMono(100), Set.of(),
                new CreateMeetingRequestSnapshot.ArtifactPolicy(false, false),
                key);
        CreateMeetingRequestFact request = new CreateMeetingRequestFact(snapshot,
                MeetingRequestCanonicalizer.digest(snapshot), 1);
        CreateMeetingResponseFact response = new CreateMeetingResponseFact(
                meetingId, MeetingState.CREATED,
                new CreateMeetingRequestSnapshot.ProfileRef("standard", 1), Set.of(), List.of(),
                AudioProfile.pcm16kMono(100),
                new CreateMeetingResponseFact.RealtimeSession(
                        URI.create("https://example.test/realtime"),
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                        "secret-token", Instant.parse("2030-01-01T00:10:00Z")),
                new CreateMeetingResponseFact.Limits(3_600_000, 64_000, 200, null));
        return new CreateMeetingFactStore.Record(
                "scope-create-fact", snapshot.idempotencyKey(), request, response, createdAt);
    }

    private String rolledBackKey() {
        return "create-fact-key-mtg_create_fact_store_3";
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

    private void register(String filename) throws Exception {
        register(schema, filename);
    }

    private void register(String targetSchema, String filename) throws Exception {
        try (Connection connection = scopedConnection(targetSchema);
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO schema_migrations(version) VALUES (?)")) {
            statement.setString(1, filename.substring(0, filename.length() - 4));
            statement.executeUpdate();
        }
    }

    private String createMigratedSchema(String prefix, int count) throws Exception {
        String target = prefix + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(target));
        }
        try {
            for (String migration : MIGRATIONS.subList(0, count)) {
                try (Connection connection = scopedConnection(target); Statement statement = connection.createStatement()) {
                    statement.execute(readMigration(migration));
                }
                register(target, migration);
            }
            return target;
        } catch (Exception failure) {
            dropSchema(target);
            throw failure;
        }
    }

    private static PGSimpleDataSource postgres(String targetSchema) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(requiredUrl());
        source.setUser(required("REFERENCE_TEST_DATABASE_USER"));
        source.setPassword(required("REFERENCE_TEST_DATABASE_PASSWORD"));
        if (targetSchema != null) {
            source.setCurrentSchema(targetSchema);
        }
        return source;
    }

    private DatabaseRuntimeConfig runtimeConfig(String targetSchema) {
        String url = scopedUrl(targetSchema);
        return DatabaseRuntimeConfig.of(url, required("REFERENCE_TEST_DATABASE_USER"),
                required("REFERENCE_TEST_DATABASE_PASSWORD"), Duration.ofSeconds(5),
                Duration.ofSeconds(3), 2, 1);
    }

    private Connection scopedConnection(String targetSchema) throws Exception {
        return postgres(targetSchema).getConnection();
    }

    private String scopedUrl(String targetSchema) {
        String url = requiredUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + targetSchema;
    }

    private void dropSchema(String targetSchema) throws Exception {
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + quote(targetSchema) + " CASCADE");
        }
    }

    private String readMigration(String filename) throws Exception {
        if (!"V001__initial_schema.sql".equals(filename)) {
            return "SELECT 1";
        }
        for (Path path : new Path[]{Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename)}) {
            if (Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("migration file not found");
    }

    private static String requiredUrl() {
        String value = required("REFERENCE_TEST_DATABASE_URL");
        if (value.contains("127.0.0.1:55432") || value.contains("localhost:55432")) {
            throw new IllegalStateException("V013 tests refuse the shared local database");
        }
        return value;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
