package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.OutboxStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.czur.reference.application.port.OutboxStore.ClaimedEvent;
import static com.czur.reference.application.port.OutboxStore.FailureResult;
import static com.czur.reference.application.port.OutboxStore.FailureStatus;
import static com.czur.reference.application.port.OutboxStore.LeaseToken;
import static com.czur.reference.application.port.OutboxStore.OutboxEvent;
import static com.czur.reference.application.port.OutboxStore.PublishResult;
import static com.czur.reference.application.port.OutboxStore.PublishStatus;
import static com.czur.reference.application.port.OutboxStore.RenewResult;
import static com.czur.reference.application.port.OutboxStore.RenewStatus;

/**
 * MySQL 5.7 Outbox Worker SQL 适配器。
 *
 * <p>MySQL 5.7 没有 {@code SKIP LOCKED} 和 {@code RETURNING}。领取在事务内用
 * {@code SELECT ... FOR UPDATE} 锁定候选行，再逐行做带 lease 版本和可用时间的
 * 条件 UPDATE；更新失败的行直接跳过，因此并发 Worker 不会共用同一租约。
 * 续租、发布和失败确认同样只依赖条件 UPDATE，不读取未加锁快照后再覆盖。</p>
 *
 * <p>该适配器只改 SQL 和参数绑定，不改变 Outbox 端口的至少一次语义、lease
 * 版本栅栏、失败重试上限或状态分类。时间统一按 UTC 微秒写入 DATETIME(6)。</p>
 */
final class JdbcMysqlOutboxWorker {
    private static final Logger LOGGER = Logger.getLogger(JdbcMysqlOutboxWorker.class.getName());

    private JdbcMysqlOutboxWorker() {
    }

    static List<ClaimedEvent> claim(DataSource dataSource, OutboxStore.ClaimRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.registeredEventTypes().isEmpty()) {
            return List.of();
        }
        Instant claimedAt = mysqlTimestamp(request.now());
        Instant leaseUntil = mysqlTimestamp(request.now().plus(request.leaseDuration()));
        if (!leaseUntil.isAfter(claimedAt)) {
            throw new IllegalArgumentException(
                    "leaseDuration must advance expiry at MySQL microsecond precision");
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<ClaimedEvent> claimed = new ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement(
                        claimSql(request.registeredEventTypes().size()))) {
                    int index = 1;
                    for (String eventType : request.registeredEventTypes()) {
                        JdbcValues.setText(select, index++, JdbcDatabaseEngine.MYSQL, eventType);
                    }
                    JdbcValues.setInstant(select, index++, claimedAt);
                    JdbcValues.setInstant(select, index++, claimedAt);
                    select.setInt(index, request.limit());
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next()) {
                            JdbcOutboxStore.StoredEvent previous =
                                    JdbcOutboxStore.readStoredEvent(rows);
                            long expectedLeaseVersion =
                                    Math.addExact(previous.leaseVersion(), 1L);
                            String tokenText = UUID.randomUUID().toString();
                            try (PreparedStatement update = connection.prepareStatement(
                                    claimUpdateSql())) {
                                update.setString(1, request.leaseOwner());
                                JdbcValues.setInstant(update, 2, leaseUntil);
                                update.setString(3, tokenText);
                                JdbcValues.setText(update, 4, JdbcDatabaseEngine.MYSQL,
                                        previous.event().eventId());
                                JdbcValues.setInstant(update, 5, claimedAt);
                                update.setLong(6, previous.leaseVersion());
                                if (update.executeUpdate() != 1) {
                                    continue;
                                }
                            }
                            OutboxEvent leased = JdbcOutboxStore.withLease(
                                    previous.event(), request.leaseOwner(), leaseUntil);
                            LeaseToken lease = new LeaseToken(
                                    leased.eventId(), request.leaseOwner(), tokenText,
                                    leaseUntil, claimedAt, expectedLeaseVersion);
                            claimed.add(new ClaimedEvent(leased, lease));
                        }
                    }
                }
                connection.commit();
                if (!claimed.isEmpty()) {
                    LOGGER.log(Level.FINE, "JDBC MySQL Outbox 事件已领取 owner={0} count={1}",
                            new Object[]{request.leaseOwner(), claimed.size()});
                }
                return List.copyOf(claimed);
            } catch (RuntimeException | SQLException failure) {
                rollbackQuietly(connection);
                throw failure instanceof SQLException
                        ? fail("领取 MySQL Outbox 事件失败 owner=" + request.leaseOwner(),
                                (SQLException) failure)
                        : (RuntimeException) failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException failure) {
            throw fail("创建 MySQL Outbox 数据库连接失败", failure);
        }
    }

    static RenewResult renew(DataSource dataSource, LeaseToken lease, Instant renewedAt,
                             Duration leaseDuration) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(renewedAt, "renewedAt");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        Instant effectiveRenewedAt = mysqlTimestamp(renewedAt);
        Instant newExpiry = mysqlTimestamp(renewedAt.plus(leaseDuration));
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
                    try (PreparedStatement statement = connection.prepareStatement(renewSql())) {
                        JdbcValues.setInstant(statement, 1, newExpiry);
                        statement.setString(2, renewedToken);
                        JdbcValues.setText(statement, 3, JdbcDatabaseEngine.MYSQL, lease.eventId());
                        statement.setString(4, lease.owner());
                        statement.setString(5, lease.token());
                        statement.setLong(6, lease.leaseVersion());
                        JdbcValues.setInstant(statement, 7, lease.expiresAt());
                        JdbcValues.setInstant(statement, 8, effectiveRenewedAt);
                        JdbcValues.setInstant(statement, 9, newExpiry);
                        updated = statement.executeUpdate();
                    }
                }

                RenewResult result;
                if (updated == 1) {
                    JdbcOutboxStore.StoredEvent current = JdbcOutboxStore.findStored(
                            connection, JdbcDatabaseEngine.MYSQL, lease.eventId(), false)
                            .orElseThrow();
                    long expectedLeaseVersion = Math.addExact(lease.leaseVersion(), 1L);
                    if (!renewedToken.equals(current.leaseToken())
                            || current.leaseVersion() != expectedLeaseVersion
                            || current.event().status() != OutboxStore.EventStatus.LEASED
                            || !lease.owner().equals(current.event().leaseOwner())
                            || !newExpiry.equals(current.event().leaseUntil())) {
                        throw new IllegalStateException("MySQL Outbox 续租后栅栏状态不一致");
                    }
                    LeaseToken renewedLease = new LeaseToken(
                            lease.eventId(), lease.owner(), renewedToken,
                            newExpiry, effectiveRenewedAt, expectedLeaseVersion);
                    result = new RenewResult(RenewStatus.RENEWED, current.event(), renewedLease);
                    LOGGER.log(Level.FINE, "JDBC MySQL Outbox lease 已续租 eventId={0} owner={1}",
                            new Object[]{lease.eventId(), lease.owner()});
                } else {
                    result = JdbcOutboxStore.classifyRenewFailure(
                            connection, JdbcDatabaseEngine.MYSQL, lease,
                            effectiveRenewedAt, newExpiry);
                }
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException failure) {
                rollbackQuietly(connection);
                throw failure instanceof SQLException
                        ? fail("续租 MySQL Outbox lease 失败 eventId=" + lease.eventId(),
                                (SQLException) failure)
                        : (RuntimeException) failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException failure) {
            throw fail("创建 MySQL Outbox 数据库连接失败", failure);
        }
    }

    static PublishResult markPublished(DataSource dataSource, LeaseToken lease,
                                       Instant publishedAt) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Instant effectivePublishedAt = mysqlTimestamp(publishedAt);
        try (Connection connection = dataSource.getConnection()) {
            int updated = 0;
            if (hasCanonicalLeaseExpiry(lease)
                    && !effectivePublishedAt.isBefore(lease.claimedAt())) {
                try (PreparedStatement statement = connection.prepareStatement(publishSql())) {
                    JdbcValues.setInstant(statement, 1, effectivePublishedAt);
                    JdbcValues.setText(statement, 2, JdbcDatabaseEngine.MYSQL, lease.eventId());
                    statement.setString(3, lease.owner());
                    statement.setString(4, lease.token());
                    statement.setLong(5, lease.leaseVersion());
                    JdbcValues.setInstant(statement, 6, lease.expiresAt());
                    JdbcValues.setInstant(statement, 7, effectivePublishedAt);
                    updated = statement.executeUpdate();
                }
            }
            if (updated == 1) {
                OutboxEvent current = JdbcOutboxStore.findStored(
                                connection, JdbcDatabaseEngine.MYSQL, lease.eventId(), false)
                        .orElseThrow().event();
                LOGGER.log(Level.INFO, "JDBC MySQL Outbox 事件已发布 eventId={0}", lease.eventId());
                return new PublishResult(PublishStatus.PUBLISHED, current);
            }
            return JdbcOutboxStore.classifyPublishFailure(
                    connection, JdbcDatabaseEngine.MYSQL, lease, effectivePublishedAt);
        } catch (SQLException failure) {
            throw fail("确认 MySQL Outbox 发布失败 eventId=" + lease.eventId(), failure);
        }
    }

    static FailureResult markFailed(DataSource dataSource, LeaseToken lease, Instant failedAt,
                                    Instant nextAvailableAt, String error, int maxAttempts) {
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
        Instant effectiveFailedAt = mysqlTimestamp(failedAt);
        Instant effectiveNextAvailableAt = mysqlTimestamp(nextAvailableAt);
        try (Connection connection = dataSource.getConnection()) {
            int updated = 0;
            if (hasCanonicalLeaseExpiry(lease)
                    && !effectiveFailedAt.isBefore(lease.claimedAt())) {
                try (PreparedStatement statement = connection.prepareStatement(failSql())) {
                    statement.setInt(1, maxAttempts);
                    JdbcValues.setInstant(statement, 2, effectiveNextAvailableAt);
                    statement.setString(3, error);
                    JdbcValues.setText(statement, 4, JdbcDatabaseEngine.MYSQL, lease.eventId());
                    statement.setString(5, lease.owner());
                    statement.setString(6, lease.token());
                    statement.setLong(7, lease.leaseVersion());
                    JdbcValues.setInstant(statement, 8, lease.expiresAt());
                    JdbcValues.setInstant(statement, 9, effectiveFailedAt);
                    updated = statement.executeUpdate();
                }
            }
            if (updated == 1) {
                OutboxEvent current = JdbcOutboxStore.findStored(
                                connection, JdbcDatabaseEngine.MYSQL, lease.eventId(), false)
                        .orElseThrow().event();
                FailureStatus status = current.status() == OutboxStore.EventStatus.DEAD
                        ? FailureStatus.DEAD : FailureStatus.RETRY_SCHEDULED;
                LOGGER.log(status == FailureStatus.DEAD ? Level.WARNING : Level.INFO,
                        "JDBC MySQL Outbox 事件处理失败 eventId={0} status={1}",
                        new Object[]{lease.eventId(), status});
                return new FailureResult(status, current);
            }
            return JdbcOutboxStore.classifyFailure(
                    connection, JdbcDatabaseEngine.MYSQL, lease, effectiveFailedAt);
        } catch (SQLException failure) {
            throw fail("记录 MySQL Outbox 失败事件失败 eventId=" + lease.eventId(), failure);
        }
    }

    private static String claimSql(int typeCount) {
        StringBuilder placeholders = new StringBuilder();
        for (int index = 0; index < typeCount; index++) {
            if (index > 0) placeholders.append(',');
            placeholders.append('?');
        }
        return "SELECT event_id, aggregate_type, aggregate_id, event_type, payload,"
                + " occurred_at, available_at, status, attempt_count,"
                + " lease_owner, lease_until, lease_token, lease_version, published_at, last_error"
                + " FROM " + table()
                + " WHERE event_type IN (" + placeholders + ")"
                + " AND available_at <= ?"
                + " AND (status = 'PENDING' OR (status = 'LEASED' AND lease_until <= ?))"
                + " ORDER BY available_at, occurred_at, event_id"
                + " LIMIT ? FOR UPDATE";
    }

    private static String claimUpdateSql() {
        return "UPDATE " + table()
                + " SET status = 'LEASED', lease_owner = ?, lease_until = ?,"
                + " lease_token = ?, lease_version = lease_version + 1"
                + " WHERE event_id = ?"
                + " AND (status = 'PENDING' OR (status = 'LEASED' AND lease_until <= ?))"
                + " AND lease_version = ?";
    }

    private static String renewSql() {
        return "UPDATE " + table()
                + " SET lease_until = ?, lease_token = ?, lease_version = lease_version + 1"
                + " WHERE event_id = ? AND status = 'LEASED'"
                + " AND lease_owner = ? AND lease_token = ? AND lease_version = ?"
                + " AND lease_until = ? AND lease_until > ? AND ? > lease_until";
    }

    private static String publishSql() {
        return "UPDATE " + table()
                + " SET status = 'PUBLISHED', published_at = ?,"
                + " lease_owner = NULL, lease_until = NULL, lease_token = NULL,"
                + " last_error = NULL"
                + " WHERE event_id = ? AND status = 'LEASED'"
                + " AND lease_owner = ? AND lease_token = ? AND lease_version = ?"
                + " AND lease_until = ? AND lease_until > ?";
    }

    private static String failSql() {
        return "UPDATE " + table()
                + " SET status = CASE WHEN attempt_count + 1 >= ? THEN 'DEAD' ELSE 'PENDING' END,"
                + " attempt_count = attempt_count + 1,"
                + " available_at = ?, lease_owner = NULL, lease_until = NULL,"
                + " lease_token = NULL, last_error = ?, published_at = NULL"
                + " WHERE event_id = ? AND status = 'LEASED'"
                + " AND lease_owner = ? AND lease_token = ? AND lease_version = ?"
                + " AND lease_until = ? AND lease_until > ?";
    }

    private static String table() {
        return JdbcDatabaseTable.OUTBOX_EVENT.physicalName(JdbcDatabaseEngine.MYSQL);
    }

    private static boolean hasCanonicalLeaseExpiry(LeaseToken lease) {
        return lease.expiresAt().equals(mysqlTimestamp(lease.expiresAt()));
    }

    private static Instant mysqlTimestamp(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
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
}
