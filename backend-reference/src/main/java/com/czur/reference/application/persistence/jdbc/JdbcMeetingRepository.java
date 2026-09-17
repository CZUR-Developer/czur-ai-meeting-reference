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
 * 当前公开 {@code save} 只允许 legacy SQL，且不引用 V003 列。owned 快照通过
 * package-private {@link #insertOwned(Connection, MeetingAggregate.MeetingSnapshot)}
 * 首次写入；该原语只允许未来 owned 创建工作单元在同一连接中调用，普通
 * Repository 仍不能绕开 Reservation 的锁定、校验和版本更新。</p>
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

    /**
     * owned 命令除领域快照外还需要数据库聚合版本参与 CAS。该版本是 JDBC
     * 并发控制细节，不上浮到公共 {@code MeetingSnapshot}，避免把关系数据库
     * 实现字段扩散到领域和公开协议。
     */
    private static final String FIND_VERSIONED_FOR_UPDATE_SQL = """
            SELECT stored.meeting_id, stored.deployment_scope, stored.client_session_id,
                   stored.subject_scope, stored.profile_id, stored.profile_revision,
                   stored.state, stored.aggregate_version, stored.created_at, stored.updated_at,
                   jsonb_exists(to_jsonb(stored), 'grant_id') AS ownership_columns_present,
                   to_jsonb(stored) ->> 'grant_id' AS grant_id,
                   (to_jsonb(stored) ->> 'grant_expires_at')::timestamptz AS grant_expires_at,
                   to_jsonb(stored) ->> 'usage_reservation_id' AS usage_reservation_id
              FROM meeting AS stored
             WHERE stored.meeting_id = ?
             FOR UPDATE
            """;

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

    /**
     * 新归属会议只能首次插入，不能通过 UPSERT 覆盖已存在的会议归属。
     * 该 SQL 只由 connection-scoped 创建工作单元调用；三个权益引用与真实主体
     * 一次性写入，数据库的延迟外键在整个事务提交时检查 Meeting 与
     * Reservation 双向引用的存在性；绑定状态仍由同事务 bind CAS 保证。
     */
    private static final String INSERT_OWNED_SQL = """
            INSERT INTO meeting (
                meeting_id, deployment_scope, client_session_id, subject_scope,
                grant_id, grant_expires_at, usage_reservation_id,
                profile_id, profile_revision, state, aggregate_version,
                created_at, updated_at, metadata)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, '{}'::jsonb)
            """;

    /**
     * 更新既有 owned 会议的可变生命周期事实。
     *
     * <p>WHERE 同时校验数据库聚合版本、更新前状态/时间和全部不可变归属、
     * Profile、创建时间。即使未来调用者遗漏行锁或错误地复用旧快照，也不能
     * 覆盖另一事务已经提交的状态或把会议认领到其他主体。SQL 不更新任何
     * 不可变列，也不包含 UPSERT。</p>
     */
    private static final String UPDATE_OWNED_EXISTING_SQL = """
            UPDATE meeting
               SET state = ?, updated_at = ?, aggregate_version = aggregate_version + 1
             WHERE meeting_id = ?
               AND deployment_scope = ?
               AND client_session_id = ?
               AND subject_scope = ?
               AND grant_id = ?
               AND grant_expires_at = ?
               AND usage_reservation_id = ?
               AND profile_id = ?
               AND profile_revision = ?
               AND created_at = ?
               AND state = ?
               AND updated_at = ?
               AND aggregate_version = ?
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

    /**
     * 在调用方事务中读取并锁定会议，同时保留 JDBC CAS 所需的聚合版本。
     *
     * <p>该入口只服务 owned 命令工作单元。公共仓库查询仍返回纯领域快照，
     * {@code aggregate_version} 不进入 API、幂等响应或 Outbox。</p>
     */
    static Optional<VersionedMeeting> findVersionedForUpdate(
            Connection connection, String meetingId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        requireText("meetingId", meetingId);
        try (PreparedStatement statement = connection.prepareStatement(
                FIND_VERSIONED_FOR_UPDATE_SQL)) {
            statement.setString(1, meetingId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new VersionedMeeting(
                        readSnapshot(resultSet), resultSet.getLong("aggregate_version")));
            }
        }
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

    /**
     * 在调用方已经开启的事务连接上首次插入 owned 会议。
     *
     * <p>该方法不会获取、提交、回滚或关闭连接，也不会执行 Reservation bind。
     * 调用方必须先在同一事务中通过权益原语验证并绑定预占。这里刻意使用纯
     * {@code INSERT}：meetingId 或 deployment/clientSession 唯一键冲突必须交给
     * 5B2 映射为 {@code MEETING_CONFLICT}，不能更新、认领或覆盖既有会议。</p>
     */
    static void insertOwned(
            Connection connection,
            MeetingAggregate.MeetingSnapshot meeting) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(meeting, "meeting");
        requireOwnedInitialSnapshot(meeting);
        try (PreparedStatement statement = connection.prepareStatement(INSERT_OWNED_SQL)) {
            statement.setString(1, meeting.meetingId());
            statement.setString(2, meeting.deploymentScope());
            statement.setString(3, meeting.clientSessionId());
            statement.setString(4, meeting.subjectScope());
            statement.setString(5, meeting.grantId());
            statement.setTimestamp(6, Timestamp.from(meeting.grantExpiresAt()));
            statement.setString(7, meeting.usageReservationId());
            statement.setString(8, meeting.profileId());
            statement.setInt(9, meeting.profileRevision());
            statement.setString(10, meeting.state().name());
            statement.setTimestamp(11, Timestamp.from(meeting.createdAt()));
            statement.setTimestamp(12, Timestamp.from(meeting.updatedAt()));
            requireInserted(statement.executeUpdate());
        }
    }

    /**
     * 在调用方事务连接上以完整旧事实和聚合版本 CAS 更新 owned 会议。
     *
     * <p>方法只更新 {@code state}/{@code updated_at} 并把数据库聚合版本加一。
     * 调用方必须传入同一次 {@link #findVersionedForUpdate(Connection, String)}
     * 得到的 before。受影响行数不是一表示快照过期、不可变事实被破坏或数据库
     * 行已消失；该方法明确失败，绝不插入新行或尝试覆盖当前事实。</p>
     */
    static void updateOwnedExisting(
            Connection connection,
            VersionedMeeting before,
            MeetingAggregate.MeetingSnapshot after) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        requireOwnedExistingTransition(before.snapshot(), after);
        MeetingAggregate.MeetingSnapshot snapshot = before.snapshot();
        try (PreparedStatement statement = connection.prepareStatement(
                UPDATE_OWNED_EXISTING_SQL)) {
            statement.setString(1, after.state().name());
            statement.setTimestamp(2, Timestamp.from(after.updatedAt()));
            statement.setString(3, snapshot.meetingId());
            statement.setString(4, snapshot.deploymentScope());
            statement.setString(5, snapshot.clientSessionId());
            statement.setString(6, snapshot.subjectScope());
            statement.setString(7, snapshot.grantId());
            statement.setTimestamp(8, Timestamp.from(snapshot.grantExpiresAt()));
            statement.setString(9, snapshot.usageReservationId());
            statement.setString(10, snapshot.profileId());
            statement.setInt(11, snapshot.profileRevision());
            statement.setTimestamp(12, Timestamp.from(snapshot.createdAt()));
            statement.setString(13, snapshot.state().name());
            statement.setTimestamp(14, Timestamp.from(snapshot.updatedAt()));
            statement.setLong(15, before.aggregateVersion());
            requireUpdatedOwned(statement.executeUpdate());
        }
    }

    /**
     * JDBC 内部的带版本会议事实；版本仅用于数据库 CAS，不属于领域快照。
     */
    record VersionedMeeting(
            MeetingAggregate.MeetingSnapshot snapshot,
            long aggregateVersion) {
        VersionedMeeting {
            Objects.requireNonNull(snapshot, "snapshot");
            if (aggregateVersion < 0) {
                throw new IllegalArgumentException("aggregateVersion must not be negative");
            }
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

    private static void requireOwnedInitialSnapshot(
            MeetingAggregate.MeetingSnapshot meeting) {
        if (isLegacy(meeting)
                || meeting.grantId() == null
                || meeting.grantExpiresAt() == null
                || meeting.usageReservationId() == null) {
            throw new IllegalArgumentException(
                    "insertOwned requires complete subject, grant and reservation facts");
        }
        if (meeting.state() != MeetingState.CREATED
                || !meeting.createdAt().equals(meeting.updatedAt())) {
            throw new IllegalArgumentException(
                    "insertOwned requires an initial CREATED snapshot");
        }
    }

    private static void requireOwnedExistingTransition(
            MeetingAggregate.MeetingSnapshot before,
            MeetingAggregate.MeetingSnapshot after) {
        if (isLegacy(before) || isLegacy(after)) {
            throw new IllegalArgumentException(
                    "updateOwnedExisting requires owned meeting snapshots");
        }
        if (!sameImmutableFacts(before, after)) {
            throw new IllegalArgumentException(
                    "updateOwnedExisting cannot change immutable meeting facts");
        }
        if (before.state() == after.state()
                || after.updatedAt().isBefore(before.updatedAt())) {
            throw new IllegalArgumentException(
                    "updateOwnedExisting requires a forward lifecycle transition");
        }
    }

    private static boolean sameImmutableFacts(
            MeetingAggregate.MeetingSnapshot before,
            MeetingAggregate.MeetingSnapshot after) {
        return before.meetingId().equals(after.meetingId())
                && before.deploymentScope().equals(after.deploymentScope())
                && before.clientSessionId().equals(after.clientSessionId())
                && before.subjectScope().equals(after.subjectScope())
                && before.grantId().equals(after.grantId())
                && before.grantExpiresAt().equals(after.grantExpiresAt())
                && before.usageReservationId().equals(after.usageReservationId())
                && before.profileId().equals(after.profileId())
                && before.profileRevision() == after.profileRevision()
                && before.createdAt().equals(after.createdAt());
    }

    private static void requireSaved(int affectedRows) throws SQLException {
        if (affectedRows != 1) {
            throw new SQLException("会议归属事实冲突，拒绝覆盖已有快照", "23000");
        }
    }

    private static void requireInserted(int affectedRows) throws SQLException {
        if (affectedRows != 1) {
            throw new SQLException("owned 会议插入数量异常", "23000");
        }
    }

    private static void requireUpdatedOwned(int affectedRows) throws SQLException {
        if (affectedRows != 1) {
            throw new SQLException(
                    "owned 会议更新前事实或聚合版本冲突", "23000");
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
