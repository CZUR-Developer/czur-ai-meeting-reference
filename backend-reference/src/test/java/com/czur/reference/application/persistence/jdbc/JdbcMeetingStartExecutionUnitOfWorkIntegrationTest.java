package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会议开始 execution fact 工作单元的隔离 PostgreSQL 16 原子性门禁。
 *
 * <p>每个用例创建独立 schema 并执行 V001 到 V011。门禁覆盖 CREATED 与
 * AUTHORIZED 首次路径、配置变化后的只读重放、归属/Profile/授权冲突、事务
 * 回滚、并发单赢家及 commit acknowledgement unknown。测试只在显式提供随机
 * 隔离数据库 URL 时运行，不访问固定端口或调用方 public schema。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcMeetingStartExecutionUnitOfWorkIntegrationTest {
    private static final List<String> MIGRATIONS = List.of(
            "V001__initial_schema.sql",
            "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql",
            "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql",
            "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql",
            "V008__durable_input_fact.sql",
            "V009__final_usage_fact.sql",
            "V010__workflow_step_fencing.sql",
            "V011__finalization_execution_artifact_ready.sql");
    private static final String DEPLOYMENT = "start-execution-deployment";
    private static final String SUBJECT = "start-execution-subject";
    private static final Instant RESERVED_AT = Instant.parse("2026-09-28T07:59:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-09-28T08:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-09-28T08:00:05.123456Z");

    private PGSimpleDataSource admin;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        admin = postgres(null);
        schema = "meeting_start_execution_"
                + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        dataSource = postgres(schema);
        for (String migration : MIGRATIONS) {
            applyMigration(migration);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (admin != null && schema != null) {
            try (Connection connection = admin.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + quote(schema) + " CASCADE");
            }
        }
    }

    @Test
    void createdPathAtomicallyAuthorizesStartsAndReplaysWithoutReplanning()
            throws Exception {
        String meetingId = createOwnedMeeting("created");
        var unitOfWork = unitOfWork(dataSource);
        var first = success(unitOfWork.start(request(
                meetingId, "标题", profile(), providerAll(), DeploymentLimits.NONE)));

        assertEquals(MeetingStartExecutionUnitOfWork.SuccessCode.STARTED, first.code());
        assertEquals(MeetingState.STARTING, first.meeting().state());
        assertEquals(STARTED_AT, first.meeting().updatedAt());
        assertEquals(STARTED_AT, first.executionFact().startedAt());
        assertEquals("标题", first.executionFact().title());
        assertEquals("NOT_REQUESTED", first.executionFact().capabilityPlan()
                .decisionFor("summary").warningCode());

        // execution fact 已经证明首次授权与规划成功。即使较早的 reserve 审计行
        // 后续不可读，重放也只依赖 Meeting + execution fact 的原子赢家。
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM usage_operation WHERE reservation_id=?")) {
            statement.setString(1, "reservation-created");
            assertEquals(1, statement.executeUpdate());
        }
        // Provider/limits 是首次规划输入，不是重放时重新解释历史会议的依据。
        var replayWithChangedRuntime = success(unitOfWork.start(request(
                meetingId, "标题", profile(),
                new CapabilityDescriptor(Set.of()),
                new DeploymentLimits(Set.of("asr", "summary")))));
        assertEquals(MeetingStartExecutionUnitOfWork.SuccessCode.REPLAYED,
                replayWithChangedRuntime.code());
        assertEquals(first.executionFact(), replayWithChangedRuntime.executionFact());
        assertEquals(MeetingState.STARTING, replayWithChangedRuntime.meeting().state());

        advanceToRunning(meetingId);
        var runningReplay = success(unitOfWork.start(request(
                meetingId, "标题", profile(),
                new CapabilityDescriptor(Set.of()),
                new DeploymentLimits(Set.of("asr")))));
        assertEquals(MeetingStartExecutionUnitOfWork.SuccessCode.REPLAYED,
                runningReplay.code());
        assertEquals(MeetingState.RUNNING, runningReplay.meeting().state());

        try (Connection connection = dataSource.getConnection()) {
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM finalization_execution_fact WHERE meeting_id=?",
                    meetingId));
            // 只有 owned 创建时的 MeetingCreated；开始工作单元不制造占位 Outbox。
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM outbox_event WHERE aggregate_id=?", meetingId));
        }
    }

    @Test
    void authorizedPathAppliesOnlyStartAndUsesExplicitStartedAt() throws Exception {
        String meetingId = createOwnedMeeting("authorized");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcMeetingRepository.VersionedMeeting locked =
                    JdbcMeetingRepository.findVersionedForUpdate(connection, meetingId)
                            .orElseThrow();
            MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(locked.snapshot());
            aggregate.apply(MeetingEvent.AUTHORIZE, CREATED_AT.plusSeconds(1));
            JdbcMeetingRepository.updateOwnedExisting(connection, locked, aggregate.snapshot());
            connection.commit();
        }

        var result = success(unitOfWork(dataSource).start(request(
                meetingId, null, profile(), providerAll(), DeploymentLimits.NONE)));
        assertEquals(MeetingStartExecutionUnitOfWork.SuccessCode.STARTED, result.code());
        assertEquals(MeetingState.STARTING, result.meeting().state());
        assertEquals(STARTED_AT, result.meeting().updatedAt());
        assertNull(result.executionFact().title());
    }

    @Test
    void rejectsOwnershipProfileAuthorizationAndFailedRequiredCapability()
            throws Exception {
        String meetingId = createOwnedMeeting("validation");
        var unitOfWork = unitOfWork(dataSource);

        assertFailure(unitOfWork.start(new MeetingStartExecutionUnitOfWork.StartRequest(
                        DEPLOYMENT, "other-subject", meetingId, STARTED_AT, null,
                        profile(), Set.of("asr"), providerAll(), DeploymentLimits.NONE)),
                MeetingStartExecutionUnitOfWork.ErrorCode.ACCESS_DENIED);
        assertFailure(unitOfWork.start(request(
                        meetingId, null, profileWithRevision(2), providerAll(),
                        DeploymentLimits.NONE)),
                MeetingStartExecutionUnitOfWork.ErrorCode.PROFILE_MISMATCH);
        assertFailure(unitOfWork.start(new MeetingStartExecutionUnitOfWork.StartRequest(
                        DEPLOYMENT, SUBJECT, meetingId, STARTED_AT, null,
                        profile(), Set.of("asr", "summary"), providerAll(),
                        DeploymentLimits.NONE)),
                MeetingStartExecutionUnitOfWork.ErrorCode.AUTHORIZATION_INCONSISTENT);
        assertFailure(unitOfWork.start(new MeetingStartExecutionUnitOfWork.StartRequest(
                        DEPLOYMENT, SUBJECT, meetingId, CREATED_AT.minusSeconds(1), null,
                        profile(), Set.of("asr"), providerAll(), DeploymentLimits.NONE)),
                MeetingStartExecutionUnitOfWork.ErrorCode.START_TIME_CONFLICT);
        assertFailure(unitOfWork.start(request(
                        meetingId, null, profile(), new CapabilityDescriptor(Set.of()),
                        DeploymentLimits.NONE)),
                MeetingStartExecutionUnitOfWork.ErrorCode.CAPABILITY_PLAN_FAILED);

        assertEquals(MeetingState.CREATED, readMeeting(meetingId).state());
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(0, count(connection,
                    "SELECT count(*) FROM finalization_execution_fact WHERE meeting_id=?",
                    meetingId));
        }
    }

    @Test
    void rejectsGrantedCapabilitiesOutsideProfileAndUnexpectedBoundVersion()
            throws Exception {
        String rogueCapabilityMeeting = createOwnedMeeting("rogue-granted");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE usage_operation
                        SET result_body = jsonb_set(
                                result_body,
                                '{authorizedProfileSnapshot,grantedCapabilities}',
                                '["asr","rogue.capability"]'::jsonb)
                      WHERE reservation_id=? AND operation_scope='usage.reserve.v1'
                     """)) {
            statement.setString(1, "reservation-rogue-granted");
            assertEquals(1, statement.executeUpdate());
        }
        assertFailure(unitOfWork(dataSource).start(request(
                        rogueCapabilityMeeting, null, profile(), providerAll(),
                        DeploymentLimits.NONE)),
                MeetingStartExecutionUnitOfWork.ErrorCode.PROFILE_MISMATCH);
        assertEquals(MeetingState.CREATED, readMeeting(rogueCapabilityMeeting).state());

        String unexpectedVersionMeeting = createOwnedMeeting("unexpected-version");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE usage_reservation SET version=2
                      WHERE reservation_id=?
                     """)) {
            statement.setString(1, "reservation-unexpected-version");
            assertEquals(1, statement.executeUpdate());
        }
        assertFailure(unitOfWork(dataSource).start(request(
                        unexpectedVersionMeeting, null, profile(), providerAll(),
                        DeploymentLimits.NONE)),
                MeetingStartExecutionUnitOfWork.ErrorCode.AUTHORIZATION_INCONSISTENT);
        assertEquals(MeetingState.CREATED, readMeeting(unexpectedVersionMeeting).state());
    }

    @Test
    void cancelledBeforeStartIsInvalidStateRatherThanMissingFactCorruption()
            throws Exception {
        String meetingId = createOwnedMeeting("cancelled-before-start");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcMeetingRepository.VersionedMeeting locked =
                    JdbcMeetingRepository.findVersionedForUpdate(connection, meetingId)
                            .orElseThrow();
            MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(locked.snapshot());
            aggregate.apply(MeetingEvent.CANCEL, CREATED_AT.plusSeconds(1));
            JdbcMeetingRepository.updateOwnedExisting(
                    connection, locked, aggregate.snapshot());
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM usage_operation WHERE reservation_id=?")) {
                statement.setString(1, "reservation-cancelled-before-start");
                assertEquals(1, statement.executeUpdate());
            }
            connection.commit();
        }

        assertFailure(unitOfWork(dataSource).start(request(
                        meetingId, null, profile(), providerAll(), DeploymentLimits.NONE)),
                MeetingStartExecutionUnitOfWork.ErrorCode.INVALID_MEETING_STATE);
        assertEquals(MeetingState.CANCELLED, readMeeting(meetingId).state());
    }

    @Test
    void temporaryMeetingTableCannotForgeOwnerDuringReplay() throws Exception {
        String meetingId = createOwnedMeeting("temp-owner");
        success(unitOfWork(dataSource).start(request(
                meetingId, null, profile(), providerAll(), DeploymentLimits.NONE)));

        DataSource shadowed = tempMeetingShadow(
                dataSource, schema, "attacker-subject");
        MeetingStartExecutionUnitOfWork.StartRequest attackerRequest =
                new MeetingStartExecutionUnitOfWork.StartRequest(
                        DEPLOYMENT,
                        "attacker-subject",
                        meetingId,
                        STARTED_AT,
                        null,
                        profile(),
                        Set.of("asr"),
                        providerAll(),
                        DeploymentLimits.NONE);
        assertFailure(unitOfWork(shadowed).start(attackerRequest),
                MeetingStartExecutionUnitOfWork.ErrorCode.ACCESS_DENIED);
    }

    @Test
    void duplicateReserveFactsFailClosedInsteadOfChoosingOne() throws Exception {
        String meetingId = createOwnedMeeting("duplicate-reserve");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO usage_operation (
                         deployment_scope, subject_scope, operation_scope,
                         idempotency_key, request_digest, reservation_id,
                         result_body, created_at)
                     SELECT deployment_scope, subject_scope, operation_scope,
                            'duplicate-reserve-key', request_digest, reservation_id,
                            result_body, created_at
                       FROM usage_operation
                      WHERE reservation_id=? AND operation_scope='usage.reserve.v1'
                     """)) {
            statement.setString(1, "reservation-duplicate-reserve");
            assertEquals(1, statement.executeUpdate());
        }

        assertFailure(unitOfWork(dataSource).start(request(
                        meetingId, null, profile(), providerAll(), DeploymentLimits.NONE)),
                MeetingStartExecutionUnitOfWork.ErrorCode.INTERNAL);
        assertEquals(MeetingState.CREATED, readMeeting(meetingId).state());
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(0, count(connection,
                    "SELECT count(*) FROM finalization_execution_fact WHERE meeting_id=?",
                    meetingId));
        }
    }

    @Test
    void exactFactConflictAndMissingFactAfterStartFailClosed() throws Exception {
        String meetingId = createOwnedMeeting("conflict");
        var unitOfWork = unitOfWork(dataSource);
        success(unitOfWork.start(request(
                meetingId, "original", profile(), providerAll(), DeploymentLimits.NONE)));

        assertFailure(unitOfWork.start(request(
                        meetingId, "changed", profile(), providerAll(), DeploymentLimits.NONE)),
                MeetingStartExecutionUnitOfWork.ErrorCode.EXECUTION_FACT_CONFLICT);
        assertFailure(unitOfWork.start(request(
                        meetingId, "original", profileWithDifferentMapping(), providerAll(),
                        DeploymentLimits.NONE)),
                MeetingStartExecutionUnitOfWork.ErrorCode.EXECUTION_FACT_CONFLICT);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM finalization_execution_fact WHERE meeting_id=?")) {
                statement.setString(1, meetingId);
                assertEquals(1, statement.executeUpdate());
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM usage_operation WHERE reservation_id=?")) {
                statement.setString(1, "reservation-conflict");
                assertEquals(1, statement.executeUpdate());
            }
            connection.commit();
        }
        assertFailure(unitOfWork.start(request(
                        meetingId, "original", profile(), providerAll(), DeploymentLimits.NONE)),
                MeetingStartExecutionUnitOfWork.ErrorCode.FACT_INCONSISTENT);
        assertEquals(MeetingState.STARTING, readMeeting(meetingId).state());
    }

    @Test
    void meetingUpdateFailureRollsBackExecutionFactAndState() throws Exception {
        String meetingId = createOwnedMeeting("rollback");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION reject_meeting_start() RETURNS trigger AS $$
                    BEGIN
                        IF NEW.state = 'STARTING' THEN
                            RAISE EXCEPTION 'injected start failure';
                        END IF;
                        RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql
                    """);
            statement.execute("""
                    CREATE TRIGGER reject_meeting_start_trigger
                    BEFORE UPDATE ON meeting
                    FOR EACH ROW EXECUTE FUNCTION reject_meeting_start()
                    """);
        }

        assertFailure(unitOfWork(dataSource).start(request(
                        meetingId, null, profile(), providerAll(), DeploymentLimits.NONE)),
                MeetingStartExecutionUnitOfWork.ErrorCode.INTERNAL);
        assertEquals(MeetingState.CREATED, readMeeting(meetingId).state());
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(0, count(connection,
                    "SELECT count(*) FROM finalization_execution_fact WHERE meeting_id=?",
                    meetingId));
        }
    }

    @Test
    void concurrentSameRequestHasOneStartAndOneReplay() throws Exception {
        String meetingId = createOwnedMeeting("concurrent");
        var request = request(
                meetingId, "same", profile(), providerAll(), DeploymentLimits.NONE);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MeetingStartExecutionUnitOfWork.Result> left = executor.submit(() -> {
                ready.countDown();
                assertTrue(fire.await(5, TimeUnit.SECONDS));
                return unitOfWork(dataSource).start(request);
            });
            Future<MeetingStartExecutionUnitOfWork.Result> right = executor.submit(() -> {
                ready.countDown();
                assertTrue(fire.await(5, TimeUnit.SECONDS));
                return unitOfWork(dataSource).start(request);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            fire.countDown();
            var first = success(left.get(10, TimeUnit.SECONDS));
            var second = success(right.get(10, TimeUnit.SECONDS));
            Set<MeetingStartExecutionUnitOfWork.SuccessCode> codes =
                    Set.of(first.code(), second.code());
            assertEquals(Set.of(
                    MeetingStartExecutionUnitOfWork.SuccessCode.STARTED,
                    MeetingStartExecutionUnitOfWork.SuccessCode.REPLAYED), codes);
            assertEquals(first.executionFact(), second.executionFact());
        }
    }

    @Test
    void commitAcknowledgementUnknownRecoversByReadOnlyReplay() throws Exception {
        String meetingId = createOwnedMeeting("unknown");
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        DataSource unknown = commitUnknown(dataSource, aborted, closed);

        MeetingStartExecutionUnitOfWork.Failure failure = failure(
                unitOfWork(unknown).start(request(
                        meetingId, "unknown", profile(), providerAll(),
                        DeploymentLimits.NONE)));
        assertEquals(MeetingStartExecutionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                failure.code());
        assertTrue(aborted.get());
        assertTrue(closed.get());

        // Provider 已变化也不能重算历史 Plan；fresh connection 只读事实后稳定重放。
        var replay = success(unitOfWork(dataSource).start(request(
                meetingId, "unknown", profile(), new CapabilityDescriptor(Set.of()),
                new DeploymentLimits(Set.of("asr")))));
        assertEquals(MeetingStartExecutionUnitOfWork.SuccessCode.REPLAYED, replay.code());
        assertEquals(MeetingState.STARTING, replay.meeting().state());
        assertNotNull(replay.executionFact().capabilityPlan().decisionFor("asr"));
    }

    private String createOwnedMeeting(String suffix) throws Exception {
        String meetingId = "meeting-start-" + suffix;
        MeetingGrant grant = new MeetingGrant(
                "grant-" + suffix,
                DEPLOYMENT,
                SUBJECT,
                "profile",
                1,
                Set.of("asr"),
                Set.of("asr", "summary"),
                3_600_000,
                RESERVED_AT,
                RESERVED_AT.plusSeconds(3_600));
        UsageReservation reservation = UsageReservation.reserved(
                "reservation-" + suffix,
                grant,
                meetingId,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                RESERVED_AT.plusSeconds(1),
                RESERVED_AT.plusSeconds(3_000));
        UsageOperationResult reserve = UsageOperationResult.reserve(
                DEPLOYMENT,
                SUBJECT,
                "reserve-key-" + suffix,
                "reserve-digest-" + suffix,
                grant.expiresAt(),
                AuthorizedProfileSnapshot.from(grant),
                reservation);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcUsageReservationStore.insertReservation(connection, reservation);
            assertTrue(JdbcUsageReservationStore.insertOperation(
                    connection, reserve, RESERVED_AT.plusSeconds(1)));
            connection.commit();
        }
        OwnedMeetingCreationUnitOfWork.Result created =
                new JdbcOwnedMeetingCreationUnitOfWork(
                        dataSource, Clock.fixed(CREATED_AT, ZoneOffset.UTC))
                        .create(new OwnedMeetingCreationUnitOfWork.CreateRequest(
                                DEPLOYMENT,
                                SUBJECT,
                                meetingId,
                                "session-" + suffix,
                                "create-key-" + suffix,
                                "create-digest-" + suffix,
                                reserve));
        assertTrue(created instanceof OwnedMeetingCreationUnitOfWork.Success);
        assertEquals(MeetingState.CREATED, readMeeting(meetingId).state());
        return meetingId;
    }

    private void advanceToRunning(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcMeetingRepository.VersionedMeeting locked =
                    JdbcMeetingRepository.findVersionedForUpdate(connection, meetingId)
                            .orElseThrow();
            MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(locked.snapshot());
            aggregate.apply(MeetingEvent.START, STARTED_AT.plusSeconds(1));
            JdbcMeetingRepository.updateOwnedExisting(connection, locked, aggregate.snapshot());
            connection.commit();
        }
    }

    private MeetingAggregate.MeetingSnapshot readMeeting(String meetingId) {
        return new JdbcMeetingRepository(dataSource).find(meetingId).orElseThrow();
    }

    private static JdbcMeetingStartExecutionUnitOfWork unitOfWork(DataSource source) {
        return new JdbcMeetingStartExecutionUnitOfWork(source, new CapabilityPlanner());
    }

    private static MeetingStartExecutionUnitOfWork.StartRequest request(
            String meetingId,
            String title,
            ProductProfile profile,
            CapabilityDescriptor provider,
            DeploymentLimits limits) {
        return new MeetingStartExecutionUnitOfWork.StartRequest(
                DEPLOYMENT,
                SUBJECT,
                meetingId,
                STARTED_AT,
                title,
                profile,
                Set.of("asr"),
                provider,
                limits);
    }

    private static ProductProfile profile() {
        return new ProductProfile(
                "profile",
                1,
                Set.of("asr"),
                Set.of("summary"),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(
                        "asr", List.of("caption"),
                        "summary", List.of("finalization")),
                Map.of(
                        "asr", List.of("transcript.final"),
                        "summary", List.of("summary.structured")));
    }

    private static ProductProfile profileWithRevision(int revision) {
        return new ProductProfile(
                "profile", revision, Set.of("asr"), Set.of("summary"),
                Map.of(), Map.of(), Map.of(),
                Map.of("asr", List.of("caption"),
                        "summary", List.of("finalization")),
                Map.of("asr", List.of("transcript.final"),
                        "summary", List.of("summary.structured")));
    }

    private static ProductProfile profileWithDifferentMapping() {
        return new ProductProfile(
                "profile", 1, Set.of("asr"), Set.of("summary"),
                Map.of(), Map.of(), Map.of(),
                Map.of("asr", List.of("different-caption"),
                        "summary", List.of("finalization")),
                Map.of("asr", List.of("transcript.final"),
                        "summary", List.of("summary.structured")));
    }

    private static CapabilityDescriptor providerAll() {
        return new CapabilityDescriptor(
                "test-provider", "1", 1, Set.of("asr", "summary"));
    }

    private static MeetingStartExecutionUnitOfWork.Success success(
            MeetingStartExecutionUnitOfWork.Result result) {
        assertTrue(result instanceof MeetingStartExecutionUnitOfWork.Success,
                () -> "expected success but got " + result);
        return (MeetingStartExecutionUnitOfWork.Success) result;
    }

    private static MeetingStartExecutionUnitOfWork.Failure failure(
            MeetingStartExecutionUnitOfWork.Result result) {
        assertTrue(result instanceof MeetingStartExecutionUnitOfWork.Failure,
                () -> "expected failure but got " + result);
        return (MeetingStartExecutionUnitOfWork.Failure) result;
    }

    private static void assertFailure(
            MeetingStartExecutionUnitOfWork.Result result,
            MeetingStartExecutionUnitOfWork.ErrorCode code) {
        assertEquals(code, failure(result).code());
    }

    private static int count(Connection connection, String sql, String value)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static DataSource commitUnknown(
            DataSource delegate,
            AtomicBoolean aborted,
            AtomicBoolean closed) {
        return (DataSource) Proxy.newProxyInstance(
                JdbcMeetingStartExecutionUnitOfWorkIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        Connection connection = args == null || args.length == 0
                                ? delegate.getConnection()
                                : delegate.getConnection((String) args[0], (String) args[1]);
                        return commitUnknownConnection(connection, aborted, closed);
                    }
                    return method.invoke(delegate, args);
                });
    }

    /**
     * 每次借出连接时创建一个 owner 被篡改的同名 TEMP meeting。
     *
     * <p>该替身只用于证明工作单元在第一次锁读之前已经固定部署 schema；未固定
     * 时 PostgreSQL 会优先解析临时表，并可能把攻击者伪造的 owner 当成真实事实。</p>
     */
    private static DataSource tempMeetingShadow(
            DataSource delegate,
            String deploymentSchema,
            String forgedSubject) {
        return (DataSource) Proxy.newProxyInstance(
                JdbcMeetingStartExecutionUnitOfWorkIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) {
                        return method.invoke(delegate, args);
                    }
                    Connection connection = args == null || args.length == 0
                            ? delegate.getConnection()
                            : delegate.getConnection((String) args[0], (String) args[1]);
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("CREATE TEMP TABLE meeting (LIKE "
                                + quote(deploymentSchema)
                                + ".meeting INCLUDING ALL)");
                        statement.execute("INSERT INTO meeting SELECT * FROM "
                                + quote(deploymentSchema) + ".meeting");
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE meeting SET subject_scope=?")) {
                        statement.setString(1, forgedSubject);
                        statement.executeUpdate();
                    }
                    return connection;
                });
    }

    private static Connection commitUnknownConnection(
            Connection delegate,
            AtomicBoolean aborted,
            AtomicBoolean closed) {
        return (Connection) Proxy.newProxyInstance(
                JdbcMeetingStartExecutionUnitOfWorkIntegrationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("commit")) {
                        delegate.commit();
                        throw new SQLException("sensitive acknowledgement", "08006");
                    }
                    if (method.getName().equals("abort")) {
                        aborted.set(true);
                        delegate.abort((Executor) args[0]);
                        return null;
                    }
                    if (method.getName().equals("close")) {
                        closed.set(true);
                    }
                    return method.invoke(delegate, args);
                });
    }

    private void applyMigration(String filename) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(readMigration(filename));
        }
    }

    private static String readMigration(String filename) throws IOException {
        Path[] candidates = {
                Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("migration not found: " + filename);
    }

    private static PGSimpleDataSource postgres(String currentSchema) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(required("REFERENCE_TEST_DATABASE_URL"));
        source.setUser(required("REFERENCE_TEST_DATABASE_USER"));
        source.setPassword(required("REFERENCE_TEST_DATABASE_PASSWORD"));
        if (currentSchema != null) {
            source.setCurrentSchema(currentSchema);
        }
        return source;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
