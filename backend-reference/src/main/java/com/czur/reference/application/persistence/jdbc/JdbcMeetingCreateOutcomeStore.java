package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.MeetingCreateOutcome;
import com.czur.reference.application.port.MeetingCreateOutcomeStore;
import com.czur.reference.application.port.MeetingCreateOutcomeStore.StoreErrorCode;
import com.czur.reference.application.port.MeetingCreateOutcomeStore.StoreException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 在既有 {@code idempotency_record} 中保存 authenticated-create 终态的 JDBC 适配器。
 *
 * <p>成功 Outcome 沿用 HTTP-like {@code response_status=200} 和历史 v1/v2 正文；
 * 确定失败使用 {@code response_status=409} 和严格 v3 正文。读取时必须同时校验
 * status 与正文类型，任何缺失、损坏或不一致都以 SQLState {@code 22000} fail
 * closed，绝不能把损坏行当成可重试的新请求。</p>
 *
 * <p>该类只复用表，不改变旧 {@link JdbcIdempotencyStore}。公开方法各自管理短
 * 事务，包内静态重载供 owned 创建工作单元加入它自己的单连接事务。日志只输出
 * 固定结果类别，不输出 scope、key、digest、主体或 Outcome 正文。</p>
 */
public final class JdbcMeetingCreateOutcomeStore implements MeetingCreateOutcomeStore {
    static final int SUCCESS_STATUS = 200;
    static final int FAILURE_STATUS = 409;

    private static final Logger LOGGER = Logger.getLogger(
            JdbcMeetingCreateOutcomeStore.class.getName());
    private static final String FIND_SQL = """
            SELECT subject_scope, idempotency_key, request_hash,
                   response_status, response_body
              FROM idempotency_record
             WHERE subject_scope = ? AND idempotency_key = ?
            """;
    private static final String INSERT_SQL = """
            INSERT INTO idempotency_record (
                subject_scope, idempotency_key, request_hash,
                response_status, response_body, created_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (subject_scope, idempotency_key) DO NOTHING
            """;

    private final DataSource dataSource;
    private final Clock clock;

    /** 使用 UTC 系统时钟创建独立短事务适配器。 */
    public JdbcMeetingCreateOutcomeStore(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    /** 注入时钟便于部署装配和确定性测试；时间会截断为 PostgreSQL 微秒精度。 */
    public JdbcMeetingCreateOutcomeStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<OutcomeRecord> find(String scope, String key) {
        // 调用契约错误应直接暴露为 IAE，不能伪装成可重试数据库故障。
        requireText("scope", scope);
        requireText("key", key);
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            return find(connection, scope, key);
        } catch (SQLException failure) {
            throw new StoreException(storeError(failure));
        } catch (RuntimeException failure) {
            throw new StoreException(StoreErrorCode.STORAGE_UNAVAILABLE);
        } finally {
            closeQuietly(connection);
        }
    }

    @Override
    public Reservation reserve(OutcomeRecord candidate) {
        Objects.requireNonNull(candidate, "candidate");
        Connection connection = null;
        boolean transactionStarted = false;
        boolean commitStarted = false;
        boolean commitCompleted = false;
        boolean previousAutoCommit = true;
        TransactionResolution transactionResolution = new TransactionResolution();
        try {
            Instant createdAt = Objects.requireNonNull(clock.instant(), "clock.instant()")
                    .truncatedTo(ChronoUnit.MICROS);
            connection = dataSource.getConnection();
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            transactionStarted = true;
            Reservation result = reserve(connection, candidate, createdAt);
            commitStarted = true;
            connection.commit();
            commitCompleted = true;
            return result;
        } catch (SQLException | RuntimeException failure) {
            if (commitStarted) {
                throw new StoreException(StoreErrorCode.OUTCOME_UNKNOWN);
            }
            if (transactionStarted) {
                transactionResolution.rollback(connection);
            }
            StoreErrorCode code = failure instanceof SQLException sqlFailure
                    ? storeError(sqlFailure) : StoreErrorCode.STORAGE_UNAVAILABLE;
            throw new StoreException(code);
        } finally {
            // rollback 未获数据库确认时，setAutoCommit(true) 可能隐式提交未决事务。
            // 此时只能关闭并丢弃连接；该规则与 owned 创建工作单元保持一致。
            if (commitCompleted || transactionResolution.rollbackConfirmed()) {
                restoreAutoCommitQuietly(connection, previousAutoCommit);
            }
            closeQuietly(connection);
        }
    }

    /** 在调用方事务连接中读取终态；不提交、回滚或关闭连接。 */
    static Optional<OutcomeRecord> find(Connection connection, String scope, String key)
            throws SQLException {
        Optional<RawOutcomeRow> raw = findRaw(connection, scope, key);
        return raw.isEmpty() ? Optional.empty() : Optional.of(decodeRaw(raw.get()));
    }

    /**
     * 在调用方连接中读取尚未解析的终态行。
     *
     * <p>该方法只验证 scope、key 和摘要可以安全参与比较，不解释 status/body。
     * typed replay lookup 因而可以先用常量时间比较摘要，并在冲突时不触碰可能
     * 损坏的正文。返回对象的 {@code toString()} 不会暴露任何原始字段。</p>
     */
    static Optional<RawOutcomeRow> findRaw(Connection connection, String scope, String key)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        requireText("scope", scope);
        requireText("key", key);
        try (PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
            statement.setString(1, scope);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readRawRow(rows)) : Optional.empty();
            }
        }
    }

    /**
     * 在调用方事务内原子争抢终态；{@code createdAt} 必须与该事务其它事实使用
     * 同一次时钟值。该方法不提交、回滚或关闭连接。
     */
    static Reservation reserve(
            Connection connection,
            OutcomeRecord candidate,
            Instant createdAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(createdAt, "createdAt");
        int inserted;
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            bindCandidate(statement, candidate, createdAt);
            inserted = statement.executeUpdate();
        }
        OutcomeRecord current = find(connection, candidate.scope(), candidate.key())
                .orElseThrow(() -> corruptFact("插入后终态不可见"));
        if (inserted == 1) {
            LOGGER.log(Level.FINE, "JDBC 事务内会议创建终态争抢成功 result=ACQUIRED");
            return new Reservation(ReservationStatus.ACQUIRED, candidate);
        }
        if (!JdbcReadLookupSupport.sameDigest(
                current.requestDigest(), candidate.requestDigest())) {
            LOGGER.log(Level.FINE, "JDBC 事务内会议创建终态摘要冲突 result=CONFLICT");
            return new Reservation(ReservationStatus.CONFLICT, current);
        }
        LOGGER.log(Level.FINE, "JDBC 事务内会议创建终态稳定重放 result=REPLAY");
        return new Reservation(ReservationStatus.REPLAY, current);
    }

    private static void bindCandidate(
            PreparedStatement statement,
            OutcomeRecord candidate,
            Instant createdAt) throws SQLException {
        statement.setString(1, candidate.scope());
        statement.setString(2, candidate.key());
        statement.setString(3, candidate.requestDigest());
        statement.setInt(4, statusFor(candidate.outcome()));
        statement.setString(5, MeetingCreateOutcomeJson.encode(candidate.outcome()));
        statement.setTimestamp(6, Timestamp.from(createdAt));
    }

    private static RawOutcomeRow readRawRow(ResultSet rows) throws SQLException {
        try {
            int responseStatus = rows.getInt("response_status");
            Integer nullableStatus = rows.wasNull() ? null : responseStatus;
            return new RawOutcomeRow(
                    requireRowText(rows, "subject_scope"),
                    requireRowText(rows, "idempotency_key"),
                    requireRowText(rows, "request_hash"),
                    nullableStatus,
                    rows.getString("response_body"));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw corruptFact("会议创建终态持久事实损坏");
        }
    }

    /** 同摘要后唯一允许使用的 status/body 严格解码入口。 */
    static OutcomeRecord decodeRaw(RawOutcomeRow raw) throws SQLException {
        Objects.requireNonNull(raw, "raw");
        try {
            if (raw.responseStatus() == null) {
                throw corruptFact("终态 status 缺失");
            }
            if (raw.responseBody() == null) {
                throw corruptFact("终态 body 缺失");
            }
            MeetingCreateOutcome outcome = MeetingCreateOutcomeJson.decode(raw.responseBody());
            if (raw.responseStatus() != statusFor(outcome)) {
                throw corruptFact("终态 status 与 body 不一致");
            }
            return new OutcomeRecord(
                    raw.scope(), raw.key(), raw.requestDigest(), outcome);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw corruptFact("会议创建终态持久事实损坏");
        }
    }

    private static int statusFor(MeetingCreateOutcome outcome) {
        return outcome instanceof MeetingCreateOutcome.Success
                ? SUCCESS_STATUS : FAILURE_STATUS;
    }

    private static String requireRowText(ResultSet rows, String column) throws SQLException {
        String value = rows.getString(column);
        if (value == null || value.isBlank()) {
            throw corruptFact("会议创建终态关键字段缺失");
        }
        return value;
    }

    private static SQLException corruptFact(String fixedReason) {
        return new SQLException(fixedReason, "22000");
    }

    private static StoreErrorCode storeError(SQLException failure) {
        return "22000".equals(failure.getSQLState())
                ? StoreErrorCode.CORRUPT_DATA
                : StoreErrorCode.STORAGE_UNAVAILABLE;
    }

    private static boolean rollbackQuietly(Connection connection) {
        if (connection == null) {
            return false;
        }
        try {
            connection.rollback();
            return true;
        } catch (SQLException | RuntimeException ignored) {
            // 保留原始失败；不把驱动动态异常正文加入日志或异常链。
            LOGGER.log(Level.WARNING, "JDBC 会议创建终态事务回滚失败");
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
        } catch (SQLException | RuntimeException ignored) {
            // 短事务连接随 try-with-resources 关闭，不暴露清理异常正文。
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException ignored) {
            LOGGER.log(Level.WARNING, "JDBC 会议创建终态数据库连接关闭失败");
        }
    }

    /** 只记录数据库是否明确确认回滚，决定 finally 能否安全恢复 auto-commit。 */
    private static final class TransactionResolution {
        private boolean rollbackConfirmed;

        private void rollback(Connection connection) {
            rollbackConfirmed = rollbackQuietly(connection);
        }

        private boolean rollbackConfirmed() {
            return rollbackConfirmed;
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /** 尚未解释 status/body 的敏感数据库行；只允许在 JDBC 包内传递。 */
    record RawOutcomeRow(
            String scope,
            String key,
            String requestDigest,
            Integer responseStatus,
            String responseBody) {
        RawOutcomeRow {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(requestDigest, "requestDigest");
        }

        @Override
        public String toString() {
            return "RawOutcomeRow[redacted]";
        }
    }
}
