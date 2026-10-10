package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.entitlement.ExpiredReservationStore;
import com.czur.reference.application.port.entitlement.UsageReservation;

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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PostgreSQL V3 到期 Reservation 领取适配器。
 *
 * <p>claim 使用 {@code FOR UPDATE SKIP LOCKED} 按 {@code expires_at,
 * reservation_id} 确定顺序选择单部署范围内的到期未绑定记录。每一行独立生成
 * lease token，并以数据库 {@code lease_version + 1} 作为 fencing 版本；同一
 * worker 批次不共享 token。release 以完整范围、owner、token、version 和
 * 截止时间做条件更新，旧 owner 或过期 token 不能清除新 lease。</p>
 *
 * <p>该类没有调度线程。连接、SQL 或损坏行只抛出不含地址、查询参数和正文的
 * 脱敏异常；周期重试由后续 Worker 负责。</p>
 */
public final class JdbcExpiredReservationStore implements ExpiredReservationStore {
    private static final Logger LOGGER = Logger.getLogger(JdbcExpiredReservationStore.class.getName());

    private static final String CLAIM_SQL = """
            SELECT reservation_id, deployment_scope, subject_scope, grant_id,
                   expected_meeting_id, bound_meeting_id, bound_at, usage_unit,
                   reserved_amount, actual_amount, created_at, expires_at, status,
                   version, committed_at, cancelled_at, cancellation_reason_code,
                   lease_owner, lease_token, lease_until, lease_version
              FROM usage_reservation
             WHERE record_format = 'V3' AND deployment_scope = ?
               AND status = 'RESERVED' AND bound_meeting_id IS NULL
               AND expires_at <= ?
               AND (lease_until IS NULL OR lease_until <= ?)
             ORDER BY expires_at, reservation_id
             FOR UPDATE SKIP LOCKED
             LIMIT ?
            """;

    private static final String CLAIM_UPDATE_SQL = """
            UPDATE usage_reservation
               SET lease_owner = ?, lease_token = ?, lease_until = ?,
                   lease_version = lease_version + 1
             WHERE record_format = 'V3' AND deployment_scope = ?
               AND subject_scope = ? AND reservation_id = ?
               AND status = 'RESERVED' AND bound_meeting_id IS NULL
               AND expires_at <= ? AND (lease_until IS NULL OR lease_until <= ?)
            """;

    private static final String FIND_FOR_RELEASE_SQL = """
            SELECT reservation_id, deployment_scope, subject_scope, grant_id,
                   expected_meeting_id, bound_meeting_id, bound_at, usage_unit,
                   reserved_amount, actual_amount, created_at, expires_at, status,
                   version, committed_at, cancelled_at, cancellation_reason_code,
                   lease_owner, lease_token, lease_until, lease_version
              FROM usage_reservation
             WHERE record_format = 'V3' AND deployment_scope = ?
               AND subject_scope = ? AND reservation_id = ?
             FOR UPDATE
            """;

    private static final String RELEASE_SQL = """
            UPDATE usage_reservation
               SET lease_owner = NULL, lease_token = NULL, lease_until = NULL
             WHERE record_format = 'V3' AND deployment_scope = ?
               AND subject_scope = ? AND reservation_id = ?
               AND status = 'RESERVED' AND bound_meeting_id IS NULL
               AND lease_owner = ? AND lease_token = ? AND lease_version = ?
               AND lease_until > ?
            """;

    private final DataSource dataSource;
    private final com.czur.reference.application.port.entitlement.EntitlementIdGenerator idGenerator;

    public JdbcExpiredReservationStore(
            DataSource dataSource,
            com.czur.reference.application.port.entitlement.EntitlementIdGenerator idGenerator) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        LOGGER.log(Level.INFO, "JDBC 到期 Reservation Store 已创建");
    }

    @Override
    public ClaimBatch claimExpired(ClaimRequest request) {
        Objects.requireNonNull(request, "request");
        /*
         * PostgreSQL 的 TIMESTAMPTZ（以及 pgjdbc 的 Timestamp 绑定）只保留微秒，
         * 并且采用四舍五入而不是简单截断。claim 返回的 LeaseToken 会在后续
         * release/lease-aware cancel 中作为 fencing 条件再次与数据库比较，因此
         * 写入值和返回句柄必须先经过同一套规范化。这里同时规范 now 与 leaseUntil，
         * 避免请求中的纳秒部分只在其中一侧被舍入，造成 ClaimBatch 或 lease 栅栏
         * 的时间不一致。规范化后的 ClaimRequest 也让批次结果明确记录数据库实际
         * 使用的时间边界；普通的业务 cancel 不经过此路径，语义不受影响。
         */
        Instant claimedAt = postgresTimestamp(request.now());
        Instant leaseUntil = postgresTimestamp(request.leaseUntil());
        if (!leaseUntil.isAfter(claimedAt)) {
            throw new IllegalArgumentException(
                    "leaseDuration must advance expiry at PostgreSQL microsecond precision");
        }
        ClaimRequest effectiveRequest = new ClaimRequest(
                request.deploymentScope(), request.leaseOwner(), claimedAt,
                Duration.between(claimedAt, leaseUntil), request.limit());
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<ClaimedReservation> claims = new ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement(CLAIM_SQL)) {
                    select.setString(1, effectiveRequest.deploymentScope());
                    select.setTimestamp(2, Timestamp.from(claimedAt));
                    select.setTimestamp(3, Timestamp.from(claimedAt));
                    select.setInt(4, effectiveRequest.limit());
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next()) {
                            LeaseRow previous = readLeaseRow(rows);
                            String token = requireToken(idGenerator.newLeaseToken());
                            try (PreparedStatement update = connection.prepareStatement(
                                    CLAIM_UPDATE_SQL)) {
                                update.setString(1, effectiveRequest.leaseOwner());
                                update.setString(2, token);
                                update.setTimestamp(3, Timestamp.from(leaseUntil));
                                update.setString(4, effectiveRequest.deploymentScope());
                                update.setString(5, previous.reservation().subjectScope());
                                update.setString(6, previous.reservation().reservationId());
                                update.setTimestamp(7, Timestamp.from(claimedAt));
                                update.setTimestamp(8, Timestamp.from(claimedAt));
                                if (update.executeUpdate() != 1) {
                                    throw new SQLException("expired reservation claim lost race");
                                }
                            }
                            ExpiredReservationStore.LeaseToken lease =
                                    new ExpiredReservationStore.LeaseToken(
                                            previous.reservation().deploymentScope(),
                                            previous.reservation().subjectScope(),
                                            previous.reservation().reservationId(),
                                            effectiveRequest.leaseOwner(), token, claimedAt, leaseUntil,
                                            previous.leaseVersion() + 1);
                            claims.add(new ClaimedReservation(previous.reservation(), lease));
                        }
                    }
                }
                connection.commit();
                LOGGER.log(Level.FINE, "JDBC 到期 Reservation 已领取 count={0}", claims.size());
                return new ClaimBatch(effectiveRequest, claims);
            } catch (JdbcUsageReservationStore.CorruptUsageDataException corruption) {
                rollbackQuietly(connection);
                throw sanitized("领取到期 Reservation 的持久事实损坏");
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection);
                throw sanitized("领取到期 Reservation 失败");
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException failure) {
            throw sanitized("创建到期 Reservation 数据库连接失败");
        }
    }

    @Override
    public ReleaseResult release(LeaseToken lease, Instant releasedAt) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(releasedAt, "releasedAt");
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Optional<LeaseRow> found = findForRelease(connection, lease);
                if (found.isEmpty()) {
                    connection.rollback();
                    return new ReleaseResult(ReleaseStatus.NOT_FOUND, null);
                }
                LeaseRow current = found.get();
                UsageReservation reservation = current.reservation();
                if (reservation.state() != UsageReservation.State.RESERVED
                        || reservation.isBound()) {
                    connection.rollback();
                    return new ReleaseResult(ReleaseStatus.TERMINAL_OR_BOUND, reservation);
                }
                if (!lease.owner().equals(current.leaseOwner())
                        || !lease.token().equals(current.leaseToken())
                        || lease.leaseVersion() != current.leaseVersion()) {
                    connection.rollback();
                    return new ReleaseResult(ReleaseStatus.NOT_OWNER, reservation);
                }
                if (current.leaseUntil() == null || !releasedAt.isBefore(current.leaseUntil())) {
                    connection.rollback();
                    return new ReleaseResult(ReleaseStatus.LEASE_EXPIRED, reservation);
                }
                try (PreparedStatement update = connection.prepareStatement(RELEASE_SQL)) {
                    update.setString(1, lease.deploymentScope());
                    update.setString(2, lease.subjectScope());
                    update.setString(3, lease.reservationId());
                    update.setString(4, lease.owner());
                    update.setString(5, lease.token());
                    update.setLong(6, lease.leaseVersion());
                    update.setTimestamp(7, Timestamp.from(releasedAt));
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return new ReleaseResult(ReleaseStatus.NOT_OWNER, reservation);
                    }
                }
                connection.commit();
                LOGGER.log(Level.FINE, "JDBC 到期 Reservation lease 已释放");
                return new ReleaseResult(ReleaseStatus.RELEASED, reservation);
            } catch (JdbcUsageReservationStore.CorruptUsageDataException corruption) {
                rollbackQuietly(connection);
                throw sanitized("释放到期 Reservation 的持久事实损坏");
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection);
                throw sanitized("释放到期 Reservation 失败");
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException failure) {
            throw sanitized("创建到期 Reservation 数据库连接失败");
        }
    }

    private static Optional<LeaseRow> findForRelease(
            Connection connection, LeaseToken lease) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_FOR_RELEASE_SQL)) {
            statement.setString(1, lease.deploymentScope());
            statement.setString(2, lease.subjectScope());
            statement.setString(3, lease.reservationId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readLeaseRow(rows)) : Optional.empty();
            }
        }
    }

    private static LeaseRow readLeaseRow(ResultSet rows) throws SQLException {
        return new LeaseRow(
                JdbcUsageReservationStore.readReservation(rows),
                rows.getString("lease_owner"), rows.getString("lease_token"),
                nullableInstant(rows, "lease_until"), rows.getLong("lease_version"));
    }

    private static String requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("lease token generator returned blank token");
        }
        return token;
    }

    private static Instant nullableInstant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /**
     * 使用 PostgreSQL/pgjdbc 对 TIMESTAMPTZ 的微秒四舍五入规则规范化时间。
     *
     * <p>不能使用 {@link Instant#truncatedTo(java.time.temporal.TemporalUnit)}：
     * 那会向下截断，并在纳秒部分达到 999999500 时与 pgjdbc 的跨秒进位结果
     * 不一致。LeaseToken 的 expiresAt 是后续 release 与 lease-aware cancel 的
     * 栅栏字段，必须和实际写入数据库的 {@code lease_until} 精确相等。</p>
     */
    private static Instant postgresTimestamp(Instant value) {
        int nanos = value.getNano();
        int roundedMicros = (nanos + 500) / 1_000;
        if (roundedMicros == 1_000_000) {
            return Instant.ofEpochSecond(Math.addExact(value.getEpochSecond(), 1L));
        }
        return Instant.ofEpochSecond(value.getEpochSecond(), roundedMicros * 1_000L);
    }

    private static IllegalStateException sanitized(String message) {
        // 原始 SQLException 可能包含 SQL、URL、参数或数据库产品正文；该适配器
        // 不把 cause 交给调用方，具体诊断只留在受控日志中的固定类别。
        return new IllegalStateException(message);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            LOGGER.log(Level.WARNING, "到期 Reservation 事务回滚失败");
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean previousAutoCommit)
            throws SQLException {
        if (!connection.isClosed()) {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private record LeaseRow(
            UsageReservation reservation,
            String leaseOwner,
            String leaseToken,
            Instant leaseUntil,
            long leaseVersion) {
    }
}
