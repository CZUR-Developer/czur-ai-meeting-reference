package com.czur.reference.application.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 当前初始化基线的编码方实测，和独立验收类分别命名。
 *
 * <p>只有显式提供隔离 MySQL 测试连接时执行。每个用例新增随机数据库，预置一张
 * 模拟旧业务哨兵表，再通过正式初始化脚本建立专用表；结束时只删除本用例创建的库。
 * 不访问实际业务库，不运行 Docker，也不调用公共 Store 冒充 M2 正常会议链。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_MYSQL_DATABASE_URL", matches = ".+")
class MySqlInitialBaselineSchemaAuthorIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-10T02:03:04.123456Z");
    private String database;
    private String oldTableDefinition;

    @BeforeEach
    void createIsolatedDatabaseAndOldBusinessSentinel() throws Exception {
        database = "ai_trans_m1_schema_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE legacy_business_sentinel (id BIGINT PRIMARY KEY, body VARCHAR(80)) ENGINE=InnoDB");
            statement.execute("INSERT INTO legacy_business_sentinel VALUES (1, '原业务哨兵😀')");
            try (var rows = statement.executeQuery("SHOW CREATE TABLE legacy_business_sentinel")) {
                assertTrue(rows.next());
                oldTableDefinition = rows.getString(2);
            }
        }
    }

    @AfterEach
    void dropOnlyTheDatabaseCreatedByThisTest() throws Exception {
        if (database == null) {
            return;
        }
        assertTrue(database.matches("ai_trans_m1_schema_[0-9a-f]{32}"));
        try (Connection connection = adminConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + database);
        }
    }

    @Test
    void initializationAndRepeatPreserveOldTableAndOneRegistration() throws Exception {
        assertSuccessfulInitialization();
        String appliedAt;
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT version, applied_at FROM ai_trans_schema_migrations")) {
                assertTrue(rows.next());
                assertEquals("V001__initial_schema", rows.getString(1));
                appliedAt = rows.getString(2);
                assertFalse(rows.next());
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*), SUM(engine='InnoDB') FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'ai\\_trans\\_%'")) {
                assertTrue(rows.next());
                assertEquals(26, rows.getInt(1));
                assertEquals(26, rows.getInt(2));
            }
        }
        ProcessResult repeated = runInitialization(null);
        assertEquals(0, repeated.exitCode(), repeated.output());
        assertTrue(repeated.output().contains("跳过"), repeated.output());
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT version, applied_at FROM ai_trans_schema_migrations")) {
                assertTrue(rows.next());
                assertEquals("V001__initial_schema", rows.getString(1));
                assertEquals(appliedAt, rows.getString(2));
                assertFalse(rows.next());
            }
            try (var rows = statement.executeQuery("SHOW CREATE TABLE legacy_business_sentinel")) {
                assertTrue(rows.next());
                assertEquals(oldTableDefinition, rows.getString(2));
            }
            try (var rows = statement.executeQuery("SELECT id, body FROM legacy_business_sentinel")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getLong(1));
                assertEquals("原业务哨兵😀", rows.getString(2));
                assertFalse(rows.next());
            }
            try (var rows = statement.executeQuery("SELECT @@foreign_key_checks")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @Test
    void utf8IdentifiersCanonicalJsonBinaryAndUtcMicrosecondsReadBackExactly() throws Exception {
        assertSuccessfulInitialization();
        String meetingId = "标识😀Case";
        String canonical = "{ \"z\":\"中文😀\", \"a\":1 }";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        try (Connection connection = connection()) {
            insertMeeting(connection, meetingId, null);
            insertMeeting(connection, "标识😀case", null);
            insertMeeting(connection, meetingId + " ", null);
            try (var statement = connection.prepareStatement("INSERT INTO ai_trans_finalization_execution_fact (meeting_id, canonical_json, execution_fact_json, content_sha256) VALUES (?, ?, ?, ?)")) {
                statement.setString(1, meetingId);
                statement.setString(2, canonical);
                statement.setString(3, canonical);
                statement.setBytes(4, digest);
                assertEquals(1, statement.executeUpdate());
            }
            try (var statement = connection.prepareStatement("INSERT INTO ai_trans_durable_input_fact (meeting_id, input_binding, last_accepted_sequence, audio_duration_ms, source_language, target_languages, accepted_at, content_digest) VALUES (?, 'input', 1, 1, 'zh', ?, ?, 'digest')")) {
                statement.setString(1, meetingId);
                statement.setString(2, "[\"en\",\"ja\"]");
                statement.setTimestamp(3, Timestamp.from(NOW), utc());
                assertEquals(1, statement.executeUpdate());
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM ai_trans_meeting")) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1));
            }
            try (var statement = connection.prepareStatement("SELECT meeting_id, canonical_json, execution_fact_json, content_sha256 FROM ai_trans_finalization_execution_fact WHERE meeting_id=?")) {
                statement.setString(1, meetingId);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(meetingId, rows.getString(1));
                    assertArrayEquals(meetingId.getBytes(StandardCharsets.UTF_8), rows.getBytes(1));
                    assertEquals(canonical, rows.getString(2));
                    ObjectMapper json = new ObjectMapper();
                    assertEquals(json.readTree(canonical), json.readTree(rows.getString(3)));
                    assertArrayEquals(digest, rows.getBytes(4));
                    assertFalse(rows.next());
                }
            }
            try (var statement = connection.prepareStatement("SELECT target_languages, accepted_at FROM ai_trans_durable_input_fact WHERE meeting_id=?")) {
                statement.setString(1, meetingId);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(new ObjectMapper().readTree("[\"en\",\"ja\"]"), new ObjectMapper().readTree(rows.getString(1)));
                    assertEquals(NOW, rows.getTimestamp(2, utc()).toInstant());
                }
            }
            try (var statement = connection.prepareStatement("INSERT INTO ai_trans_meeting_audio_input (meeting_id, writer_id, writer_epoch) VALUES (?, ?, 1)")) {
                statement.setString(1, meetingId);
                statement.setString(2, "cafe1234-1234-1234-1234-123456789abc");
                assertEquals(1, statement.executeUpdate());
            }
            byte[] audio = new byte[32];
            audio[0] = 0;
            audio[31] = (byte) 0xff;
            try (var statement = connection.prepareStatement("INSERT INTO ai_trans_meeting_audio_frame (meeting_id, stream_sequence, start_position_ms, duration_ms, audio, source_generation, format_revision, gap_duration_ms_before, flags, digest_version, content_digest) VALUES (?, 1, 0, 1, ?, 0, 0, 0, 0, 1, ?)")) {
                statement.setString(1, meetingId);
                statement.setBytes(2, audio);
                statement.setBytes(3, digest);
                assertEquals(1, statement.executeUpdate());
            }
            try (var statement = connection.prepareStatement("SELECT audio, content_digest FROM ai_trans_meeting_audio_frame WHERE meeting_id=?")) {
                statement.setString(1, meetingId);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertArrayEquals(audio, rows.getBytes(1));
                    assertArrayEquals(digest, rows.getBytes(2));
                }
            }
        }
    }

    @Test
    void bothReservationReferencesRemainImmediateForeignKeys() throws Exception {
        assertSuccessfulInitialization();
        try (Connection connection = connection()) {
            SQLException missingReservation = assertThrows(SQLException.class,
                    () -> insertMeeting(connection, "orphan-meeting", "missing-reservation"));
            assertEquals(1452, missingReservation.getErrorCode());
            connection.setAutoCommit(false);
            insertReservation(connection);
            insertMeeting(connection, "bound-meeting", "reservation");
            try (var statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("UPDATE ai_trans_usage_reservation SET bound_meeting_id='bound-meeting', bound_at=created_at, version=1 WHERE reservation_id='reservation' AND bound_meeting_id IS NULL"));
            }
            connection.commit();
            try (var statement = connection.createStatement()) {
                SQLException missingMeeting = assertThrows(SQLException.class,
                        () -> statement.executeUpdate("UPDATE ai_trans_usage_reservation SET bound_meeting_id='missing-meeting' WHERE reservation_id='reservation'"));
                assertEquals(1452, missingMeeting.getErrorCode());
                connection.rollback();
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND constraint_name IN ('ai_trans_meeting_usage_reservation_fk','ai_trans_usage_reservation_bound_meeting_fk')")) {
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt(1));
                }
            }
        }
    }

    @Test
    void failedDdlDoesNotRegisterTheCurrentBaseline() throws Exception {
        Path temporary = Files.createTempDirectory("mysql-m1-failed-baseline-");
        Path sql = temporary.resolve("V001__initial_schema.sql");
        try {
            Files.writeString(sql, "CREATE TABLE ai_trans_partial_fixture (id BIGINT PRIMARY KEY) ENGINE=InnoDB;\nTHIS_IS_AN_EXPECTED_DDL_FAILURE;\n", StandardCharsets.UTF_8);
            ProcessResult failed = runInitialization(temporary);
            assertTrue(failed.exitCode() != 0, failed.output());
            try (Connection connection = connection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ai_trans_schema_migrations'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        } finally {
            Files.deleteIfExists(sql);
            Files.deleteIfExists(temporary);
        }
    }

    private static void insertMeeting(Connection connection, String id, String reservationId) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO ai_trans_meeting (meeting_id, deployment_scope, client_session_id, subject_scope, profile_id, profile_revision, state, created_at, updated_at, metadata, usage_reservation_id) VALUES (?, 'deployment', ?, 'subject', 'profile', 1, 'CREATED', ?, ?, '{}', ?)")) {
            statement.setString(1, id);
            statement.setString(2, id);
            statement.setTimestamp(3, Timestamp.from(NOW), utc());
            statement.setTimestamp(4, Timestamp.from(NOW), utc());
            statement.setString(5, reservationId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void insertReservation(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO ai_trans_usage_reservation (reservation_id, status, created_at, updated_at, record_format, deployment_scope, subject_scope, grant_id, expected_meeting_id, usage_unit, reserved_amount, expires_at, version) VALUES ('reservation', 'RESERVED', ?, ?, 'V3', 'deployment', 'subject', 'grant', 'bound-meeting', 'MEETING_DURATION_MS', 100, ?, 0)")) {
            statement.setTimestamp(1, Timestamp.from(NOW), utc());
            statement.setTimestamp(2, Timestamp.from(NOW), utc());
            statement.setTimestamp(3, Timestamp.from(NOW.plusSeconds(100)), utc());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void assertSuccessfulInitialization() throws Exception {
        ProcessResult result = runInitialization(null);
        assertEquals(0, result.exitCode(), result.output());
    }

    private ProcessResult runInitialization(Path migrationDirectory) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("bash", initializationScript().toString());
        builder.redirectErrorStream(true);
        builder.environment().put("DATABASE_URL", scopedUrl());
        builder.environment().put("DATABASE_USER", required("REFERENCE_TEST_MYSQL_DATABASE_USER"));
        builder.environment().put("DATABASE_PASSWORD", required("REFERENCE_TEST_MYSQL_DATABASE_PASSWORD"));
        builder.environment().put("LOG_LEVEL", "DEBUG");
        builder.environment().remove("MIGRATION_DIR");
        if (migrationDirectory != null) {
            builder.environment().put("MIGRATION_DIR", migrationDirectory.toString());
        }
        Process process = builder.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "隔离初始化脚本执行超时");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(required("REFERENCE_TEST_MYSQL_DATABASE_URL"), required("REFERENCE_TEST_MYSQL_DATABASE_USER"), required("REFERENCE_TEST_MYSQL_DATABASE_PASSWORD"));
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(scopedUrl(), required("REFERENCE_TEST_MYSQL_DATABASE_USER"), required("REFERENCE_TEST_MYSQL_DATABASE_PASSWORD"));
        try (var statement = connection.createStatement()) {
            statement.execute("SET SESSION time_zone='+00:00'");
        }
        return connection;
    }

    private String scopedUrl() {
        String url = required("REFERENCE_TEST_MYSQL_DATABASE_URL");
        int query = url.indexOf('?');
        String address = query < 0 ? url : url.substring(0, query);
        String parameters = query < 0 ? "" : url.substring(query);
        return address.substring(0, address.lastIndexOf('/') + 1) + database + parameters;
    }

    private static Path initializationScript() {
        return List.of(Path.of("deploy", "migrate-mysql.sh"), Path.of("..", "deploy", "migrate-mysql.sh"))
                .stream().filter(Files::isRegularFile).findFirst().orElseThrow().toAbsolutePath();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
