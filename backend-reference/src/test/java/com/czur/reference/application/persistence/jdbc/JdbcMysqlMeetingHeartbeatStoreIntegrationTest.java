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
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会议心跳 UPSERT 的 MySQL 5.7 实测，不是 HTTP 或收口验收。
 *
 * <p>每例新建独立的随机库并执行正式初始化脚本；只删除本用例创建的库。没有
 * {@code REFERENCE_TEST_MYSQL_DATABASE_URL} 时整类跳过，不访问业务库。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_MYSQL_DATABASE_URL", matches = ".+")
class JdbcMysqlMeetingHeartbeatStoreIntegrationTest {
    private static final JdbcDatabaseEngine MYSQL = JdbcDatabaseEngine.MYSQL;
    private static final Instant STARTED = Instant.parse("2030-05-01T01:02:03.123456Z");
    private static final Instant ACTIVE = STARTED.plusSeconds(120);
    private static final Instant LATER = ACTIVE.plusSeconds(120);

    private String databaseName;
    private JdbcDatabaseContext database;

    @BeforeEach
    void initializeOnlyNewTaskDatabase() throws Exception {
        databaseName = "ai_trans_hb_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        Path script = List.of(Path.of("..", "deploy", "migrate-mysql.sh"),
                        Path.of("deploy", "migrate-mysql.sh")).stream()
                .filter(Files::isRegularFile).findFirst().orElseThrow().toAbsolutePath();
        ProcessBuilder builder = new ProcessBuilder("bash", script.toString()).redirectErrorStream(true);
        builder.environment().put("DATABASE_URL", scopedUrl());
        builder.environment().put("DATABASE_USER", user());
        builder.environment().put("DATABASE_PASSWORD", password());
        builder.environment().remove("MIGRATION_DIR");
        Process process = builder.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "独立库初始化超时");
        assertEquals(0, process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        database = JdbcDatabaseContext.start(DatabaseRuntimeConfig.of(scopedUrl(), user(), password(),
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(20), 4, 0));
    }

    @AfterEach
    void dropOnlyOwnDatabase() throws Exception {
        if (database != null) {
            database.close();
        }
        if (databaseName != null) {
            assertTrue(databaseName.matches("ai_trans_hb_[0-9a-f]{32}"));
            try (Connection connection = admin(); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + databaseName);
            }
        }
    }

    @Test
    void firstHeartbeatFreezesStartAndRepeatsOnlyRefreshActivity() throws Exception {
        insertMeeting("mtg_hb_first");
        insertMeeting("mtg_hb_second");
        var store = new JdbcMeetingHeartbeatStore(database);

        assertTrue(store.record("mtg_hb_first", STARTED));
        assertRow("mtg_hb_first", STARTED, STARTED, 1);
        assertTrue(store.record("mtg_hb_first", ACTIVE));
        assertRow("mtg_hb_first", STARTED, ACTIVE, 2);
        assertTrue(store.record("mtg_hb_first", LATER));
        assertRow("mtg_hb_first", STARTED, LATER, 3);

        // 另一场会议仍是首次插入；重复心跳不会把 started_at 改成最后一次活跃。
        assertNoRow("mtg_hb_second");
        assertTrue(store.record("mtg_hb_second", LATER));
        assertRow("mtg_hb_second", LATER, LATER, 1);
        assertRow("mtg_hb_first", STARTED, LATER, 3);
    }

    @Test
    void outOfOrderHeartbeatNeverRegressesActivity() throws Exception {
        insertMeeting("mtg_hb_order");
        var store = new JdbcMeetingHeartbeatStore(database);

        assertTrue(store.record("mtg_hb_order", LATER));
        assertRow("mtg_hb_order", LATER, LATER, 1);
        // 较早取时间、较晚提交的请求不能把最后活跃改回过去，也不能低于 started_at。
        assertTrue(store.record("mtg_hb_order", STARTED));
        assertRow("mtg_hb_order", LATER, LATER, 2);
        assertTrue(store.record("mtg_hb_order", ACTIVE));
        assertRow("mtg_hb_order", LATER, LATER, 3);
    }

    @Test
    void inactiveOrDecidedMeetingCannotBeRefreshed() throws Exception {
        insertMeeting("mtg_hb_decided", "RUNNING");
        var store = new JdbcMeetingHeartbeatStore(database);
        assertTrue(store.record("mtg_hb_decided", STARTED));
        assertRow("mtg_hb_decided", STARTED, STARTED, 1);
        insertAbandonment("mtg_hb_decided");

        // 决定已落：即使状态行还是 RUNNING，本次心跳也不能刷新活跃时间。
        assertFalse(store.record("mtg_hb_decided", LATER));
        assertRow("mtg_hb_decided", STARTED, STARTED, 1);

        // 状态离开 STARTING/RUNNING 后同样拒绝，不能把已停止会议重新记活。
        insertMeeting("mtg_hb_stopping", "STOPPING");
        assertFalse(store.record("mtg_hb_stopping", ACTIVE));
        assertNoRow("mtg_hb_stopping");
    }

    @Test
    void unknownMeetingIsRejectedWithoutCreatingRow() throws Exception {
        var store = new JdbcMeetingHeartbeatStore(database);

        assertFalse(store.record("mtg_hb_missing", STARTED));
        assertNoRow("mtg_hb_missing");
    }

    private void insertMeeting(String meetingId) throws Exception {
        insertMeeting(meetingId, "RUNNING");
    }

    private void insertMeeting(String meetingId, String state) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO ai_trans_meeting (meeting_id, deployment_scope,"
                             + " client_session_id, subject_scope, profile_id, profile_revision,"
                             + " state, aggregate_version, created_at, updated_at, metadata)"
                             + " VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, CAST('{}' AS JSON))")) {
            JdbcValues.setText(statement, 1, MYSQL, meetingId);
            JdbcValues.setText(statement, 2, MYSQL, "hb-deployment");
            JdbcValues.setText(statement, 3, MYSQL, "hb-session-" + meetingId);
            JdbcValues.setText(statement, 4, MYSQL, "hb-subject");
            statement.setString(5, "hb-profile");
            statement.setInt(6, 1);
            JdbcValues.setText(statement, 7, MYSQL, state);
            JdbcValues.setInstant(statement, 8, STARTED);
            JdbcValues.setInstant(statement, 9, STARTED);
            assertEquals(1, statement.executeUpdate());
        }
    }

    /** 落一条与生产同形状的弃场决定：状态仍是 RUNNING，只有决定行判定不可活跃。 */
    private void insertAbandonment(String meetingId) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO ai_trans_meeting_abandonment (meeting_id, decision_kind,"
                             + " deployment_scope, subject_scope, target_state, decided_at,"
                             + " started_at, last_active_at, settle_duration_ms,"
                             + " usage_reservation_id, settlement_state, settled_at)"
                             + " VALUES (?, 'ABANDONED', ?, ?, 'READY_WITH_WARNINGS', ?, ?, ?, 0,"
                             + " NULL, 'SETTLED', ?)")) {
            JdbcValues.setText(statement, 1, MYSQL, meetingId);
            JdbcValues.setText(statement, 2, MYSQL, "hb-deployment");
            JdbcValues.setText(statement, 3, MYSQL, "hb-subject");
            JdbcValues.setInstant(statement, 4, ACTIVE);
            JdbcValues.setInstant(statement, 5, STARTED);
            JdbcValues.setInstant(statement, 6, STARTED);
            JdbcValues.setInstant(statement, 7, ACTIVE);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void assertRow(String meetingId, Instant startedAt, Instant lastActiveAt,
            long heartbeatCount) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT meeting_id, started_at, last_active_at, heartbeat_count"
                             + " FROM ai_trans_meeting_heartbeat WHERE meeting_id = ?")) {
            JdbcValues.setText(statement, 1, MYSQL, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(meetingId, JdbcValues.text(rows, "meeting_id"));
                assertEquals(startedAt, JdbcValues.instant(rows, "started_at"));
                assertEquals(lastActiveAt, JdbcValues.instant(rows, "last_active_at"));
                assertEquals(heartbeatCount, rows.getLong("heartbeat_count"));
                assertFalse(rows.next());
            }
        }
    }

    private void assertNoRow(String meetingId) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT heartbeat_count FROM ai_trans_meeting_heartbeat WHERE meeting_id = ?")) {
            JdbcValues.setText(statement, 1, MYSQL, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertFalse(rows.next());
            }
        }
    }

    private Connection connection() throws Exception {
        return database.dataSource().getConnection();
    }

    private static Connection admin() throws Exception {
        return DriverManager.getConnection(required("REFERENCE_TEST_MYSQL_DATABASE_URL"),
                user(), password());
    }

    private static String user() {
        return required("REFERENCE_TEST_MYSQL_DATABASE_USER");
    }

    private static String password() {
        return required("REFERENCE_TEST_MYSQL_DATABASE_PASSWORD");
    }

    private static String required(String name) {
        return Objects.requireNonNull(System.getenv(name), name + " required");
    }

    private String scopedUrl() {
        String url = required("REFERENCE_TEST_MYSQL_DATABASE_URL");
        int query = url.indexOf('?');
        String base = query < 0 ? url : url.substring(0, query);
        return base.substring(0, base.lastIndexOf('/') + 1) + databaseName;
    }
}
