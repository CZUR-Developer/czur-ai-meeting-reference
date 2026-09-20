package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.FinalizationReceiptGenerator;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.MeetingFinalizationStore;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingState;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PostgreSQL {@code meeting} 表上的会议级最终收口事实适配器。
 *
 * <p>该实现要求 V005。{@code meeting.input_state} 与
 * {@code finalization_receipt_id} 是公开 Stop/查询使用的会议级权威事实；
 * {@code meeting_stream.input_state} 仍属于实时逻辑流恢复范围，不能用多条 stream
 * 记录临时聚合出 receipt。</p>
 *
 * <p>首次输入接管在会议行锁内完成：确认 pending、只调用一次候选生成器、通过
 * 领域聚合把 Meeting 从 STOPPING 推进到 FINALIZING，再在同一事务中保存状态与
 * receipt。并发或重启后的重跑会先看到 accepted 并原样返回已保存值。日志、
 *异常和 {@code toString()} 只暴露状态，不输出 receipt 正文。</p>
 */
public final class JdbcMeetingFinalizationStore implements MeetingFinalizationStore {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcMeetingFinalizationStore.class.getName());

    private static final String FIND_SQL = """
            SELECT state, input_state, finalization_receipt_id
              FROM meeting
             WHERE meeting_id = ?
            """;

    private static final String MARK_PENDING_SQL = """
            UPDATE meeting
               SET input_state = 'INPUT_COMPLETE_PENDING'
             WHERE meeting_id = ?
               AND input_state = 'INPUT_OPEN'
               AND finalization_receipt_id IS NULL
            """;

    private static final String MARK_ACCEPTED_SQL = """
            UPDATE meeting
               SET input_state = 'INPUT_ACCEPTED', finalization_receipt_id = ?
             WHERE meeting_id = ?
               AND state = 'FINALIZING'
               AND input_state = 'INPUT_COMPLETE_PENDING'
               AND finalization_receipt_id IS NULL
            """;

    private final DataSource dataSource;
    private final FinalizationReceiptGenerator receiptGenerator;

    public JdbcMeetingFinalizationStore(
            DataSource dataSource,
            FinalizationReceiptGenerator receiptGenerator) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.receiptGenerator = Objects.requireNonNull(receiptGenerator, "receiptGenerator");
    }

    @Override
    public Result find(String meetingId) {
        MeetingFinalizationStore.requireMeetingId(meetingId);
        try (Connection connection = dataSource.getConnection()) {
            return readRow(connection, meetingId)
                    .<Result>map(row -> isConsistent(row)
                            ? new Found(row.fact())
                            : new Conflict(ConflictReason.FACT_INCONSISTENT))
                    .orElseGet(NotFound::new);
        } catch (SQLException failure) {
            throw databaseFailure("读取会议最终收口事实失败", failure);
        } catch (IllegalArgumentException invalidFact) {
            throw new IllegalStateException("会议最终收口事实不一致");
        } catch (RuntimeException runtimeFailure) {
            // DataSource、连接池和 JDBC 代理也可能用 RuntimeException 报告故障。
            // 禁止把其中可能携带的数据库地址、Token 或动态正文带出适配器边界。
            throw runtimeFailure("读取会议最终收口事实失败");
        }
    }

    @Override
    public Result requestInputCompletion(String meetingId) {
        MeetingFinalizationStore.requireMeetingId(meetingId);
        return inTransaction(connection -> requestInputCompletion(connection, meetingId),
                "记录输入完成意图失败");
    }

    @Override
    public Result acceptInput(String meetingId, Instant acceptedAt) {
        MeetingFinalizationStore.requireMeetingId(meetingId);
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Instant canonicalTime = acceptedAt.truncatedTo(ChronoUnit.MICROS);
        return inTransaction(
                connection -> acceptInput(connection, meetingId, canonicalTime, receiptGenerator),
                "提交输入接管事实失败");
    }

    /**
     * 在 Stop 已经持有会议行锁的事务中记录 pending；不提交、回滚或关闭连接。
     *
     * <p>调用位置必须晚于 Meeting 的 STOPPING 更新，早于 Stop Outbox 和事务提交。
     * 这样停止幂等结果、生命周期、pending 和 Outbox 形成一个原子事实集合。</p>
     */
    static Result requestInputCompletion(Connection connection, String meetingId)
            throws SQLException {
        requireTransactionalConnection(connection);
        MeetingFinalizationStore.requireMeetingId(meetingId);
        // 公共入口也必须获得与 Stop/Worker 相同的会议行锁。只依赖 UPDATE 的受影响
        // 行数会让并发第二个调用误报冲突，而不是稳定重放第一个 pending 事实。
        if (JdbcMeetingRepository.findVersionedForUpdate(connection, meetingId).isEmpty()) {
            return new NotFound();
        }
        Optional<Row> rowOptional = readRow(connection, meetingId);
        Row row = rowOptional.orElseThrow(
                () -> new IllegalStateException("锁定后的会议事实不可见"));
        if (row.fact().inputState() == InputState.INPUT_ACCEPTED) {
            return isConsistent(row)
                    ? new Replayed(row.fact())
                    : new Conflict(ConflictReason.FACT_INCONSISTENT);
        }
        if (row.fact().inputState() == InputState.INPUT_COMPLETE_PENDING) {
            return row.state() == MeetingState.STOPPING
                    ? new Replayed(row.fact())
                    : new Conflict(ConflictReason.FACT_INCONSISTENT);
        }
        if (row.fact().inputState() == InputState.INPUT_CANCELLED) {
            return new Conflict(ConflictReason.INVALID_INPUT_STATE);
        }
        if (row.state() != MeetingState.STOPPING) {
            return new Conflict(ConflictReason.INVALID_MEETING_STATE);
        }
        try (PreparedStatement statement = connection.prepareStatement(MARK_PENDING_SQL)) {
            statement.setString(1, meetingId);
            if (statement.executeUpdate() != 1) {
                return new Conflict(ConflictReason.FACT_INCONSISTENT);
            }
        }
        FinalizationFact pending = FinalizationFact.pending(
                InputState.INPUT_COMPLETE_PENDING);
        LOGGER.log(Level.FINE,
                "会议输入完成意图已持久化 meetingId={0} inputState={1}",
                new Object[]{meetingId, pending.inputState()});
        return new Applied(pending);
    }

    /**
     * 在调用方事务中完成 pending 到 accepted 的唯一跃迁。
     *
     * <p>方法自行锁定会议行，但不管理事务生命周期。receipt 生成器只会在已证明
     * 当前为 STOPPING + pending 后调用；accepted 重跑永远读取数据库旧值。</p>
     */
    static Result acceptInput(
            Connection connection,
            String meetingId,
            Instant acceptedAt,
            FinalizationReceiptGenerator receiptGenerator) throws SQLException {
        requireTransactionalConnection(connection);
        MeetingFinalizationStore.requireMeetingId(meetingId);
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(receiptGenerator, "receiptGenerator");

        Optional<JdbcMeetingRepository.VersionedMeeting> lockedOptional =
                JdbcMeetingRepository.findVersionedForUpdate(connection, meetingId);
        if (lockedOptional.isEmpty()) {
            return new NotFound();
        }
        JdbcMeetingRepository.VersionedMeeting locked = lockedOptional.get();
        MeetingAggregate.MeetingSnapshot current = locked.snapshot();
        Row row = readRow(connection, meetingId)
                .orElseThrow(() -> new IllegalStateException("锁定后的会议事实不可见"));

        if (row.fact().inputState() == InputState.INPUT_ACCEPTED) {
            return isConsistent(row)
                    ? new Replayed(row.fact())
                    : new Conflict(ConflictReason.FACT_INCONSISTENT);
        }
        if (row.fact().inputState() != InputState.INPUT_COMPLETE_PENDING) {
            return new Conflict(ConflictReason.INVALID_INPUT_STATE);
        }
        if (current.state() != MeetingState.STOPPING) {
            return new Conflict(ConflictReason.INVALID_MEETING_STATE);
        }
        if (acceptedAt.isBefore(current.updatedAt())) {
            return new Conflict(ConflictReason.FACT_TIME_BEFORE_CURRENT);
        }

        String receipt;
        try {
            receipt = requireReceipt(receiptGenerator.newReceipt());
        } catch (RuntimeException generationFailure) {
            // 生成器可能来自部署装配；禁止把其动态正文或候选 receipt 挂到异常链。
            throw new IllegalStateException("finalization receipt generation failed");
        }

        MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(current);
        aggregate.apply(MeetingEvent.INPUT_ACCEPTED, acceptedAt);
        JdbcMeetingRepository.updateOwnedExisting(connection, locked, aggregate.snapshot());
        try (PreparedStatement statement = connection.prepareStatement(MARK_ACCEPTED_SQL)) {
            statement.setString(1, receipt);
            statement.setString(2, meetingId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("会议输入接管事实并发冲突");
            }
        }
        FinalizationFact accepted = FinalizationFact.accepted(receipt);
        LOGGER.log(Level.INFO,
                "会议输入已由后端接管 meetingId={0} inputState={1} receiptPresent=true",
                new Object[]{meetingId, accepted.inputState()});
        return new Applied(accepted);
    }

    private Result inTransaction(TransactionWork work, String failureMessage) {
        Connection connection = null;
        boolean previousAutoCommit = true;
        boolean transactionStarted = false;
        boolean commitStarted = false;
        boolean commitCompleted = false;
        boolean rollbackConfirmed = true;
        try {
            connection = dataSource.getConnection();
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            transactionStarted = true;
            Result result = work.execute(connection);
            commitStarted = true;
            connection.commit();
            commitCompleted = true;
            transactionStarted = false;
            return result;
        } catch (RuntimeException failure) {
            if (commitStarted && !commitCompleted) {
                rollbackConfirmed = false;
                throw outcomeUnknown(failureMessage, failure);
            }
            if (transactionStarted) {
                rollbackConfirmed = rollbackQuietly(connection, failure);
            }
            throw runtimeFailure(failureMessage);
        } catch (Error failure) {
            // Error 交给进程级策略处理，但仍先尽力回滚并确保未确认连接被废弃。
            if (commitStarted && !commitCompleted) {
                rollbackConfirmed = false;
            } else if (transactionStarted) {
                rollbackConfirmed = rollbackQuietly(connection, failure);
            }
            throw failure;
        } catch (SQLException failure) {
            if (commitStarted && !commitCompleted) {
                rollbackConfirmed = false;
                throw outcomeUnknown(failureMessage, failure);
            }
            if (transactionStarted) {
                rollbackConfirmed = rollbackQuietly(connection, failure);
            }
            throw databaseFailure(failureMessage, failure);
        } finally {
            finishConnection(connection, previousAutoCommit, rollbackConfirmed);
            closeQuietly(connection);
        }
    }

    private static Optional<Row> readRow(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                MeetingState state = MeetingState.valueOf(requiredColumn(rows, "state"));
                InputState inputState = InputState.valueOf(requiredColumn(rows, "input_state"));
                String receipt = rows.getString("finalization_receipt_id");
                return Optional.of(new Row(
                        state, new FinalizationFact(inputState, receipt)));
            }
        }
    }

    private static String requiredColumn(ResultSet rows, String name) throws SQLException {
        String value = rows.getString(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("会议最终收口列为空：" + name);
        }
        return value;
    }

    private static String requireReceipt(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("generated receipt must not be blank");
        }
        return value;
    }

    private static boolean isConsistent(Row row) {
        return switch (row.fact().inputState()) {
            case INPUT_OPEN -> switch (row.state()) {
                case CREATED, AUTHORIZED, STARTING, RUNNING -> true;
                default -> false;
            };
            case INPUT_COMPLETE_PENDING -> row.state() == MeetingState.STOPPING;
            case INPUT_ACCEPTED -> switch (row.state()) {
                case FINALIZING, READY, READY_WITH_WARNINGS, FAILED,
                        DELETION_PENDING, DELETED -> true;
                default -> false;
            };
            case INPUT_CANCELLED -> switch (row.state()) {
                case CANCELLED, DELETION_PENDING, DELETED -> true;
                default -> false;
            };
        };
    }

    private static void requireTransactionalConnection(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (connection.getAutoCommit()) {
            throw new IllegalArgumentException(
                    "connection-scoped finalization operation requires autoCommit=false");
        }
    }

    private static RuntimeException databaseFailure(String message, SQLException cause) {
        return new IllegalStateException(message + " sqlState=" + safeSqlState(cause));
    }

    private static RuntimeException runtimeFailure(String message) {
        return new IllegalStateException(message + " runtimeFailure=true");
    }

    private static RuntimeException outcomeUnknown(String message, Throwable failure) {
        // commit 调用一旦开始，驱动抛错不能证明事务未提交。禁止 rollback 或恢复
        // autoCommit；上层只能重新读取 meeting-level fact 判定最终结果。
        return new IllegalStateException(
                message + " outcome=UNKNOWN sqlState=" + safeSqlState(failure));
    }

    private static boolean rollbackQuietly(Connection connection, Throwable originalFailure) {
        if (connection == null) {
            return true;
        }
        try {
            connection.rollback();
            return true;
        } catch (SQLException | RuntimeException rollbackFailure) {
            originalFailure.addSuppressed(new IllegalStateException(
                    "最终收口事务回滚失败 sqlState=" + safeSqlState(rollbackFailure)));
            LOGGER.log(Level.WARNING, "最终收口事务回滚失败 sqlState={0}",
                    safeSqlState(rollbackFailure));
            return false;
        }
    }

    private static void finishConnection(
            Connection connection, boolean previousAutoCommit, boolean rollbackConfirmed) {
        if (connection == null) {
            return;
        }
        if (!rollbackConfirmed) {
            abortQuietly(connection);
            return;
        }
        try {
            if (!connection.isClosed()) {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "恢复最终收口连接状态失败 sqlState={0}",
                    safeSqlState(failure));
        }
    }

    private static void abortQuietly(Connection connection) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "废弃最终收口数据库连接失败 sqlState={0}",
                    safeSqlState(failure));
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "关闭最终收口数据库连接失败 sqlState={0}",
                    safeSqlState(failure));
        }
    }

    private static String safeSqlState(Throwable failure) {
        String state = failure instanceof SQLException sqlFailure
                ? sqlFailure.getSQLState() : null;
        return state != null && state.matches("[0-9A-Z]{5}") ? state : "UNKNOWN";
    }

    private record Row(MeetingState state, FinalizationFact fact) {
        private Row {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(fact, "fact");
        }
    }

    @FunctionalInterface
    private interface TransactionWork {
        Result execute(Connection connection) throws SQLException;
    }
}
