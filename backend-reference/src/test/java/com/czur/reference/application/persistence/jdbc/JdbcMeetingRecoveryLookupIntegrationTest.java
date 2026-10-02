package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.MeetingCreateFailure;
import com.czur.reference.application.port.MeetingCreateOutcome;
import com.czur.reference.application.port.MeetingCreateOutcomeStore;
import com.czur.reference.application.port.MeetingRecoveryLookup;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.meeting.MeetingAggregate;
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
import java.sql.SQLTransientException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会议创建恢复查询的真实 PostgreSQL 16 只读事实门禁。
 *
 * <p>每个用例在独立 schema 中执行 V001/V002/V003，因此既覆盖 JDBC
 * 映射与真实约束，又不读写调用方 public schema。测试会核对查询前后
 * 所有业务表行数，防止 Recovery 通过取消、补写或修复掩盖矛盾。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcMeetingRecoveryLookupIntegrationTest {
    private static final Instant RESERVE_AT = Instant.parse("2026-09-18T02:00:00Z");
    private static final Instant CREATE_AT = Instant.parse("2026-09-18T02:00:30.123456Z");
    private static final String DEPLOYMENT = "recovery-deployment";
    private static final String SUBJECT = "recovery-subject";

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createSchemaAndMigrate() throws Exception {
        adminDataSource = postgres(null);
        schema = "meeting_recovery_" + UUID.randomUUID().toString().replace("-", "");
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
    void successReplaysOriginalCreatedOutcomeAcrossAllowedCurrentStates() throws Exception {
        CreatedScenario scenario = createMeeting("states");
        for (MeetingState state : MeetingState.values()) {
            if (state == MeetingState.UNKNOWN) {
                continue;
            }
            update("UPDATE meeting SET state=?, updated_at=?, "
                            + "manifest_state=CASE WHEN ?='DELETED' THEN 'DELETED' "
                            + "ELSE 'NOT_STARTED' END, "
                            + "deleted_at=CASE WHEN ?='DELETED' THEN ?::timestamptz ELSE NULL END "
                            + "WHERE meeting_id=?",
                    state.name(), CREATE_AT.plusSeconds(state.ordinal() + 1),
                    state.name(), state.name(), CREATE_AT.plusSeconds(state.ordinal() + 1),
                    scenario.request().meetingId());
            TableCounts before = tableCounts();

            MeetingRecoveryLookup.Replayed replayed = assertInstanceOf(
                    MeetingRecoveryLookup.Replayed.class,
                    lookup().lookup(scenario.request()));

            assertEquals(scenario.outcome(), replayed.outcome());
            MeetingCreateOutcome.Success original = assertInstanceOf(
                    MeetingCreateOutcome.Success.class, replayed.outcome());
            assertEquals(MeetingState.CREATED, original.result().meeting().state(),
                    "必须返回原始创建快照，不能用当前状态重建");
            assertEquals(before, tableCounts());
        }

        // 故意构造损坏的旧状态；当前 V001 物理约束会先拒绝，需要显式移除。
        execute("ALTER TABLE meeting DROP CONSTRAINT meeting_state_ck");
        execute("ALTER TABLE meeting DROP CONSTRAINT meeting_deleted_at_equivalence_ck");
        update("UPDATE meeting SET state=?, updated_at=?, "
                        + "manifest_state='NOT_STARTED', deleted_at=NULL WHERE meeting_id=?",
                MeetingState.UNKNOWN.name(), CREATE_AT.plusSeconds(120),
                scenario.request().meetingId());
        TableCounts before = tableCounts();
        assertReason(MeetingRecoveryLookup.InconsistencyReason.SUCCESS_OUTCOME_FACT_MISMATCH,
                lookup().lookup(scenario.request()));
        assertEquals(before, tableCounts());
    }

    @Test
    void successAllowsCommittedReservationAndRemainsStableAfterAdapterRestart()
            throws Exception {
        CreatedScenario scenario = createMeeting("committed");
        update("""
                UPDATE usage_reservation
                   SET status='COMMITTED', actual_amount=45000, committed_at=?,
                       updated_at=?, version=2
                 WHERE reservation_id=?
                """, CREATE_AT.plusSeconds(60), CREATE_AT.plusSeconds(60),
                scenario.request().reserveResult().reservation().reservationId());
        TableCounts before = tableCounts();

        MeetingRecoveryLookup.Replayed first = assertInstanceOf(
                MeetingRecoveryLookup.Replayed.class,
                lookup().lookup(scenario.request()));
        MeetingRecoveryLookup.Replayed restarted = assertInstanceOf(
                MeetingRecoveryLookup.Replayed.class,
                new JdbcMeetingRecoveryLookup(dataSource).lookup(scenario.request()));

        assertEquals(scenario.outcome(), first.outcome());
        assertEquals(first.outcome(), restarted.outcome());
        assertEquals(before, tableCounts());
    }

    @Test
    void failureReplaysWithInitialOrLaterCancelledUnboundReservation() throws Exception {
        FailureScenario scenario = createFailure("failure");
        assertEquals(scenario.outcome(), replayed(scenario.request()).outcome());

        update("""
                UPDATE usage_reservation
                   SET status='CANCELLED', cancelled_at=?, updated_at=?,
                       cancellation_reason_code='CREATE_FAILED', version=1
                 WHERE reservation_id=?
                """, CREATE_AT, CREATE_AT,
                scenario.request().reserveResult().reservation().reservationId());
        TableCounts before = tableCounts();

        assertEquals(scenario.outcome(), replayed(scenario.request()).outcome());
        assertEquals(before, tableCounts());
    }

    @Test
    void failureRejectsMeetingAndBoundReservationFacts() throws Exception {
        CreatedScenario withMeeting = createMeeting("failure-meeting");
        replaceOutcomeWithFailure(withMeeting.request());
        assertReason(MeetingRecoveryLookup.InconsistencyReason.FAILURE_OUTCOME_WITH_MEETING,
                lookup().lookup(withMeeting.request()));

        CreatedScenario boundOnly = createMeeting("failure-bound");
        replaceOutcomeWithFailure(boundOnly.request());
        // 仅在本隔离 schema 移除外键，人为构造“只有已绑定 Reservation”
        // 的已损坏事实，验证更精确的失败分类。
        execute("ALTER TABLE usage_reservation DROP CONSTRAINT "
                + "usage_reservation_bound_meeting_fk");
        update("DELETE FROM meeting WHERE meeting_id=?", boundOnly.request().meetingId());

        assertReason(
                MeetingRecoveryLookup.InconsistencyReason
                        .FAILURE_OUTCOME_WITH_BOUND_RESERVATION,
                lookup().lookup(boundOnly.request()));
    }

    @Test
    void missingOutcomeReturnsNotFoundOnlyForExactInitialReservation() throws Exception {
        OwnedMeetingCreationUnitOfWork.CreateRequest request = initialRequest("not-found");
        TableCounts before = tableCounts();

        assertInstanceOf(MeetingRecoveryLookup.NotFound.class, lookup().lookup(request));
        assertEquals(before, tableCounts());

        update("UPDATE usage_reservation SET reserved_amount=30000 WHERE reservation_id=?",
                request.reserveResult().reservation().reservationId());
        assertReason(MeetingRecoveryLookup.InconsistencyReason.RESERVATION_FACT_MISMATCH,
                lookup().lookup(request));

        OwnedMeetingCreationUnitOfWork.CreateRequest missing =
                initialRequest("not-found-missing");
        deleteReserveFacts(missing.reserveResult());
        assertReason(MeetingRecoveryLookup.InconsistencyReason.RESERVATION_FACT_MISMATCH,
                lookup().lookup(missing));

        OwnedMeetingCreationUnitOfWork.CreateRequest cancelled =
                initialRequest("not-found-cancelled");
        update("""
                UPDATE usage_reservation
                   SET status='CANCELLED', cancelled_at=?, updated_at=?,
                       cancellation_reason_code='CREATE_FAILED', version=1
                 WHERE reservation_id=?
                """, CREATE_AT, CREATE_AT,
                cancelled.reserveResult().reservation().reservationId());
        assertReason(MeetingRecoveryLookup.InconsistencyReason.RESERVATION_FACT_MISMATCH,
                lookup().lookup(cancelled));

        OwnedMeetingCreationUnitOfWork.CreateRequest committed =
                initialRequest("not-found-committed");
        execute("ALTER TABLE usage_reservation DROP CONSTRAINT "
                + "usage_reservation_bound_meeting_fk");
        update("""
                UPDATE usage_reservation
                   SET status='COMMITTED', bound_meeting_id=expected_meeting_id,
                       bound_at=?, actual_amount=45000, committed_at=?,
                       updated_at=?, version=2
                 WHERE reservation_id=?
                """, CREATE_AT, CREATE_AT.plusSeconds(1), CREATE_AT.plusSeconds(1),
                committed.reserveResult().reservation().reservationId());
        assertReason(
                MeetingRecoveryLookup.InconsistencyReason
                        .OUTCOME_MISSING_WITH_BOUND_RESERVATION,
                lookup().lookup(committed));
    }

    @Test
    void missingOutcomeWithCommittedCreationFactsIsNeverReportedAsNotFound()
            throws Exception {
        CreatedScenario scenario = createMeeting("missing-outcome");
        deleteMeetingOutcome(scenario.request());
        TableCounts before = tableCounts();

        assertReason(MeetingRecoveryLookup.InconsistencyReason.OUTCOME_MISSING_WITH_MEETING,
                lookup().lookup(scenario.request()));
        assertEquals(before, tableCounts());

        execute("ALTER TABLE usage_reservation DROP CONSTRAINT "
                + "usage_reservation_bound_meeting_fk");
        update("DELETE FROM meeting WHERE meeting_id=?", scenario.request().meetingId());
        TableCounts boundOnly = tableCounts();
        assertReason(
                MeetingRecoveryLookup.InconsistencyReason
                        .OUTCOME_MISSING_WITH_BOUND_RESERVATION,
                lookup().lookup(scenario.request()));
        assertEquals(boundOnly, tableCounts());
    }

    @Test
    void secondReadObservesSuccessCommittedAfterFirstNotFound() throws Exception {
        OwnedMeetingCreationUnitOfWork.CreateRequest request = initialRequest("second-read");
        AtomicBoolean committedBetweenFactReads = new AtomicBoolean();
        DataSource controlled = commitBeforeScopeSessionQuery(dataSource, () -> {
            OwnedMeetingCreationUnitOfWork.Success created = assertInstanceOf(
                    OwnedMeetingCreationUnitOfWork.Success.class,
                    createUnitOfWork().create(request));
            assertEquals(OwnedMeetingCreationUnitOfWork.SuccessCode.CREATED, created.code());
            committedBetweenFactReads.set(true);
        });

        MeetingRecoveryLookup.Replayed replayed = assertInstanceOf(
                MeetingRecoveryLookup.Replayed.class,
                new JdbcMeetingRecoveryLookup(controlled).lookup(request));

        assertTrue(committedBetweenFactReads.get(),
                "创建事务必须在 meetingId 读空后、scope/session 查询前提交");
        assertEquals(request.meetingId(),
                ((MeetingCreateOutcome.Success) replayed.outcome())
                        .result().meeting().meetingId());
    }

    @Test
    void twoMeetingIdentityQueriesMustResolveToTheSameMeeting() throws Exception {
        CreatedScenario scenario = createMeeting("identity-conflict");
        update("UPDATE meeting SET client_session_id=? WHERE meeting_id=?",
                "other-session", scenario.request().meetingId());
        insertLegacyMeeting("other-meeting", scenario.request().clientSessionId());

        assertReason(MeetingRecoveryLookup.InconsistencyReason.SUCCESS_OUTCOME_FACT_MISMATCH,
                lookup().lookup(scenario.request()));
    }

    @Test
    void outcomeConflictWinsBeforeBodyDecodeAndSameDigestDamageIsInconsistent()
            throws Exception {
        CreatedScenario scenario = createMeeting("outcome-order");
        OwnedMeetingCreationUnitOfWork.CreateRequest differentDigest =
                new OwnedMeetingCreationUnitOfWork.CreateRequest(
                        scenario.request().deploymentScope(),
                        scenario.request().subjectScope(),
                        scenario.request().meetingId(),
                        scenario.request().clientSessionId(),
                        scenario.request().idempotencyKey(),
                        "different-digest",
                        scenario.request().reserveResult());
        update("""
                UPDATE idempotency_record SET response_body='{"schemaVersion":99}'::jsonb
                 WHERE subject_scope=? AND idempotency_key=?
                """, IdempotencyScopeCodec.meetingCreate(DEPLOYMENT, SUBJECT),
                scenario.request().idempotencyKey());

        assertInstanceOf(MeetingRecoveryLookup.Conflict.class,
                lookup().lookup(differentDigest));
        assertReason(MeetingRecoveryLookup.InconsistencyReason.STORED_OUTCOME_INVALID,
                lookup().lookup(scenario.request()));
    }

    @Test
    void storedSuccessMustMatchMeetingReservationGrantAndProfileFacts() throws Exception {
        for (String changedField : List.of(
                "meetingId", "profileRevision", "grantExpiresAt", "reservationId")) {
            CreatedScenario scenario = createMeeting("outcome-" + changedField);
            MeetingAggregate.MeetingSnapshot original =
                    ((MeetingCreateOutcome.Success) scenario.outcome())
                            .result().meeting();
            replaceSuccessOutcome(
                    scenario.request(), changedCreatedSnapshot(original, changedField));

            assertReason(
                    MeetingRecoveryLookup.InconsistencyReason
                            .SUCCESS_OUTCOME_FACT_MISMATCH,
                    lookup().lookup(scenario.request()));
        }
    }

    @Test
    void successDetectsOwnershipProfileGrantReservationBoundAndInitialFactDrift()
            throws Exception {
        assertSuccessMutationReason("subject", "UPDATE meeting SET subject_scope='other' "
                        + "WHERE meeting_id=?",
                MeetingRecoveryLookup.InconsistencyReason.SUCCESS_OUTCOME_FACT_MISMATCH);
        assertSuccessMutationReason("profile", "UPDATE meeting SET profile_id='other' "
                        + "WHERE meeting_id=?",
                MeetingRecoveryLookup.InconsistencyReason.SUCCESS_OUTCOME_FACT_MISMATCH);
        assertSuccessMutationReason("grant", "UPDATE meeting SET grant_id='other' "
                        + "WHERE meeting_id=?",
                MeetingRecoveryLookup.InconsistencyReason.SUCCESS_OUTCOME_FACT_MISMATCH);
        assertSuccessMutationReason("meeting-reservation",
                "UPDATE meeting SET usage_reservation_id=NULL WHERE meeting_id=?",
                MeetingRecoveryLookup.InconsistencyReason.SUCCESS_OUTCOME_FACT_MISMATCH);
        assertSuccessMutationReason("legacy-shape",
                "UPDATE meeting SET subject_scope='LEGACY_UNOWNED', grant_id=NULL, "
                        + "grant_expires_at=NULL, usage_reservation_id=NULL WHERE meeting_id=?",
                MeetingRecoveryLookup.InconsistencyReason.SUCCESS_OUTCOME_FACT_MISMATCH);
        assertReservationMutationReason("reservation-grant",
                "UPDATE usage_reservation SET grant_id='other' WHERE reservation_id=?",
                MeetingRecoveryLookup.InconsistencyReason.RESERVATION_FACT_MISMATCH);
        assertReservationMutationReason("bound-time",
                "UPDATE usage_reservation SET bound_at=bound_at + interval '1 second' "
                        + "WHERE reservation_id=?",
                MeetingRecoveryLookup.InconsistencyReason.SUCCESS_OUTCOME_FACT_MISMATCH);
        assertReservationMutationReason("reserved-amount",
                "UPDATE usage_reservation SET reserved_amount=30000 WHERE reservation_id=?",
                MeetingRecoveryLookup.InconsistencyReason.RESERVATION_FACT_MISMATCH);
    }

    @Test
    void completeScopePreventsReadingSameReservationIdentityFromAnotherOwner()
            throws Exception {
        UsageOperationResult source = reserveResult(
                "cross-scope", "meeting-cross-scope", DEPLOYMENT, SUBJECT);
        OwnedMeetingCreationUnitOfWork.CreateRequest request = request(
                source, "session-cross-scope", "key-cross-scope", "digest-cross-scope");
        UsageOperationResult other = reserveResult(
                "cross-scope", "meeting-cross-scope", "other-deployment", "other-subject");
        try (Connection connection = dataSource.getConnection()) {
            JdbcUsageReservationStore.insertReservation(connection, other.reservation());
        }

        assertReason(MeetingRecoveryLookup.InconsistencyReason.RESERVATION_FACT_MISMATCH,
                lookup().lookup(request));
    }

    @Test
    void disconnectedDatabaseIsRetryableButWrongSchemaIsNot() {
        OwnedMeetingCreationUnitOfWork.CreateRequest request = detachedRequest("unavailable");
        PGSimpleDataSource disconnected = new PGSimpleDataSource();
        disconnected.setUrl("jdbc:postgresql://127.0.0.1:1/reference?connectTimeout=1");
        disconnected.setUser("reference");
        disconnected.setPassword("reference");

        MeetingRecoveryLookup.Unavailable transientFailure = assertInstanceOf(
                MeetingRecoveryLookup.Unavailable.class,
                new JdbcMeetingRecoveryLookup(disconnected).lookup(request));
        assertTrue(transientFailure.retryable());

        MeetingRecoveryLookup.Unavailable schemaFailure = assertInstanceOf(
                MeetingRecoveryLookup.Unavailable.class,
                new JdbcMeetingRecoveryLookup(
                        postgres("missing_schema_" + UUID.randomUUID())).lookup(request));
        assertFalse(schemaFailure.retryable());
    }

    @Test
    void currentFactSqlFailureUsesTheSameRetryabilityClassification() throws Exception {
        CreatedScenario scenario = createMeeting("fact-read-failure");
        MeetingRecoveryLookup.Unavailable transientFailure = assertInstanceOf(
                MeetingRecoveryLookup.Unavailable.class,
                new JdbcMeetingRecoveryLookup(failMeetingFactRead(
                        dataSource,
                        new SQLTransientException(
                                "dynamic detail must stay hidden", "08006")))
                        .lookup(scenario.request()));
        assertTrue(transientFailure.retryable());

        MeetingRecoveryLookup.Unavailable permanentFailure = assertInstanceOf(
                MeetingRecoveryLookup.Unavailable.class,
                new JdbcMeetingRecoveryLookup(failMeetingFactRead(
                        dataSource,
                        new SQLException(
                                "dynamic detail must stay hidden", "42P01")))
                        .lookup(scenario.request()));
        assertFalse(permanentFailure.retryable());
    }

    private void assertSuccessMutationReason(
            String suffix,
            String sql,
            MeetingRecoveryLookup.InconsistencyReason reason) throws Exception {
        CreatedScenario scenario = createMeeting("drift-" + suffix);
        update(sql, scenario.request().meetingId());
        assertReason(reason, lookup().lookup(scenario.request()));
    }

    private void assertReservationMutationReason(
            String suffix,
            String sql,
            MeetingRecoveryLookup.InconsistencyReason reason) throws Exception {
        CreatedScenario scenario = createMeeting("drift-" + suffix);
        update(sql, scenario.request().reserveResult().reservation().reservationId());
        assertReason(reason, lookup().lookup(scenario.request()));
    }

    private CreatedScenario createMeeting(String suffix) throws Exception {
        OwnedMeetingCreationUnitOfWork.CreateRequest request = initialRequest(suffix);
        OwnedMeetingCreationUnitOfWork.Success created = assertInstanceOf(
                OwnedMeetingCreationUnitOfWork.Success.class,
                createUnitOfWork().create(request));
        MeetingCreateOutcome outcome = MeetingCreateOutcome.success(created.result());
        return new CreatedScenario(request, outcome);
    }

    private FailureScenario createFailure(String suffix) throws Exception {
        OwnedMeetingCreationUnitOfWork.CreateRequest request = initialRequest(suffix);
        MeetingCreateOutcome outcome = MeetingCreateOutcome.failure(
                new MeetingCreateFailure(
                        OwnedMeetingCreationUnitOfWork.ErrorCode.RESERVATION_EXPIRED,
                        false));
        MeetingCreateOutcomeStore.OutcomeRecord record =
                new MeetingCreateOutcomeStore.OutcomeRecord(
                        IdempotencyScopeCodec.meetingCreate(DEPLOYMENT, SUBJECT),
                        request.idempotencyKey(),
                        request.canonicalRequestDigest(),
                        outcome);
        assertEquals(MeetingCreateOutcomeStore.ReservationStatus.ACQUIRED,
                new JdbcMeetingCreateOutcomeStore(
                        dataSource, Clock.fixed(CREATE_AT, ZoneOffset.UTC))
                        .reserve(record).status());
        return new FailureScenario(request, outcome);
    }

    private OwnedMeetingCreationUnitOfWork.CreateRequest initialRequest(String suffix)
            throws SQLException {
        UsageOperationResult reserve = reserveResult(
                suffix, "meeting-" + suffix, DEPLOYMENT, SUBJECT);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcUsageReservationStore.insertReservation(connection, reserve.reservation());
            assertTrue(JdbcUsageReservationStore.insertOperation(
                    connection, reserve, RESERVE_AT));
            connection.commit();
        }
        return request(
                reserve,
                "session-" + suffix,
                "meeting-key-" + suffix,
                "meeting-digest-" + suffix);
    }

    private static OwnedMeetingCreationUnitOfWork.CreateRequest detachedRequest(String suffix) {
        UsageOperationResult reserve = reserveResult(
                suffix, "meeting-" + suffix, DEPLOYMENT, SUBJECT);
        return request(
                reserve,
                "session-" + suffix,
                "meeting-key-" + suffix,
                "meeting-digest-" + suffix);
    }

    private static UsageOperationResult reserveResult(
            String suffix,
            String meetingId,
            String deploymentScope,
            String subjectScope) {
        MeetingGrant grant = new MeetingGrant(
                "grant-" + suffix,
                deploymentScope,
                subjectScope,
                "profile-recovery",
                3,
                Set.of("transcript.realtime"),
                Set.of("transcript.realtime"),
                3_600_000,
                RESERVE_AT.minusSeconds(30),
                CREATE_AT.plusSeconds(600));
        UsageReservation reservation = UsageReservation.reserved(
                "reservation-" + suffix,
                grant,
                meetingId,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                RESERVE_AT,
                CREATE_AT.plusSeconds(300));
        return UsageOperationResult.reserve(
                deploymentScope,
                subjectScope,
                "reserve-key-" + suffix,
                "reserve-digest-" + suffix,
                grant.expiresAt(),
                AuthorizedProfileSnapshot.from(grant),
                reservation);
    }

    private static OwnedMeetingCreationUnitOfWork.CreateRequest request(
            UsageOperationResult reserve,
            String session,
            String key,
            String digest) {
        return new OwnedMeetingCreationUnitOfWork.CreateRequest(
                reserve.deploymentScope(),
                reserve.subjectScope(),
                reserve.reservation().expectedMeetingId(),
                session,
                key,
                digest,
                reserve);
    }

    private JdbcOwnedMeetingCreationUnitOfWork createUnitOfWork() {
        return new JdbcOwnedMeetingCreationUnitOfWork(
                dataSource, Clock.fixed(CREATE_AT, ZoneOffset.UTC));
    }

    private JdbcMeetingRecoveryLookup lookup() {
        return new JdbcMeetingRecoveryLookup(dataSource);
    }

    private MeetingRecoveryLookup.Replayed replayed(
            OwnedMeetingCreationUnitOfWork.CreateRequest request) {
        return assertInstanceOf(
                MeetingRecoveryLookup.Replayed.class, lookup().lookup(request));
    }

    private static void assertReason(
            MeetingRecoveryLookup.InconsistencyReason expected,
            MeetingRecoveryLookup.Result result) {
        assertEquals(expected,
                assertInstanceOf(MeetingRecoveryLookup.Inconsistent.class, result).reason());
    }

    private void replaceOutcomeWithFailure(
            OwnedMeetingCreationUnitOfWork.CreateRequest request) throws SQLException {
        MeetingCreateOutcome failure = MeetingCreateOutcome.failure(
                new MeetingCreateFailure(
                        OwnedMeetingCreationUnitOfWork.ErrorCode.RESERVATION_EXPIRED,
                        false));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE idempotency_record
                        SET response_status=409, response_body=?::jsonb
                      WHERE subject_scope=? AND idempotency_key=?
                     """)) {
            statement.setString(1, MeetingCreateOutcomeJson.encode(failure));
            statement.setString(2, IdempotencyScopeCodec.meetingCreate(
                    request.deploymentScope(), request.subjectScope()));
            statement.setString(3, request.idempotencyKey());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void replaceSuccessOutcome(
            OwnedMeetingCreationUnitOfWork.CreateRequest request,
            MeetingAggregate.MeetingSnapshot changed) throws SQLException {
        MeetingCreateOutcome outcome = MeetingCreateOutcome.success(
                new MeetingCommandResult(
                        MeetingCommandResult.Operation.CREATED, changed));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE idempotency_record
                        SET response_status=200, response_body=?::jsonb
                      WHERE subject_scope=? AND idempotency_key=?
                     """)) {
            statement.setString(1, MeetingCreateOutcomeJson.encode(outcome));
            statement.setString(2, IdempotencyScopeCodec.meetingCreate(
                    request.deploymentScope(), request.subjectScope()));
            statement.setString(3, request.idempotencyKey());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static MeetingAggregate.MeetingSnapshot changedCreatedSnapshot(
            MeetingAggregate.MeetingSnapshot original,
            String changedField) {
        return new MeetingAggregate.MeetingSnapshot(
                changedField.equals("meetingId")
                        ? "other-meeting" : original.meetingId(),
                original.deploymentScope(),
                original.clientSessionId(),
                original.subjectScope(),
                original.grantId(),
                changedField.equals("grantExpiresAt")
                        ? original.grantExpiresAt().plusSeconds(1)
                        : original.grantExpiresAt(),
                changedField.equals("reservationId")
                        ? "other-reservation" : original.usageReservationId(),
                original.profileId(),
                changedField.equals("profileRevision")
                        ? original.profileRevision() + 1
                        : original.profileRevision(),
                original.state(),
                original.createdAt(),
                original.updatedAt());
    }

    private void deleteReserveFacts(UsageOperationResult reserve) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement operation = connection.prepareStatement("""
                    DELETE FROM usage_operation
                     WHERE deployment_scope=? AND subject_scope=?
                       AND operation_scope=? AND idempotency_key=?
                    """);
                 PreparedStatement reservation = connection.prepareStatement("""
                    DELETE FROM usage_reservation
                     WHERE deployment_scope=? AND subject_scope=? AND reservation_id=?
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
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void deleteMeetingOutcome(
            OwnedMeetingCreationUnitOfWork.CreateRequest request) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM idempotency_record
                      WHERE subject_scope=? AND idempotency_key=?
                     """)) {
            statement.setString(1, IdempotencyScopeCodec.meetingCreate(
                    request.deploymentScope(), request.subjectScope()));
            statement.setString(2, request.idempotencyKey());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void insertLegacyMeeting(String meetingId, String sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting (
                         meeting_id, deployment_scope, client_session_id, subject_scope,
                         profile_id, profile_revision, state, aggregate_version,
                         created_at, updated_at, metadata)
                     VALUES (?, ?, ?, 'LEGACY_UNOWNED', 'legacy-profile', 1,
                             'CREATED', 0, ?, ?, '{}'::jsonb)
                     """)) {
            statement.setString(1, meetingId);
            statement.setString(2, DEPLOYMENT);
            statement.setString(3, sessionId);
            statement.setTimestamp(4, Timestamp.from(CREATE_AT));
            statement.setTimestamp(5, Timestamp.from(CREATE_AT));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void update(String sql, Object... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                Object value = parameters[index];
                if (value instanceof Instant instant) {
                    statement.setTimestamp(index + 1, Timestamp.from(instant));
                } else {
                    statement.setObject(index + 1, value);
                }
            }
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * 在首轮 meetingId 查询已经返回空结果、即将准备 scope/session 查询时执行动作。
     *
     * <p>代理只控制 SELECT 的时间边界，不改写 SQL 或结果；动作使用原始 DataSource
     * 的另一条连接提交创建事务。因此测试可以确定性复现 READ COMMITTED 下多条
     * SELECT 横跨另一个事务 commit 的窗口，而不依赖线程调度或 sleep。</p>
     */
    private static DataSource commitBeforeScopeSessionQuery(
            DataSource delegate,
            CheckedAction action) {
        AtomicBoolean fired = new AtomicBoolean();
        return new DelegatingDataSource(delegate) {
            @Override
            public Connection getConnection() throws SQLException {
                return beforePrepare(super.getConnection(), sql -> {
                    if (sql.contains("WHERE stored.deployment_scope = ? "
                            + "AND stored.client_session_id = ?")
                            && fired.compareAndSet(false, true)) {
                        action.run();
                    }
                });
            }
        };
    }

    /** 让 Outcome 首读成功后，当前 Meeting 事实查询在指定 SQLState 失败。 */
    private static DataSource failMeetingFactRead(
            DataSource delegate,
            SQLException failure) {
        return new DelegatingDataSource(delegate) {
            @Override
            public Connection getConnection() throws SQLException {
                return beforePrepare(super.getConnection(), sql -> {
                    if (sql.contains("FROM meeting AS stored")) {
                        throw failure;
                    }
                });
            }
        };
    }

    private static Connection beforePrepare(Connection delegate, SqlObserver observer) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if ("prepareStatement".equals(method.getName())
                            && arguments != null
                            && arguments.length > 0
                            && arguments[0] instanceof String sql) {
                        observer.beforePrepare(sql);
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException reflected) {
                        throw reflected.getCause();
                    }
                });
    }

    private TableCounts tableCounts() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return new TableCounts(
                    count(connection, "idempotency_record"),
                    count(connection, "usage_operation"),
                    count(connection, "usage_reservation"),
                    count(connection, "meeting"),
                    count(connection, "outbox_event"));
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        // 表名只来自本测试的固定常量，不接受外部输入。
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private void applyMigration(String filename) throws Exception {
        if (!"V001__initial_schema.sql".equals(filename)) {
            return;
        }
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
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(migration, StandardCharsets.UTF_8));
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

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface SqlObserver {
        void beforePrepare(String sql) throws Exception;
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

    private record CreatedScenario(
            OwnedMeetingCreationUnitOfWork.CreateRequest request,
            MeetingCreateOutcome outcome) {
    }

    private record FailureScenario(
            OwnedMeetingCreationUnitOfWork.CreateRequest request,
            MeetingCreateOutcome outcome) {
    }

    private record TableCounts(
            long idempotency,
            long usageOperation,
            long usageReservation,
            long meeting,
            long outbox) {
    }
}
