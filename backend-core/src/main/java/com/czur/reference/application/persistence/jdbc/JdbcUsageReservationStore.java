package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.entitlement.UsageActual;
import com.czur.reference.application.port.entitlement.ExpiredReservationStore;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * V003 {@code usage_reservation}/{@code usage_operation} 的事务内 JDBC 原语。
 *
 * <p>该类型不获取连接、不提交事务，也不对外提供权益业务结果。调用方必须为
 * 每个公开操作建立明确事务，并在 operation 唯一键竞争失败时完整回滚候选
 * Reservation。所有 Reservation 查询和变更都同时带 deployment、subject、
 * reservationId 和 {@code record_format='V3'}，绝不先按全局 ID 探测真实范围。</p>
 *
 * <p>数据库 NUMERIC 只能通过 {@link BigDecimal#longValueExact()} 进入公共 long
 * 契约。小数或溢出表示持久事实损坏，不能截断、四舍五入或伪装为合法用量。</p>
 */
final class JdbcUsageReservationStore {
    private static final String FIND_OPERATION_SQL = """
            SELECT request_digest, reservation_id, result_body::text
              FROM usage_operation
             WHERE deployment_scope = ? AND subject_scope = ?
               AND operation_scope = ? AND idempotency_key = ?
            """;

    /**
     * 通过 Meeting 持久保存的主 Reservation 反查首次 reserve 结果。
     *
     * <p>V003 没有把 reserve 的幂等键复制到 Meeting；稳定关联链是
     * {@code meeting.usage_reservation_id -> usage_operation.reservation_id}。
     * 正常创建事务只会产生一条 RESERVE 行。这里最多读取两条，若同一个
     * Reservation 出现多个首次 reserve 赢家则按损坏事实关闭式失败，绝不按
     * created_at 或字典序挑选一个“看起来最早”的结果。</p>
     */
    private static final String FIND_RESERVE_OPERATION_BY_RESERVATION_SQL = """
            SELECT operation_scope, idempotency_key, request_digest,
                   reservation_id, result_body::text
              FROM usage_operation
             WHERE deployment_scope = ? AND subject_scope = ?
               AND reservation_id = ? AND operation_scope = 'usage.reserve.v1'
             ORDER BY idempotency_key
             LIMIT 2
            """;

    private static final String INSERT_OPERATION_SQL = """
            INSERT INTO usage_operation (
                deployment_scope, subject_scope, operation_scope, idempotency_key,
                request_digest, reservation_id, result_body, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (deployment_scope, subject_scope, operation_scope, idempotency_key)
            DO NOTHING
            """;

    private static final String INSERT_RESERVATION_SQL = """
            INSERT INTO usage_reservation (
                reservation_id, meeting_id, usage_type, status, quantity, provider_ref,
                created_at, updated_at, record_format, deployment_scope, subject_scope,
                grant_id, expected_meeting_id, bound_meeting_id, bound_at, usage_unit,
                reserved_amount, actual_amount, expires_at, version, committed_at,
                cancelled_at, cancellation_reason_code, lease_owner, lease_token,
                lease_until, lease_version)
            VALUES (?, NULL, NULL, 'RESERVED', NULL, NULL,
                    ?, ?, 'V3', ?, ?, ?, ?, NULL, NULL, ?, ?, NULL, ?, 0,
                    NULL, NULL, NULL, NULL, NULL, NULL, 0)
            """;

    private static final String LOCK_RESERVATION_SQL = """
            SELECT reservation_id, deployment_scope, subject_scope, grant_id,
                   expected_meeting_id, bound_meeting_id, bound_at, usage_unit,
                   reserved_amount, actual_amount, created_at, expires_at, status,
                   version, committed_at, cancelled_at, cancellation_reason_code,
                   lease_owner, lease_token, lease_until, lease_version
              FROM usage_reservation
             WHERE deployment_scope = ? AND subject_scope = ? AND reservation_id = ?
               AND record_format = 'V3'
             FOR UPDATE
            """;

    private static final String FIND_RESERVATION_SQL = """
            SELECT reservation_id, deployment_scope, subject_scope, grant_id,
                   expected_meeting_id, bound_meeting_id, bound_at, usage_unit,
                   reserved_amount, actual_amount, created_at, expires_at, status,
                   version, committed_at, cancelled_at, cancellation_reason_code
              FROM usage_reservation
             WHERE deployment_scope = ? AND subject_scope = ? AND reservation_id = ?
               AND record_format = 'V3'
            """;

    private static final String COMMIT_SQL = """
            UPDATE usage_reservation
               SET status = 'COMMITTED', actual_amount = ?, committed_at = ?,
                   version = ?, updated_at = ?, lease_owner = NULL,
                   lease_token = NULL, lease_until = NULL
             WHERE deployment_scope = ? AND subject_scope = ? AND reservation_id = ?
               AND record_format = 'V3' AND status = 'RESERVED'
               AND bound_meeting_id IS NOT NULL AND version = ?
            """;

    private static final String CANCEL_SQL = """
            UPDATE usage_reservation
               SET status = 'CANCELLED', cancelled_at = ?, cancellation_reason_code = ?,
                   version = ?, updated_at = ?, lease_owner = NULL,
                   lease_token = NULL, lease_until = NULL
             WHERE deployment_scope = ? AND subject_scope = ? AND reservation_id = ?
               AND record_format = 'V3' AND status = 'RESERVED'
               AND bound_meeting_id IS NULL AND expected_meeting_id = ? AND version = ?
            """;

    private static final String LEASE_AWARE_CANCEL_SQL = """
            UPDATE usage_reservation
               SET status = 'CANCELLED', cancelled_at = ?, cancellation_reason_code = ?,
                   version = ?, updated_at = ?, lease_owner = NULL,
                   lease_token = NULL, lease_until = NULL
             WHERE deployment_scope = ? AND subject_scope = ? AND reservation_id = ?
               AND record_format = 'V3' AND status = 'RESERVED'
               AND bound_meeting_id IS NULL AND expected_meeting_id = ? AND version = ?
               AND lease_owner = ? AND lease_token = ? AND lease_until = ?
               AND lease_version = ? AND ? < lease_until
            """;

    private static final String BIND_FOR_OWNED_MEETING_SQL = """
            UPDATE usage_reservation
               SET bound_meeting_id = ?, bound_at = ?, version = ?, updated_at = ?,
                   lease_owner = NULL, lease_token = NULL, lease_until = NULL
             WHERE deployment_scope = ? AND subject_scope = ? AND reservation_id = ?
               AND record_format = 'V3' AND status = 'RESERVED'
               AND bound_meeting_id IS NULL AND bound_at IS NULL
               AND expected_meeting_id = ? AND grant_id = ? AND version = ?
               AND expires_at > ?
               AND (lease_until IS NULL OR lease_until <= ?)
            """;

    private JdbcUsageReservationStore() {
    }

    /** 在完整四元组范围内读取原始幂等行；正文只在摘要相同时由 Provider 解码。 */
    static Optional<UsageOperationRow> findOperation(
            Connection connection,
            String deploymentScope,
            String subjectScope,
            String operationScope,
            String idempotencyKey) throws SQLException {
        return findOperation(connection, JdbcDatabaseEngine.POSTGRESQL,
                deploymentScope, subjectScope, operationScope, idempotencyKey);
    }

    static Optional<UsageOperationRow> findOperation(Connection connection, JdbcDatabaseEngine engine,
            String deploymentScope, String subjectScope, String operationScope, String idempotencyKey)
            throws SQLException {
        return findOperationUsing(connection, engine, deploymentScope, subjectScope,
                operationScope, idempotencyKey, false);
    }

    /** 写事务已获得 Reservation/同键序列化锁后使用，避免 RR 等待后读取旧 snapshot。 */
    static Optional<UsageOperationRow> findOperationCurrent(Connection connection, JdbcDatabaseEngine engine,
            String deploymentScope, String subjectScope, String operationScope, String idempotencyKey)
            throws SQLException {
        return findOperationUsing(connection, engine, deploymentScope, subjectScope,
                operationScope, idempotencyKey, engine == JdbcDatabaseEngine.MYSQL);
    }

    private static Optional<UsageOperationRow> findOperationUsing(Connection connection, JdbcDatabaseEngine engine,
            String deploymentScope, String subjectScope, String operationScope, String idempotencyKey,
            boolean currentRead) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        String sql = engine == JdbcDatabaseEngine.POSTGRESQL ? FIND_OPERATION_SQL
                : "SELECT request_digest, reservation_id, result_body FROM "
                + JdbcDatabaseTable.USAGE_OPERATION.physicalName(engine)
                + " WHERE deployment_scope = ? AND subject_scope = ?"
                + " AND operation_scope = ? AND idempotency_key = ?"
                + (currentRead ? " LOCK IN SHARE MODE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, deploymentScope);
            JdbcValues.setText(statement, 2, engine, subjectScope);
            JdbcValues.setText(statement, 3, engine, operationScope);
            JdbcValues.setText(statement, 4, engine, idempotencyKey);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                String digest = rows.getString("request_digest");
                String reservationId = JdbcValues.text(rows, "reservation_id");
                String resultBody = rows.getString("result_body");
                if (digest == null || digest.isBlank()
                        || reservationId == null || reservationId.isBlank()
                        || resultBody == null) {
                    throw new CorruptUsageDataException("usage operation fields are incomplete");
                }
                return Optional.of(new UsageOperationRow(digest, reservationId, resultBody));
            }
        }
    }

    /**
     * 在完整部署、主体和 Reservation 范围中读取唯一首次 reserve 结果。
     *
     * <p>该原语不锁定或改变任何行，也不提交、回滚或关闭连接。调用方必须先
     * 锁定并验证 Meeting，再使用返回的幂等键/摘要严格解码 v2 结果正文。其它
     * 部署或主体统一表现为不存在；重复 reserve 行属于不可恢复的数据损坏。</p>
     */
    static Optional<ReserveOperationRow> findReserveOperationByReservation(
            Connection connection,
            String deploymentScope,
            String subjectScope,
            String reservationId) throws SQLException {
        return findReserveOperationByReservation(connection, JdbcDatabaseEngine.POSTGRESQL,
                deploymentScope, subjectScope, reservationId);
    }

    static Optional<ReserveOperationRow> findReserveOperationByReservation(
            Connection connection, JdbcDatabaseEngine engine, String deploymentScope,
            String subjectScope, String reservationId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        try (PreparedStatement statement = connection.prepareStatement(
                engine == JdbcDatabaseEngine.POSTGRESQL ? FIND_RESERVE_OPERATION_BY_RESERVATION_SQL
                        : "SELECT operation_scope, idempotency_key, request_digest, reservation_id, result_body FROM "
                        + JdbcDatabaseTable.USAGE_OPERATION.physicalName(engine)
                        + " WHERE deployment_scope = ? AND subject_scope = ? AND reservation_id = ?"
                        + " AND operation_scope = 'usage.reserve.v1' ORDER BY idempotency_key LIMIT 2"
                        + " LOCK IN SHARE MODE")) {
            JdbcValues.setText(statement, 1, engine, deploymentScope);
            JdbcValues.setText(statement, 2, engine, subjectScope);
            JdbcValues.setText(statement, 3, engine, reservationId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                ReserveOperationRow result = new ReserveOperationRow(
                        requireText(rows, "operation_scope"),
                        requireText(rows, "idempotency_key"),
                        requireText(rows, "request_digest"),
                        requireText(rows, "reservation_id"),
                        requireText(rows, "result_body"));
                if (rows.next()) {
                    throw new CorruptUsageDataException(
                            "reservation has multiple reserve operation facts");
                }
                return Optional.of(result);
            }
        }
    }

    /**
     * 插入首次完整 Reservation 快照；ID 碰撞交给数据库唯一约束拒绝。
     *
     * <p>首次结果 JSON 保留策略返回的原始时间。这里明确写入其微秒截断值，
     * 与 {@link OwnedMeetingCreationFacts} 的持久事实投影一致，避免 PostgreSQL
     * 对纳秒值向上舍入后跨微秒边界，导致正常 Create 被误判为事实损坏。</p>
     */
    static void insertReservation(Connection connection, UsageReservation reservation)
            throws SQLException {
        insertReservation(connection, JdbcDatabaseEngine.POSTGRESQL, reservation);
    }

    static void insertReservation(Connection connection, JdbcDatabaseEngine engine, UsageReservation reservation)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(reservation, "reservation");
        if (reservation.state() != UsageReservation.State.RESERVED
                || reservation.isBound()
                || reservation.version() != 0) {
            throw new IllegalArgumentException("only initial unbound reservation can be inserted");
        }
        String sql = engine == JdbcDatabaseEngine.POSTGRESQL ? INSERT_RESERVATION_SQL : """
                INSERT INTO %s (
                    reservation_id, meeting_id, usage_type, status, quantity, provider_ref,
                    created_at, updated_at, record_format, deployment_scope, subject_scope,
                    grant_id, expected_meeting_id, bound_meeting_id, bound_at, usage_unit,
                    reserved_amount, actual_amount, expires_at, version, committed_at,
                    cancelled_at, cancellation_reason_code, lease_owner, lease_token,
                    lease_until, lease_version)
                VALUES (?, NULL, NULL, 'RESERVED', NULL, NULL,
                        ?, ?, 'V3', ?, ?, ?, ?, NULL, NULL, ?, ?, NULL, ?, 0,
                        NULL, NULL, NULL, NULL, NULL, NULL, 0)
                """.formatted(JdbcDatabaseTable.USAGE_RESERVATION.physicalName(engine));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, reservation.reservationId());
            JdbcValues.setInstant(statement, 2, reservation.createdAt());
            JdbcValues.setInstant(statement, 3, reservation.createdAt());
            JdbcValues.setText(statement, 4, engine, reservation.deploymentScope());
            JdbcValues.setText(statement, 5, engine, reservation.subjectScope());
            JdbcValues.setText(statement, 6, engine, reservation.grantId());
            JdbcValues.setText(statement, 7, engine, reservation.expectedMeetingId());
            statement.setString(8, reservation.reservedUsage().unit().name());
            statement.setBigDecimal(9, BigDecimal.valueOf(reservation.reservedUsage().amount()));
            JdbcValues.setInstant(statement, 10, reservation.expiresAt());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("usage reservation insert count is not one");
            }
        }
    }

    /**
     * 保存首次稳定结果。返回 false 表示四元组已有赢家；调用方必须回滚当前
     * 整个事务后再读取赢家，不能只删除候选 Reservation。
     */
    static boolean insertOperation(
            Connection connection, UsageOperationResult result, Instant createdAt)
            throws SQLException {
        return insertOperation(connection, JdbcDatabaseEngine.POSTGRESQL, result, createdAt);
    }

    static boolean insertOperation(Connection connection, JdbcDatabaseEngine engine,
            UsageOperationResult result, Instant createdAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(createdAt, "createdAt");
        String sql = engine == JdbcDatabaseEngine.POSTGRESQL ? INSERT_OPERATION_SQL : "INSERT INTO "
                + JdbcDatabaseTable.USAGE_OPERATION.physicalName(engine)
                + " (deployment_scope, subject_scope, operation_scope, idempotency_key,"
                + " request_digest, reservation_id, result_body, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, result.deploymentScope());
            JdbcValues.setText(statement, 2, engine, result.subjectScope());
            JdbcValues.setText(statement, 3, engine, result.operationScope());
            JdbcValues.setText(statement, 4, engine, result.idempotencyKey());
            statement.setString(5, result.requestDigest());
            JdbcValues.setText(statement, 6, engine, result.reservation().reservationId());
            statement.setString(7, UsageOperationResultJson.encode(result));
            JdbcValues.setInstant(statement, 8, createdAt);
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            // 此表唯一轴仅为 operation 四元组；外键/截断错误不能当成重放。
            if (engine == JdbcDatabaseEngine.MYSQL && JdbcSqlErrors.isDuplicate(engine, failure)) {
                return false;
            }
            throw failure;
        }
    }

    /** 锁定完整范围内的 V3 Reservation；其它范围和历史格式统一表现为不存在。 */
    static Optional<UsageReservation> lockReservation(
            Connection connection,
            String deploymentScope,
            String subjectScope,
            String reservationId) throws SQLException {
        return lockReservation(connection, JdbcDatabaseEngine.POSTGRESQL,
                deploymentScope, subjectScope, reservationId);
    }

    static Optional<UsageReservation> lockReservation(Connection connection, JdbcDatabaseEngine engine,
            String deploymentScope, String subjectScope, String reservationId) throws SQLException {
        return lockReservationForOwnedCreation(
                connection, engine, deploymentScope, subjectScope, reservationId)
                .map(LockedReservation::reservation);
    }

    /**
     * 在完整部署、主体和 Reservation ID 范围内非锁定读取 V3 事实。
     *
     * <p>本原语只执行 {@code SELECT}，不使用 {@code FOR UPDATE}，也不
     * 获取、提交、回滚或关闭连接。它为事后恢复核对提供当前快照；
     * 其它范围和历史格式统一表现为不存在，避免跨主体探测。</p>
     */
    static Optional<UsageReservation> findReservation(
            Connection connection,
            String deploymentScope,
            String subjectScope,
            String reservationId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        try (PreparedStatement statement = connection.prepareStatement(FIND_RESERVATION_SQL)) {
            statement.setString(1, deploymentScope);
            statement.setString(2, subjectScope);
            statement.setString(3, reservationId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readReservation(rows)) : Optional.empty();
            }
        }
    }

    /**
     * 锁定 owned 会议创建所需的完整 Reservation 与清理 lease 视图。
     *
     * <p>普通权益提交和取消只关心 Reservation 状态；会议创建还必须在读取
     * {@code bindAt} 后明确判断清理 Worker 的 lease 是否仍然有效。把 lease
     * 和 Reservation 放在同一个 {@code FOR UPDATE} 结果中，可避免先读状态、
     * 再用 {@code bind=false} 猜测失败原因。owner/token 只用于校验持久事实
     * 完整性，不会离开 JDBC 包，也不会进入日志或返回值。</p>
     */
    static Optional<LockedReservation> lockReservationForOwnedCreation(
            Connection connection,
            String deploymentScope,
            String subjectScope,
            String reservationId) throws SQLException {
        return lockReservationForOwnedCreation(connection, JdbcDatabaseEngine.POSTGRESQL,
                deploymentScope, subjectScope, reservationId);
    }

    static Optional<LockedReservation> lockReservationForOwnedCreation(
            Connection connection, JdbcDatabaseEngine engine, String deploymentScope,
            String subjectScope, String reservationId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        String sql = engine == JdbcDatabaseEngine.POSTGRESQL ? LOCK_RESERVATION_SQL : """
                SELECT reservation_id, deployment_scope, subject_scope, grant_id,
                       expected_meeting_id, bound_meeting_id, bound_at, usage_unit,
                       reserved_amount, actual_amount, created_at, expires_at, status,
                       version, committed_at, cancelled_at, cancellation_reason_code,
                       lease_owner, lease_token, lease_until, lease_version
                  FROM %s
                 WHERE deployment_scope = ? AND subject_scope = ? AND reservation_id = ?
                   AND record_format = 'V3' FOR UPDATE
                """.formatted(JdbcDatabaseTable.USAGE_RESERVATION.physicalName(engine));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, deploymentScope);
            JdbcValues.setText(statement, 2, engine, subjectScope);
            JdbcValues.setText(statement, 3, engine, reservationId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readLockedReservation(rows)) : Optional.empty();
            }
        }
    }

    /** 使用状态、绑定和 expectedVersion 条件原子提交最终用量。 */
    static boolean commit(
            Connection connection,
            UsageReservation previous,
            UsageReservation committed) throws SQLException {
        return commit(connection, JdbcDatabaseEngine.POSTGRESQL, previous, committed);
    }

    static boolean commit(Connection connection, JdbcDatabaseEngine engine,
            UsageReservation previous, UsageReservation committed) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(committed, "committed");
        if (committed.state() != UsageReservation.State.COMMITTED) {
            throw new IllegalArgumentException("committed snapshot is required");
        }
        String sql = engine == JdbcDatabaseEngine.POSTGRESQL ? COMMIT_SQL : "UPDATE "
                + JdbcDatabaseTable.USAGE_RESERVATION.physicalName(engine)
                + " SET status = 'COMMITTED', actual_amount = ?, committed_at = ?, version = ?, updated_at = ?,"
                + " lease_owner = NULL, lease_token = NULL, lease_until = NULL"
                + " WHERE deployment_scope = ? AND subject_scope = ? AND reservation_id = ?"
                + " AND record_format = 'V3' AND status = 'RESERVED' AND bound_meeting_id IS NOT NULL AND version = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBigDecimal(1, BigDecimal.valueOf(committed.actualUsage().amount()));
            JdbcValues.setInstant(statement, 2, committed.committedAt());
            statement.setLong(3, committed.version());
            JdbcValues.setInstant(statement, 4, committed.committedAt());
            JdbcValues.setText(statement, 5, engine, previous.deploymentScope());
            JdbcValues.setText(statement, 6, engine, previous.subjectScope());
            JdbcValues.setText(statement, 7, engine, previous.reservationId());
            statement.setLong(8, previous.version());
            return statement.executeUpdate() == 1;
        }
    }

    /** 使用范围、预期会议和 expectedVersion 条件原子取消未绑定预占。 */
    static boolean cancel(
            Connection connection,
            UsageReservation previous,
            UsageReservation cancelled) throws SQLException {
        return cancel(connection, JdbcDatabaseEngine.POSTGRESQL, previous, cancelled);
    }

    static boolean cancel(Connection connection, JdbcDatabaseEngine engine,
            UsageReservation previous, UsageReservation cancelled) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(cancelled, "cancelled");
        if (cancelled.state() != UsageReservation.State.CANCELLED) {
            throw new IllegalArgumentException("cancelled snapshot is required");
        }
        String sql = engine == JdbcDatabaseEngine.POSTGRESQL ? CANCEL_SQL : "UPDATE "
                + JdbcDatabaseTable.USAGE_RESERVATION.physicalName(engine)
                + " SET status = 'CANCELLED', cancelled_at = ?, cancellation_reason_code = ?, version = ?, updated_at = ?,"
                + " lease_owner = NULL, lease_token = NULL, lease_until = NULL"
                + " WHERE deployment_scope = ? AND subject_scope = ? AND reservation_id = ?"
                + " AND record_format = 'V3' AND status = 'RESERVED' AND bound_meeting_id IS NULL"
                + " AND expected_meeting_id = ? AND version = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setInstant(statement, 1, cancelled.cancelledAt());
            statement.setString(2, cancelled.cancellationReasonCode());
            statement.setLong(3, cancelled.version());
            JdbcValues.setInstant(statement, 4, cancelled.cancelledAt());
            JdbcValues.setText(statement, 5, engine, previous.deploymentScope());
            JdbcValues.setText(statement, 6, engine, previous.subjectScope());
            JdbcValues.setText(statement, 7, engine, previous.reservationId());
            JdbcValues.setText(statement, 8, engine, previous.expectedMeetingId());
            statement.setLong(9, previous.version());
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * 使用清理 Worker 的完整 lease 栅栏原子取消 Reservation。
     *
     * <p>普通 cancel 原语不能用于孤儿清理，因为它只校验业务版本；本原语
     * 还校验 owner、token、leaseVersion、数据库中的 lease 截止时间和取消时刻。
     * 更新成功后同时清空 lease，但保留单调递增的 Reservation 业务 version。
     * 调用方必须在同一事务中随后写入 usage_operation，并负责提交或回滚整个事务。</p>
     */
    static boolean cancelWithLease(
            Connection connection,
            UsageReservation previous,
            UsageReservation cancelled,
            ExpiredReservationStore.LeaseToken lease,
            Instant cancelledAt) throws SQLException {
        return cancelWithLease(connection, JdbcDatabaseEngine.POSTGRESQL,
                previous, cancelled, lease, cancelledAt);
    }

    static boolean cancelWithLease(
            Connection connection,
            JdbcDatabaseEngine engine,
            UsageReservation previous,
            UsageReservation cancelled,
            ExpiredReservationStore.LeaseToken lease,
            Instant cancelledAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(cancelledAt, "cancelledAt");
        if (cancelled.state() != UsageReservation.State.CANCELLED) {
            throw new IllegalArgumentException("cancelled snapshot is required");
        }
        if (!previous.deploymentScope().equals(lease.deploymentScope())
                || !previous.subjectScope().equals(lease.subjectScope())
                || !previous.reservationId().equals(lease.reservationId())) {
            throw new IllegalArgumentException("lease scope does not match reservation");
        }
        if (!cancelledAt.isBefore(lease.expiresAt())) {
            return false;
        }
        String sql = engine == JdbcDatabaseEngine.POSTGRESQL ? LEASE_AWARE_CANCEL_SQL
                : "UPDATE " + JdbcDatabaseTable.USAGE_RESERVATION.physicalName(engine)
                + " SET status = 'CANCELLED', cancelled_at = ?, cancellation_reason_code = ?,"
                + " version = ?, updated_at = ?, lease_owner = NULL,"
                + " lease_token = NULL, lease_until = NULL"
                + " WHERE deployment_scope = ? AND subject_scope = ? AND reservation_id = ?"
                + " AND record_format = 'V3' AND status = 'RESERVED'"
                + " AND bound_meeting_id IS NULL AND expected_meeting_id = ? AND version = ?"
                + " AND lease_owner = ? AND lease_token = ? AND lease_until = ?"
                + " AND lease_version = ? AND ? < lease_until";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (engine == JdbcDatabaseEngine.MYSQL) {
                JdbcValues.setInstant(statement, 1, cancelled.cancelledAt());
                statement.setString(2, cancelled.cancellationReasonCode());
                statement.setLong(3, cancelled.version());
                JdbcValues.setInstant(statement, 4, cancelled.cancelledAt());
                JdbcValues.setText(statement, 5, engine, previous.deploymentScope());
                JdbcValues.setText(statement, 6, engine, previous.subjectScope());
                JdbcValues.setText(statement, 7, engine, previous.reservationId());
                statement.setString(8, previous.expectedMeetingId());
                statement.setLong(9, previous.version());
                statement.setString(10, lease.owner());
                statement.setString(11, lease.token());
                JdbcValues.setInstant(statement, 12, lease.expiresAt());
                statement.setLong(13, lease.leaseVersion());
                JdbcValues.setInstant(statement, 14, cancelledAt);
            } else {
                statement.setTimestamp(1, Timestamp.from(cancelled.cancelledAt()));
                statement.setString(2, cancelled.cancellationReasonCode());
                statement.setLong(3, cancelled.version());
                statement.setTimestamp(4, Timestamp.from(cancelled.cancelledAt()));
                statement.setString(5, previous.deploymentScope());
                statement.setString(6, previous.subjectScope());
                statement.setString(7, previous.reservationId());
                statement.setString(8, previous.expectedMeetingId());
                statement.setLong(9, previous.version());
                statement.setString(10, lease.owner());
                statement.setString(11, lease.token());
                statement.setTimestamp(12, Timestamp.from(lease.expiresAt()));
                statement.setLong(13, lease.leaseVersion());
                statement.setTimestamp(14, Timestamp.from(cancelledAt));
            }
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * 在调用方事务连接上把尚未消费的 V3 Reservation 绑定到预期会议。
     *
     * <p>该操作是 5B2 owned 创建事务使用的 compare-and-set 原语：更新条件
     * 同时校验部署、主体、Reservation ID、expectedMeetingId、Grant、状态和
     * expectedVersion。{@code bindAt} 必须严格早于 {@code expires_at}；恰好
     * 等于截止时刻也会失败。尚未到期的清理 lease 会阻止绑定，已到期 lease
     * 允许创建路径接管，并在成功时清空 owner/token/until，但保留单调递增的
     * leaseVersion 作为 fencing 历史。</p>
     *
     * <p>方法不获取连接、不提交、不回滚，也不插入 Meeting。返回 false 只表示
     * CAS 条件未命中，调用方必须在同一锁定事务中读取事实后映射稳定错误，不能
     * 仅凭 false 猜测 Reservation 是否存在。</p>
     */
    static boolean bindForOwnedMeeting(
            Connection connection,
            UsageReservation reservation,
            Instant bindAt) throws SQLException {
        return bindForOwnedMeeting(connection, JdbcDatabaseEngine.POSTGRESQL, reservation, bindAt);
    }

    static boolean bindForOwnedMeeting(Connection connection, JdbcDatabaseEngine engine,
            UsageReservation reservation, Instant bindAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(bindAt, "bindAt");
        if (reservation.state() != UsageReservation.State.RESERVED
                || reservation.isBound()) {
            throw new IllegalArgumentException(
                    "initial unbound RESERVED snapshot is required");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                engine == JdbcDatabaseEngine.POSTGRESQL ? BIND_FOR_OWNED_MEETING_SQL : "UPDATE "
                        + JdbcDatabaseTable.USAGE_RESERVATION.physicalName(engine)
                        + " SET bound_meeting_id = ?, bound_at = ?, version = ?, updated_at = ?,"
                        + " lease_owner = NULL, lease_token = NULL, lease_until = NULL"
                        + " WHERE deployment_scope = ? AND subject_scope = ? AND reservation_id = ?"
                        + " AND record_format = 'V3' AND status = 'RESERVED' AND bound_meeting_id IS NULL AND bound_at IS NULL"
                        + " AND expected_meeting_id = ? AND grant_id = ? AND version = ? AND expires_at > ?"
                        + " AND (lease_until IS NULL OR lease_until <= ?)")) {
            JdbcValues.setText(statement, 1, engine, reservation.expectedMeetingId());
            JdbcValues.setInstant(statement, 2, bindAt);
            statement.setLong(3, reservation.version() + 1);
            JdbcValues.setInstant(statement, 4, bindAt);
            JdbcValues.setText(statement, 5, engine, reservation.deploymentScope());
            JdbcValues.setText(statement, 6, engine, reservation.subjectScope());
            JdbcValues.setText(statement, 7, engine, reservation.reservationId());
            JdbcValues.setText(statement, 8, engine, reservation.expectedMeetingId());
            JdbcValues.setText(statement, 9, engine, reservation.grantId());
            statement.setLong(10, reservation.version());
            JdbcValues.setInstant(statement, 11, bindAt);
            JdbcValues.setInstant(statement, 12, bindAt);
            return statement.executeUpdate() == 1;
        }
    }

    /** 从当前 ResultSet 行恢复完整 V3 Reservation，并执行领域构造器二次校验。 */
    static UsageReservation readReservation(ResultSet rows) throws SQLException {
        try {
            UsageUnit unit = UsageUnit.valueOf(requireText(rows, "usage_unit"));
            UsageEstimate reserved = new UsageEstimate(
                    unit, exactLong(rows.getBigDecimal("reserved_amount"), "reserved_amount"));
            BigDecimal actualAmount = rows.getBigDecimal("actual_amount");
            UsageActual actual = actualAmount == null
                    ? null : new UsageActual(unit, exactLong(actualAmount, "actual_amount"));
            return new UsageReservation(
                    requireText(rows, "reservation_id"),
                    requireText(rows, "deployment_scope"),
                    requireText(rows, "subject_scope"),
                    requireText(rows, "grant_id"),
                    requireText(rows, "expected_meeting_id"),
                    rows.getString("bound_meeting_id"),
                    nullableInstant(rows, "bound_at"),
                    reserved,
                    actual,
                    requireInstant(rows, "created_at"),
                    requireInstant(rows, "expires_at"),
                    UsageReservation.State.valueOf(requireText(rows, "status")),
                    rows.getLong("version"),
                    nullableInstant(rows, "committed_at"),
                    nullableInstant(rows, "cancelled_at"),
                    rows.getString("cancellation_reason_code"));
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new CorruptUsageDataException("usage reservation row is invalid", invalid);
        }
    }

    private static LockedReservation readLockedReservation(ResultSet rows) throws SQLException {
        UsageReservation reservation = readReservation(rows);
        String leaseOwner = rows.getString("lease_owner");
        String leaseToken = rows.getString("lease_token");
        Instant leaseUntil = nullableInstant(rows, "lease_until");
        long leaseVersion = rows.getLong("lease_version");
        boolean noLease = leaseOwner == null && leaseToken == null && leaseUntil == null;
        boolean completeLease = leaseOwner != null && !leaseOwner.isBlank()
                && leaseToken != null && !leaseToken.isBlank()
                && leaseUntil != null && leaseVersion > 0;
        if (!noLease && !completeLease) {
            throw new CorruptUsageDataException("usage reservation lease facts are incomplete");
        }
        return new LockedReservation(
                reservation, leaseOwner, leaseToken, leaseUntil, leaseVersion);
    }

    static long exactLong(BigDecimal value, String field) {
        if (value == null) {
            throw new CorruptUsageDataException("numeric field is null: " + field);
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException invalid) {
            throw new CorruptUsageDataException("numeric field is not an exact long: " + field,
                    invalid);
        }
    }

    private static String requireText(ResultSet rows, String column) throws SQLException {
        String value = JdbcValues.text(rows, column);
        if (value == null || value.isBlank()) {
            throw new CorruptUsageDataException("text field is blank: " + column);
        }
        return value;
    }

    private static Instant requireInstant(ResultSet rows, String column) throws SQLException {
        Instant value = JdbcValues.instant(rows, column);
        if (value == null) {
            throw new CorruptUsageDataException("time field is null: " + column);
        }
        return value;
    }

    private static Instant nullableInstant(ResultSet rows, String column) throws SQLException {
        return JdbcValues.instant(rows, column);
    }

    /** usage_operation 中尚未解码的摘要与稳定结果正文。 */
    record UsageOperationRow(
            String requestDigest,
            String reservationId,
            String resultBody) {
        UsageOperationRow {
            Objects.requireNonNull(requestDigest, "requestDigest");
            Objects.requireNonNull(reservationId, "reservationId");
            Objects.requireNonNull(resultBody, "resultBody");
        }
    }

    /**
     * 由 Reservation 稳定关联得到的首次 reserve 原始事实。
     *
     * <p>正文只允许交给严格 codec；record 不覆盖 {@code toString()} 是安全的，
     * 因为该 package-private 类型不得离开 JDBC 包，也不得直接进入日志。调用者
     * 仍禁止记录实例或其中任一字段。</p>
     */
    record ReserveOperationRow(
            String operationScope,
            String idempotencyKey,
            String requestDigest,
            String reservationId,
            String resultBody) {
        ReserveOperationRow {
            Objects.requireNonNull(operationScope, "operationScope");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(requestDigest, "requestDigest");
            Objects.requireNonNull(reservationId, "reservationId");
            Objects.requireNonNull(resultBody, "resultBody");
        }

        /** 防止未来调试日志误输出幂等键、摘要或完整授权结果。 */
        @Override
        public String toString() {
            return "ReserveOperationRow[redacted]";
        }
    }

    /**
     * owned 创建事务持有行锁期间观察到的稳定视图。
     *
     * <p>{@code leaseUntil} 为空表示没有清理 lease；等于判断时刻表示 lease
     * 已到期，只有严格晚于判断时刻才阻止 bind，与 SQL CAS 条件保持一致。</p>
     */
    record LockedReservation(
            UsageReservation reservation,
            String leaseOwner,
            String leaseToken,
            Instant leaseUntil,
            long leaseVersion) {
        LockedReservation {
            Objects.requireNonNull(reservation, "reservation");
        }

        boolean hasActiveLeaseAt(Instant instant) {
            Objects.requireNonNull(instant, "instant");
            return leaseUntil != null && leaseUntil.isAfter(instant);
        }
    }

    /** 数据库行或 JSON 违反冻结契约；不得按暂时不可用自动重试。 */
    static final class CorruptUsageDataException extends IllegalStateException {
        CorruptUsageDataException(String message) {
            super(message);
        }

        CorruptUsageDataException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
