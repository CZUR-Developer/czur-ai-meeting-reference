package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingAccessGuard;
import com.czur.reference.application.command.MeetingCommandEventFactory;
import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.IdempotencyStore;
import com.czur.reference.application.port.MeetingFinalizationStore;
import com.czur.reference.application.port.OutboxStore;
import com.czur.reference.application.port.OwnedMeetingStopUnitOfWork;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingState;

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
 * 基于单连接事务的 owned 会议原子停止工作单元。
 *
 * <p>每次调用先按 meetingId 锁定会议行，再通过 {@link MeetingAccessGuard}
 * 校验部署和真实主体。归属拒绝发生在任何停止幂等查询之前，防止跨部署、跨
 * 主体或历史无主会议借助幂等键探测已存在结果。通过归属后，幂等范围由服务端
 * 上下文中的部署和主体编码，客户端不能指定或覆盖。</p>
 *
 * <p>{@code STARTING}/{@code RUNNING} 首次停止会在同一事务中完成：保存停止
 * 幂等结果、以完整不可变事实和聚合版本 CAS 更新 Meeting、追加
 * {@code INPUT_COMPLETE_PENDING} 会议级输入事实和
 * {@code MeetingStopRequested} Outbox。已经处于
 * {@code STOPPING}/{@code FINALIZING} 时，只为新的幂等键保存当时快照，不重复
 * 更新生命周期或追加事件，但仍核对数据库中的 pending/accepted 事实。其它状态稳定返回
 * {@link ErrorCode#INVALID_MEETING_STATE}。Grant 截止时间不参与停止判断。</p>
 *
 * <p>事务在行锁和状态校验后只读取一次注入的 {@link Clock}，并截断到
 * 数据库时间列的微秒精度。首次状态更新时，同一时刻用于
 * Meeting、pending、停止幂等记录和 Outbox。日志不输出部署、主体、幂等 scope/key/
 * digest、Grant、Reservation、数据库地址或会议正文。</p>
 */
public final class JdbcOwnedMeetingStopUnitOfWork
        implements OwnedMeetingStopUnitOfWork {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcOwnedMeetingStopUnitOfWork.class.getName());

    private final DataSource dataSource;
    private final Clock clock;
    private final JdbcDatabaseEngine engine;

    public JdbcOwnedMeetingStopUnitOfWork(DataSource dataSource, Clock clock) {
        this(dataSource, JdbcDatabaseEngine.MYSQL, clock);
    }

    public JdbcOwnedMeetingStopUnitOfWork(JdbcDatabaseContext database, Clock clock) {
        this(database.dataSource(), database.engine(), clock);
    }

    private JdbcOwnedMeetingStopUnitOfWork(DataSource dataSource, JdbcDatabaseEngine engine, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * 执行 owned 会议停止事务。
     *
     * <p>同键同摘要返回首次稳定结果；同键不同摘要返回冲突。会议行锁覆盖归属、
     * 幂等、状态决策和首次状态更新，使不同幂等键并发停止同一会议时最多只有
     * 一个事务产生状态更新和停止 Outbox。</p>
     */
    @Override
    public Result stop(StopRequest request) {
        Objects.requireNonNull(request, "request");
        Connection connection = null;
        boolean transactionStarted = false;
        boolean commitStarted = false;
        boolean commitCompleted = false;
        boolean previousAutoCommit = true;
        TransactionResolution transactionResolution = new TransactionResolution();
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
                        connection, transactionResolution, ErrorCode.MEETING_NOT_FOUND);
            }
            JdbcMeetingRepository.VersionedMeeting locked = lockedOptional.get();
            MeetingAggregate.MeetingSnapshot current = locked.snapshot();

            // 归属校验必须早于 IdempotencyScopeCodec 计算后的任何数据库查询。
            // 这样跨主体调用者不能通过 key 是否存在区分历史事实。
            phase = Phase.ACCESS_CHECK;
            if (MeetingAccessGuard.evaluate(request.context(), current)
                    != MeetingAccessGuard.Decision.ALLOWED) {
                return failAndRollback(
                        connection, transactionResolution, ErrorCode.ACCESS_DENIED);
            }

            String stopScope = IdempotencyScopeCodec.meetingStop(
                    request.context().deploymentScope(),
                    request.context().identity().subject().subjectScope());
            phase = Phase.IDEMPOTENCY_READ;
            Optional<IdempotencyStore.IdempotencyRecord> previous =
                    JdbcIdempotencyStore.findCurrent(
                            connection, engine, stopScope, request.idempotencyKey());
            if (previous.isPresent()) {
                Result replay = classifyIdempotency(previous.get(), request, current);
                transactionResolution.rollback(connection);
                return replay;
            }

            phase = Phase.STATE_CHECK;
            boolean advancesState;
            if (current.state() == MeetingState.STARTING
                    || current.state() == MeetingState.RUNNING) {
                advancesState = true;
            } else if (current.state() == MeetingState.STOPPING
                    || current.state() == MeetingState.FINALIZING) {
                advancesState = false;
            } else {
                return failAndRollback(
                        connection, transactionResolution,
                        ErrorCode.INVALID_MEETING_STATE);
            }

            phase = Phase.CLOCK_READ;
            Instant acceptedAt = Objects.requireNonNull(
                            clock.instant(), "clock.instant()")
                    .truncatedTo(ChronoUnit.MICROS);
            if (acceptedAt.isBefore(current.updatedAt())) {
                // STARTING/RUNNING 路径随后由聚合再次保护；这里显式覆盖无需
                // 状态更新的 STOPPING/FINALIZING，避免新幂等事实早于会议现状。
                return failAndRollback(
                        connection, transactionResolution, ErrorCode.INTERNAL);
            }

            MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(current);
            if (advancesState) {
                aggregate.apply(MeetingEvent.STOP_REQUESTED, acceptedAt);
            }
            MeetingCommandResult accepted = new MeetingCommandResult(
                    MeetingCommandResult.Operation.STOP_ACCEPTED,
                    aggregate.snapshot());
            IdempotencyStore.IdempotencyRecord candidate =
                    new IdempotencyStore.IdempotencyRecord(
                            stopScope,
                            request.idempotencyKey(),
                            request.canonicalRequestDigest(),
                            accepted);

            phase = Phase.IDEMPOTENCY_RESERVE;
            IdempotencyStore.Reservation idempotency =
                    JdbcIdempotencyStore.reserve(
                            connection, engine, candidate, acceptedAt);
            if (idempotency.status()
                    != IdempotencyStore.ReservationStatus.ACQUIRED) {
                Result replay = classifyIdempotency(
                        idempotency.record(), request, current);
                transactionResolution.rollback(connection);
                return replay;
            }

            if (advancesState) {
                phase = Phase.MEETING_UPDATE;
                JdbcMeetingRepository.updateOwnedExisting(
                        connection, engine, locked, accepted.meeting());

            }

            // Stop 只持久化输入完成意图，绝不生成 receipt 或伪造 INPUT_ACCEPTED。
            // 稳定 idempotency body 继续保存首次 STOPPING 快照；未来 REST 响应必须
            // 另读 meeting-level finalization fact，不能让旧 body 覆盖新 receipt。
            phase = Phase.INPUT_COMPLETION_PENDING;
            MeetingFinalizationStore.Result completion =
                    JdbcMeetingFinalizationStore.requestInputCompletion(
                            connection, engine, request.meetingId());
            if (!(completion instanceof MeetingFinalizationStore.Applied)
                    && !(completion instanceof MeetingFinalizationStore.Replayed)) {
                return failAndRollback(
                        connection, transactionResolution, ErrorCode.INTERNAL);
            }

            // 客户端事件会议在 Stop 提交的同一事务登记待收尾事实：同步 Finalizer
            // 失败或进程中断后，后台扫描仍能重试同一场收口。旧音频会议没有
            // meeting_client_input 行，不写待办，继续由原收口链处理。
            phase = Phase.FINALIZATION_PENDING;
            JdbcClientFinalizationPendingStore.markIfClient(
                    connection, engine, request.meetingId(), acceptedAt);

            if (advancesState) {
                phase = Phase.OUTBOX_APPEND;
                OutboxStore.AppendResult append = JdbcOutboxStore.append(
                        connection, engine,
                        MeetingCommandEventFactory.meetingStopRequested(
                                accepted.meeting(), acceptedAt));
                if (append.status() != OutboxStore.AppendStatus.APPENDED) {
                    return failAndRollback(
                            connection, transactionResolution, ErrorCode.INTERNAL);
                }
            }

            phase = Phase.COMMIT;
            commitStarted = true;
            connection.commit();
            commitCompleted = true;
            LOGGER.log(Level.FINE,
                    "owned 会议停止工作单元已提交 result=ACCEPTED stateAdvanced={0}",
                    advancesState);
            return new Success(SuccessCode.ACCEPTED, accepted);
        } catch (IllegalArgumentException | IllegalStateException invalidFact) {
            if (commitStarted) {
                return loggedFailure(ErrorCode.OUTCOME_UNKNOWN, Phase.COMMIT, null);
            }
            if (transactionStarted) {
                transactionResolution.rollback(connection);
            }
            return loggedFailure(ErrorCode.INTERNAL, phase, null);
        } catch (RuntimeException runtimeFailure) {
            // DataSource、Clock、JDBC 代理或驱动均可能用运行时异常报告故障。
            // commit 调用一旦开始便无法确认最终结局；commit 前异常仍按确定
            // 失败回滚。Error 不在此捕获，交由进程级策略处理。
            if (commitStarted) {
                return loggedFailure(ErrorCode.OUTCOME_UNKNOWN, Phase.COMMIT, null);
            }
            if (transactionStarted) {
                transactionResolution.rollback(connection);
            }
            return loggedFailure(ErrorCode.INTERNAL, phase, null);
        } catch (SQLException sqlFailure) {
            if (commitStarted) {
                return loggedFailure(
                        ErrorCode.OUTCOME_UNKNOWN, Phase.COMMIT,
                        sqlFailure.getSQLState());
            }
            if (transactionStarted) {
                transactionResolution.rollback(connection);
            }
            ErrorCode code = (JdbcSqlErrors.isTransientTransaction(engine, sqlFailure)
                    || isTemporaryStorageFailure(sqlFailure))
                    ? ErrorCode.STORAGE_UNAVAILABLE : ErrorCode.INTERNAL;
            return loggedFailure(code, phase, sqlFailure.getSQLState());
        } finally {
            // 只有 commit 已确认，或 rollback 已确认，才可恢复原 auto-commit。
            // 结果未知或回滚失败时，setAutoCommit(true) 可能隐式提交未决事务，
            // 因此只能直接关闭并丢弃连接。
            if (commitCompleted || transactionResolution.rollbackConfirmed()) {
                restoreAutoCommitQuietly(connection, previousAutoCommit);
            } else {
                // commit 结果未知或 rollback 未确认时，不把可能带未决事务的物理
                // 连接归还连接池；与事件日志/最终收口 JDBC 原语使用同一废弃规则。
                abortQuietly(connection);
            }
            closeQuietly(connection);
        }
    }

    private static Result classifyIdempotency(
            IdempotencyStore.IdempotencyRecord record,
            StopRequest request,
            MeetingAggregate.MeetingSnapshot current) {
        if (!sameDigest(record.requestDigest(), request.canonicalRequestDigest())) {
            return failure(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
        MeetingCommandResult result = record.result();
        MeetingAggregate.MeetingSnapshot stored = result.meeting();
        if (result.operation() != MeetingCommandResult.Operation.STOP_ACCEPTED
                || (stored.state() != MeetingState.STOPPING
                        && stored.state() != MeetingState.FINALIZING)
                || !sameImmutableFacts(stored, current)
                || !request.meetingId().equals(stored.meetingId())
                || !request.context().deploymentScope().equals(stored.deploymentScope())
                || !request.context().identity().subject().subjectScope()
                        .equals(stored.subjectScope())) {
            return failure(ErrorCode.INTERNAL);
        }
        LOGGER.log(Level.FINE,
                "owned 会议停止工作单元命中稳定重放 result=REPLAYED");
        return new Success(SuccessCode.REPLAYED, result);
    }

    private static boolean sameImmutableFacts(
            MeetingAggregate.MeetingSnapshot left,
            MeetingAggregate.MeetingSnapshot right) {
        return left.meetingId().equals(right.meetingId())
                && left.deploymentScope().equals(right.deploymentScope())
                && left.clientSessionId().equals(right.clientSessionId())
                && left.subjectScope().equals(right.subjectScope())
                && Objects.equals(left.grantId(), right.grantId())
                && Objects.equals(left.grantExpiresAt(), right.grantExpiresAt())
                && Objects.equals(left.usageReservationId(), right.usageReservationId())
                && left.profileId().equals(right.profileId())
                && left.profileRevision() == right.profileRevision()
                && left.createdAt().equals(right.createdAt());
    }

    private static boolean sameDigest(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static Result failAndRollback(
            Connection connection,
            TransactionResolution transactionResolution,
            ErrorCode code) {
        transactionResolution.rollback(connection);
        return loggedFailure(code, Phase.BUSINESS_VALIDATION, null);
    }

    private static Failure failure(ErrorCode code) {
        return new Failure(code);
    }

    private static Failure loggedFailure(
            ErrorCode code, Phase phase, String sqlState) {
        Level level = code.retryable() ? Level.WARNING : Level.FINE;
        LOGGER.log(level,
                "owned 会议停止工作单元失败 phase={0} code={1} sqlState={2}",
                new Object[]{phase.name(), code.name(), safeSqlState(sqlState)});
        return failure(code);
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

    /**
     * 尝试回滚并返回数据库是否明确确认了回滚。
     *
     * <p>返回 false 后不得调用 {@code setAutoCommit(true)}；JDBC 规范允许该调用
     * 提交当前事务。回滚失败不改变已确定的业务错误，也不会伪装成提交结果
     * 未知。</p>
     */
    private static boolean rollbackQuietly(Connection connection) {
        if (connection == null) {
            return false;
        }
        try {
            connection.rollback();
            return true;
        } catch (SQLException | RuntimeException rollbackFailure) {
            LOGGER.log(Level.WARNING, "owned 会议停止工作单元回滚失败");
            return false;
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
        } catch (SQLException | RuntimeException restoreFailure) {
            LOGGER.log(Level.WARNING, "owned 会议停止工作单元恢复连接状态失败");
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException closeFailure) {
            LOGGER.log(Level.WARNING, "owned 会议停止工作单元关闭连接失败");
        }
    }

    private static void abortQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException abortFailure) {
            LOGGER.log(Level.WARNING, "owned 会议停止工作单元废弃数据库连接失败");
        }
    }

    /** 记录本地事务是否已由数据库明确回滚，用于约束 finally 的连接恢复。 */
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
        IDEMPOTENCY_READ,
        STATE_CHECK,
        CLOCK_READ,
        IDEMPOTENCY_RESERVE,
        MEETING_UPDATE,
        INPUT_COMPLETION_PENDING,
        FINALIZATION_PENDING,
        OUTBOX_APPEND,
        COMMIT,
        BUSINESS_VALIDATION
    }
}
