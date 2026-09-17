package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.port.IdempotencyStore;
import com.czur.reference.application.port.OutboxStore;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 PostgreSQL 门禁。
 *
 * <p>只有显式设置 {@code REFERENCE_TEST_DATABASE_URL} 才执行，默认 Maven 测试不会
 * 连接外部数据库。运行前需在目标数据库执行 V001/V002 迁移；该测试不负责修改
 * 共享数据库的 schema。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-16T04:00:00Z");
    private static DataSource dataSource;

    @BeforeAll
    static void initializeDataSource() {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setUrl(System.getenv("REFERENCE_TEST_DATABASE_URL"));
        String user = System.getenv("REFERENCE_TEST_DATABASE_USER");
        String password = System.getenv("REFERENCE_TEST_DATABASE_PASSWORD");
        if (user != null && !user.isBlank()) {
            postgres.setUser(user);
        }
        if (password != null) {
            postgres.setPassword(password);
        }
        dataSource = postgres;
    }

    @AfterEach
    void cleanFixtures() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                     DELETE FROM outbox_event WHERE event_id LIKE 'jdbc-test-%';
                     DELETE FROM idempotency_record WHERE subject_scope LIKE 'jdbc-test-%';
                     DELETE FROM meeting WHERE meeting_id LIKE 'jdbc-test-%';
                     """);
        }
    }

    @Test
    void repositoriesPersistAndRecoverPortState() throws Exception {
        JdbcMeetingRepository meetings = new JdbcMeetingRepository(dataSource);
        MeetingAggregate.MeetingSnapshot snapshot = new MeetingAggregate.MeetingSnapshot(
                "jdbc-test-meeting", "jdbc-test-scope", "jdbc-test-session", "profile", 1,
                MeetingState.CREATED, NOW, NOW);
        meetings.save(snapshot);
        assertEquals(snapshot, meetings.find("jdbc-test-meeting").orElseThrow());
        assertEquals(snapshot, meetings.findByDeploymentScopeAndClientSessionId(
                "jdbc-test-scope", "jdbc-test-session").orElseThrow());

        JdbcIdempotencyStore idempotency = new JdbcIdempotencyStore(dataSource);
        MeetingCommandResult result = new MeetingCommandResult(MeetingCommandResult.Operation.CREATED, snapshot);
        IdempotencyStore.IdempotencyRecord candidate = new IdempotencyStore.IdempotencyRecord(
                "jdbc-test-idempotency", "key", "digest", result);
        assertEquals(IdempotencyStore.ReservationStatus.ACQUIRED, idempotency.reserve(candidate).status());
        assertEquals(IdempotencyStore.ReservationStatus.REPLAY, idempotency.reserve(candidate).status());
        IdempotencyStore.IdempotencyRecord conflict = new IdempotencyStore.IdempotencyRecord(
                "jdbc-test-idempotency", "key", "other", result);
        assertEquals(IdempotencyStore.ReservationStatus.CONFLICT, idempotency.reserve(conflict).status());

        JdbcOutboxStore outbox = new JdbcOutboxStore(dataSource);
        OutboxStore.OutboxEvent event = OutboxStore.OutboxEvent.pending(
                "jdbc-test-event", "Meeting", "jdbc-test-meeting", "MeetingCreated", "{}", NOW, NOW);
        assertEquals(OutboxStore.AppendStatus.APPENDED, outbox.append(event).status());
        OutboxStore.ClaimedEvent claimed = outbox.claim("jdbc-test-owner", NOW, Duration.ofMinutes(1), 1)
                .stream().findFirst().orElseThrow();
        assertTrue(claimed.lease().leaseVersion() > 0);
        assertEquals(OutboxStore.PublishStatus.PUBLISHED,
                outbox.markPublished(claimed.lease(), NOW.plusSeconds(10)).status());

        OutboxStore.OutboxEvent retryEvent = OutboxStore.OutboxEvent.pending(
                "jdbc-test-event-fence", "Meeting", "jdbc-test-meeting", "Retry", "{}", NOW, NOW);
        outbox.append(retryEvent);
        OutboxStore.ClaimedEvent firstLease = outbox.claim(
                        "jdbc-test-owner-a", NOW, Duration.ofMinutes(1), 1)
                .stream().findFirst().orElseThrow();
        OutboxStore.ClaimedEvent secondLease = outbox.claim(
                        "jdbc-test-owner-b", NOW.plusSeconds(61), Duration.ofMinutes(1), 1)
                .stream().findFirst().orElseThrow();
        assertTrue(secondLease.lease().leaseVersion() > firstLease.lease().leaseVersion());
        // 事件已被新 owner 重新领取，旧 token 被栅栏拒绝；此时状态仍是 LEASED，
        // 因而返回 NOT_OWNER，而不是把新 owner 的有效 lease 误判成过期。
        assertEquals(OutboxStore.PublishStatus.NOT_OWNER,
                outbox.markPublished(firstLease.lease(), NOW.plusSeconds(62)).status());
        assertEquals(OutboxStore.FailureStatus.RETRY_SCHEDULED,
                outbox.markFailed(secondLease.lease(), NOW.plusSeconds(62), NOW.plusSeconds(120),
                        "temporary failure", 3).status());
    }
}
