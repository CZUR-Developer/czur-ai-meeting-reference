package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandEventFactory;
import com.czur.reference.application.port.CompleteMeetingCreationUnitOfWork;
import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.OutboxStore;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.meeting.MeetingAggregate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * V013 完整 Create 事实、Meeting、Reservation 和 Outbox 的单连接事务实现。
 *
 * <p>此类不被当前 HTTP Create 路由调用。数据库 advisory transaction lock 只把
 * 同一服务端 scope/key 的本工作单元串行化；V013 唯一约束仍是最终防线。锁后先
 * 仅读摘要，不同摘要不解析旧响应；同摘要严格读取保存的原响应并立即重放。
 * 首次路径复核首次 reserve 持久结果和锁定的 Reservation，之后一次读取微秒
 * 时钟，按 bind、Meeting、V013、Outbox 顺序写入并提交。V013 的 meeting_id
 * 外键立即生效，所以不能把 V013 插入提前到 Meeting 之前。</p>
 *
 * <p>日志只记录固定阶段、结果分类及 SQLState，不输出业务标识、请求 JSON、
 * 主体、幂等键、实时令牌或驱动动态异常消息。提交调用一旦开始，故障只能标为
 * OUTCOME_UNKNOWN；上层须先查询 V013 再决定是否重试或取消权益。</p>
 */
public final class JdbcCompleteMeetingCreationUnitOfWork
        implements CompleteMeetingCreationUnitOfWork {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcCompleteMeetingCreationUnitOfWork.class.getName());
    private final DataSource dataSource;
    private final Clock clock;
    private final JdbcDatabaseEngine engine;

    public JdbcCompleteMeetingCreationUnitOfWork(DataSource dataSource, Clock clock) {
        this(dataSource, JdbcDatabaseEngine.MYSQL, clock);
    }

    public JdbcCompleteMeetingCreationUnitOfWork(JdbcDatabaseContext database, Clock clock) {
        this(database.dataSource(), database.engine(), clock);
    }

    private JdbcCompleteMeetingCreationUnitOfWork(DataSource dataSource, JdbcDatabaseEngine engine, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** 仅提交已经组装完成的确定性事实；不调用任何外部 Provider。 */
    @Override
    public Result create(CreateRequest request) {
        Objects.requireNonNull(request, "request");
        String scope = IdempotencyScopeCodec.meetingCreateV2(
                request.deploymentScope(), request.subjectScope());
        String key = request.requestFact().requestSnapshot().idempotencyKey();
        Connection connection = null;
        boolean previousAutoCommit = true;
        boolean transactionStarted = false;
        boolean commitStarted = false;
        boolean commitCompleted = false;
        boolean rollbackConfirmed = false;
        Phase phase = Phase.OPEN_CONNECTION;
        try {
            connection = dataSource.getConnection();
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            transactionStarted = true;

            phase = Phase.KEY_LOCK;
            JdbcIdempotencyLock.acquire(connection, engine, scope, key);
            JdbcCreateMeetingFactStore facts = new JdbcCreateMeetingFactStore(connection, engine);
            phase = Phase.FACT_PROBE;
            Result previous = replayIfPresent(facts, scope, key, request);
            if (previous != null) {
                rollbackConfirmed = rollbackQuietly(connection);
                return previous;
            }

            phase = Phase.RESERVATION_LOCK;
            UsageReservation supplied = request.reserveResult().reservation();
            Optional<JdbcUsageReservationStore.LockedReservation> lockedOptional =
                    JdbcUsageReservationStore.lockReservationForOwnedCreation(
                            connection, engine, request.deploymentScope(), request.subjectScope(),
                            supplied.reservationId());
            if (lockedOptional.isEmpty()) {
                rollbackConfirmed = rollbackQuietly(connection);
                return failure(ErrorCode.RESERVATION_NOT_FOUND, false);
            }

            // Reservation 行锁等待期间，绕开本工作单元的旧/测试调用者也可能
            // 写入同一 V013 键；再次探查并依赖唯一约束兜底。
            phase = Phase.FACT_PROBE;
            previous = replayIfPresent(facts, scope, key, request);
            if (previous != null) {
                rollbackConfirmed = rollbackQuietly(connection);
                return previous;
            }

            phase = Phase.RESERVE_RESULT_READ;
            JdbcUsageReservationStore.LockedReservation locked = lockedOptional.get();
            Failure validation = validateStableReserve(connection, engine, request, locked.reservation());
            if (validation != null) {
                rollbackConfirmed = rollbackQuietly(connection);
                return validation;
            }

            phase = Phase.CLOCK_READ;
            Instant at = Objects.requireNonNull(clock.instant(), "clock.instant()")
                    .truncatedTo(ChronoUnit.MICROS);
            UsageReservation current = locked.reservation();
            if (at.isBefore(current.createdAt())) {
                rollbackConfirmed = rollbackQuietly(connection);
                return failure(ErrorCode.INTERNAL, false);
            }
            if (current.isExpiredAt(at) || !at.isBefore(
                    request.reserveResult().grantExpiresAt().truncatedTo(ChronoUnit.MICROS))) {
                rollbackConfirmed = rollbackQuietly(connection);
                return failure(ErrorCode.RESERVATION_EXPIRED, false);
            }
            if (locked.hasActiveLeaseAt(at)) {
                rollbackConfirmed = rollbackQuietly(connection);
                return failure(ErrorCode.RESERVATION_STATE_CONFLICT, true);
            }

            AuthorizedProfileSnapshot profile =
                    request.reserveResult().requireAuthorizedProfileSnapshot();
            MeetingAggregate.MeetingSnapshot meeting = MeetingAggregate.owned(
                    request.responseFact().meetingId(), request.deploymentScope(),
                    request.requestFact().requestSnapshot().clientSessionId(),
                    request.subjectScope(), current.grantId(),
                    request.reserveResult().grantExpiresAt(), current.reservationId(),
                    profile.profileId(), profile.profileRevision(), at).snapshot();

            // MySQL 的双向外键即时检查：先插入引用已存在预占的 Meeting，再条件绑定。
            // PG 保留原延迟外键路径的 bind -> Meeting 顺序，不复制业务状态机。
            if (engine == JdbcDatabaseEngine.MYSQL) {
                phase = Phase.MEETING_INSERT;
                JdbcMeetingRepository.insertOwned(connection, engine, meeting);
            }
            phase = Phase.RESERVATION_BIND;
            if (!JdbcUsageReservationStore.bindForOwnedMeeting(connection, engine, current, at)) {
                rollbackConfirmed = rollbackQuietly(connection);
                return failure(ErrorCode.INTERNAL, false);
            }
            phase = Phase.FACT_INSERT;
            CreateMeetingFactStore.PersistResult persisted = facts.persist(
                    new CreateMeetingFactStore.Record(scope, key,
                            request.requestFact(), request.responseFact(), at));
            if (persisted.status() != CreateMeetingFactStore.PersistStatus.APPLIED) {
                // 无论赢家为何，候选 Meeting/Reservation 都必须整体回滚。
                Result winner = sameDigest(persisted.record().request()
                        .canonicalRequestDigest(), request.requestFact()
                        .canonicalRequestDigest())
                        ? new Success(SuccessCode.REPLAYED, persisted.record().response())
                        : failure(ErrorCode.IDEMPOTENCY_CONFLICT, false);
                rollbackConfirmed = rollbackQuietly(connection);
                return winner;
            }
            phase = Phase.OUTBOX_APPEND;
            OutboxStore.AppendResult append = JdbcOutboxStore.append(
                    connection, engine, MeetingCommandEventFactory.meetingCreated(meeting, at));
            if (append.status() != OutboxStore.AppendStatus.APPENDED) {
                rollbackConfirmed = rollbackQuietly(connection);
                return failure(ErrorCode.INTERNAL, false);
            }

            phase = Phase.COMMIT;
            commitStarted = true;
            connection.commit();
            commitCompleted = true;
            LOGGER.log(Level.FINE, "完整 Create 事务已提交 result=CREATED");
            return new Success(SuccessCode.CREATED, persisted.record().response());
        } catch (JdbcCreateMeetingFactStore.StoredFactException failure) {
            if (transactionStarted && !commitStarted) {
                rollbackConfirmed = rollbackQuietly(connection);
            }
            ErrorCode code = failure.kind() == JdbcCreateMeetingFactStore.FailureKind.CORRUPT_DATA
                    ? ErrorCode.INTERNAL : ErrorCode.STORAGE_UNAVAILABLE;
            return loggedFailure(code, code == ErrorCode.STORAGE_UNAVAILABLE, phase, null);
        } catch (JdbcUsageReservationStore.CorruptUsageDataException failure) {
            if (transactionStarted && !commitStarted) {
                rollbackConfirmed = rollbackQuietly(connection);
            }
            return loggedFailure(ErrorCode.INTERNAL, false, phase, null);
        } catch (SQLException failure) {
            if (commitStarted) {
                return loggedFailure(ErrorCode.OUTCOME_UNKNOWN, true, Phase.COMMIT,
                        failure.getSQLState());
            }
            if (transactionStarted) {
                rollbackConfirmed = rollbackQuietly(connection);
            }
            if (phase == Phase.MEETING_INSERT && JdbcSqlErrors.isDuplicate(engine, failure)) {
                return loggedFailure(ErrorCode.MEETING_CONFLICT, false, phase,
                        failure.getSQLState());
            }
            ErrorCode code = (JdbcSqlErrors.isTransientTransaction(engine, failure)
                    || isTemporaryStorageFailure(failure))
                    ? ErrorCode.STORAGE_UNAVAILABLE : ErrorCode.INTERNAL;
            return loggedFailure(code, code == ErrorCode.STORAGE_UNAVAILABLE,
                    phase, failure.getSQLState());
        } catch (RuntimeException failure) {
            if (commitStarted) {
                return loggedFailure(ErrorCode.OUTCOME_UNKNOWN, true, Phase.COMMIT, null);
            }
            if (transactionStarted) {
                rollbackConfirmed = rollbackQuietly(connection);
            }
            return loggedFailure(ErrorCode.INTERNAL, false, phase, null);
        } finally {
            if (commitCompleted || rollbackConfirmed) {
                restoreAutoCommitQuietly(connection, previousAutoCommit);
            }
            closeQuietly(connection);
        }
    }

    private static Result replayIfPresent(
            JdbcCreateMeetingFactStore facts, String scope, String key, CreateRequest request) {
        Optional<String> digest = facts.probeRequestDigest(scope, key);
        if (digest.isEmpty()) {
            return null;
        }
        if (!sameDigest(digest.get(), request.requestFact().canonicalRequestDigest())) {
            return failure(ErrorCode.IDEMPOTENCY_CONFLICT, false);
        }
        CreateMeetingFactStore.Record stored = facts.find(scope, key).orElseThrow(
                () -> new IllegalStateException("Create fact disappeared after digest probe"));
        if (!sameDigest(stored.request().canonicalRequestDigest(),
                request.requestFact().canonicalRequestDigest())) {
            return failure(ErrorCode.IDEMPOTENCY_CONFLICT, false);
        }
        return new Success(SuccessCode.REPLAYED, stored.response());
    }

    private static Failure validateStableReserve(
            Connection connection, JdbcDatabaseEngine engine, CreateRequest request, UsageReservation current)
            throws SQLException {
        UsageOperationResult supplied = request.reserveResult();
        Optional<JdbcUsageReservationStore.UsageOperationRow> rowOptional =
                JdbcUsageReservationStore.findOperationCurrent(connection, engine,
                        supplied.deploymentScope(), supplied.subjectScope(),
                        supplied.operationScope(), supplied.idempotencyKey());
        if (rowOptional.isEmpty()) {
            return failure(ErrorCode.INTERNAL, false);
        }
        JdbcUsageReservationStore.UsageOperationRow row = rowOptional.get();
        if (!sameDigest(row.requestDigest(), supplied.requestDigest())) {
            return failure(ErrorCode.RESERVATION_STATE_CONFLICT, false);
        }
        if (!row.reservationId().equals(supplied.reservation().reservationId())
                || !row.reservationId().equals(current.reservationId())) {
            return failure(ErrorCode.INTERNAL, false);
        }
        final UsageOperationResult stored;
        try {
            stored = UsageOperationResultJson.decode(row.resultBody(),
                    supplied.deploymentScope(), supplied.subjectScope(),
                    supplied.operationScope(), supplied.idempotencyKey(), supplied.requestDigest());
        } catch (IllegalArgumentException failure) {
            throw new JdbcUsageReservationStore.CorruptUsageDataException(
                    "stored reserve result is invalid");
        }
        if (!stored.supportsOwnedMeetingCreation()) {
            return failure(ErrorCode.INTERNAL, false);
        }
        if (!stored.equals(supplied)) {
            return failure(ErrorCode.RESERVATION_STATE_CONFLICT, false);
        }
        AuthorizedProfileSnapshot profile = stored.requireAuthorizedProfileSnapshot();
        if (!profile.grantedCapabilities().containsAll(profile.requestedCapabilities())
                || current.reservedUsage().unit() != UsageUnit.MEETING_DURATION_MS
                || current.reservedUsage().amount() > profile.maxDurationMs()) {
            return failure(ErrorCode.INTERNAL, false);
        }
        if (current.state() != UsageReservation.State.RESERVED || current.isBound()) {
            return failure(ErrorCode.RESERVATION_STATE_CONFLICT, false);
        }
        if (current.version() != stored.reservation().version()) {
            return failure(ErrorCode.VERSION_CONFLICT, false);
        }
        if (!OwnedMeetingCreationFacts.sameInitialReservationFacts(
                current, stored.reservation())) {
            return failure(ErrorCode.INTERNAL, false);
        }
        return null;
    }

    private static boolean sameDigest(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static Failure failure(ErrorCode code, boolean retryable) {
        return new Failure(code, retryable);
    }

    private static Failure loggedFailure(
            ErrorCode code, boolean retryable, Phase phase, String sqlState) {
        LOGGER.log(retryable ? Level.WARNING : Level.FINE,
                "完整 Create 事务失败 phase={0} code={1} sqlState={2}",
                new Object[]{phase.name(), code.name(), safeSqlState(sqlState)});
        return failure(code, retryable);
    }

    private static boolean isTemporaryStorageFailure(SQLException failure) {
        if (failure instanceof SQLTransientException || failure instanceof SQLRecoverableException) {
            return true;
        }
        String state = failure.getSQLState();
        if (state == null || state.length() < 2) {
            return true;
        }
        return switch (state.substring(0, 2)) {
            case "08", "40", "53", "54", "55", "57", "58" -> true;
            default -> false;
        };
    }

    private static String safeSqlState(String state) {
        return state != null && state.matches("[A-Z0-9]{5}") ? state : "UNKNOWN";
    }

    private static boolean rollbackQuietly(Connection connection) {
        if (connection == null) {
            return false;
        }
        try {
            connection.rollback();
            return true;
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "完整 Create 事务回滚失败");
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
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "完整 Create 事务恢复连接状态失败");
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "完整 Create 事务关闭连接失败");
        }
    }

    private enum Phase {
        OPEN_CONNECTION, KEY_LOCK, FACT_PROBE, RESERVATION_LOCK,
        RESERVE_RESULT_READ, CLOCK_READ, RESERVATION_BIND,
        MEETING_INSERT, FACT_INSERT, OUTBOX_APPEND, COMMIT
    }
}
