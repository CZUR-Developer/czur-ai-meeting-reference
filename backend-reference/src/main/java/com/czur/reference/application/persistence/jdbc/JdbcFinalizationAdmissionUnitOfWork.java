package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.finalization.DeterministicMockFinalizationPlanner;
import com.czur.reference.application.finalization.FinalizationAdmissionPlanner;
import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.finalization.FinalizationCommitPlan;
import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.port.DurableInputFactStore;
import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.FinalUsageFactStore;
import com.czur.reference.application.port.FinalizationAdmissionUnitOfWork;
import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.FinalizationReceiptGenerator;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.MeetingFinalizationStore;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.json.FinalizationCheckpointJson;
import com.czur.reference.protocol.json.FinalizationExecutionFactJson;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PostgreSQL 单连接实现的最终收口 Admission 工作单元。
 *
 * <p>首次路径只取得一条连接并按固定顺序完成：固定部署 schema、锁定 Meeting、
 * 核对 owner/Profile/execution fact、锁定主 Reservation、保存 V008、从 V008 保存
 * V009、生成并保存 receipt、从冻结事实形成 checkpoint、注册 Workflow，最后把
 * Manifest 状态推进到 GENERATING。所有步骤共享同一事务；本类不读取 Clock，
 * Workflow 首次调度时间和 Meeting 更新时间都使用 V008 的 {@code acceptedAt}。</p>
 *
 * <p>重放路径不会重新运行 Planner，也不会读取 Reservation 当前版本。它从持久
 * Workflow 的 canonical payload 恢复 checkpoint，并与 V008、V009、execution fact、
 * owner 和不可变 Workflow Definition 逐项核对。这样权益已经由后续 Worker 提交，
 * 或 Workflow 已经被领取/完成时，Admission 重试仍不会改变历史计划。</p>
 *
 * <p>commit acknowledgement unknown 时连接会被 abort，不会恢复到连接池。
 * 后续调用只能重新进入本方法的只读重放分支，不能从异常推断 receipt 或直接开始
 * Artifact、模型、对象存储、权益 Provider 等外部动作。</p>
 */
public final class JdbcFinalizationAdmissionUnitOfWork
        implements FinalizationAdmissionUnitOfWork {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcFinalizationAdmissionUnitOfWork.class.getName());
    private static final String PIN_SEARCH_PATH_SQL = """
            SELECT set_config(
                'search_path', format('%I, pg_temp', current_schema()), TRUE)
            """;

    private final DataSource dataSource;
    private final FinalizationReceiptGenerator receiptGenerator;
    private final FinalizationAdmissionPlanner planner;
    private final FailureProbe failureProbe;
    private final RecoveryProbe recoveryProbe;

    public JdbcFinalizationAdmissionUnitOfWork(
            DataSource dataSource,
            FinalizationReceiptGenerator receiptGenerator,
            FinalizationAdmissionPlanner planner) {
        this(dataSource, receiptGenerator, planner, ignored -> { }, ignored -> { });
    }

    /** 仅供同包故障门禁注入事务窗口；生产装配始终使用无操作探针。 */
    JdbcFinalizationAdmissionUnitOfWork(
            DataSource dataSource,
            FinalizationReceiptGenerator receiptGenerator,
            FinalizationAdmissionPlanner planner,
            FailureProbe failureProbe) {
        this(dataSource, receiptGenerator, planner, failureProbe, ignored -> { });
    }

    /** 同包恢复并发门禁构造器；两个探针在生产装配中均为无操作。 */
    JdbcFinalizationAdmissionUnitOfWork(
            DataSource dataSource,
            FinalizationReceiptGenerator receiptGenerator,
            FinalizationAdmissionPlanner planner,
            FailureProbe failureProbe,
            RecoveryProbe recoveryProbe) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.receiptGenerator = Objects.requireNonNull(
                receiptGenerator, "receiptGenerator");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.failureProbe = Objects.requireNonNull(failureProbe, "failureProbe");
        this.recoveryProbe = Objects.requireNonNull(recoveryProbe, "recoveryProbe");
    }

    @Override
    public Result admit(AdmissionRequest request) {
        return execute(request);
    }

    @Override
    public Result recover(AdmissionRequest request) {
        return recoverReadOnly(request);
    }

    /** outcome-unknown 后使用全新只读连接恢复，不取得行锁或执行任何 DML。 */
    private Result recoverReadOnly(AdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        Connection connection = null;
        boolean previousAutoCommit = true;
        boolean previousReadOnly = false;
        int previousIsolation = Connection.TRANSACTION_READ_COMMITTED;
        boolean transactionStarted = false;
        TransactionResolution resolution = new TransactionResolution();
        Phase phase = Phase.OPEN_CONNECTION;
        try {
            connection = dataSource.getConnection();
            previousAutoCommit = connection.getAutoCommit();
            previousReadOnly = connection.isReadOnly();
            previousIsolation = connection.getTransactionIsolation();
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            transactionStarted = true;

            phase = Phase.SCHEMA_PIN;
            pinDeploymentSchema(connection);
            phase = Phase.RECOVERY_MEETING_READ;
            Optional<JdbcMeetingRepository.PublicationMeeting> meetingOptional =
                    JdbcMeetingRepository.findManifestPublication(
                            connection, request.meetingId());
            if (meetingOptional.isEmpty()) {
                return failAndRollback(connection, resolution,
                        ErrorCode.MEETING_NOT_FOUND, phase);
            }
            JdbcMeetingRepository.PublicationMeeting meeting =
                    meetingOptional.orElseThrow();
            recoveryProbe.hit(RecoveryPoint.AFTER_MEETING_SNAPSHOT);
            if (!request.deploymentScope().equals(
                    meeting.snapshot().deploymentScope())
                    || !request.subjectScope().equals(
                    meeting.snapshot().subjectScope())) {
                return failAndRollback(connection, resolution,
                        ErrorCode.ACCESS_DENIED, Phase.ACCESS_CHECK);
            }

            Optional<FinalizationExecutionFact> executionOptional =
                    new JdbcFinalizationExecutionFactStore(connection)
                            .find(request.meetingId());
            if (executionOptional.isEmpty()) {
                return failAndRollback(connection, resolution,
                        ErrorCode.EXECUTION_FACT_MISSING,
                        Phase.EXECUTION_FACT_READ);
            }
            FinalizationExecutionFact execution = executionOptional.orElseThrow();
            if (!meeting.snapshot().meetingId().equals(execution.meetingId())) {
                return failAndRollback(connection, resolution,
                        ErrorCode.FACT_INCONSISTENT,
                        Phase.EXECUTION_FACT_READ);
            }
            if (!meeting.snapshot().profileId().equals(
                    execution.productProfile().id())
                    || meeting.snapshot().profileRevision()
                    != execution.productProfile().revision()) {
                return failAndRollback(connection, resolution,
                        ErrorCode.PROFILE_MISMATCH,
                        Phase.EXECUTION_FACT_READ);
            }

            if (isReplayablePostAdmission(meeting)) {
                return replay(
                        connection, resolution, request, meeting, execution);
            }
            if (!isFirstAdmissionState(meeting)) {
                return failAndRollback(
                        connection,
                        resolution,
                        meeting.finalization().isAccepted()
                                || hasFirstAdmissionShape(meeting)
                                ? ErrorCode.FACT_INCONSISTENT
                                : ErrorCode.INVALID_MEETING_STATE,
                        Phase.STATE_CHECK);
            }

            phase = Phase.RECOVERY_ABSENCE_CHECK;
            boolean noAdmissionFacts =
                    new JdbcManifestFactStore(connection)
                            .findLatest(request.meetingId()).isEmpty()
                    && JdbcDurableInputFactStore.find(
                            connection, request.meetingId()).isEmpty()
                    && JdbcFinalUsageFactStore.find(
                            connection, request.meetingId()).isEmpty()
                    && JdbcWorkflowStepStore.find(
                            connection,
                            FinalizationCheckpoint.workflowKey(
                                    request.meetingId())).isEmpty();
            if (!noAdmissionFacts) {
                return failAndRollback(connection, resolution,
                        ErrorCode.FACT_INCONSISTENT, phase);
            }
            MeetingAggregate.MeetingSnapshot snapshot = meeting.snapshot();
            if (snapshot.usageReservationId() == null) {
                return failAndRollback(connection, resolution,
                        ErrorCode.RESERVATION_INCONSISTENT, phase);
            }
            Optional<UsageReservation> reservation =
                    JdbcUsageReservationStore.findReservation(
                            connection,
                            request.deploymentScope(),
                            request.subjectScope(),
                            snapshot.usageReservationId());
            if (reservation.isEmpty()
                    || !validUnadmittedReservation(
                    snapshot, reservation.orElseThrow())) {
                return failAndRollback(connection, resolution,
                        ErrorCode.RESERVATION_INCONSISTENT, phase);
            }
            return failAndRollback(connection, resolution,
                    ErrorCode.ADMISSION_NOT_FOUND, phase);
        } catch (JdbcMeetingRepository.PublicationSnapshotException
                 | JdbcUsageReservationStore.CorruptUsageDataException failure) {
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            return loggedFailure(ErrorCode.FACT_INCONSISTENT, phase, null);
        } catch (JdbcFinalizationExecutionFactStore.StoredFactException failure) {
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            ErrorCode code = failure.kind()
                    == JdbcFinalizationExecutionFactStore.FailureKind.STORAGE_UNAVAILABLE
                    ? ErrorCode.STORAGE_UNAVAILABLE : ErrorCode.FACT_INCONSISTENT;
            return loggedFailure(code, phase, null);
        } catch (JdbcManifestFactStore.StoredFactException failure) {
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            ErrorCode code = failure.kind()
                    == JdbcManifestFactStore.FailureKind.STORAGE_UNAVAILABLE
                    ? ErrorCode.STORAGE_UNAVAILABLE : ErrorCode.FACT_INCONSISTENT;
            return loggedFailure(code, phase, null);
        } catch (SQLException failure) {
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            ErrorCode code = JdbcReadLookupSupport.classify(failure)
                    == JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE
                    ? ErrorCode.STORAGE_UNAVAILABLE : ErrorCode.INTERNAL;
            return loggedFailure(code, phase,
                    JdbcReadLookupSupport.safeSqlState(failure));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            return loggedFailure(ErrorCode.FACT_INCONSISTENT, phase, null);
        } catch (RuntimeException failure) {
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            return loggedFailure(ErrorCode.INTERNAL, phase, null);
        } finally {
            if (resolution.rollbackConfirmed() || !transactionStarted) {
                if (!restoreReadOnlyConnection(
                        connection,
                        previousAutoCommit,
                        previousReadOnly,
                        previousIsolation)) {
                    abortQuietly(connection);
                }
            } else {
                abortQuietly(connection);
            }
            closeQuietly(connection);
        }
    }

    private Result execute(AdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        Connection connection = null;
        boolean previousAutoCommit = true;
        boolean transactionStarted = false;
        boolean commitStarted = false;
        boolean commitCompleted = false;
        TransactionResolution resolution = new TransactionResolution();
        Phase phase = Phase.OPEN_CONNECTION;
        try {
            connection = dataSource.getConnection();
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            transactionStarted = true;

            phase = Phase.SCHEMA_PIN;
            pinDeploymentSchema(connection);

            phase = Phase.MEETING_LOCK;
            Optional<JdbcMeetingRepository.PublicationMeeting> lockedOptional =
                    JdbcMeetingRepository.findManifestPublicationForUpdate(
                            connection, request.meetingId());
            if (lockedOptional.isEmpty()) {
                return failAndRollback(connection, resolution,
                        ErrorCode.MEETING_NOT_FOUND, phase);
            }
            JdbcMeetingRepository.PublicationMeeting locked = lockedOptional.orElseThrow();
            MeetingAggregate.MeetingSnapshot meeting = locked.snapshot();

            phase = Phase.ACCESS_CHECK;
            if (!request.deploymentScope().equals(meeting.deploymentScope())
                    || !request.subjectScope().equals(meeting.subjectScope())) {
                return failAndRollback(connection, resolution,
                        ErrorCode.ACCESS_DENIED, phase);
            }

            phase = Phase.EXECUTION_FACT_READ;
            Optional<FinalizationExecutionFact> executionOptional =
                    new JdbcFinalizationExecutionFactStore(connection)
                            .find(request.meetingId());
            if (executionOptional.isEmpty()) {
                return failAndRollback(connection, resolution,
                        ErrorCode.EXECUTION_FACT_MISSING, phase);
            }
            FinalizationExecutionFact execution = executionOptional.orElseThrow();
            if (!meeting.meetingId().equals(execution.meetingId())) {
                return failAndRollback(connection, resolution,
                        ErrorCode.FACT_INCONSISTENT, phase);
            }
            if (!meeting.profileId().equals(execution.productProfile().id())
                    || meeting.profileRevision() != execution.productProfile().revision()) {
                return failAndRollback(connection, resolution,
                        ErrorCode.PROFILE_MISMATCH, phase);
            }

            phase = Phase.STATE_CHECK;
            if (isReplayablePostAdmission(locked)) {
                return replay(connection, resolution, request, locked, execution);
            }
            if (!isFirstAdmissionState(locked)) {
                ErrorCode code = locked.finalization().isAccepted()
                        || hasFirstAdmissionShape(locked)
                        ? ErrorCode.FACT_INCONSISTENT
                        : ErrorCode.INVALID_MEETING_STATE;
                return failAndRollback(connection, resolution, code, phase);
            }

            phase = Phase.PREEXISTING_FACT_CHECK;
            if (new JdbcManifestFactStore(connection).findLatest(request.meetingId()).isPresent()
                    || JdbcDurableInputFactStore.find(
                            connection, request.meetingId()).isPresent()
                    || JdbcFinalUsageFactStore.find(
                            connection, request.meetingId()).isPresent()
                    || JdbcWorkflowStepStore.find(
                            connection,
                            FinalizationCheckpoint.workflowKey(request.meetingId())).isPresent()) {
                return failAndRollback(connection, resolution,
                        ErrorCode.FACT_INCONSISTENT, phase);
            }

            phase = Phase.RESERVATION_LOCK;
            if (meeting.usageReservationId() == null) {
                return failAndRollback(connection, resolution,
                        ErrorCode.RESERVATION_INCONSISTENT, phase);
            }
            Optional<JdbcUsageReservationStore.LockedReservation> reservationOptional =
                    JdbcUsageReservationStore.lockReservationForOwnedCreation(
                            connection,
                            request.deploymentScope(),
                            request.subjectScope(),
                            meeting.usageReservationId());
            if (reservationOptional.isEmpty()) {
                return failAndRollback(connection, resolution,
                        ErrorCode.RESERVATION_INCONSISTENT, phase);
            }
            UsageReservation reservation = reservationOptional.orElseThrow().reservation();
            if (!validInitialReservation(
                    request,
                    meeting,
                    reservationOptional.orElseThrow())) {
                return failAndRollback(connection, resolution,
                        ErrorCode.RESERVATION_INCONSISTENT, phase);
            }

            phase = Phase.INPUT_PREFLIGHT;
            MeetingFinalizationStore.Result preflight =
                    JdbcMeetingFinalizationStore.preflightAcceptInput(
                            connection,
                            request.meetingId(),
                            request.inputFact().acceptedAt());
            if (!(preflight instanceof MeetingFinalizationStore.Found)) {
                return failAndRollback(connection, resolution,
                        ErrorCode.FACT_INCONSISTENT, phase);
            }

            phase = Phase.INPUT_FACT_WRITE;
            failureProbe.hit(FailurePoint.BEFORE_FIRST_WRITE);
            DurableInputFactStore.PersistResult inputPersisted =
                    JdbcDurableInputFactStore.persist(
                            connection, request.inputFact());
            if (inputPersisted.status() != DurableInputFactStore.PersistStatus.APPLIED) {
                return failAndRollback(connection, resolution,
                        inputPersisted.status() == DurableInputFactStore.PersistStatus.CONFLICT
                                ? ErrorCode.INPUT_FACT_CONFLICT
                                : ErrorCode.FACT_INCONSISTENT,
                        phase);
            }
            failureProbe.hit(FailurePoint.AFTER_V008);

            phase = Phase.FINAL_USAGE_WRITE;
            FinalUsageFact finalUsage = FinalUsageFact.fromInput(inputPersisted.fact());
            FinalUsageFactStore.PersistResult usagePersisted =
                    JdbcFinalUsageFactStore.persist(connection, finalUsage);
            if (usagePersisted.status() != FinalUsageFactStore.PersistStatus.APPLIED) {
                return failAndRollback(connection, resolution,
                        usagePersisted.status() == FinalUsageFactStore.PersistStatus.CONFLICT
                                ? ErrorCode.FINAL_USAGE_CONFLICT
                                : ErrorCode.FACT_INCONSISTENT,
                        phase);
            }
            failureProbe.hit(FailurePoint.AFTER_V009);

            phase = Phase.RECEIPT_GENERATION;
            String receipt = newReceipt();
            phase = Phase.RECEIPT_WRITE;
            MeetingFinalizationStore.Result accepted =
                    JdbcMeetingFinalizationStore.acceptInput(
                            connection,
                            request.meetingId(),
                            request.inputFact().acceptedAt(),
                            () -> receipt);
            if (!(accepted instanceof MeetingFinalizationStore.Applied applied)
                    || !applied.fact().isAccepted()) {
                return failAndRollback(connection, resolution,
                        ErrorCode.FACT_INCONSISTENT, phase);
            }
            failureProbe.hit(FailurePoint.AFTER_RECEIPT);

            phase = Phase.CHECKPOINT_PLAN;
            DeterministicMockFinalizationPlanner.Plan plan = planner.derive(
                    request.deploymentScope(),
                    request.subjectScope(),
                    inputPersisted.fact(),
                    execution,
                    reservation);
            if (!plan.finalUsage().equals(usagePersisted.fact())) {
                return failAndRollback(connection, resolution,
                        ErrorCode.FACT_INCONSISTENT, phase);
            }
            failureProbe.hit(FailurePoint.AFTER_CHECKPOINT);

            phase = Phase.WORKFLOW_REGISTER;
            WorkflowStepStore.RegisterResult registered =
                    JdbcWorkflowStepStore.register(connection, plan.workflowStep());
            if (registered.status() != WorkflowStepStore.RegisterStatus.APPLIED) {
                return failAndRollback(connection, resolution,
                        registered.status() == WorkflowStepStore.RegisterStatus.CONFLICT
                                ? ErrorCode.WORKFLOW_CONFLICT
                                : ErrorCode.FACT_INCONSISTENT,
                        phase);
            }
            failureProbe.hit(FailurePoint.AFTER_WORKFLOW);

            phase = Phase.MANIFEST_BEGIN;
            Optional<JdbcMeetingRepository.PublicationMeeting> acceptedMeetingOptional =
                    JdbcMeetingRepository.findManifestPublicationForUpdate(
                            connection, request.meetingId());
            if (acceptedMeetingOptional.isEmpty()) {
                return failAndRollback(connection, resolution,
                        ErrorCode.FACT_INCONSISTENT, phase);
            }
            JdbcMeetingRepository.PublicationMeeting acceptedMeeting =
                    acceptedMeetingOptional.orElseThrow();
            if (acceptedMeeting.snapshot().state() != MeetingState.FINALIZING
                    || acceptedMeeting.finalization().inputState()
                    != InputState.INPUT_ACCEPTED
                    || acceptedMeeting.manifestState() != ManifestStatus.NOT_STARTED
                    || new JdbcManifestFactStore(connection)
                    .findLatest(request.meetingId()).isPresent()
                    || !JdbcMeetingRepository.updateManifestPublication(
                    connection,
                    acceptedMeeting,
                    MeetingState.FINALIZING,
                    ManifestStatus.GENERATING,
                    request.inputFact().acceptedAt())) {
                return failAndRollback(connection, resolution,
                        ErrorCode.MANIFEST_CONFLICT, phase);
            }
            failureProbe.hit(FailurePoint.AFTER_MANIFEST);

            phase = Phase.COMMIT;
            failureProbe.hit(FailurePoint.BEFORE_COMMIT);
            commitStarted = true;
            connection.commit();
            commitCompleted = true;
            LOGGER.log(Level.INFO,
                    "最终收口 Admission 已原子提交 state=FINALIZING manifest=GENERATING"
                            + " artifactCount={0}",
                    plan.artifacts().size());
            return new Success(
                    SuccessCode.ADMITTED,
                    applied.fact(),
                    usagePersisted.fact(),
                    plan.workflowStep().key());
        } catch (ReceiptGenerationException failure) {
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            return loggedFailure(ErrorCode.RECEIPT_GENERATION_FAILED, phase, null);
        } catch (JdbcMeetingRepository.PublicationSnapshotException
                 | JdbcUsageReservationStore.CorruptUsageDataException failure) {
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            return loggedFailure(ErrorCode.FACT_INCONSISTENT, phase, null);
        } catch (JdbcFinalizationExecutionFactStore.StoredFactException failure) {
            if (commitStarted) {
                return loggedFailure(ErrorCode.OUTCOME_UNKNOWN, Phase.COMMIT, null);
            }
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            ErrorCode code = failure.kind()
                    == JdbcFinalizationExecutionFactStore.FailureKind.STORAGE_UNAVAILABLE
                    ? ErrorCode.STORAGE_UNAVAILABLE : ErrorCode.FACT_INCONSISTENT;
            return loggedFailure(code, phase, null);
        } catch (JdbcManifestFactStore.StoredFactException failure) {
            if (commitStarted) {
                return loggedFailure(ErrorCode.OUTCOME_UNKNOWN, Phase.COMMIT, null);
            }
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            ErrorCode code = failure.kind()
                    == JdbcManifestFactStore.FailureKind.STORAGE_UNAVAILABLE
                    ? ErrorCode.STORAGE_UNAVAILABLE : ErrorCode.FACT_INCONSISTENT;
            return loggedFailure(code, phase, null);
        } catch (SQLException failure) {
            if (commitStarted) {
                return loggedFailure(
                        ErrorCode.OUTCOME_UNKNOWN,
                        Phase.COMMIT,
                        JdbcReadLookupSupport.safeSqlState(failure));
            }
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            ErrorCode code = JdbcReadLookupSupport.classify(failure)
                    == JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE
                    ? ErrorCode.STORAGE_UNAVAILABLE : ErrorCode.INTERNAL;
            return loggedFailure(code, phase,
                    JdbcReadLookupSupport.safeSqlState(failure));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            if (commitStarted) {
                return loggedFailure(ErrorCode.OUTCOME_UNKNOWN, Phase.COMMIT, null);
            }
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            return loggedFailure(ErrorCode.FACT_INCONSISTENT, phase, null);
        } catch (RuntimeException failure) {
            if (commitStarted) {
                return loggedFailure(ErrorCode.OUTCOME_UNKNOWN, Phase.COMMIT, null);
            }
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            return loggedFailure(ErrorCode.INTERNAL, phase, null);
        } finally {
            if (commitCompleted || resolution.rollbackConfirmed() || !transactionStarted) {
                restoreAutoCommitQuietly(connection, previousAutoCommit);
            } else {
                abortQuietly(connection);
            }
            closeQuietly(connection);
        }
    }

    /**
     * 已完成 Admission 的只读恢复。方法不重新生成 receipt、不锁 Reservation、
     * 不重新运行 Planner，也不改变 Workflow 的调度或 lease 状态。
     */
    private Result replay(
            Connection connection,
            TransactionResolution resolution,
            AdmissionRequest request,
            JdbcMeetingRepository.PublicationMeeting locked,
            FinalizationExecutionFact execution) throws SQLException {
        Phase phase = Phase.REPLAY_FACT_READ;
        Optional<FinalizationInputFact> inputOptional =
                JdbcDurableInputFactStore.find(connection, request.meetingId());
        if (inputOptional.isEmpty()) {
            return failAndRollback(connection, resolution,
                    ErrorCode.FACT_INCONSISTENT, phase);
        }
        FinalizationInputFact input = inputOptional.orElseThrow();
        if (!input.equals(request.inputFact())) {
            return failAndRollback(connection, resolution,
                    ErrorCode.INPUT_FACT_CONFLICT, phase);
        }

        Optional<FinalUsageFact> usageOptional =
                JdbcFinalUsageFactStore.find(connection, request.meetingId());
        if (usageOptional.isEmpty()) {
            return failAndRollback(connection, resolution,
                    ErrorCode.FACT_INCONSISTENT, phase);
        }
        FinalUsageFact finalUsage = usageOptional.orElseThrow();
        if (!FinalUsageFact.fromInput(input).equals(finalUsage)) {
            return failAndRollback(connection, resolution,
                    ErrorCode.FINAL_USAGE_CONFLICT, phase);
        }

        WorkflowStep initial;
        WorkflowStep.Status persistedWorkflowStatus;
        FinalizationCheckpoint persistedCheckpoint;
        try {
            WorkflowStep.Key key = FinalizationCheckpoint.workflowKey(request.meetingId());
            Optional<WorkflowStep> workflowOptional =
                    JdbcWorkflowStepStore.find(connection, key);
            if (workflowOptional.isEmpty()) {
                return failAndRollback(connection, resolution,
                        ErrorCode.FACT_INCONSISTENT, Phase.REPLAY_WORKFLOW_READ);
            }
            WorkflowStep workflow = workflowOptional.orElseThrow();
            persistedWorkflowStatus = workflow.status();
            FinalizationCheckpoint checkpoint =
                    FinalizationCheckpointJson.decode(workflow.canonicalPayload());
            persistedCheckpoint = checkpoint;
            initial = FinalizationCheckpointJson.workflowStep(checkpoint);
            boolean definitionMatches = JdbcWorkflowStepStore.definitionMatches(
                    connection, initial).orElse(false);
            boolean consistent = definitionMatches
                    && workflow.key().equals(initial.key())
                    && workflow.checkpointType().equals(initial.checkpointType())
                    && workflow.canonicalPayload().equals(initial.canonicalPayload())
                    && workflow.contentDigest().equals(initial.contentDigest())
                    && checkpoint.meetingId().equals(request.meetingId())
                    && checkpoint.inputBinding().equals(input.inputBinding())
                    && checkpoint.inputFactDigest().equals(
                    FinalizationCheckpoint.inputFactDigest(input))
                    && checkpoint.executionFactDigest().equals(
                    FinalizationExecutionFactJson.sha256(execution))
                    && checkpoint.finalUsageFact().equals(finalUsage)
                    && checkpoint.commitPlan().deploymentScope().equals(
                    request.deploymentScope())
                    && checkpoint.commitPlan().subjectScope().equals(
                    request.subjectScope());
            if (!consistent) {
                return failAndRollback(connection, resolution,
                        ErrorCode.WORKFLOW_CONFLICT, Phase.REPLAY_WORKFLOW_READ);
            }
        } catch (IllegalArgumentException | IllegalStateException malformed) {
            return failAndRollback(connection, resolution,
                    ErrorCode.WORKFLOW_CONFLICT, Phase.REPLAY_WORKFLOW_READ);
        }

        Optional<UsageReservation> currentReservation =
                JdbcUsageReservationStore.findReservation(
                        connection,
                        request.deploymentScope(),
                        request.subjectScope(),
                        persistedCheckpoint.commitPlan().reservationId());
        if (currentReservation.isEmpty()
                || !validReplayReservation(
                locked.snapshot(),
                persistedCheckpoint.commitPlan(),
                currentReservation.orElseThrow())) {
            return failAndRollback(connection, resolution,
                    ErrorCode.RESERVATION_INCONSISTENT,
                    Phase.REPLAY_RESERVATION_READ);
        }
        Optional<MeetingManifest> latest =
                new JdbcManifestFactStore(connection).findLatest(request.meetingId());
        if (!replayStateFactsConsistent(
                locked,
                input,
                execution,
                persistedWorkflowStatus,
                currentReservation.orElseThrow(),
                latest)) {
            return failAndRollback(connection, resolution,
                    ErrorCode.FACT_INCONSISTENT, Phase.REPLAY_STATE_CHECK);
        }
        if (!replayManifestConsistent(
                locked,
                execution,
                input,
                finalUsage,
                persistedCheckpoint,
                latest)) {
            return failAndRollback(connection, resolution,
                    ErrorCode.MANIFEST_CONFLICT, Phase.REPLAY_MANIFEST_READ);
        }
        if (!resolution.rollback(connection)) {
            return loggedFailure(
                    ErrorCode.STORAGE_UNAVAILABLE, Phase.REPLAY_ROLLBACK, null);
        }
        LOGGER.log(Level.FINE,
                "最终收口 Admission 按持久事实稳定重放 state={0} workflowStatus={1}",
                new Object[]{locked.snapshot().state(), persistedWorkflowStatus});
        return new Success(
                SuccessCode.REPLAYED,
                locked.finalization(),
                finalUsage,
                initial.key());
    }

    private String newReceipt() {
        try {
            String candidate = receiptGenerator.newReceipt();
            // 复用公开事实构造器完成空白校验；对象立即丢弃，候选只传给同一事务。
            FinalizationFact.accepted(candidate);
            return candidate;
        } catch (RuntimeException failure) {
            throw new ReceiptGenerationException();
        }
    }

    private static boolean validInitialReservation(
            AdmissionRequest request,
            MeetingAggregate.MeetingSnapshot meeting,
            JdbcUsageReservationStore.LockedReservation locked) {
        UsageReservation reservation = locked.reservation();
        return reservation.state() == UsageReservation.State.RESERVED
                && reservation.isBound()
                && reservation.version() == 1
                && request.deploymentScope().equals(reservation.deploymentScope())
                && request.subjectScope().equals(reservation.subjectScope())
                && Objects.equals(meeting.grantId(), reservation.grantId())
                && meeting.meetingId().equals(reservation.expectedMeetingId())
                && meeting.meetingId().equals(reservation.boundMeetingId())
                && meeting.usageReservationId().equals(reservation.reservationId())
                && meeting.createdAt().equals(reservation.boundAt())
                && !locked.hasActiveLeaseAt(request.inputFact().acceptedAt());
    }

    /** outcome-unknown 恢复确认全空时，主 Reservation 也必须仍是可首次接管快照。 */
    private static boolean validUnadmittedReservation(
            MeetingAggregate.MeetingSnapshot meeting,
            UsageReservation reservation) {
        return reservation.state() == UsageReservation.State.RESERVED
                && reservation.isBound()
                && reservation.version() == 1
                && meeting.deploymentScope().equals(reservation.deploymentScope())
                && meeting.subjectScope().equals(reservation.subjectScope())
                && Objects.equals(meeting.grantId(), reservation.grantId())
                && meeting.meetingId().equals(reservation.expectedMeetingId())
                && meeting.meetingId().equals(reservation.boundMeetingId())
                && meeting.usageReservationId().equals(reservation.reservationId())
                && meeting.createdAt().equals(reservation.boundAt());
    }

    /**
     * 重放只核对 Reservation 与 checkpoint 的合法演进，不用当前版本重新生成计划。
     * Worker 尚未提交时必须仍为冻结版本的 RESERVED；已经提交时只允许精确的
     * {@code expectedVersion + 1} 和 checkpoint 中冻结的最终用量。
     */
    private static boolean validReplayReservation(
            MeetingAggregate.MeetingSnapshot meeting,
            FinalizationCommitPlan plan,
            UsageReservation reservation) {
        if (!meeting.deploymentScope().equals(reservation.deploymentScope())
                || !meeting.subjectScope().equals(reservation.subjectScope())
                || !Objects.equals(meeting.grantId(), reservation.grantId())
                || !meeting.usageReservationId().equals(reservation.reservationId())
                || !plan.reservationId().equals(reservation.reservationId())
                || !plan.deploymentScope().equals(reservation.deploymentScope())
                || !plan.subjectScope().equals(reservation.subjectScope())
                || !meeting.meetingId().equals(reservation.expectedMeetingId())
                || !meeting.meetingId().equals(reservation.boundMeetingId())
                || !meeting.createdAt().equals(reservation.boundAt())) {
            return false;
        }
        if (reservation.state() == UsageReservation.State.RESERVED) {
            return reservation.version() == plan.expectedVersion()
                    && reservation.actualUsage() == null;
        }
        return reservation.state() == UsageReservation.State.COMMITTED
                && plan.expectedVersion() < Long.MAX_VALUE
                && reservation.version() == plan.expectedVersion() + 1
                && plan.actual().equals(reservation.actualUsage());
    }

    private static boolean replayStateFactsConsistent(
            JdbcMeetingRepository.PublicationMeeting meeting,
            FinalizationInputFact input,
            FinalizationExecutionFact execution,
            WorkflowStep.Status workflowStatus,
            UsageReservation reservation,
            Optional<MeetingManifest> latest) {
        MeetingAggregate.MeetingSnapshot snapshot = meeting.snapshot();
        if (!meeting.finalization().isAccepted()
                || execution.startedAt().isAfter(input.acceptedAt())
                || snapshot.updatedAt().isBefore(input.acceptedAt())) {
            return false;
        }
        int revision = latest.map(MeetingManifest::manifestRevision).orElse(0);
        // 参考链中 owned create 保存 version=0；开始事实、停止事实、输入接管和
        // Manifest begin 各推进一次，因此 GENERATING 固定为 version=4。之后每个
        // Manifest revision 与 Meeting CAS 同事务提交，精确增加一次。
        boolean exactVersion = meeting.aggregateVersion() == 4L + revision;
        if (!exactVersion) {
            return false;
        }
        return switch (snapshot.state()) {
            case FINALIZING -> meeting.manifestState() == ManifestStatus.GENERATING
                    && workflowStatus != WorkflowStep.Status.COMPLETED
                    && workflowStatus != WorkflowStep.Status.DEAD
                    && snapshot.updatedAt().equals(input.acceptedAt())
                    && latest.isEmpty();
            case READY -> meeting.manifestState() == ManifestStatus.READY
                    && workflowStatus == WorkflowStep.Status.COMPLETED
                    && reservation.state() == UsageReservation.State.COMMITTED
                    && latest.isPresent();
            case READY_WITH_WARNINGS ->
                    meeting.manifestState() == ManifestStatus.READY_WITH_WARNINGS
                            && workflowStatus == WorkflowStep.Status.COMPLETED
                            && reservation.state() == UsageReservation.State.COMMITTED
                            && latest.isPresent();
            case FAILED -> meeting.manifestState() == ManifestStatus.FAILED
                    && workflowStatus == WorkflowStep.Status.DEAD
                    // Artifact 位于权益提交之前；不可重试 Artifact 失败可在尚未
                    // commit usage 时进入 FAILED。若权益已经提交，COMMITTED 也合法。
                    && (reservation.state() == UsageReservation.State.RESERVED
                    || reservation.state() == UsageReservation.State.COMMITTED)
                    && latest.isPresent();
            case DELETION_PENDING ->
                    meeting.manifestState() == ManifestStatus.DELETION_PENDING
                            && (workflowStatus == WorkflowStep.Status.COMPLETED
                            || workflowStatus == WorkflowStep.Status.DEAD)
                            && (reservation.state() == UsageReservation.State.RESERVED
                            || reservation.state() == UsageReservation.State.COMMITTED)
                            && latest.isPresent();
            case DELETED -> meeting.manifestState() == ManifestStatus.DELETED
                    && (workflowStatus == WorkflowStep.Status.COMPLETED
                    || workflowStatus == WorkflowStep.Status.DEAD)
                    && (reservation.state() == UsageReservation.State.RESERVED
                    || reservation.state() == UsageReservation.State.COMMITTED)
                    && latest.isPresent();
            default -> false;
        };
    }

    private static boolean isFirstAdmissionState(
            JdbcMeetingRepository.PublicationMeeting meeting) {
        // owned create 的初始 version=0；开始与停止各推进一次。首次 Admission
        // 必须从精确的预接管 version=2 开始，不能把状态字段相同但版本已漂移的
        // 聚合继续推进，否则 GENERATING 会落在无法恢复的 version 上。
        return hasFirstAdmissionShape(meeting)
                && meeting.aggregateVersion() == 2L;
    }

    private static boolean hasFirstAdmissionShape(
            JdbcMeetingRepository.PublicationMeeting meeting) {
        return meeting.snapshot().state() == MeetingState.STOPPING
                && meeting.finalization().inputState()
                == InputState.INPUT_COMPLETE_PENDING
                && meeting.manifestState() == ManifestStatus.NOT_STARTED;
    }

    private static boolean isReplayablePostAdmission(
            JdbcMeetingRepository.PublicationMeeting meeting) {
        if (!meeting.finalization().isAccepted()) {
            return false;
        }
        return switch (meeting.snapshot().state()) {
            case FINALIZING -> meeting.manifestState() == ManifestStatus.GENERATING;
            case READY -> meeting.manifestState() == ManifestStatus.READY;
            case READY_WITH_WARNINGS ->
                    meeting.manifestState() == ManifestStatus.READY_WITH_WARNINGS;
            case FAILED -> meeting.manifestState() == ManifestStatus.FAILED;
            case DELETION_PENDING ->
                    meeting.manifestState() == ManifestStatus.DELETION_PENDING;
            case DELETED -> meeting.manifestState() == ManifestStatus.DELETED;
            default -> false;
        };
    }

    private static boolean replayManifestConsistent(
            JdbcMeetingRepository.PublicationMeeting meeting,
            FinalizationExecutionFact execution,
            FinalizationInputFact input,
            FinalUsageFact finalUsage,
            FinalizationCheckpoint checkpoint,
            Optional<MeetingManifest> latest) {
        if (meeting.snapshot().state() == MeetingState.FINALIZING) {
            return meeting.manifestState() == ManifestStatus.GENERATING
                    && latest.isEmpty();
        }
        if (latest.isEmpty()) {
            return false;
        }
        MeetingManifest manifest = latest.orElseThrow();
        boolean baseMatches = manifest.meetingId().equals(meeting.snapshot().meetingId())
                && manifest.status() == meeting.manifestState()
                && manifest.profile().id().equals(meeting.snapshot().profileId())
                && manifest.profile().revision()
                == meeting.snapshot().profileRevision()
                && Objects.equals(manifest.title(), execution.title())
                && manifest.startedAt().equals(execution.startedAt())
                && manifest.endedAt().equals(input.acceptedAt())
                && manifest.durationMs() == finalUsage.actual().amount()
                && manifest.languages().source().equals(input.sourceLanguage())
                && manifest.languages().targets().equals(input.targetLanguages());
        if (!baseMatches) {
            return false;
        }
        if (meeting.snapshot().state() != MeetingState.READY
                && meeting.snapshot().state() != MeetingState.READY_WITH_WARNINGS) {
            return true;
        }
        if (manifest.artifacts().size() != checkpoint.artifacts().size()) {
            return false;
        }
        return checkpoint.artifacts().stream().allMatch(material ->
                manifest.artifacts().stream().anyMatch(reference ->
                        reference.artifactId().equals(material.spec().artifactId())
                                && reference.type().equals(material.spec().artifactType())
                                && reference.mediaType().equals(material.spec().mediaType())
                                && Objects.equals(
                                reference.language(), material.spec().language())
                                && reference.revision() == material.spec().revision()
                                && reference.size() == material.claim().size()
                                && reference.checksum().equals(
                                material.claim().checksum())
                                && reference.status()
                                == MeetingManifest.ArtifactStatus.READY));
    }

    private static Result failAndRollback(
            Connection connection,
            TransactionResolution resolution,
            ErrorCode code,
            Phase phase) {
        if (!resolution.rollback(connection)) {
            return loggedFailure(ErrorCode.STORAGE_UNAVAILABLE, phase, null);
        }
        return loggedFailure(code, phase, null);
    }

    private static Failure loggedFailure(
            ErrorCode code,
            Phase phase,
            String sqlState) {
        Level level = code.retryable() ? Level.WARNING : Level.FINE;
        LOGGER.log(level,
                "最终收口 Admission 失败 code={0} phase={1} sqlState={2}",
                new Object[]{code, phase, sqlState == null ? "N/A" : sqlState});
        return new Failure(code);
    }

    private static void pinDeploymentSchema(Connection connection) throws SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT current_schema()")) {
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("schema is unavailable");
                }
                String schema = rows.getString(1);
                if (schema == null || schema.startsWith("pg_temp")) {
                    throw new SQLException(
                            "temporary schema is not a deployment schema");
                }
            }
        }
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                PIN_SEARCH_PATH_SQL)) {
            statement.execute();
        }
    }

    private static void restoreAutoCommitQuietly(
            Connection connection,
            boolean previousAutoCommit) {
        if (connection == null) {
            return;
        }
        try {
            if (!connection.isClosed()) {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING,
                    "最终收口 Admission 恢复连接状态失败 storage=UNAVAILABLE");
        }
    }

    private static boolean restoreReadOnlyConnection(
            Connection connection,
            boolean previousAutoCommit,
            boolean previousReadOnly,
            int previousIsolation) {
        if (connection == null) {
            return false;
        }
        try {
            if (!connection.isClosed()) {
                connection.setAutoCommit(previousAutoCommit);
                connection.setTransactionIsolation(previousIsolation);
                connection.setReadOnly(previousReadOnly);
            }
            return true;
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING,
                    "最终收口 Admission 恢复只读连接状态失败 storage=UNAVAILABLE");
            return false;
        }
    }

    private static void abortQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING,
                    "最终收口 Admission 废弃未确认连接失败 storage=UNAVAILABLE");
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING,
                    "最终收口 Admission 关闭连接失败 storage=UNAVAILABLE");
        }
    }

    private enum Phase {
        OPEN_CONNECTION,
        SCHEMA_PIN,
        RECOVERY_MEETING_READ,
        MEETING_LOCK,
        ACCESS_CHECK,
        EXECUTION_FACT_READ,
        STATE_CHECK,
        PREEXISTING_FACT_CHECK,
        RECOVERY_ABSENCE_CHECK,
        RESERVATION_LOCK,
        INPUT_PREFLIGHT,
        INPUT_FACT_WRITE,
        FINAL_USAGE_WRITE,
        RECEIPT_GENERATION,
        RECEIPT_WRITE,
        CHECKPOINT_PLAN,
        WORKFLOW_REGISTER,
        MANIFEST_BEGIN,
        REPLAY_FACT_READ,
        REPLAY_WORKFLOW_READ,
        REPLAY_RESERVATION_READ,
        REPLAY_STATE_CHECK,
        REPLAY_MANIFEST_READ,
        REPLAY_ROLLBACK,
        COMMIT
    }

    /** Admission 本地事务的八个可重复故障窗口。 */
    enum FailurePoint {
        BEFORE_FIRST_WRITE,
        AFTER_V008,
        AFTER_V009,
        AFTER_RECEIPT,
        AFTER_CHECKPOINT,
        AFTER_WORKFLOW,
        AFTER_MANIFEST,
        BEFORE_COMMIT
    }

    /** 探针禁止执行数据库或外部动作；测试只允许抛出固定异常触发回滚。 */
    @FunctionalInterface
    interface FailureProbe {
        void hit(FailurePoint point);
    }

    enum RecoveryPoint {
        AFTER_MEETING_SNAPSHOT
    }

    /** 只供并发快照门禁暂停读取；生产装配不得执行阻塞或外部动作。 */
    @FunctionalInterface
    interface RecoveryProbe {
        void hit(RecoveryPoint point);
    }

    private static final class TransactionResolution {
        private boolean rollbackAttempted;
        private boolean rollbackConfirmed;

        boolean rollback(Connection connection) {
            if (rollbackAttempted) {
                return rollbackConfirmed;
            }
            rollbackAttempted = true;
            if (connection == null) {
                return false;
            }
            try {
                connection.rollback();
                rollbackConfirmed = true;
            } catch (SQLException | RuntimeException failure) {
                LOGGER.log(Level.WARNING,
                        "最终收口 Admission 回滚未确认 storage=UNAVAILABLE");
            }
            return rollbackConfirmed;
        }

        boolean rollbackConfirmed() {
            return rollbackConfirmed;
        }
    }

    private static final class ReceiptGenerationException extends RuntimeException {
        private ReceiptGenerationException() {
            super("receipt generation failed");
        }
    }
}
