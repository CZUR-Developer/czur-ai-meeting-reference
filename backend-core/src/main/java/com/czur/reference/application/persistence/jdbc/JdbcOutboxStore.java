package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.OutboxStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.czur.reference.application.port.OutboxStore.AppendStatus;
import static com.czur.reference.application.port.OutboxStore.AppendResult;
import static com.czur.reference.application.port.OutboxStore.ClaimedEvent;
import static com.czur.reference.application.port.OutboxStore.EventStatus;
import static com.czur.reference.application.port.OutboxStore.FailureResult;
import static com.czur.reference.application.port.OutboxStore.FailureStatus;
import static com.czur.reference.application.port.OutboxStore.LeaseToken;
import static com.czur.reference.application.port.OutboxStore.OutboxEvent;
import static com.czur.reference.application.port.OutboxStore.PublishResult;
import static com.czur.reference.application.port.OutboxStore.PublishStatus;
import static com.czur.reference.application.port.OutboxStore.RenewResult;
import static com.czur.reference.application.port.OutboxStore.RenewStatus;

/**
 * Outbox 适配器参考实现。
 *
 * <p>所有领取和完成操作都在数据库条件更新中校验 owner、token、版本和
 * lease 到期时间。{@code lease_version} 在每次成功领取时递增；端口的
 * {@link LeaseToken} 保留不透明 token，数据库版本用于防止同一 owner 重用
 * 旧请求。事件载荷只作为 JSON 不透明文本传递，日志不会输出载荷正文。</p>
 *
 * <p>该类不负责消息发布，也不负责创建表；部署前必须执行唯一 V001 基线。
 * 会议快照和 Outbox 的同事务写入由 {@link JdbcMeetingUnitOfWork} 负责。</p>
 */
public final class JdbcOutboxStore implements OutboxStore {
    private static final Logger LOGGER = Logger.getLogger(JdbcOutboxStore.class.getName());

    private final DataSource dataSource;
    private final JdbcDatabaseEngine engine;

    public JdbcOutboxStore(DataSource dataSource) {
        this(dataSource, JdbcDatabaseEngine.MYSQL);
    }

    public JdbcOutboxStore(JdbcDatabaseContext database) {
        this(database.dataSource(), database.engine());
    }

    private JdbcOutboxStore(DataSource dataSource, JdbcDatabaseEngine engine) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public AppendResult append(OutboxEvent event) {
        validateAppend(event);
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                AppendResult result = append(connection, engine, event);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException failure) {
                rollbackQuietly(connection);
                throw failure instanceof SQLException
                        ? fail("追加 Outbox 事件失败 eventId=" + event.eventId(), (SQLException) failure)
                        : (RuntimeException) failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException failure) {
            throw fail("创建 Outbox 数据库连接失败", failure);
        }
    }

    /**
     * 在调用方已经开启的事务连接上追加 Outbox 事件；不会提交、回滚或关闭
     * 连接。调用方可因此把事件追加与会议快照、幂等记录放入同一事务。
     */
    static AppendResult append(Connection connection, OutboxEvent event) throws SQLException {
        return append(connection, JdbcDatabaseEngine.MYSQL, event);
    }

    static AppendResult append(Connection connection, JdbcDatabaseEngine engine, OutboxEvent event)
            throws SQLException {
        validateAppend(event);
        Objects.requireNonNull(connection, "connection");
        int inserted;
        String sql = "INSERT INTO "
                + JdbcDatabaseTable.OUTBOX_EVENT.physicalName(engine)
                + " (event_id, aggregate_type, aggregate_id, event_type, payload,"
                + " occurred_at, available_at, status, attempt_count)"
                + " VALUES (?, ?, ?, ?, CAST(? AS JSON), ?, ?, 'PENDING', 0)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, event.eventId());
                JdbcValues.setText(statement, 2, engine, event.aggregateType());
                JdbcValues.setText(statement, 3, engine, event.aggregateId());
                JdbcValues.setText(statement, 4, engine, event.eventType());
                statement.setString(5, event.payload());
                JdbcValues.setInstant(statement, 6, event.occurredAt());
                JdbcValues.setInstant(statement, 7, event.availableAt());
            inserted = statement.executeUpdate();
        } catch (SQLException failure) {
            if (engine != JdbcDatabaseEngine.MYSQL || !JdbcSqlErrors.isDuplicate(engine, failure)) {
                throw failure;
            }
            inserted = 0; // 此表只有 event_id 唯一轴，重读必须是 current read。
        }
        OutboxEvent current = findStored(connection, engine, event.eventId(), true).map(StoredEvent::event)
                .orElseThrow(() -> new IllegalStateException("Outbox 插入后不可见"));
        if (inserted == 1) {
            LOGGER.log(Level.INFO, "JDBC 事务内 Outbox 事件已追加 eventId={0} eventType={1}",
                    new Object[]{event.eventId(), event.eventType()});
            return new AppendResult(AppendStatus.APPENDED, current);
        }
        if (sameMessage(connection, engine, event)) {
            LOGGER.log(Level.FINE, "JDBC 事务内 Outbox 事件幂等重放 eventId={0}", event.eventId());
            return new AppendResult(AppendStatus.REPLAYED, current);
        }
        LOGGER.log(Level.WARNING, "JDBC 事务内 Outbox eventId 内容冲突 eventId={0}", event.eventId());
        return new AppendResult(AppendStatus.CONFLICT, current);
    }

    @Override
    public Optional<OutboxEvent> find(String eventId) {
        requireText("eventId", eventId);
        try (Connection connection = dataSource.getConnection()) {
            return find(connection, engine, eventId);
        } catch (SQLException failure) {
            throw fail("查询 Outbox 事件失败 eventId=" + eventId, failure);
        }
    }

    @Override
    public List<ClaimedEvent> claim(ClaimRequest request) {
        return JdbcMysqlOutboxWorker.claim(dataSource, request);
    }

    @Override
    public RenewResult renew(LeaseToken lease, Instant renewedAt, Duration leaseDuration) {
        return JdbcMysqlOutboxWorker.renew(dataSource, lease, renewedAt, leaseDuration);
    }

    @Override
    public PublishResult markPublished(LeaseToken lease, Instant publishedAt) {
        return JdbcMysqlOutboxWorker.markPublished(dataSource, lease, publishedAt);
    }

    @Override
    public FailureResult markFailed(LeaseToken lease, Instant failedAt,
                                    Instant nextAvailableAt, String error, int maxAttempts) {
        return JdbcMysqlOutboxWorker.markFailed(
                dataSource, lease, failedAt, nextAvailableAt, error, maxAttempts);
    }

    static RenewResult classifyRenewFailure(
            Connection connection, JdbcDatabaseEngine engine, LeaseToken lease,
            Instant renewedAt, Instant newExpiry) throws SQLException {
        Optional<StoredEvent> found = findStored(connection, engine, lease.eventId(), false);
        if (found.isEmpty()) {
            return new RenewResult(RenewStatus.NOT_FOUND, null, null);
        }
        StoredEvent stored = found.get();
        OutboxEvent current = stored.event();
        if (current.status() == EventStatus.PUBLISHED) {
            return new RenewResult(RenewStatus.ALREADY_PUBLISHED, current, null);
        }
        if (current.status() == EventStatus.DEAD) {
            return new RenewResult(RenewStatus.ALREADY_DEAD, current, null);
        }
        if (!leaseMatches(stored, lease)) {
            return new RenewResult(RenewStatus.NOT_OWNER, current, null);
        }
        if (renewedAt.isBefore(lease.claimedAt())
                || !renewedAt.isBefore(lease.expiresAt())
                || !newExpiry.isAfter(lease.expiresAt())) {
            return new RenewResult(RenewStatus.LEASE_EXPIRED, current, null);
        }
        // 条件更新失败但快照看似仍完全匹配时，不猜测续租成功。
        return new RenewResult(RenewStatus.NOT_OWNER, current, null);
    }

    static PublishResult classifyPublishFailure(Connection connection, JdbcDatabaseEngine engine,
                                                LeaseToken lease, Instant at) throws SQLException {
        Optional<StoredEvent> found = findStored(connection, engine, lease.eventId(), false);
        if (found.isEmpty()) {
            return new PublishResult(PublishStatus.NOT_FOUND, null);
        }
        StoredEvent stored = found.get();
        OutboxEvent current = stored.event();
        if (current.status() == EventStatus.PUBLISHED) {
            return new PublishResult(PublishStatus.ALREADY_PUBLISHED, current);
        }
        if (current.status() == EventStatus.DEAD) {
            return new PublishResult(PublishStatus.ALREADY_DEAD, current);
        }
        if (!leaseMatches(stored, lease)) {
            return new PublishResult(PublishStatus.NOT_OWNER, current);
        }
        if (at.isBefore(lease.claimedAt()) || !at.isBefore(lease.expiresAt())) {
            return new PublishResult(PublishStatus.LEASE_EXPIRED, current);
        }
        return new PublishResult(PublishStatus.NOT_OWNER, current);
    }

    static FailureResult classifyFailure(Connection connection, JdbcDatabaseEngine engine,
                                         LeaseToken lease, Instant at) throws SQLException {
        Optional<StoredEvent> found = findStored(connection, engine, lease.eventId(), false);
        if (found.isEmpty()) {
            return new FailureResult(FailureStatus.NOT_FOUND, null);
        }
        StoredEvent stored = found.get();
        OutboxEvent current = stored.event();
        if (current.status() == EventStatus.PUBLISHED) {
            return new FailureResult(FailureStatus.ALREADY_PUBLISHED, current);
        }
        if (current.status() == EventStatus.DEAD) {
            return new FailureResult(FailureStatus.ALREADY_DEAD, current);
        }
        if (!leaseMatches(stored, lease)) {
            return new FailureResult(FailureStatus.NOT_OWNER, current);
        }
        if (at.isBefore(lease.claimedAt()) || !at.isBefore(lease.expiresAt())) {
            return new FailureResult(FailureStatus.LEASE_EXPIRED, current);
        }
        return new FailureResult(FailureStatus.NOT_OWNER, current);
    }

    /** 在调用方事务连接上读取事件；不会关闭或提交连接。 */
    static Optional<OutboxEvent> find(Connection connection, String eventId) throws SQLException {
        return findStored(connection, eventId).map(StoredEvent::event);
    }

    static Optional<OutboxEvent> find(Connection connection, JdbcDatabaseEngine engine, String eventId)
            throws SQLException {
        return findStored(connection, engine, eventId, false).map(StoredEvent::event);
    }

    private static Optional<StoredEvent> findStored(Connection connection, String eventId)
            throws SQLException {
        return findStored(connection, JdbcDatabaseEngine.MYSQL, eventId, false);
    }

    static Optional<StoredEvent> findStored(Connection connection, JdbcDatabaseEngine engine,
            String eventId, boolean currentRead) throws SQLException {
        String sql = """
                SELECT event_id, aggregate_type, aggregate_id, event_type, payload,
                       occurred_at, available_at, status, attempt_count,
                       lease_owner, lease_until, lease_token, lease_version, published_at, last_error
                  FROM %s WHERE event_id = ?
                """.formatted(JdbcDatabaseTable.OUTBOX_EVENT.physicalName(engine))
                + (currentRead ? " LOCK IN SHARE MODE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readStoredEvent(rows)) : Optional.empty();
            }
        }
    }

    /**
     * 使用数据库 JSON 相等语义比较不可变消息字段。
     *
     * <p>JSON 读取后可能改变无意义空白或对象键顺序，不能继续使用 Java 原始
     * 字符串相等判断，否则同一事件在首次写入后重放会被误判为冲突。</p>
     */
    private static boolean sameMessage(Connection connection, JdbcDatabaseEngine engine, OutboxEvent event)
            throws SQLException {
        String sql = "SELECT aggregate_type = ?"
                + " AND aggregate_id = ? AND event_type = ? AND payload = CAST(? AS JSON)"
                + " AND occurred_at = ? AS same_message FROM "
                + JdbcDatabaseTable.OUTBOX_EVENT.physicalName(engine) + " WHERE event_id = ? LOCK IN SHARE MODE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, event.aggregateType());
            JdbcValues.setText(statement, 2, engine, event.aggregateId());
            JdbcValues.setText(statement, 3, engine, event.eventType());
            statement.setString(4, event.payload());
            JdbcValues.setInstant(statement, 5, event.occurredAt());
            JdbcValues.setText(statement, 6, engine, event.eventId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean("same_message");
            }
        }
    }

    private static OutboxEvent readEvent(ResultSet rows) throws SQLException {
        return new OutboxEvent(
                JdbcValues.text(rows, "event_id"),
                JdbcValues.text(rows, "aggregate_type"),
                JdbcValues.text(rows, "aggregate_id"),
                JdbcValues.text(rows, "event_type"),
                rows.getString("payload"),
                JdbcValues.instant(rows, "occurred_at"),
                JdbcValues.instant(rows, "available_at"),
                EventStatus.valueOf(JdbcValues.text(rows, "status")),
                rows.getInt("attempt_count"),
                rows.getString("lease_owner"),
                JdbcValues.instant(rows, "lease_until"),
                JdbcValues.instant(rows, "published_at"),
                rows.getString("last_error"));
    }

    static StoredEvent readStoredEvent(ResultSet rows) throws SQLException {
        return new StoredEvent(
                readEvent(rows), rows.getString("lease_token"), rows.getLong("lease_version"));
    }

    static boolean leaseMatches(StoredEvent stored, LeaseToken requested) {
        OutboxEvent current = stored.event();
        return current.status() == EventStatus.LEASED
                && requested.owner().equals(current.leaseOwner())
                && requested.expiresAt().equals(current.leaseUntil())
                && requested.token().equals(stored.leaseToken())
                && requested.leaseVersion() == stored.leaseVersion();
    }

    static OutboxEvent withLease(OutboxEvent event, String owner, java.time.Instant until) {
        return new OutboxEvent(event.eventId(), event.aggregateType(), event.aggregateId(), event.eventType(),
                event.payload(), event.occurredAt(), event.availableAt(), EventStatus.LEASED,
                event.attemptCount(), owner, until, null, event.lastError());
    }

    record StoredEvent(OutboxEvent event, String leaseToken, long leaseVersion) {
    }

    private static void bindAppend(PreparedStatement statement, OutboxEvent event) throws SQLException {
        statement.setString(1, event.eventId());
        statement.setString(2, event.aggregateType());
        statement.setString(3, event.aggregateId());
        statement.setString(4, event.eventType());
        statement.setString(5, event.payload());
        statement.setTimestamp(6, Timestamp.from(event.occurredAt()));
        statement.setTimestamp(7, Timestamp.from(event.availableAt()));
    }

    private static void validateAppend(OutboxEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.status() != EventStatus.PENDING || event.attemptCount() != 0
                || event.leaseOwner() != null || event.leaseUntil() != null || event.publishedAt() != null) {
            throw new IllegalArgumentException("append requires a PENDING event with no attempt, lease or publication timestamp");
        }
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
