package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.domain.meeting.MeetingState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 弃场/启动失败收口决定的窄 JDBC 原语。
 *
 * <p>决定先于结算落库：扫描器在一个短事务里锁定会议、复核候选条件并插入本行，
 * 事务提交后才在连接外调用 {@code EntitlementProvider}。本行存在就表示会议
 * 不可再活跃，心跳与客户端输入必须拒绝；结算失败时保留本行，下一轮只重试
 * 结算，成功后再写结果/终态并删除本行。主键保证同一会议只有一条决定。</p>
 *
 * <p>本类不打开连接、不提交事务、不调用权益端口；所有方法都要求调用方持有
 * 同一事务连接。{@link Decision} 只包含收口需要的冻结事实，不携带心跳正文。</p>
 */
final class JdbcMeetingAbandonmentStore {
    /** 无心跳的启动失败：零用量结算后取消；心跳过期：按冻结时长结算后收成警告终态。 */
    enum Kind { NEVER_STARTED, ABANDONED }

    /** DECIDED 仍待权益结算；SETTLED 只差结果/终态写入。 */
    enum Settlement { DECIDED, SETTLED }

    private static final String COLUMNS = """
            meeting_id, decision_kind, deployment_scope, subject_scope, target_state,
            decided_at, started_at, last_active_at, settle_duration_ms,
            usage_reservation_id, settlement_state, settled_at
            """;

    private JdbcMeetingAbandonmentStore() { }

    /**
     * 一条已落库的收口决定。
     *
     * <p>{@code usageReservationId} 可空：只有 legacy 未绑定权益的会议没有预占，
     * 这类决定在落库时就视为已结算。{@code settleDurationMs} 与
     * {@code startedAt/lastActiveAt} 在落决定时冻结，重试不得重算。</p>
     */
    record Decision(
            String meetingId,
            Kind kind,
            String deploymentScope,
            String subjectScope,
            MeetingState targetState,
            Instant decidedAt,
            Instant startedAt,
            Instant lastActiveAt,
            long settleDurationMs,
            String usageReservationId,
            Settlement settlement,
            Instant settledAt) {
        Decision {
            Objects.requireNonNull(meetingId, "meetingId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(deploymentScope, "deploymentScope");
            Objects.requireNonNull(subjectScope, "subjectScope");
            Objects.requireNonNull(targetState, "targetState");
            Objects.requireNonNull(decidedAt, "decidedAt");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(lastActiveAt, "lastActiveAt");
            Objects.requireNonNull(settlement, "settlement");
            MeetingState requiredTarget = kind == Kind.NEVER_STARTED
                    ? MeetingState.CANCELLED : MeetingState.READY_WITH_WARNINGS;
            if (targetState != requiredTarget) {
                throw new IllegalArgumentException(
                        "decision kind " + kind + " must target " + requiredTarget);
            }
            if (settleDurationMs < 0) {
                throw new IllegalArgumentException("settleDurationMs must not be negative");
            }
            if (lastActiveAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("lastActiveAt cannot be before startedAt");
            }
            if (decidedAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("decidedAt cannot be before startedAt");
            }
            if ((settlement == Settlement.SETTLED) != (settledAt != null)) {
                throw new IllegalArgumentException(
                        "settledAt must be present exactly when settlement is SETTLED");
            }
        }

        boolean needsSettlement() {
            return settlement == Settlement.DECIDED;
        }
    }

    /** 收口完成后终态；用于扫描未完成决定。 */
    static MeetingState targetState(Kind kind) {
        return kind == Kind.NEVER_STARTED
                ? MeetingState.CANCELLED : MeetingState.READY_WITH_WARNINGS;
    }

    /** 在调用方事务内按主键读取；锁读用于收尾事务的二次确认。 */
    static Optional<Decision> find(Connection connection, JdbcDatabaseEngine engine,
            String meetingId, boolean forUpdate) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(engine, "engine");
        String sql = "SELECT " + COLUMNS + " FROM "
                + JdbcDatabaseTable.MEETING_ABANDONMENT.physicalName(engine)
                + " WHERE meeting_id=?" + (forUpdate && engine == JdbcDatabaseEngine.MYSQL
                        ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows, engine)) : Optional.empty();
            }
        }
    }

    /**
     * 只判断是否存在决定；输入/开始等热路径用它拒绝已收口会议。
     *
     * <p>MySQL 使用当前读（{@code FOR UPDATE}）：调用方通常已持有会议父行锁，
     * 这里再按同一锁顺序读决定行，避免 RR 快照漏掉刚提交的决定。这些调用方
     * 不会插入决定行，因此间隙锁只会让决定插入短暂等待，不构成死锁环。</p>
     */
    static boolean exists(Connection connection, JdbcDatabaseEngine engine, String meetingId)
            throws SQLException {
        return exists(connection, engine, meetingId, true);
    }

    /**
     * 决定写入事务内部的“是否已有决定”检查使用非锁定读：调用方已持有会议父行
     * 锁，决定只可能在该锁之前提交，一致性读即可看到；避免两个不同会议的写入方
     * 在空决定行上各持间隙锁后互相死锁。
     */
    static boolean exists(Connection connection, JdbcDatabaseEngine engine, String meetingId,
            boolean forUpdate) throws SQLException {
        String sql = "SELECT 1 FROM "
                + JdbcDatabaseTable.MEETING_ABANDONMENT.physicalName(engine)
                + " WHERE meeting_id=?"
                + (forUpdate && engine == JdbcDatabaseEngine.MYSQL ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** 在调用方事务内插入决定；主键冲突直接抛 SQLException，绝不覆盖已有决定。 */
    static void insert(Connection connection, JdbcDatabaseEngine engine, Decision decision)
            throws SQLException {
        Objects.requireNonNull(decision, "decision");
        String sql = "INSERT INTO " + JdbcDatabaseTable.MEETING_ABANDONMENT.physicalName(engine)
                + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, decision.meetingId());
            JdbcValues.setText(statement, 2, engine, decision.kind().name());
            JdbcValues.setText(statement, 3, engine, decision.deploymentScope());
            JdbcValues.setText(statement, 4, engine, decision.subjectScope());
            JdbcValues.setText(statement, 5, engine, decision.targetState().name());
            JdbcValues.setInstant(statement, 6, decision.decidedAt());
            JdbcValues.setInstant(statement, 7, decision.startedAt());
            JdbcValues.setInstant(statement, 8, decision.lastActiveAt());
            statement.setLong(9, decision.settleDurationMs());
            JdbcValues.setText(statement, 10, engine, decision.usageReservationId());
            JdbcValues.setText(statement, 11, engine, decision.settlement().name());
            JdbcValues.setInstant(statement, 12, decision.settledAt());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("abandonment decision insert did not create one row");
            }
        }
    }

    /**
     * 权益结算成功后单独提交的标记；同一决定只允许 DECIDED→SETTLED。
     *
     * @return true 表示本次调用完成状态前进；false 表示已被另一轮标记
     */
    static boolean markSettled(Connection connection, JdbcDatabaseEngine engine,
            String meetingId, Instant settledAt) throws SQLException {
        Objects.requireNonNull(settledAt, "settledAt");
        String sql = "UPDATE " + JdbcDatabaseTable.MEETING_ABANDONMENT.physicalName(engine)
                + " SET settlement_state=?, settled_at=? WHERE meeting_id=? AND settlement_state=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, Settlement.SETTLED.name());
            JdbcValues.setInstant(statement, 2, settledAt);
            JdbcValues.setText(statement, 3, engine, meetingId);
            JdbcValues.setText(statement, 4, engine, Settlement.DECIDED.name());
            return statement.executeUpdate() == 1;
        }
    }

    /** 结果/终态写完后删除决定；重复删除安全，返回是否确实删除了一行。 */
    static boolean delete(Connection connection, JdbcDatabaseEngine engine, String meetingId)
            throws SQLException {
        String sql = "DELETE FROM " + JdbcDatabaseTable.MEETING_ABANDONMENT.physicalName(engine)
                + " WHERE meeting_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            return statement.executeUpdate() == 1;
        }
    }

    private static Decision read(ResultSet rows, JdbcDatabaseEngine engine)
            throws SQLException {
        String rawReservation = JdbcValues.text(rows, "usage_reservation_id");
        return new Decision(
                JdbcValues.text(rows, "meeting_id"),
                Kind.valueOf(JdbcValues.text(rows, "decision_kind")),
                JdbcValues.text(rows, "deployment_scope"),
                JdbcValues.text(rows, "subject_scope"),
                MeetingState.valueOf(JdbcValues.text(rows, "target_state")),
                JdbcValues.instant(rows, "decided_at"),
                JdbcValues.instant(rows, "started_at"),
                JdbcValues.instant(rows, "last_active_at"),
                rows.getLong("settle_duration_ms"),
                rawReservation,
                Settlement.valueOf(JdbcValues.text(rows, "settlement_state")),
                JdbcValues.instant(rows, "settled_at"));
    }
}
