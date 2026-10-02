package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V015 按需门禁；每例只操作独立随机 schema，拒绝共享 55432。 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcV015DurableAudioReadinessIntegrationTest {
    private static final List<String> BASE = List.of(
            "V001__initial_schema.sql", "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql", "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql", "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql", "V008__durable_input_fact.sql",
            "V009__final_usage_fact.sql", "V010__workflow_step_fencing.sql",
            "V011__finalization_execution_artifact_ready.sql",
            "V012__artifact_deletion_plan.sql", "V013__meeting_create_fact.sql");
    private String schema;

    @BeforeEach
    void createSchema() throws Exception {
        schema = "audio_ready_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = source(false).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
        }
        for (String file : BASE) {
            apply(file);
            register(file);
        }
    }

    @AfterEach
    void dropSchema() throws Exception {
        if (schema != null) {
            try (Connection connection = source(false).getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
            }
        }
    }

    @Test
    void oldRuntimeStartsWithoutV015AndOptionalGateRequiresIt() throws Exception {
        try (JdbcDatabaseRuntime runtime = runtime()) {
            assertTrue(runtime.checkReadiness().ready());
            assertEquals(DatabaseReadiness.Status.MIGRATION_MISSING,
                    runtime.checkDurableAudioReadiness().status());
            assertEquals(java.util.Set.of("V015__durable_audio_input"),
                    runtime.checkDurableAudioReadiness().missingMigrations());
            apply("V015__durable_audio_input.sql");
            assertTrue(runtime.checkDurableAudioReadiness().ready());
        }
    }

    @Test
    void registeredHalfTableAndMissingColumnAreRejected() throws Exception {
        execute("INSERT INTO schema_migrations(version) VALUES ('V015__durable_audio_input')");
        execute("CREATE TABLE meeting_audio_input (meeting_id TEXT)");
        try (JdbcDatabaseRuntime runtime = runtime()) {
            assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID,
                    runtime.checkDurableAudioReadiness().status());
            assertTrue(runtime.checkReadiness().ready());
        }
        execute("DROP TABLE meeting_audio_input");
        execute("DELETE FROM schema_migrations WHERE version = 'V015__durable_audio_input'");
        apply("V015__durable_audio_input.sql");
        execute("ALTER TABLE meeting_audio_input DROP COLUMN complete_request_id CASCADE");
        try (JdbcDatabaseRuntime runtime = runtime()) {
            assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID,
                    runtime.checkDurableAudioReadiness().status());
        }
    }

    @Test
    void brokenPrimaryForeignAndCheckConstraintsAreRejected() throws Exception {
        apply("V015__durable_audio_input.sql");
        try (JdbcDatabaseRuntime runtime = runtime()) {
            assertTrue(runtime.checkDurableAudioReadiness().ready());
            execute("ALTER TABLE meeting_audio_frame DROP CONSTRAINT meeting_audio_frame_pkey");
            assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID,
                    runtime.checkDurableAudioReadiness().status());
            execute("ALTER TABLE meeting_audio_frame ADD CONSTRAINT meeting_audio_frame_pkey "
                    + "PRIMARY KEY (meeting_id, stream_sequence)");
            execute("ALTER TABLE meeting_audio_frame DROP CONSTRAINT meeting_audio_frame_input_fk");
            assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID,
                    runtime.checkDurableAudioReadiness().status());
            execute("ALTER TABLE meeting_audio_frame ADD CONSTRAINT meeting_audio_frame_input_fk "
                    + "FOREIGN KEY (meeting_id) REFERENCES meeting_audio_input(meeting_id) "
                    + "ON DELETE RESTRICT");
            execute("ALTER TABLE meeting_audio_frame DROP CONSTRAINT meeting_audio_frame_shape_ck");
            assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID,
                    runtime.checkDurableAudioReadiness().status());
        }
    }

    @Test
    void sameNameAndColumnsCannotHideWeakCheck() throws Exception {
        apply("V015__durable_audio_input.sql");
        execute("ALTER TABLE meeting_audio_input DROP CONSTRAINT meeting_audio_input_cursor_ck");
        execute("""
                ALTER TABLE meeting_audio_input
                  ADD CONSTRAINT meeting_audio_input_cursor_ck CHECK ((
                    (writer_epoch > 0 OR TRUE)
                    AND (accepted_stream_sequence >= 0 OR TRUE)
                    AND (accepted_audio_position_ms >= 0 OR TRUE)) IS TRUE)
                """);
        assertEquals("3,4,5", query("""
                SELECT array_to_string(conkey, ',') FROM pg_constraint
                 WHERE conrelid = 'meeting_audio_input'::regclass
                   AND conname = 'meeting_audio_input_cursor_ck'
                """));
        try (JdbcDatabaseRuntime runtime = runtime()) {
            assertEquals(DatabaseReadiness.Status.SCHEMA_INVALID,
                    runtime.checkDurableAudioReadiness().status());
            assertTrue(runtime.checkReadiness().ready());
        }
    }

    private JdbcDatabaseRuntime runtime() {
        return JdbcDatabaseRuntime.start(DatabaseRuntimeConfig.of(scopedUrl(),
                required("REFERENCE_TEST_DATABASE_USER"),
                required("REFERENCE_TEST_DATABASE_PASSWORD"), Duration.ofSeconds(5),
                Duration.ofSeconds(3), 2, 1));
    }

    private void apply(String file) throws Exception {
        Path path = Path.of("deploy", "migrations", file);
        if (!Files.isRegularFile(path)) path = Path.of("..", "deploy", "migrations", file);
        execute(Files.readString(path));
    }

    private void register(String file) throws Exception {
        execute("INSERT INTO schema_migrations(version) VALUES ('"
                + file.substring(0, file.length() - 4) + "')");
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = source(true).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String query(String sql) throws Exception {
        try (Connection connection = source(true).getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private PGSimpleDataSource source(boolean scoped) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(scoped ? scopedUrl() : databaseUrl());
        source.setUser(required("REFERENCE_TEST_DATABASE_USER"));
        source.setPassword(required("REFERENCE_TEST_DATABASE_PASSWORD"));
        return source;
    }

    private String scopedUrl() {
        return databaseUrl() + "?currentSchema=" + schema;
    }

    private static String databaseUrl() {
        String value = required("REFERENCE_TEST_DATABASE_URL");
        if (value.contains("127.0.0.1:55432") || value.contains("localhost:55432")) {
            throw new IllegalStateException("shared database is forbidden");
        }
        return value;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(key + " is required");
        return value;
    }
}
