package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.MeetingHeartbeatStore;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MySQL 5.7 会议心跳的短事务适配器。
 *
 * <p>先锁定会议父行并复核 {@code STARTING/RUNNING} 与“没有弃场决定”，再执行
 * 心跳 UPSERT。首次插入写入 {@code started_at=now,last_active_at=now,
 * heartbeat_count=1}；主键冲突时只把 {@code last_active_at} 刷新为本次
 * {@code now}，并让 {@code heartbeat_count} 原子加一。{@code started_at} 永不
 * 被覆盖，重复心跳因此只是刷新最后活跃时间，不会改写会议的真正开始事实。</p>
 *
 * <p>锁顺序与弃场决定事务一致（先会议父行、后决定行），因此扫描器落决定时
 * 心跳要么在决定前完整提交，要么在决定后看到 {@code STOPPING}/决定而拒绝；
 * 不会出现“服务端已弃场、心跳又把会议记活”的竞态，也不会与决定插入互相死锁。
 * 会议不可活跃时返回 false。</p>
 *
 * <p>实现只使用调用方传入的服务端时钟，不在 SQL 里读取数据库时间；连接在方法
 * 结束前归还。日志只记录故障类别与 SQLState，不记录会议标识或时间。</p>
 */
public final class JdbcMeetingHeartbeatStore implements MeetingHeartbeatStore {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcMeetingHeartbeatStore.class.getName());

    // MySQL 5.7 在 ON DUPLICATE KEY UPDATE 中使用 VALUES(col) 引用本次候选值；
    // 不更新 started_at，也不把计数改成常量。
    private static final String UPSERT_SQL = """
            INSERT INTO %s (meeting_id, started_at, last_active_at, heartbeat_count)
            VALUES (?, ?, ?, 1)
            ON DUPLICATE KEY UPDATE
                last_active_at = VALUES(last_active_at),
                heartbeat_count = heartbeat_count + 1
            """;

    private final DataSource dataSource;
    private final JdbcDatabaseEngine engine;

    /** 复用唯一数据库 Context 的池与引擎；不另建连接池。 */
    public JdbcMeetingHeartbeatStore(JdbcDatabaseContext database) {
        this(Objects.requireNonNull(database, "database").dataSource(), database.engine());
    }

    /** 供测试和宿主复用已装配的池；引擎必须与 URL 一致。 */
    public JdbcMeetingHeartbeatStore(DataSource dataSource, JdbcDatabaseEngine engine) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public boolean record(String meetingId, Instant now) {
        MeetingIdPolicy.requireValid(meetingId);
        Objects.requireNonNull(now, "now");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String state = lockMeetingState(connection, meetingId);
                if (!"STARTING".equals(state) && !"RUNNING".equals(state)) {
                    connection.rollback();
                    LOGGER.log(Level.FINE, "会议心跳未被接受：会议不存在或已不可活跃");
                    return false;
                }
                if (abandonmentDecided(connection, meetingId)) {
                    connection.rollback();
                    LOGGER.log(Level.FINE, "会议心跳未被接受：已落弃场决定");
                    return false;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        UPSERT_SQL.formatted(
                                JdbcDatabaseTable.MEETING_HEARTBEAT.physicalName(engine)))) {
                    JdbcValues.setText(statement, 1, engine, meetingId);
                    // 首次插入的 started_at 与 last_active_at 都用本次服务端时间。
                    JdbcValues.setInstant(statement, 2, now);
                    JdbcValues.setInstant(statement, 3, now);
                    // 1=首次插入，2=命中主键并刷新；计数每次加一，不会出现“值未变”的 0。
                    if (statement.executeUpdate() < 1) {
                        connection.rollback();
                        throw new IllegalStateException("meeting heartbeat upsert affected no row");
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection);
                throw failure instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException("meeting heartbeat write failed", failure);
            }
        } catch (SQLException failure) {
            LOGGER.log(Level.WARNING, "会议心跳写入失败 sqlState={0}", failure.getSQLState());
            throw new IllegalStateException("meeting heartbeat write failed", failure);
        }
    }

    /** 锁定会议父行；返回当前状态，会议不存在时返回 null。 */
    private String lockMeetingState(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state FROM " + JdbcDatabaseTable.MEETING.physicalName(engine)
                        + " WHERE meeting_id=? FOR UPDATE")) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? JdbcValues.text(rows, "state") : null;
            }
        }
    }

    /** 会议行锁内读取决定行；决定写入方同样先锁会议父行，二者不会乱序。 */
    private boolean abandonmentDecided(Connection connection, String meetingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + JdbcDatabaseTable.MEETING_ABANDONMENT.physicalName(engine)
                        + " WHERE meeting_id=? FOR UPDATE")) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 保留原始失败原因。
        }
    }
}
