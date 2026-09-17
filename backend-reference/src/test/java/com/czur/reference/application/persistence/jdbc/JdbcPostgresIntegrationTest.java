package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.command.CreateMeetingCommand;
import com.czur.reference.application.command.MeetingCommandService;
import com.czur.reference.application.command.StopMeetingCommand;
import com.czur.reference.application.port.IdempotencyStore;
import com.czur.reference.application.port.OutboxStore;
import com.czur.reference.domain.meeting.MeetingEvent;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
                     DELETE FROM outbox_event
                      WHERE event_id LIKE 'jdbc-test-%' OR aggregate_id LIKE 'jdbc-test-%';
                     DELETE FROM idempotency_record
                      WHERE subject_scope LIKE 'jdbc-test-%' OR idempotency_key LIKE 'jdbc-test-%';
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
                "jdbc-test-event", "Meeting", "jdbc-test-meeting", "MeetingCreated",
                "{\"a\":1,\"b\":2}", NOW, NOW);
        assertEquals(OutboxStore.AppendStatus.APPENDED, outbox.append(event).status());
        OutboxStore.OutboxEvent reorderedJson = OutboxStore.OutboxEvent.pending(
                "jdbc-test-event", "Meeting", "jdbc-test-meeting", "MeetingCreated",
                "{\"b\":2, \"a\":1}", NOW, NOW);
        assertEquals(OutboxStore.AppendStatus.REPLAYED, outbox.append(reorderedJson).status());
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

    @Test
    void meetingCommandsCommitSnapshotIdempotencyAndOutboxTogether() {
        MeetingCommandService service = new MeetingCommandService(new JdbcMeetingUnitOfWork(dataSource));
        CreateMeetingCommand create = new CreateMeetingCommand(
                "jdbc-test-uow", "jdbc-test-scope", "jdbc-test-session", "profile", 1, NOW,
                "jdbc-test-uow-create");

        MeetingCommandResult created = service.create(create);

        assertEquals(MeetingState.CREATED, created.meeting().state());
        assertEquals(created.meeting(), new JdbcMeetingRepository(dataSource)
                .find("jdbc-test-uow").orElseThrow());
        assertTrue(new JdbcIdempotencyStore(dataSource)
                .find("meeting.create", "jdbc-test-uow-create").isPresent());
        assertEquals(OutboxStore.AppendStatus.REPLAYED,
                new JdbcOutboxStore(dataSource).append(OutboxStore.OutboxEvent.pending(
                        commandEventId("MeetingCreated", "jdbc-test-uow"), "Meeting", "jdbc-test-uow",
                        "MeetingCreated", "{\"meetingId\":\"jdbc-test-uow\",\"deploymentScope\":\"jdbc-test-scope\",\"state\":\"CREATED\",\"updatedAt\":\"2026-09-16T04:00:00Z\"}", NOW, NOW)).status());

        // 让停止命令满足状态机的 RUNNING 前置条件。
        var running = com.czur.reference.domain.meeting.MeetingAggregate.fromSnapshot(created.meeting());
        running.apply(MeetingEvent.AUTHORIZE, NOW);
        running.apply(MeetingEvent.START, NOW);
        running.apply(MeetingEvent.START, NOW);
        new JdbcMeetingRepository(dataSource).save(running.snapshot());

        MeetingCommandResult stopped = service.stop(
                new StopMeetingCommand("jdbc-test-uow", NOW.plusSeconds(2), "jdbc-test-uow-stop"));
        assertEquals(MeetingState.STOPPING, stopped.meeting().state());
        JdbcOutboxStore commandOutbox = new JdbcOutboxStore(dataSource);
        String stopEventId = commandEventId("MeetingStopRequested", "jdbc-test-uow");
        var originalStopEvent = commandOutbox.find(stopEventId).orElseThrow();

        // 即使调用方错误地换了幂等键，已经进入 STOPPING 的会议也只保留首次
        // Stop Outbox，避免 Worker 重复触发收口、用量结算或产物生成。
        MeetingCommandResult repeatedStop = service.stop(
                new StopMeetingCommand("jdbc-test-uow", NOW.plusSeconds(3), "jdbc-test-uow-stop-other"));
        assertEquals(MeetingState.STOPPING, repeatedStop.meeting().state());
        assertEquals(originalStopEvent, commandOutbox.find(stopEventId).orElseThrow());
    }

    @Test
    void failedOutboxAppendRollsBackMeetingAndIdempotency() {
        String meetingId = "jdbc-test-uow-rollback";
        String eventId = commandEventId("MeetingCreated", meetingId);
        JdbcOutboxStore outbox = new JdbcOutboxStore(dataSource);
        outbox.append(OutboxStore.OutboxEvent.pending(
                eventId, "Meeting", meetingId, "MeetingCreated", "{\"different\":true}", NOW, NOW));

        MeetingCommandService service = new MeetingCommandService(new JdbcMeetingUnitOfWork(dataSource));
        CreateMeetingCommand command = new CreateMeetingCommand(
                meetingId, "jdbc-test-scope", "jdbc-test-rollback-session", "profile", 1, NOW,
                "jdbc-test-uow-rollback-key");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.create(command));
        assertTrue(new JdbcMeetingRepository(dataSource).find(meetingId).isEmpty());
        assertTrue(new JdbcIdempotencyStore(dataSource)
                .find("meeting.create", "jdbc-test-uow-rollback-key").isEmpty());
        assertEquals(OutboxStore.AppendStatus.REPLAYED,
                outbox.append(OutboxStore.OutboxEvent.pending(
                        eventId, "Meeting", meetingId, "MeetingCreated",
                        "{\"different\":true}", NOW, NOW)).status());
    }

    @Test
    void callbackFailureRollsBackAllThreeTransactionalPorts() {
        String meetingId = "jdbc-test-uow-callback";
        var snapshot = new MeetingAggregate.MeetingSnapshot(
                meetingId, "jdbc-test-scope", "jdbc-test-callback-session", "profile", 1,
                MeetingState.CREATED, NOW, NOW);
        var result = new MeetingCommandResult(MeetingCommandResult.Operation.CREATED, snapshot);
        var idempotencyRecord = new IdempotencyStore.IdempotencyRecord(
                "meeting.create", "jdbc-test-uow-callback-key", "digest", result);
        var event = OutboxStore.OutboxEvent.pending(
                "jdbc-test-uow-callback-event", "Meeting", meetingId,
                "MeetingCreated", "{}", NOW, NOW);

        JdbcMeetingUnitOfWork unitOfWork = new JdbcMeetingUnitOfWork(dataSource);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> unitOfWork.execute(transaction -> {
                    transaction.meetings().save(snapshot);
                    transaction.idempotency().save(idempotencyRecord);
                    transaction.outbox().append(event);
                    throw new IllegalStateException("simulated callback failure");
                }));

        assertTrue(new JdbcMeetingRepository(dataSource).find(meetingId).isEmpty());
        assertTrue(new JdbcIdempotencyStore(dataSource)
                .find("meeting.create", "jdbc-test-uow-callback-key").isEmpty());
        assertTrue(new JdbcOutboxStore(dataSource).find(event.eventId()).isEmpty());
    }

    @Test
    void concurrentStopAcrossConnectionsSharesOneStopEvent() throws Exception {
        String meetingId = "jdbc-test-uow-concurrent-stop";
        var created = new MeetingAggregate(
                meetingId, "jdbc-test-scope", "jdbc-test-concurrent-session", "profile", 1, NOW);
        created.apply(MeetingEvent.AUTHORIZE, NOW);
        created.apply(MeetingEvent.START, NOW);
        created.apply(MeetingEvent.START, NOW);
        new JdbcMeetingRepository(dataSource).save(created.snapshot());

        MeetingCommandService firstService = new MeetingCommandService(new JdbcMeetingUnitOfWork(dataSource));
        MeetingCommandService secondService = new MeetingCommandService(new JdbcMeetingUnitOfWork(dataSource));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MeetingCommandResult> first = executor.submit(() -> {
                start.await();
                return firstService.stop(new StopMeetingCommand(
                        meetingId, NOW.plusSeconds(4), "jdbc-test-concurrent-stop-a"));
            });
            Future<MeetingCommandResult> second = executor.submit(() -> {
                start.await();
                return secondService.stop(new StopMeetingCommand(
                        meetingId, NOW.plusSeconds(3), "jdbc-test-concurrent-stop-b"));
            });
            start.countDown();

            assertEquals(MeetingState.STOPPING, first.get().meeting().state());
            assertEquals(MeetingState.STOPPING, second.get().meeting().state());
        } finally {
            executor.shutdownNow();
        }

        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT count(*)
                       FROM outbox_event
                      WHERE aggregate_id = ? AND event_type = 'MeetingStopRequested'
                     """)) {
            statement.setString(1, meetingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    private static String commandEventId(String eventType, String meetingId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(eventType.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(meetingId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return "meeting-" + HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
