package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.MeetingRepository;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingOwnership;
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
 * <p>读取同时兼容 V001/V002 与 V003：查询通过 {@code to_jsonb(meeting)} 读取
 * 可选列，旧表缺少归属列时明确恢复为 {@code LEGACY_UNOWNED}，不会猜测真实主体。
 * 当前公开 {@code save} 只允许 legacy SQL，且不引用 V003 列。owned 快照只有读取
 * 映射；写入必须等待 5B 的 connection-scoped 原子 bind 事务入口，不能通过普通
 * Repository 绕开 Reservation 的锁定、校验和版本更新。</p>
 */
public final class JdbcMeetingRepository implements MeetingRepository {
    private static final Logger LOGGER = Logger.getLogger(JdbcMeetingRepository.class.getName());
    private static final String FIND_SQL = """
            SELECT stored.meeting_id, stored.deployment_scope, stored.client_session_id,
                   stored.subject_scope, stored.profile_id, stored.profile_revision,
                   stored.state, stored.created_at, stored.updated_at,
                   jsonb_exists(to_jsonb(stored), 'grant_id') AS ownership_columns_present,
                   to_jsonb(stored) ->> 'grant_id' AS grant_id,
                   (to_jsonb(stored) ->> 'grant_expires_at')::timestamptz AS grant_expires_at,
                   to_jsonb(stored) ->> 'usage_reservation_id' AS usage_reservation_id
              FROM meeting AS stored
             WHERE stored.meeting_id = ?
            """;

    private static final String FIND_FOR_UPDATE_SQL = FIND_SQL + " FOR UPDATE";

    private static final String FIND_BY_SCOPE_SQL = """
            SELECT stored.meeting_id, stored.deployment_scope, stored.client_session_id,
                   stored.subject_scope, stored.profile_id, stored.profile_revision,
                   stored.state, stored.created_at, stored.updated_at,
                   jsonb_exists(to_jsonb(stored), 'grant_id') AS ownership_columns_present,
                   to_jsonb(stored) ->> 'grant_id' AS grant_id,
                   (to_jsonb(stored) ->> 'grant_expires_at')::timestamptz AS grant_expires_at,
                   to_jsonb(stored) ->> 'usage_reservation_id' AS usage_reservation_id
              FROM meeting AS stored
             WHERE stored.deployment_scope = ? AND stored.client_session_id = ?
             ORDER BY stored.created_at, stored.meeting_id
             LIMIT 1
            """;

    /**
     * V001/V002/V003 都可执行的兼容写入。冲突更新只有在数据库行仍是 legacy
     * 形态时才允许；因此旧命令不能用相同 meetingId 覆盖真实主体会议。
     */
    private static final String SAVE_LEGACY_SQL = """
            INSERT INTO meeting (
                meeting_id, deployment_scope, client_session_id, subject_scope,
                profile_id, profile_revision, state, aggregate_version,
                created_at, updated_at, metadata)
            VALUES (?, ?, ?, 'LEGACY_UNOWNED', ?, ?, ?, 0, ?, ?, '{}'::jsonb)
            ON CONFLICT (meeting_id) DO UPDATE SET
                deployment_scope = EXCLUDED.deployment_scope,
                client_session_id = EXCLUDED.client_session_id,
                profile_id = EXCLUDED.profile_id,
                profile_revision = EXCLUDED.profile_revision,
                state = EXCLUDED.state,
                updated_at = EXCLUDED.updated_at
            WHERE NOT jsonb_exists(to_jsonb(meeting), 'grant_id')
               OR (meeting.subject_scope = 'LEGACY_UNOWNED'
                   AND to_jsonb(meeting) ->> 'grant_id' IS NULL
                   AND to_jsonb(meeting) ->> 'grant_expires_at' IS NULL
                   AND to_jsonb(meeting) ->> 'usage_reservation_id' IS NULL)
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
        requireLegacySave(meeting);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SAVE_LEGACY_SQL)) {
            bindSnapshot(statement, meeting);
            requireSaved(statement.executeUpdate());
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
        requireLegacySave(meeting);
        try (PreparedStatement statement = connection.prepareStatement(SAVE_LEGACY_SQL)) {
            bindSnapshot(statement, meeting);
            requireSaved(statement.executeUpdate());
        }
    }

    private static MeetingAggregate.MeetingSnapshot readSnapshot(ResultSet resultSet) throws SQLException {
        String meetingId = resultSet.getString("meeting_id");
        String deploymentScope = resultSet.getString("deployment_scope");
        String clientSessionId = resultSet.getString("client_session_id");
        String profileId = resultSet.getString("profile_id");
        int profileRevision = resultSet.getInt("profile_revision");
        MeetingState state = MeetingState.valueOf(resultSet.getString("state"));
        Instant createdAt = resultSet.getTimestamp("created_at").toInstant();
        Instant updatedAt = resultSet.getTimestamp("updated_at").toInstant();
        if (!resultSet.getBoolean("ownership_columns_present")) {
            return MeetingAggregate.MeetingSnapshot.legacyUnowned(
                    meetingId, deploymentScope, clientSessionId, profileId,
                    profileRevision, state, createdAt, updatedAt);
        }
        String subjectScope = resultSet.getString("subject_scope");
        String grantId = resultSet.getString("grant_id");
        Timestamp grantExpiresAt = resultSet.getTimestamp("grant_expires_at");
        String usageReservationId = resultSet.getString("usage_reservation_id");
        return new MeetingAggregate.MeetingSnapshot(
                meetingId, deploymentScope, clientSessionId, subjectScope, grantId,
                grantExpiresAt == null ? null : grantExpiresAt.toInstant(), usageReservationId,
                profileId, profileRevision, state, createdAt, updatedAt);
    }

    private static void bindSnapshot(
            PreparedStatement statement,
            MeetingAggregate.MeetingSnapshot meeting) throws SQLException {
        statement.setString(1, meeting.meetingId());
        statement.setString(2, meeting.deploymentScope());
        statement.setString(3, meeting.clientSessionId());
        statement.setString(4, meeting.profileId());
        statement.setInt(5, meeting.profileRevision());
        statement.setString(6, meeting.state().name());
        statement.setTimestamp(7, Timestamp.from(meeting.createdAt()));
        statement.setTimestamp(8, Timestamp.from(meeting.updatedAt()));
    }

    private static boolean isLegacy(MeetingAggregate.MeetingSnapshot meeting) {
        return MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(meeting.subjectScope());
    }

    private static void requireLegacySave(MeetingAggregate.MeetingSnapshot meeting) {
        if (!isLegacy(meeting)) {
            throw new IllegalArgumentException(
                    "owned meeting must be persisted by the atomic reservation bind unit of work");
        }
    }

    private static void requireSaved(int affectedRows) throws SQLException {
        if (affectedRows != 1) {
            throw new SQLException("会议归属事实冲突，拒绝覆盖已有快照", "23000");
        }
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
