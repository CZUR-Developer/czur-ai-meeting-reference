package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.finalization.FinalizationCommitPlan;
import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.finalization.FinalizationTerminalOperationMarker;
import com.czur.reference.application.finalization.FinalizationTerminalFailureIdentity;
import com.czur.reference.application.manifest.FinalizationManifestFactory;
import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestFactStore;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore;
import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.json.FinalizationCheckpointJson;
import com.czur.reference.protocol.json.FinalizationExecutionFactJson;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 单连接实现的最终收口 Completion 工作单元。
 *
 * <p>写路径固定使用 Meeting -> Workflow -> 排序 Artifact metadata -> Manifest ->
 * Meeting CAS -> Workflow terminal 的锁序。成功和死信路径都只执行一次数据库提交；
 * 任一栅栏、事实或状态不一致都会回滚整个事务。本类不会调用 Artifact Store、模型、
 * Entitlement Provider，也不会复用会自行开事务的 Manifest publication 适配器。</p>
 *
 * <p>提交开始后发生异常一律返回 {@code OUTCOME_UNKNOWN} 并废弃连接。只读恢复使用
 * 全新连接、{@code readOnly=true} 和 {@code REPEATABLE_READ}，同时核对请求中的
 * leaseVersion、terminal 时间、Workflow Definition、Artifact metadata、Manifest、
 * Meeting 与 Reservation；不会重新执行任何终态写入。</p>
 */
public final class JdbcFinalizationCompletionUnitOfWork
        implements FinalizationCompletionUnitOfWork {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcFinalizationCompletionUnitOfWork.class.getName());

    private final DataSource dataSource;
    private final FailureProbe failureProbe;
    private final RecoveryProbe recoveryProbe;

    public JdbcFinalizationCompletionUnitOfWork(DataSource dataSource) {
        this(dataSource, ignored -> { }, ignored -> { });
    }

    /** 仅供同包事务窗口门禁注入固定失败；生产装配始终使用无操作探针。 */
    JdbcFinalizationCompletionUnitOfWork(
            DataSource dataSource,
            FailureProbe failureProbe) {
        this(dataSource, failureProbe, ignored -> { });
    }

    /** 仅供同包门禁暂停恢复读取窗口；生产装配始终使用无操作探针。 */
    JdbcFinalizationCompletionUnitOfWork(
            DataSource dataSource,
            FailureProbe failureProbe,
            RecoveryProbe recoveryProbe) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.failureProbe = Objects.requireNonNull(failureProbe, "failureProbe");
        this.recoveryProbe = Objects.requireNonNull(recoveryProbe, "recoveryProbe");
    }

    @Override
    public Result complete(CompletionRequest request) {
        Objects.requireNonNull(request, "request");
        return execute(new Operation(
                request.lease(),
                request.checkpoint(),
                request.readyArtifacts(),
                request.completedAt(),
                null,
                -1));
    }

    @Override
    public Result fail(FailureRequest request) {
        Objects.requireNonNull(request, "request");
        return execute(new Operation(
                request.lease(),
                request.checkpoint(),
                request.readyArtifacts(),
                request.failedAt(),
                request.failure(),
                request.leasedAttempt()));
    }

    @Override
    public Result recoverCompletion(CompletionRequest request) {
        Objects.requireNonNull(request, "request");
        return recover(new Operation(
                request.lease(),
                request.checkpoint(),
                request.readyArtifacts(),
                request.completedAt(),
                null,
                -1));
    }

    @Override
    public Result recoverFailure(FailureRequest request) {
        Objects.requireNonNull(request, "request");
        return recover(new Operation(
                request.lease(),
                request.checkpoint(),
                request.readyArtifacts(),
                request.failedAt(),
                request.failure(),
                request.leasedAttempt()));
    }

    private Result execute(Operation operation) {
        Connection connection = null;
        boolean previousAutoCommit = true;
        boolean transactionStarted = false;
        boolean commitStarted = false;
        boolean commitCompleted = false;
        TransactionResolution resolution = new TransactionResolution();
        Phase phase = Phase.OPEN_CONNECTION;
        try {
            validateDescriptorSet(operation);
            connection = dataSource.getConnection();
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            transactionStarted = true;

            phase = Phase.MEETING_LOCK;
            Optional<JdbcMeetingRepository.PublicationMeeting> meetingOptional =
                    JdbcMeetingRepository.findManifestPublicationForUpdate(
                            connection, operation.checkpoint().meetingId());
            if (meetingOptional.isEmpty()) {
                return failAndRollback(connection, resolution,
                        ErrorCode.MEETING_NOT_FOUND, phase);
            }
            JdbcMeetingRepository.PublicationMeeting meeting = meetingOptional.orElseThrow();

            phase = Phase.FACT_READ;
            Facts facts = loadFacts(connection, operation.checkpoint().meetingId());
            ErrorCode factsError = validateFacts(meeting, operation.checkpoint(), facts);
            if (factsError != null) {
                return failAndRollback(connection, resolution, factsError, phase);
            }
            if (!isInitialCompletionState(meeting, facts.input())) {
                return failAndRollback(connection, resolution,
                        ErrorCode.INVALID_MEETING_STATE, Phase.STATE_CHECK);
            }

            phase = Phase.WORKFLOW_LOCK;
            JdbcWorkflowStepStore.LeaseFenceResult fence =
                    JdbcWorkflowStepStore.lockActiveLease(
                            connection, operation.lease(), operation.terminalAt());
            ErrorCode fenceError = mapFence(fence.status());
            if (fenceError != null) {
                return failAndRollback(connection, resolution, fenceError, phase);
            }
            if (operation.isFailure()
                    && fence.step().attempt() != operation.leasedAttempt()) {
                return failAndRollback(connection, resolution,
                        ErrorCode.WORKFLOW_TERMINAL_CONFLICT, phase);
            }
            if (!workflowDefinitionMatches(
                    connection, fence.step(), operation.checkpoint())) {
                return failAndRollback(connection, resolution,
                        ErrorCode.CHECKPOINT_CONFLICT, phase);
            }
            if (!terminalMarkerMatches(operation, fence.step())) {
                // Completion 是最终本地写入边界，不能只依赖 Worker 自律。缺失、损坏
                // 或属于另一请求的 marker 必须在首笔 metadata 写入前关闭式拒绝。
                return failAndRollback(connection, resolution,
                        ErrorCode.FACT_INCONSISTENT, phase);
            }

            phase = Phase.ENTITLEMENT_READ;
            Optional<UsageReservation> reservationOptional =
                    JdbcUsageReservationStore.findReservation(
                            connection,
                            operation.checkpoint().commitPlan().deploymentScope(),
                            operation.checkpoint().commitPlan().subjectScope(),
                            operation.checkpoint().commitPlan().reservationId());
            if (reservationOptional.isEmpty()
                    || !validReservation(
                    meeting,
                    operation.checkpoint().commitPlan(),
                    reservationOptional.orElseThrow(),
                    operation.isFailure())) {
                return failAndRollback(connection, resolution,
                        operation.isFailure()
                                ? ErrorCode.FACT_INCONSISTENT
                                : ErrorCode.ENTITLEMENT_NOT_COMMITTED,
                        phase);
            }

            failureProbe.hit(FailurePoint.BEFORE_FIRST_WRITE);
            phase = Phase.ARTIFACT_METADATA;
            List<ArtifactReadyMetadataStore.ReadyMetadata> metadata =
                    persistMetadata(connection, operation);
            if (metadata == null) {
                return failAndRollback(connection, resolution,
                        ErrorCode.ARTIFACT_CONFLICT, phase);
            }
            failureProbe.hit(FailurePoint.AFTER_ARTIFACT_METADATA);

            phase = Phase.MANIFEST_BUILD;
            MeetingManifest manifest = operation.isFailure()
                    ? FinalizationManifestFactory.failed(
                    facts.execution(),
                    facts.input(),
                    facts.finalUsage(),
                    operation.checkpoint(),
                    metadata,
                    operation.failure(),
                    operation.terminalAt())
                    : FinalizationManifestFactory.ready(
                    facts.execution(),
                    facts.input(),
                    facts.finalUsage(),
                    operation.checkpoint(),
                    metadata);
            JdbcManifestFactStore manifestStore = new JdbcManifestFactStore(connection);
            if (manifestStore.findLatest(operation.checkpoint().meetingId()).isPresent()) {
                return failAndRollback(connection, resolution,
                        ErrorCode.MANIFEST_CONFLICT, phase);
            }

            phase = Phase.MANIFEST_APPEND;
            ManifestFactStore.AppendResult appended = manifestStore.append(
                    new ManifestFactStore.AppendRequest(
                            manifest,
                            new ManifestFactStore.ExpectedPreviousRevision.None()));
            if (appended.status() != ManifestFactStore.AppendStatus.APPENDED) {
                return failAndRollback(connection, resolution,
                        ErrorCode.MANIFEST_CONFLICT, phase);
            }
            failureProbe.hit(FailurePoint.AFTER_MANIFEST);

            phase = Phase.MEETING_UPDATE;
            MeetingState terminalMeetingState = switch (manifest.status()) {
                case READY -> MeetingState.READY;
                case READY_WITH_WARNINGS -> MeetingState.READY_WITH_WARNINGS;
                case FAILED -> MeetingState.FAILED;
                default -> throw new IllegalStateException(
                        "completion factory returned non-terminal manifest");
            };
            if (!JdbcMeetingRepository.updateManifestPublication(
                    connection,
                    meeting,
                    terminalMeetingState,
                    manifest.status(),
                    operation.terminalAt())) {
                return failAndRollback(connection, resolution,
                        ErrorCode.FACT_INCONSISTENT, phase);
            }
            failureProbe.hit(FailurePoint.AFTER_MEETING);

            phase = Phase.WORKFLOW_TERMINAL;
            if (operation.isFailure()) {
                WorkflowStepStore.FailResult failed = JdbcWorkflowStepStore.fail(
                        connection,
                        operation.lease(),
                        operation.terminalAt(),
                        operation.terminalAt(),
                        operation.failure().code(),
                        FinalizationTerminalFailureIdentity.of(operation.failure()),
                        Math.addExact(operation.leasedAttempt(), 1));
                if (failed.status() != WorkflowStepStore.FailStatus.DEAD) {
                    return failAndRollback(connection, resolution,
                            ErrorCode.WORKFLOW_TERMINAL_CONFLICT, phase);
                }
            } else {
                WorkflowStepStore.CompleteResult completed =
                        JdbcWorkflowStepStore.complete(
                                connection,
                                operation.lease(),
                                operation.terminalAt());
                if (completed.status() != WorkflowStepStore.CompleteStatus.COMPLETED) {
                    return failAndRollback(connection, resolution,
                            ErrorCode.WORKFLOW_TERMINAL_CONFLICT, phase);
                }
            }
            failureProbe.hit(FailurePoint.AFTER_WORKFLOW_TERMINAL);

            phase = Phase.COMMIT;
            failureProbe.hit(FailurePoint.BEFORE_COMMIT);
            commitStarted = true;
            connection.commit();
            commitCompleted = true;
            SuccessCode code = operation.isFailure()
                    ? SuccessCode.FAILED : SuccessCode.COMPLETED;
            LOGGER.log(Level.INFO,
                    "最终收口 Completion 已原子提交 result={0} manifest={1}",
                    new Object[]{code, manifest.status()});
            return new Success(code, manifest);
        } catch (JdbcMeetingRepository.PublicationSnapshotException
                 | JdbcUsageReservationStore.CorruptUsageDataException failure) {
            if (commitStarted) {
                return loggedFailure(ErrorCode.OUTCOME_UNKNOWN, Phase.COMMIT, null);
            }
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
            return loggedFailure(mapStoredFactFailure(failure.kind()), phase, null);
        } catch (JdbcManifestFactStore.StoredFactException failure) {
            if (commitStarted) {
                return loggedFailure(ErrorCode.OUTCOME_UNKNOWN, Phase.COMMIT, null);
            }
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            return loggedFailure(mapStoredFactFailure(failure.kind()), phase, null);
        } catch (JdbcArtifactReadyMetadataStore.StoredFactException failure) {
            if (commitStarted) {
                return loggedFailure(ErrorCode.OUTCOME_UNKNOWN, Phase.COMMIT, null);
            }
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            return loggedFailure(mapStoredFactFailure(failure.kind()), phase, null);
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

    private Result recover(Operation operation) {
        Connection connection = null;
        boolean previousAutoCommit = true;
        boolean previousReadOnly = false;
        int previousIsolation = Connection.TRANSACTION_READ_COMMITTED;
        boolean transactionStarted = false;
        TransactionResolution resolution = new TransactionResolution();
        Phase phase = Phase.OPEN_CONNECTION;
        try {
            validateDescriptorSet(operation);
            connection = dataSource.getConnection();
            previousAutoCommit = connection.getAutoCommit();
            previousReadOnly = connection.isReadOnly();
            previousIsolation = connection.getTransactionIsolation();
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            transactionStarted = true;

            Optional<JdbcMeetingRepository.PublicationMeeting> meetingOptional =
                    JdbcMeetingRepository.findManifestPublication(
                            connection, operation.checkpoint().meetingId());
            if (meetingOptional.isEmpty()) {
                return failAndRollback(connection, resolution,
                        ErrorCode.MEETING_NOT_FOUND, Phase.RECOVERY_READ);
            }
            JdbcMeetingRepository.PublicationMeeting meeting = meetingOptional.orElseThrow();
            recoveryProbe.hit(RecoveryPoint.AFTER_MEETING_SNAPSHOT);
            Facts facts = loadFacts(connection, operation.checkpoint().meetingId());
            ErrorCode factsError = validateFacts(meeting, operation.checkpoint(), facts);
            if (factsError != null) {
                return failAndRollback(connection, resolution,
                        factsError, Phase.RECOVERY_READ);
            }

            Optional<WorkflowStep> workflowOptional = JdbcWorkflowStepStore.find(
                    connection, operation.lease().key());
            if (workflowOptional.isEmpty()) {
                return failAndRollback(connection, resolution,
                        ErrorCode.WORKFLOW_NOT_FOUND, Phase.RECOVERY_READ);
            }
            WorkflowStep workflow = workflowOptional.orElseThrow();
            if (!workflowDefinitionMatches(
                    connection, workflow, operation.checkpoint())) {
                return failAndRollback(connection, resolution,
                        ErrorCode.CHECKPOINT_CONFLICT, Phase.RECOVERY_READ);
            }

            MetadataSnapshot metadata = readMetadataSnapshot(connection, operation);
            if (metadata.state() == MetadataState.PARTIAL_OR_DRIFT) {
                return failAndRollback(connection, resolution,
                        ErrorCode.ARTIFACT_CONFLICT, Phase.RECOVERY_READ);
            }
            Optional<MeetingManifest> latest =
                    new JdbcManifestFactStore(connection).findLatest(
                            operation.checkpoint().meetingId());
            if (metadata.state() == MetadataState.ALL_ABSENT) {
                if (isOriginalLeaseState(
                        connection, operation, meeting, facts, workflow, latest)) {
                    return failAndRollback(connection, resolution,
                            ErrorCode.COMPLETION_NOT_FOUND, Phase.RECOVERY_READ);
                }
                return failAndRollback(connection, resolution,
                        ErrorCode.FACT_INCONSISTENT, Phase.RECOVERY_READ);
            }
            MeetingManifest expected = operation.isFailure()
                    ? FinalizationManifestFactory.failed(
                    facts.execution(),
                    facts.input(),
                    facts.finalUsage(),
                    operation.checkpoint(),
                    metadata.metadata(),
                    operation.failure(),
                    operation.terminalAt())
                    : FinalizationManifestFactory.ready(
                    facts.execution(),
                    facts.input(),
                    facts.finalUsage(),
                    operation.checkpoint(),
                    metadata.metadata());
            if (isConfirmedTerminal(operation, meeting, workflow, latest, expected)) {
                Optional<UsageReservation> reservation =
                        JdbcUsageReservationStore.findReservation(
                                connection,
                                operation.checkpoint().commitPlan().deploymentScope(),
                                operation.checkpoint().commitPlan().subjectScope(),
                                operation.checkpoint().commitPlan().reservationId());
                if (reservation.isEmpty()
                        || !validReservation(
                        meeting,
                        operation.checkpoint().commitPlan(),
                        reservation.orElseThrow(),
                        operation.isFailure())) {
                    return failAndRollback(connection, resolution,
                            ErrorCode.FACT_INCONSISTENT, Phase.RECOVERY_READ);
                }
                if (!resolution.rollback(connection)) {
                    return loggedFailure(
                            ErrorCode.STORAGE_UNAVAILABLE,
                            Phase.RECOVERY_ROLLBACK,
                            null);
                }
                return new Success(
                        operation.isFailure()
                                ? SuccessCode.FAILURE_REPLAYED
                                : SuccessCode.COMPLETION_REPLAYED,
                        expected);
            }

            if (metadata.metadata().isEmpty() && isOriginalLeaseState(
                    connection, operation, meeting, facts, workflow, latest)) {
                return failAndRollback(connection, resolution,
                        ErrorCode.COMPLETION_NOT_FOUND, Phase.RECOVERY_READ);
            }
            return failAndRollback(connection, resolution,
                    ErrorCode.FACT_INCONSISTENT, Phase.RECOVERY_READ);
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
            return loggedFailure(mapStoredFactFailure(failure.kind()), phase, null);
        } catch (JdbcManifestFactStore.StoredFactException failure) {
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            return loggedFailure(mapStoredFactFailure(failure.kind()), phase, null);
        } catch (JdbcArtifactReadyMetadataStore.StoredFactException failure) {
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            return loggedFailure(mapStoredFactFailure(failure.kind()), phase, null);
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

    private static Facts loadFacts(Connection connection, String meetingId)
            throws SQLException {
        Optional<FinalizationExecutionFact> execution =
                new JdbcFinalizationExecutionFactStore(connection).find(meetingId);
        if (execution.isEmpty()) {
            return new Facts(null, null, null);
        }
        Optional<FinalizationInputFact> input =
                JdbcDurableInputFactStore.find(connection, meetingId);
        Optional<FinalUsageFact> usage =
                JdbcFinalUsageFactStore.find(connection, meetingId);
        return new Facts(
                execution.orElseThrow(),
                input.orElse(null),
                usage.orElse(null));
    }

    private static ErrorCode validateFacts(
            JdbcMeetingRepository.PublicationMeeting meeting,
            FinalizationCheckpoint checkpoint,
            Facts facts) {
        if (facts.execution() == null) {
            return ErrorCode.EXECUTION_FACT_MISSING;
        }
        if (facts.input() == null) {
            return ErrorCode.INPUT_FACT_MISSING;
        }
        if (facts.finalUsage() == null) {
            return ErrorCode.FINAL_USAGE_MISSING;
        }
        try {
            boolean consistent = checkpoint.meetingId().equals(
                    meeting.snapshot().meetingId())
                    && checkpoint.meetingId().equals(facts.execution().meetingId())
                    && checkpoint.meetingId().equals(facts.input().meetingId())
                    && checkpoint.meetingId().equals(facts.finalUsage().meetingId())
                    && checkpoint.inputBinding().equals(facts.input().inputBinding())
                    && checkpoint.inputFactDigest().equals(
                    FinalizationCheckpoint.inputFactDigest(facts.input()))
                    && checkpoint.executionFactDigest().equals(
                    FinalizationExecutionFactJson.sha256(facts.execution()))
                    && checkpoint.finalUsageFact().equals(facts.finalUsage())
                    && meeting.snapshot().profileId().equals(
                    facts.execution().productProfile().id())
                    && meeting.snapshot().profileRevision()
                    == facts.execution().productProfile().revision()
                    && meeting.snapshot().deploymentScope().equals(
                    checkpoint.commitPlan().deploymentScope())
                    && meeting.snapshot().subjectScope().equals(
                    checkpoint.commitPlan().subjectScope())
                    && Objects.equals(
                    meeting.snapshot().usageReservationId(),
                    checkpoint.commitPlan().reservationId());
            return consistent ? null : ErrorCode.CHECKPOINT_CONFLICT;
        } catch (RuntimeException failure) {
            return ErrorCode.CHECKPOINT_CONFLICT;
        }
    }

    private static boolean isInitialCompletionState(
            JdbcMeetingRepository.PublicationMeeting meeting,
            FinalizationInputFact input) {
        return meeting.snapshot().state() == MeetingState.FINALIZING
                && meeting.finalization().inputState() == InputState.INPUT_ACCEPTED
                && meeting.finalization().finalizationReceipt() != null
                && meeting.manifestState() == ManifestStatus.GENERATING
                && meeting.aggregateVersion() == 4L
                && meeting.snapshot().updatedAt().equals(input.acceptedAt());
    }

    private static boolean workflowDefinitionMatches(
            Connection connection,
            WorkflowStep current,
            FinalizationCheckpoint checkpoint) throws SQLException {
        WorkflowStep initial = FinalizationCheckpointJson.workflowStep(checkpoint);
        return current.key().equals(initial.key())
                && current.checkpointType().equals(initial.checkpointType())
                && current.canonicalPayload().equals(initial.canonicalPayload())
                && current.contentDigest().equals(initial.contentDigest())
                && JdbcWorkflowStepStore.definitionMatches(connection, initial).orElse(false);
    }

    private static ErrorCode mapFence(JdbcWorkflowStepStore.LeaseFenceStatus status) {
        return switch (status) {
            case ACTIVE -> null;
            case NOT_FOUND -> ErrorCode.WORKFLOW_NOT_FOUND;
            case NOT_OWNER -> ErrorCode.LEASE_NOT_OWNER;
            case LEASE_EXPIRED -> ErrorCode.LEASE_EXPIRED;
            case ALREADY_COMPLETED, ALREADY_DEAD ->
                    ErrorCode.WORKFLOW_TERMINAL_CONFLICT;
        };
    }

    private static boolean validReservation(
            JdbcMeetingRepository.PublicationMeeting meeting,
            FinalizationCommitPlan plan,
            UsageReservation reservation,
            boolean failurePath) {
        boolean identity = meeting.snapshot().meetingId().equals(
                reservation.expectedMeetingId())
                && meeting.snapshot().meetingId().equals(reservation.boundMeetingId())
                && meeting.snapshot().deploymentScope().equals(
                reservation.deploymentScope())
                && meeting.snapshot().subjectScope().equals(reservation.subjectScope())
                && Objects.equals(meeting.snapshot().grantId(), reservation.grantId())
                && Objects.equals(
                meeting.snapshot().usageReservationId(), reservation.reservationId())
                && plan.reservationId().equals(reservation.reservationId())
                && plan.deploymentScope().equals(reservation.deploymentScope())
                && plan.subjectScope().equals(reservation.subjectScope())
                && meeting.snapshot().createdAt().equals(reservation.boundAt());
        if (!identity) {
            return false;
        }
        if (reservation.state() == UsageReservation.State.COMMITTED) {
            return plan.expectedVersion() < Long.MAX_VALUE
                    && reservation.version() == plan.expectedVersion() + 1
                    && plan.actual().equals(reservation.actualUsage());
        }
        return failurePath
                && reservation.state() == UsageReservation.State.RESERVED
                && reservation.version() == plan.expectedVersion()
                && reservation.actualUsage() == null;
    }

    private static void validateDescriptorSet(Operation operation) {
        Map<String, FinalizationCheckpoint.ArtifactMaterial> expected = new HashMap<>();
        for (FinalizationCheckpoint.ArtifactMaterial material
                : operation.checkpoint().artifacts()) {
            expected.put(material.spec().artifactId(), material);
        }
        Map<String, ArtifactDescriptor> candidates = new HashMap<>();
        for (ArtifactDescriptor descriptor : operation.readyArtifacts()) {
            String artifactId = descriptor.intent().spec().artifactId();
            FinalizationCheckpoint.ArtifactMaterial material = expected.get(artifactId);
            if (material == null
                    || candidates.putIfAbsent(artifactId, descriptor) != null
                    || !material.spec().equals(descriptor.intent().spec())
                    || !material.claim().equals(descriptor.content())) {
                throw new IllegalArgumentException(
                        "READY descriptors do not match checkpoint artifacts");
            }
        }
        if (!operation.isFailure() && candidates.size() != expected.size()) {
            throw new IllegalArgumentException(
                    "successful completion requires every checkpoint artifact");
        }
    }

    private static List<ArtifactReadyMetadataStore.ReadyMetadata> persistMetadata(
            Connection connection,
            Operation operation) {
        JdbcArtifactReadyMetadataStore store =
                new JdbcArtifactReadyMetadataStore(connection);
        List<ArtifactReadyMetadataStore.ReadyMetadata> before =
                store.findByMeetingId(operation.checkpoint().meetingId());
        if (!metadataCanBeCompleted(before, operation)) {
            return null;
        }

        List<ArtifactDescriptor> sorted = new ArrayList<>(operation.readyArtifacts());
        sorted.sort(Comparator.comparing(value -> value.intent().spec().artifactId()));
        for (ArtifactDescriptor descriptor : sorted) {
            ArtifactReadyMetadataStore.PersistResult result =
                    store.persistReady(descriptor);
            if (result.status() == ArtifactReadyMetadataStore.PersistStatus.CONFLICT) {
                return null;
            }
        }
        MetadataSnapshot after = classifyMetadata(
                store.findByMeetingId(operation.checkpoint().meetingId()), operation);
        return after.state() == MetadataState.EXACT_TERMINAL
                ? after.metadata() : null;
    }

    private static MetadataSnapshot readMetadataSnapshot(
            Connection connection,
            Operation operation) {
        JdbcArtifactReadyMetadataStore store =
                new JdbcArtifactReadyMetadataStore(connection);
        return classifyMetadata(
                store.findByMeetingId(operation.checkpoint().meetingId()), operation);
    }

    /**
     * 写前允许本请求的 READY descriptor 尚未写入或已经精确存在；任何遗漏产物、
     * checkpoint 外产物或 descriptor 漂移都表示会议级 metadata 已被其它请求污染。
     */
    private static boolean metadataCanBeCompleted(
            List<ArtifactReadyMetadataStore.ReadyMetadata> current,
            Operation operation) {
        Map<String, ArtifactDescriptor> expectedReady = readyByArtifactId(operation);
        Map<String, FinalizationCheckpoint.ArtifactMaterial> checkpoint =
                checkpointByArtifactId(operation);
        Map<String, Boolean> seen = new HashMap<>();
        for (ArtifactReadyMetadataStore.ReadyMetadata metadata : current) {
            String artifactId = metadata.artifact().intent().spec().artifactId();
            ArtifactDescriptor expected = expectedReady.get(artifactId);
            if (!checkpoint.containsKey(artifactId)
                    || expected == null
                    || !metadata.artifact().equals(expected)
                    || seen.putIfAbsent(artifactId, Boolean.TRUE) != null) {
                return false;
            }
        }
        return true;
    }

    private static MetadataSnapshot classifyMetadata(
            List<ArtifactReadyMetadataStore.ReadyMetadata> current,
            Operation operation) {
        Map<String, ArtifactDescriptor> expected = readyByArtifactId(operation);
        if (current.isEmpty() && !expected.isEmpty()) {
            return new MetadataSnapshot(MetadataState.ALL_ABSENT, List.of());
        }
        if (current.size() != expected.size()) {
            return new MetadataSnapshot(MetadataState.PARTIAL_OR_DRIFT, List.of());
        }
        Map<String, ArtifactReadyMetadataStore.ReadyMetadata> exact = new HashMap<>();
        for (ArtifactReadyMetadataStore.ReadyMetadata metadata : current) {
            String artifactId = metadata.artifact().intent().spec().artifactId();
            ArtifactDescriptor descriptor = expected.get(artifactId);
            if (descriptor == null
                    || !metadata.artifact().equals(descriptor)
                    || exact.putIfAbsent(artifactId, metadata) != null) {
                return new MetadataSnapshot(
                        MetadataState.PARTIAL_OR_DRIFT, List.of());
            }
        }
        List<ArtifactReadyMetadataStore.ReadyMetadata> ordered = new ArrayList<>();
        for (FinalizationCheckpoint.ArtifactMaterial material
                : operation.checkpoint().artifacts()) {
            ArtifactReadyMetadataStore.ReadyMetadata metadata = exact.get(
                    material.spec().artifactId());
            if (metadata != null) {
                ordered.add(metadata);
            }
        }
        return new MetadataSnapshot(
                MetadataState.EXACT_TERMINAL, List.copyOf(ordered));
    }

    private static Map<String, ArtifactDescriptor> readyByArtifactId(
            Operation operation) {
        Map<String, ArtifactDescriptor> result = new HashMap<>();
        for (ArtifactDescriptor descriptor : operation.readyArtifacts()) {
            result.put(descriptor.intent().spec().artifactId(), descriptor);
        }
        return result;
    }

    private static Map<String, FinalizationCheckpoint.ArtifactMaterial>
            checkpointByArtifactId(Operation operation) {
        Map<String, FinalizationCheckpoint.ArtifactMaterial> result = new HashMap<>();
        for (FinalizationCheckpoint.ArtifactMaterial material
                : operation.checkpoint().artifacts()) {
            result.put(material.spec().artifactId(), material);
        }
        return result;
    }

    private static boolean isConfirmedTerminal(
            Operation operation,
            JdbcMeetingRepository.PublicationMeeting meeting,
            WorkflowStep workflow,
            Optional<MeetingManifest> latest,
            MeetingManifest expected) {
        if (latest.isEmpty() || !latest.orElseThrow().equals(expected)
                || meeting.aggregateVersion() != 5L
                || meeting.finalization().inputState() != InputState.INPUT_ACCEPTED
                || !meeting.snapshot().updatedAt().equals(operation.terminalAt())
                || meeting.manifestState() != expected.status()
                || workflow.leaseVersion() != operation.lease().leaseVersion()
                || !workflow.updatedAt().equals(operation.terminalAt())) {
            return false;
        }
        if (operation.isFailure()) {
            return meeting.snapshot().state() == MeetingState.FAILED
                    && workflow.status() == WorkflowStep.Status.DEAD
                    && workflow.attempt() == Math.addExact(
                    operation.leasedAttempt(), 1)
                    && operation.failure().code().equals(workflow.errorCode())
                    && FinalizationTerminalFailureIdentity.of(operation.failure()).equals(
                    workflow.errorSummary());
        }
        MeetingState expectedMeetingState = expected.status() == ManifestStatus.READY
                ? MeetingState.READY : MeetingState.READY_WITH_WARNINGS;
        return meeting.snapshot().state() == expectedMeetingState
                && workflow.status() == WorkflowStep.Status.COMPLETED
                && workflow.errorCode() == null
                && workflow.errorSummary() == null;
    }

    private static boolean isOriginalLeaseState(
            Connection connection,
            Operation operation,
            JdbcMeetingRepository.PublicationMeeting meeting,
            Facts facts,
            WorkflowStep workflow,
            Optional<MeetingManifest> latest) throws SQLException {
        if (!isInitialCompletionState(meeting, facts.input())
                || latest.isPresent()
                || workflow.status() != WorkflowStep.Status.LEASED
                || workflow.leaseVersion() < operation.lease().leaseVersion()
                || !terminalMarkerMatches(operation, workflow)) {
            return false;
        }
        if (workflow.leaseVersion() == operation.lease().leaseVersion()) {
            // version 未旋转时还必须核对数据库私有 token，避免公开 Workflow 快照
            // 无法区分的伪造旧请求被误判为“确定未提交”。
            return JdbcWorkflowStepStore.activeLeaseMatches(
                    connection, operation.lease());
        }
        // version 增大只允许来自续租或到期重领。marker 保留了旧请求的完整私有
        // identity；当前行仍精确携带它且 attempt 未变化，才能跨 lease 证明未提交。
        return true;
    }

    private static boolean terminalMarkerMatches(
            Operation operation,
            WorkflowStep workflow) {
        try {
            FinalizationTerminalOperationMarker marker =
                    FinalizationTerminalOperationMarker.decode(
                            workflow.errorCode(),
                            workflow.errorSummary(),
                            operation.checkpoint());
            if (!marker.lease().equals(operation.lease())
                    || !marker.terminalAt().equals(operation.terminalAt())
                    || marker.leasedAttempt() != workflow.attempt()
                    || !marker.readyArtifacts().equals(operation.readyArtifacts())) {
                return false;
            }
            if (operation.isFailure()) {
                return marker.kind()
                        == FinalizationTerminalOperationMarker.Kind.FAILURE
                        && marker.leasedAttempt() == operation.leasedAttempt()
                        && marker.failure().equals(operation.failure());
            }
            return marker.kind() == FinalizationTerminalOperationMarker.Kind.SUCCESS
                    && marker.failure() == null;
        } catch (IllegalArgumentException invalidMarker) {
            return false;
        }
    }

    private static ErrorCode mapStoredFactFailure(
            JdbcFinalizationExecutionFactStore.FailureKind kind) {
        return switch (kind) {
            case STORAGE_UNAVAILABLE -> ErrorCode.STORAGE_UNAVAILABLE;
            case CORRUPT_DATA -> ErrorCode.FACT_INCONSISTENT;
            case TRANSACTION_REQUIRED -> ErrorCode.INTERNAL;
        };
    }

    private static ErrorCode mapStoredFactFailure(
            JdbcManifestFactStore.FailureKind kind) {
        return switch (kind) {
            case STORAGE_UNAVAILABLE -> ErrorCode.STORAGE_UNAVAILABLE;
            case CORRUPT_DATA -> ErrorCode.FACT_INCONSISTENT;
            case TRANSACTION_REQUIRED -> ErrorCode.INTERNAL;
        };
    }

    private static ErrorCode mapStoredFactFailure(
            JdbcArtifactReadyMetadataStore.FailureKind kind) {
        return switch (kind) {
            case STORAGE_UNAVAILABLE -> ErrorCode.STORAGE_UNAVAILABLE;
            case CORRUPT_DATA -> ErrorCode.FACT_INCONSISTENT;
            case TRANSACTION_REQUIRED -> ErrorCode.INTERNAL;
        };
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
        LOGGER.log(code.retryable() ? Level.WARNING : Level.FINE,
                "最终收口 Completion 失败 code={0} phase={1} sqlState={2}",
                new Object[]{code, phase, sqlState == null ? "N/A" : sqlState});
        return new Failure(code);
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
                    "最终收口 Completion 恢复连接状态失败 storage=UNAVAILABLE");
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
                    "最终收口 Completion 恢复只读连接状态失败 storage=UNAVAILABLE");
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
                    "最终收口 Completion 废弃未确认连接失败 storage=UNAVAILABLE");
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
                    "最终收口 Completion 关闭连接失败 storage=UNAVAILABLE");
        }
    }

    private record Facts(
            FinalizationExecutionFact execution,
            FinalizationInputFact input,
            FinalUsageFact finalUsage) {
    }

    private enum MetadataState {
        EXACT_TERMINAL,
        ALL_ABSENT,
        PARTIAL_OR_DRIFT
    }

    private record MetadataSnapshot(
            MetadataState state,
            List<ArtifactReadyMetadataStore.ReadyMetadata> metadata) {
        private MetadataSnapshot {
            Objects.requireNonNull(state, "state");
            metadata = List.copyOf(metadata);
            if (state != MetadataState.EXACT_TERMINAL && !metadata.isEmpty()) {
                throw new IllegalArgumentException(
                        "only exact metadata snapshots may carry rows");
            }
        }
    }

    private record Operation(
            WorkflowStepStore.LeaseToken lease,
            FinalizationCheckpoint checkpoint,
            List<ArtifactDescriptor> readyArtifacts,
            java.time.Instant terminalAt,
            TerminalFailure failure,
            int leasedAttempt) {
        private Operation {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(checkpoint, "checkpoint");
            readyArtifacts = List.copyOf(readyArtifacts);
            Objects.requireNonNull(terminalAt, "terminalAt");
            if (failure == null && leasedAttempt != -1) {
                throw new IllegalArgumentException(
                        "successful completion must not carry a leased attempt");
            }
            if (failure != null && leasedAttempt < 0) {
                throw new IllegalArgumentException(
                        "failure completion requires a leased attempt");
            }
            if (failure != null) {
                Math.addExact(leasedAttempt, 1);
            }
        }

        boolean isFailure() {
            return failure != null;
        }
    }

    private enum Phase {
        OPEN_CONNECTION,
        MEETING_LOCK,
        FACT_READ,
        STATE_CHECK,
        WORKFLOW_LOCK,
        ENTITLEMENT_READ,
        ARTIFACT_METADATA,
        MANIFEST_BUILD,
        MANIFEST_APPEND,
        MEETING_UPDATE,
        WORKFLOW_TERMINAL,
        RECOVERY_READ,
        RECOVERY_ROLLBACK,
        COMMIT
    }

    enum FailurePoint {
        BEFORE_FIRST_WRITE,
        AFTER_ARTIFACT_METADATA,
        AFTER_MANIFEST,
        AFTER_MEETING,
        AFTER_WORKFLOW_TERMINAL,
        BEFORE_COMMIT
    }

    enum RecoveryPoint {
        AFTER_MEETING_SNAPSHOT
    }

    @FunctionalInterface
    interface FailureProbe {
        void hit(FailurePoint point);
    }

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
                        "最终收口 Completion 回滚未确认 storage=UNAVAILABLE");
            }
            return rollbackConfirmed;
        }

        boolean rollbackConfirmed() {
            return rollbackConfirmed;
        }
    }
}
