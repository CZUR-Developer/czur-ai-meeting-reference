package com.czur.reference.application.persistence.jdbc;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V015 在隔离 PostgreSQL 16 schema 中的新库、重跑及关键约束门禁。
 * 不使用共享 55432，结束时只删除本例生成的随机 schema。
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcV015DurableAudioMigrationIntegrationTest {
    private static final List<String> PREVIOUS = List.of(
            "V001__initial_schema.sql", "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql", "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql", "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql", "V008__durable_input_fact.sql",
            "V009__final_usage_fact.sql", "V010__workflow_step_fencing.sql",
            "V011__finalization_execution_artifact_ready.sql",
            "V012__artifact_deletion_plan.sql", "V013__meeting_create_fact.sql",
            "V014__realtime_token_grant.sql");
    private static final String V015 = "V015__durable_audio_input.sql";

    private String schema;

    @BeforeEach
    void createSchemaWithPreviousMigrations() throws Exception {
        schema = "audio_v015_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = source(null).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        for (String filename : PREVIOUS) {
            execute(readMigration(filename));
            register(filename);
        }
    }

    @AfterEach
    void dropOnlyOurSchema() throws Exception {
        if (schema != null) {
            try (Connection connection = source(null).getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + quote(schema) + " CASCADE");
            }
        }
    }

    @Test
    void freshMigrationRerunAndPrimaryForeignCheckConstraints() throws Exception {
        assertEquals(PREVIOUS.size(), migrationCount());
        execute(readMigration(V015));
        assertEquals(PREVIOUS.size() + 1, migrationCount());
        execute(readMigration(V015)); // SQL 本身看到已登记后无操作。
        execute("""
                INSERT INTO schema_migrations(version) VALUES ('V015__durable_audio_input')
                ON CONFLICT (version) DO NOTHING
                """); // 模拟 migrate.sh 随后的登记。
        assertEquals(PREVIOUS.size() + 1, migrationCount());
        assertEquals(1, count("SELECT count(*) FROM schema_migrations "
                + "WHERE version = 'V001__initial_schema'"));

        execute("""
                INSERT INTO meeting (meeting_id, deployment_scope, client_session_id,
                    subject_scope, profile_id, profile_revision, state, created_at, updated_at)
                VALUES ('mtg_v015', 'test', 'session-v015', 'subject', 'profile', 1,
                    'CREATED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        execute("""
                INSERT INTO meeting_audio_input (meeting_id, writer_id, writer_epoch)
                VALUES ('mtg_v015', '00000000-0000-0000-0000-000000000015', 1)
                """);
        execute("""
                INSERT INTO meeting_audio_frame (
                    meeting_id, stream_sequence, start_position_ms, duration_ms, audio,
                    source_generation, format_revision, gap_duration_ms_before,
                    flags, digest_version, content_digest)
                VALUES ('mtg_v015', 1, 0, 20, decode(repeat('00', 640), 'hex'),
                    0, 0, 0, 0, 1, decode(repeat('00', 32), 'hex'))
                """);
        assertEquals(1, count("SELECT count(*) FROM meeting_audio_frame"));

        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO meeting_audio_input (meeting_id, writer_id, writer_epoch)
                VALUES ('mtg_v015', '00000000-0000-0000-0000-000000000016', 2)
                """)); // PK
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO meeting_audio_input (meeting_id, writer_id, writer_epoch)
                VALUES ('mtg_missing', '00000000-0000-0000-0000-000000000016', 1)
                """)); // meeting FK
        assertThrows(SQLException.class, () -> execute("""
                UPDATE meeting_audio_input SET accepted_stream_sequence = 0,
                    accepted_audio_position_ms = 20 WHERE meeting_id = 'mtg_v015'
                """)); // cursor CHECK
        assertThrows(SQLException.class, () -> execute("""
                UPDATE meeting_audio_input SET complete_last_stream_sequence = 1
                 WHERE meeting_id = 'mtg_v015'
                """)); // completion all-or-none CHECK
        execute("""
                UPDATE meeting_audio_input SET
                    complete_last_stream_sequence = 1,
                    complete_last_audio_position_ms = 20,
                    complete_recording_checksum = '',
                    complete_recording_size_bytes = 0,
                    complete_request_id = 'done'
                 WHERE meeting_id = 'mtg_v015'
                """);
        assertThrows(SQLException.class, () -> execute("""
                UPDATE meeting_audio_input
                   SET complete_request_id = 'done' || repeat(' ', 125)
                 WHERE meeting_id = 'mtg_v015'
                """)); // 不得按 trim 后长度放过原长 129。
        assertThrows(SQLException.class, () -> execute("""
                UPDATE meeting_audio_input
                   SET complete_request_id = 'done' || chr(10)
                 WHERE meeting_id = 'mtg_v015'
                """));
        assertThrows(SQLException.class, () -> execute("""
                UPDATE meeting_audio_input
                   SET complete_request_id = 'done' || chr(13)
                 WHERE meeting_id = 'mtg_v015'
                """));
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO meeting_audio_frame (
                    meeting_id, stream_sequence, start_position_ms, duration_ms, audio,
                    source_generation, format_revision, gap_duration_ms_before,
                    flags, digest_version, content_digest)
                VALUES ('mtg_v015', 1, 20, 20, decode(repeat('00', 640), 'hex'),
                    0, 0, 0, 0, 1, decode(repeat('00', 32), 'hex'))
                """)); // frame PK
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO meeting_audio_frame (
                    meeting_id, stream_sequence, start_position_ms, duration_ms, audio,
                    source_generation, format_revision, gap_duration_ms_before,
                    flags, digest_version, content_digest)
                VALUES ('mtg_missing', 1, 0, 20, decode(repeat('00', 640), 'hex'),
                    0, 0, 0, 0, 1, decode(repeat('00', 32), 'hex'))
                """)); // input FK
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO meeting_audio_frame (
                    meeting_id, stream_sequence, start_position_ms, duration_ms, audio,
                    source_generation, format_revision, gap_duration_ms_before,
                    flags, digest_version, content_digest)
                VALUES ('mtg_v015', 2, 20, 20, decode(repeat('00', 639), 'hex'),
                    0, 0, 0, 0, 1, decode(repeat('00', 32), 'hex'))
                """)); // frame size CHECK
        assertEquals(1, count("SELECT count(*) FROM meeting_audio_frame"));
    }

    private int migrationCount() throws SQLException {
        return count("SELECT count(*) FROM schema_migrations");
    }

    private int count(String sql) throws SQLException {
        try (Connection connection = source(schema).getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = source(schema).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void register(String filename) throws SQLException {
        try (Connection connection = source(schema).getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO schema_migrations(version) VALUES (?)")) {
            statement.setString(1, filename.substring(0, filename.length() - 4));
            statement.executeUpdate();
        }
    }

    private static PGSimpleDataSource source(String schema) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(requiredUrl());
        source.setUser(required("REFERENCE_TEST_DATABASE_USER"));
        source.setPassword(required("REFERENCE_TEST_DATABASE_PASSWORD"));
        if (schema != null) source.setCurrentSchema(schema);
        return source;
    }

    private static String readMigration(String filename) throws Exception {
        for (Path path : new Path[]{Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename)}) {
            if (Files.isRegularFile(path)) return Files.readString(path, StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("migration file missing");
    }

    private static String requiredUrl() {
        String url = required("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException("shared database is forbidden");
        }
        return url;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required");
        return value;
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
