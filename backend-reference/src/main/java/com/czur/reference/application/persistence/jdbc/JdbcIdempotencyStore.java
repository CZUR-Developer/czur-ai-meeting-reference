package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.port.IdempotencyStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基于 PostgreSQL 唯一键的幂等记录适配器。
 *
 * <p>{@link #reserve(IdempotencyRecord)} 在同一连接事务内执行
 * {@code INSERT ... ON CONFLICT DO NOTHING}，随后读取数据库中最终记录，
 * 因此多个应用实例对同一个范围/键并发请求时只有一个实例获得
 * {@link ReservationStatus#ACQUIRED}。响应正文以固定 JSON 结构保存，不记录
 * 密码、Token、音频或模型响应。</p>
 *
 * <p>该类不创建表；部署方必须先执行 {@code deploy/migrations/V001__initial_schema.sql}。
 * 事务编排（会议快照、幂等和 Outbox 的原子提交）由后续 Unit of Work 适配器负责。</p>
 */
public final class JdbcIdempotencyStore implements IdempotencyStore {
    private static final Logger LOGGER = Logger.getLogger(JdbcIdempotencyStore.class.getName());

    private static final String FIND_SQL = """
            SELECT subject_scope, idempotency_key, request_hash,
                   response_status, response_body, created_at
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

    private static final String DELETE_SQL = """
            DELETE FROM idempotency_record
             WHERE subject_scope = ? AND idempotency_key = ? AND request_hash = ?
            """;

    private final DataSource dataSource;

    public JdbcIdempotencyStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<IdempotencyRecord> find(String scope, String key) {
        requireText("scope", scope);
        requireText("key", key);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
            statement.setString(1, scope);
            statement.setString(2, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readRecord(resultSet)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw fail("查询幂等记录失败 scope=" + scope, failure);
        }
    }

    @Override
    public void save(IdempotencyRecord record) {
        Objects.requireNonNull(record, "record");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            bindCandidate(statement, record);
            statement.executeUpdate();
            LOGGER.log(Level.FINE, "JDBC 幂等记录已保存 scope={0}", record.scope());
        } catch (SQLException failure) {
            throw fail("保存幂等记录失败 scope=" + record.scope(), failure);
        }
    }

    @Override
    public Reservation reserve(IdempotencyRecord candidate) {
        Objects.requireNonNull(candidate, "candidate");
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int inserted;
                try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                    bindCandidate(statement, candidate);
                    inserted = statement.executeUpdate();
                }
                IdempotencyRecord current = find(connection, candidate.scope(), candidate.key())
                        .orElseThrow(() -> new IllegalStateException("幂等记录插入后不可见"));
                connection.commit();
                if (inserted == 1) {
                    LOGGER.log(Level.FINE, "JDBC 幂等预占成功 scope={0}", candidate.scope());
                    return new Reservation(ReservationStatus.ACQUIRED, candidate);
                }
                if (!current.requestDigest().equals(candidate.requestDigest())) {
                    LOGGER.log(Level.WARNING, "JDBC 幂等键摘要冲突 scope={0}", candidate.scope());
                    return new Reservation(ReservationStatus.CONFLICT, current);
                }
                LOGGER.log(Level.INFO, "JDBC 幂等请求重放 scope={0}", candidate.scope());
                return new Reservation(ReservationStatus.REPLAY, current);
            } catch (RuntimeException | SQLException failure) {
                rollbackQuietly(connection);
                throw failure instanceof SQLException
                        ? fail("登记幂等记录失败 scope=" + candidate.scope(), (SQLException) failure)
                        : (RuntimeException) failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException failure) {
            throw fail("创建幂等数据库连接失败", failure);
        }
    }

    @Override
    public void release(IdempotencyRecord candidate) {
        Objects.requireNonNull(candidate, "candidate");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_SQL)) {
            statement.setString(1, candidate.scope());
            statement.setString(2, candidate.key());
            statement.setString(3, candidate.requestDigest());
            statement.executeUpdate();
            LOGGER.log(Level.FINE, "JDBC 幂等预占已释放 scope={0}", candidate.scope());
        } catch (SQLException failure) {
            throw fail("释放幂等记录失败 scope=" + candidate.scope(), failure);
        }
    }

    private static void bindCandidate(PreparedStatement statement, IdempotencyRecord candidate)
            throws SQLException {
        statement.setString(1, candidate.scope());
        statement.setString(2, candidate.key());
        statement.setString(3, candidate.requestDigest());
        statement.setInt(4, 200);
        statement.setString(5, IdempotencyResultJson.encode(candidate.result()));
        statement.setTimestamp(6, Timestamp.from(Instant.now()));
    }

    private static Optional<IdempotencyRecord> find(Connection connection, String scope, String key)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
            statement.setString(1, scope);
            statement.setString(2, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readRecord(resultSet)) : Optional.empty();
            }
        }
    }

    private static IdempotencyRecord readRecord(ResultSet resultSet) throws SQLException {
        String responseBody = resultSet.getString("response_body");
        if (responseBody == null) {
            throw new SQLException("idempotency_record.response_body 不能为空");
        }
        MeetingCommandResult result = IdempotencyResultJson.decode(responseBody);
        return new IdempotencyRecord(
                resultSet.getString("subject_scope"),
                resultSet.getString("idempotency_key"),
                resultSet.getString("request_hash"),
                result);
    }

    private static RuntimeException fail(String message, SQLException cause) {
        return new IllegalStateException(message, cause);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 保留原始异常，回滚失败只作为连接清理问题处理。
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
