package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.entitlement.FixedEntitlementPolicy;
import com.czur.reference.application.entitlement.FixedEntitlementPolicyConfig;
import com.czur.reference.application.port.ClientMeetingInputPort;
import com.czur.reference.application.port.FinalizationReceiptGenerator;
import com.czur.reference.application.port.OwnedMeetingStopUnitOfWork;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.SettleAbandonedRequest;
import com.czur.reference.application.port.entitlement.CancelReservationRequest;
import com.czur.reference.application.port.entitlement.UsageActual;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.application.port.model.CanonicalTranscriptEvent;
import com.czur.reference.application.realtime.RealtimeMeetingStartCoordinator;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.realtime.RealtimeTranscriptEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stop 受理后收尾失败、冻结用量后无 Stop、累计正文预算的 MySQL 5.7 实测。
 *
 * <p>每例新建自己的随机库并执行正式 V001；没有
 * {@code REFERENCE_TEST_MYSQL_DATABASE_URL} 时整类跳过。结算使用真实 JDBC
 * 权益账本，产物使用本机文件替身，不请求任何厂商。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_MYSQL_DATABASE_URL", matches = ".+")
class JdbcMysqlFinalizationRecoveryIntegrationTest {
    private static final JdbcDatabaseEngine MYSQL = JdbcDatabaseEngine.MYSQL;
    private static final String DEPLOYMENT = "recovery-deployment";
    private static final String SUBJECT = "recovery-subject";
    private static final String PROFILE = "standard";
    private static final Set<String> CAPABILITIES = Set.of("transcript.realtime");
    private static final UsageEstimate ESTIMATE =
            new UsageEstimate(UsageUnit.MEETING_DURATION_MS, Duration.ofMinutes(60).toMillis());
    private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant NOW = T0.plus(Duration.ofMinutes(30));
    private static final long MAX_MEETING_DURATION_MS = Duration.ofHours(5).toMillis();

    private String databaseName;
    private JdbcDatabaseContext database;
    private final AtomicInteger receipts = new AtomicInteger();
    @TempDir Path artifactRoot;

    @BeforeEach
    void initializeOnlyNewTaskDatabase() throws Exception {
        databaseName = "ai_trans_recovery_" + UUID.randomUUID().toString().replace("-", "");
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
            assertTrue(databaseName.matches("ai_trans_recovery_[0-9a-f]{32}"));
            try (Connection connection = admin(); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + databaseName);
            }
        }
    }

    /**
     * 故障注入：第一次收口在结算端口失败，会议停在 STOPPING 且保留待办；
     * 后台扫描重试后 READY，客户端用量只结算一次，重复扫描不重复发布。
     */
    @Test
    void stopAcceptedFinalizerFailureIsRetriedAndSettledOnce() throws Exception {
        seedClientMeeting("mtg_retry", "STARTING", 2_500L, T0.plus(Duration.ofMinutes(1)));
        insertTranscriptEvent("mtg_retry", 1, "seg-1", "正常停止后的字幕");
        Instant stoppedAt = T0.plus(Duration.ofMinutes(2));
        stopMeeting("mtg_retry", stoppedAt);

        assertEquals("STOPPING", meetingState("mtg_retry"));
        assertEquals(1, pendingCount("mtg_retry"));
        assertPendingReason("mtg_retry", "STOP_ACCEPTED");

        ArtifactStore store = new Persist(artifactRoot).store();
        EntitlementProvider flaky = new CommitFailingOnce(providerAt(stoppedAt));
        JdbcClientMeetingResultFinalizer finalizer = finalizer(stoppedAt, store, flaky);
        JdbcClientFinalizationPendingSweep sweep = new JdbcClientFinalizationPendingSweep(
                database, DEPLOYMENT, Clock.fixed(stoppedAt, ZoneOffset.UTC), finalizer,
                new JdbcClientFinalizationPendingSweep.Config(Duration.ZERO, 50));

        JdbcClientFinalizationPendingSweep.Result first = sweep.runOnce();

        assertEquals(1, first.deferredMeetings());
        assertEquals(0, first.finalizedMeetings());
        assertEquals("STOPPING", meetingState("mtg_retry"));
        assertEquals(1, pendingCount("mtg_retry"));
        assertEquals(0, clientUsageOperationCount("mtg_retry"));
        assertEquals(0, clientResultCount("mtg_retry"));

        JdbcClientFinalizationPendingSweep.Result second = sweep.runOnce();

        assertEquals(1, second.finalizedMeetings());
        assertEquals(0, second.deferredMeetings());
        assertEquals("READY", meetingState("mtg_retry"));
        assertEquals(0, pendingCount("mtg_retry"));
        assertEquals(1, clientUsageOperationCount("mtg_retry"));
        assertSettled("mtg_retry", 2_500L);
        assertEquals(1, clientResultCount("mtg_retry"));
        assertEquals(1, artifactCount("mtg_retry"));

        JdbcClientFinalizationPendingSweep.Result third = sweep.runOnce();
        assertEquals(0, third.finalizedMeetings());
        assertEquals(1, clientUsageOperationCount("mtg_retry"));
        assertEquals(1, clientResultCount("mtg_retry"));
        assertEquals(1, artifactCount("mtg_retry"));
    }

    /**
     * 冻结用量后不 Stop：弃场扫描必须让路；超时恢复用冻结值结算一次并收成
     * READY，重复扫描不再扣减或重复发布。
     */
    @Test
    void frozenUsageWithoutStopIsFinalizedWithFrozenAmount() throws Exception {
        seedClientMeeting("mtg_usage", "STARTING", 12_345L, T0.plus(Duration.ofMinutes(1)));
        insertTranscriptEvent("mtg_usage", 1, "seg-usage", "冻结用量后的字幕");

        CountingProvider provider = new CountingProvider(providerAt(NOW));
        JdbcClientMeetingResultFinalizer finalizer =
                finalizer(NOW, new Persist(artifactRoot).store(), provider);
        JdbcAbandonedMeetingSweeper abandon = new JdbcAbandonedMeetingSweeper(
                database, DEPLOYMENT, Clock.fixed(NOW, ZoneOffset.UTC), provider, finalizer,
                JdbcAbandonedMeetingSweeper.Config.defaults(60_000, MAX_MEETING_DURATION_MS));

        JdbcAbandonedMeetingSweeper.Result skipped = abandon.runOnce();
        assertEquals(0, skipped.cancelledMeetings());
        assertEquals(0, skipped.finalizedMeetings());
        assertEquals("STARTING", meetingState("mtg_usage"));
        assertEquals(0, abandonmentCount("mtg_usage"));

        JdbcFrozenUsageTimeoutSweeper recovery = new JdbcFrozenUsageTimeoutSweeper(
                database, DEPLOYMENT, Clock.fixed(NOW, ZoneOffset.UTC), finalizer,
                new JdbcFrozenUsageTimeoutSweeper.Config(Duration.ofMinutes(10), 50));

        JdbcFrozenUsageTimeoutSweeper.Result first = recovery.runOnce();

        assertEquals(1, first.finalizedMeetings());
        assertEquals(0, first.deferredMeetings());
        assertEquals("READY", meetingState("mtg_usage"));
        assertEquals(0, pendingCount("mtg_usage"));
        assertSettled("mtg_usage", 12_345L);
        assertEquals(1, provider.commitCalls);
        assertEquals(1, clientUsageOperationCount("mtg_usage"));
        assertEquals(1, clientResultCount("mtg_usage"));
        assertEquals(1, artifactCount("mtg_usage"));

        JdbcFrozenUsageTimeoutSweeper.Result second = recovery.runOnce();

        assertEquals(0, second.finalizedMeetings());
        assertEquals(1, provider.commitCalls);
        assertEquals(1, clientUsageOperationCount("mtg_usage"));
        assertEquals(1, clientResultCount("mtg_usage"));
        assertEquals(1, artifactCount("mtg_usage"));
    }

    /**
     * append 受理按最终序列化口径累计正文：恰好等于预算允许，超出 1 字节拒绝；
     * 被拒绝后会议仍能正常 Stop 到 READY，不会卡在 STOPPING。
     */
    @Test
    void cumulativeAppendBudgetRejectsOneByteOverAndMeetingStillStops() throws Exception {
        seedClientMeeting("mtg_budget_probe", "STARTING", null, null);
        seedClientMeeting("mtg_budget_fit", "STARTING", null, null);
        seedClientMeeting("mtg_budget_over", "STARTING", null, null);

        JdbcClientMeetingInputPort probeInput = inputPort(8L * 1024L * 1024L);
        append(probeInput, "mtg_budget_probe", transcript("probe-1", 1, "seg-a", 1,
                "甲".repeat(120), true));
        append(probeInput, "mtg_budget_probe", transcript("probe-2", 2, "seg-b", 1,
                "乙".repeat(240), true));
        long exactBudget = serializedBodyBytes("mtg_budget_probe");
        assertTrue(exactBudget > 0);

        JdbcClientMeetingInputPort fitInput = inputPort(exactBudget);
        assertTrue(append(fitInput, "mtg_budget_fit", transcript("fit-1", 1, "seg-a", 1,
                "甲".repeat(120), true)));
        assertTrue(append(fitInput, "mtg_budget_fit", transcript("fit-2", 2, "seg-b", 1,
                "乙".repeat(240), true)));
        assertEquals(exactBudget, serializedBodyBytes("mtg_budget_fit"));

        JdbcClientMeetingInputPort overInput = inputPort(exactBudget - 1L);
        assertTrue(append(overInput, "mtg_budget_over", transcript("over-1", 1, "seg-a", 1,
                "甲".repeat(120), true)));
        assertEquals(new ClientMeetingInputPort.Rejected(
                        ClientMeetingInputPort.Reason.CONTENT_TOO_LARGE),
                overInput.append(context(), "mtg_budget_over",
                        transcript("over-2", 2, "seg-b", 1, "乙".repeat(240), true)));
        assertEquals("STARTING", meetingState("mtg_budget_over"));

        // 恰好等于预算的会议正常发布 READY；超出 1 字节被拒的会议只保留已接受内容，
        // 仍能正常 Stop，不会因预算问题停在 STOPPING。
        assertEquals("READY", stopAndFinalize("mtg_budget_fit", exactBudget));
        assertEquals("READY", stopAndFinalize("mtg_budget_over", exactBudget - 1L));
    }

    private String stopAndFinalize(String meetingId, long budget) throws Exception {
        Instant stoppedAt = T0.plus(Duration.ofMinutes(5));
        stopMeeting(meetingId, stoppedAt);
        JdbcClientMeetingResultFinalizer finalizer = finalizer(stoppedAt,
                new Persist(artifactRoot).store(), providerAt(stoppedAt), budget);
        assertTrue(finalizer.finalizePendingClient(meetingId));
        return meetingState(meetingId);
    }

    private static ClientMeetingInputPort.ClientTranscript transcript(String clientId, long sequence,
            String segmentId, int revision, String text, boolean complete) {
        return new ClientMeetingInputPort.ClientTranscript(clientId, sequence,
                new CanonicalTranscriptEvent(segmentId, revision,
                        CanonicalTranscriptEvent.Operation.UPSERT, 0, 2_000,
                        "zh-CN", text, complete, null, 0, List.of(), List.of()));
    }

    private boolean append(JdbcClientMeetingInputPort input, String meetingId,
            ClientMeetingInputPort.ClientEvent event) {
        return assertInstanceOf(ClientMeetingInputPort.Appended.class,
                input.append(context(), meetingId, event)).created();
    }

    private long serializedBodyBytes(String meetingId) throws Exception {
        try (Connection connection = connection()) {
            return JdbcClientMeetingResultFinalizer.serializedClientBodyBytes(
                    connection, MYSQL, meetingId);
        }
    }

    private JdbcClientMeetingInputPort inputPort(long budget) {
        RealtimeMeetingStartCoordinator coordinator = new RealtimeMeetingStartCoordinator(
                DEPLOYMENT, new JdbcRealtimeStartFactsLookup(database),
                (profileId, revision) -> java.util.Optional.empty(),
                new CapabilityDescriptor(Set.of()), DeploymentLimits.NONE,
                Clock.fixed(T0, ZoneOffset.UTC),
                new JdbcMeetingStartExecutionUnitOfWork(database, new CapabilityPlanner()));
        return new JdbcClientMeetingInputPort(database, coordinator,
                Clock.fixed(T0, ZoneOffset.UTC), new Persist(artifactRoot).store(), budget);
    }

    private JdbcClientMeetingResultFinalizer finalizer(Instant now, ArtifactStore store,
            EntitlementProvider provider) {
        return finalizer(now, store, provider, 1024L * 1024L);
    }

    private JdbcClientMeetingResultFinalizer finalizer(Instant now, ArtifactStore store,
            EntitlementProvider provider, long budget) {
        FinalizationReceiptGenerator generator =
                () -> "receipt-recovery-" + receipts.incrementAndGet();
        return new JdbcClientMeetingResultFinalizer(database, generator,
                Clock.fixed(now, ZoneOffset.UTC), store, provider, null, budget);
    }

    private OwnedMeetingStopUnitOfWork.Success stopMeeting(String meetingId, Instant at) {
        JdbcOwnedMeetingStopUnitOfWork stop = new JdbcOwnedMeetingStopUnitOfWork(
                database, Clock.fixed(at, ZoneOffset.UTC));
        return assertInstanceOf(OwnedMeetingStopUnitOfWork.Success.class,
                stop.stop(new OwnedMeetingStopUnitOfWork.StopRequest(context(), meetingId,
                        "stop-key-" + meetingId, "stop-digest-" + meetingId)));
    }

    private static MeetingCommandContext context() {
        return new MeetingCommandContext(DEPLOYMENT, new VerifiedIdentity(
                new Subject(SUBJECT), Set.of("USER"),
                Set.of(MeetingPermissions.CREATE, MeetingPermissions.STOP),
                T0.minusSeconds(60), T0.plus(Duration.ofHours(6)), null));
    }

    /**
     * 只落一场 owned 客户端会议及其已绑定预占；client_input 行同时表示客户端事件链。
     * 冻结用量参数为空时模拟“已开始但未提交用量”的会议。
     */
    private void seedClientMeeting(String meetingId, String state, Long audioDurationMs,
            Instant usageSubmittedAt) throws Exception {
        Instant reservedAt = T0.minusSeconds(5);
        EntitlementProvider seedProvider = providerAt(reservedAt, meetingId);
        MeetingGrant grant = seedProvider.authorizeStart(new AuthorizeStartRequest(
                DEPLOYMENT, new Subject(SUBJECT), PROFILE, 1, CAPABILITIES, ESTIMATE,
                "session-" + meetingId)).valueOrThrow();
        UsageReservation reserved = seedProvider.reserveUsage(new ReserveUsageRequest(
                DEPLOYMENT, SUBJECT, UsageOperationScopes.RESERVE,
                "reserve-key-" + meetingId, "reserve-digest-" + meetingId,
                grant, ESTIMATE, meetingId)).valueOrThrow().reservation();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
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
                    JdbcValues.setText(statement, 6, MYSQL, state);
                    JdbcValues.setInstant(statement, 7, T0);
                    JdbcValues.setInstant(statement, 8, T0);
                    JdbcValues.setText(statement, 9, MYSQL, grant.grantId());
                    JdbcValues.setInstant(statement, 10, grant.expiresAt());
                    JdbcValues.setText(statement, 11, MYSQL, reserved.reservationId());
                    assertEquals(1, statement.executeUpdate());
                }
                assertTrue(JdbcUsageReservationStore.bindForOwnedMeeting(
                        connection, MYSQL, reserved, T0));
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO ai_trans_meeting_client_input"
                                + " (meeting_id, started_at, audio_duration_ms,"
                                + " usage_submitted_at, usage_expected_version)"
                                + " VALUES (?, ?, ?, ?, ?)")) {
                    JdbcValues.setText(statement, 1, MYSQL, meetingId);
                    JdbcValues.setInstant(statement, 2, T0.plus(Duration.ofMinutes(1)));
                    if (audioDurationMs == null) {
                        statement.setNull(3, java.sql.Types.BIGINT);
                    } else {
                        statement.setLong(3, audioDurationMs);
                    }
                    if (usageSubmittedAt == null) {
                        statement.setNull(4, java.sql.Types.TIMESTAMP);
                    } else {
                        JdbcValues.setInstant(statement, 4, usageSubmittedAt);
                    }
                    if (audioDurationMs == null) {
                        statement.setNull(5, java.sql.Types.BIGINT);
                    } else {
                        statement.setLong(5, 1L);
                    }
                    assertEquals(1, statement.executeUpdate());
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void insertTranscriptEvent(String meetingId, long clientSequence, String segmentId,
            String text) throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                long sequence = JdbcMeetingEventLogStore.currentSequence(
                        connection, MYSQL, meetingId) + 1;
                String eventId = "evt_" + meetingId + "_" + clientSequence;
                var event = RealtimeTranscriptEventMapper.event(meetingId, eventId, sequence,
                        T0.plus(Duration.ofMinutes(2)), new CanonicalTranscriptEvent(
                                segmentId, 1, CanonicalTranscriptEvent.Operation.UPSERT,
                                0, 2_000, "zh-CN", text, true, null, 0, List.of(), List.of()));
                assertEquals(com.czur.reference.application.port.EventLogStore.AppendStatus.APPENDED,
                        JdbcMeetingEventLogStore.append(connection, MYSQL, event).status());
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO ai_trans_meeting_client_event"
                                + " (meeting_id, client_event_id, client_sequence, event_id)"
                                + " VALUES (?, ?, ?, ?)")) {
                    JdbcValues.setText(statement, 1, MYSQL, meetingId);
                    JdbcValues.setText(statement, 2, MYSQL, eventId);
                    statement.setLong(3, clientSequence);
                    JdbcValues.setText(statement, 4, MYSQL, eventId);
                    assertEquals(1, statement.executeUpdate());
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void assertSettled(String meetingId, long expectedDurationMs) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT status, actual_amount FROM ai_trans_usage_reservation"
                             + " WHERE reservation_id=?")) {
            JdbcValues.setText(statement, 1, MYSQL, reservationId(meetingId));
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("COMMITTED", JdbcValues.text(rows, "status"));
                assertEquals(0, BigDecimal.valueOf(expectedDurationMs)
                        .compareTo(rows.getBigDecimal("actual_amount")));
            }
        }
    }

    private int pendingCount(String meetingId) throws Exception {
        return countWhere("ai_trans_meeting_finalization_pending", meetingId);
    }

    private int clientResultCount(String meetingId) throws Exception {
        return countWhere("ai_trans_meeting_client_result", meetingId);
    }

    private int artifactCount(String meetingId) throws Exception {
        return countWhere("ai_trans_artifact", meetingId);
    }

    private int abandonmentCount(String meetingId) throws Exception {
        return countWhere("ai_trans_meeting_abandonment", meetingId);
    }

    private int countWhere(String table, String meetingId) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE meeting_id=?")) {
            JdbcValues.setText(statement, 1, MYSQL, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private void assertPendingReason(String meetingId, String reason) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT pending_reason FROM ai_trans_meeting_finalization_pending"
                             + " WHERE meeting_id=?")) {
            JdbcValues.setText(statement, 1, MYSQL, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(reason, JdbcValues.text(rows, "pending_reason"));
            }
        }
    }

    private int clientUsageOperationCount(String meetingId) throws Exception {
        String key = "client-usage-" + sha256(meetingId);
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM ai_trans_usage_operation WHERE idempotency_key=?")) {
            JdbcValues.setText(statement, 1, MYSQL, key);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
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

    private EntitlementProvider providerAt(Instant now) {
        return providerAt(now, "recovery");
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

    private static String reservationId(String token) {
        return "reservation_" + token;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
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

    /** 每次新开一个本机文件 Store；测试只关心发布/重放，不共享进程内状态。 */
    private record Persist(Path root) {
        ArtifactStore store() {
            try {
                return new PersistentFileSystemMockArtifactStore(root, 8L * 1024L * 1024L);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    /** 首次 commitUsage 返回可重试失败，验证失败不会发布结果或重复结算。 */
    private static final class CommitFailingOnce implements EntitlementProvider {
        private final EntitlementProvider delegate;
        private boolean failNext = true;

        private CommitFailingOnce(EntitlementProvider delegate) {
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
            if (failNext) {
                failNext = false;
                return EntitlementProviderResult.failure(new EntitlementProviderError(
                        EntitlementProviderError.Code.PROVIDER_UNAVAILABLE, true));
            }
            return delegate.commitUsage(request);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> settleAbandoned(
                SettleAbandonedRequest request) {
            return delegate.settleAbandoned(request);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> cancelReservation(
                CancelReservationRequest request) {
            return delegate.cancelReservation(request);
        }
    }

    /** 统计正常 commitUsage 次数，证明冻结用量只结算一次。 */
    private static final class CountingProvider implements EntitlementProvider {
        private final EntitlementProvider delegate;
        private int commitCalls;

        private CountingProvider(EntitlementProvider delegate) {
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
            commitCalls++;
            return delegate.commitUsage(request);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> settleAbandoned(
                SettleAbandonedRequest request) {
            return delegate.settleAbandoned(request);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> cancelReservation(
                CancelReservationRequest request) {
            return delegate.cancelReservation(request);
        }
    }
}
