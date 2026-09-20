package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingCommandEventFactory;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.MeetingFinalizationStore;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.OwnedMeetingStopUnitOfWork;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * owned 会议停止工作单元的真实 PostgreSQL 16 原子性门禁。
 *
 * <p>每个用例创建独立 schema 并执行 V001～V005，覆盖归属拒绝顺序、
 * 状态规则、幂等、不同键并发、完整 CAS、Outbox 回滚、统一事实时间以及
 * commit/rollback/restore/close 故障语义。测试只在显式提供数据库环境变量时
 * 运行，并在结束后删除自己的 schema。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcOwnedMeetingStopUnitOfWorkIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-17T10:00:00Z");
    private static final Instant STOP_CLOCK =
            Instant.parse("2026-09-17T10:10:00.123456789Z");
    private static final Instant STOP_AT = STOP_CLOCK.truncatedTo(ChronoUnit.MICROS);
    private static final String DEPLOYMENT = "stop-uow-deployment";
    private static final String SUBJECT = "stop-uow-subject";

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createSchemaAndMigrate() throws Exception {
        adminDataSource = postgres(null);
        schema = "stop_uow_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        dataSource = postgres(schema);
        applyMigration("V001__initial_schema.sql");
        applyMigration("V002__outbox_lease_fencing.sql");
        applyMigration("V003__identity_entitlement.sql");
        applyMigration("V004__meeting_event_envelope.sql");
        applyMigration("V005__meeting_finalization_fact.sql");
    }

    @AfterEach
    void dropSchema() throws Exception {
        if (adminDataSource == null || schema == null) {
            return;
        }
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS "
                    + quoteIdentifier(schema) + " CASCADE");
        }
    }

    @Test
    void ownershipDenialPrecedesEveryStopIdempotencyRead() throws Exception {
        createOwned("other-deployment", MeetingState.RUNNING,
                "deployment-other", SUBJECT, STOP_AT.plusSeconds(600));
        createOwned("other-subject", MeetingState.RUNNING,
                DEPLOYMENT, "subject-other", STOP_AT.plusSeconds(600));
        insertLegacy("legacy", MeetingState.RUNNING, DEPLOYMENT);

        DataSource failOnIdempotencyRead = faultInjectingDataSource(
                dataSource, FaultPoint.FAIL_IF_IDEMPOTENCY_READ,
                new FaultObservation());
        var unitOfWork = unitOfWork(failOnIdempotencyRead, STOP_CLOCK);

        assertFailureCode(unitOfWork.stop(request(
                        "meeting-other-deployment", "key-deployment", "digest-deployment")),
                OwnedMeetingStopUnitOfWork.ErrorCode.ACCESS_DENIED);
        assertFailureCode(unitOfWork.stop(request(
                        "meeting-other-subject", "key-subject", "digest-subject")),
                OwnedMeetingStopUnitOfWork.ErrorCode.ACCESS_DENIED);
        assertFailureCode(unitOfWork.stop(request(
                        "meeting-legacy", "key-legacy", "digest-legacy")),
                OwnedMeetingStopUnitOfWork.ErrorCode.ACCESS_DENIED);

        assertFailureCode(unitOfWork.stop(request(
                        "meeting-missing", "key-missing", "digest-missing")),
                OwnedMeetingStopUnitOfWork.ErrorCode.MEETING_NOT_FOUND);
    }

    @Test
    void allowedStatesAndExpiredGrantFollowFrozenStopRules() throws Exception {
        List<MeetingState> advancing = List.of(MeetingState.STARTING, MeetingState.RUNNING);
        for (MeetingState state : advancing) {
            String suffix = state.name().toLowerCase();
            createOwned(suffix, state, DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
            var result = success(unitOfWork(dataSource, STOP_CLOCK).stop(request(
                    "meeting-" + suffix, "key-" + suffix, "digest-" + suffix)));
            assertEquals(OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED, result.code());
            assertEquals(MeetingState.STOPPING, result.result().meeting().state());
            assertEquals(1, stopEventCount("meeting-" + suffix));
            assertEquals(1L, aggregateVersion("meeting-" + suffix));
        }

        for (MeetingState state : List.of(MeetingState.STOPPING, MeetingState.FINALIZING)) {
            String suffix = "noop-" + state.name().toLowerCase();
            createOwned(suffix, state, DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
            var result = success(unitOfWork(dataSource, STOP_CLOCK).stop(request(
                    "meeting-" + suffix, "key-" + suffix, "digest-" + suffix)));
            assertEquals(OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED, result.code());
            assertEquals(state, result.result().meeting().state());
            assertEquals(0, stopEventCount("meeting-" + suffix));
            assertEquals(0L, aggregateVersion("meeting-" + suffix));
        }

        // Grant 在创建后、停止前到期仍不撤销会议所有者停止自己的会议。
        createOwned("expired-grant", MeetingState.RUNNING,
                DEPLOYMENT, SUBJECT, STOP_AT.minusSeconds(1));
        var expiredGrant = success(unitOfWork(dataSource, STOP_CLOCK).stop(request(
                "meeting-expired-grant", "key-expired-grant", "digest-expired-grant")));
        assertEquals(MeetingState.STOPPING, expiredGrant.result().meeting().state());
    }

    @Test
    void allOtherStatesAreRejectedWithoutIdempotencyOrOutboxFacts() throws Exception {
        List<MeetingState> rejected = List.of(
                MeetingState.UNKNOWN,
                MeetingState.CREATED,
                MeetingState.AUTHORIZED,
                MeetingState.READY,
                MeetingState.READY_WITH_WARNINGS,
                MeetingState.FAILED,
                MeetingState.CANCELLED,
                MeetingState.DELETION_PENDING,
                MeetingState.DELETED);
        for (MeetingState state : rejected) {
            String suffix = "invalid-" + state.name().toLowerCase();
            createOwned(suffix, state, DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
            var failure = failure(unitOfWork(dataSource, STOP_CLOCK).stop(request(
                    "meeting-" + suffix, "key-" + suffix, "digest-" + suffix)));
            assertEquals(OwnedMeetingStopUnitOfWork.ErrorCode.INVALID_MEETING_STATE,
                    failure.code());
            assertFalse(failure.retryable());
            assertEquals(0, stopIdempotencyCount("key-" + suffix));
            assertEquals(0, stopEventCount("meeting-" + suffix));
        }
    }

    @Test
    void firstStopReplayConflictAndFactTimesAreStable() throws Exception {
        createOwned("stable", MeetingState.RUNNING,
                DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
        var request = request("meeting-stable", "key-stable", "digest-stable");
        AtomicInteger clockReads = new AtomicInteger();
        Clock singleReadClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                clockReads.incrementAndGet();
                return STOP_CLOCK;
            }
        };
        var unitOfWork = new JdbcOwnedMeetingStopUnitOfWork(
                dataSource, singleReadClock);

        var first = success(unitOfWork.stop(request));
        assertEquals(OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED, first.code());
        assertEquals(STOP_AT, first.result().meeting().updatedAt());
        assertEquals(1, clockReads.get(), "首次事务只能读取一次服务端时钟");

        var replay = success(unitOfWork.stop(request));
        assertEquals(OwnedMeetingStopUnitOfWork.SuccessCode.REPLAYED, replay.code());
        assertEquals(first.result(), replay.result());
        assertEquals(1, clockReads.get(), "稳定重放不得重新生成事实时间");

        var conflict = failure(unitOfWork.stop(request(
                "meeting-stable", "key-stable", "digest-other")));
        assertEquals(OwnedMeetingStopUnitOfWork.ErrorCode.IDEMPOTENCY_CONFLICT,
                conflict.code());
        assertEquals(1, clockReads.get(), "摘要冲突不得读取或生成事实时间");

        String scope = IdempotencyScopeCodec.meetingStop(DEPLOYMENT, SUBJECT);
        String eventId = MeetingCommandEventFactory.meetingStopRequested(
                first.result().meeting(), STOP_AT).eventId();
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(STOP_AT, queryInstant(connection,
                    "SELECT updated_at FROM meeting WHERE meeting_id = ?",
                    "meeting-stable"));
            assertEquals(STOP_AT, queryInstant(connection, """
                    SELECT created_at FROM idempotency_record
                     WHERE subject_scope = ? AND idempotency_key = 'key-stable'
                    """, scope));
            assertEquals(STOP_AT, queryInstant(connection,
                    "SELECT occurred_at FROM outbox_event WHERE event_id = ?", eventId));
            assertEquals(STOP_AT, queryInstant(connection,
                    "SELECT available_at FROM outbox_event WHERE event_id = ?", eventId));
        }
    }

    @Test
    void stableStopReplayDoesNotOverrideCurrentFinalizationFact() throws Exception {
        createOwned("finalization-boundary", MeetingState.RUNNING,
                DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
        var request = request(
                "meeting-finalization-boundary", "key-finalization-boundary",
                "digest-finalization-boundary");
        var stop = unitOfWork(dataSource, STOP_CLOCK);

        var first = success(stop.stop(request));
        assertEquals(MeetingState.STOPPING, first.result().meeting().state());
        var finalization = new JdbcMeetingFinalizationStore(
                dataSource, () -> "receipt-finalization-boundary");
        var pending = assertInstanceOf(MeetingFinalizationStore.Found.class,
                finalization.find("meeting-finalization-boundary"));
        assertEquals(InputState.INPUT_COMPLETE_PENDING,
                pending.fact().inputState());

        var accepted = assertInstanceOf(MeetingFinalizationStore.Applied.class,
                finalization.acceptInput(
                        "meeting-finalization-boundary", STOP_AT.plusSeconds(1)));
        assertEquals(InputState.INPUT_ACCEPTED, accepted.fact().inputState());

        var replay = success(stop.stop(request));
        assertEquals(OwnedMeetingStopUnitOfWork.SuccessCode.REPLAYED, replay.code());
        assertEquals(MeetingState.STOPPING, replay.result().meeting().state(),
                "停止幂等正文保持首次停止意图，不伪装成持续状态查询");

        var current = assertInstanceOf(MeetingFinalizationStore.Found.class,
                new JdbcMeetingFinalizationStore(
                        dataSource,
                        () -> { throw new AssertionError("accepted 重读不得生成 receipt"); })
                        .find("meeting-finalization-boundary"));
        assertEquals("receipt-finalization-boundary",
                current.fact().finalizationReceipt(),
                "REST/查询必须组合当前数据库事实，不能使用旧 Stop body 覆盖 receipt");
        assertEquals(MeetingState.FINALIZING,
                new JdbcMeetingRepository(dataSource)
                        .find("meeting-finalization-boundary").orElseThrow().state());
    }

    @Test
    void concurrentPendingAndAcceptanceHaveOneWinnerAndSurviveNewAdapterInstance()
            throws Exception {
        createOwned("finalization-race", MeetingState.STOPPING,
                DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
        AtomicInteger generated = new AtomicInteger();
        var store = new JdbcMeetingFinalizationStore(dataSource, () ->
                "receipt-race-" + generated.incrementAndGet());

        List<MeetingFinalizationStore.Result> pendingResults = concurrently(10,
                () -> store.requestInputCompletion("meeting-finalization-race"));
        assertEquals(1, pendingResults.stream()
                .filter(MeetingFinalizationStore.Applied.class::isInstance).count());
        assertEquals(9, pendingResults.stream()
                .filter(MeetingFinalizationStore.Replayed.class::isInstance).count());
        assertEquals(0, generated.get());

        List<MeetingFinalizationStore.Result> acceptanceResults = concurrently(10,
                () -> store.acceptInput(
                        "meeting-finalization-race", STOP_AT.plusSeconds(1)));
        assertEquals(1, acceptanceResults.stream()
                .filter(MeetingFinalizationStore.Applied.class::isInstance).count());
        assertEquals(9, acceptanceResults.stream()
                .filter(MeetingFinalizationStore.Replayed.class::isInstance).count());
        assertEquals(1, generated.get());

        var afterRestart = new JdbcMeetingFinalizationStore(
                dataSource,
                () -> { throw new AssertionError("重启重放不得生成第二个 receipt"); });
        var found = assertInstanceOf(MeetingFinalizationStore.Found.class,
                afterRestart.find("meeting-finalization-race"));
        assertEquals("receipt-race-1", found.fact().finalizationReceipt());
        assertEquals(MeetingState.FINALIZING,
                new JdbcMeetingRepository(dataSource)
                        .find("meeting-finalization-race").orElseThrow().state());
        assertEquals(1L, aggregateVersion("meeting-finalization-race"));
    }

    @Test
    void finalizationCommitUnknownAndRollbackFailureNeverPublishUnsafeOutcome()
            throws Exception {
        createOwned("receipt-commit-unknown", MeetingState.STOPPING,
                DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
        new JdbcMeetingFinalizationStore(dataSource, () -> "unused")
                .requestInputCompletion("meeting-receipt-commit-unknown");
        FaultObservation commitObservation = new FaultObservation();
        var commitUnknown = new JdbcMeetingFinalizationStore(
                faultInjectingDataSource(dataSource, FaultPoint.AFTER_COMMIT, commitObservation),
                () -> "receipt-commit-sensitive");
        IllegalStateException unknown = assertThrows(IllegalStateException.class,
                () -> commitUnknown.acceptInput(
                        "meeting-receipt-commit-unknown", STOP_AT.plusSeconds(1)));
        assertTrue(unknown.getMessage().contains("outcome=UNKNOWN"));
        assertFalse(unknown.getMessage().contains("receipt-commit-sensitive"));
        assertEquals(null, unknown.getCause());
        assertEquals(0, unknown.getSuppressed().length);
        assertFalse(commitObservation.restoreAutoCommitAttempted.get());
        var committed = assertInstanceOf(MeetingFinalizationStore.Found.class,
                new JdbcMeetingFinalizationStore(dataSource, () -> "must-not-run")
                        .find("meeting-receipt-commit-unknown"));
        assertEquals("receipt-commit-sensitive", committed.fact().finalizationReceipt(),
                "commit ACK 丢失后必须以数据库查询恢复真实结果");

        createOwned("receipt-rollback", MeetingState.STOPPING,
                DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
        new JdbcMeetingFinalizationStore(dataSource, () -> "unused")
                .requestInputCompletion("meeting-receipt-rollback");
        FaultObservation rollbackObservation = new FaultObservation();
        var rollbackFailure = new JdbcMeetingFinalizationStore(
                faultInjectingDataSource(
                        dataSource, FaultPoint.FINALIZATION_UPDATE_AND_ROLLBACK,
                        rollbackObservation),
                () -> "receipt-rollback-sensitive");
        IllegalStateException rolledBack = assertThrows(IllegalStateException.class,
                () -> rollbackFailure.acceptInput(
                        "meeting-receipt-rollback", STOP_AT.plusSeconds(1)));
        String fullDiagnostic = rolledBack.toString();
        assertFalse(fullDiagnostic.contains("receipt-rollback-sensitive"));
        assertEquals(null, rolledBack.getCause());
        assertEquals(0, rolledBack.getSuppressed().length);
        assertFalse(rollbackObservation.restoreAutoCommitAttempted.get());
        assertTrue(rollbackObservation.abortAttempted.get());
        var pending = assertInstanceOf(MeetingFinalizationStore.Found.class,
                new JdbcMeetingFinalizationStore(dataSource, () -> "unused")
                        .find("meeting-receipt-rollback"));
        assertEquals(InputState.INPUT_COMPLETE_PENDING, pending.fact().inputState());
        assertEquals(MeetingState.STOPPING,
                new JdbcMeetingRepository(dataSource)
                        .find("meeting-receipt-rollback").orElseThrow().state());
    }

    @Test
    void differentKeysConcurrentStopUpdateAndAppendOnlyOnce() throws Exception {
        createOwned("race", MeetingState.RUNNING,
                DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
        var unitOfWork = unitOfWork(dataSource, STOP_CLOCK);
        CountDownLatch start = new CountDownLatch(1);
        var firstRequest = request("meeting-race", "key-race-a", "digest-race");
        var secondRequest = request("meeting-race", "key-race-b", "digest-race");
        List<OwnedMeetingStopUnitOfWork.Result> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<OwnedMeetingStopUnitOfWork.Result> first = executor.submit(() -> {
                start.await();
                return unitOfWork.stop(firstRequest);
            });
            Future<OwnedMeetingStopUnitOfWork.Result> second = executor.submit(() -> {
                start.await();
                return unitOfWork.stop(secondRequest);
            });
            start.countDown();
            results.add(first.get());
            results.add(second.get());
        }

        assertEquals(2, results.stream()
                .filter(result -> result instanceof OwnedMeetingStopUnitOfWork.Success success
                        && success.code() == OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED)
                .count());
        assertEquals(1L, aggregateVersion("meeting-race"));
        assertEquals(1, stopEventCount("meeting-race"));
        assertEquals(1, stopIdempotencyCount("key-race-a"));
        assertEquals(1, stopIdempotencyCount("key-race-b"));
        assertEquals(OwnedMeetingStopUnitOfWork.SuccessCode.REPLAYED,
                success(unitOfWork.stop(firstRequest)).code());
        assertEquals(OwnedMeetingStopUnitOfWork.SuccessCode.REPLAYED,
                success(unitOfWork.stop(secondRequest)).code());
    }

    @Test
    void ownedUpdateUsesImmutableFactsVersionCasAndOrdinarySaveStillRejects()
            throws Exception {
        MeetingAggregate.MeetingSnapshot initial = createOwned(
                "cas", MeetingState.RUNNING,
                DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
        JdbcMeetingRepository.VersionedMeeting locked;
        MeetingAggregate.MeetingSnapshot stopping;
        try (Connection connection = transaction()) {
            locked = JdbcMeetingRepository.findVersionedForUpdate(
                    connection, initial.meetingId()).orElseThrow();
            MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(locked.snapshot());
            aggregate.apply(MeetingEvent.STOP_REQUESTED, STOP_AT);
            stopping = aggregate.snapshot();
            JdbcMeetingRepository.updateOwnedExisting(connection, locked, stopping);
            connection.commit();
        }
        assertEquals(MeetingState.STOPPING,
                new JdbcMeetingRepository(dataSource).find(initial.meetingId())
                        .orElseThrow().state());
        assertEquals(1L, aggregateVersion(initial.meetingId()));

        try (Connection connection = transaction()) {
            assertThrows(SQLException.class, () ->
                    JdbcMeetingRepository.updateOwnedExisting(
                            connection, locked, stopping));
            connection.rollback();
        }

        MeetingAggregate.MeetingSnapshot tampered = MeetingAggregate.MeetingSnapshot.owned(
                stopping.meetingId(), stopping.deploymentScope(), stopping.clientSessionId(),
                stopping.subjectScope(), stopping.grantId(), stopping.grantExpiresAt(),
                stopping.usageReservationId(), "profile-tampered",
                stopping.profileRevision(), stopping.state(), stopping.createdAt(),
                stopping.updatedAt());
        try (Connection connection = transaction()) {
            assertThrows(IllegalArgumentException.class, () ->
                    JdbcMeetingRepository.updateOwnedExisting(connection, locked, tampered));
            connection.rollback();
        }
        assertThrows(IllegalArgumentException.class, () ->
                new JdbcMeetingRepository(dataSource).save(stopping));
    }

    @Test
    void outboxConflictRollsBackMeetingIdempotencyAndNewOutboxFact() throws Exception {
        MeetingAggregate.MeetingSnapshot current = createOwned(
                "outbox", MeetingState.RUNNING,
                DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
        MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(current);
        aggregate.apply(MeetingEvent.STOP_REQUESTED, STOP_AT);
        var event = MeetingCommandEventFactory.meetingStopRequested(
                aggregate.snapshot(), STOP_AT);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO outbox_event (
                         event_id, aggregate_type, aggregate_id, event_type, payload,
                         occurred_at, available_at, status, attempt_count)
                     VALUES (?, 'Meeting', ?, 'MeetingStopRequested',
                             '{"different":true}'::jsonb, ?, ?, 'PENDING', 0)
                     """)) {
            statement.setString(1, event.eventId());
            statement.setString(2, current.meetingId());
            statement.setTimestamp(3, Timestamp.from(STOP_AT));
            statement.setTimestamp(4, Timestamp.from(STOP_AT));
            assertEquals(1, statement.executeUpdate());
        }

        var failure = failure(unitOfWork(dataSource, STOP_CLOCK).stop(request(
                current.meetingId(), "key-outbox", "digest-outbox")));
        assertEquals(OwnedMeetingStopUnitOfWork.ErrorCode.INTERNAL, failure.code());
        assertEquals(MeetingState.RUNNING,
                new JdbcMeetingRepository(dataSource).find(current.meetingId())
                        .orElseThrow().state());
        assertEquals(0L, aggregateVersion(current.meetingId()));
        assertEquals(0, stopIdempotencyCount("key-outbox"));
        assertEquals(1, stopEventCount(current.meetingId()));
    }

    @Test
    void commitUnknownBeforeAndAfterCommitHaveRecoverableFacts() throws Exception {
        createOwned("commit-before", MeetingState.RUNNING,
                DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
        var beforeRequest = request(
                "meeting-commit-before", "key-commit-before", "digest-commit-before");
        var before = failure(unitOfWork(faultInjectingDataSource(
                        dataSource, FaultPoint.BEFORE_COMMIT, new FaultObservation()),
                STOP_CLOCK).stop(beforeRequest));
        assertEquals(OwnedMeetingStopUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, before.code());
        assertTrue(before.retryable());
        assertEquals(MeetingState.RUNNING,
                new JdbcMeetingRepository(dataSource).find("meeting-commit-before")
                        .orElseThrow().state());
        assertEquals(0, stopIdempotencyCount("key-commit-before"));

        createOwned("commit-after", MeetingState.RUNNING,
                DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
        var afterRequest = request(
                "meeting-commit-after", "key-commit-after", "digest-commit-after");
        var after = failure(unitOfWork(faultInjectingDataSource(
                        dataSource, FaultPoint.AFTER_COMMIT, new FaultObservation()),
                STOP_CLOCK).stop(afterRequest));
        assertEquals(OwnedMeetingStopUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, after.code());
        assertTrue(after.retryable());
        assertEquals(MeetingState.STOPPING,
                new JdbcMeetingRepository(dataSource).find("meeting-commit-after")
                        .orElseThrow().state());
        assertEquals(OwnedMeetingStopUnitOfWork.SuccessCode.REPLAYED,
                success(unitOfWork(dataSource, STOP_CLOCK).stop(afterRequest)).code());

        createOwned("runtime-after", MeetingState.RUNNING,
                DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
        var runtimeRequest = request(
                "meeting-runtime-after", "key-runtime-after", "digest-runtime-after");
        var runtime = failure(unitOfWork(faultInjectingDataSource(
                        dataSource, FaultPoint.RUNTIME_AFTER_COMMIT,
                        new FaultObservation()), STOP_CLOCK).stop(runtimeRequest));
        assertEquals(OwnedMeetingStopUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, runtime.code());
        assertEquals(OwnedMeetingStopUnitOfWork.SuccessCode.REPLAYED,
                success(unitOfWork(dataSource, STOP_CLOCK).stop(runtimeRequest)).code());
    }

    @Test
    void rollbackRestoreAndCloseFailuresNeverImplicitlyCommitOrOverrideResult()
            throws Exception {
        createOwned("rollback-sql", MeetingState.RUNNING,
                DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
        FaultObservation rollbackSqlObservation = new FaultObservation();
        var rollbackSql = failure(unitOfWork(faultInjectingDataSource(
                        dataSource, FaultPoint.OUTBOX_AND_ROLLBACK_SQL,
                        rollbackSqlObservation), STOP_CLOCK).stop(request(
                "meeting-rollback-sql", "key-rollback-sql", "digest-rollback-sql")));
        assertEquals(OwnedMeetingStopUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE,
                rollbackSql.code());
        assertFalse(rollbackSqlObservation.restoreAutoCommitAttempted.get());
        assertTrue(rollbackSqlObservation.abortAttempted.get());
        assertTrue(rollbackSqlObservation.closeAttempted.get());
        assertEquals(MeetingState.RUNNING,
                new JdbcMeetingRepository(dataSource).find("meeting-rollback-sql")
                        .orElseThrow().state());
        assertEquals(0, stopIdempotencyCount("key-rollback-sql"));

        createOwned("rollback-runtime", MeetingState.CREATED,
                DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
        FaultObservation rollbackRuntimeObservation = new FaultObservation();
        var rollbackRuntime = failure(unitOfWork(faultInjectingDataSource(
                        dataSource, FaultPoint.ROLLBACK_RUNTIME,
                        rollbackRuntimeObservation), STOP_CLOCK).stop(request(
                "meeting-rollback-runtime", "key-rollback-runtime",
                "digest-rollback-runtime")));
        assertEquals(OwnedMeetingStopUnitOfWork.ErrorCode.INVALID_MEETING_STATE,
                rollbackRuntime.code());
        assertFalse(rollbackRuntimeObservation.restoreAutoCommitAttempted.get());
        assertTrue(rollbackRuntimeObservation.abortAttempted.get());
        assertTrue(rollbackRuntimeObservation.closeAttempted.get());

        createOwned("restore-runtime", MeetingState.RUNNING,
                DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
        FaultObservation restoreObservation = new FaultObservation();
        var restored = success(unitOfWork(faultInjectingDataSource(
                        dataSource, FaultPoint.RESTORE_AUTOCOMMIT_RUNTIME,
                        restoreObservation), STOP_CLOCK).stop(request(
                "meeting-restore-runtime", "key-restore-runtime",
                "digest-restore-runtime")));
        assertEquals(OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED, restored.code());
        assertTrue(restoreObservation.restoreAutoCommitAttempted.get());
        assertTrue(restoreObservation.closeAttempted.get());

        createOwned("close-runtime", MeetingState.RUNNING,
                DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
        FaultObservation closeObservation = new FaultObservation();
        var closed = success(unitOfWork(faultInjectingDataSource(
                        dataSource, FaultPoint.CLOSE_RUNTIME,
                        closeObservation), STOP_CLOCK).stop(request(
                "meeting-close-runtime", "key-close-runtime", "digest-close-runtime")));
        assertEquals(OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED, closed.code());
        assertTrue(closeObservation.restoreAutoCommitAttempted.get());
        assertTrue(closeObservation.closeAttempted.get());
    }

    @Test
    void clockFailureIsInternalAndDoesNotPersistPartialFacts() throws Exception {
        createOwned("clock", MeetingState.RUNNING,
                DEPLOYMENT, SUBJECT, STOP_AT.plusSeconds(600));
        Clock failingClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                throw new java.time.DateTimeException("dynamic clock detail");
            }
        };

        var result = failure(new JdbcOwnedMeetingStopUnitOfWork(
                dataSource, failingClock).stop(request(
                "meeting-clock", "key-clock", "digest-clock")));
        assertEquals(OwnedMeetingStopUnitOfWork.ErrorCode.INTERNAL, result.code());
        assertEquals(MeetingState.RUNNING,
                new JdbcMeetingRepository(dataSource).find("meeting-clock")
                        .orElseThrow().state());
        assertEquals(0, stopIdempotencyCount("key-clock"));
    }

    private JdbcOwnedMeetingStopUnitOfWork unitOfWork(
            DataSource source, Instant clockInstant) {
        return new JdbcOwnedMeetingStopUnitOfWork(
                source, Clock.fixed(clockInstant, ZoneOffset.UTC));
    }

    private OwnedMeetingStopUnitOfWork.StopRequest request(
            String meetingId, String key, String digest) {
        return new OwnedMeetingStopUnitOfWork.StopRequest(
                context(DEPLOYMENT, SUBJECT), meetingId, key, digest);
    }

    private MeetingAggregate.MeetingSnapshot createOwned(
            String suffix,
            MeetingState state,
            String deployment,
            String subject,
            Instant grantExpiresAt) throws Exception {
        String meetingId = "meeting-" + suffix;
        Instant reservationExpiresAt = grantExpiresAt.minusMillis(1);
        MeetingGrant grant = new MeetingGrant(
                "grant-" + suffix,
                deployment,
                subject,
                "profile-owned",
                7,
                Set.of("transcript.realtime"),
                Set.of("transcript.realtime"),
                3_600_000,
                CREATED_AT.minusSeconds(20),
                grantExpiresAt);
        UsageReservation reservation = UsageReservation.reserved(
                "reservation-" + suffix,
                grant,
                meetingId,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                CREATED_AT.minusSeconds(10),
                reservationExpiresAt);
        UsageOperationResult reserve = UsageOperationResult.reserve(
                deployment,
                subject,
                "reserve-key-" + suffix,
                "reserve-digest-" + suffix,
                grantExpiresAt,
                AuthorizedProfileSnapshot.from(grant),
                reservation);
        try (Connection connection = transaction()) {
            JdbcUsageReservationStore.insertReservation(connection, reservation);
            assertTrue(JdbcUsageReservationStore.insertOperation(
                    connection, reserve, CREATED_AT.minusSeconds(10)));
            connection.commit();
        }
        OwnedMeetingCreationUnitOfWork.CreateRequest createRequest =
                new OwnedMeetingCreationUnitOfWork.CreateRequest(
                        deployment,
                        subject,
                        meetingId,
                        "session-" + suffix,
                        "create-key-" + suffix,
                        "create-digest-" + suffix,
                        reserve);
        var created = new JdbcOwnedMeetingCreationUnitOfWork(
                dataSource, Clock.fixed(CREATED_AT, ZoneOffset.UTC))
                .create(createRequest);
        assertTrue(created instanceof OwnedMeetingCreationUnitOfWork.Success,
                () -> "owned setup failed: " + created);
        if (state != MeetingState.CREATED) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         UPDATE meeting
                            SET state = ?, updated_at = ?,
                                input_state = ?, finalization_receipt_id = ?
                          WHERE meeting_id = ?
                         """)) {
                statement.setString(1, state.name());
                statement.setTimestamp(2, Timestamp.from(CREATED_AT.plusSeconds(1)));
                boolean accepted = state == MeetingState.FINALIZING;
                statement.setString(3, accepted
                        ? InputState.INPUT_ACCEPTED.name() : InputState.INPUT_OPEN.name());
                statement.setString(4, accepted ? "setup-receipt-" + suffix : null);
                statement.setString(5, meetingId);
                assertEquals(1, statement.executeUpdate());
            }
        }
        return new JdbcMeetingRepository(dataSource).find(meetingId).orElseThrow();
    }

    private void insertLegacy(String suffix, MeetingState state, String deployment) {
        Instant updated = state == MeetingState.CREATED
                ? CREATED_AT : CREATED_AT.plusSeconds(1);
        new JdbcMeetingRepository(dataSource).save(
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        "meeting-" + suffix,
                        deployment,
                        "session-" + suffix,
                        "profile-legacy",
                        1,
                        state,
                        CREATED_AT,
                        updated));
    }

    private static MeetingCommandContext context(String deployment, String subject) {
        return new MeetingCommandContext(
                deployment,
                new VerifiedIdentity(
                        new Subject(subject),
                        Set.of("ROLE_USER"),
                        Set.of(MeetingPermissions.STOP),
                        CREATED_AT.minusSeconds(60),
                        STOP_AT.plusSeconds(600),
                        "credential-reference"));
    }

    private int stopIdempotencyCount(String key) throws SQLException {
        String scope = IdempotencyScopeCodec.meetingStop(DEPLOYMENT, SUBJECT);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*) FROM idempotency_record
                      WHERE subject_scope = ? AND idempotency_key = ?
                     """)) {
            statement.setString(1, scope);
            statement.setString(2, key);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private int stopEventCount(String meetingId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*) FROM outbox_event
                      WHERE aggregate_id = ? AND event_type = 'MeetingStopRequested'
                     """)) {
            statement.setString(1, meetingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private long aggregateVersion(String meetingId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT aggregate_version FROM meeting WHERE meeting_id = ?")) {
            statement.setString(1, meetingId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static <T> List<T> concurrently(int count, CheckedSupplier<T> operation)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return operation.get();
                }));
            }
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>(count);
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private Connection transaction() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static Instant queryInstant(
            Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                Timestamp value = rows.getTimestamp(1);
                assertNotNull(value);
                return value.toInstant();
            }
        }
    }

    private static OwnedMeetingStopUnitOfWork.Success success(
            OwnedMeetingStopUnitOfWork.Result result) {
        assertTrue(result instanceof OwnedMeetingStopUnitOfWork.Success,
                () -> "expected success but got " + result);
        return (OwnedMeetingStopUnitOfWork.Success) result;
    }

    private static OwnedMeetingStopUnitOfWork.Failure failure(
            OwnedMeetingStopUnitOfWork.Result result) {
        assertTrue(result instanceof OwnedMeetingStopUnitOfWork.Failure,
                () -> "expected failure but got " + result);
        return (OwnedMeetingStopUnitOfWork.Failure) result;
    }

    private static void assertFailureCode(
            OwnedMeetingStopUnitOfWork.Result result,
            OwnedMeetingStopUnitOfWork.ErrorCode expected) {
        assertEquals(expected, failure(result).code());
    }

    private static DataSource faultInjectingDataSource(
            DataSource delegate,
            FaultPoint faultPoint,
            FaultObservation observation) {
        return new DelegatingDataSource(delegate) {
            @Override
            public Connection getConnection() throws SQLException {
                return faultInjectingConnection(
                        super.getConnection(), faultPoint, observation);
            }

            @Override
            public Connection getConnection(String username, String password)
                    throws SQLException {
                return faultInjectingConnection(
                        super.getConnection(username, password), faultPoint, observation);
            }
        };
    }

    private static Connection faultInjectingConnection(
            Connection delegate,
            FaultPoint faultPoint,
            FaultObservation observation) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if ("prepareStatement".equals(method.getName())
                            && arguments != null
                            && arguments.length > 0
                            && arguments[0] instanceof String sql) {
                        if (faultPoint == FaultPoint.FAIL_IF_IDEMPOTENCY_READ
                                && sql.contains("FROM idempotency_record")) {
                            throw new AssertionError(
                                    "access denial must precede idempotency read");
                        }
                        if (faultPoint == FaultPoint.OUTBOX_AND_ROLLBACK_SQL
                                && sql.contains("INSERT INTO outbox_event")) {
                            throw new SQLException(
                                    "dynamic outbox detail must stay hidden", "08006");
                        }
                        if (faultPoint == FaultPoint.FINALIZATION_UPDATE_AND_ROLLBACK
                                && sql.contains("SET input_state = 'INPUT_ACCEPTED'")) {
                            PreparedStatement prepared;
                            try {
                                prepared = (PreparedStatement) method.invoke(delegate, arguments);
                            } catch (InvocationTargetException reflected) {
                                throw reflected.getCause();
                            }
                            return Proxy.newProxyInstance(
                                    PreparedStatement.class.getClassLoader(),
                                    new Class<?>[]{PreparedStatement.class},
                                    (statementProxy, statementMethod, statementArguments) -> {
                                        if ("executeUpdate".equals(statementMethod.getName())) {
                                            try {
                                                statementMethod.invoke(prepared, statementArguments);
                                            } catch (InvocationTargetException reflected) {
                                                throw reflected.getCause();
                                            }
                                            throw new SQLException(
                                                    "dynamic receipt-rollback-sensitive detail",
                                                    "08006");
                                        }
                                        try {
                                            return statementMethod.invoke(
                                                    prepared, statementArguments);
                                        } catch (InvocationTargetException reflected) {
                                            throw reflected.getCause();
                                        }
                                    });
                        }
                    }
                    if (faultPoint == FaultPoint.OUTBOX_AND_ROLLBACK_SQL
                            && "rollback".equals(method.getName())) {
                        throw new SQLException(
                                "dynamic rollback detail must stay hidden", "08006");
                    }
                    if (faultPoint == FaultPoint.ROLLBACK_RUNTIME
                            && "rollback".equals(method.getName())) {
                        throw new IllegalStateException(
                                "dynamic rollback runtime detail must stay hidden");
                    }
                    if (faultPoint == FaultPoint.FINALIZATION_UPDATE_AND_ROLLBACK
                            && "rollback".equals(method.getName())) {
                        throw new SQLException(
                                "dynamic receipt-rollback-sensitive rollback detail",
                                "08006");
                    }
                    if (faultPoint == FaultPoint.BEFORE_COMMIT
                            && "commit".equals(method.getName())) {
                        throw new SQLException("commit request failed", "08006");
                    }
                    if (faultPoint == FaultPoint.AFTER_COMMIT
                            && "commit".equals(method.getName())) {
                        delegate.commit();
                        throw new SQLException("commit acknowledgement lost", "08006");
                    }
                    if (faultPoint == FaultPoint.RUNTIME_AFTER_COMMIT
                            && "commit".equals(method.getName())) {
                        delegate.commit();
                        throw new IllegalStateException(
                                "dynamic runtime commit detail must stay hidden");
                    }
                    if ("setAutoCommit".equals(method.getName())
                            && arguments != null
                            && arguments.length == 1
                            && Boolean.TRUE.equals(arguments[0])) {
                        observation.restoreAutoCommitAttempted.set(true);
                        if (faultPoint == FaultPoint.RESTORE_AUTOCOMMIT_RUNTIME) {
                            throw new IllegalStateException(
                                    "dynamic auto-commit detail must stay hidden");
                        }
                    }
                    if ("close".equals(method.getName())) {
                        observation.closeAttempted.set(true);
                        if (faultPoint == FaultPoint.CLOSE_RUNTIME) {
                            delegate.close();
                            throw new IllegalStateException(
                                    "dynamic close detail must stay hidden");
                        }
                    }
                    if ("abort".equals(method.getName())) {
                        observation.abortAttempted.set(true);
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException reflected) {
                        throw reflected.getCause();
                    }
                });
    }

    private void applyMigration(String filename) throws Exception {
        Path[] candidates = {
                Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename)
        };
        Path migration = null;
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                migration = candidate;
                break;
            }
        }
        if (migration == null) {
            throw new IllegalStateException("migration file not found: " + filename);
        }
        String sql = Files.readString(migration, StandardCharsets.UTF_8);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static PGSimpleDataSource postgres(String currentSchema) {
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
        if (currentSchema != null) {
            postgres.setCurrentSchema(currentSchema);
        }
        return postgres;
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private enum FaultPoint {
        FAIL_IF_IDEMPOTENCY_READ,
        OUTBOX_AND_ROLLBACK_SQL,
        ROLLBACK_RUNTIME,
        BEFORE_COMMIT,
        AFTER_COMMIT,
        RUNTIME_AFTER_COMMIT,
        FINALIZATION_UPDATE_AND_ROLLBACK,
        RESTORE_AUTOCOMMIT_RUNTIME,
        CLOSE_RUNTIME
    }

    /** 记录代理连接是否进入可能改变事务结局的清理分支。 */
    private static final class FaultObservation {
        private final AtomicBoolean restoreAutoCommitAttempted = new AtomicBoolean();
        private final AtomicBoolean closeAttempted = new AtomicBoolean();
        private final AtomicBoolean abortAttempted = new AtomicBoolean();
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    /** 只覆盖连接创建的测试 DataSource，其余标准配置透明委托。 */
    private static class DelegatingDataSource implements DataSource {
        private final DataSource delegate;

        DelegatingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password)
                throws SQLException {
            return delegate.getConnection(username, password);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
