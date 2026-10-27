package com.czur.reference.application.persistence.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * “Stop 已受理、结果尚未收口”待办的窄 JDBC 原语。
 *
 * <p>正常 Stop 在推进到 {@code STOPPING} 的同一事务写入 {@code STOP_ACCEPTED}
 * 待办，再同步尝试结果收口。收口失败（产物存储、结算或进程中断）时待办保留，
 * 后台扫描按 {@code requested_at} 超时重试同一个 Finalizer；成功写入
 * READY／READY_WITH_WARNINGS 后在收尾事务内删除。冻结用量后长期没有 Stop 的
 * 会议由另一条扫描写入 {@code USAGE_TIMEOUT} 待办，复用同一张表与同一收口入口。</p>
 *
 * <p>本类只打开/使用调用方连接或传入的 DataSource，不创建线程、不调用权益或
 * 产物端口。主键保证同一会议最多一条待办，重复 Stop／重复扫描不会产生第二条
 * 事实；删除在结果事务内完成，避免“结果已 READY 但待办仍留下”的稳定窗口。</p>
 */
final class JdbcClientFinalizationPendingStore {
    /** Stop 受理来源；只用于选择收尾语义，不进入公开响应。 */
    enum Reason {
        /** 客户端已发送 Stop，会议应走普通 READY 结果。 */
        STOP_ACCEPTED,
        /** 客户端冻结采音用量后超时未 Stop，终态按冻结结果能力决定。 */
        USAGE_TIMEOUT
    }

    /** 一条待办；时间均为服务端时钟写入的 UTC。 */
    record Pending(String meetingId, String deploymentScope, Reason reason,
            Instant requestedAt, int attemptCount, Instant lastAttemptAt) {
        Pending {
            Objects.requireNonNull(meetingId, "meetingId");
            Objects.requireNonNull(deploymentScope, "deploymentScope");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(requestedAt, "requestedAt");
            if (attemptCount < 0) {
                throw new IllegalArgumentException("attemptCount must not be negative");
            }
        }
    }

    private static final String COLUMNS = """
            meeting_id, deployment_scope, pending_reason, requested_at,
            attempt_count, last_attempt_at
            """;

    private JdbcClientFinalizationPendingStore() { }

    /**
     * 正常 Stop 事务内登记待办；只有客户端事件会议（存在
     * {@code meeting_client_input} 行）才写入，旧音频会议继续由原收口链处理。
     *
     * @return true 表示本次插入或已有同一会议待办
     */
    static boolean markIfClient(Connection connection, JdbcDatabaseEngine engine,
            String meetingId, Instant requestedAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(requestedAt, "requestedAt");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM "
                        + JdbcDatabaseTable.MEETING_CLIENT_INPUT.physicalName(engine)
                        + " WHERE meeting_id=?")) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return false;
            }
        }
        mark(connection, engine, meetingId, deploymentScope(connection, engine, meetingId),
                Reason.STOP_ACCEPTED, requestedAt);
        return true;
    }

    /** 在调用方事务内登记指定原因的待办；主键冲突按已登记处理。 */
    static void mark(Connection connection, JdbcDatabaseEngine engine, String meetingId,
            String deploymentScope, Reason reason, Instant requestedAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (deploymentScope == null || deploymentScope.isBlank()) {
            throw new IllegalArgumentException("deploymentScope must not be blank");
        }
        String sql = "INSERT INTO "
                + JdbcDatabaseTable.MEETING_FINALIZATION_PENDING.physicalName(engine)
                + " (meeting_id, deployment_scope, pending_reason, requested_at,"
                + " attempt_count, last_attempt_at) VALUES (?, ?, ?, ?, 0, NULL)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            JdbcValues.setText(statement, 2, engine, deploymentScope);
            JdbcValues.setText(statement, 3, engine, reason.name());
            JdbcValues.setInstant(statement, 4, requestedAt);
            try {
                statement.executeUpdate();
            } catch (SQLException duplicate) {
                if (!JdbcSqlErrors.isDuplicate(engine, duplicate)) throw duplicate;
            }
        }
    }

    /** 收尾事务内删除待办；重复删除安全，返回是否确实删除了一行。 */
    static boolean delete(Connection connection, JdbcDatabaseEngine engine, String meetingId)
            throws SQLException {
        String sql = "DELETE FROM "
                + JdbcDatabaseTable.MEETING_FINALIZATION_PENDING.physicalName(engine)
                + " WHERE meeting_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            return statement.executeUpdate() == 1;
        }
    }

    /** 连接级读取；收尾事务用 {@code forUpdate} 与结果写入串行化。 */
    static Optional<Pending> find(Connection connection, JdbcDatabaseEngine engine,
            String meetingId, boolean forUpdate) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM "
                + JdbcDatabaseTable.MEETING_FINALIZATION_PENDING.physicalName(engine)
                + " WHERE meeting_id=?"
                + (forUpdate && engine == JdbcDatabaseEngine.MYSQL ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows, engine)) : Optional.empty();
            }
        }
    }

    /** 独立短读；调用方已确认本类不持有其它池连接。 */
    static Optional<Pending> find(DataSource dataSource, JdbcDatabaseEngine engine,
            String meetingId) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            return find(connection, engine, meetingId, false);
        } catch (SQLException failure) {
            throw new IllegalStateException("pending finalization read unavailable", failure);
        }
    }

    /** 独立短删；成功收口后的防御性清理，重复删除安全。 */
    static void clear(DataSource dataSource, JdbcDatabaseEngine engine, String meetingId) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            delete(connection, engine, meetingId);
        } catch (SQLException failure) {
            throw new IllegalStateException("pending finalization delete unavailable", failure);
        }
    }

    /** 扫描到期的本部署待办；按受理时间从旧到新，避免旧会议长期饥饿。 */
    static List<Pending> findDue(DataSource dataSource, JdbcDatabaseEngine engine,
            String deploymentScope, Instant cutoff, int batchLimit) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(cutoff, "cutoff");
        if (batchLimit <= 0) throw new IllegalArgumentException("batchLimit must be positive");
        String sql = "SELECT " + COLUMNS + " FROM "
                + JdbcDatabaseTable.MEETING_FINALIZATION_PENDING.physicalName(engine)
                + " WHERE deployment_scope=? AND requested_at<=?"
                + " ORDER BY requested_at LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, deploymentScope);
            JdbcValues.setInstant(statement, 2, cutoff);
            statement.setInt(3, batchLimit);
            List<Pending> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(read(rows, engine));
                }
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw new IllegalStateException("pending finalization scan unavailable", failure);
        }
    }

    /** 记录一次失败尝试；不推迟下一次扫描，扫描器按自身周期继续重试。 */
    static void recordAttempt(DataSource dataSource, JdbcDatabaseEngine engine,
            String meetingId, Instant attemptedAt) {
        String sql = "UPDATE "
                + JdbcDatabaseTable.MEETING_FINALIZATION_PENDING.physicalName(engine)
                + " SET attempt_count=attempt_count+1, last_attempt_at=? WHERE meeting_id=?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setInstant(statement, 1, attemptedAt);
            JdbcValues.setText(statement, 2, engine, meetingId);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("pending finalization attempt write unavailable",
                    failure);
        }
    }

    private static String deploymentScope(Connection connection, JdbcDatabaseEngine engine,
            String meetingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT deployment_scope FROM "
                        + JdbcDatabaseTable.MEETING.physicalName(engine)
                        + " WHERE meeting_id=?")) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("meeting disappeared while marking pending");
                }
                return JdbcValues.text(rows, "deployment_scope");
            }
        }
    }

    private static Pending read(ResultSet rows, JdbcDatabaseEngine engine)
            throws SQLException {
        String lastAttempt = "last_attempt_at";
        return new Pending(
                JdbcValues.text(rows, "meeting_id"),
                JdbcValues.text(rows, "deployment_scope"),
                Reason.valueOf(JdbcValues.text(rows, "pending_reason")),
                JdbcValues.instant(rows, "requested_at"),
                rows.getInt("attempt_count"),
                JdbcValues.instant(rows, lastAttempt));
    }
}
