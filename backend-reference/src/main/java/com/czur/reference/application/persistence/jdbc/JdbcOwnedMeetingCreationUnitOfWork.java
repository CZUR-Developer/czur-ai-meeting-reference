package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandEventFactory;
import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.IdempotencyStore;
import com.czur.reference.application.port.OutboxStore;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.meeting.MeetingAggregate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLRecoverableException;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基于 PostgreSQL 单连接事务的 owned 会议原子创建工作单元。
 *
 * <p>本实现只消费已经完成首次 reserve 的稳定结果，不调用身份 Provider、权益
 * Provider 或其它外部系统。每次首次创建在同一个 JDBC 事务中完成会议命令幂等
 * 预占、Reservation bind、owned Meeting 插入和 {@code MeetingCreated} Outbox
 * 追加；提交前的任一确定失败都会显式回滚，因此不会留下半绑定 Reservation、
 * 孤立幂等记录或缺少事件的会议。</p>
 *
 * <p>事务在锁定 Reservation 后只读取一次注入的 {@link Clock}，并截断到
 * PostgreSQL {@code timestamptz} 的微秒精度。同一个 {@code bindAt} 同时写入
 * Reservation、Meeting、幂等记录和 Outbox。调用方传入的 reserve 对象不会被
 * 直接信任：实现会按完整四元组读取 {@code usage_operation}，严格解码 v2
 * 首次授权快照，再与锁定行和请求逐项核对。</p>
 *
 * <p>日志只包含固定阶段和稳定错误码，不记录部署、主体、幂等 scope/key/digest、
 * Grant、Reservation、SQL 正文或驱动动态异常消息。只有
 * {@link Connection#commit()} 调用已经开始后抛出的失败才返回
 * {@link ErrorCode#OUTCOME_UNKNOWN}；所有 commit 前故障均保持可证明的回滚语义。</p>
 */
public final class JdbcOwnedMeetingCreationUnitOfWork
        implements OwnedMeetingCreationUnitOfWork {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcOwnedMeetingCreationUnitOfWork.class.getName());

    private final DataSource dataSource;
    private final Clock clock;

    public JdbcOwnedMeetingCreationUnitOfWork(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 执行 owned 会议创建事务。
     *
     * <p>同一幂等键和摘要直接返回首次数据库结果，不重新 bind 或追加 Outbox；
     * 摘要冲突稳定拒绝。首次路径锁定 Reservation 后会再次读取会议幂等记录，
     * 让等待中的并发请求观察先提交事务的最终事实。</p>
     */
    @Override
    public Result create(CreateRequest request) {
        Objects.requireNonNull(request, "request");
        String meetingScope = IdempotencyScopeCodec.meetingCreate(
                request.deploymentScope(), request.subjectScope());
        Connection connection = null;
        boolean transactionStarted = false;
        boolean commitStarted = false;
        boolean commitCompleted = false;
        boolean previousAutoCommit = true;
        Phase phase = Phase.OPEN_CONNECTION;
        try {
            connection = dataSource.getConnection();
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            transactionStarted = true;

            phase = Phase.IDEMPOTENCY_READ;
            Optional<IdempotencyStore.IdempotencyRecord> previous =
                    JdbcIdempotencyStore.find(
                            connection, meetingScope, request.idempotencyKey());
            if (previous.isPresent()) {
                Result replay = classifyIdempotency(previous.get(), request);
                rollbackQuietly(connection);
                return replay;
            }

            phase = Phase.RESERVATION_LOCK;
            UsageReservation suppliedReservation = request.reserveResult().reservation();
            Optional<JdbcUsageReservationStore.LockedReservation> lockedOptional =
                    JdbcUsageReservationStore.lockReservationForOwnedCreation(
                            connection,
                            request.deploymentScope(),
                            request.subjectScope(),
                            suppliedReservation.reservationId());
            if (lockedOptional.isEmpty()) {
                return failAndRollback(connection, ErrorCode.RESERVATION_NOT_FOUND, false);
            }

            // 行锁等待期间同 key 的另一事务可能已经提交；锁后必须再次读取。
            phase = Phase.IDEMPOTENCY_READ;
            previous = JdbcIdempotencyStore.find(
                    connection, meetingScope, request.idempotencyKey());
            if (previous.isPresent()) {
                Result replay = classifyIdempotency(previous.get(), request);
                rollbackQuietly(connection);
                return replay;
            }

            phase = Phase.RESERVE_RESULT_READ;
            StableReserveValidation stable = readAndValidateStableReserve(
                    connection, request, lockedOptional.get().reservation());
            if (stable.failure() != null) {
                return failAndRollback(
                        connection, stable.failure().code(), stable.failure().retryable());
            }

            JdbcUsageReservationStore.LockedReservation locked = lockedOptional.get();
            UsageReservation current = locked.reservation();
            Result staticReservationFailure = validateStaticReservation(
                    current, stable.result().reservation());
            if (staticReservationFailure != null) {
                rollbackQuietly(connection);
                return staticReservationFailure;
            }

            phase = Phase.CLOCK_READ;
            Instant bindAt = Objects.requireNonNull(clock.instant(), "clock.instant()")
                    .truncatedTo(ChronoUnit.MICROS);
            Result timedReservationFailure = validateAtBindTime(
                    current, stable.result(), locked, bindAt);
            if (timedReservationFailure != null) {
                rollbackQuietly(connection);
                return timedReservationFailure;
            }

            MeetingAggregate aggregate = MeetingAggregate.owned(
                    request.meetingId(),
                    request.deploymentScope(),
                    request.clientSessionId(),
                    request.subjectScope(),
                    current.grantId(),
                    stable.result().grantExpiresAt(),
                    current.reservationId(),
                    stable.result().requireAuthorizedProfileSnapshot().profileId(),
                    stable.result().requireAuthorizedProfileSnapshot().profileRevision(),
                    bindAt);
            MeetingCommandResult created = new MeetingCommandResult(
                    MeetingCommandResult.Operation.CREATED, aggregate.snapshot());
            IdempotencyStore.IdempotencyRecord candidate =
                    new IdempotencyStore.IdempotencyRecord(
                            meetingScope,
                            request.idempotencyKey(),
                            request.canonicalRequestDigest(),
                            created);

            phase = Phase.IDEMPOTENCY_RESERVE;
            IdempotencyStore.Reservation idempotency =
                    JdbcIdempotencyStore.reserve(connection, candidate, bindAt);
            if (idempotency.status() != IdempotencyStore.ReservationStatus.ACQUIRED) {
                Result replay = classifyIdempotency(idempotency.record(), request);
                rollbackQuietly(connection);
                return replay;
            }

            phase = Phase.RESERVATION_BIND;
            if (!JdbcUsageReservationStore.bindForOwnedMeeting(
                    connection, current, bindAt)) {
                // 已持有行锁且状态、版本、lease、时间均已验证，CAS 不命中表示
                // 数据库事实或触发器偏离冻结契约，不能猜测成业务状态冲突。
                return failAndRollback(connection, ErrorCode.INTERNAL, false);
            }

            phase = Phase.MEETING_INSERT;
            JdbcMeetingRepository.insertOwned(connection, created.meeting());

            phase = Phase.OUTBOX_APPEND;
            OutboxStore.AppendResult append = JdbcOutboxStore.append(
                    connection,
                    MeetingCommandEventFactory.meetingCreated(created.meeting(), bindAt));
            if (append.status() != OutboxStore.AppendStatus.APPENDED) {
                return failAndRollback(connection, ErrorCode.INTERNAL, false);
            }

            phase = Phase.COMMIT;
            commitStarted = true;
            connection.commit();
            commitCompleted = true;
            LOGGER.log(Level.FINE, "owned 会议创建工作单元已提交 result=CREATED");
            return new Success(SuccessCode.CREATED, created);
        } catch (JdbcUsageReservationStore.CorruptUsageDataException corruption) {
            if (transactionStarted && !commitStarted) {
                rollbackQuietly(connection);
            }
            return loggedFailure(ErrorCode.INTERNAL, false, phase, null);
        } catch (IllegalArgumentException | IllegalStateException invalidFact) {
            if (commitStarted) {
                return loggedFailure(
                        ErrorCode.OUTCOME_UNKNOWN, true, Phase.COMMIT, null);
            }
            if (transactionStarted && !commitStarted) {
                rollbackQuietly(connection);
            }
            return loggedFailure(ErrorCode.INTERNAL, false, phase, null);
        } catch (RuntimeException runtimeFailure) {
            // Clock、DataSource 代理或驱动可能抛出非 SQLException 的运行时异常。
            // commit 调用一旦开始，无论驱动以 SQLException 还是运行时异常报告
            // 故障，都不能再证明数据库最终提交或回滚；Error 不在此捕获，交由
            // 进程级策略处理。
            if (commitStarted) {
                return loggedFailure(
                        ErrorCode.OUTCOME_UNKNOWN, true, Phase.COMMIT, null);
            }
            if (transactionStarted && !commitStarted) {
                rollbackQuietly(connection);
            }
            return loggedFailure(ErrorCode.INTERNAL, false, phase, null);
        } catch (SQLException sqlFailure) {
            if (commitStarted) {
                return loggedFailure(
                        ErrorCode.OUTCOME_UNKNOWN, true, Phase.COMMIT, sqlFailure.getSQLState());
            }
            if (transactionStarted) {
                rollbackQuietly(connection);
            }
            if (phase == Phase.MEETING_INSERT && isUniqueViolation(sqlFailure)) {
                return loggedFailure(
                        ErrorCode.MEETING_CONFLICT, false, phase, sqlFailure.getSQLState());
            }
            ErrorCode code = isTemporaryStorageFailure(sqlFailure)
                    ? ErrorCode.STORAGE_UNAVAILABLE : ErrorCode.INTERNAL;
            return loggedFailure(
                    code, code == ErrorCode.STORAGE_UNAVAILABLE,
                    phase, sqlFailure.getSQLState());
        } finally {
            // commit 抛错时结果未知，setAutoCommit(true) 本身可能再次尝试提交，
            // 因而只能直接归还/关闭连接，不能用“恢复状态”改变未知事务结果。
            if (!commitStarted || commitCompleted) {
                restoreAutoCommitQuietly(connection, previousAutoCommit);
            }
            closeQuietly(connection);
        }
    }

    private static StableReserveValidation readAndValidateStableReserve(
            Connection connection,
            CreateRequest request,
            UsageReservation lockedReservation) throws SQLException {
        UsageOperationResult supplied = request.reserveResult();
        Optional<JdbcUsageReservationStore.UsageOperationRow> operation =
                JdbcUsageReservationStore.findOperation(
                        connection,
                        supplied.deploymentScope(),
                        supplied.subjectScope(),
                        supplied.operationScope(),
                        supplied.idempotencyKey());
        if (operation.isEmpty()) {
            return StableReserveValidation.failure(ErrorCode.INTERNAL, false);
        }
        JdbcUsageReservationStore.UsageOperationRow row = operation.get();
        if (!sameDigest(row.requestDigest(), supplied.requestDigest())) {
            return StableReserveValidation.failure(
                    ErrorCode.RESERVATION_STATE_CONFLICT, false);
        }
        if (!row.reservationId().equals(supplied.reservation().reservationId())
                || !row.reservationId().equals(lockedReservation.reservationId())) {
            return StableReserveValidation.failure(ErrorCode.INTERNAL, false);
        }

        final UsageOperationResult stored;
        try {
            stored = UsageOperationResultJson.decode(
                    row.resultBody(),
                    supplied.deploymentScope(),
                    supplied.subjectScope(),
                    supplied.operationScope(),
                    supplied.idempotencyKey(),
                    supplied.requestDigest());
        } catch (IllegalArgumentException invalidStoredResult) {
            throw new JdbcUsageReservationStore.CorruptUsageDataException(
                    "stored reserve result is invalid");
        }
        if (!stored.supportsOwnedMeetingCreation()) {
            return StableReserveValidation.failure(ErrorCode.INTERNAL, false);
        }
        if (!stored.equals(supplied)) {
            return StableReserveValidation.failure(
                    ErrorCode.RESERVATION_STATE_CONFLICT, false);
        }
        AuthorizedProfileSnapshot profile = stored.requireAuthorizedProfileSnapshot();
        UsageReservation reservation = stored.reservation();
        if (!profile.grantedCapabilities().containsAll(profile.requestedCapabilities())
                || reservation.reservedUsage().unit() != UsageUnit.MEETING_DURATION_MS
                || reservation.reservedUsage().amount() > profile.maxDurationMs()) {
            return StableReserveValidation.failure(ErrorCode.INTERNAL, false);
        }
        return StableReserveValidation.success(stored);
    }

    private static Result validateStaticReservation(
            UsageReservation current,
            UsageReservation firstReserved) {
        if (current.state() != UsageReservation.State.RESERVED || current.isBound()) {
            return failure(ErrorCode.RESERVATION_STATE_CONFLICT, false);
        }
        if (current.version() != firstReserved.version()) {
            return failure(ErrorCode.VERSION_CONFLICT, false);
        }
        if (!sameInitialReservationFacts(current, firstReserved)) {
            return failure(ErrorCode.INTERNAL, false);
        }
        return null;
    }

    private static Result validateAtBindTime(
            UsageReservation current,
            UsageOperationResult stableReserve,
            JdbcUsageReservationStore.LockedReservation locked,
            Instant bindAt) {
        if (bindAt.isBefore(current.createdAt())) {
            return failure(ErrorCode.INTERNAL, false);
        }
        if (current.isExpiredAt(bindAt)) {
            return failure(ErrorCode.RESERVATION_EXPIRED, false);
        }
        Instant grantExpiry = stableReserve.grantExpiresAt().truncatedTo(ChronoUnit.MICROS);
        if (!bindAt.isBefore(grantExpiry)) {
            return failure(ErrorCode.RESERVATION_EXPIRED, false);
        }
        if (locked.hasActiveLeaseAt(bindAt)) {
            return failure(ErrorCode.RESERVATION_STATE_CONFLICT, true);
        }
        return null;
    }

    private static boolean sameInitialReservationFacts(
            UsageReservation current,
            UsageReservation firstReserved) {
        return current.reservationId().equals(firstReserved.reservationId())
                && current.deploymentScope().equals(firstReserved.deploymentScope())
                && current.subjectScope().equals(firstReserved.subjectScope())
                && current.grantId().equals(firstReserved.grantId())
                && current.expectedMeetingId().equals(firstReserved.expectedMeetingId())
                && current.reservedUsage().equals(firstReserved.reservedUsage())
                && samePersistentInstant(current.createdAt(), firstReserved.createdAt())
                && samePersistentInstant(current.expiresAt(), firstReserved.expiresAt())
                && current.state() == firstReserved.state()
                && current.boundMeetingId() == null
                && current.boundAt() == null
                && current.actualUsage() == null
                && current.committedAt() == null
                && current.cancelledAt() == null
                && current.cancellationReasonCode() == null;
    }

    private static Result classifyIdempotency(
            IdempotencyStore.IdempotencyRecord record,
            CreateRequest request) {
        if (!sameDigest(record.requestDigest(), request.canonicalRequestDigest())) {
            return failure(ErrorCode.IDEMPOTENCY_CONFLICT, false);
        }
        MeetingCommandResult result = record.result();
        MeetingAggregate.MeetingSnapshot meeting = result.meeting();
        if (result.operation() != MeetingCommandResult.Operation.CREATED
                || !request.deploymentScope().equals(meeting.deploymentScope())
                || !request.subjectScope().equals(meeting.subjectScope())
                || !request.clientSessionId().equals(meeting.clientSessionId())) {
            return failure(ErrorCode.INTERNAL, false);
        }
        LOGGER.log(Level.FINE, "owned 会议创建工作单元命中稳定重放 result=REPLAYED");
        return new Success(SuccessCode.REPLAYED, result);
    }

    private static boolean sameDigest(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean samePersistentInstant(Instant left, Instant right) {
        return left.truncatedTo(ChronoUnit.MICROS)
                .equals(right.truncatedTo(ChronoUnit.MICROS));
    }

    private static Result failAndRollback(
            Connection connection, ErrorCode code, boolean retryable) {
        rollbackQuietly(connection);
        return loggedFailure(code, retryable, Phase.BUSINESS_VALIDATION, null);
    }

    private static Failure failure(ErrorCode code, boolean retryable) {
        return new Failure(code, retryable);
    }

    private static Failure loggedFailure(
            ErrorCode code, boolean retryable, Phase phase, String sqlState) {
        Level level = retryable ? Level.WARNING : Level.FINE;
        LOGGER.log(level,
                "owned 会议创建工作单元失败 phase={0} code={1} sqlState={2}",
                new Object[]{phase.name(), code.name(), safeSqlState(sqlState)});
        return failure(code, retryable);
    }

    private static boolean isUniqueViolation(SQLException failure) {
        return "23505".equals(failure.getSQLState());
    }

    private static boolean isTemporaryStorageFailure(SQLException failure) {
        if (failure instanceof SQLTransientException
                || failure instanceof SQLRecoverableException) {
            return true;
        }
        String sqlState = failure.getSQLState();
        if (sqlState == null || sqlState.length() < 2) {
            return true;
        }
        String category = sqlState.substring(0, 2);
        return switch (category) {
            case "08", "40", "53", "54", "55", "57", "58" -> true;
            default -> false;
        };
    }

    private static String safeSqlState(String sqlState) {
        if (sqlState == null || sqlState.isBlank()) {
            return "UNKNOWN";
        }
        return sqlState.matches("[A-Z0-9]{5}") ? sqlState : "INVALID";
    }

    private static void rollbackQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            // commit 前的业务结果仍然确定；回滚失败不能被误报为 OUTCOME_UNKNOWN。
            LOGGER.log(Level.WARNING, "owned 会议创建工作单元回滚失败");
        }
    }

    private static void restoreAutoCommitQuietly(
            Connection connection, boolean previousAutoCommit) {
        if (connection == null) {
            return;
        }
        try {
            if (!connection.isClosed()) {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException restoreFailure) {
            LOGGER.log(Level.WARNING, "owned 会议创建工作单元恢复连接状态失败");
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            LOGGER.log(Level.WARNING, "owned 会议创建工作单元关闭连接失败");
        }
    }

    private enum Phase {
        OPEN_CONNECTION,
        IDEMPOTENCY_READ,
        RESERVATION_LOCK,
        RESERVE_RESULT_READ,
        CLOCK_READ,
        IDEMPOTENCY_RESERVE,
        RESERVATION_BIND,
        MEETING_INSERT,
        OUTBOX_APPEND,
        COMMIT,
        BUSINESS_VALIDATION
    }

    private record StableReserveValidation(
            UsageOperationResult result,
            Failure failure) {
        private static StableReserveValidation success(UsageOperationResult result) {
            return new StableReserveValidation(
                    Objects.requireNonNull(result, "result"), null);
        }

        private static StableReserveValidation failure(
                ErrorCode code, boolean retryable) {
            return new StableReserveValidation(null, new Failure(code, retryable));
        }
    }
}
