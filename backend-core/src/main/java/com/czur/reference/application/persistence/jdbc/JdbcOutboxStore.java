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
import static com.czur.reference.application.port.OutboxStore.RenewResult;
import static com.czur.reference.application.port.OutboxStore.RenewStatus;

/**
 * Outbox 适配器参考实现。
 *
 * <p>所有领取和完成操作都在数据库条件更新中校验 owner、token、版本和
 * lease 到期时间。{@code lease_version} 在每次成功领取时递增；端口的
 * {@link LeaseToken} 保留不透明 token，数据库版本用于防止同一 owner 重用
 * 旧请求。事件载荷只作为 JSONB 不透明文本传递，日志不会输出载荷正文。</p>
 *
 * <p>该类不负责消息发布，也不负责创建表；部署前必须执行唯一 V001 基线。
 * 会议快照和 Outbox 的同事务写入由 {@link JdbcMeetingUnitOfWork} 负责。</p>
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

    private static final String SAME_MESSAGE_SQL = """
            SELECT aggregate_type = ?
               AND aggregate_id = ?
               AND event_type = ?
               AND payload = ?::jsonb
               AND occurred_at = ? AS same_message
              FROM outbox_event
             WHERE event_id = ?
            """;

    private static final String CLAIM_SQL = """
            SELECT event_id, aggregate_type, aggregate_id, event_type, payload,
                   occurred_at, available_at, status, attempt_count,
                   lease_owner, lease_until, lease_token, lease_version, published_at, last_error
             FROM outbox_event
             WHERE event_type = ANY (?::text[])
               AND available_at <= ?
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
               AND lease_version = ?
            RETURNING lease_version
            """;

    private static final String RENEW_SQL = """
            UPDATE outbox_event
               SET lease_until = ?, lease_token = ?, lease_version = lease_version + 1
             WHERE event_id = ? AND status = 'LEASED'
               AND lease_owner = ? AND lease_token = ? AND lease_version = ?
               AND lease_until = ? AND lease_until > ?
               AND ? > lease_until
            """;

    private static final String PUBLISH_SQL = """
            UPDATE outbox_event
               SET status = 'PUBLISHED', published_at = ?,
                   lease_owner = NULL, lease_until = NULL, lease_token = NULL,
                   last_error = NULL
             WHERE event_id = ? AND status = 'LEASED'
               AND lease_owner = ? AND lease_token = ? AND lease_version = ?
               AND lease_until = ? AND lease_until > ?
            """;

    private static final String FAIL_SQL = """
            UPDATE outbox_event
               SET status = CASE WHEN attempt_count + 1 >= ? THEN 'DEAD' ELSE 'PENDING' END,
                   attempt_count = attempt_count + 1,
                   available_at = ?, lease_owner = NULL, lease_until = NULL,
                   lease_token = NULL, last_error = ?, published_at = NULL
             WHERE event_id = ? AND status = 'LEASED'
               AND lease_owner = ? AND lease_token = ? AND lease_version = ?
               AND lease_until = ? AND lease_until > ?
            """;

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
                if (engine == JdbcDatabaseEngine.MYSQL) {
                    AppendResult result = append(connection, engine, event);
                    connection.commit();
                    return result;
                }
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
                if (sameMessage(connection, event)) {
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
        if (engine == JdbcDatabaseEngine.MYSQL) {
            return JdbcMysqlOutboxWorker.claim(dataSource, request);
        }
        Objects.requireNonNull(request, "request");
        if (request.registeredEventTypes().isEmpty()) {
            return List.of();
        }
        Instant claimedAt = roundedMicrosecondTimestamp(request.now());
        Instant leaseUntil = roundedMicrosecondTimestamp(request.now().plus(request.leaseDuration()));
        if (!leaseUntil.isAfter(claimedAt)) {
            throw new IllegalArgumentException(
                    "leaseDuration must advance expiry at database microsecond precision");
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<ClaimedEvent> claimed = new ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement(CLAIM_SQL)) {
                    java.sql.Array registeredTypes = connection.createArrayOf(
                            "text", request.registeredEventTypes().toArray(String[]::new));
                    try {
                        select.setArray(1, registeredTypes);
                        select.setTimestamp(2, Timestamp.from(claimedAt));
                        select.setTimestamp(3, Timestamp.from(claimedAt));
                        select.setInt(4, request.limit());
                        try (ResultSet rows = select.executeQuery()) {
                            while (rows.next()) {
                                StoredEvent previous = readStoredEvent(rows);
                                long expectedLeaseVersion = Math.addExact(previous.leaseVersion(), 1L);
                                String tokenText = UUID.randomUUID().toString();
                                try (PreparedStatement update = connection.prepareStatement(CLAIM_UPDATE_SQL)) {
                                    update.setString(1, request.leaseOwner());
                                    update.setTimestamp(2, Timestamp.from(leaseUntil));
                                    update.setString(3, tokenText);
                                    update.setString(4, previous.event().eventId());
                                    update.setTimestamp(5, Timestamp.from(claimedAt));
                                    update.setLong(6, previous.leaseVersion());
                                    try (ResultSet updated = update.executeQuery()) {
                                        if (!updated.next()) {
                                            continue;
                                        }
                                        long returnedLeaseVersion = updated.getLong("lease_version");
                                        if (returnedLeaseVersion != expectedLeaseVersion || updated.next()) {
                                            throw new IllegalStateException(
                                                    "Outbox 领取后 leaseVersion 不一致");
                                        }
                                    }
                                }
                                OutboxEvent leased = withLease(
                                        previous.event(), request.leaseOwner(), leaseUntil);
                                LeaseToken lease = new LeaseToken(
                                        leased.eventId(), request.leaseOwner(), tokenText,
                                        leaseUntil, claimedAt, expectedLeaseVersion);
                                claimed.add(new ClaimedEvent(leased, lease));
                            }
                        }
                    } finally {
                        registeredTypes.free();
                    }
                }
                connection.commit();
                if (!claimed.isEmpty()) {
                    LOGGER.log(Level.FINE, "JDBC Outbox 事件已领取 owner={0} count={1}",
                            new Object[]{request.leaseOwner(), claimed.size()});
                }
                return List.copyOf(claimed);
            } catch (RuntimeException | SQLException failure) {
                rollbackQuietly(connection);
                throw failure instanceof SQLException
                        ? fail("领取 Outbox 事件失败 owner=" + request.leaseOwner(),
                                (SQLException) failure)
                        : (RuntimeException) failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException failure) {
            throw fail("创建 Outbox 数据库连接失败", failure);
        }
    }

    @Override
    public RenewResult renew(LeaseToken lease, Instant renewedAt, Duration leaseDuration) {
        if (engine == JdbcDatabaseEngine.MYSQL) {
            return JdbcMysqlOutboxWorker.renew(dataSource, lease, renewedAt, leaseDuration);
        }
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(renewedAt, "renewedAt");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        Instant effectiveRenewedAt = roundedMicrosecondTimestamp(renewedAt);
        Instant newExpiry = roundedMicrosecondTimestamp(renewedAt.plus(leaseDuration));
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int updated = 0;
                String renewedToken = UUID.randomUUID().toString();
                boolean temporalWindowAllowsRenewal =
                        hasCanonicalLeaseExpiry(lease)
                                && !effectiveRenewedAt.isBefore(lease.claimedAt())
                                && effectiveRenewedAt.isBefore(lease.expiresAt())
                                && newExpiry.isAfter(lease.expiresAt());
                if (temporalWindowAllowsRenewal) {
                    try (PreparedStatement statement = connection.prepareStatement(RENEW_SQL)) {
                        statement.setTimestamp(1, Timestamp.from(newExpiry));
                        statement.setString(2, renewedToken);
                        statement.setString(3, lease.eventId());
                        statement.setString(4, lease.owner());
                        statement.setString(5, lease.token());
                        statement.setLong(6, lease.leaseVersion());
                        statement.setTimestamp(7, Timestamp.from(lease.expiresAt()));
                        statement.setTimestamp(8, Timestamp.from(effectiveRenewedAt));
                        statement.setTimestamp(9, Timestamp.from(newExpiry));
                        updated = statement.executeUpdate();
                    }
                }

                RenewResult result;
                if (updated == 1) {
                    StoredEvent current = findStored(connection, lease.eventId()).orElseThrow();
                    long expectedLeaseVersion = Math.addExact(lease.leaseVersion(), 1L);
                    if (!renewedToken.equals(current.leaseToken())
                            || current.leaseVersion() != expectedLeaseVersion
                            || current.event().status() != EventStatus.LEASED
                            || !lease.owner().equals(current.event().leaseOwner())
                            || !newExpiry.equals(current.event().leaseUntil())) {
                        throw new IllegalStateException("Outbox 续租后栅栏状态不一致");
                    }
                    LeaseToken renewedLease = new LeaseToken(
                            lease.eventId(), lease.owner(), renewedToken,
                            newExpiry, effectiveRenewedAt, expectedLeaseVersion);
                    result = new RenewResult(RenewStatus.RENEWED, current.event(), renewedLease);
                    LOGGER.log(Level.FINE, "JDBC Outbox lease 已续租 eventId={0} owner={1}",
                            new Object[]{lease.eventId(), lease.owner()});
                } else {
                    result = classifyRenewFailure(
                            connection, engine, lease, effectiveRenewedAt, newExpiry);
                }
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException failure) {
                rollbackQuietly(connection);
                throw failure instanceof SQLException
                        ? fail("续租 Outbox lease 失败 eventId=" + lease.eventId(),
                                (SQLException) failure)
                        : (RuntimeException) failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException failure) {
            throw fail("创建 Outbox 数据库连接失败", failure);
        }
    }

    @Override
    public PublishResult markPublished(LeaseToken lease, Instant publishedAt) {
        if (engine == JdbcDatabaseEngine.MYSQL) {
            return JdbcMysqlOutboxWorker.markPublished(dataSource, lease, publishedAt);
        }
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Instant effectivePublishedAt = roundedMicrosecondTimestamp(publishedAt);
        try (Connection connection = dataSource.getConnection()) {
            int updated = 0;
            if (hasCanonicalLeaseExpiry(lease)
                    && !effectivePublishedAt.isBefore(lease.claimedAt())) {
                try (PreparedStatement statement = connection.prepareStatement(PUBLISH_SQL)) {
                    statement.setTimestamp(1, Timestamp.from(effectivePublishedAt));
                    statement.setString(2, lease.eventId());
                    statement.setString(3, lease.owner());
                    statement.setString(4, lease.token());
                    statement.setLong(5, lease.leaseVersion());
                    statement.setTimestamp(6, Timestamp.from(lease.expiresAt()));
                    statement.setTimestamp(7, Timestamp.from(effectivePublishedAt));
                    updated = statement.executeUpdate();
                }
            }
            if (updated == 1) {
                OutboxEvent current = find(connection, lease.eventId()).orElseThrow();
                LOGGER.log(Level.INFO, "JDBC Outbox 事件已发布 eventId={0}", lease.eventId());
                return new PublishResult(PublishStatus.PUBLISHED, current);
            }
            return classifyPublishFailure(connection, engine, lease, effectivePublishedAt);
        } catch (SQLException failure) {
            throw fail("确认 Outbox 发布失败 eventId=" + lease.eventId(), failure);
        }
    }

    @Override
    public FailureResult markFailed(LeaseToken lease, Instant failedAt,
                                    Instant nextAvailableAt, String error, int maxAttempts) {
        if (engine == JdbcDatabaseEngine.MYSQL) {
            return JdbcMysqlOutboxWorker.markFailed(
                    dataSource, lease, failedAt, nextAvailableAt, error, maxAttempts);
        }
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
        Instant effectiveFailedAt = roundedMicrosecondTimestamp(failedAt);
        Instant effectiveNextAvailableAt = roundedMicrosecondTimestamp(nextAvailableAt);
        try (Connection connection = dataSource.getConnection()) {
            int updated = 0;
            if (hasCanonicalLeaseExpiry(lease)
                    && !effectiveFailedAt.isBefore(lease.claimedAt())) {
                try (PreparedStatement statement = connection.prepareStatement(FAIL_SQL)) {
                    statement.setInt(1, maxAttempts);
                    statement.setTimestamp(2, Timestamp.from(effectiveNextAvailableAt));
                    statement.setString(3, error);
                    statement.setString(4, lease.eventId());
                    statement.setString(5, lease.owner());
                    statement.setString(6, lease.token());
                    statement.setLong(7, lease.leaseVersion());
                    statement.setTimestamp(8, Timestamp.from(lease.expiresAt()));
                    statement.setTimestamp(9, Timestamp.from(effectiveFailedAt));
                    updated = statement.executeUpdate();
                }
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
            return classifyFailure(connection, engine, lease, effectiveFailedAt);
        } catch (SQLException failure) {
            throw fail("记录 Outbox 失败事件失败 eventId=" + lease.eventId(), failure);
        }
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
     * <p>JSONB 读取后可能改变无意义空白或对象键顺序，不能继续使用 Java 原始
     * 字符串相等判断，否则同一事件在首次写入后重放会被误判为冲突。</p>
     */
    private static boolean sameMessage(Connection connection, OutboxEvent event) throws SQLException {
        return sameMessage(connection, JdbcDatabaseEngine.MYSQL, event);
    }

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

    /**
     * expiresAt 是 LeaseToken 中唯一会与数据库时间列直接比较的时间栅栏。
     * 非微秒规范值若先交给驱动舍入，可能伪装成当前 lease_until，因此必须在
     * 绑定 SQL 前拒绝。claimedAt 只用于操作时间窗口，不擅自扩大为数据库栅栏字段。
     */
    private boolean hasCanonicalLeaseExpiry(LeaseToken lease) {
        return lease.expiresAt().equals(canonicalTimestamp(lease.expiresAt()));
    }

    private Instant canonicalTimestamp(Instant value) {
        return value.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    static OutboxEvent withLease(OutboxEvent event, String owner, java.time.Instant until) {
        return new OutboxEvent(event.eventId(), event.aggregateType(), event.aggregateId(), event.eventType(),
                event.payload(), event.occurredAt(), event.availableAt(), EventStatus.LEASED,
                event.attemptCount(), owner, until, null, event.lastError());
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 驱动把时间戳四舍五入到最近微秒，lease 句柄必须使用相同规则。 */
    private static Instant roundedMicrosecondTimestamp(Instant value) {
        int nanos = value.getNano();
        int roundedMicros = (nanos + 500) / 1_000;
        if (roundedMicros == 1_000_000) {
            return Instant.ofEpochSecond(Math.addExact(value.getEpochSecond(), 1L));
        }
        return Instant.ofEpochSecond(value.getEpochSecond(), roundedMicros * 1_000L);
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
