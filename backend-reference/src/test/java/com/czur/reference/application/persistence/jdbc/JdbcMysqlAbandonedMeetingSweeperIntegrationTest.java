package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.entitlement.FixedEntitlementPolicy;
import com.czur.reference.application.entitlement.FixedEntitlementPolicyConfig;
import com.czur.reference.application.port.AbandonedMeetingFinalizer;
import com.czur.reference.application.port.FinalizationReceiptGenerator;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.CancelReservationRequest;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.SettleAbandonedRequest;
import com.czur.reference.application.port.entitlement.UsageActual;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.identity.Subject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弃场扫描与弃场结算的 MySQL 5.7 实测。
 *
 * <p>覆盖 A 类"零用量结算后取消"、结算失败保留原状态可重试、B 类按时长结算并
 * 收成 READY_WITH_WARNINGS、Stop 用量优先以及幂等重放。每例创建独立随机库并
 * 执行正式 V001；没有 {@code REFERENCE_TEST_MYSQL_DATABASE_URL} 时整类跳过。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_MYSQL_DATABASE_URL", matches = ".+")
class JdbcMysqlAbandonedMeetingSweeperIntegrationTest {
    private static final JdbcDatabaseEngine MYSQL = JdbcDatabaseEngine.MYSQL;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEPLOYMENT = "abandon-deployment";
    private static final String SUBJECT = "abandon-subject";
    private static final String PROFILE = "standard";
    private static final Set<String> CAPABILITIES = Set.of("transcript.realtime");
    private static final UsageEstimate ESTIMATE =
            new UsageEstimate(UsageUnit.MEETING_DURATION_MS, Duration.ofMinutes(60).toMillis());
    private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant SWEEP_NOW = T0.plus(Duration.ofMinutes(30));
    private static final Duration NO_HEARTBEAT_WINDOW = Duration.ofMinutes(15);
    private static final Duration HEARTBEAT_IDLE_WINDOW = Duration.ofMinutes(10);
    private static final long MAX_MEETING_DURATION_MS = Duration.ofHours(5).toMillis();

    private String databaseName;
    private JdbcDatabaseContext database;
    private final AtomicInteger receipts = new AtomicInteger();

    @BeforeEach
    void initializeOnlyNewTaskDatabase() throws Exception {
        databaseName = "ai_trans_sweep_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        Path script = List.of(Path.of("..", "deploy", "migrate-mysql.sh"),
                        Path.of("deploy", "migrate-mysql.sh")).stream()
                .filter(Files::isRegularFile).findFirst().orElseThrow().toAbsolutePath();
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
    }

    @AfterEach
    void dropOnlyOwnDatabase() throws Exception {
        if (database != null) database.close();
        if (databaseName != null) {
            assertTrue(databaseName.matches("ai_trans_sweep_[0-9a-f]{32}"));
            try (Connection connection = admin(); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + databaseName);
            }
        }
    }

    @Test
    void neverStartedMeetingIsZeroSettledThenCancelled() throws Exception {
        seedOwnedMeeting("mtg_never_started", "STARTING", T0);
        JdbcAbandonedMeetingSweeper sweeper = sweeper(providerAt(SWEEP_NOW, "sweep"),
                finalizerAt(SWEEP_NOW), MAX_MEETING_DURATION_MS);

        JdbcAbandonedMeetingSweeper.Result first = sweeper.runOnce();

        assertEquals(1, first.cancelledMeetings());
        assertEquals(0, first.finalizedMeetings());
        assertEquals("CANCELLED", meetingState("mtg_never_started"));
        assertSettled("mtg_never_started", 0);
        assertEquals(1, abandonOperationCount("mtg_never_started"));
        // 已取消的会议不再命中；重复扫描不追加结算事实。
        JdbcAbandonedMeetingSweeper.Result second = sweeper.runOnce();
        assertEquals(0, second.cancelledMeetings());
        assertEquals(1, abandonOperationCount("mtg_never_started"));
    }

    @Test
    void settlementFailureKeepsMeetingRetryable() throws Exception {
        seedOwnedMeeting("mtg_settle_fails", "STARTING", T0);
        EntitlementProvider flaky = new FlakySettlementProvider(providerAt(SWEEP_NOW, "sweep"));
        JdbcAbandonedMeetingSweeper sweeper = sweeper(flaky, finalizerAt(SWEEP_NOW),
                MAX_MEETING_DURATION_MS);

        JdbcAbandonedMeetingSweeper.Result first = sweeper.runOnce();

        assertEquals(1, first.deferredMeetings());
        assertEquals("STARTING", meetingState("mtg_settle_fails"));
        assertReservationState("mtg_settle_fails", "RESERVED", null);
        assertEquals(0, abandonOperationCount("mtg_settle_fails"));
        // 下一轮结算恢复后必须能继续处理，说明失败没有被错误吞成终态。
        JdbcAbandonedMeetingSweeper.Result second = sweeper.runOnce();
        assertEquals(1, second.cancelledMeetings());
        assertEquals("CANCELLED", meetingState("mtg_settle_fails"));
        assertSettled("mtg_settle_fails", 0);
    }

    @Test
    void idleHeartbeatMeetingIsSettledByDurationAndFinalizedWithWarnings() throws Exception {
        seedOwnedMeeting("mtg_idle", "RUNNING", T0);
        Instant heartbeatStarted = T0.plus(Duration.ofMinutes(1));
        Instant lastActive = T0.plus(Duration.ofMinutes(13));
        insertHeartbeat("mtg_idle", heartbeatStarted, lastActive);
        JdbcAbandonedMeetingSweeper sweeper = sweeper(providerAt(SWEEP_NOW, "sweep"),
                finalizerAt(SWEEP_NOW), MAX_MEETING_DURATION_MS);

        JdbcAbandonedMeetingSweeper.Result result = sweeper.runOnce();

        assertEquals(1, result.finalizedMeetings());
        assertEquals(0, result.cancelledMeetings());
        long expectedDurationMs = Duration.ofMinutes(12).toMillis();
        assertEquals("READY_WITH_WARNINGS", meetingState("mtg_idle"));
        assertSettled("mtg_idle", expectedDurationMs);
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT input_state, manifest_state, finalization_receipt_id"
                             + " FROM ai_trans_meeting WHERE meeting_id=?")) {
            JdbcValues.setText(statement, 1, MYSQL, "mtg_idle");
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("INPUT_ACCEPTED", JdbcValues.text(rows, "input_state"));
                assertEquals("READY_WITH_WARNINGS", JdbcValues.text(rows, "manifest_state"));
                assertNotNull(JdbcValues.text(rows, "finalization_receipt_id"));
            }
        }
        // 客户端没有 startClient 时由心跳补齐输入起点；结果行对 client_input 有外键。
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT i.started_at FROM ai_trans_meeting_client_input i"
                             + " WHERE i.meeting_id=?")) {
            JdbcValues.setText(statement, 1, MYSQL, "mtg_idle");
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(heartbeatStarted,
                        JdbcValues.instant(rows, "started_at"));
            }
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT artifact_id, manifest_json FROM ai_trans_meeting_client_result"
                             + " WHERE meeting_id=?")) {
            JdbcValues.setText(statement, 1, MYSQL, "mtg_idle");
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertNull(rows.getBytes("artifact_id"));
                JsonNode manifest = JSON.readTree(rows.getString("manifest_json"));
                assertEquals("READY_WITH_WARNINGS", manifest.path("status").asText());
                assertEquals(expectedDurationMs, manifest.path("durationMs").asLong());
                assertEquals(heartbeatStarted.toString(), manifest.path("startedAt").asText());
                assertTrue(manifest.path("artifacts").isEmpty());
            }
        }
        // 已收口的会议不再结算；重跑扫描不追加操作事实。
        JdbcAbandonedMeetingSweeper.Result again = sweeper.runOnce();
        assertEquals(0, again.finalizedMeetings());
        assertEquals(1, abandonOperationCount("mtg_idle"));
    }

    @Test
    void heartbeatDurationIsCappedByDeploymentMaximum() throws Exception {
        seedOwnedMeeting("mtg_capped", "STARTING", T0);
        Instant heartbeatStarted = T0.plus(Duration.ofMinutes(1));
        Instant lastActive = T0.plus(Duration.ofMinutes(19));
        insertHeartbeat("mtg_capped", heartbeatStarted, lastActive);
        long capMs = Duration.ofMinutes(10).toMillis();
        JdbcAbandonedMeetingSweeper sweeper = sweeper(providerAt(SWEEP_NOW, "sweep"), finalizerAt(SWEEP_NOW),
                capMs);

        assertEquals(1, sweeper.runOnce().finalizedMeetings());

        assertSettled("mtg_capped", capMs);
        assertEquals("READY_WITH_WARNINGS", meetingState("mtg_capped"));
    }

    @Test
    void stopUsageTakesPriorityOverHeartbeatSettlement() throws Exception {
        seedOwnedMeeting("mtg_stopped_usage", "RUNNING", T0);
        insertHeartbeat("mtg_stopped_usage", T0.plus(Duration.ofMinutes(1)),
                T0.plus(Duration.ofMinutes(2)));
        insertClientUsage("mtg_stopped_usage", 12345L, 1L);
        JdbcAbandonedMeetingSweeper sweeper = sweeper(providerAt(SWEEP_NOW, "sweep"),
                finalizerAt(SWEEP_NOW), MAX_MEETING_DURATION_MS);

        JdbcAbandonedMeetingSweeper.Result result = sweeper.runOnce();

        assertEquals(0, result.finalizedMeetings());
        assertEquals("RUNNING", meetingState("mtg_stopped_usage"));
        assertReservationState("mtg_stopped_usage", "RESERVED", null);
        assertEquals(0, abandonOperationCount("mtg_stopped_usage"));
    }

    @Test
    void settleAbandonedReplaysAndTreatsCommittedAsReplay() throws Exception {
        seedOwnedMeeting("mtg_replay", "STARTING", T0);
        EntitlementProvider provider = providerAt(SWEEP_NOW, "sweep");
        SettleAbandonedRequest request = new SettleAbandonedRequest(
                DEPLOYMENT, SUBJECT, reservationId("mtg_replay"), "mtg_replay",
                new UsageActual(UsageUnit.MEETING_DURATION_MS, 600_000));

        UsageOperationResult first = provider.settleAbandoned(request).valueOrThrow();
        UsageOperationResult replay = provider.settleAbandoned(request).valueOrThrow();

        assertEquals(first, replay);
        assertEquals(UsageReservation.State.COMMITTED, replay.reservation().state());
        assertEquals(600_000, replay.reservation().actualUsage().amount());
        assertEquals(2, replay.reservation().version());
        assertEquals(1, abandonOperationCount("mtg_replay"));
        // 重算摘要不同但预占已 COMMITTED：按重放成功返回，不得二次扣减。
        UsageOperationResult differentAmount = provider.settleAbandoned(
                new SettleAbandonedRequest(DEPLOYMENT, SUBJECT, reservationId("mtg_replay"),
                        "mtg_replay", new UsageActual(UsageUnit.MEETING_DURATION_MS, 900_000)))
                .valueOrThrow();
        assertEquals(600_000, differentAmount.reservation().actualUsage().amount());
        assertEquals(1, abandonOperationCount("mtg_replay"));
    }

    @Test
    void settleAbandonedAcceptsReservationAlreadyCommittedByNormalStop() throws Exception {
        seedOwnedMeeting("mtg_stop_committed", "STOPPING", T0);
        EntitlementProvider provider = providerAt(SWEEP_NOW, "sweep");
        CommitUsageRequest stopCommit = new CommitUsageRequest(
                DEPLOYMENT, SUBJECT, UsageOperationScopes.COMMIT, "client-usage-stop-key",
                "stop-digest", reservationId("mtg_stop_committed"), 1,
                new UsageActual(UsageUnit.MEETING_DURATION_MS, 4_000));
        provider.commitUsage(stopCommit).valueOrThrow();

        UsageOperationResult abandoned = provider.settleAbandoned(new SettleAbandonedRequest(
                DEPLOYMENT, SUBJECT, reservationId("mtg_stop_committed"),
                "mtg_stop_committed",
                new UsageActual(UsageUnit.MEETING_DURATION_MS, 0))).valueOrThrow();

        assertEquals(4_000, abandoned.reservation().actualUsage().amount());
        // 预占自己的 reserve 操作 + 正常 Stop 的 commit 操作；弃场键不能再追加一条。
        assertEquals(2, operationCount("mtg_stop_committed"));
        assertEquals(0, abandonOperationCount("mtg_stop_committed"));
    }

    private JdbcAbandonedMeetingSweeper sweeper(EntitlementProvider provider,
            AbandonedMeetingFinalizer finalizer, long maxMeetingDurationMs) {
        Clock clock = Clock.fixed(SWEEP_NOW, ZoneOffset.UTC);
        return new JdbcAbandonedMeetingSweeper(database, DEPLOYMENT, clock, provider, finalizer,
                new JdbcAbandonedMeetingSweeper.Config(NO_HEARTBEAT_WINDOW,
                        HEARTBEAT_IDLE_WINDOW, maxMeetingDurationMs, 50));
    }

    private AbandonedMeetingFinalizer finalizerAt(Instant now) {
        FinalizationReceiptGenerator generator =
                () -> "receipt-abandon-" + receipts.incrementAndGet();
        return new JdbcClientMeetingResultFinalizer(database, generator,
                Clock.fixed(now, ZoneOffset.UTC), null, providerAt(now, "finalizer"));
    }

    private EntitlementProvider providerAt(Instant now, String token) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        EntitlementIdGenerator ids = new EntitlementIdGenerator() {
            @Override public String newGrantId() { return "grant_" + token; }
            @Override public String newReservationId() { return reservationId(token); }
            @Override public String newLeaseToken() { return "lease_" + token; }
        };
        FixedEntitlementPolicy policy = new FixedEntitlementPolicy(
                new FixedEntitlementPolicyConfig(DEPLOYMENT, PROFILE, 1, CAPABILITIES,
                        MAX_MEETING_DURATION_MS, Duration.ofMinutes(5), Duration.ofMinutes(3)),
                clock, ids);
        return new JdbcEntitlementProvider(database, policy, clock);
    }

    /**
     * 直接落一场 owned 会议及其已绑定预占。会议行只插入扫描需要的稳定事实，
     * 不伪造 Start 执行记录或客户端事件。
     */
    private void seedOwnedMeeting(String meetingId, String state, Instant createdAt)
            throws Exception {
        Instant reservedAt = createdAt.minusSeconds(5);
        EntitlementProvider provider = providerAt(reservedAt, meetingId);
        MeetingGrant grant = provider.authorizeStart(new AuthorizeStartRequest(
                DEPLOYMENT, new Subject(SUBJECT), PROFILE, 1, CAPABILITIES, ESTIMATE,
                "session-" + meetingId)).valueOrThrow();
        UsageReservation reserved = provider.reserveUsage(new ReserveUsageRequest(
                DEPLOYMENT, SUBJECT, UsageOperationScopes.RESERVE,
                "reserve-key-" + meetingId, "reserve-digest-" + meetingId,
                grant, ESTIMATE, meetingId)).valueOrThrow().reservation();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                // reserveUsage 已把版本 0 的预占写入同库；bound_meeting_id 有即时外键，
                // 必须先插入会议行，再按正式 Create 的顺序做 owned 绑定。
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ai_trans_meeting (
                            meeting_id, deployment_scope, client_session_id, subject_scope,
                            profile_id, profile_revision, state, aggregate_version,
                            created_at, updated_at, metadata, grant_id, grant_expires_at,
                            usage_reservation_id, input_state, manifest_state)
                        VALUES (?, ?, ?, ?, ?, 1, ?, 0, ?, ?, CAST('{}' AS JSON), ?, ?, ?,
                            'INPUT_OPEN', 'NOT_STARTED')
                        """)) {
                    JdbcValues.setText(statement, 1, MYSQL, meetingId);
                    JdbcValues.setText(statement, 2, MYSQL, DEPLOYMENT);
                    JdbcValues.setText(statement, 3, MYSQL, "session-" + meetingId);
                    JdbcValues.setText(statement, 4, MYSQL, SUBJECT);
                    statement.setString(5, PROFILE);
                    statement.setString(6, state);
                    JdbcValues.setInstant(statement, 7, createdAt);
                    JdbcValues.setInstant(statement, 8, createdAt);
                    JdbcValues.setText(statement, 9, MYSQL, grant.grantId());
                    JdbcValues.setInstant(statement, 10, grant.expiresAt());
                    JdbcValues.setText(statement, 11, MYSQL, reserved.reservationId());
                    assertEquals(1, statement.executeUpdate());
                }
                assertTrue(JdbcUsageReservationStore.bindForOwnedMeeting(
                        connection, MYSQL, reserved, createdAt));
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void insertHeartbeat(String meetingId, Instant startedAt, Instant lastActiveAt)
            throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO ai_trans_meeting_heartbeat"
                             + " (meeting_id, started_at, last_active_at, heartbeat_count)"
                             + " VALUES (?, ?, ?, 1)")) {
            JdbcValues.setText(statement, 1, MYSQL, meetingId);
            JdbcValues.setInstant(statement, 2, startedAt);
            JdbcValues.setInstant(statement, 3, lastActiveAt);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void insertClientUsage(String meetingId, long audioDurationMs, long expectedVersion)
            throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO ai_trans_meeting_client_input"
                             + " (meeting_id, started_at, audio_duration_ms,"
                             + " usage_submitted_at, usage_expected_version)"
                             + " VALUES (?, ?, ?, ?, ?)")) {
            JdbcValues.setText(statement, 1, MYSQL, meetingId);
            JdbcValues.setInstant(statement, 2, T0.plus(Duration.ofMinutes(1)));
            statement.setLong(3, audioDurationMs);
            JdbcValues.setInstant(statement, 4, T0.plus(Duration.ofMinutes(2)));
            statement.setLong(5, expectedVersion);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private String meetingState(String meetingId) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT state FROM ai_trans_meeting WHERE meeting_id=?")) {
            JdbcValues.setText(statement, 1, MYSQL, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return JdbcValues.text(rows, "state");
            }
        }
    }

    private void assertSettled(String meetingId, long expectedDurationMs) throws Exception {
        assertReservationState(meetingId, "COMMITTED", BigDecimal.valueOf(expectedDurationMs));
    }

    private void assertReservationState(String meetingId, String state, BigDecimal actual)
            throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT status, actual_amount, bound_meeting_id, version"
                             + " FROM ai_trans_usage_reservation WHERE reservation_id=?")) {
            JdbcValues.setText(statement, 1, MYSQL, reservationId(meetingId));
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(state, JdbcValues.text(rows, "status"));
                if (actual == null) {
                    assertNull(rows.getBigDecimal("actual_amount"));
                } else {
                    assertEquals(0, actual.compareTo(rows.getBigDecimal("actual_amount")));
                }
                assertEquals(meetingId, JdbcValues.text(rows, "bound_meeting_id"));
            }
        }
    }

    private int abandonOperationCount(String meetingId) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM ai_trans_usage_operation WHERE idempotency_key=?")) {
            JdbcValues.setText(statement, 1, MYSQL,
                    "meeting.abandon.v1:" + meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private int operationCount(String meetingId) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM ai_trans_usage_operation WHERE reservation_id=?")) {
            JdbcValues.setText(statement, 1, MYSQL, reservationId(meetingId));
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static String reservationId(String meetingId) {
        return "reservation_" + meetingId;
    }

    private Connection connection() throws Exception {
        return database.dataSource().getConnection();
    }

    private Connection admin() throws Exception {
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
        String suffix = query < 0 ? "" : url.substring(query);
        String base = query < 0 ? url : url.substring(0, query);
        return base.substring(0, base.lastIndexOf('/') + 1) + databaseName + suffix;
    }

    /** 首次弃场结算固定失败，验证失败不取消且下一轮能重试。 */
    private static final class FlakySettlementProvider implements EntitlementProvider {
        private final EntitlementProvider delegate;
        private boolean failNext = true;

        private FlakySettlementProvider(EntitlementProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public EntitlementProviderResult<MeetingGrant> authorizeStart(
                AuthorizeStartRequest request) {
            return delegate.authorizeStart(request);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> reserveUsage(
                ReserveUsageRequest request) {
            return delegate.reserveUsage(request);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> commitUsage(
                CommitUsageRequest request) {
            return delegate.commitUsage(request);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> settleAbandoned(
                SettleAbandonedRequest request) {
            if (failNext) {
                failNext = false;
                return EntitlementProviderResult.failure(new EntitlementProviderError(
                        EntitlementProviderError.Code.PROVIDER_UNAVAILABLE, true));
            }
            return delegate.settleAbandoned(request);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> cancelReservation(
                CancelReservationRequest request) {
            return delegate.cancelReservation(request);
        }
    }
}
