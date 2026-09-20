package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.MeetingCreateOutcome;
import com.czur.reference.application.port.MeetingCreateReplayLookup;
import com.czur.reference.application.port.MeetingRecoveryLookup;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基于 PostgreSQL 已提交事实的 owned 会议创建恢复查询。
 *
 * <p>实现先复用 {@link JdbcMeetingCreateReplayLookup} 核对创建 Outcome，
 * 再用同一 {@link DataSource} 的主库连接联合读取 meetingId、
 * deployment/clientSession 和完整 scope 下的 V3 Reservation。成功恢复
 * 始终返回数据库中的原始 {@code CREATED} Outcome，不会用已演进的
 * 当前 Meeting 重建响应。</p>
 *
 * <p>全路径只执行 {@code SELECT}：不禁用 auto-commit、不开启显式
 * 事务，不加行锁，不提交、回滚、取消、补写或修复任何业务事实。
 * PostgreSQL 默认 READ COMMITTED 下，若首读未发现 Outcome，但随后看到
 * Meeting 或已绑定 Reservation，实现会二次查询 Outcome；二读出现
 * Success 时重新读取一次当前事实，避免把创建事务刚好在多条
 * SELECT 之间提交误判为单边 Meeting。</p>
 *
 * <p>日志只记录固定状态、原因和已清洗 SQLState，不输出部署、
 * 主体、会议、幂等键、摘要、Grant、Reservation、Outcome 正文或
 * 数据库地址。</p>
 */
public final class JdbcMeetingRecoveryLookup implements MeetingRecoveryLookup {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcMeetingRecoveryLookup.class.getName());
    private final DataSource dataSource;
    private final MeetingCreateReplayLookup replayLookup;

    /** 使用同一主库 DataSource 组装 Outcome 和当前事实查询。 */
    public JdbcMeetingRecoveryLookup(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.replayLookup = new JdbcMeetingCreateReplayLookup(dataSource);
    }

    /**
     * 只读核对 Outcome、双 Meeting 身份和 Reservation 事实。
     *
     * <p>正常冲突与不一致都以五状态返回，不用异常代替业务分支。</p>
     */
    @Override
    public Result lookup(OwnedMeetingCreationUnitOfWork.CreateRequest request) {
        Objects.requireNonNull(request, "request");
        MeetingCreateReplayLookup.Result first = replayLookup.lookup(replayRequest(request));
        Result terminal = mapTerminalReplay(first);
        if (terminal != null) {
            return logged(terminal);
        }

        Optional<MeetingCreateOutcome> outcome = replayedOutcome(first);
        CurrentFactsRead firstFacts = readCurrentFacts(request, outcome.orElse(null));
        if (firstFacts.failure() != null) {
            return logged(firstFacts.failure());
        }
        CurrentFacts facts = firstFacts.facts();
        if (outcome.isPresent()) {
            return logged(classifyOutcome(outcome.orElseThrow(), facts, request));
        }

        // Outcome 首读缺失但创建事务的其它已提交事实可见时，
        // 必须二读 Outcome 来消除 READ COMMITTED 的提交窗口。
        if (facts.hasAnyMeeting() || facts.hasBoundReservation()) {
            MeetingCreateReplayLookup.Result second = replayLookup.lookup(replayRequest(request));
            Result secondTerminal = mapTerminalReplay(second);
            if (secondTerminal != null) {
                return logged(secondTerminal);
            }
            Optional<MeetingCreateOutcome> secondOutcome = replayedOutcome(second);
            if (secondOutcome.isPresent()) {
                MeetingCreateOutcome observed = secondOutcome.orElseThrow();
                if (observed instanceof MeetingCreateOutcome.Success) {
                    // 首轮三条 SELECT 可能横跨创建 commit；Outcome 二读已成功
                    // 时重读当前事实，避免使用提交前后混合视图。
                    CurrentFactsRead refreshed = readCurrentFacts(request, observed);
                    if (refreshed.failure() != null) {
                        return logged(refreshed.failure());
                    }
                    facts = refreshed.facts();
                }
                return logged(classifyOutcome(observed, facts, request));
            }
            return logged(missingOutcomeWithVisibleFacts(facts));
        }

        return logged(classifyNotFound(facts, request));
    }

    private CurrentFactsRead readCurrentFacts(
            OwnedMeetingCreationUnitOfWork.CreateRequest request,
            MeetingCreateOutcome knownOutcome) {
        Connection connection = null;
        FactPhase phase = FactPhase.OPEN_CONNECTION;
        try {
            connection = dataSource.getConnection();
            phase = FactPhase.MEETING_BY_ID;
            Optional<MeetingAggregate.MeetingSnapshot> byId =
                    JdbcMeetingRepository.find(connection, request.meetingId());
            phase = FactPhase.MEETING_BY_SCOPE_SESSION;
            Optional<MeetingAggregate.MeetingSnapshot> byScopeSession =
                    JdbcMeetingRepository.findByDeploymentScopeAndClientSessionId(
                            connection,
                            request.deploymentScope(),
                            request.clientSessionId());
            phase = FactPhase.RESERVATION;
            Optional<UsageReservation> reservation =
                    JdbcUsageReservationStore.findReservation(
                            connection,
                            request.deploymentScope(),
                            request.subjectScope(),
                            request.reserveResult().reservation().reservationId());
            return CurrentFactsRead.success(new CurrentFacts(
                    byId, byScopeSession, reservation));
        } catch (JdbcUsageReservationStore.CorruptUsageDataException corruption) {
            return CurrentFactsRead.failure(inconsistent(
                    InconsistencyReason.RESERVATION_FACT_MISMATCH));
        } catch (IllegalArgumentException | NullPointerException invalidStoredFact) {
            return CurrentFactsRead.failure(corruptFactResult(phase, knownOutcome));
        } catch (SQLException failure) {
            JdbcReadLookupSupport.FailureKind kind = JdbcReadLookupSupport.classify(failure);
            if (kind == JdbcReadLookupSupport.FailureKind.INCONSISTENT) {
                Result result = corruptFactResult(phase, knownOutcome);
                LOGGER.log(Level.FINE,
                        "会议创建恢复查询读取损坏事实 phase={0} sqlState={1}",
                        new Object[]{phase, JdbcReadLookupSupport.safeSqlState(failure)});
                return CurrentFactsRead.failure(result);
            }
            boolean retryable =
                    kind == JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE;
            UnavailableReason reason = retryable
                    ? UnavailableReason.TRANSIENT_STORAGE_FAILURE
                    : UnavailableReason.STORAGE_FAILURE;
            LOGGER.log(Level.WARNING,
                    "会议创建恢复查询存储不可用 phase={0} sqlState={1} retryable={2}",
                    new Object[]{phase, JdbcReadLookupSupport.safeSqlState(failure), retryable});
            return CurrentFactsRead.failure(new Unavailable(reason, retryable));
        } catch (RuntimeException failure) {
            // DataSource、驱动代理或自定义连接可以以非 SQLException
            // 报错；没有稳定 SQLState 时不能无依据承诺可重试。
            return CurrentFactsRead.failure(new Unavailable(
                    UnavailableReason.STORAGE_FAILURE, false));
        } finally {
            closeQuietly(connection);
        }
    }

    private static Result classifyOutcome(
            MeetingCreateOutcome outcome,
            CurrentFacts facts,
            OwnedMeetingCreationUnitOfWork.CreateRequest request) {
        if (outcome instanceof MeetingCreateOutcome.Success success) {
            return classifySuccess(success, facts, request);
        }
        return classifyFailure((MeetingCreateOutcome.Failure) outcome, facts, request);
    }

    private static Result classifySuccess(
            MeetingCreateOutcome.Success success,
            CurrentFacts facts,
            OwnedMeetingCreationUnitOfWork.CreateRequest request) {
        MeetingAggregate.MeetingSnapshot created = success.result().meeting();
        UsageOperationResult firstReserve = request.reserveResult();
        UsageReservation initialReservation = firstReserve.reservation();
        if (!sameCreatedOutcomeFacts(created, request, firstReserve)) {
            return inconsistent(InconsistencyReason.SUCCESS_OUTCOME_FACT_MISMATCH);
        }
        if (!facts.hasSameMeetingIdentity()) {
            return inconsistent(InconsistencyReason.SUCCESS_OUTCOME_FACT_MISMATCH);
        }
        MeetingAggregate.MeetingSnapshot current = facts.meetingById().orElseThrow();
        // UNKNOWN 只是公开协议解码时的占位哨兵，不是可持久化的会议生命周期事实。
        // 除 UNKNOWN 外的每个枚举值都可能是创建交易成功后的合法演进状态，恢复查询
        // 仍返回原始 CREATED Outcome，不用当前状态重建创建响应。状态放宽不会放宽
        // 其它事实：下方仍严格校验 Meeting 不可变字段、Reservation 初始事实及绑定关系。
        if (current.state() == MeetingState.UNKNOWN
                || !sameMeetingImmutableFacts(current, created)) {
            return inconsistent(InconsistencyReason.SUCCESS_OUTCOME_FACT_MISMATCH);
        }
        if (facts.reservation().isEmpty()
                || !OwnedMeetingCreationFacts.sameInitialReservationFacts(
                        facts.reservation().orElseThrow(), initialReservation)) {
            return inconsistent(InconsistencyReason.RESERVATION_FACT_MISMATCH);
        }
        UsageReservation currentReservation = facts.reservation().orElseThrow();
        boolean allowedState = currentReservation.state() == UsageReservation.State.RESERVED
                || currentReservation.state() == UsageReservation.State.COMMITTED;
        if (!allowedState
                || !currentReservation.isBound()
                || !request.meetingId().equals(currentReservation.boundMeetingId())
                || !OwnedMeetingCreationFacts.samePersistentInstant(
                        currentReservation.boundAt(), current.createdAt())) {
            return inconsistent(InconsistencyReason.SUCCESS_OUTCOME_FACT_MISMATCH);
        }
        return new Replayed(success);
    }

    private static Result classifyFailure(
            MeetingCreateOutcome.Failure failure,
            CurrentFacts facts,
            OwnedMeetingCreationUnitOfWork.CreateRequest request) {
        if (facts.hasAnyMeeting()) {
            return inconsistent(InconsistencyReason.FAILURE_OUTCOME_WITH_MEETING);
        }
        UsageReservation initial = request.reserveResult().reservation();
        if (facts.reservation().isEmpty()
                || !OwnedMeetingCreationFacts.sameInitialReservationFacts(
                        facts.reservation().orElseThrow(), initial)) {
            return inconsistent(InconsistencyReason.RESERVATION_FACT_MISMATCH);
        }
        UsageReservation current = facts.reservation().orElseThrow();
        if (current.isBound()
                || current.state() == UsageReservation.State.COMMITTED) {
            return inconsistent(
                    InconsistencyReason.FAILURE_OUTCOME_WITH_BOUND_RESERVATION);
        }
        boolean allowedInitial = current.state() == UsageReservation.State.RESERVED
                && current.version() == 0;
        boolean allowedCompensation = current.state() == UsageReservation.State.CANCELLED;
        if (!allowedInitial && !allowedCompensation) {
            return inconsistent(InconsistencyReason.RESERVATION_FACT_MISMATCH);
        }
        return new Replayed(failure);
    }

    private static Result classifyNotFound(
            CurrentFacts facts,
            OwnedMeetingCreationUnitOfWork.CreateRequest request) {
        if (facts.hasAnyMeeting()) {
            return inconsistent(InconsistencyReason.OUTCOME_MISSING_WITH_MEETING);
        }
        if (facts.hasBoundReservation()) {
            return inconsistent(
                    InconsistencyReason.OUTCOME_MISSING_WITH_BOUND_RESERVATION);
        }
        UsageReservation initial = request.reserveResult().reservation();
        if (facts.reservation().isEmpty()
                || !OwnedMeetingCreationFacts.sameInitialReservationFacts(
                        facts.reservation().orElseThrow(), initial)) {
            return inconsistent(InconsistencyReason.RESERVATION_FACT_MISMATCH);
        }
        UsageReservation current = facts.reservation().orElseThrow();
        if (current.state() != UsageReservation.State.RESERVED
                || current.version() != 0
                || current.isBound()) {
            return inconsistent(InconsistencyReason.RESERVATION_FACT_MISMATCH);
        }
        return new NotFound();
    }

    private static Result missingOutcomeWithVisibleFacts(CurrentFacts facts) {
        if (facts.hasAnyMeeting()) {
            return inconsistent(InconsistencyReason.OUTCOME_MISSING_WITH_MEETING);
        }
        if (facts.hasBoundReservation()) {
            return inconsistent(
                    InconsistencyReason.OUTCOME_MISSING_WITH_BOUND_RESERVATION);
        }
        // 只有调用方注入的 Outcome lookup 在两读之间改变了当前
        // 事实视图才可能到达这里；仍 fail closed，绝不伪造 NOT_FOUND。
        return inconsistent(InconsistencyReason.RESERVATION_FACT_MISMATCH);
    }

    private static boolean sameCreatedOutcomeFacts(
            MeetingAggregate.MeetingSnapshot created,
            OwnedMeetingCreationUnitOfWork.CreateRequest request,
            UsageOperationResult firstReserve) {
        UsageReservation reservation = firstReserve.reservation();
        return request.meetingId().equals(created.meetingId())
                && request.deploymentScope().equals(created.deploymentScope())
                && request.clientSessionId().equals(created.clientSessionId())
                && request.subjectScope().equals(created.subjectScope())
                && reservation.grantId().equals(created.grantId())
                && OwnedMeetingCreationFacts.samePersistentInstant(
                        firstReserve.grantExpiresAt(), created.grantExpiresAt())
                && reservation.reservationId().equals(created.usageReservationId())
                && request.profileId().equals(created.profileId())
                && request.profileRevision() == created.profileRevision();
    }

    private static boolean sameMeetingImmutableFacts(
            MeetingAggregate.MeetingSnapshot current,
            MeetingAggregate.MeetingSnapshot created) {
        return current.meetingId().equals(created.meetingId())
                && current.deploymentScope().equals(created.deploymentScope())
                && current.clientSessionId().equals(created.clientSessionId())
                && current.subjectScope().equals(created.subjectScope())
                && Objects.equals(current.grantId(), created.grantId())
                && current.grantExpiresAt() != null
                && OwnedMeetingCreationFacts.samePersistentInstant(
                        current.grantExpiresAt(), created.grantExpiresAt())
                && Objects.equals(
                        current.usageReservationId(), created.usageReservationId())
                && current.profileId().equals(created.profileId())
                && current.profileRevision() == created.profileRevision()
                && OwnedMeetingCreationFacts.samePersistentInstant(
                        current.createdAt(), created.createdAt());
    }

    private static MeetingCreateReplayLookup.Request replayRequest(
            OwnedMeetingCreationUnitOfWork.CreateRequest request) {
        return new MeetingCreateReplayLookup.Request(
                request.deploymentScope(),
                request.subjectScope(),
                request.clientSessionId(),
                request.idempotencyKey(),
                request.canonicalRequestDigest());
    }

    /**
     * 将不需要继续读当前事实的 typed replay 结果映射到恢复结果。
     *
     * @return 终止结果；{@code null} 表示仍需要处理 NOT_FOUND 或 REPLAYED
     */
    private static Result mapTerminalReplay(MeetingCreateReplayLookup.Result replay) {
        if (replay instanceof MeetingCreateReplayLookup.Conflict) {
            return new Conflict();
        }
        if (replay instanceof MeetingCreateReplayLookup.Unavailable unavailable) {
            UnavailableReason reason = unavailable.retryable()
                    ? UnavailableReason.TRANSIENT_STORAGE_FAILURE
                    : UnavailableReason.STORAGE_FAILURE;
            return new Unavailable(reason, unavailable.retryable());
        }
        if (replay instanceof MeetingCreateReplayLookup.Inconsistent inconsistent) {
            InconsistencyReason reason = inconsistent.reason()
                    == MeetingCreateReplayLookup.InconsistencyReason.STORED_OUTCOME_INVALID
                    ? InconsistencyReason.STORED_OUTCOME_INVALID
                    : InconsistencyReason.SUCCESS_OUTCOME_FACT_MISMATCH;
            return new Inconsistent(reason);
        }
        return null;
    }

    private static Optional<MeetingCreateOutcome> replayedOutcome(
            MeetingCreateReplayLookup.Result replay) {
        return replay instanceof MeetingCreateReplayLookup.Replayed replayed
                ? Optional.of(replayed.outcome())
                : Optional.empty();
    }

    private static Result corruptFactResult(
            FactPhase phase,
            MeetingCreateOutcome knownOutcome) {
        if (phase == FactPhase.RESERVATION) {
            return inconsistent(InconsistencyReason.RESERVATION_FACT_MISMATCH);
        }
        if (knownOutcome instanceof MeetingCreateOutcome.Failure) {
            return inconsistent(InconsistencyReason.FAILURE_OUTCOME_WITH_MEETING);
        }
        if (knownOutcome instanceof MeetingCreateOutcome.Success) {
            return inconsistent(InconsistencyReason.SUCCESS_OUTCOME_FACT_MISMATCH);
        }
        return inconsistent(InconsistencyReason.OUTCOME_MISSING_WITH_MEETING);
    }

    private static Inconsistent inconsistent(InconsistencyReason reason) {
        return new Inconsistent(reason);
    }

    private static Result logged(Result result) {
        Level level = result.status() == Status.UNAVAILABLE ? Level.WARNING : Level.FINE;
        if (result instanceof Inconsistent inconsistent) {
            LOGGER.log(level,
                    "会议创建恢复查询完成 status={0} reason={1}",
                    new Object[]{result.status(), inconsistent.reason()});
        } else if (result instanceof Unavailable unavailable) {
            LOGGER.log(level,
                    "会议创建恢复查询完成 status={0} reason={1} retryable={2}",
                    new Object[]{result.status(), unavailable.reason(), unavailable.retryable()});
        } else {
            LOGGER.log(level, "会议创建恢复查询完成 status={0}", result.status());
        }
        return result;
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "会议创建恢复查询关闭连接失败");
        }
    }

    private enum FactPhase {
        OPEN_CONNECTION,
        MEETING_BY_ID,
        MEETING_BY_SCOPE_SESSION,
        RESERVATION
    }

    private record CurrentFacts(
            Optional<MeetingAggregate.MeetingSnapshot> meetingById,
            Optional<MeetingAggregate.MeetingSnapshot> meetingByScopeSession,
            Optional<UsageReservation> reservation) {
        private CurrentFacts {
            Objects.requireNonNull(meetingById, "meetingById");
            Objects.requireNonNull(meetingByScopeSession, "meetingByScopeSession");
            Objects.requireNonNull(reservation, "reservation");
        }

        boolean hasAnyMeeting() {
            return meetingById.isPresent() || meetingByScopeSession.isPresent();
        }

        boolean hasSameMeetingIdentity() {
            return meetingById.isPresent()
                    && meetingByScopeSession.isPresent()
                    && meetingById.orElseThrow().meetingId().equals(
                            meetingByScopeSession.orElseThrow().meetingId());
        }

        boolean hasBoundReservation() {
            return reservation.isPresent() && reservation.orElseThrow().isBound();
        }
    }

    private record CurrentFactsRead(CurrentFacts facts, Result failure) {
        private CurrentFactsRead {
            if ((facts == null) == (failure == null)) {
                throw new IllegalArgumentException(
                        "exactly one current facts read branch is required");
            }
        }

        static CurrentFactsRead success(CurrentFacts facts) {
            return new CurrentFactsRead(Objects.requireNonNull(facts, "facts"), null);
        }

        static CurrentFactsRead failure(Result failure) {
            return new CurrentFactsRead(null, Objects.requireNonNull(failure, "failure"));
        }
    }
}
