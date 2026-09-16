package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.OutboxStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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

/**
 * PostgreSQL Outbox 适配器参考实现。
 *
 * <p>所有领取和完成操作都在数据库条件更新中校验 owner、token、版本和
 * lease 到期时间。{@code lease_version} 在每次成功领取时递增；端口的
 * {@link LeaseToken} 保留不透明 token，数据库版本用于防止同一 owner 重用
 * 旧请求。事件载荷只作为 JSONB 不透明文本传递，日志不会输出载荷正文。</p>
 *
 * <p>该类不负责消息发布，也不负责创建表；部署前必须执行 V001、V002 迁移。
 * 会议快照和 Outbox 的同事务写入由后续 Unit of Work 负责。</p>
 */
public final class JdbcOutboxStore implements OutboxStore {
    private static final Logger LOGGER = Logger.getLogger(JdbcOutboxStore.class.getName());

    private static final String APPEND_SQL = """
            INSERT INTO outbox_event (
                event_id, aggregate_type, aggregate_id, event_type, payload,
                occurred_at, available_at, status, attempt_count)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, 'PENDING', 0)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private static final String FIND_SQL = """
            SELECT event_id, aggregate_type, aggregate_id, event_type, payload,
                   occurred_at, available_at, status, attempt_count,
                   lease_owner, lease_until, lease_token, lease_version, published_at, last_error
              FROM outbox_event
             WHERE event_id = ?
            """;

    private static final String CLAIM_SQL = """
            SELECT event_id, aggregate_type, aggregate_id, event_type, payload,
                   occurred_at, available_at, status, attempt_count,
                   lease_owner, lease_until, lease_token, lease_version, published_at, last_error
              FROM outbox_event
             WHERE available_at <= ?
               AND (status = 'PENDING' OR (status = 'LEASED' AND lease_until <= ?))
             ORDER BY available_at, occurred_at, event_id
             FOR UPDATE SKIP LOCKED
             LIMIT ?
            """;

    private static final String CLAIM_UPDATE_SQL = """
            UPDATE outbox_event
               SET status = 'LEASED', lease_owner = ?, lease_until = ?,
                   lease_token = ?, lease_version = lease_version + 1
             WHERE event_id = ?
               AND (status = 'PENDING' OR (status = 'LEASED' AND lease_until <= ?))
            """;

    private static final String PUBLISH_SQL = """
            UPDATE outbox_event
               SET status = 'PUBLISHED', published_at = ?,
                   lease_owner = NULL, lease_until = NULL, lease_token = NULL,
                   last_error = NULL
             WHERE event_id = ? AND status = 'LEASED'
               AND lease_owner = ? AND lease_token = ? AND lease_version = ?
               AND lease_until > ?
            """;

    private static final String FAIL_SQL = """
            UPDATE outbox_event
               SET status = CASE WHEN attempt_count + 1 >= ? THEN 'DEAD' ELSE 'PENDING' END,
                   attempt_count = attempt_count + 1,
                   available_at = ?, lease_owner = NULL, lease_until = NULL,
                   lease_token = NULL, last_error = ?, published_at = NULL
             WHERE event_id = ? AND status = 'LEASED'
               AND lease_owner = ? AND lease_token = ? AND lease_version = ?
               AND lease_until > ?
            """;

    private final DataSource dataSource;

    public JdbcOutboxStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public AppendResult append(OutboxEvent event) {
        validateAppend(event);
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int inserted;
                try (PreparedStatement statement = connection.prepareStatement(APPEND_SQL)) {
                    bindAppend(statement, event);
                    inserted = statement.executeUpdate();
                }
                OutboxEvent current = find(connection, event.eventId())
                        .orElseThrow(() -> new IllegalStateException("Outbox 插入后不可见"));
                connection.commit();
                if (inserted == 1) {
                    LOGGER.log(Level.INFO, "JDBC Outbox 事件已追加 eventId={0} eventType={1}",
                            new Object[]{event.eventId(), event.eventType()});
                    return new AppendResult(AppendStatus.APPENDED, current);
                }
                if (current.sameMessage(event)) {
                    LOGGER.log(Level.FINE, "JDBC Outbox 事件幂等重放 eventId={0}", event.eventId());
                    return new AppendResult(AppendStatus.REPLAYED, current);
                }
                LOGGER.log(Level.WARNING, "JDBC Outbox eventId 内容冲突 eventId={0}", event.eventId());
                return new AppendResult(AppendStatus.CONFLICT, current);
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

    @Override
    public Optional<OutboxEvent> find(String eventId) {
        requireText("eventId", eventId);
        try (Connection connection = dataSource.getConnection()) {
            return find(connection, eventId);
        } catch (SQLException failure) {
            throw fail("查询 Outbox 事件失败 eventId=" + eventId, failure);
        }
    }

    @Override
    public List<ClaimedEvent> claim(String leaseOwner, java.time.Instant now,
                                    java.time.Duration leaseDuration, int limit) {
        requireText("leaseOwner", leaseOwner);
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        java.time.Instant leaseUntil = now.plus(leaseDuration);
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<ClaimedEvent> claimed = new ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement(CLAIM_SQL)) {
                    select.setTimestamp(1, Timestamp.from(now));
                    select.setTimestamp(2, Timestamp.from(now));
                    select.setInt(3, limit);
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next()) {
                            OutboxEvent previous = readEvent(rows);
                            long previousLeaseVersion = rows.getLong("lease_version");
                            String tokenText = UUID.randomUUID().toString();
                            try (PreparedStatement update = connection.prepareStatement(CLAIM_UPDATE_SQL)) {
                                update.setString(1, leaseOwner);
                                update.setTimestamp(2, Timestamp.from(leaseUntil));
                                update.setString(3, tokenText);
                                update.setString(4, previous.eventId());
                                update.setTimestamp(5, Timestamp.from(now));
                                if (update.executeUpdate() != 1) {
                                    continue;
                                }
                            }
                            OutboxEvent leased = withLease(previous, leaseOwner, leaseUntil);
                            LeaseToken lease = new LeaseToken(
                                    leased.eventId(), leaseOwner, tokenText, leaseUntil, now,
                                    previousLeaseVersion + 1);
                            claimed.add(new ClaimedEvent(leased, lease));
                        }
                    }
                }
                connection.commit();
                if (!claimed.isEmpty()) {
                    LOGGER.log(Level.FINE, "JDBC Outbox 事件已领取 owner={0} count={1}",
                            new Object[]{leaseOwner, claimed.size()});
                }
                return List.copyOf(claimed);
            } catch (RuntimeException | SQLException failure) {
                rollbackQuietly(connection);
                throw failure instanceof SQLException
                        ? fail("领取 Outbox 事件失败 owner=" + leaseOwner, (SQLException) failure)
                        : (RuntimeException) failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException failure) {
            throw fail("创建 Outbox 数据库连接失败", failure);
        }
    }

    @Override
    public PublishResult markPublished(LeaseToken lease, java.time.Instant publishedAt) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(publishedAt, "publishedAt");
        try (Connection connection = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement statement = connection.prepareStatement(PUBLISH_SQL)) {
                statement.setTimestamp(1, Timestamp.from(publishedAt));
                statement.setString(2, lease.eventId());
                statement.setString(3, lease.owner());
                statement.setString(4, lease.token());
                statement.setLong(5, lease.leaseVersion());
                statement.setTimestamp(6, Timestamp.from(publishedAt));
                updated = statement.executeUpdate();
            }
            if (updated == 1) {
                OutboxEvent current = find(connection, lease.eventId()).orElseThrow();
                LOGGER.log(Level.INFO, "JDBC Outbox 事件已发布 eventId={0}", lease.eventId());
                return new PublishResult(PublishStatus.PUBLISHED, current);
            }
            return classifyPublishFailure(connection, lease, publishedAt);
        } catch (SQLException failure) {
            throw fail("确认 Outbox 发布失败 eventId=" + lease.eventId(), failure);
        }
    }

    @Override
    public FailureResult markFailed(LeaseToken lease, java.time.Instant failedAt,
                                    java.time.Instant nextAvailableAt, String error, int maxAttempts) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(nextAvailableAt, "nextAvailableAt");
        requireText("error", error);
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (nextAvailableAt.isBefore(failedAt)) {
            throw new IllegalArgumentException("nextAvailableAt cannot be before failedAt");
        }
        try (Connection connection = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement statement = connection.prepareStatement(FAIL_SQL)) {
                statement.setInt(1, maxAttempts);
                statement.setTimestamp(2, Timestamp.from(nextAvailableAt));
                statement.setString(3, error);
                statement.setString(4, lease.eventId());
                statement.setString(5, lease.owner());
                statement.setString(6, lease.token());
                statement.setLong(7, lease.leaseVersion());
                statement.setTimestamp(8, Timestamp.from(failedAt));
                updated = statement.executeUpdate();
            }
            if (updated == 1) {
                OutboxEvent current = find(connection, lease.eventId()).orElseThrow();
                FailureStatus status = current.status() == EventStatus.DEAD
                        ? FailureStatus.DEAD : FailureStatus.RETRY_SCHEDULED;
                LOGGER.log(status == FailureStatus.DEAD ? Level.WARNING : Level.INFO,
                        "JDBC Outbox 事件处理失败 eventId={0} status={1}",
                        new Object[]{lease.eventId(), status});
                return new FailureResult(status, current);
            }
            return classifyFailure(connection, lease, failedAt);
        } catch (SQLException failure) {
            throw fail("记录 Outbox 失败事件失败 eventId=" + lease.eventId(), failure);
        }
    }

    private static PublishResult classifyPublishFailure(Connection connection, LeaseToken lease,
                                                        java.time.Instant at) throws SQLException {
        Optional<OutboxEvent> found = find(connection, lease.eventId());
        if (found.isEmpty()) {
            return new PublishResult(PublishStatus.NOT_FOUND, null);
        }
        OutboxEvent current = found.get();
        if (current.status() == EventStatus.PUBLISHED) {
            return new PublishResult(PublishStatus.ALREADY_PUBLISHED, current);
        }
        if (current.status() == EventStatus.DEAD) {
            return new PublishResult(PublishStatus.ALREADY_DEAD, current);
        }
        if (current.status() == EventStatus.LEASED
                && (current.leaseUntil() == null || !current.leaseUntil().isAfter(at)
                )) {
            return new PublishResult(PublishStatus.LEASE_EXPIRED, current);
        }
        return new PublishResult(PublishStatus.NOT_OWNER, current);
    }

    private static FailureResult classifyFailure(Connection connection, LeaseToken lease,
                                                 java.time.Instant at) throws SQLException {
        Optional<OutboxEvent> found = find(connection, lease.eventId());
        if (found.isEmpty()) {
            return new FailureResult(FailureStatus.NOT_FOUND, null);
        }
        OutboxEvent current = found.get();
        if (current.status() == EventStatus.PUBLISHED) {
            return new FailureResult(FailureStatus.ALREADY_PUBLISHED, current);
        }
        if (current.status() == EventStatus.DEAD) {
            return new FailureResult(FailureStatus.ALREADY_DEAD, current);
        }
        if (current.status() == EventStatus.LEASED
                && (current.leaseUntil() == null || !current.leaseUntil().isAfter(at)
                )) {
            return new FailureResult(FailureStatus.LEASE_EXPIRED, current);
        }
        return new FailureResult(FailureStatus.NOT_OWNER, current);
    }

    private static Optional<OutboxEvent> find(Connection connection, String eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
            statement.setString(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readEvent(rows)) : Optional.empty();
            }
        }
    }

    private static OutboxEvent readEvent(ResultSet rows) throws SQLException {
        return new OutboxEvent(
                rows.getString("event_id"),
                rows.getString("aggregate_type"),
                rows.getString("aggregate_id"),
                rows.getString("event_type"),
                rows.getString("payload"),
                rows.getTimestamp("occurred_at").toInstant(),
                rows.getTimestamp("available_at").toInstant(),
                EventStatus.valueOf(rows.getString("status")),
                rows.getInt("attempt_count"),
                rows.getString("lease_owner"),
                toInstant(rows.getTimestamp("lease_until")),
                toInstant(rows.getTimestamp("published_at")),
                rows.getString("last_error"));
    }

    private static OutboxEvent withLease(OutboxEvent event, String owner, java.time.Instant until) {
        return new OutboxEvent(event.eventId(), event.aggregateType(), event.aggregateId(), event.eventType(),
                event.payload(), event.occurredAt(), event.availableAt(), EventStatus.LEASED,
                event.attemptCount(), owner, until, null, event.lastError());
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
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
