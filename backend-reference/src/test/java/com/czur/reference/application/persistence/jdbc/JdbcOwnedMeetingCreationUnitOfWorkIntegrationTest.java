package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandEventFactory;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.meeting.MeetingAggregate;
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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * owned 会议创建工作单元的真实 PostgreSQL 16 原子性门禁。
 *
 * <p>每个用例创建独立 schema 并执行 V001/V002/V003，验证并发竞争、唯一键
 * 回滚、严格过期、lease、版本栅栏、统一事实时间以及 commit 结果未知边界。
 * 测试只在显式提供 {@code REFERENCE_TEST_DATABASE_URL} 时运行，且结束后删除
 * 自己的 schema，不读写调用方 public schema。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcOwnedMeetingCreationUnitOfWorkIntegrationTest {
    private static final Instant RESERVE_AT = Instant.parse("2026-09-17T09:59:30Z");
    private static final Instant BIND_CLOCK =
            Instant.parse("2026-09-17T10:00:00.123456789Z");
    private static final Instant BIND_AT = BIND_CLOCK.truncatedTo(ChronoUnit.MICROS);
    private static final String DEPLOYMENT = "owned-uow-deployment";
    private static final String SUBJECT = "owned-uow-subject";

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createSchemaAndMigrate() throws Exception {
        adminDataSource = postgres(null);
        schema = "owned_uow_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        dataSource = postgres(schema);
        applyMigration("V001__initial_schema.sql");
        applyMigration("V002__outbox_lease_fencing.sql");
        applyMigration("V003__identity_entitlement.sql");
    }

    @AfterEach
    void dropSchema() throws Exception {
        if (adminDataSource == null || schema == null) {
            return;
        }
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schema) + " CASCADE");
        }
    }

    @Test
    void firstCreateReplayConflictAndAllFactTimesAreStable() throws Exception {
        UsageOperationResult reserve = insertReserve(
                "stable", "meeting-stable", BIND_AT.plusSeconds(60));
        var request = request(
                reserve, "meeting-stable", "session-stable", "meeting-key", "digest-a");
        var unitOfWork = unitOfWork(dataSource, BIND_CLOCK);

        var first = success(unitOfWork.create(request));
        assertEquals(OwnedMeetingCreationUnitOfWork.SuccessCode.CREATED, first.code());

        var replay = success(unitOfWork.create(request));
        assertEquals(OwnedMeetingCreationUnitOfWork.SuccessCode.REPLAYED, replay.code());
        assertEquals(first.result(), replay.result());

        var conflict = failure(unitOfWork.create(request(
                reserve, "meeting-stable", "session-stable", "meeting-key", "digest-b")));
        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.IDEMPOTENCY_CONFLICT,
                conflict.code());
        assertFalse(conflict.retryable());

        String scope = IdempotencyScopeCodec.meetingCreate(DEPLOYMENT, SUBJECT);
        String eventId = MeetingCommandEventFactory.meetingCreated(
                first.result().meeting(), BIND_AT).eventId();
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(BIND_AT, queryInstant(connection,
                    "SELECT bound_at FROM usage_reservation WHERE reservation_id = ?",
                    reserve.reservation().reservationId()));
            assertEquals(BIND_AT, queryInstant(connection,
                    "SELECT updated_at FROM usage_reservation WHERE reservation_id = ?",
                    reserve.reservation().reservationId()));
            assertEquals(BIND_AT, queryInstant(connection,
                    "SELECT created_at FROM meeting WHERE meeting_id = ?", "meeting-stable"));
            assertEquals(BIND_AT, queryInstant(connection,
                    "SELECT updated_at FROM meeting WHERE meeting_id = ?", "meeting-stable"));
            assertEquals(BIND_AT, queryInstant(connection, """
                    SELECT created_at FROM idempotency_record
                     WHERE subject_scope = ? AND idempotency_key = 'meeting-key'
                    """, scope));
            assertEquals(BIND_AT, queryInstant(connection,
                    "SELECT occurred_at FROM outbox_event WHERE event_id = ?", eventId));
            assertEquals(BIND_AT, queryInstant(connection,
                    "SELECT available_at FROM outbox_event WHERE event_id = ?", eventId));
            assertEquals(1, queryCount(connection,
                    "SELECT count(*) FROM meeting WHERE meeting_id = 'meeting-stable'"));
            assertEquals(1, queryCount(connection,
                    "SELECT count(*) FROM outbox_event WHERE event_id = '" + eventId + "'"));
        }
    }

    @Test
    void strictExpiryAndActiveLeaseReturnDifferentStableFailures() throws Exception {
        UsageOperationResult expiring = insertReserve(
                "expiry", "meeting-expiry", BIND_AT);
        var expired = failure(unitOfWork(dataSource, BIND_CLOCK).create(request(
                expiring, "meeting-expiry", "session-expiry", "key-expiry", "digest-expiry")));
        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.RESERVATION_EXPIRED,
                expired.code());
        assertFalse(expired.retryable());

        UsageOperationResult leased = insertReserve(
                "lease", "meeting-lease", BIND_AT.plusSeconds(60));
        setLease(leased.reservation().reservationId(), BIND_AT.plusSeconds(1));
        var activeLease = failure(unitOfWork(dataSource, BIND_CLOCK).create(request(
                leased, "meeting-lease", "session-lease", "key-lease", "digest-lease")));
        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.RESERVATION_STATE_CONFLICT,
                activeLease.code());
        assertTrue(activeLease.retryable());
        assertUnboundAndNoMeetingIdempotency(
                leased.reservation().reservationId(), "meeting-lease", "key-lease");
    }

    @Test
    void terminalBoundAndVersionFactsHaveExplicitClassifications() throws Exception {
        UsageOperationResult bound = insertReserve(
                "bound", "meeting-bound", BIND_AT.plusSeconds(60));
        try (Connection connection = transaction()) {
            assertTrue(JdbcUsageReservationStore.bindForOwnedMeeting(
                    connection, bound.reservation(), BIND_AT.minusSeconds(1)));
            // 两个外键均为延迟约束；同一事务补入匹配会议后即可提交已绑定场景。
            JdbcMeetingRepository.insertOwned(connection,
                    ownedSnapshot(bound, "session-existing", BIND_AT.minusSeconds(1)));
            connection.commit();
        }
        var boundFailure = failure(unitOfWork(dataSource, BIND_CLOCK).create(request(
                bound, "meeting-bound", "session-bound", "key-bound", "digest-bound")));
        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.RESERVATION_STATE_CONFLICT,
                boundFailure.code());

        UsageOperationResult versioned = insertReserve(
                "version", "meeting-version", BIND_AT.plusSeconds(60));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE usage_reservation DROP CONSTRAINT usage_reservation_v3_ck");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE usage_reservation SET version = 7
                      WHERE reservation_id = ?
                     """)) {
            statement.setString(1, versioned.reservation().reservationId());
            assertEquals(1, statement.executeUpdate());
        }
        var versionFailure = failure(unitOfWork(dataSource, BIND_CLOCK).create(request(
                versioned, "meeting-version", "session-version", "key-version", "digest-version")));
        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.VERSION_CONFLICT,
                versionFailure.code());
    }

    @Test
    void twoKeysCompetingForOneReservationLeaveOnlyWinnerFacts() throws Exception {
        UsageOperationResult reserve = insertReserve(
                "same-reservation", "meeting-race", BIND_AT.plusSeconds(60));
        var firstRequest = request(
                reserve, "meeting-race", "session-race", "key-race-a", "digest-race-a");
        var secondRequest = request(
                reserve, "meeting-race", "session-race", "key-race-b", "digest-race-b");
        var unitOfWork = unitOfWork(dataSource, BIND_CLOCK);
        CountDownLatch start = new CountDownLatch(1);

        List<OwnedMeetingCreationUnitOfWork.Result> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<OwnedMeetingCreationUnitOfWork.Result> first = executor.submit(() -> {
                start.await();
                return unitOfWork.create(firstRequest);
            });
            Future<OwnedMeetingCreationUnitOfWork.Result> second = executor.submit(() -> {
                start.await();
                return unitOfWork.create(secondRequest);
            });
            start.countDown();
            results.add(first.get());
            results.add(second.get());
        }

        assertEquals(1, results.stream()
                .filter(result -> result instanceof OwnedMeetingCreationUnitOfWork.Success success
                        && success.code() == OwnedMeetingCreationUnitOfWork.SuccessCode.CREATED)
                .count());
        assertEquals(1, results.stream()
                .filter(result -> result instanceof OwnedMeetingCreationUnitOfWork.Failure failure
                        && failure.code()
                        == OwnedMeetingCreationUnitOfWork.ErrorCode.RESERVATION_STATE_CONFLICT)
                .count());
        String scope = IdempotencyScopeCodec.meetingCreate(DEPLOYMENT, SUBJECT);
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(1, queryCount(connection, """
                    SELECT count(*) FROM idempotency_record
                     WHERE subject_scope = ?
                       AND idempotency_key IN ('key-race-a','key-race-b')
                    """, scope));
            assertEquals(1, queryCount(connection,
                    "SELECT count(*) FROM meeting WHERE meeting_id = 'meeting-race'"));
        }
    }

    @Test
    void sameKeyAndDigestConcurrentRequestsCreateOnceAndReplayOnce() throws Exception {
        UsageOperationResult reserve = insertReserve(
                "same-key", "meeting-same-key", BIND_AT.plusSeconds(60));
        var request = request(
                reserve, "meeting-same-key", "session-same-key",
                "key-same", "digest-same");
        var unitOfWork = unitOfWork(dataSource, BIND_CLOCK);
        CountDownLatch start = new CountDownLatch(1);

        List<OwnedMeetingCreationUnitOfWork.Result> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<OwnedMeetingCreationUnitOfWork.Result> first = executor.submit(() -> {
                start.await();
                return unitOfWork.create(request);
            });
            Future<OwnedMeetingCreationUnitOfWork.Result> second = executor.submit(() -> {
                start.await();
                return unitOfWork.create(request);
            });
            start.countDown();
            results.add(first.get());
            results.add(second.get());
        }

        assertEquals(1, results.stream()
                .filter(result -> result instanceof OwnedMeetingCreationUnitOfWork.Success success
                        && success.code() == OwnedMeetingCreationUnitOfWork.SuccessCode.CREATED)
                .count());
        assertEquals(1, results.stream()
                .filter(result -> result instanceof OwnedMeetingCreationUnitOfWork.Success success
                        && success.code() == OwnedMeetingCreationUnitOfWork.SuccessCode.REPLAYED)
                .count());
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(1, queryCount(connection,
                    "SELECT count(*) FROM meeting WHERE meeting_id = 'meeting-same-key'"));
            assertEquals(1, queryCount(connection,
                    "SELECT count(*) FROM outbox_event WHERE aggregate_id = 'meeting-same-key'"));
        }
    }

    @Test
    void meetingIdAndClientSessionConflictsRollbackReservationAndIdempotency() throws Exception {
        UsageOperationResult winner = insertReserve(
                "winner", "meeting-winner", BIND_AT.plusSeconds(60));
        success(unitOfWork(dataSource, BIND_CLOCK).create(request(
                winner, "meeting-winner", "shared-session", "key-winner", "digest-winner")));

        UsageOperationResult sameMeeting = insertReserve(
                "same-meeting", "meeting-winner", BIND_AT.plusSeconds(60));
        var meetingConflict = failure(unitOfWork(dataSource, BIND_CLOCK).create(request(
                sameMeeting, "meeting-winner", "other-session", "key-same-meeting",
                "digest-same-meeting")));
        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.MEETING_CONFLICT,
                meetingConflict.code());
        assertUnboundAndNoMeetingIdempotency(
                sameMeeting.reservation().reservationId(),
                "meeting-winner", "key-same-meeting", 1);

        UsageOperationResult sameSession = insertReserve(
                "same-session", "meeting-other", BIND_AT.plusSeconds(60));
        var sessionConflict = failure(unitOfWork(dataSource, BIND_CLOCK).create(request(
                sameSession, "meeting-other", "shared-session", "key-same-session",
                "digest-same-session")));
        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.MEETING_CONFLICT,
                sessionConflict.code());
        assertUnboundAndNoMeetingIdempotency(
                sameSession.reservation().reservationId(),
                "meeting-other", "key-same-session");
    }

    @Test
    void preCommitStorageFailureRollsBackAllCandidateFacts() throws Exception {
        UsageOperationResult reserve = insertReserve(
                "precommit", "meeting-precommit", BIND_AT.plusSeconds(60));
        DataSource failing = faultInjectingDataSource(
                dataSource, FaultPoint.BEFORE_MEETING_INSERT);

        var result = failure(unitOfWork(failing, BIND_CLOCK).create(request(
                reserve, "meeting-precommit", "session-precommit", "key-precommit",
                "digest-precommit")));

        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE,
                result.code());
        assertTrue(result.retryable());
        assertUnboundAndNoMeetingIdempotency(
                reserve.reservation().reservationId(),
                "meeting-precommit", "key-precommit");
    }

    @Test
    void rollbackSqlFailureClosesConnectionWithoutImplicitCommit() throws Exception {
        UsageOperationResult reserve = insertReserve(
                "rollback-sql", "meeting-rollback-sql", BIND_AT.plusSeconds(60));
        FaultObservation observation = new FaultObservation();
        DataSource failing = faultInjectingDataSource(
                dataSource, FaultPoint.OUTBOX_WRITE_AND_ROLLBACK_SQL, observation);

        var result = failure(unitOfWork(failing, BIND_CLOCK).create(request(
                reserve, "meeting-rollback-sql", "session-rollback-sql",
                "key-rollback-sql", "digest-rollback-sql")));

        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE,
                result.code());
        assertTrue(result.retryable());
        assertFalse(observation.restoreAutoCommitAttempted.get());
        assertTrue(observation.closeAttempted.get());
        assertUnboundAndNoMeetingIdempotency(
                reserve.reservation().reservationId(),
                "meeting-rollback-sql", "key-rollback-sql");
    }

    @Test
    void rollbackRuntimeFailureDoesNotEscapeOrChangeBusinessResult() throws Exception {
        UsageOperationResult reserve = insertReserve(
                "rollback-runtime", "meeting-rollback-runtime", BIND_AT.plusSeconds(60));
        deleteReserveFacts(reserve);
        FaultObservation observation = new FaultObservation();
        DataSource failing = faultInjectingDataSource(
                dataSource, FaultPoint.ROLLBACK_RUNTIME, observation);

        var result = failure(unitOfWork(failing, BIND_CLOCK).create(request(
                reserve, "meeting-rollback-runtime", "session-rollback-runtime",
                "key-rollback-runtime", "digest-rollback-runtime")));

        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.RESERVATION_NOT_FOUND,
                result.code());
        assertFalse(result.retryable());
        assertFalse(observation.restoreAutoCommitAttempted.get());
        assertTrue(observation.closeAttempted.get());
    }

    @Test
    void commitFailureAfterDatabaseCommitIsOutcomeUnknownAndCanBeReplayed() throws Exception {
        UsageOperationResult reserve = insertReserve(
                "commit-unknown", "meeting-commit-unknown", BIND_AT.plusSeconds(60));
        var request = request(
                reserve, "meeting-commit-unknown", "session-commit-unknown",
                "key-commit-unknown", "digest-commit-unknown");
        DataSource failing = faultInjectingDataSource(
                dataSource, FaultPoint.AFTER_COMMIT);

        var unknown = failure(unitOfWork(failing, BIND_CLOCK).create(request));

        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                unknown.code());
        assertTrue(unknown.retryable());
        assertTrue(new JdbcMeetingRepository(dataSource)
                .find("meeting-commit-unknown").isPresent());
        var replay = success(unitOfWork(dataSource, BIND_CLOCK).create(request));
        assertEquals(OwnedMeetingCreationUnitOfWork.SuccessCode.REPLAYED, replay.code());
    }

    @Test
    void commitFailureBeforeDatabaseCommitIsStillOutcomeUnknownButLeavesNoFacts()
            throws Exception {
        UsageOperationResult reserve = insertReserve(
                "commit-before", "meeting-commit-before", BIND_AT.plusSeconds(60));
        var request = request(
                reserve, "meeting-commit-before", "session-commit-before",
                "key-commit-before", "digest-commit-before");

        var unknown = failure(unitOfWork(
                faultInjectingDataSource(dataSource, FaultPoint.BEFORE_COMMIT),
                BIND_CLOCK).create(request));

        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                unknown.code());
        assertTrue(unknown.retryable());
        assertUnboundAndNoMeetingIdempotency(
                reserve.reservation().reservationId(),
                "meeting-commit-before", "key-commit-before");
    }

    @Test
    void runtimeFailureAfterDatabaseCommitIsOutcomeUnknownAndCanBeReplayed() throws Exception {
        UsageOperationResult reserve = insertReserve(
                "runtime-commit", "meeting-runtime-commit", BIND_AT.plusSeconds(60));
        var request = request(
                reserve, "meeting-runtime-commit", "session-runtime-commit",
                "key-runtime-commit", "digest-runtime-commit");

        var unknown = failure(unitOfWork(
                faultInjectingDataSource(dataSource, FaultPoint.RUNTIME_AFTER_COMMIT),
                BIND_CLOCK).create(request));

        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                unknown.code());
        assertTrue(unknown.retryable());
        assertTrue(new JdbcMeetingRepository(dataSource)
                .find("meeting-runtime-commit").isPresent());
        var replay = success(unitOfWork(dataSource, BIND_CLOCK).create(request));
        assertEquals(OwnedMeetingCreationUnitOfWork.SuccessCode.REPLAYED, replay.code());
    }

    @Test
    void restoreAutoCommitRuntimeFailureDoesNotOverrideCommittedSuccess() throws Exception {
        UsageOperationResult reserve = insertReserve(
                "restore-runtime", "meeting-restore-runtime", BIND_AT.plusSeconds(60));
        FaultObservation observation = new FaultObservation();
        DataSource failing = faultInjectingDataSource(
                dataSource, FaultPoint.RESTORE_AUTOCOMMIT_RUNTIME, observation);

        var result = success(unitOfWork(failing, BIND_CLOCK).create(request(
                reserve, "meeting-restore-runtime", "session-restore-runtime",
                "key-restore-runtime", "digest-restore-runtime")));

        assertEquals(OwnedMeetingCreationUnitOfWork.SuccessCode.CREATED, result.code());
        assertTrue(observation.restoreAutoCommitAttempted.get());
        assertTrue(observation.closeAttempted.get());
        assertTrue(new JdbcMeetingRepository(dataSource)
                .find("meeting-restore-runtime").isPresent());
    }

    @Test
    void closeRuntimeFailureDoesNotOverrideCommittedSuccess() throws Exception {
        UsageOperationResult reserve = insertReserve(
                "close-runtime", "meeting-close-runtime", BIND_AT.plusSeconds(60));
        FaultObservation observation = new FaultObservation();
        DataSource failing = faultInjectingDataSource(
                dataSource, FaultPoint.CLOSE_RUNTIME, observation);

        var result = success(unitOfWork(failing, BIND_CLOCK).create(request(
                reserve, "meeting-close-runtime", "session-close-runtime",
                "key-close-runtime", "digest-close-runtime")));

        assertEquals(OwnedMeetingCreationUnitOfWork.SuccessCode.CREATED, result.code());
        assertTrue(observation.restoreAutoCommitAttempted.get());
        assertTrue(observation.closeAttempted.get());
        assertTrue(new JdbcMeetingRepository(dataSource)
                .find("meeting-close-runtime").isPresent());
    }

    @Test
    void outboxConflictIsInternalAndRollsBackMeetingBindAndIdempotency() throws Exception {
        UsageOperationResult reserve = insertReserve(
                "outbox", "meeting-outbox", BIND_AT.plusSeconds(60));
        MeetingAggregate.MeetingSnapshot candidate = ownedSnapshot(
                reserve, "session-outbox", BIND_AT);
        var stableEvent = MeetingCommandEventFactory.meetingCreated(candidate, BIND_AT);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO outbox_event (
                         event_id, aggregate_type, aggregate_id, event_type, payload,
                         occurred_at, available_at, status, attempt_count)
                     VALUES (?, 'Meeting', ?, 'MeetingCreated', '{"different":true}'::jsonb,
                             ?, ?, 'PENDING', 0)
                     """)) {
            statement.setString(1, stableEvent.eventId());
            statement.setString(2, candidate.meetingId());
            statement.setTimestamp(3, Timestamp.from(BIND_AT));
            statement.setTimestamp(4, Timestamp.from(BIND_AT));
            assertEquals(1, statement.executeUpdate());
        }

        var result = failure(unitOfWork(dataSource, BIND_CLOCK).create(request(
                reserve, "meeting-outbox", "session-outbox", "key-outbox", "digest-outbox")));

        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.INTERNAL, result.code());
        assertUnboundAndNoMeetingIdempotency(
                reserve.reservation().reservationId(), "meeting-outbox", "key-outbox");
    }

    @Test
    void clockRuntimeFailureIsInternalAndRollsBackWithoutLeakingException() throws Exception {
        UsageOperationResult reserve = insertReserve(
                "clock", "meeting-clock", BIND_AT.plusSeconds(60));
        Clock failingClock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                throw new java.time.DateTimeException("dynamic clock detail");
            }
        };

        var result = failure(new JdbcOwnedMeetingCreationUnitOfWork(
                dataSource, failingClock).create(request(
                reserve, "meeting-clock", "session-clock", "key-clock", "digest-clock")));

        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.INTERNAL, result.code());
        assertUnboundAndNoMeetingIdempotency(
                reserve.reservation().reservationId(), "meeting-clock", "key-clock");
    }

    @Test
    void operationRelationReservationMismatchIsInternalAndNeverBinds() throws Exception {
        UsageOperationResult reserve = insertReserve(
                "relation", "meeting-relation", BIND_AT.plusSeconds(60));
        UsageOperationResult other = insertReserve(
                "relation-other", "meeting-relation-other", BIND_AT.plusSeconds(60));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE usage_operation DROP CONSTRAINT "
                    + "usage_operation_reservation_scope_fk");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE usage_operation SET reservation_id = ?
                      WHERE deployment_scope = ? AND subject_scope = ?
                        AND operation_scope = ? AND idempotency_key = ?
                     """)) {
            statement.setString(1, other.reservation().reservationId());
            statement.setString(2, reserve.deploymentScope());
            statement.setString(3, reserve.subjectScope());
            statement.setString(4, reserve.operationScope());
            statement.setString(5, reserve.idempotencyKey());
            assertEquals(1, statement.executeUpdate());
        }

        var mismatch = failure(unitOfWork(dataSource, BIND_CLOCK).create(request(
                reserve, "meeting-relation", "session-relation", "key-relation",
                "digest-relation")));
        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.INTERNAL, mismatch.code());
        assertUnboundAndNoMeetingIdempotency(
                reserve.reservation().reservationId(),
                "meeting-relation", "key-relation");
    }

    private JdbcOwnedMeetingCreationUnitOfWork unitOfWork(
            DataSource source, Instant clockInstant) {
        return new JdbcOwnedMeetingCreationUnitOfWork(
                source, Clock.fixed(clockInstant, ZoneOffset.UTC));
    }

    private UsageOperationResult insertReserve(
            String suffix,
            String meetingId,
            Instant expiresAt) throws SQLException {
        MeetingGrant grant = new MeetingGrant(
                "grant-" + suffix,
                DEPLOYMENT,
                SUBJECT,
                "profile-owned",
                7,
                Set.of("transcript.realtime"),
                Set.of("transcript.realtime"),
                3_600_000,
                RESERVE_AT.minusSeconds(30),
                expiresAt.plusSeconds(60));
        UsageReservation reservation = UsageReservation.reserved(
                "reservation-" + suffix,
                grant,
                meetingId,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                RESERVE_AT,
                expiresAt);
        UsageOperationResult result = UsageOperationResult.reserve(
                DEPLOYMENT,
                SUBJECT,
                "reserve-key-" + suffix,
                "reserve-digest-" + suffix,
                grant.expiresAt(),
                AuthorizedProfileSnapshot.from(grant),
                reservation);
        try (Connection connection = transaction()) {
            JdbcUsageReservationStore.insertReservation(connection, reservation);
            assertTrue(JdbcUsageReservationStore.insertOperation(
                    connection, result, RESERVE_AT));
            connection.commit();
        }
        return result;
    }

    private static OwnedMeetingCreationUnitOfWork.CreateRequest request(
            UsageOperationResult reserve,
            String meetingId,
            String clientSessionId,
            String idempotencyKey,
            String digest) {
        return new OwnedMeetingCreationUnitOfWork.CreateRequest(
                DEPLOYMENT,
                SUBJECT,
                meetingId,
                clientSessionId,
                idempotencyKey,
                digest,
                reserve);
    }

    private static OwnedMeetingCreationUnitOfWork.Success success(
            OwnedMeetingCreationUnitOfWork.Result result) {
        assertTrue(result instanceof OwnedMeetingCreationUnitOfWork.Success,
                () -> "expected success but got " + result);
        return (OwnedMeetingCreationUnitOfWork.Success) result;
    }

    private static OwnedMeetingCreationUnitOfWork.Failure failure(
            OwnedMeetingCreationUnitOfWork.Result result) {
        assertTrue(result instanceof OwnedMeetingCreationUnitOfWork.Failure,
                () -> "expected failure but got " + result);
        return (OwnedMeetingCreationUnitOfWork.Failure) result;
    }

    private MeetingAggregate.MeetingSnapshot ownedSnapshot(
            UsageOperationResult reserve,
            String clientSession,
            Instant createdAt) {
        return MeetingAggregate.MeetingSnapshot.owned(
                reserve.reservation().expectedMeetingId(),
                DEPLOYMENT,
                clientSession,
                SUBJECT,
                reserve.reservation().grantId(),
                reserve.grantExpiresAt(),
                reserve.reservation().reservationId(),
                reserve.requireAuthorizedProfileSnapshot().profileId(),
                reserve.requireAuthorizedProfileSnapshot().profileRevision(),
                com.czur.reference.domain.meeting.MeetingState.CREATED,
                createdAt,
                createdAt);
    }

    private void setLease(String reservationId, Instant leaseUntil) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE usage_reservation
                        SET lease_owner = 'test-worker', lease_token = 'test-token',
                            lease_until = ?, lease_version = 1
                      WHERE reservation_id = ?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(leaseUntil));
            statement.setString(2, reservationId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void deleteReserveFacts(UsageOperationResult reserve) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement operation = connection.prepareStatement("""
                     DELETE FROM usage_operation
                      WHERE deployment_scope = ? AND subject_scope = ?
                        AND operation_scope = ? AND idempotency_key = ?
                     """);
             PreparedStatement reservation = connection.prepareStatement("""
                     DELETE FROM usage_reservation
                      WHERE deployment_scope = ? AND subject_scope = ?
                        AND reservation_id = ?
                     """)) {
            operation.setString(1, reserve.deploymentScope());
            operation.setString(2, reserve.subjectScope());
            operation.setString(3, reserve.operationScope());
            operation.setString(4, reserve.idempotencyKey());
            assertEquals(1, operation.executeUpdate());
            reservation.setString(1, reserve.deploymentScope());
            reservation.setString(2, reserve.subjectScope());
            reservation.setString(3, reserve.reservation().reservationId());
            assertEquals(1, reservation.executeUpdate());
        }
    }

    private void assertUnboundAndNoMeetingIdempotency(
            String reservationId,
            String meetingId,
            String idempotencyKey) throws SQLException {
        assertUnboundAndNoMeetingIdempotency(
                reservationId, meetingId, idempotencyKey, 0);
    }

    private void assertUnboundAndNoMeetingIdempotency(
            String reservationId,
            String meetingId,
            String idempotencyKey,
            int expectedMeetingCount) throws SQLException {
        String scope = IdempotencyScopeCodec.meetingCreate(DEPLOYMENT, SUBJECT);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement reservation = connection.prepareStatement("""
                     SELECT bound_meeting_id, bound_at, version
                       FROM usage_reservation WHERE reservation_id = ?
                     """);
             PreparedStatement meeting = connection.prepareStatement(
                     "SELECT count(*) FROM meeting WHERE meeting_id = ?");
             PreparedStatement idempotency = connection.prepareStatement("""
                     SELECT count(*) FROM idempotency_record
                      WHERE subject_scope = ? AND idempotency_key = ?
                     """)) {
            reservation.setString(1, reservationId);
            try (var rows = reservation.executeQuery()) {
                assertTrue(rows.next());
                assertNull(rows.getString("bound_meeting_id"));
                assertNull(rows.getTimestamp("bound_at"));
                assertEquals(0, rows.getLong("version"));
            }
            meeting.setString(1, meetingId);
            try (var rows = meeting.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(expectedMeetingCount, rows.getInt(1));
            }
            idempotency.setString(1, scope);
            idempotency.setString(2, idempotencyKey);
            try (var rows = idempotency.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
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

    private static int queryCount(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static int queryCount(
            Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static DataSource faultInjectingDataSource(
            DataSource delegate, FaultPoint faultPoint) {
        return faultInjectingDataSource(delegate, faultPoint, new FaultObservation());
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
                    if (faultPoint == FaultPoint.BEFORE_MEETING_INSERT
                            && "prepareStatement".equals(method.getName())
                            && arguments != null
                            && arguments.length > 0
                            && arguments[0] instanceof String sql
                            && sql.contains("INSERT INTO meeting")) {
                        throw new SQLException("dynamic driver detail must stay hidden", "08006");
                    }
                    if (faultPoint == FaultPoint.OUTBOX_WRITE_AND_ROLLBACK_SQL
                            && "prepareStatement".equals(method.getName())
                            && arguments != null
                            && arguments.length > 0
                            && arguments[0] instanceof String sql
                            && sql.contains("INSERT INTO outbox_event")) {
                        throw new SQLException(
                                "dynamic outbox detail must stay hidden", "08006");
                    }
                    if (faultPoint == FaultPoint.OUTBOX_WRITE_AND_ROLLBACK_SQL
                            && "rollback".equals(method.getName())) {
                        throw new SQLException(
                                "dynamic rollback detail must stay hidden", "08006");
                    }
                    if (faultPoint == FaultPoint.ROLLBACK_RUNTIME
                            && "rollback".equals(method.getName())) {
                        throw new IllegalStateException(
                                "dynamic rollback runtime detail must stay hidden");
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
        BEFORE_MEETING_INSERT,
        OUTBOX_WRITE_AND_ROLLBACK_SQL,
        ROLLBACK_RUNTIME,
        BEFORE_COMMIT,
        AFTER_COMMIT,
        RUNTIME_AFTER_COMMIT,
        RESTORE_AUTOCOMMIT_RUNTIME,
        CLOSE_RUNTIME
    }

    /** 记录代理连接是否进入了可能改变事务结局的清理分支。 */
    private static final class FaultObservation {
        private final AtomicBoolean restoreAutoCommitAttempted = new AtomicBoolean();
        private final AtomicBoolean closeAttempted = new AtomicBoolean();
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
        public Connection getConnection(String username, String password) throws SQLException {
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
