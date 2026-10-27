package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.port.IdempotencyStore;
import com.czur.reference.application.port.MeetingStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基于数据库唯一键的幂等记录适配器。
 *
 * <p>{@link #reserve(IdempotencyRecord)} 在同一连接事务内执行普通 {@code INSERT}：
 * 唯一键重复（MySQL 1062）解释为未取得写入权，随后读取数据库中最终记录，
 * 因此多个应用实例对同一个范围/键并发请求时只有一个实例获得
 * {@link ReservationStatus#ACQUIRED}。响应正文以固定 JSON 结构保存，不记录
 * 密码、Token、音频或模型响应。</p>
 *
 * <p>该类不创建表；部署方必须先执行 {@code deploy/migrations/V001__initial_schema.sql}。
 * 会议快照、幂等和 Outbox 的原子提交由 {@link JdbcMeetingUnitOfWork} 负责。</p>
 */
public final class JdbcIdempotencyStore implements IdempotencyStore {
    private static final Logger LOGGER = Logger.getLogger(JdbcIdempotencyStore.class.getName());

    /** 固定基线物理表名；所有读写共用同一映射，不拼接任意标识符。 */
    private static final String TABLE =
            JdbcDatabaseTable.IDEMPOTENCY_RECORD.physicalName(JdbcDatabaseEngine.MYSQL);

    /** MySQL 5.7 基线写入；重复键由调用点按 1062 解释为“已有赢家”。 */
    private static String insertSql(JdbcDatabaseEngine engine) {
        return "INSERT INTO " + JdbcDatabaseTable.IDEMPOTENCY_RECORD.physicalName(engine)
                + " (subject_scope, idempotency_key, request_hash,"
                + " response_status, response_body, created_at)"
                + " VALUES (?, ?, ?, ?, CAST(? AS JSON), ?)";
    }

    private static final String DELETE_SQL = "DELETE FROM " + TABLE
            + " WHERE subject_scope = ? AND idempotency_key = ? AND request_hash = ?";

    /** 删除端口必须先比较摘要，再决定是否解析响应正文。 */
    private static final String FIND_DELETION_SQL = "SELECT request_hash, response_status, response_body"
            + " FROM " + TABLE + " WHERE subject_scope = ? AND idempotency_key = ?";

    private final DataSource dataSource;
    private final JdbcDatabaseEngine engine;

    public JdbcIdempotencyStore(DataSource dataSource) {
        this(dataSource, JdbcDatabaseEngine.MYSQL);
    }

    public JdbcIdempotencyStore(JdbcDatabaseContext database) {
        this(database.dataSource(), database.engine());
    }

    private JdbcIdempotencyStore(DataSource dataSource, JdbcDatabaseEngine engine) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public Optional<IdempotencyRecord> find(String scope, String key) {
        requireText("scope", scope);
        requireText("key", key);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(findSql(engine, false))) {
            JdbcValues.setText(statement, 1, engine, scope);
            JdbcValues.setText(statement, 2, engine, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readRecord(resultSet)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw fail("查询幂等记录失败", failure);
        }
    }

    @Override
    public void save(IdempotencyRecord record) {
        // 唯一键只插入首次事实：已有赢家时保留赢家，绝不覆盖。
        reserve(record);
    }

    @Override
    public Reservation reserve(IdempotencyRecord candidate) {
        Objects.requireNonNull(candidate, "candidate");
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Reservation result = reserve(connection, engine, candidate, Instant.now());
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException failure) {
                rollbackQuietly(connection);
                throw failure instanceof SQLException
                        ? fail("登记幂等记录失败", (SQLException) failure)
                        : (RuntimeException) failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException failure) {
            throw fail("创建幂等数据库连接失败", failure);
        }
    }

    /**
     * 在调用方已经开启的事务连接上预占幂等键；不会提交、回滚或关闭连接。
     * 插入与读取在同一连接内完成，外层 Unit of Work 可以把它和会议快照、
     * Outbox 事件作为一个原子提交单元。
     */
    static Reservation reserve(Connection connection, IdempotencyRecord candidate) throws SQLException {
        return reserve(connection, candidate, Instant.now());
    }

    /**
     * 在调用方事务连接上使用显式事实时间预占幂等键。
     *
     * <p>owned 创建工作单元必须把 Reservation bind、Meeting、幂等记录和
     * Outbox 的时间统一为一次读取的微秒精度 {@code createdAt}。因此新事务
     * 编排调用本重载；不传时间的旧入口继续使用当前 JVM 时间以保持兼容。</p>
     */
    static Reservation reserve(
            Connection connection,
            IdempotencyRecord candidate,
            Instant createdAt) throws SQLException {
        return reserve(connection, JdbcDatabaseEngine.MYSQL, candidate, createdAt);
    }

    static Reservation reserve(Connection connection, JdbcDatabaseEngine engine,
            IdempotencyRecord candidate, Instant createdAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(createdAt, "createdAt");
        int inserted;
        try (PreparedStatement statement = connection.prepareStatement(insertSql(engine))) {
            JdbcValues.setText(statement, 1, engine, candidate.scope());
                JdbcValues.setText(statement, 2, engine, candidate.key());
                statement.setString(3, candidate.requestDigest());
                statement.setInt(4, 200);
                statement.setString(5, IdempotencyResultJson.encode(candidate.result()));
                JdbcValues.setInstant(statement, 6, createdAt);
            inserted = statement.executeUpdate();
        } catch (SQLException failure) {
            if (engine != JdbcDatabaseEngine.MYSQL || !JdbcSqlErrors.isDuplicate(engine, failure)) {
                throw failure;
            }
            inserted = 0; // 此表唯一轴仅为编码范围/键；其它完整性错误向外传播。
        }
        IdempotencyRecord current = findCurrent(connection, engine, candidate.scope(), candidate.key())
                .orElseThrow(() -> new IllegalStateException("幂等记录插入后不可见"));
        if (inserted == 1) {
            LOGGER.log(Level.FINE, "JDBC 事务内幂等预占成功");
            return new Reservation(ReservationStatus.ACQUIRED, candidate);
        }
        if (!current.requestDigest().equals(candidate.requestDigest())) {
            LOGGER.log(Level.WARNING, "JDBC 事务内幂等键摘要冲突");
            return new Reservation(ReservationStatus.CONFLICT, current);
        }
        LOGGER.log(Level.INFO, "JDBC 事务内幂等请求重放");
        return new Reservation(ReservationStatus.REPLAY, current);
    }

    /** 在调用方事务连接上读取幂等记录。 */
    static Optional<IdempotencyRecord> find(Connection connection, String scope, String key)
            throws SQLException {
        return find(connection, JdbcDatabaseEngine.MYSQL, scope, key);
    }

    static Optional<IdempotencyRecord> find(Connection connection, JdbcDatabaseEngine engine,
            String scope, String key) throws SQLException {
        return findUsing(connection, engine, scope, key, false);
    }

    /** 写事务已经取得业务行锁后，读取等待期间提交的唯一赢家。 */
    static Optional<IdempotencyRecord> findCurrent(Connection connection, JdbcDatabaseEngine engine,
            String scope, String key) throws SQLException {
        return findUsing(connection, engine, scope, key, engine == JdbcDatabaseEngine.MYSQL);
    }

    private static Optional<IdempotencyRecord> findUsing(Connection connection, JdbcDatabaseEngine engine,
            String scope, String key, boolean currentRead) throws SQLException {
        requireText("scope", scope);
        requireText("key", key);
        try (PreparedStatement statement = connection.prepareStatement(findSql(engine, currentRead))) {
            JdbcValues.setText(statement, 1, engine, scope);
            JdbcValues.setText(statement, 2, engine, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readRecord(resultSet)) : Optional.empty();
            }
        }
    }

    /**
     * 在调用方事务连接上保存幂等记录；不会自行提交事务。
     *
     * <p>更新语义保持“只插入首次事实”：唯一键重复表示已有赢家，本次保存不覆盖、
     * 也不向上抛错；其它完整性错误仍然传播。</p>
     */
    static void save(Connection connection, IdempotencyRecord record) throws SQLException {
        Objects.requireNonNull(record, "record");
        try (PreparedStatement statement =
                     connection.prepareStatement(insertSql(JdbcDatabaseEngine.MYSQL))) {
            bindCandidate(statement, record);
            try {
                statement.executeUpdate();
            } catch (SQLException duplicate) {
                if (!JdbcSqlErrors.isDuplicate(JdbcDatabaseEngine.MYSQL, duplicate)) {
                    throw duplicate;
                }
            }
        }
    }

    /** 在调用方事务连接上释放预占；仅删除摘要完全相同的记录。 */
    static void release(Connection connection, IdempotencyRecord candidate) throws SQLException {
        Objects.requireNonNull(candidate, "candidate");
        try (PreparedStatement statement = connection.prepareStatement(DELETE_SQL)) {
            statement.setString(1, candidate.scope());
            statement.setString(2, candidate.key());
            statement.setString(3, candidate.requestDigest());
            statement.executeUpdate();
        }
    }

    /**
     * 在调用方事务内读取删除幂等原始事实。
     *
     * <p>返回值刻意不解析 {@code response_body}；删除端口必须先比较请求摘要，
     * 摘要一致后才能调用 {@link #decodeDeletion(DeletionRawRecord)}。
     * 这样同键不同摘要不会借助损坏正文形成额外侧信道。</p>
     */
    static Optional<DeletionRawRecord> findDeletion(
            Connection connection,
            String scope,
            String key) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        requireText("scope", scope);
        requireText("key", key);
        try (PreparedStatement statement = connection.prepareStatement(FIND_DELETION_SQL)) {
            statement.setString(1, scope);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                Object status = rows.getObject("response_status");
                return Optional.of(new DeletionRawRecord(
                        rows.getString("request_hash"),
                        status == null ? null : ((Number) status).intValue(),
                        rows.getString("response_body")));
            }
        }
    }

    /**
     * 预占删除幂等事实，并读取唯一键下最终可见的原始记录。
     *
     * <p>该原语固定写入 HTTP 202 和 {@link MeetingDeletionResultJson}，不经过
     * {@link MeetingCommandResult} 或 {@link IdempotencyResultJson}。它不提交、
     * 回滚或关闭连接，也不记录 scope、key、digest、正文或 receipt。</p>
     */
    static DeletionReservation reserveDeletion(
            Connection connection,
            String scope,
            String key,
            String requestDigest,
            MeetingStatus accepted,
            Instant createdAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        requireText("scope", scope);
        requireText("key", key);
        requireText("requestDigest", requestDigest);
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(createdAt, "createdAt");
        int inserted;
        try (PreparedStatement statement =
                     connection.prepareStatement(insertSql(JdbcDatabaseEngine.MYSQL))) {
            JdbcValues.setText(statement, 1, JdbcDatabaseEngine.MYSQL, scope);
            JdbcValues.setText(statement, 2, JdbcDatabaseEngine.MYSQL, key);
            statement.setString(3, requestDigest);
            statement.setInt(4, 202);
            statement.setString(5, MeetingDeletionResultJson.encode(accepted));
            JdbcValues.setInstant(statement, 6, createdAt);
            try {
                inserted = statement.executeUpdate();
            } catch (SQLException duplicate) {
                // 同键已有删除受理事实：本次没有取得写入权，随后按最终行裁决。
                if (!JdbcSqlErrors.isDuplicate(JdbcDatabaseEngine.MYSQL, duplicate)) {
                    throw duplicate;
                }
                inserted = 0;
            }
        }
        DeletionRawRecord current = findDeletion(connection, scope, key)
                .orElseThrow(() -> new IllegalStateException(
                        "删除幂等记录插入后不可见"));
        return new DeletionReservation(inserted == 1, current);
    }

    /** 严格解码摘要已经匹配的删除结果；错误状态或正文统一由调用方关闭式映射。 */
    static MeetingStatus decodeDeletion(DeletionRawRecord record) {
        Objects.requireNonNull(record, "record");
        if (!Integer.valueOf(202).equals(record.responseStatus())
                || record.responseBody() == null) {
            throw new IllegalArgumentException("删除幂等持久事实格式非法");
        }
        return MeetingDeletionResultJson.decode(record.responseBody());
    }

    /** 删除幂等原始事实；诊断文本永远不显示摘要或正文。 */
    record DeletionRawRecord(
            String requestDigest,
            Integer responseStatus,
            String responseBody) {
        @Override
        public String toString() {
            return "DeletionRawRecord[redacted]";
        }
    }

    /** 删除幂等插入竞争结果和数据库最终行。 */
    record DeletionReservation(boolean acquired, DeletionRawRecord record) {
        DeletionReservation {
            Objects.requireNonNull(record, "record");
        }
    }

    @Override
    public void release(IdempotencyRecord candidate) {
        Objects.requireNonNull(candidate, "candidate");
        String sql = "DELETE FROM " + JdbcDatabaseTable.IDEMPOTENCY_RECORD.physicalName(engine)
                + " WHERE subject_scope = ? AND idempotency_key = ? AND request_hash = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (engine == JdbcDatabaseEngine.MYSQL) {
                JdbcValues.setText(statement, 1, engine, candidate.scope());
                JdbcValues.setText(statement, 2, engine, candidate.key());
                statement.setString(3, candidate.requestDigest());
            } else {
                statement.setString(1, candidate.scope());
                statement.setString(2, candidate.key());
                statement.setString(3, candidate.requestDigest());
            }
            statement.executeUpdate();
            LOGGER.log(Level.FINE, "JDBC 幂等预占已释放");
        } catch (SQLException failure) {
            throw fail("释放幂等记录失败", failure);
        }
    }

    private static String findSql(JdbcDatabaseEngine engine, boolean currentRead) {
        return "SELECT subject_scope, idempotency_key, request_hash, response_status, response_body, created_at FROM "
                + JdbcDatabaseTable.IDEMPOTENCY_RECORD.physicalName(engine)
                + " WHERE subject_scope = ? AND idempotency_key = ?"
                + (currentRead ? " LOCK IN SHARE MODE" : "");
    }

    private static void bindCandidate(PreparedStatement statement, IdempotencyRecord candidate)
            throws SQLException {
        JdbcValues.setText(statement, 1, JdbcDatabaseEngine.MYSQL, candidate.scope());
        JdbcValues.setText(statement, 2, JdbcDatabaseEngine.MYSQL, candidate.key());
        statement.setString(3, candidate.requestDigest());
        statement.setInt(4, 200);
        statement.setString(5, IdempotencyResultJson.encode(candidate.result()));
        JdbcValues.setInstant(statement, 6, Instant.now());
    }

    private static IdempotencyRecord readRecord(ResultSet resultSet) throws SQLException {
        String responseBody = resultSet.getString("response_body");
        if (responseBody == null) {
            throw new SQLException("idempotency_record.response_body 不能为空");
        }
        final MeetingCommandResult result;
        try {
            result = IdempotencyResultJson.decode(responseBody);
        } catch (IllegalArgumentException malformed) {
            // 不附带 cause，防止损坏 JSON、主体或权益引用通过异常链外泄。
            throw new SQLException("幂等持久事实损坏", "22000");
        }
        return new IdempotencyRecord(
                JdbcValues.text(resultSet, "subject_scope"),
                JdbcValues.text(resultSet, "idempotency_key"),
                resultSet.getString("request_hash"),
                result);
    }

    private static RuntimeException fail(String message, SQLException cause) {
        String state = cause.getSQLState();
        return new IllegalStateException(message + " sqlState="
                + (state == null || state.isBlank() ? "UNKNOWN" : state));
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
