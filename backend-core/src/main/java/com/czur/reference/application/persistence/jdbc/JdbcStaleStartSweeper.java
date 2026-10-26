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
    /** 启动阶段的会议超过这个时长仍没进入 RUNNING，视为启动失败。 */
    public static final Duration DEFAULT_START_TIMEOUT = Duration.ofMinutes(15);
    public static final int DEFAULT_BATCH_LIMIT = 50;
    private static final String CANCEL_REASON = "STALE_START";

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

    /** 一次扫描；返回取消的会议数与释放的预占数。 */
    public Result runOnce() {
        Instant now = clock.instant();
        Instant threshold = now.minus(startTimeout);
        String meetingTable = database.table(JdbcDatabaseTable.MEETING);
        String reservationTable = database.table(JdbcDatabaseTable.USAGE_RESERVATION);
        String select = "SELECT meeting_id, usage_reservation_id FROM " + meetingTable
                + " WHERE deployment_scope = ? AND state IN ('CREATED','STARTING')"
                + " AND updated_at <= ? ORDER BY updated_at LIMIT ? FOR UPDATE";
        int cancelled = 0;
        int released = 0;
        try (Connection connection = database.dataSource().getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<String[]> rows = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(select)) {
                    JdbcValues.setText(statement, 1, engine, deploymentScope);
                    JdbcValues.setInstant(statement, 2, threshold);
                    statement.setInt(3, batchLimit);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            rows.add(new String[]{result.getString(1), result.getString(2)});
                        }
                    }
                }
                for (String[] row : rows) {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE " + meetingTable + " SET state = 'CANCELLED', updated_at = ?,"
                                    + " aggregate_version = aggregate_version + 1"
                                    + " WHERE meeting_id = ? AND state IN ('CREATED','STARTING')")) {
                        JdbcValues.setInstant(update, 1, now);
                        JdbcValues.setText(update, 2, engine, row[0]);
                        if (update.executeUpdate() == 1) {
                            cancelled++;
                        }
                    }
                    if (row[1] != null && !row[1].isBlank()) {
                        try (PreparedStatement release = connection.prepareStatement(
                                "UPDATE " + reservationTable + " SET status = 'CANCELLED',"
                                        + " cancelled_at = ?, cancellation_reason_code = ?,"
                                        + " updated_at = ?, version = version + 1"
                                        + " WHERE reservation_id = ? AND status = 'RESERVED'")) {
                            JdbcValues.setInstant(release, 1, now);
                            release.setString(2, CANCEL_REASON);
                            JdbcValues.setInstant(release, 3, now);
                            JdbcValues.setText(release, 4, engine, row[1]);
                            if (release.executeUpdate() == 1) {
                                released++;
                            }
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
        return new Result(cancelled, released);
    }

    /** 单次扫描结果。 */
    public record Result(int cancelledMeetings, int releasedReservations) {
    }
}
