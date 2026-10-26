package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动失败收口：只取消 CREATED／STARTING 且超过阈值的会议，并释放它绑定的预占。
 * 使用独立的 MySQL 库，不连共享端口；没有提供测试库时整类跳过。
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_MYSQL_DATABASE_URL", matches = ".+")
class JdbcMysqlStaleStartSweeperIntegrationTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final String DEPLOYMENT = "stale-sweep-deployment";
    private static final String SUBJECT = "stale-sweep-subject";

    private String databaseName;
    private JdbcDatabaseContext database;
    private Connection direct;

    @BeforeEach
    void createOnlyOwnDatabase() throws Exception {
        databaseName = "ai_trans_stale_sweep_"
                + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        Path script = List.of(Path.of("..", "deploy", "migrate-mysql.sh"),
                        Path.of("deploy", "migrate-mysql.sh"))
                .stream().filter(Files::isRegularFile).findFirst().orElseThrow().toAbsolutePath();
        ProcessBuilder builder = new ProcessBuilder("bash", script.toString())
                .redirectErrorStream(true);
        builder.environment().put("DATABASE_URL", scopedUrl());
        builder.environment().put("DATABASE_USER", user());
        builder.environment().put("DATABASE_PASSWORD", password());
        builder.environment().remove("MIGRATION_DIR");
        Process process = builder.start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "独立库初始化超时");
        assertEquals(0, process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        database = JdbcDatabaseContext.start(DatabaseRuntimeConfig.of(
                scopedUrl(), user(), password(),
                Duration.ofSeconds(5), Duration.ofSeconds(2),
                Duration.ofSeconds(20), 8, 0));
        direct = DriverManager.getConnection(scopedUrl(), user(), password());
    }

    @AfterEach
    void dropOnlyOwnDatabase() throws Exception {
        if (direct != null) direct.close();
        if (database != null) database.close();
        if (databaseName != null) {
            assertTrue(databaseName.matches("ai_trans_stale_sweep_[0-9a-f]{32}"));
            try (Connection connection = admin(); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + databaseName);
            }
        }
    }

    @Test
    void cancelsOnlyStaleStartingMeetingsAndReleasesTheirReservation() throws Exception {
        // 两张表互相有外键：先插未绑定预占，再插会议，最后回填绑定。
        insertReservation("res_stale", "RESERVED");
        insertReservation("res_fresh", "RESERVED");
        insertMeeting("mtg_stale", "STARTING", NOW.minus(Duration.ofMinutes(30)), "res_stale");
        insertMeeting("mtg_fresh", "STARTING", NOW.minus(Duration.ofMinutes(1)), "res_fresh");
        insertMeeting("mtg_running", "RUNNING", NOW.minus(Duration.ofHours(3)), null);
        bindReservation("res_stale", "mtg_stale");
        bindReservation("res_fresh", "mtg_fresh");

        JdbcStaleStartSweeper sweeper = new JdbcStaleStartSweeper(
                database, DEPLOYMENT, Clock.fixed(NOW, ZoneOffset.UTC));

        JdbcStaleStartSweeper.Result result = sweeper.runOnce();

        assertEquals(1, result.cancelledMeetings());
        assertEquals(1, result.releasedReservations());
        assertEquals("CANCELLED", meetingState("mtg_stale"));
        assertEquals("CANCELLED", reservationStatus("res_stale"));
        assertEquals("STARTING", meetingState("mtg_fresh"));
        assertEquals("RESERVED", reservationStatus("res_fresh"));
        assertEquals("RUNNING", meetingState("mtg_running"));
        // 再跑一次不重复处理。
        assertEquals(0, sweeper.runOnce().cancelledMeetings());
    }

    private void insertMeeting(String id, String state, Instant updatedAt, String reservationId)
            throws Exception {
        try (PreparedStatement statement = direct.prepareStatement(
                "INSERT INTO ai_trans_meeting (meeting_id, deployment_scope, client_session_id,"
                        + " subject_scope, profile_id, profile_revision, state, aggregate_version,"
                        + " created_at, updated_at, metadata, usage_reservation_id)"
                        + " VALUES (?,?,?,?,?,?,?,0,?,?,?,?)")) {
            statement.setString(1, id);
            statement.setString(2, DEPLOYMENT);
            statement.setString(3, "session-" + id);
            statement.setString(4, SUBJECT);
            statement.setString(5, "demo-profile");
            statement.setInt(6, 2);
            statement.setString(7, state);
            JdbcValues.setInstant(statement, 8, updatedAt);
            JdbcValues.setInstant(statement, 9, updatedAt);
            statement.setString(10, "{}");
            statement.setString(11, reservationId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void insertReservation(String id, String status) throws Exception {
        try (PreparedStatement statement = direct.prepareStatement(
                "INSERT INTO ai_trans_usage_reservation (reservation_id, status, created_at,"
                        + " updated_at, record_format, deployment_scope, subject_scope,"
                        + " expected_meeting_id, bound_meeting_id, bound_at, usage_unit,"
                        + " reserved_amount, version)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0)")) {
            statement.setString(1, id);
            statement.setString(2, status);
            JdbcValues.setInstant(statement, 3, NOW.minus(Duration.ofMinutes(40)));
            JdbcValues.setInstant(statement, 4, NOW.minus(Duration.ofMinutes(40)));
            statement.setString(5, "V3");
            statement.setString(6, DEPLOYMENT);
            statement.setString(7, SUBJECT);
            statement.setString(8, "mtg_expected");
            statement.setString(9, null);
            JdbcValues.setInstant(statement, 10, NOW.minus(Duration.ofMinutes(35)));
            statement.setString(11, "MEETING_DURATION_MS");
            statement.setLong(12, 600000);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void bindReservation(String reservationId, String meetingId) throws Exception {
        try (PreparedStatement statement = direct.prepareStatement(
                "UPDATE ai_trans_usage_reservation SET bound_meeting_id=?, bound_at=?"
                        + " WHERE reservation_id=?")) {
            statement.setString(1, meetingId);
            JdbcValues.setInstant(statement, 2, NOW.minus(Duration.ofMinutes(35)));
            statement.setString(3, reservationId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private String meetingState(String id) throws Exception {
        try (PreparedStatement statement = direct.prepareStatement(
                "SELECT state FROM ai_trans_meeting WHERE meeting_id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private String reservationStatus(String id) throws Exception {
        try (PreparedStatement statement = direct.prepareStatement(
                "SELECT status FROM ai_trans_usage_reservation WHERE reservation_id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private Connection admin() throws Exception {
        String url = required("REFERENCE_TEST_MYSQL_DATABASE_URL");
        int query = url.indexOf('?');
        String base = query < 0 ? url : url.substring(0, query);
        int scheme = base.indexOf("//");
        int slash = base.indexOf('/', scheme + 2);
        String server = slash < 0 ? base + "/" : base.substring(0, slash + 1);
        return DriverManager.getConnection(server, user(), password());
    }

    private String scopedUrl() {
        String url = required("REFERENCE_TEST_MYSQL_DATABASE_URL");
        int query = url.indexOf('?');
        String suffix = query < 0 ? "" : url.substring(query);
        String base = query < 0 ? url : url.substring(0, query);
        int scheme = base.indexOf("//");
        int slash = base.indexOf('/', scheme + 2);
        return base.substring(0, slash + 1) + databaseName + suffix;
    }

    private static String user() {
        return required("REFERENCE_TEST_MYSQL_DATABASE_USER");
    }

    private static String password() {
        return required("REFERENCE_TEST_MYSQL_DATABASE_PASSWORD");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing " + name);
        }
        return value;
    }
}
