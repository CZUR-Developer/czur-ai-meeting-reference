package com.czur.reference.application.persistence.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 把"建了会议但一直没开始"的会议收口为 CANCELLED，并释放它绑定的预占。
 *
 * <p>启动失败发生在客户端（模型连接、Adapter 或开流），后端只看到 CREATED／STARTING。
 * 过期预占清理只处理尚未绑定会议的预占，因此这类会议会一直留着；本扫描补上这一种情况。
 * 只处理 {@code CREATED}／{@code STARTING}：进入 RUNNING 之后的会议可能正在正常进行，
 * 不在这里按时间猜。</p>
 *
 * <p>扫描在单个事务内 {@code FOR UPDATE} 领取候选，逐条更新；重复执行只会重新扫描还在
 * 目标状态的会议，已取消的不会再次命中。</p>
 */
public final class JdbcStaleStartSweeper {
    /**
     * 判定窗口：超过参考最大会议时长（60 分钟）且从未产生任何客户端事件，才视为启动失败。
     * 正常会议在整个过程中一直是 STARTING，所以不能用"状态 + 时间"直接判定。
     */
    public static final Duration DEFAULT_START_TIMEOUT = Duration.ofMinutes(60);
    public static final int DEFAULT_BATCH_LIMIT = 50;

    private final JdbcDatabaseContext database;
    private final JdbcDatabaseEngine engine;
    private final String deploymentScope;
    private final Clock clock;
    private final Duration startTimeout;
    private final int batchLimit;

    public JdbcStaleStartSweeper(JdbcDatabaseContext database, String deploymentScope,
            Clock clock) {
        this(database, deploymentScope, clock, DEFAULT_START_TIMEOUT, DEFAULT_BATCH_LIMIT);
    }

    public JdbcStaleStartSweeper(JdbcDatabaseContext database, String deploymentScope,
            Clock clock, Duration startTimeout, int batchLimit) {
        this.database = Objects.requireNonNull(database, "database");
        this.engine = database.engine();
        if (deploymentScope == null || deploymentScope.isBlank()) {
            throw new IllegalArgumentException("deploymentScope");
        }
        this.deploymentScope = deploymentScope;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startTimeout = Objects.requireNonNull(startTimeout, "startTimeout");
        this.batchLimit = batchLimit;
        if (startTimeout.isNegative() || startTimeout.isZero()) {
            throw new IllegalArgumentException("startTimeout");
        }
        if (batchLimit <= 0) {
            throw new IllegalArgumentException("batchLimit");
        }
    }

    /**
     * 一次扫描；只取消从未收到客户端输入的启动失败会议。
     *
     * <p>预占不动：领域规定 {@code CANCELLED} 不允许保留会议绑定，而已绑定预占不在
     * 现有取消／清理流程的合法转移里。这里只把会议置为终态，避免留下"永远开始不了"
     * 的记录；预占结算交给后续正式的失败结算流程，而不是绕过 Provider 直接改状态。</p>
     */
    public Result runOnce() {
        Instant now = clock.instant();
        Instant threshold = now.minus(startTimeout);
        String meetingTable = database.table(JdbcDatabaseTable.MEETING);
        // 正常会议从开始到结束一直是 STARTING（start 之后不再推进状态，字幕写入也不碰
        // meeting 行），而且 startClient 就会写一条 client input 行。因此判定依据是
        // “超过最大会议时长 + 从未产生任何客户端事件”：出过字幕的会一律不碰。
        String select = "SELECT m.meeting_id FROM " + meetingTable + " m"
                + " WHERE m.deployment_scope = ? AND m.state IN ('CREATED','STARTING')"
                + " AND m.updated_at <= ?"
                + " AND NOT EXISTS (SELECT 1 FROM " + database.table(JdbcDatabaseTable.MEETING_CLIENT_EVENT)
                + " e WHERE e.meeting_id = m.meeting_id)"
                + " ORDER BY m.updated_at LIMIT ? FOR UPDATE";
        int cancelled = 0;
        try (Connection connection = database.dataSource().getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<String> rows = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(select)) {
                    JdbcValues.setText(statement, 1, engine, deploymentScope);
                    JdbcValues.setInstant(statement, 2, threshold);
                    statement.setInt(3, batchLimit);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            rows.add(result.getString(1));
                        }
                    }
                }
                for (String meetingId : rows) {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE " + meetingTable + " SET state = 'CANCELLED', updated_at = ?,"
                                    + " aggregate_version = aggregate_version + 1"
                                    + " WHERE meeting_id = ? AND state IN ('CREATED','STARTING')")) {
                        JdbcValues.setInstant(update, 1, now);
                        JdbcValues.setText(update, 2, engine, meetingId);
                        if (update.executeUpdate() == 1) {
                            cancelled++;
                        }
                    }
                }
                connection.commit();
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("stale start sweep failed", failure);
        }
        return new Result(cancelled);
    }

    /** 单次扫描结果。 */
    public record Result(int cancelledMeetings) {
    }
}
