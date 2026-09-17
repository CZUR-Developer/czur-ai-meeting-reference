package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.MeetingRepository;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基于 JDBC 的 PostgreSQL 会议仓库参考实现。
 *
 * <p>该类只依赖标准 {@link DataSource} 和 SQL，不依赖 Gateway、Web 框架或
 * PostgreSQL 专有 Java API。独立查询可直接使用本类；会议命令需要跨会议、
 * 幂等和 Outbox 原子提交时，应通过 {@link JdbcMeetingUnitOfWork} 执行。</p>
 *
 * <p>公开领域快照暂时还没有认证主体、Grant 和主 Reservation。这个旧命令兼容
 * 入口只能使用 {@code subject_scope='LEGACY_UNOWNED'}，明确表示普通用户不能认领
 * 或据此获得访问权；它不是 reference-dev 认证创建路径。后续认证编排必须通过
 * 带真实主体和预占绑定的专用事务入口写入，不能复用该占位值。</p>
 */
public final class JdbcMeetingRepository implements MeetingRepository {
    private static final Logger LOGGER = Logger.getLogger(JdbcMeetingRepository.class.getName());
    private static final String SUBJECT_SCOPE = "LEGACY_UNOWNED";

    private static final String FIND_SQL = """
            SELECT meeting_id, deployment_scope, client_session_id, profile_id,
                   profile_revision, state, created_at, updated_at
              FROM meeting
             WHERE meeting_id = ?
            """;

    private static final String FIND_FOR_UPDATE_SQL = FIND_SQL + " FOR UPDATE";

    private static final String FIND_BY_SCOPE_SQL = """
            SELECT meeting_id, deployment_scope, client_session_id, profile_id,
                   profile_revision, state, created_at, updated_at
              FROM meeting
             WHERE deployment_scope = ? AND client_session_id = ?
             ORDER BY created_at, meeting_id
             LIMIT 1
            """;

    private static final String SAVE_SQL = """
            INSERT INTO meeting (
                meeting_id, deployment_scope, client_session_id, subject_scope,
                profile_id, profile_revision, state, aggregate_version,
                created_at, updated_at, metadata)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, '{}'::jsonb)
            ON CONFLICT (meeting_id) DO UPDATE SET
                deployment_scope = EXCLUDED.deployment_scope,
                client_session_id = EXCLUDED.client_session_id,
                profile_id = EXCLUDED.profile_id,
                profile_revision = EXCLUDED.profile_revision,
                state = EXCLUDED.state,
                updated_at = EXCLUDED.updated_at
            """;

    private final DataSource dataSource;

    public JdbcMeetingRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<MeetingAggregate.MeetingSnapshot> find(String meetingId) {
        requireText("meetingId", meetingId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
            statement.setString(1, meetingId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readSnapshot(resultSet)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failure("查询会议快照失败 meetingId=" + meetingId, failure);
        }
    }

    @Override
    public Optional<MeetingAggregate.MeetingSnapshot> findByDeploymentScopeAndClientSessionId(
            String deploymentScope,
            String clientSessionId) {
        requireText("deploymentScope", deploymentScope);
        requireText("clientSessionId", clientSessionId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_BY_SCOPE_SQL)) {
            statement.setString(1, deploymentScope);
            statement.setString(2, clientSessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readSnapshot(resultSet)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failure("按范围查询会议失败 deploymentScope=" + deploymentScope, failure);
        }
    }

    @Override
    public void save(MeetingAggregate.MeetingSnapshot meeting) {
        Objects.requireNonNull(meeting, "meeting");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SAVE_SQL)) {
            statement.setString(1, meeting.meetingId());
            statement.setString(2, meeting.deploymentScope());
            statement.setString(3, meeting.clientSessionId());
            statement.setString(4, SUBJECT_SCOPE);
            statement.setString(5, meeting.profileId());
            statement.setInt(6, meeting.profileRevision());
            statement.setString(7, meeting.state().name());
            statement.setTimestamp(8, Timestamp.from(meeting.createdAt()));
            statement.setTimestamp(9, Timestamp.from(meeting.updatedAt()));
            statement.executeUpdate();
            LOGGER.log(Level.FINE, "JDBC 会议快照已保存 meetingId={0} state={1}",
                    new Object[]{meeting.meetingId(), meeting.state()});
        } catch (SQLException failure) {
            LOGGER.log(Level.SEVERE, "JDBC 会议快照保存失败 meetingId=" + meeting.meetingId(), failure);
            throw failure("保存会议快照失败 meetingId=" + meeting.meetingId(), failure);
        }
    }

    /**
     * 在调用方已经开启的事务连接上读取快照；该方法不会提交、回滚或关闭连接。
     * 事务 Unit of Work 通过它保证会议与幂等、Outbox 使用同一数据库连接。
     */
    static Optional<MeetingAggregate.MeetingSnapshot> find(Connection connection, String meetingId)
            throws SQLException {
        return find(connection, FIND_SQL, meetingId);
    }

    /**
     * 在调用方事务连接上读取并锁定会议行。
     *
     * <p>会议停止命令必须先获得该行锁，再判断是否首次进入 STOPPING，避免
     * 多个服务实例同时基于旧 RUNNING 快照生成第二条停止事件。锁由外层工作
     * 单元提交或回滚时释放。</p>
     */
    static Optional<MeetingAggregate.MeetingSnapshot> findForUpdate(
            Connection connection, String meetingId) throws SQLException {
        return find(connection, FIND_FOR_UPDATE_SQL, meetingId);
    }

    private static Optional<MeetingAggregate.MeetingSnapshot> find(
            Connection connection, String sql, String meetingId) throws SQLException {
        requireText("meetingId", meetingId);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, meetingId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readSnapshot(resultSet)) : Optional.empty();
            }
        }
    }

    /** 在调用方事务连接上按部署范围和客户端会话读取快照。 */
    static Optional<MeetingAggregate.MeetingSnapshot> findByDeploymentScopeAndClientSessionId(
            Connection connection, String deploymentScope, String clientSessionId) throws SQLException {
        requireText("deploymentScope", deploymentScope);
        requireText("clientSessionId", clientSessionId);
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_SCOPE_SQL)) {
            statement.setString(1, deploymentScope);
            statement.setString(2, clientSessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readSnapshot(resultSet)) : Optional.empty();
            }
        }
    }

    /** 在调用方事务连接上保存快照；该方法不会自行提交事务。 */
    static void save(Connection connection, MeetingAggregate.MeetingSnapshot meeting) throws SQLException {
        Objects.requireNonNull(meeting, "meeting");
        try (PreparedStatement statement = connection.prepareStatement(SAVE_SQL)) {
            statement.setString(1, meeting.meetingId());
            statement.setString(2, meeting.deploymentScope());
            statement.setString(3, meeting.clientSessionId());
            statement.setString(4, SUBJECT_SCOPE);
            statement.setString(5, meeting.profileId());
            statement.setInt(6, meeting.profileRevision());
            statement.setString(7, meeting.state().name());
            statement.setTimestamp(8, Timestamp.from(meeting.createdAt()));
            statement.setTimestamp(9, Timestamp.from(meeting.updatedAt()));
            statement.executeUpdate();
        }
    }

    private static MeetingAggregate.MeetingSnapshot readSnapshot(ResultSet resultSet) throws SQLException {
        return new MeetingAggregate.MeetingSnapshot(
                resultSet.getString("meeting_id"),
                resultSet.getString("deployment_scope"),
                resultSet.getString("client_session_id"),
                resultSet.getString("profile_id"),
                resultSet.getInt("profile_revision"),
                MeetingState.valueOf(resultSet.getString("state")),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static RuntimeException failure(String message, SQLException cause) {
        return new IllegalStateException(message, cause);
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
