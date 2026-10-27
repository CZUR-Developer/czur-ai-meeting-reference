package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.finalization.ProductProfileSnapshot;
import com.czur.reference.application.command.CreateMeetingRequestFact;
import com.czur.reference.application.command.CreateMeetingRequestSnapshot;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.command.CompleteMeetingReserveFacts;
import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.application.port.FinalizationExecutionFactStore;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingOwnership;
import com.czur.reference.domain.meeting.MeetingState;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 公共 JDBC 单连接实现的会议开始 execution fact 工作单元。
 *
 * <p>首次路径严格按以下顺序执行：锁定 Meeting、验证归属和 Profile、锁定主
 * Reservation、反查并严格解码首次 reserve 的 AuthorizedProfileSnapshot、运行
 * {@link CapabilityPlanner}（完整 v2 Create 则核对 V013 原计划）、写入 V011
 * execution fact、应用领域状态转换并 CAS
 * 更新 Meeting，最后统一提交。所有 JDBC 原语共享同一个显式事务；本类不读取
 * Clock，也不允许数据库默认时间参与 startedAt。</p>
 *
 * <p>重放路径在锁定 Meeting 后优先读取 execution fact。若 startedAt、title 和
 * 完整 Profile 一致，且 Meeting 已处于 STARTING 或后续状态，则直接返回持久
 * 事实：不再读取首次 reserve 行，不使用调用方的 requested/provider/limits 计算
 * 新 Plan，也不再次应用 START。execution fact 已经原子证明首次授权链校验和规划
 * 成功，重放不应因更早的审计行不可用而改变历史会议。</p>
 *
 * <p>该实现不会追加 Outbox：当前开始状态没有已冻结的异步消费者，最终收口
 * Worker 由后续 admission 创建的 workflow fact 驱动。提交确认未知时返回稳定
 * {@link ErrorCode#OUTCOME_UNKNOWN} 并废弃连接；调用方必须用同一显式请求重试，
 * 由 fact + Meeting 状态的原子关系恢复，不得根据异常猜测事务结果。</p>
 */
public final class JdbcMeetingStartExecutionUnitOfWork
        implements MeetingStartExecutionUnitOfWork {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcMeetingStartExecutionUnitOfWork.class.getName());
    private static final EnumSet<MeetingState> REPLAYABLE_STARTED_STATES = EnumSet.of(
            MeetingState.STARTING,
            MeetingState.RUNNING,
            MeetingState.STOPPING,
            MeetingState.FINALIZING,
            MeetingState.READY,
            MeetingState.READY_WITH_WARNINGS,
            MeetingState.FAILED,
            MeetingState.CANCELLED,
            MeetingState.DELETION_PENDING,
            MeetingState.DELETED);
    private static final EnumSet<MeetingState> STATES_REQUIRING_EXECUTION_FACT = EnumSet.of(
            MeetingState.STARTING,
            MeetingState.RUNNING,
            MeetingState.STOPPING,
            MeetingState.FINALIZING,
            MeetingState.READY,
            MeetingState.READY_WITH_WARNINGS,
            MeetingState.FAILED);

    private final DataSource dataSource;
    private final JdbcDatabaseEngine engine;
    private final CapabilityPlanner capabilityPlanner;

    public JdbcMeetingStartExecutionUnitOfWork(
            DataSource dataSource,
            CapabilityPlanner capabilityPlanner) {
        this(dataSource, JdbcDatabaseEngine.MYSQL, capabilityPlanner);
    }

    /** 复用唯一连接上下文；引擎仅选择 SQL，不改变原 Start 状态机。 */
    public JdbcMeetingStartExecutionUnitOfWork(
            JdbcDatabaseContext database, CapabilityPlanner capabilityPlanner) {
        this(database.dataSource(), database.engine(), capabilityPlanner);
    }

    private JdbcMeetingStartExecutionUnitOfWork(DataSource dataSource,
            JdbcDatabaseEngine engine, CapabilityPlanner capabilityPlanner) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.capabilityPlanner = Objects.requireNonNull(
                capabilityPlanner, "capabilityPlanner");
    }

    /**
     * 原子注册首次执行事实，或按数据库中的历史事实稳定重放。
     */
    @Override
    public Result start(StartRequest request) {
        return startInMode(request, StartMode.LEGACY_V1);
    }

    /** 完整 v2 Create 的专用入口；模式由可信装配选择，不读取客户端版本标志。 */
    @Override
    public Result startCompleteCreate(StartRequest request) {
        return startInMode(request, StartMode.COMPLETE_CREATE_V2);
    }

    private Result startInMode(StartRequest request, StartMode mode) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(mode, "mode");
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

            phase = Phase.MEETING_LOCK;
            Optional<JdbcMeetingRepository.VersionedMeeting> lockedOptional =
                    JdbcMeetingRepository.findVersionedForUpdate(
                            connection, engine, request.meetingId());
            if (lockedOptional.isEmpty()) {
                return failAndRollback(
                        connection, resolution, ErrorCode.MEETING_NOT_FOUND, phase);
            }
            JdbcMeetingRepository.VersionedMeeting locked = lockedOptional.orElseThrow();
            MeetingAggregate.MeetingSnapshot current = locked.snapshot();

            phase = Phase.ACCESS_CHECK;
            if (!sameOwner(request, current)) {
                return failAndRollback(
                        connection, resolution, ErrorCode.ACCESS_DENIED, phase);
            }
            if (!sameProfileIdentity(request, current)) {
                return failAndRollback(
                        connection, resolution, ErrorCode.PROFILE_MISMATCH, phase);
            }
            // 已落弃场决定的会议不允许再 Start（含 WSS 首次启动）；决定行表示扫描器
            // 已冻结收口事实，重新启动会把会议拉回普通活跃链。行锁内查询避免竞态。
            phase = Phase.STATE_CHECK;
            if (JdbcMeetingAbandonmentStore.exists(connection, engine, request.meetingId())) {
                return failAndRollback(
                        connection, resolution, ErrorCode.INVALID_MEETING_STATE, phase);
            }

            phase = Phase.EXECUTION_FACT_READ;
            JdbcFinalizationExecutionFactStore factStore =
                    new JdbcFinalizationExecutionFactStore(connection, engine);
            Optional<FinalizationExecutionFact> existing =
                    factStore.find(request.meetingId());

            if (existing.isPresent()) {
                phase = Phase.REPLAY_CHECK;
                FinalizationExecutionFact winner = existing.orElseThrow();
                if (!sameReplayIdentity(request, winner)) {
                    return failAndRollback(
                            connection, resolution,
                            ErrorCode.EXECUTION_FACT_CONFLICT, phase);
                }
                if (!REPLAYABLE_STARTED_STATES.contains(current.state())) {
                    return failAndRollback(
                            connection, resolution, ErrorCode.FACT_INCONSISTENT, phase);
                }
                resolution.rollback(connection);
                LOGGER.log(Level.FINE,
                        "会议开始执行事实稳定重放 state={0}", current.state());
                return new Success(SuccessCode.REPLAYED, current, winner);
            }

            phase = Phase.STATE_CHECK;
            if (current.state() != MeetingState.CREATED
                    && current.state() != MeetingState.AUTHORIZED) {
                ErrorCode code = STATES_REQUIRING_EXECUTION_FACT.contains(current.state())
                        ? ErrorCode.FACT_INCONSISTENT
                        : ErrorCode.INVALID_MEETING_STATE;
                return failAndRollback(connection, resolution, code, phase);
            }

            // 只有确实允许首次开始的状态才读取较早的授权审计。否则授权行缺失
            // 会掩盖 Meeting 自身已经足以确定的结果：例如开始前已取消应稳定返回
            // INVALID_MEETING_STATE，STARTING 却缺 fact 应稳定返回 FACT_INCONSISTENT。
            phase = Phase.AUTHORIZATION_READ;
            AuthorizationFacts authorization = readAuthorization(
                    connection, engine, current);
            ErrorCode authorizationFailure = validateAuthorization(
                    request, current, authorization);
            if (authorizationFailure != null) {
                return failAndRollback(
                        connection, resolution, authorizationFailure, phase);
            }
            if (authorization.currentReservation().state()
                    != UsageReservation.State.RESERVED) {
                return failAndRollback(
                        connection, resolution,
                        ErrorCode.AUTHORIZATION_INCONSISTENT, phase);
            }
            if (request.startedAt().isBefore(current.updatedAt())) {
                return failAndRollback(
                        connection, resolution, ErrorCode.START_TIME_CONFLICT, phase);
            }

            CapabilityPlan createPlan = null;
            if (mode == StartMode.COMPLETE_CREATE_V2) {
                phase = Phase.CREATE_FACT_READ;
                Optional<CreateMeetingFactStore.Record> createFact =
                        new JdbcCreateMeetingFactStore(connection, engine)
                                .findByMeetingId(request.meetingId());
                if (createFact.isEmpty()
                        || !sameCompleteCreateFacts(
                                request, current, authorization, createFact.orElseThrow())) {
                    return failAndRollback(connection, resolution,
                            ErrorCode.CREATE_FACT_INCONSISTENT, phase);
                }
                CreateMeetingResponseFact response = createFact.orElseThrow().response();
                if (response.capabilityDecisions().stream()
                        .anyMatch(decision -> decision.planRevision() != 1L)) {
                    return failAndRollback(connection, resolution,
                            ErrorCode.CREATE_FACT_INCONSISTENT, phase);
                }
                createPlan = new CapabilityPlan(1L, response.capabilityDecisions());
                CapabilityDecision realtime = createPlan.decisionFor(
                        CapabilityIds.TRANSCRIPT_REALTIME);
                if (createPlan.failed() || realtime == null
                        || realtime.action() != CapabilityDecision.Action.ENABLE) {
                    return failAndRollback(connection, resolution,
                            ErrorCode.CREATE_FACT_INCONSISTENT, phase);
                }
            }

            phase = Phase.CAPABILITY_PLAN;
            AuthorizedProfileSnapshot authorized = authorization.profile();
            CapabilityPlan currentPlan = capabilityPlanner.plan(
                    request.modelCapabilities(),
                    request.productProfile(),
                    authorized.requestedCapabilities(),
                    authorized.grantedCapabilities(),
                    request.deploymentLimits(),
                    1L);
            if (currentPlan.failed()
                    || (createPlan != null && !createPlan.equals(currentPlan))) {
                return failAndRollback(
                        connection, resolution,
                        ErrorCode.CAPABILITY_PLAN_FAILED, phase);
            }
            CapabilityPlan plan = createPlan == null ? currentPlan : createPlan;

            FinalizationExecutionFact candidate = FinalizationExecutionFact.freeze(
                    request.meetingId(),
                    request.startedAt(),
                    request.title(),
                    request.productProfile(),
                    plan);
            phase = Phase.EXECUTION_FACT_WRITE;
            FinalizationExecutionFactStore.PersistResult persisted =
                    factStore.persist(candidate);
            if (persisted.status()
                    == FinalizationExecutionFactStore.PersistStatus.CONFLICT) {
                return failAndRollback(
                        connection, resolution,
                        ErrorCode.EXECUTION_FACT_CONFLICT, phase);
            }
            if (persisted.status()
                    != FinalizationExecutionFactStore.PersistStatus.APPLIED) {
                // 持有 Meeting 行锁且开始前没有读到事实；同一数据库事务内突然
                // 得到 REPLAYED 表示有写入方绕过了该工作单元，不能继续补齐状态。
                return failAndRollback(
                        connection, resolution, ErrorCode.FACT_INCONSISTENT, phase);
            }

            phase = Phase.MEETING_UPDATE;
            MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(current);
            if (current.state() == MeetingState.CREATED) {
                aggregate.apply(MeetingEvent.AUTHORIZE, request.startedAt());
            }
            aggregate.apply(MeetingEvent.START, request.startedAt());
            if (aggregate.state() != MeetingState.STARTING) {
                return failAndRollback(
                        connection, resolution, ErrorCode.INTERNAL, phase);
            }
            JdbcMeetingRepository.updateOwnedExisting(
                    connection, engine, locked, aggregate.snapshot());

            phase = Phase.COMMIT;
            commitStarted = true;
            connection.commit();
            commitCompleted = true;
            LOGGER.log(Level.INFO,
                    "会议开始执行事实已原子提交 state=STARTING planRevision={0}",
                    plan.planRevision());
            return new Success(SuccessCode.STARTED, aggregate.snapshot(), candidate);
        } catch (JdbcCreateMeetingFactStore.StoredFactException failure) {
            if (commitStarted) {
                return loggedFailure(ErrorCode.OUTCOME_UNKNOWN, Phase.COMMIT, null);
            }
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            ErrorCode code = failure.kind()
                    == JdbcCreateMeetingFactStore.FailureKind.STORAGE_UNAVAILABLE
                    ? ErrorCode.STORAGE_UNAVAILABLE
                    : ErrorCode.CREATE_FACT_INCONSISTENT;
            return loggedFailure(code, phase, null);
        } catch (JdbcFinalizationExecutionFactStore.StoredFactException failure) {
            if (commitStarted) {
                return loggedFailure(ErrorCode.OUTCOME_UNKNOWN, Phase.COMMIT, null);
            }
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            ErrorCode code = failure.kind()
                    == JdbcFinalizationExecutionFactStore.FailureKind.STORAGE_UNAVAILABLE
                    ? ErrorCode.STORAGE_UNAVAILABLE : ErrorCode.INTERNAL;
            return loggedFailure(code, phase, null);
        } catch (JdbcUsageReservationStore.CorruptUsageDataException failure) {
            if (commitStarted) {
                return loggedFailure(ErrorCode.OUTCOME_UNKNOWN, Phase.COMMIT, null);
            }
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            return loggedFailure(ErrorCode.INTERNAL, phase, null);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            if (commitStarted) {
                return loggedFailure(ErrorCode.OUTCOME_UNKNOWN, Phase.COMMIT, null);
            }
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            return loggedFailure(ErrorCode.INTERNAL, phase, null);
        } catch (RuntimeException failure) {
            if (commitStarted) {
                return loggedFailure(ErrorCode.OUTCOME_UNKNOWN, Phase.COMMIT, null);
            }
            if (transactionStarted) {
                resolution.rollback(connection);
            }
            return loggedFailure(ErrorCode.INTERNAL, phase, null);
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
            JdbcReadLookupSupport.FailureKind kind =
                    JdbcReadLookupSupport.classify(failure);
            ErrorCode code = kind
                    == JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE
                    || JdbcSqlErrors.isTransientTransaction(engine, failure)
                    ? ErrorCode.STORAGE_UNAVAILABLE : ErrorCode.INTERNAL;
            return loggedFailure(
                    code, phase, JdbcReadLookupSupport.safeSqlState(failure));
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
     * 从 Meeting 的稳定 Reservation 引用恢复首次授权事实。
     *
     * <p>方法不接收调用方提供的 granted capabilities。operation 行由完整部署、
     * 主体和 reservationId 反查，严格 codec 再次核对正文中的全部范围、幂等键和
     * 摘要。缺行返回空事实，由上层映射 AUTHORIZATION_INCONSISTENT；损坏或多行
     * 由 Store 抛出固定异常并整体回滚。</p>
     */
    private static AuthorizationFacts readAuthorization(
            Connection connection,
            JdbcDatabaseEngine engine,
            MeetingAggregate.MeetingSnapshot meeting) throws SQLException {
        if (meeting.usageReservationId() == null) {
            return AuthorizationFacts.missing();
        }
        Optional<JdbcUsageReservationStore.LockedReservation> reservation =
                JdbcUsageReservationStore.lockReservationForOwnedCreation(
                        connection, engine,
                        meeting.deploymentScope(),
                        meeting.subjectScope(),
                        meeting.usageReservationId());
        if (reservation.isEmpty()) {
            return AuthorizationFacts.missing();
        }
        Optional<JdbcUsageReservationStore.ReserveOperationRow> operation =
                JdbcUsageReservationStore.findReserveOperationByReservation(
                        connection, engine,
                        meeting.deploymentScope(),
                        meeting.subjectScope(),
                        meeting.usageReservationId());
        if (operation.isEmpty()) {
            return AuthorizationFacts.missing();
        }
        JdbcUsageReservationStore.ReserveOperationRow row = operation.orElseThrow();
        if (!UsageOperationScopes.RESERVE.equals(row.operationScope())
                || !meeting.usageReservationId().equals(row.reservationId())) {
            return AuthorizationFacts.missing();
        }
        final UsageOperationResult result;
        try {
            result = UsageOperationResultJson.decode(
                    row.resultBody(),
                    meeting.deploymentScope(),
                    meeting.subjectScope(),
                    row.operationScope(),
                    row.idempotencyKey(),
                    row.requestDigest());
        } catch (IllegalArgumentException malformed) {
            throw new JdbcUsageReservationStore.CorruptUsageDataException(
                    "stored reserve authorization is invalid");
        }
        if (!result.supportsOwnedMeetingCreation()) {
            return AuthorizationFacts.missing();
        }
        return new AuthorizationFacts(
                result.requireAuthorizedProfileSnapshot(),
                result.reservation(),
                reservation.orElseThrow().reservation(),
                result.grantExpiresAt(),
                row.idempotencyKey(),
                row.requestDigest());
    }

    /** 返回 null 表示持久授权链与请求及 Meeting 完全一致。 */
    private static ErrorCode validateAuthorization(
            StartRequest request,
            MeetingAggregate.MeetingSnapshot meeting,
            AuthorizationFacts authorization) {
        if (!authorization.present()) {
            return ErrorCode.AUTHORIZATION_INCONSISTENT;
        }
        AuthorizedProfileSnapshot profile = authorization.profile();
        UsageReservation first = authorization.firstReservation();
        UsageReservation current = authorization.currentReservation();

        if (!meeting.profileId().equals(profile.profileId())
                || meeting.profileRevision() != profile.profileRevision()) {
            return ErrorCode.PROFILE_MISMATCH;
        }
        if (!request.requestedCapabilities().equals(profile.requestedCapabilities())
                || !profile.grantedCapabilities().containsAll(
                        profile.requestedCapabilities())) {
            return ErrorCode.AUTHORIZATION_INCONSISTENT;
        }
        if (!request.productProfile().allCapabilities().containsAll(
                profile.requestedCapabilities())
                || !request.productProfile().allCapabilities().containsAll(
                        profile.grantedCapabilities())) {
            return ErrorCode.PROFILE_MISMATCH;
        }
        if (first.state() != UsageReservation.State.RESERVED
                || first.isBound()
                || first.version() != 0
                || !meeting.usageReservationId().equals(first.reservationId())
                || !OwnedMeetingCreationFacts.sameInitialReservationFacts(current, first)
                || !meeting.meetingId().equals(current.expectedMeetingId())
                || !meeting.meetingId().equals(current.boundMeetingId())
                || current.boundAt() == null
                || !OwnedMeetingCreationFacts.samePersistentInstant(
                        meeting.createdAt(), current.boundAt())
                || !meeting.grantId().equals(current.grantId())
                || authorization.grantExpiresAt() == null
                || !OwnedMeetingCreationFacts.samePersistentInstant(
                        meeting.grantExpiresAt(), authorization.grantExpiresAt())
                || current.state() == UsageReservation.State.CANCELLED
                // owned 创建 bind 是该 Reservation 在首次 start 前唯一允许的
                // 状态迁移：初始 version=0，bind 后必须精确为 1。更大版本表示
                // 已有未被本工作单元解释的演进，不能继续按“首次开始”使用。
                || current.version() != 1
                || current.reservedUsage().unit() != UsageUnit.MEETING_DURATION_MS
                || current.reservedUsage().amount() > profile.maxDurationMs()) {
            return ErrorCode.AUTHORIZATION_INCONSISTENT;
        }
        return null;
    }

    /**
     * 把 V013 完整首次响应绑定到已锁定 Meeting 和首次 reserve，而非只相信
     * meeting_id 索引命中。标题属于 Start 的显式输入，可在 Create 后改变，因此
     * 不要求它与 Create 请求标题相等；能力请求与冻结计划则不得改变。
     */
    private static boolean sameCompleteCreateFacts(
            StartRequest request,
            MeetingAggregate.MeetingSnapshot meeting,
            AuthorizationFacts authorization,
            CreateMeetingFactStore.Record fact) {
        CreateMeetingRequestFact requestFact = fact.request();
        CreateMeetingRequestSnapshot snapshot = requestFact.requestSnapshot();
        CreateMeetingResponseFact response = fact.response();
        return fact.scope().equals(IdempotencyScopeCodec.meetingCreateV2(
                        meeting.deploymentScope(), meeting.subjectScope()))
                && fact.key().equals(snapshot.idempotencyKey())
                && OwnedMeetingCreationFacts.samePersistentInstant(
                        fact.createdAt(), meeting.createdAt())
                && meeting.meetingId().equals(response.meetingId())
                && meeting.clientSessionId().equals(snapshot.clientSessionId())
                && meeting.profileId().equals(snapshot.profile().id())
                && meeting.profileRevision() == snapshot.profile().revision()
                && response.acceptedProfile().equals(snapshot.profile())
                && response.acceptedAudioProfile().equals(snapshot.audioProfile())
                && snapshot.requestedCapabilities().equals(
                        authorization.profile().requestedCapabilities())
                && request.requestedCapabilities().equals(
                        snapshot.requestedCapabilities())
                && response.grantedCapabilities().equals(
                        authorization.profile().grantedCapabilities())
                && response.limits().maxMeetingDurationMs()
                        <= authorization.profile().maxDurationMs()
                && CompleteMeetingReserveFacts.internalReserveKey(requestFact)
                        .equals(authorization.reserveKey())
                && requestFact.canonicalRequestDigest()
                        .equals(authorization.reserveRequestDigest());
    }

    private static boolean sameOwner(
            StartRequest request,
            MeetingAggregate.MeetingSnapshot meeting) {
        return request.deploymentScope().equals(meeting.deploymentScope())
                && request.subjectScope().equals(meeting.subjectScope())
                && !MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(
                        meeting.subjectScope());
    }

    private static boolean sameProfileIdentity(
            StartRequest request,
            MeetingAggregate.MeetingSnapshot meeting) {
        return request.productProfile().id().equals(meeting.profileId())
                && request.productProfile().revision() == meeting.profileRevision();
    }

    private static boolean sameReplayIdentity(
            StartRequest request,
            FinalizationExecutionFact existing) {
        return request.meetingId().equals(existing.meetingId())
                && request.startedAt().equals(existing.startedAt())
                && Objects.equals(request.title(), existing.title())
                && ProductProfileSnapshot.from(request.productProfile())
                        .equals(existing.productProfile());
    }

    private static Result failAndRollback(
            Connection connection,
            TransactionResolution resolution,
            ErrorCode code,
            Phase phase) {
        resolution.rollback(connection);
        return loggedFailure(code, phase, null);
    }

    private static Failure loggedFailure(
            ErrorCode code,
            Phase phase,
            String sqlState) {
        Level level = code.retryable() ? Level.WARNING : Level.FINE;
        LOGGER.log(level,
                "会议开始执行事实工作单元失败 phase={0} code={1} sqlState={2}",
                new Object[]{phase.name(), code.name(),
                        sqlState == null ? "N/A" : sqlState});
        return new Failure(code);
    }

    private static boolean rollbackQuietly(Connection connection) {
        if (connection == null) {
            return false;
        }
        try {
            connection.rollback();
            return true;
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "会议开始执行事实事务回滚失败");
            return false;
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
            LOGGER.log(Level.WARNING, "会议开始执行事实连接状态恢复失败");
        }
    }

    private static void abortQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "会议开始执行事实未知连接废弃失败");
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "会议开始执行事实连接关闭失败");
        }
    }

    private record AuthorizationFacts(
            AuthorizedProfileSnapshot profile,
            UsageReservation firstReservation,
            UsageReservation currentReservation,
            java.time.Instant grantExpiresAt,
            String reserveKey,
            String reserveRequestDigest) {
        private static AuthorizationFacts missing() {
            return new AuthorizationFacts(null, null, null, null, null, null);
        }

        private boolean present() {
            return profile != null
                    && firstReservation != null
                    && currentReservation != null
                    && grantExpiresAt != null
                    && reserveKey != null
                    && reserveRequestDigest != null;
        }

        @Override
        public String toString() {
            return "AuthorizationFacts[redacted]";
        }
    }

    private static final class TransactionResolution {
        private boolean rollbackConfirmed;

        void rollback(Connection connection) {
            rollbackConfirmed = rollbackQuietly(connection);
        }

        boolean rollbackConfirmed() {
            return rollbackConfirmed;
        }
    }

    private enum Phase {
        OPEN_CONNECTION,
        MEETING_LOCK,
        ACCESS_CHECK,
        EXECUTION_FACT_READ,
        AUTHORIZATION_READ,
        CREATE_FACT_READ,
        REPLAY_CHECK,
        STATE_CHECK,
        CAPABILITY_PLAN,
        EXECUTION_FACT_WRITE,
        MEETING_UPDATE,
        COMMIT
    }

    private enum StartMode {
        LEGACY_V1,
        COMPLETE_CREATE_V2
    }
}
