package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.MeetingHeartbeatStore;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MySQL 5.7 会议心跳的单语句 UPSERT 适配器。
 *
 * <p>首次插入写入 {@code started_at=now,last_active_at=now,heartbeat_count=1}；
 * 主键冲突时只把 {@code last_active_at} 刷新为本次 {@code now}，并让
 * {@code heartbeat_count} 原子加一。{@code started_at} 永不被覆盖，重复心跳
 * 因此只是刷新最后活跃时间，不会改写会议的真正开始事实。</p>
 *
 * <p>实现只使用调用方传入的服务端时钟，不在 SQL 里读取数据库时间；也不持有
 * 连接、事务或后台线程。日志只记录故障类别与 SQLState，不记录会议标识或时间。</p>
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
    public void record(String meetingId, Instant now) {
        MeetingIdPolicy.requireValid(meetingId);
        Objects.requireNonNull(now, "now");
        String sql = UPSERT_SQL.formatted(
                JdbcDatabaseTable.MEETING_HEARTBEAT.physicalName(engine));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            JdbcValues.setInstant(statement, 2, now);
            JdbcValues.setInstant(statement, 3, now);
            int affected = statement.executeUpdate();
            if (affected < 1) {
                throw new IllegalStateException("meeting heartbeat upsert affected no row");
            }
        } catch (SQLException failure) {
            LOGGER.log(Level.WARNING, "会议心跳写入失败 sqlState={0}", failure.getSQLState());
            throw new IllegalStateException("meeting heartbeat write failed", failure);
        }
    }
}
