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
 * 启动失败收口：只取消超过最大会议时长、且从未产生任何客户端事件的会议。
 * 正常长会从头到尾都是 STARTING，所以这里必须覆盖“有事件就不取消”的回归场景。
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_MYSQL_DATABASE_URL", matches = ".+")
class JdbcMysqlStaleStartSweeperIntegrationTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final String DEPLOYMENT = "stale-sweep-deployment";
    private static final String SUBJECT = "stale-sweep-subject";
    private static final Duration TIMEOUT = Duration.ofMinutes(60);

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
    void cancelsOnlyNeverStartedMeetingsAndNeverTouchesLongRunningOnes() throws Exception {
        Instant old = NOW.minus(Duration.ofMinutes(90));
        insertMeeting("mtg_never_started", "STARTING", old);
        insertMeeting("mtg_created_only", "CREATED", old);
        insertMeeting("mtg_long_running", "STARTING", old);
        insertClientEvent("mtg_long_running");
        insertMeeting("mtg_fresh", "STARTING", NOW.minus(Duration.ofMinutes(5)));

        JdbcStaleStartSweeper sweeper = new JdbcStaleStartSweeper(
                database, DEPLOYMENT, Clock.fixed(NOW, ZoneOffset.UTC));

        JdbcStaleStartSweeper.Result result = sweeper.runOnce();

        assertEquals(2, result.cancelledMeetings());
        assertEquals("CANCELLED", meetingState("mtg_never_started"));
        assertEquals("CANCELLED", meetingState("mtg_created_only"));
        // 正常长会出过字幕，状态必须保持不动。
        assertEquals("STARTING", meetingState("mtg_long_running"));
        assertEquals("STARTING", meetingState("mtg_fresh"));
        assertEquals(0, sweeper.runOnce().cancelledMeetings());
    }

    private void insertMeeting(String id, String state, Instant updatedAt) throws Exception {
        try (PreparedStatement statement = direct.prepareStatement(
                "INSERT INTO ai_trans_meeting (meeting_id, deployment_scope, client_session_id,"
                        + " subject_scope, profile_id, profile_revision, state, aggregate_version,"
                        + " created_at, updated_at, metadata)"
                        + " VALUES (?,?,?,?,?,?,?,0,?,?,?)")) {
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
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void insertClientEvent(String meetingId) throws Exception {
        // 事件表对 meeting_client_input 有外键，先补上输入行。
        try (PreparedStatement statement = direct.prepareStatement(
                "INSERT INTO ai_trans_meeting_client_input (meeting_id, started_at)"
                        + " VALUES (?,?)")) {
            statement.setString(1, meetingId);
            JdbcValues.setInstant(statement, 2, NOW.minus(Duration.ofMinutes(89)));
            assertEquals(1, statement.executeUpdate());
        }
        String eventId = "evt-" + UUID.randomUUID();
        // 客户端事件对规范事件表有外键，先写一条最小规范事件。
        try (PreparedStatement statement = direct.prepareStatement(
                "INSERT INTO ai_trans_meeting_event (meeting_id, event_sequence, event_id,"
                        + " event_type, payload, occurred_at)"
                        + " VALUES (?,?,?,?,CAST(? AS JSON),?)")) {
            statement.setString(1, meetingId);
            statement.setLong(2, 1);
            statement.setString(3, eventId);
            statement.setString(4, "transcript.final");
            statement.setString(5, "{}");
            JdbcValues.setInstant(statement, 6, NOW.minus(Duration.ofMinutes(88)));
            assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement = direct.prepareStatement(
                "INSERT INTO ai_trans_meeting_client_event (meeting_id, client_event_id,"
                        + " client_sequence, event_id) VALUES (?,?,?,?)")) {
            statement.setString(1, meetingId);
            statement.setString(2, "event-" + UUID.randomUUID());
            statement.setLong(3, 1);
            statement.setString(4, eventId);
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
