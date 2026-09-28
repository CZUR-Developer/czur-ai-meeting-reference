package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.finalization.DeterministicMockFinalizationPlanner;
import com.czur.reference.application.finalization.FinalizationAdmissionPlanner;
import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.port.FinalizationAdmissionUnitOfWork;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingFinalizationStore;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.WorkflowStep;
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
import com.czur.reference.protocol.json.FinalizationCheckpointJson;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finalization Admission 单连接事务的隔离 PostgreSQL 16 门禁。
 *
 * <p>每个用例创建全新 schema 并执行 V001 到 V011。测试覆盖首次原子提交、只读
 * 重放、零 Planner/receipt 重算、失败全回滚、Reservation/Workflow 漂移、并发
 * 单赢家、commit acknowledgement unknown、连接 abort 以及 TEMP 表遮蔽。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcFinalizationAdmissionUnitOfWorkIntegrationTest {
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
    private static final String DEPLOYMENT = "admission-deployment";
    private static final String SUBJECT = "admission-subject";
    private static final Instant RESERVED_AT = Instant.parse("2026-09-28T07:59:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-09-28T08:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-09-28T08:00:05.123456Z");
    private static final Instant STOPPED_AT = Instant.parse("2026-09-28T08:09:59.123456Z");
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-28T08:10:00.123456Z");

    private PGSimpleDataSource admin;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        admin = postgres(null);
        schema = "finalization_admission_"
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
    void firstAdmissionCommitsAllFactsAndReplayRunsNoPlannerOrReceipt() throws Exception {
        String meetingId = createAdmissibleMeeting("atomic");
        AtomicInteger receiptCalls = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();
        FinalizationAdmissionPlanner countingPlanner = countingPlanner(plannerCalls);
        var unitOfWork = unitOfWork(
                dataSource,
                () -> "receipt-" + receiptCalls.incrementAndGet(),
                countingPlanner);

        var first = success(unitOfWork.admit(request(meetingId)));
        assertEquals(FinalizationAdmissionUnitOfWork.SuccessCode.ADMITTED, first.code());
        assertEquals(1, receiptCalls.get());
        assertEquals(1, plannerCalls.get());

        var replay = success(unitOfWork.recover(request(meetingId)));
        assertEquals(FinalizationAdmissionUnitOfWork.SuccessCode.REPLAYED, replay.code());
        assertEquals(first.finalization(), replay.finalization());
        assertEquals(first.finalUsage(), replay.finalUsage());
        assertEquals(first.workflowKey(), replay.workflowKey());
        assertEquals(1, receiptCalls.get(), "recovery must not regenerate receipt");
        assertEquals(1, plannerCalls.get(), "recovery must not rerun planner");
        assertAdmissionFactsExactlyMatchFrozenPlan(
                meetingId, "receipt-1", first.workflowKey());

        assertMeeting(meetingId, MeetingState.FINALIZING,
                "INPUT_ACCEPTED", ManifestStatus.GENERATING.name());
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM durable_input_fact WHERE meeting_id=?", meetingId));
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM final_usage_fact WHERE meeting_id=?", meetingId));
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM workflow_step WHERE meeting_id=?", meetingId));
            assertEquals(0, count(connection,
                    "SELECT count(*) FROM meeting_manifest_revision WHERE meeting_id=?",
                    meetingId));
        }
    }

    @Test
    void receiptAndManifestFailuresRollbackEveryAdmissionFact() throws Exception {
        String receiptFailureMeeting = createAdmissibleMeeting("receipt-failure");
        var receiptFailure = failure(unitOfWork(
                dataSource,
                () -> { throw new IllegalStateException("sensitive receipt failure"); },
                new DeterministicMockFinalizationPlanner())
                .admit(request(receiptFailureMeeting)));
        assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.RECEIPT_GENERATION_FAILED,
                receiptFailure.code());
        assertNoAdmissionFacts(receiptFailureMeeting);

        String manifestFailureMeeting = createAdmissibleMeeting("manifest-failure");
        installGeneratingRejectionTrigger();
        var manifestFailure = failure(unitOfWork(
                dataSource,
                () -> "receipt-manifest-failure",
                new DeterministicMockFinalizationPlanner())
                .admit(request(manifestFailureMeeting)));
        assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.MANIFEST_CONFLICT,
                manifestFailure.code());
        assertNoAdmissionFacts(manifestFailureMeeting);
    }

    @Test
    void everyLocalTransactionFailureWindowRollsBackAndCanRetryExactlyOnce()
            throws Exception {
        AtomicInteger ordinal = new AtomicInteger();
        for (JdbcFinalizationAdmissionUnitOfWork.FailurePoint point
                : JdbcFinalizationAdmissionUnitOfWork.FailurePoint.values()) {
            String suffix = "rollback-" + ordinal.incrementAndGet();
            String meetingId = createAdmissibleMeeting(suffix);
            MeetingRow meetingBefore = readMeetingRow(meetingId);
            ReservationRow reservationBefore = readReservationRow(
                    "reservation-" + suffix);
            AtomicInteger receiptCalls = new AtomicInteger();
            AtomicInteger plannerCalls = new AtomicInteger();
            var failing = new JdbcFinalizationAdmissionUnitOfWork(
                    dataSource,
                    () -> "receipt-" + receiptCalls.incrementAndGet(),
                    countingPlanner(plannerCalls),
                    reached -> {
                        if (reached == point) {
                            throw new InjectedAdmissionFailure();
                        }
                    });

            assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.INTERNAL,
                    failure(failing.admit(request(meetingId))).code(),
                    () -> "unexpected failure classification at " + point);
            assertEquals(meetingBefore, readMeetingRow(meetingId),
                    () -> "meeting half-state at " + point);
            assertEquals(reservationBefore,
                    readReservationRow("reservation-" + suffix),
                    () -> "reservation changed at " + point);
            assertNoAdmissionFacts(meetingId);

            AtomicInteger retryPlannerCalls = new AtomicInteger();
            var retried = success(unitOfWork(
                    dataSource,
                    () -> "retry-receipt-" + suffix,
                    countingPlanner(retryPlannerCalls)).admit(request(meetingId)));
            assertEquals(FinalizationAdmissionUnitOfWork.SuccessCode.ADMITTED,
                    retried.code());
            assertEquals(1, retryPlannerCalls.get());
            try (Connection connection = dataSource.getConnection()) {
                assertEquals(1, count(connection,
                        "SELECT count(*) FROM durable_input_fact WHERE meeting_id=?",
                        meetingId));
                assertEquals(1, count(connection,
                        "SELECT count(*) FROM final_usage_fact WHERE meeting_id=?",
                        meetingId));
                assertEquals(1, count(connection,
                        "SELECT count(*) FROM workflow_step WHERE meeting_id=?",
                        meetingId));
            }
        }
    }

    @Test
    void readOnlyRecoveryReportsAbsenceWithoutPlannerOrReceipt() throws Exception {
        String meetingId = createAdmissibleMeeting("recovery-absent");
        AtomicInteger receiptCalls = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();
        var result = failure(unitOfWork(
                dataSource,
                () -> "receipt-" + receiptCalls.incrementAndGet(),
                countingPlanner(plannerCalls))
                .recover(request(meetingId)));

        assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.ADMISSION_NOT_FOUND,
                result.code());
        assertFalse(result.retryable(),
                "absence switches the caller to a new admit; recover is not retried");
        assertEquals(0, receiptCalls.get());
        assertEquals(0, plannerCalls.get());
        assertNoAdmissionFacts(meetingId);
    }

    @Test
    void firstAdmissionRejectsAggregateVersionDriftBeforeAnyWriteOrPlanner()
            throws Exception {
        String meetingId = createAdmissibleMeeting("aggregate-version-drift");
        executeMutation("""
                UPDATE meeting SET aggregate_version=9 WHERE meeting_id=?
                """, meetingId);
        MeetingRow meetingBefore = readMeetingRow(meetingId);
        ReservationRow reservationBefore = readReservationRow(
                "reservation-aggregate-version-drift");
        AtomicInteger receiptCalls = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();

        var result = failure(unitOfWork(
                dataSource,
                () -> "must-not-run-" + receiptCalls.incrementAndGet(),
                countingPlanner(plannerCalls)).admit(request(meetingId)));

        assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                result.code());
        assertEquals(0, receiptCalls.get());
        assertEquals(0, plannerCalls.get());
        assertEquals(meetingBefore, readMeetingRow(meetingId));
        assertEquals(reservationBefore, readReservationRow(
                "reservation-aggregate-version-drift"));
        assertNoAdmissionFacts(meetingId);
    }

    @Test
    void readOnlyRecoveryRejectsEveryPartialAdmissionShape() throws Exception {
        List<PartialCase> cases = List.of(
                new PartialCase("v008-only", this::persistV008Only),
                new PartialCase("v008-v009", this::persistV008AndV009),
                new PartialCase("receipt-only", this::persistReceiptOnly),
                new PartialCase("missing-workflow", meetingId -> {
                    success(unitOfWork(
                            dataSource,
                            () -> "receipt-missing-workflow",
                            new DeterministicMockFinalizationPlanner())
                            .admit(request(meetingId)));
                    executeMutation(
                            "DELETE FROM workflow_step WHERE meeting_id=?",
                            meetingId);
                }),
                new PartialCase("missing-generating", meetingId -> {
                    success(unitOfWork(
                            dataSource,
                            () -> "receipt-missing-generating",
                            new DeterministicMockFinalizationPlanner())
                            .admit(request(meetingId)));
                    executeMutation("""
                            UPDATE meeting
                               SET manifest_state='NOT_STARTED'
                             WHERE meeting_id=?
                            """, meetingId);
                }));

        for (PartialCase partial : cases) {
            String meetingId = createAdmissibleMeeting(
                    "partial-" + partial.name());
            partial.mutation().apply(meetingId);
            AtomicInteger receiptCalls = new AtomicInteger();
            AtomicInteger plannerCalls = new AtomicInteger();
            var recovered = failure(unitOfWork(
                    dataSource,
                    () -> "must-not-run-" + receiptCalls.incrementAndGet(),
                    countingPlanner(plannerCalls)).recover(request(meetingId)));
            assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                    recovered.code(),
                    () -> "partial admission was not rejected: " + partial.name());
            assertEquals(0, receiptCalls.get());
            assertEquals(0, plannerCalls.get());
        }
    }

    @Test
    void initialAdmissionRejectsInvalidReservationStateVersionBindingAndLease()
            throws Exception {
        List<ReservationCase> cases = List.of(
                new ReservationCase("committed", (meetingId, reservationId) ->
                        executeReservationMutation("""
                                UPDATE usage_reservation
                                   SET status='COMMITTED', actual_amount=60000,
                                       committed_at=?, updated_at=?, version=2
                                 WHERE reservation_id=?
                                """, reservationId, ACCEPTED_AT)),
                new ReservationCase("version", (meetingId, reservationId) ->
                        executeReservationMutation("""
                                UPDATE usage_reservation SET version=2
                                 WHERE reservation_id=?
                                """, reservationId)),
                new ReservationCase("unbound", (meetingId, reservationId) ->
                        executeReservationMutation("""
                                UPDATE usage_reservation
                                   SET bound_meeting_id=NULL, bound_at=NULL, version=0
                                 WHERE reservation_id=?
                                """, reservationId)),
                // V003 的物理约束本身禁止“已绑定且仍持有 cleanup lease”。最后一个
                // 反例刻意移除该 schema 内的约束并注入损坏行，证明 Admission 仍会
                // 在应用边界关闭式拒绝，而不是只依赖数据库 CHECK。
                new ReservationCase("active-lease", (meetingId, reservationId) -> {
                    try (Connection connection = dataSource.getConnection();
                         Statement statement = connection.createStatement()) {
                        statement.execute("ALTER TABLE usage_reservation DROP CONSTRAINT "
                                + "usage_reservation_v3_ck");
                    }
                    try (Connection connection = dataSource.getConnection();
                         PreparedStatement statement = connection.prepareStatement("""
                                 UPDATE usage_reservation
                                    SET lease_owner='cleanup-worker',
                                        lease_token='cleanup-token',
                                        lease_until=?, lease_version=1
                                  WHERE reservation_id=?
                                 """)) {
                        statement.setTimestamp(1,
                                java.sql.Timestamp.from(ACCEPTED_AT.plusSeconds(60)));
                        statement.setString(2, reservationId);
                        assertEquals(1, statement.executeUpdate());
                    }
                }));

        for (ReservationCase invalid : cases) {
            String suffix = "reservation-" + invalid.name();
            String meetingId = createAdmissibleMeeting(suffix);
            String reservationId = "reservation-" + suffix;
            invalid.mutation().apply(meetingId, reservationId);
            AtomicInteger receiptCalls = new AtomicInteger();
            AtomicInteger plannerCalls = new AtomicInteger();
            var result = failure(unitOfWork(
                    dataSource,
                    () -> "must-not-run-" + receiptCalls.incrementAndGet(),
                    countingPlanner(plannerCalls)).admit(request(meetingId)));
            assertEquals(
                    FinalizationAdmissionUnitOfWork.ErrorCode.RESERVATION_INCONSISTENT,
                    result.code(),
                    () -> "invalid reservation was not rejected: " + invalid.name());
            assertEquals(0, receiptCalls.get());
            assertEquals(0, plannerCalls.get());
            assertNoAdmissionFacts(meetingId);
        }
    }

    @Test
    void replayAcceptsExactCommittedReservationWithoutRegeneratingPlan()
            throws Exception {
        String meetingId = createAdmissibleMeeting("replay-committed");
        AtomicInteger receiptCalls = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();
        var unitOfWork = unitOfWork(
                dataSource,
                () -> "receipt-" + receiptCalls.incrementAndGet(),
                countingPlanner(plannerCalls));
        var admitted = success(unitOfWork.admit(request(meetingId)));
        commitReservation(meetingId);

        var recovered = success(unitOfWork.recover(request(meetingId)));
        assertEquals(FinalizationAdmissionUnitOfWork.SuccessCode.REPLAYED,
                recovered.code());
        assertEquals(admitted.finalization(), recovered.finalization());
        assertEquals(admitted.finalUsage(), recovered.finalUsage());
        assertEquals(1, receiptCalls.get());
        assertEquals(1, plannerCalls.get());
    }

    @Test
    void replayRejectsReservationVersionAndCommittedUsageDrift() throws Exception {
        List<ReplayReservationCase> cases = List.of(
                new ReplayReservationCase("reserved-version", (meetingId, reservationId) ->
                        executeReservationMutation("""
                                UPDATE usage_reservation SET version=2
                                 WHERE reservation_id=?
                                """, reservationId)),
                new ReplayReservationCase("committed-version", (meetingId, reservationId) ->
                        executeReservationMutation("""
                                UPDATE usage_reservation
                                   SET status='COMMITTED', actual_amount=60000,
                                       committed_at=?, updated_at=?, version=3
                                 WHERE reservation_id=?
                                """, reservationId, ACCEPTED_AT.plusSeconds(1))),
                new ReplayReservationCase("committed-actual", (meetingId, reservationId) ->
                        executeReservationMutation("""
                                UPDATE usage_reservation
                                   SET status='COMMITTED', actual_amount=60001,
                                       committed_at=?, updated_at=?, version=2
                                 WHERE reservation_id=?
                                """, reservationId, ACCEPTED_AT.plusSeconds(1))));

        for (ReplayReservationCase drift : cases) {
            String suffix = "replay-reservation-" + drift.name();
            String meetingId = createAdmissibleMeeting(suffix);
            AtomicInteger receiptCalls = new AtomicInteger();
            AtomicInteger plannerCalls = new AtomicInteger();
            var unitOfWork = unitOfWork(
                    dataSource,
                    () -> "receipt-" + suffix + '-' + receiptCalls.incrementAndGet(),
                    countingPlanner(plannerCalls));
            success(unitOfWork.admit(request(meetingId)));
            drift.mutation().apply(meetingId, "reservation-" + suffix);

            assertEquals(
                    FinalizationAdmissionUnitOfWork.ErrorCode.RESERVATION_INCONSISTENT,
                    failure(unitOfWork.recover(request(meetingId))).code(),
                    () -> "reservation replay drift was not rejected: " + drift.name());
            assertEquals(1, receiptCalls.get());
            assertEquals(1, plannerCalls.get());
        }
    }

    @Test
    void replayRejectsInputReservationAndWorkflowDrift() throws Exception {
        String inputMeeting = createAdmissibleMeeting("input-drift");
        var inputUnit = unitOfWork(dataSource, () -> "receipt-input",
                new DeterministicMockFinalizationPlanner());
        success(inputUnit.admit(request(inputMeeting)));
        FinalizationInputFact changed = new FinalizationInputFact(
                inputMeeting, "binding-input-drift", 99, 60_000,
                "zh-CN", List.of("en-US"), ACCEPTED_AT, "sha256:changed");
        var inputFailure = failure(inputUnit.recover(
                new FinalizationAdmissionUnitOfWork.AdmissionRequest(
                        DEPLOYMENT, SUBJECT, inputMeeting, changed)));
        assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.INPUT_FACT_CONFLICT,
                inputFailure.code());

        String reservationMeeting = createAdmissibleMeeting("reservation-drift");
        var reservationUnit = unitOfWork(dataSource, () -> "receipt-reservation",
                new DeterministicMockFinalizationPlanner());
        success(reservationUnit.admit(request(reservationMeeting)));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE usage_reservation SET grant_id='drifted-grant'"
                             + " WHERE reservation_id=?")) {
            statement.setString(1, "reservation-reservation-drift");
            assertEquals(1, statement.executeUpdate());
        }
        assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.RESERVATION_INCONSISTENT,
                failure(reservationUnit.recover(request(reservationMeeting))).code());

        String workflowMeeting = createAdmissibleMeeting("workflow-drift");
        var workflowUnit = unitOfWork(dataSource, () -> "receipt-workflow",
                new DeterministicMockFinalizationPlanner());
        success(workflowUnit.admit(request(workflowMeeting)));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE workflow_step
                        SET status='COMPLETED', next_attempt_at=NULL
                      WHERE meeting_id=?
                     """)) {
            statement.setString(1, workflowMeeting);
            assertEquals(1, statement.executeUpdate());
        }
        assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                failure(workflowUnit.recover(request(workflowMeeting))).code());
    }

    @Test
    void recoveryRejectsPersistedFactIdentityManifestAndMeetingDrift()
            throws Exception {
        List<DriftCase> cases = List.of(
                new DriftCase("v008", """
                        UPDATE durable_input_fact
                           SET audio_duration_ms=audio_duration_ms+1
                         WHERE meeting_id=?
                        """, FinalizationAdmissionUnitOfWork.ErrorCode.INPUT_FACT_CONFLICT),
                new DriftCase("v009", """
                        UPDATE final_usage_fact
                           SET actual_amount=actual_amount+1
                         WHERE meeting_id=?
                        """, FinalizationAdmissionUnitOfWork.ErrorCode.FACT_INCONSISTENT),
                new DriftCase("checkpoint-payload", """
                        UPDATE workflow_step
                           SET canonical_payload=canonical_payload || ' '
                         WHERE meeting_id=?
                        """, FinalizationAdmissionUnitOfWork.ErrorCode.WORKFLOW_CONFLICT),
                new DriftCase("checkpoint-digest", """
                        UPDATE workflow_step
                           SET content_digest='sha256:' || repeat('0', 64)
                         WHERE meeting_id=?
                        """, FinalizationAdmissionUnitOfWork.ErrorCode.WORKFLOW_CONFLICT),
                new DriftCase("checkpoint-type", """
                        UPDATE workflow_step
                           SET checkpoint_type='drifted-checkpoint-type'
                         WHERE meeting_id=?
                        """, FinalizationAdmissionUnitOfWork.ErrorCode.WORKFLOW_CONFLICT),
                new DriftCase("workflow-step-type", """
                        UPDATE workflow_step
                           SET step_type='drifted-step-type'
                         WHERE meeting_id=?
                        """, FinalizationAdmissionUnitOfWork.ErrorCode.FACT_INCONSISTENT),
                new DriftCase("workflow-step-key", """
                        UPDATE workflow_step
                           SET step_key='drifted-step-key'
                         WHERE meeting_id=?
                        """, FinalizationAdmissionUnitOfWork.ErrorCode.FACT_INCONSISTENT),
                new DriftCase("commit-plan", """
                        UPDATE workflow_step
                           SET canonical_payload=replace(
                                   canonical_payload,
                                   'admission-subject',
                                   'drifted-subject'),
                               content_digest='sha256:' || encode(
                                   sha256(convert_to(replace(
                                       canonical_payload,
                                       'admission-subject',
                                       'drifted-subject'), 'UTF8')), 'hex')
                         WHERE meeting_id=?
                        """, FinalizationAdmissionUnitOfWork.ErrorCode.WORKFLOW_CONFLICT),
                new DriftCase("execution", """
                        UPDATE finalization_execution_fact
                           SET canonical_json=replace(
                                   canonical_json,
                                   'Admission test',
                                   'Drifted title'),
                               execution_fact_json=replace(
                                   canonical_json,
                                   'Admission test',
                                   'Drifted title')::jsonb,
                               content_sha256=sha256(convert_to(replace(
                                   canonical_json,
                                   'Admission test',
                                   'Drifted title'), 'UTF8'))
                         WHERE meeting_id=?
                        """, FinalizationAdmissionUnitOfWork.ErrorCode.WORKFLOW_CONFLICT),
                new DriftCase("deployment", """
                        UPDATE meeting SET deployment_scope='drifted-deployment'
                         WHERE meeting_id=?
                        """, FinalizationAdmissionUnitOfWork.ErrorCode.ACCESS_DENIED),
                new DriftCase("subject", """
                        UPDATE meeting SET subject_scope='drifted-subject'
                         WHERE meeting_id=?
                        """, FinalizationAdmissionUnitOfWork.ErrorCode.ACCESS_DENIED),
                new DriftCase("profile", """
                        UPDATE meeting SET profile_revision=profile_revision+1
                         WHERE meeting_id=?
                        """, FinalizationAdmissionUnitOfWork.ErrorCode.PROFILE_MISMATCH),
                new DriftCase("manifest-state", """
                        UPDATE meeting SET manifest_state='NOT_STARTED'
                         WHERE meeting_id=?
                        """, FinalizationAdmissionUnitOfWork.ErrorCode.FACT_INCONSISTENT),
                new DriftCase("meeting-version", """
                        UPDATE meeting SET aggregate_version=aggregate_version+1
                         WHERE meeting_id=?
                        """, FinalizationAdmissionUnitOfWork.ErrorCode.FACT_INCONSISTENT),
                new DriftCase("meeting-time", """
                        UPDATE meeting SET updated_at=updated_at+interval '1 second'
                         WHERE meeting_id=?
                        """, FinalizationAdmissionUnitOfWork.ErrorCode.FACT_INCONSISTENT));

        for (DriftCase drift : cases) {
            String meetingId = createAdmissibleMeeting("drift-" + drift.name());
            var unitOfWork = unitOfWork(
                    dataSource,
                    () -> "receipt-" + drift.name(),
                    new DeterministicMockFinalizationPlanner());
            success(unitOfWork.admit(request(meetingId)));
            executeMutation(drift.sql(), meetingId);
            assertEquals(drift.expected(),
                    failure(unitOfWork.recover(request(meetingId))).code(),
                    () -> "drift was not rejected: " + drift.name());
        }
    }

    @Test
    void deploymentSchemaWinsOverAllTemporaryShadowTables() throws Exception {
        String meetingId = createAdmissibleMeeting("temp-shadows");
        DataSource shadowed = allTemporaryShadows(dataSource, schema);
        var result = success(unitOfWork(
                shadowed,
                () -> "receipt-temp-shadows",
                new DeterministicMockFinalizationPlanner()).admit(request(meetingId)));
        assertEquals(FinalizationAdmissionUnitOfWork.SuccessCode.ADMITTED, result.code());
        assertMeeting(meetingId, MeetingState.FINALIZING,
                "INPUT_ACCEPTED", ManifestStatus.GENERATING.name());
    }

    @Test
    void admissionLogsNeverExposeBusinessOrSecretSentinels() throws Exception {
        String meetingId = createAdmissibleMeeting("log-sentinel");
        String binding = "binding-secret-sentinel";
        String digest = "digest-secret-sentinel";
        String receipt = "receipt-secret-sentinel";
        FinalizationAdmissionUnitOfWork.AdmissionRequest sensitiveRequest =
                new FinalizationAdmissionUnitOfWork.AdmissionRequest(
                        DEPLOYMENT,
                        SUBJECT,
                        meetingId,
                        new FinalizationInputFact(
                                meetingId, binding, 42, 60_000,
                                "zh-CN", List.of("en-US"), ACCEPTED_AT, digest));

        // 只审计参考实现自己的日志命名空间。若把 JVM 根 Logger 调到 ALL，
        // PostgreSQL JDBC 驱动会输出 wire protocol 和 SQL 绑定参数；那属于第三方
        // 驱动的诊断模式，不是本应用的生产日志契约，也会把本测试本身使用的
        // 数据库连接信息一起误判为应用泄露。
        Logger application = Logger.getLogger("com.czur.reference");
        CapturingHandler capture = new CapturingHandler();
        Level previousLevel = application.getLevel();
        application.setLevel(Level.ALL);
        capture.setLevel(Level.ALL);
        application.addHandler(capture);
        try {
            success(unitOfWork(
                    dataSource,
                    () -> receipt,
                    new DeterministicMockFinalizationPlanner())
                    .admit(sensitiveRequest));
        } finally {
            application.removeHandler(capture);
            application.setLevel(previousLevel);
        }
        String logs = capture.content();
        for (String forbidden : List.of(
                meetingId,
                DEPLOYMENT,
                SUBJECT,
                binding,
                digest,
                receipt,
                "secret-token-sentinel",
                "https://secret.example.invalid",
                "SELECT * FROM secret_sentinel")) {
            assertFalse(logs.contains(forbidden),
                    () -> "sensitive log value leaked: " + forbidden);
        }
    }

    @Test
    void concurrentAdmissionHasOneWriterAndOneReadOnlyReplay() throws Exception {
        String meetingId = createAdmissibleMeeting("concurrent");
        AtomicInteger receiptCalls = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();
        var unitOfWork = unitOfWork(
                dataSource,
                () -> "receipt-" + receiptCalls.incrementAndGet(),
                countingPlanner(plannerCalls));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<FinalizationAdmissionUnitOfWork.Result> left = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return unitOfWork.admit(request(meetingId));
            });
            Future<FinalizationAdmissionUnitOfWork.Result> right = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return unitOfWork.admit(request(meetingId));
            });
            start.countDown();
            var first = success(left.get(20, TimeUnit.SECONDS));
            var second = success(right.get(20, TimeUnit.SECONDS));
            assertTrue(Set.of(first.code(), second.code()).containsAll(Set.of(
                    FinalizationAdmissionUnitOfWork.SuccessCode.ADMITTED,
                    FinalizationAdmissionUnitOfWork.SuccessCode.REPLAYED)));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, receiptCalls.get());
        assertEquals(1, plannerCalls.get());
        assertAdmissionFactsExactlyMatchFrozenPlan(
                meetingId,
                "receipt-1",
                FinalizationCheckpoint.workflowKey(meetingId));
    }

    @Test
    void commitUnknownAbortsConnectionAndFreshRecoveryUsesAnotherReadOnlyConnection()
            throws Exception {
        String meetingId = createAdmissibleMeeting("commit-unknown");
        AtomicInteger receiptCalls = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();
        FinalizationAdmissionPlanner planner = countingPlanner(plannerCalls);
        CommitUnknownHarness uncertain = commitUnknownOnce(
                dataSource, CommitDisposition.COMMITTED);
        var unitOfWork = unitOfWork(
                uncertain.dataSource(),
                () -> "receipt-" + receiptCalls.incrementAndGet(),
                planner);

        var unknown = failure(unitOfWork.admit(request(meetingId)));
        assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                unknown.code());
        assertTrue(uncertain.aborted().get());
        assertTrue(uncertain.closed().get());
        assertEquals(1, uncertain.borrowed().get());

        var recovered = success(unitOfWork.recover(request(meetingId)));
        assertEquals(FinalizationAdmissionUnitOfWork.SuccessCode.REPLAYED,
                recovered.code());
        assertEquals(2, uncertain.borrowed().get());
        assertTrue(uncertain.recoveryReadOnly().get());
        assertNotSame(
                uncertain.uncertainConnection().get(),
                uncertain.recoveryConnection().get(),
                "outcome-unknown recovery must never reuse the uncertain connection");
        assertEquals(1, receiptCalls.get());
        assertEquals(1, plannerCalls.get());
    }

    @Test
    void commitUnknownAllAbsentRequiresReadOnlyAbsenceBeforeNewAdmission()
            throws Exception {
        String meetingId = createAdmissibleMeeting("commit-unknown-absent");
        MeetingRow meetingBefore = readMeetingRow(meetingId);
        ReservationRow reservationBefore = readReservationRow(
                "reservation-commit-unknown-absent");
        AtomicInteger receiptCalls = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();
        CommitUnknownHarness uncertain = commitUnknownOnce(
                dataSource, CommitDisposition.ROLLED_BACK);
        var unitOfWork = unitOfWork(
                uncertain.dataSource(),
                () -> "receipt-" + receiptCalls.incrementAndGet(),
                countingPlanner(plannerCalls));

        assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                failure(unitOfWork.admit(request(meetingId))).code());
        assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.ADMISSION_NOT_FOUND,
                failure(unitOfWork.recover(request(meetingId))).code());
        assertEquals(2, uncertain.borrowed().get());
        assertTrue(uncertain.recoveryReadOnly().get());
        assertEquals(meetingBefore, readMeetingRow(meetingId));
        assertEquals(reservationBefore, readReservationRow(
                "reservation-commit-unknown-absent"));
        assertNoAdmissionFacts(meetingId);
        assertEquals(1, receiptCalls.get());
        assertEquals(1, plannerCalls.get());

        var retried = success(unitOfWork.admit(request(meetingId)));
        assertEquals(FinalizationAdmissionUnitOfWork.SuccessCode.ADMITTED,
                retried.code());
        assertEquals(3, uncertain.borrowed().get());
        assertEquals(2, receiptCalls.get());
        assertEquals(2, plannerCalls.get());
        assertAdmissionFactsExactlyMatchFrozenPlan(
                meetingId, "receipt-2", retried.workflowKey());
    }

    @Test
    void commitUnknownRecoveryRejectsPartialAndDriftedCommittedFacts()
            throws Exception {
        String partialMeeting = createAdmissibleMeeting("unknown-partial");
        CommitUnknownHarness partialSource = commitUnknownOnce(
                dataSource, CommitDisposition.COMMITTED);
        var partialUnit = unitOfWork(
                partialSource.dataSource(),
                () -> "receipt-unknown-partial",
                new DeterministicMockFinalizationPlanner());
        assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                failure(partialUnit.admit(request(partialMeeting))).code());
        executeMutation(
                "DELETE FROM final_usage_fact WHERE meeting_id=?",
                partialMeeting);
        assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.FACT_INCONSISTENT,
                failure(partialUnit.recover(request(partialMeeting))).code());
        assertTrue(partialSource.recoveryReadOnly().get());

        String driftedMeeting = createAdmissibleMeeting("unknown-drift");
        CommitUnknownHarness driftedSource = commitUnknownOnce(
                dataSource, CommitDisposition.COMMITTED);
        var driftedUnit = unitOfWork(
                driftedSource.dataSource(),
                () -> "receipt-unknown-drift",
                new DeterministicMockFinalizationPlanner());
        assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                failure(driftedUnit.admit(request(driftedMeeting))).code());
        executeMutation("""
                UPDATE workflow_step
                   SET content_digest='sha256:' || repeat('9', 64)
                 WHERE meeting_id=?
                """, driftedMeeting);
        assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.WORKFLOW_CONFLICT,
                failure(driftedUnit.recover(request(driftedMeeting))).code());
        assertTrue(driftedSource.recoveryReadOnly().get());
    }

    @Test
    void readOnlyRecoveryUsesOneRepeatableSnapshotAcrossAllFactTables()
            throws Exception {
        String meetingId = createAdmissibleMeeting("repeatable-read");
        success(unitOfWork(
                dataSource,
                () -> "receipt-repeatable-read",
                new DeterministicMockFinalizationPlanner()).admit(request(meetingId)));

        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch continueRecovery = new CountDownLatch(1);
        var recovering = new JdbcFinalizationAdmissionUnitOfWork(
                dataSource,
                () -> "must-not-run",
                countingPlanner(new AtomicInteger()),
                ignored -> { },
                point -> {
                    snapshotRead.countDown();
                    try {
                        if (!continueRecovery.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("recovery gate timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("recovery gate interrupted");
                    }
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<FinalizationAdmissionUnitOfWork.Result> result = executor.submit(
                    () -> recovering.recover(request(meetingId)));
            assertTrue(snapshotRead.await(10, TimeUnit.SECONDS));
            executeMutation("""
                    UPDATE workflow_step
                       SET content_digest='sha256:' || repeat('1', 64)
                     WHERE meeting_id=?
                    """, meetingId);
            continueRecovery.countDown();

            assertEquals(FinalizationAdmissionUnitOfWork.SuccessCode.REPLAYED,
                    success(result.get(20, TimeUnit.SECONDS)).code(),
                    "one recovery must not splice a newer workflow into an older meeting snapshot");
        } finally {
            continueRecovery.countDown();
            executor.shutdownNow();
        }

        assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.WORKFLOW_CONFLICT,
                failure(unitOfWork(
                        dataSource,
                        () -> "must-not-run",
                        new DeterministicMockFinalizationPlanner())
                        .recover(request(meetingId))).code(),
                "a fresh recovery must observe and reject the committed drift");
    }

    @Test
    void deploymentSchemaPinRejectsTemporarySchemaBeforeBusinessReads() throws Exception {
        String meetingId = createAdmissibleMeeting("temp-schema");
        DataSource temporary = currentSchemaIsTemporary(dataSource);
        var result = failure(unitOfWork(
                temporary,
                () -> "receipt-temp",
                new DeterministicMockFinalizationPlanner()).admit(request(meetingId)));
        assertEquals(FinalizationAdmissionUnitOfWork.ErrorCode.INTERNAL, result.code());
        assertNoAdmissionFacts(meetingId);
    }

    private String createAdmissibleMeeting(String suffix) throws Exception {
        String meetingId = "mtg_admission_" + suffix.replace('-', '_');
        MeetingGrant grant = new MeetingGrant(
                "grant-" + suffix,
                DEPLOYMENT,
                SUBJECT,
                "profile",
                1,
                Set.of("asr"),
                Set.of("asr"),
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

        var started = new JdbcMeetingStartExecutionUnitOfWork(
                dataSource, new CapabilityPlanner()).start(
                new MeetingStartExecutionUnitOfWork.StartRequest(
                        DEPLOYMENT,
                        SUBJECT,
                        meetingId,
                        STARTED_AT,
                        "Admission test",
                        profile(),
                        Set.of("asr"),
                        new CapabilityDescriptor(Set.of("asr")),
                        DeploymentLimits.NONE));
        assertTrue(started instanceof MeetingStartExecutionUnitOfWork.Success);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcMeetingRepository.VersionedMeeting locked =
                    JdbcMeetingRepository.findVersionedForUpdate(connection, meetingId)
                            .orElseThrow();
            MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(locked.snapshot());
            aggregate.apply(MeetingEvent.START, STARTED_AT.plusSeconds(1));
            aggregate.apply(MeetingEvent.STOP_REQUESTED, STOPPED_AT);
            JdbcMeetingRepository.updateOwnedExisting(
                    connection, locked, aggregate.snapshot());
            MeetingFinalizationStore.Result pending =
                    JdbcMeetingFinalizationStore.requestInputCompletion(
                            connection, meetingId);
            assertTrue(pending instanceof MeetingFinalizationStore.Applied);
            connection.commit();
        }
        assertMeeting(meetingId, MeetingState.STOPPING,
                "INPUT_COMPLETE_PENDING", ManifestStatus.NOT_STARTED.name());
        return meetingId;
    }

    private static ProductProfile profile() {
        return new ProductProfile(
                "profile",
                1,
                Set.of("asr"),
                Set.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("asr", List.of("caption")),
                Map.of("asr", List.of("transcript.final")));
    }

    private static FinalizationAdmissionUnitOfWork.AdmissionRequest request(
            String meetingId) {
        return new FinalizationAdmissionUnitOfWork.AdmissionRequest(
                DEPLOYMENT,
                SUBJECT,
                meetingId,
                new FinalizationInputFact(
                        meetingId,
                        "binding-" + meetingId,
                        42,
                        60_000,
                        "zh-CN",
                        List.of("en-US"),
                        ACCEPTED_AT,
                        "sha256:input-" + meetingId));
    }

    private static JdbcFinalizationAdmissionUnitOfWork unitOfWork(
            DataSource source,
            com.czur.reference.application.port.FinalizationReceiptGenerator receiptGenerator,
            FinalizationAdmissionPlanner planner) {
        return new JdbcFinalizationAdmissionUnitOfWork(
                source, receiptGenerator, planner);
    }

    private static FinalizationAdmissionPlanner countingPlanner(AtomicInteger calls) {
        DeterministicMockFinalizationPlanner delegate =
                new DeterministicMockFinalizationPlanner();
        return (deployment, subject, input, execution, reservation) -> {
            calls.incrementAndGet();
            return delegate.derive(
                    deployment, subject, input, execution, reservation);
        };
    }

    private void assertAdmissionFactsExactlyMatchFrozenPlan(
            String meetingId,
            String expectedReceipt,
            WorkflowStep.Key expectedKey) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            WorkflowStep persisted = JdbcWorkflowStepStore.find(
                    connection, expectedKey).orElseThrow();
            FinalizationCheckpoint decoded = FinalizationCheckpointJson.decode(
                    persisted.canonicalPayload());
            FinalizationExecutionFact execution =
                    new JdbcFinalizationExecutionFactStore(connection)
                            .find(meetingId).orElseThrow();
            String reservationId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT usage_reservation_id FROM meeting WHERE meeting_id=?
                    """)) {
                statement.setString(1, meetingId);
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    reservationId = rows.getString("usage_reservation_id");
                    assertFalse(rows.next());
                }
            }
            UsageReservation reservation = JdbcUsageReservationStore.findReservation(
                    connection,
                    DEPLOYMENT,
                    SUBJECT,
                    reservationId)
                    .orElseThrow();
            DeterministicMockFinalizationPlanner.Plan expected =
                    new DeterministicMockFinalizationPlanner().derive(
                            DEPLOYMENT,
                            SUBJECT,
                            request(meetingId).inputFact(),
                            execution,
                            reservation);

            assertEquals(expectedKey, persisted.key());
            assertEquals(FinalizationCheckpoint.STEP_TYPE,
                    persisted.key().stepType());
            assertEquals(FinalizationCheckpoint.CHECKPOINT_TYPE,
                    persisted.checkpointType());
            assertEquals(ACCEPTED_AT, persisted.nextAttemptAt());
            assertEquals(ACCEPTED_AT, persisted.updatedAt());
            assertEquals(expected.workflowStep(), persisted,
                    "persisted Workflow Definition must exactly match Planner output");
            assertEquals(expected.checkpoint(), decoded,
                    "strictly decoded checkpoint must exactly match Planner output");
            assertEquals(expected.checkpoint().commitPlan(), decoded.commitPlan(),
                    "commit-plan fields must be frozen without reconstruction drift");

            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT finalization_receipt_id
                      FROM meeting
                     WHERE meeting_id=?
                    """)) {
                statement.setString(1, meetingId);
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(expectedReceipt,
                            rows.getString("finalization_receipt_id"));
                    assertFalse(rows.next());
                }
            }
            connection.rollback();
        }
    }

    private void persistV008Only(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcDurableInputFactStore.persist(connection, request(meetingId).inputFact());
            connection.commit();
        }
    }

    private void persistV008AndV009(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            FinalizationInputFact input = request(meetingId).inputFact();
            JdbcDurableInputFactStore.persist(connection, input);
            JdbcFinalUsageFactStore.persist(
                    connection, FinalUsageFact.fromInput(input));
            connection.commit();
        }
    }

    private void persistReceiptOnly(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            MeetingFinalizationStore.Result accepted =
                    JdbcMeetingFinalizationStore.acceptInput(
                            connection,
                            meetingId,
                            ACCEPTED_AT,
                            () -> "receipt-partial-only");
            assertTrue(accepted instanceof MeetingFinalizationStore.Applied);
            connection.commit();
        }
    }

    private void commitReservation(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE usage_reservation
                        SET status='COMMITTED', actual_amount=60000,
                            committed_at=?, updated_at=?, version=version+1
                      WHERE reservation_id=(
                            SELECT usage_reservation_id
                              FROM meeting
                             WHERE meeting_id=?)
                        AND status='RESERVED' AND version=1
                     """)) {
            java.sql.Timestamp committedAt = java.sql.Timestamp.from(
                    ACCEPTED_AT.plusSeconds(1));
            statement.setTimestamp(1, committedAt);
            statement.setTimestamp(2, committedAt);
            statement.setString(3, meetingId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void executeReservationMutation(String sql, String reservationId)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reservationId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void executeReservationMutation(
            String sql,
            String reservationId,
            Instant timestamp) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            java.sql.Timestamp value = java.sql.Timestamp.from(timestamp);
            statement.setTimestamp(1, value);
            statement.setTimestamp(2, value);
            statement.setString(3, reservationId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void assertNoAdmissionFacts(String meetingId) throws Exception {
        assertMeeting(meetingId, MeetingState.STOPPING,
                "INPUT_COMPLETE_PENDING", ManifestStatus.NOT_STARTED.name());
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(0, count(connection,
                    "SELECT count(*) FROM durable_input_fact WHERE meeting_id=?", meetingId));
            assertEquals(0, count(connection,
                    "SELECT count(*) FROM final_usage_fact WHERE meeting_id=?", meetingId));
            assertEquals(0, count(connection,
                    "SELECT count(*) FROM workflow_step WHERE meeting_id=?", meetingId));
            assertEquals(0, count(connection,
                    "SELECT count(*) FROM meeting_manifest_revision WHERE meeting_id=?",
                    meetingId));
        }
    }

    private void assertMeeting(
            String meetingId,
            MeetingState state,
            String inputState,
            String manifestState) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT state, input_state, manifest_state,
                            finalization_receipt_id
                       FROM meeting WHERE meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(state.name(), rows.getString("state"));
                assertEquals(inputState, rows.getString("input_state"));
                assertEquals(manifestState, rows.getString("manifest_state"));
                if ("INPUT_ACCEPTED".equals(inputState)) {
                    assertFalse(rows.getString("finalization_receipt_id").isBlank());
                } else {
                    assertEquals(null, rows.getString("finalization_receipt_id"));
                }
            }
        }
    }

    private MeetingRow readMeetingRow(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT state, input_state, finalization_receipt_id,
                            manifest_state, aggregate_version, updated_at
                       FROM meeting WHERE meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return new MeetingRow(
                        rows.getString("state"),
                        rows.getString("input_state"),
                        rows.getString("finalization_receipt_id"),
                        rows.getString("manifest_state"),
                        rows.getLong("aggregate_version"),
                        rows.getTimestamp("updated_at").toInstant());
            }
        }
    }

    private ReservationRow readReservationRow(String reservationId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT status, version, actual_amount, committed_at,
                            deployment_scope, subject_scope, grant_id,
                            expected_meeting_id, bound_meeting_id, bound_at
                       FROM usage_reservation WHERE reservation_id=?
                     """)) {
            statement.setString(1, reservationId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return new ReservationRow(
                        rows.getString("status"),
                        rows.getLong("version"),
                        rows.getBigDecimal("actual_amount"),
                        rows.getTimestamp("committed_at") == null
                                ? null : rows.getTimestamp("committed_at").toInstant(),
                        rows.getString("deployment_scope"),
                        rows.getString("subject_scope"),
                        rows.getString("grant_id"),
                        rows.getString("expected_meeting_id"),
                        rows.getString("bound_meeting_id"),
                        rows.getTimestamp("bound_at").toInstant());
            }
        }
    }

    private void installGeneratingRejectionTrigger() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION reject_generating() RETURNS trigger AS $$
                    BEGIN
                        IF NEW.manifest_state = 'GENERATING' THEN
                            RETURN NULL;
                        END IF;
                        RETURN NEW;
                    END
                    $$ LANGUAGE plpgsql
                    """);
            statement.execute("""
                    CREATE TRIGGER reject_generating_update
                    BEFORE UPDATE ON meeting
                    FOR EACH ROW EXECUTE FUNCTION reject_generating()
                    """);
        }
    }

    private void executeMutation(String sql, String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, meetingId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static int count(Connection connection, String sql, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static FinalizationAdmissionUnitOfWork.Success success(
            FinalizationAdmissionUnitOfWork.Result result) {
        assertTrue(result instanceof FinalizationAdmissionUnitOfWork.Success,
                () -> "expected success but got " + result);
        return (FinalizationAdmissionUnitOfWork.Success) result;
    }

    private static FinalizationAdmissionUnitOfWork.Failure failure(
            FinalizationAdmissionUnitOfWork.Result result) {
        assertTrue(result instanceof FinalizationAdmissionUnitOfWork.Failure,
                () -> "expected failure but got " + result);
        return (FinalizationAdmissionUnitOfWork.Failure) result;
    }

    private static CommitUnknownHarness commitUnknownOnce(
            DataSource delegate,
            CommitDisposition disposition) {
        AtomicBoolean injected = new AtomicBoolean();
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean recoveryReadOnly = new AtomicBoolean();
        AtomicInteger borrowed = new AtomicInteger();
        AtomicReference<Connection> uncertainConnection = new AtomicReference<>();
        AtomicReference<Connection> recoveryConnection = new AtomicReference<>();
        DataSource wrapped = (DataSource) Proxy.newProxyInstance(
                JdbcFinalizationAdmissionUnitOfWorkIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) {
                        return method.invoke(delegate, args);
                    }
                    Connection connection = args == null || args.length == 0
                            ? delegate.getConnection()
                            : delegate.getConnection((String) args[0], (String) args[1]);
                    int ordinal = borrowed.incrementAndGet();
                    if (injected.compareAndSet(false, true)) {
                        uncertainConnection.set(connection);
                        return commitUnknownConnection(
                                connection, disposition, aborted, closed);
                    }
                    if (ordinal == 2) {
                        recoveryConnection.set(connection);
                    }
                    return readOnlyTrackingConnection(
                            connection, recoveryReadOnly);
                });
        return new CommitUnknownHarness(
                wrapped,
                aborted,
                closed,
                recoveryReadOnly,
                borrowed,
                uncertainConnection,
                recoveryConnection);
    }

    private static Connection commitUnknownConnection(
            Connection delegate,
            CommitDisposition disposition,
            AtomicBoolean aborted,
            AtomicBoolean closed) {
        return (Connection) Proxy.newProxyInstance(
                JdbcFinalizationAdmissionUnitOfWorkIntegrationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("commit")) {
                        if (disposition == CommitDisposition.COMMITTED) {
                            delegate.commit();
                        } else {
                            delegate.rollback();
                        }
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

    private static Connection readOnlyTrackingConnection(
            Connection delegate,
            AtomicBoolean recoveryReadOnly) {
        return (Connection) Proxy.newProxyInstance(
                JdbcFinalizationAdmissionUnitOfWorkIntegrationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setReadOnly")
                            && args != null
                            && args.length == 1
                            && Boolean.TRUE.equals(args[0])) {
                        recoveryReadOnly.set(true);
                    }
                    return method.invoke(delegate, args);
                });
    }

    /** 返回 current_schema=pg_temp 的连接，证明业务读取前会关闭式失败。 */
    private static DataSource currentSchemaIsTemporary(DataSource delegate) {
        return (DataSource) Proxy.newProxyInstance(
                JdbcFinalizationAdmissionUnitOfWorkIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) {
                        return method.invoke(delegate, args);
                    }
                    Connection connection = args == null || args.length == 0
                            ? delegate.getConnection()
                            : delegate.getConnection((String) args[0], (String) args[1]);
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("CREATE TEMP TABLE schema_probe(value integer)");
                        statement.execute("SET search_path TO pg_temp");
                    }
                    return connection;
                });
    }

    /**
     * 每次借出连接时在 pg_temp 建立 Admission 会访问的全部同名表；部署 schema
     * 仍保持为连接的 currentSchema。正确实现必须在第一次业务读取前把部署 schema
     * 显式放在 pg_temp 前面，因此这些空影子表不能遮蔽真实事实。
     */
    private static DataSource allTemporaryShadows(
            DataSource delegate,
            String deploymentSchema) {
        List<String> tableNames = List.of(
                "meeting",
                "finalization_execution_fact",
                "durable_input_fact",
                "final_usage_fact",
                "workflow_step",
                "meeting_manifest_revision",
                "usage_reservation");
        return (DataSource) Proxy.newProxyInstance(
                JdbcFinalizationAdmissionUnitOfWorkIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) {
                        return method.invoke(delegate, args);
                    }
                    Connection connection = args == null || args.length == 0
                            ? delegate.getConnection()
                            : delegate.getConnection((String) args[0], (String) args[1]);
                    try (Statement statement = connection.createStatement()) {
                        for (String table : tableNames) {
                            statement.execute("CREATE TEMP TABLE " + table
                                    + " (LIKE " + quote(deploymentSchema) + "."
                                    + table + " INCLUDING ALL)");
                        }
                    }
                    return connection;
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

    private record MeetingRow(
            String state,
            String inputState,
            String receipt,
            String manifestState,
            long aggregateVersion,
            Instant updatedAt) {
    }

    private record ReservationRow(
            String state,
            long version,
            java.math.BigDecimal actualAmount,
            Instant committedAt,
            String deploymentScope,
            String subjectScope,
            String grantId,
            String expectedMeetingId,
            String boundMeetingId,
            Instant boundAt) {
    }

    private record DriftCase(
            String name,
            String sql,
            FinalizationAdmissionUnitOfWork.ErrorCode expected) {
    }

    private record PartialCase(String name, PartialMutation mutation) {
    }

    @FunctionalInterface
    private interface PartialMutation {
        void apply(String meetingId) throws Exception;
    }

    private record ReservationCase(String name, ReservationMutation mutation) {
    }

    private record ReplayReservationCase(
            String name,
            ReservationMutation mutation) {
    }

    @FunctionalInterface
    private interface ReservationMutation {
        void apply(String meetingId, String reservationId) throws Exception;
    }

    private enum CommitDisposition {
        COMMITTED,
        ROLLED_BACK
    }

    private record CommitUnknownHarness(
            DataSource dataSource,
            AtomicBoolean aborted,
            AtomicBoolean closed,
            AtomicBoolean recoveryReadOnly,
            AtomicInteger borrowed,
            AtomicReference<Connection> uncertainConnection,
            AtomicReference<Connection> recoveryConnection) {
    }

    private static final class CapturingHandler extends Handler {
        private final StringBuilder content = new StringBuilder();

        @Override
        public void publish(LogRecord record) {
            if (!isLoggable(record)) {
                return;
            }
            content.append(record.getMessage()).append('\n');
            if (record.getParameters() != null) {
                for (Object parameter : record.getParameters()) {
                    content.append(String.valueOf(parameter)).append('\n');
                }
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        String content() {
            return content.toString();
        }
    }

    private static final class InjectedAdmissionFailure extends RuntimeException {
    }
}
