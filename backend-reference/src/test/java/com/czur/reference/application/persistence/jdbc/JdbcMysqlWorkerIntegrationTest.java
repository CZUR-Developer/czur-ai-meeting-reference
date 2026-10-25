package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.port.IdempotencyStore;
import com.czur.reference.application.port.OutboxStore;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.ExpiredReservationStore;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MySQL 5.7 Outbox Worker 与到期 Reservation 清理的真实数据库门禁。
 *
 * <p>每例新建随机 ai_trans_mysql_worker_ 库并执行唯一 V001，只删除本例库。
 * 不验证真实模型、HTTP 或设备；只证明 MySQL SQL、时间精度和 lease 条件更新
 * 可以在真实 InnoDB 上完成领取、续租、发布、失败回队和 lease-aware cancel。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_MYSQL_DATABASE_URL", matches = ".+")
class JdbcMysqlWorkerIntegrationTest {
    private static final JdbcDatabaseEngine MYSQL = JdbcDatabaseEngine.MYSQL;
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00.123456Z");
    private static final String DEPLOYMENT = "mysql-worker-deployment";
    private static final String SUBJECT = "mysql-worker-subject";
    private static final String RESERVATION = "reservation-worker-1";
    private static final String MEETING = "mtg-worker-1";

    private String databaseName;
    private JdbcDatabaseContext database;

    @BeforeEach
    void createOnlyOwnDatabase() throws Exception {
        databaseName = "ai_trans_mysql_worker_"
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
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "独立库初始化超时");
        assertEquals(0, process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        database = JdbcDatabaseContext.start(DatabaseRuntimeConfig.of(
                scopedUrl(), user(), password(),
                Duration.ofSeconds(5), Duration.ofSeconds(2),
                Duration.ofSeconds(20), 8, 0));
    }

    @AfterEach
    void dropOnlyOwnDatabase() throws Exception {
        if (database != null) {
            database.close();
        }
        if (databaseName != null) {
            assertTrue(databaseName.matches("ai_trans_mysql_worker_[0-9a-f]{32}"));
            try (Connection connection = admin(); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + databaseName);
            }
        }
    }

    @Test
    void outboxClaimRenewPublishRetryAndDeathUseMysqlConditions() {
        JdbcOutboxStore store = new JdbcOutboxStore(database);
        String eventType = "worker.test";
        OutboxStore.OutboxEvent first = OutboxStore.OutboxEvent.pending(
                "event-1", "meeting", MEETING, eventType, "{\"n\":1}",
                NOW.minusSeconds(1), NOW.minusSeconds(1));
        assertEquals(OutboxStore.AppendStatus.APPENDED, store.append(first).status());

        List<OutboxStore.ClaimedEvent> claimed = store.claim(
                new OutboxStore.ClaimRequest(Set.of(eventType), "owner-1", NOW,
                        Duration.ofSeconds(30), 10));
        assertEquals(1, claimed.size());
        OutboxStore.LeaseToken lease = claimed.getFirst().lease();
        assertEquals(1L, lease.leaseVersion());
        assertEquals(OutboxStore.EventStatus.LEASED, claimed.getFirst().event().status());

        OutboxStore.RenewResult renewed = store.renew(
                lease, NOW.plusSeconds(5), Duration.ofSeconds(30));
        assertEquals(OutboxStore.RenewStatus.RENEWED, renewed.status());
        assertEquals(2L, renewed.lease().leaseVersion());
        assertEquals(NOW.plusSeconds(35).truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                renewed.lease().expiresAt());

        OutboxStore.PublishResult published = store.markPublished(
                renewed.lease(), NOW.plusSeconds(10));
        assertEquals(OutboxStore.PublishStatus.PUBLISHED, published.status());
        assertEquals(OutboxStore.EventStatus.PUBLISHED, published.event().status());

        OutboxStore.OutboxEvent second = OutboxStore.OutboxEvent.pending(
                "event-2", "meeting", MEETING, eventType, "{\"n\":2}",
                NOW.minusSeconds(1), NOW.minusSeconds(1));
        store.append(second);
        OutboxStore.LeaseToken secondLease = store.claim(
                        new OutboxStore.ClaimRequest(Set.of(eventType), "owner-2", NOW,
                                Duration.ofSeconds(30), 10))
                .getFirst().lease();
        OutboxStore.FailureResult retry = store.markFailed(
                secondLease, NOW.plusSeconds(1), NOW.plusSeconds(2), "temporary", 2);
        assertEquals(OutboxStore.FailureStatus.RETRY_SCHEDULED, retry.status());
        assertEquals(OutboxStore.EventStatus.PENDING, retry.event().status());

        OutboxStore.LeaseToken retryLease = store.claim(
                        new OutboxStore.ClaimRequest(Set.of(eventType), "owner-3",
                                NOW.plusSeconds(3), Duration.ofSeconds(30), 10))
                .getFirst().lease();
        OutboxStore.FailureResult dead = store.markFailed(
                retryLease, NOW.plusSeconds(4), NOW.plusSeconds(5), "permanent", 2);
        assertEquals(OutboxStore.FailureStatus.DEAD, dead.status());
        assertEquals(OutboxStore.EventStatus.DEAD, dead.event().status());
    }

    @Test
    void expiredReservationClaimReleaseAndLeaseAwareCancelUseMysqlConditions() throws Exception {
        insertExpiredReservation(RESERVATION);
        JdbcExpiredReservationStore store = new JdbcExpiredReservationStore(
                database.dataSource(), ids(), MYSQL);

        ExpiredReservationStore.ClaimBatch batch = store.claimExpired(
                new ExpiredReservationStore.ClaimRequest(
                        DEPLOYMENT, "cleanup-owner", NOW, Duration.ofSeconds(30), 10));
        assertEquals(1, batch.claims().size());
        ExpiredReservationStore.ClaimedReservation claim = batch.claims().getFirst();
        assertEquals(1L, claim.lease().leaseVersion());

        ExpiredReservationStore.ReleaseResult released = store.release(
                claim.lease(), NOW.plusSeconds(1));
        assertEquals(ExpiredReservationStore.ReleaseStatus.RELEASED, released.status());

        // release 后第一行仍是到期未绑定；测试里直接终止它，避免第二次 claim 同时取到两行。
        try (Connection connection = database.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE ai_trans_usage_reservation SET status = 'CANCELLED'"
                             + " WHERE reservation_id = ?")) {
            JdbcValues.setText(statement, 1, MYSQL, RESERVATION);
            assertEquals(1, statement.executeUpdate());
        }

        // 第二次 claim 后用同一行验证 lease-aware cancel 的 MySQL 条件更新。
        insertExpiredReservation("reservation-worker-2");
        store = new JdbcExpiredReservationStore(database.dataSource(), ids(), MYSQL);
        ExpiredReservationStore.ClaimBatch second = store.claimExpired(
                new ExpiredReservationStore.ClaimRequest(
                        DEPLOYMENT, "cleanup-owner-2", NOW, Duration.ofSeconds(30), 10));
        assertEquals(1, second.claims().size());
        ExpiredReservationStore.ClaimedReservation cancelClaim = second.claims().getFirst();
        UsageReservation reserved = cancelClaim.reservation();
        UsageReservation cancelled = reserved.cancel(
                "EXPIRED_UNBOUND", NOW.plusSeconds(1), reserved.version());
        try (Connection connection = database.dataSource().getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                assertTrue(JdbcUsageReservationStore.cancelWithLease(
                        connection, MYSQL, reserved, cancelled, cancelClaim.lease(),
                        NOW.plusSeconds(1)));
                connection.commit();
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
        try (Connection connection = database.dataSource().getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT status FROM ai_trans_usage_reservation WHERE reservation_id = "
                             + "CAST('reservation-worker-2' AS BINARY)")) {
            assertTrue(rows.next());
            assertEquals("CANCELLED", JdbcValues.text(rows, "status"));
        }
    }

    @Test
    void legacyMysqlSaveAndIdempotencyReleaseDoNotThrow() {
        JdbcMeetingRepository repository = new JdbcMeetingRepository(database);
        MeetingAggregate.MeetingSnapshot legacy = MeetingAggregate.MeetingSnapshot.legacyUnowned(
                "mtg-legacy-1", DEPLOYMENT, "client-legacy", "profile-legacy", 1,
                MeetingState.CREATED, NOW, NOW);
        repository.save(legacy);
        assertEquals(MeetingState.CREATED,
                repository.find("mtg-legacy-1").orElseThrow().state());

        MeetingAggregate.MeetingSnapshot running = new MeetingAggregate.MeetingSnapshot(
                legacy.meetingId(), legacy.deploymentScope(), legacy.clientSessionId(),
                legacy.subjectScope(), legacy.grantId(), legacy.grantExpiresAt(),
                legacy.usageReservationId(), legacy.profileId(), legacy.profileRevision(),
                MeetingState.RUNNING, legacy.createdAt(), NOW.plusSeconds(5));
        repository.save(running);
        assertEquals(MeetingState.RUNNING,
                repository.find("mtg-legacy-1").orElseThrow().state());

        JdbcIdempotencyStore idempotency = new JdbcIdempotencyStore(database);
        IdempotencyStore.IdempotencyRecord record = new IdempotencyStore.IdempotencyRecord(
                DEPLOYMENT, "idem-legacy", "digest-legacy",
                new MeetingCommandResult(MeetingCommandResult.Operation.CREATED, legacy));
        idempotency.save(record);
        assertTrue(idempotency.find(DEPLOYMENT, "idem-legacy").isPresent());
        idempotency.release(record);
        assertTrue(idempotency.find(DEPLOYMENT, "idem-legacy").isEmpty());
    }

    private void insertExpiredReservation(String reservationId) throws Exception {
        Instant createdAt = NOW.minusSeconds(3600);
        Instant expiresAt = NOW.minusSeconds(1);
        try (Connection connection = database.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO ai_trans_usage_reservation ("
                             + "reservation_id, deployment_scope, subject_scope, grant_id,"
                             + " expected_meeting_id, usage_unit, reserved_amount, created_at,"
                             + " updated_at, record_format, expires_at, status, version, lease_version)"
                             + " VALUES (?, ?, ?, 'grant-worker', ?, 'MEETING_DURATION_MS', 1000,"
                             + " ?, ?, 'V3', ?, 'RESERVED', 0, 0)")) {
            JdbcValues.setText(statement, 1, MYSQL, reservationId);
            JdbcValues.setText(statement, 2, MYSQL, DEPLOYMENT);
            JdbcValues.setText(statement, 3, MYSQL, SUBJECT);
            statement.setString(4, MEETING);
            JdbcValues.setInstant(statement, 5, createdAt);
            JdbcValues.setInstant(statement, 6, createdAt);
            JdbcValues.setInstant(statement, 7, expiresAt);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static EntitlementIdGenerator ids() {
        return new EntitlementIdGenerator() {
            private int sequence;

            @Override public String newGrantId() { return "grant-worker"; }
            @Override public String newReservationId() { return "reservation-worker"; }
            @Override public String newLeaseToken() { return "lease-token-" + (++sequence); }
        };
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
