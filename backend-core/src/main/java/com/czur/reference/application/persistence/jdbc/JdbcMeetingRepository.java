package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingAccessGuard;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingRepository;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import com.czur.reference.domain.meeting.MeetingOwnership;
import com.czur.reference.domain.meeting.MeetingState;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基于 JDBC 的会议仓库参考实现。
 *
 * <p>该类只依赖标准 {@link DataSource} 和 SQL，不依赖 Gateway、Web 框架或
 * 数据库专有 Java API。独立查询可直接使用本类；会议命令需要跨会议、
 * 幂等和 Outbox 原子提交时，应通过 {@link JdbcMeetingUnitOfWork} 执行。</p>
 *
 * <p>读取同时兼容 V001/V002 与 V003：查询按基线物理列读取归属与版本事实，
 * 旧表缺少归属列时明确恢复为 {@code LEGACY_UNOWNED}，不会猜测真实主体。
 * 当前公开 {@code save} 只允许 legacy SQL，且不引用 V003 列。owned 快照通过
 * package-private {@link #insertOwned(Connection, MeetingAggregate.MeetingSnapshot)}
 * 首次写入；该原语只允许未来 owned 创建工作单元在同一连接中调用，普通
 * Repository 仍不能绕开 Reservation 的锁定、校验和版本更新。</p>
 */
public final class JdbcMeetingRepository implements MeetingRepository {
    private static final Logger LOGGER = Logger.getLogger(JdbcMeetingRepository.class.getName());
    private static final EnumSet<MeetingState> DELETION_SOURCE_STATES = EnumSet.of(
            MeetingState.READY,
            MeetingState.READY_WITH_WARNINGS,
            MeetingState.FAILED,
            MeetingState.CANCELLED);
    private static final EnumSet<ManifestStatus> DELETION_SOURCE_MANIFEST_STATES = EnumSet.of(
            ManifestStatus.NOT_STARTED,
            ManifestStatus.GENERATING,
            ManifestStatus.READY,
            ManifestStatus.READY_WITH_WARNINGS,
            ManifestStatus.FAILED);
    /**
     * 兼容写入的单条 UPSERT。冲突更新必须对每一列使用同一个 legacy 行守卫，
     * 避免旧命令覆盖已经绑定权益的会议；守卫不命中时冲突分支不改变任何列。
     */
    private static String legacySaveSql(JdbcDatabaseEngine engine) {
        String table = JdbcDatabaseTable.MEETING.physicalName(engine);
        String guard = "subject_scope = 'LEGACY_UNOWNED' AND grant_id IS NULL"
                + " AND grant_expires_at IS NULL AND usage_reservation_id IS NULL";
        return "INSERT INTO " + table
                + " (meeting_id, deployment_scope, client_session_id, subject_scope,"
                + " profile_id, profile_revision, state, aggregate_version,"
                + " created_at, updated_at, metadata)"
                + " VALUES (?, ?, ?, 'LEGACY_UNOWNED', ?, ?, ?, 0, ?, ?, CAST('{}' AS JSON))"
                + " ON DUPLICATE KEY UPDATE"
                + " deployment_scope = IF(" + guard
                + ", VALUES(deployment_scope), deployment_scope),"
                + " client_session_id = IF(" + guard
                + ", VALUES(client_session_id), client_session_id),"
                + " profile_id = IF(" + guard + ", VALUES(profile_id), profile_id),"
                + " profile_revision = IF(" + guard
                + ", VALUES(profile_revision), profile_revision),"
                + " state = IF(" + guard + ", VALUES(state), state),"
                + " updated_at = IF(" + guard + ", VALUES(updated_at), updated_at)";
    }

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

    /**
     * 删除事务使用的单次锁读。查询取齐删除 CAS 和重放校验所需字段，但调用方
     * 只能先读取原始 deployment/subject 并执行访问守卫；守卫放行后才解析状态、
     * receipt、Manifest、时间和不可变归属事实。
     */
    private static final String FIND_DELETION_FOR_UPDATE_SQL = """
            SELECT meeting_id, deployment_scope, client_session_id, subject_scope,
                   grant_id, grant_expires_at, usage_reservation_id,
                   profile_id, profile_revision, state, aggregate_version,
                   input_state, finalization_receipt_id, manifest_state,
                   created_at, updated_at
              FROM meeting
             WHERE meeting_id = ?
             FOR UPDATE
            """;

    /**
     * Manifest 发布工作单元使用的锁读。与删除锁读分开命名，避免把删除专用的
     * receipt/Manifest 解析误复用到最终产物发布路径。所有可变事实都在同一行
     * 读取，外层事务在继续读取 V007 latest revision 前必须持有这把行锁。
     */
    private static final String FIND_MANIFEST_PUBLICATION_FOR_UPDATE_SQL = """
            SELECT meeting_id, deployment_scope, client_session_id, subject_scope,
                   grant_id, grant_expires_at, usage_reservation_id,
                   profile_id, profile_revision, state, aggregate_version,
                   input_state, finalization_receipt_id, manifest_state,
                   created_at, updated_at
              FROM meeting
             WHERE meeting_id = ?
            FOR UPDATE
            """;

    /**
     * commit-unknown 只读恢复使用的同形快照；禁止附带行锁或其它写意图。
     */
    private static final String FIND_MANIFEST_PUBLICATION_SQL = """
            SELECT meeting_id, deployment_scope, client_session_id, subject_scope,
                   grant_id, grant_expires_at, usage_reservation_id,
                   profile_id, profile_revision, state, aggregate_version,
                   input_state, finalization_receipt_id, manifest_state,
                   created_at, updated_at
              FROM meeting
             WHERE meeting_id = ?
            """;

    /**
     * Manifest 发布的单行 CAS。V007 revision 插入与此更新必须复用同一连接和
     * 事务；SQL 同时比较完整的不可变归属、Profile、输入 receipt、旧状态、时间
     * 和聚合版本，防止包内调用方凭过期快照覆盖其它工作单元。
     */
    private static final String UPDATE_MANIFEST_PUBLICATION_SQL = """
            UPDATE meeting
               SET state = ?,
                   manifest_state = ?,
                   updated_at = ?,
                   aggregate_version = aggregate_version + 1
             WHERE meeting_id = ?
               AND deployment_scope = ?
               AND client_session_id = ?
               AND subject_scope = ?
               AND grant_id <=> ?
               AND grant_expires_at <=> ?
               AND usage_reservation_id <=> ?
               AND profile_id = ?
               AND profile_revision = ?
               AND created_at = ?
               AND state = ?
               AND input_state = ?
               AND finalization_receipt_id <=> ?
               AND manifest_state = ?
               AND updated_at = ?
               AND aggregate_version = ?
            """;

    /**
     * 删除受理只改变四个可变事实。WHERE 重放锁读得到的全部不可变字段、旧状态、
     * 输入事实、Manifest、时间和聚合版本，防止旧快照或意外改写被静默覆盖。
     */
    private static final String MARK_DELETION_PENDING_SQL = """
            UPDATE meeting
               SET state = ?,
                   manifest_state = ?,
                   updated_at = ?,
                   aggregate_version = aggregate_version + 1
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
               AND input_state = ?
               AND finalization_receipt_id <=> ?
               AND manifest_state = ?
               AND updated_at = ?
               AND aggregate_version = ?
            """;

    private final DataSource dataSource;
    private final JdbcDatabaseEngine engine;

    public JdbcMeetingRepository(DataSource dataSource) {
        this(dataSource, JdbcDatabaseEngine.MYSQL);
    }

    public JdbcMeetingRepository(JdbcDatabaseContext database) {
        this(database.dataSource(), database.engine());
    }

    private JdbcMeetingRepository(DataSource dataSource, JdbcDatabaseEngine engine) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public Optional<MeetingAggregate.MeetingSnapshot> find(String meetingId) {
        requireText("meetingId", meetingId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(findSql(engine, false))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readSnapshot(resultSet, engine)) : Optional.empty();
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
             PreparedStatement statement = connection.prepareStatement(mysqlColumns(false) + " WHERE m.deployment_scope = ?"
                     + " AND m.client_session_id = ? ORDER BY m.created_at, m.meeting_id LIMIT 1")) {
            JdbcValues.setText(statement, 1, engine, deploymentScope);
            JdbcValues.setText(statement, 2, engine, clientSessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readSnapshot(resultSet, engine)) : Optional.empty();
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
             PreparedStatement statement = connection.prepareStatement(legacySaveSql(engine))) {
            bindSnapshot(statement, meeting, engine);
            requireSaved(statement.executeUpdate(), engine);
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
        return find(connection, findSql(JdbcDatabaseEngine.MYSQL, false), meetingId);
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
        return find(connection, findSql(JdbcDatabaseEngine.MYSQL, false) + " FOR UPDATE",
                meetingId);
    }

    /**
     * 在调用方事务中读取并锁定会议，同时保留 JDBC CAS 所需的聚合版本。
     *
     * <p>该入口只服务 owned 命令工作单元。公共仓库查询仍返回纯领域快照，
     * {@code aggregate_version} 不进入 API、幂等响应或 Outbox。</p>
     */
    static Optional<VersionedMeeting> findVersionedForUpdate(
            Connection connection, String meetingId) throws SQLException {
        return findVersionedForUpdate(connection, JdbcDatabaseEngine.MYSQL, meetingId);
    }

    static Optional<VersionedMeeting> findVersionedForUpdate(
            Connection connection, JdbcDatabaseEngine engine, String meetingId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        requireText("meetingId", meetingId);
        try (PreparedStatement statement = connection.prepareStatement(
                findSql(engine, true) + " FOR UPDATE")) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new VersionedMeeting(
                        readSnapshot(resultSet, engine), resultSet.getLong("aggregate_version")));
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
        try (PreparedStatement statement = connection.prepareStatement(mysqlColumns(false)
                + " WHERE m.deployment_scope = ? AND m.client_session_id = ?"
                + " ORDER BY m.created_at, m.meeting_id LIMIT 1")) {
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
        try (PreparedStatement statement = connection.prepareStatement(
                legacySaveSql(JdbcDatabaseEngine.MYSQL))) {
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
        insertOwned(connection, JdbcDatabaseEngine.MYSQL, meeting);
    }

    static void insertOwned(Connection connection, JdbcDatabaseEngine engine,
                            MeetingAggregate.MeetingSnapshot meeting) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(meeting, "meeting");
        requireOwnedInitialSnapshot(meeting);
        String sql = """
                INSERT INTO %s (
                    meeting_id, deployment_scope, client_session_id, subject_scope,
                    grant_id, grant_expires_at, usage_reservation_id,
                    profile_id, profile_revision, state, aggregate_version,
                    created_at, updated_at, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, CAST('{}' AS JSON))
                """.formatted(JdbcDatabaseTable.MEETING.physicalName(engine));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.setText(statement, 1, engine, meeting.meetingId());
            JdbcValues.setText(statement, 2, engine, meeting.deploymentScope());
            JdbcValues.setText(statement, 3, engine, meeting.clientSessionId());
            JdbcValues.setText(statement, 4, engine, meeting.subjectScope());
            JdbcValues.setText(statement, 5, engine, meeting.grantId());
            JdbcValues.setInstant(statement, 6, meeting.grantExpiresAt());
            JdbcValues.setText(statement, 7, engine, meeting.usageReservationId());
            JdbcValues.setText(statement, 8, engine, meeting.profileId());
            statement.setInt(9, meeting.profileRevision());
            statement.setString(10, meeting.state().name());
            JdbcValues.setInstant(statement, 11, meeting.createdAt());
            JdbcValues.setInstant(statement, 12, meeting.updatedAt());
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
        updateOwnedExisting(connection, JdbcDatabaseEngine.MYSQL, before, after);
    }

    static void updateOwnedExisting(Connection connection, JdbcDatabaseEngine engine,
                                   VersionedMeeting before,
                                   MeetingAggregate.MeetingSnapshot after) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        requireOwnedExistingTransition(before.snapshot(), after);
        MeetingAggregate.MeetingSnapshot snapshot = before.snapshot();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                UPDATE %s
                   SET state = ?, updated_at = ?, aggregate_version = aggregate_version + 1
                 WHERE meeting_id = ? AND deployment_scope = ? AND client_session_id = ?
                   AND subject_scope = ? AND grant_id = ? AND grant_expires_at = ?
                   AND usage_reservation_id = ? AND profile_id = ? AND profile_revision = ?
                   AND created_at = ? AND state = ? AND updated_at = ? AND aggregate_version = ?
                """.formatted(JdbcDatabaseTable.MEETING.physicalName(engine)))) {
            statement.setString(1, after.state().name());
            JdbcValues.setInstant(statement, 2, after.updatedAt());
            JdbcValues.setText(statement, 3, engine, snapshot.meetingId());
            JdbcValues.setText(statement, 4, engine, snapshot.deploymentScope());
            JdbcValues.setText(statement, 5, engine, snapshot.clientSessionId());
            JdbcValues.setText(statement, 6, engine, snapshot.subjectScope());
            JdbcValues.setText(statement, 7, engine, snapshot.grantId());
            JdbcValues.setInstant(statement, 8, snapshot.grantExpiresAt());
            JdbcValues.setText(statement, 9, engine, snapshot.usageReservationId());
            JdbcValues.setText(statement, 10, engine, snapshot.profileId());
            statement.setInt(11, snapshot.profileRevision());
            JdbcValues.setInstant(statement, 12, snapshot.createdAt());
            statement.setString(13, snapshot.state().name());
            JdbcValues.setInstant(statement, 14, snapshot.updatedAt());
            statement.setLong(15, before.aggregateVersion());
            requireUpdatedOwned(statement.executeUpdate());
        }
    }

    /**
     * 在调用方事务连接上以完整旧事实和聚合版本 CAS 更新历史兼容会议。
     *
     * <p>历史会议没有 Grant/预占，owned CAS 会拒绝；本方法只更新
     * {@code state}/{@code updated_at}/聚合版本，WHERE 仍核对全部 legacy 归属
     * 形态、Profile、创建时间、旧状态、旧更新时间和版本，防止旧命令覆盖已绑定
     * 权益的会议或凭过期快照回退状态。只由弃场扫描的“结算成功→取消”收口使用。</p>
     */
    static void updateLegacyExisting(Connection connection, JdbcDatabaseEngine engine,
            VersionedMeeting before, MeetingAggregate.MeetingSnapshot after) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        requireLegacyExistingTransition(before.snapshot(), after);
        MeetingAggregate.MeetingSnapshot snapshot = before.snapshot();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                UPDATE %s
                   SET state = ?, updated_at = ?, aggregate_version = aggregate_version + 1
                 WHERE meeting_id = ? AND deployment_scope = ? AND client_session_id = ?
                   AND subject_scope = ? AND grant_id IS NULL AND grant_expires_at IS NULL
                   AND usage_reservation_id IS NULL AND profile_id = ? AND profile_revision = ?
                   AND created_at = ? AND state = ? AND updated_at = ? AND aggregate_version = ?
                """.formatted(JdbcDatabaseTable.MEETING.physicalName(engine)))) {
            statement.setString(1, after.state().name());
            JdbcValues.setInstant(statement, 2, after.updatedAt());
            JdbcValues.setText(statement, 3, engine, snapshot.meetingId());
            JdbcValues.setText(statement, 4, engine, snapshot.deploymentScope());
            JdbcValues.setText(statement, 5, engine, snapshot.clientSessionId());
            JdbcValues.setText(statement, 6, engine, snapshot.subjectScope());
            JdbcValues.setText(statement, 7, engine, snapshot.profileId());
            statement.setInt(8, snapshot.profileRevision());
            JdbcValues.setInstant(statement, 9, snapshot.createdAt());
            statement.setString(10, snapshot.state().name());
            JdbcValues.setInstant(statement, 11, snapshot.updatedAt());
            statement.setLong(12, before.aggregateVersion());
            requireUpdatedOwned(statement.executeUpdate());
        }
    }

    /**
     * 锁定删除目标，并在同一个 ResultSet 中先完成原始归属检查。
     *
     * <p>该方法必须由已经关闭 auto-commit 的删除事务调用。不存在、拒绝和放行
     * 使用显式联合结果，避免调用方在访问拒绝后继续解析仅所有者可见的状态、
     * receipt 或损坏字段。行锁持续到外层事务提交或回滚。</p>
     */
    static DeletionLockResult lockForDeletion(
            Connection connection,
            String meetingId,
            MeetingCommandContext context) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(context, "context");
        requireText("meetingId", meetingId);
        try (PreparedStatement statement = connection.prepareStatement(
                FIND_DELETION_FOR_UPDATE_SQL)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return new DeletionNotFound();
                }

                // 安全顺序不可调整：外域/跨主体/legacy 行只允许读取这两个原始列。
                String storedDeployment = rows.getString("deployment_scope");
                String storedSubject = rows.getString("subject_scope");
                if (MeetingAccessGuard.evaluate(context, storedDeployment, storedSubject)
                        != MeetingAccessGuard.Decision.ALLOWED) {
                    return new DeletionAccessDenied();
                }
                try {
                    return new DeletionFound(readDeletionMeeting(
                            rows, storedDeployment, storedSubject));
                } catch (DeletionSnapshotException inconsistent) {
                    // 归属已经通过后，非法枚举、NULL 标量、半归属或 receipt
                    // 不变量破坏都属于已锁定事实损坏，而不是数据库可用性故障。
                    // 异常不携带原始列值，避免把敏感事实带入上层日志。
                    return new DeletionSnapshotInconsistent();
                }
            }
        }
    }

    /**
     * 在 Manifest 发布事务中锁定会议并恢复全部公开状态事实。
     *
     * <p>该原语不创建独立事务，也不读取或写入 V007。调用方必须在继续访问
     * {@link JdbcManifestFactStore} 前保持同一连接的行锁。归属、Profile、输入
     * receipt 或枚举值无法恢复时抛出固定的 {@link PublicationSnapshotException}，
     * 由发布端口映射为 {@code SNAPSHOT_INCONSISTENT}，不得猜测缺失事实。</p>
     */
    static Optional<PublicationMeeting> findManifestPublicationForUpdate(
            Connection connection,
            String meetingId) throws SQLException, PublicationSnapshotException {
        Objects.requireNonNull(connection, "connection");
        requireText("meetingId", meetingId);
        try (PreparedStatement statement = connection.prepareStatement(
                FIND_MANIFEST_PUBLICATION_FOR_UPDATE_SQL)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                PublicationMeeting result = readManifestPublicationMeeting(rows);
                if (rows.next()) {
                    throw new PublicationSnapshotException();
                }
                return Optional.of(result);
            }
        }
    }

    /**
     * 在调用方只读事务中恢复 Manifest/Meeting 完整快照，不取得 {@code FOR UPDATE}
     * 行锁，也不提交、回滚或关闭连接。仅供 outcome-unknown 恢复使用；任何首次
     * 写入路径仍必须调用带行锁版本并在同一事务内完成 CAS。
     */
    static Optional<PublicationMeeting> findManifestPublication(
            Connection connection,
            String meetingId) throws SQLException, PublicationSnapshotException {
        Objects.requireNonNull(connection, "connection");
        requireText("meetingId", meetingId);
        try (PreparedStatement statement = connection.prepareStatement(
                FIND_MANIFEST_PUBLICATION_SQL)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                PublicationMeeting result = readManifestPublicationMeeting(rows);
                if (rows.next()) {
                    throw new PublicationSnapshotException();
                }
                return Optional.of(result);
            }
        }
    }

    /**
     * 在同一事务内 CAS 推进会议生命周期和 Manifest 状态。
     *
     * @return 恰好更新一行表示所有事实仍与锁读一致；零行表示快照损坏或
     *         调用方越过了锁读边界，调用方必须回滚整个 revision 追加
     */
    static boolean updateManifestPublication(
            Connection connection,
            PublicationMeeting before,
            MeetingState afterState,
            ManifestStatus afterManifestState,
            Instant afterUpdatedAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(afterState, "afterState");
        Objects.requireNonNull(afterManifestState, "afterManifestState");
        Objects.requireNonNull(afterUpdatedAt, "afterUpdatedAt");
        validateManifestPublicationTransition(
                before, afterState, afterManifestState, afterUpdatedAt);
        MeetingAggregate.MeetingSnapshot snapshot = before.snapshot();
        try (PreparedStatement statement = connection.prepareStatement(
                UPDATE_MANIFEST_PUBLICATION_SQL)) {
            statement.setString(1, afterState.name());
            statement.setString(2, afterManifestState.name());
            statement.setTimestamp(3, Timestamp.from(afterUpdatedAt));
            statement.setString(4, snapshot.meetingId());
            statement.setString(5, snapshot.deploymentScope());
            statement.setString(6, snapshot.clientSessionId());
            statement.setString(7, snapshot.subjectScope());
            statement.setString(8, snapshot.grantId());
            if (snapshot.grantExpiresAt() == null) {
                statement.setNull(9, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                statement.setTimestamp(9, Timestamp.from(snapshot.grantExpiresAt()));
            }
            statement.setString(10, snapshot.usageReservationId());
            statement.setString(11, snapshot.profileId());
            statement.setInt(12, snapshot.profileRevision());
            statement.setTimestamp(13, Timestamp.from(snapshot.createdAt()));
            statement.setString(14, snapshot.state().name());
            statement.setString(15, before.finalization().inputState().name());
            statement.setString(16, before.finalization().finalizationReceipt());
            statement.setString(17, before.manifestState().name());
            statement.setTimestamp(18, Timestamp.from(snapshot.updatedAt()));
            statement.setLong(19, before.aggregateVersion());
            return statement.executeUpdate() == 1;
        }
    }

    private static PublicationMeeting readManifestPublicationMeeting(ResultSet rows)
            throws SQLException, PublicationSnapshotException {
        try {
            String meetingId = requiredColumn(rows, "meeting_id");
            String deploymentScope = requiredColumn(rows, "deployment_scope");
            String clientSessionId = requiredColumn(rows, "client_session_id");
            String subjectScope = requiredColumn(rows, "subject_scope");
            String grantId = rows.getString("grant_id");
            Timestamp grantExpiresAt = rows.getTimestamp("grant_expires_at");
            String usageReservationId = rows.getString("usage_reservation_id");
            String profileId = requiredColumn(rows, "profile_id");
            int profileRevision = rows.getInt("profile_revision");
            if (rows.wasNull()) {
                throw new PublicationSnapshotException();
            }
            MeetingState state = MeetingState.valueOf(requiredColumn(rows, "state"));
            long aggregateVersion = rows.getLong("aggregate_version");
            if (rows.wasNull() || aggregateVersion < 0) {
                throw new PublicationSnapshotException();
            }
            InputState inputState = InputState.valueOf(requiredColumn(rows, "input_state"));
            String receipt = rows.getString("finalization_receipt_id");
            ManifestStatus manifestState = ManifestStatus.valueOf(
                    requiredColumn(rows, "manifest_state"));
            Timestamp createdAt = rows.getTimestamp("created_at");
            Timestamp updatedAt = rows.getTimestamp("updated_at");
            if (createdAt == null || updatedAt == null) {
                throw new PublicationSnapshotException();
            }
            FinalizationFact finalization = new FinalizationFact(inputState, receipt);
            MeetingAggregate.MeetingSnapshot snapshot = new MeetingAggregate.MeetingSnapshot(
                    meetingId,
                    deploymentScope,
                    clientSessionId,
                    subjectScope,
                    grantId,
                    grantExpiresAt == null ? null : grantExpiresAt.toInstant(),
                    usageReservationId,
                    profileId,
                    profileRevision,
                    state,
                    createdAt.toInstant(),
                    updatedAt.toInstant());
            return new PublicationMeeting(snapshot, aggregateVersion, finalization, manifestState);
        } catch (PublicationSnapshotException malformed) {
            throw malformed;
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new PublicationSnapshotException();
        }
    }

    private static String requiredColumn(ResultSet rows, String name)
            throws SQLException, PublicationSnapshotException {
        String value = rows.getString(name);
        if (value == null || value.isBlank()) {
            throw new PublicationSnapshotException();
        }
        return value;
    }

    private static void validateManifestPublicationTransition(
            PublicationMeeting before,
            MeetingState afterState,
            ManifestStatus afterManifestState,
            Instant afterUpdatedAt) {
        if (afterUpdatedAt.isBefore(before.snapshot().updatedAt())) {
            throw new IllegalArgumentException("manifest publication time cannot move backwards");
        }
        if (before.finalization().inputState() != InputState.INPUT_ACCEPTED) {
            throw new IllegalArgumentException("manifest publication requires accepted input");
        }
        if (afterState == MeetingState.FINALIZING
                && afterManifestState == ManifestStatus.GENERATING) {
            if (before.snapshot().state() != MeetingState.FINALIZING
                    || before.manifestState() != ManifestStatus.NOT_STARTED) {
                throw new IllegalArgumentException("begin generation state is inconsistent");
            }
            return;
        }
        if (before.snapshot().state() == MeetingState.FINALIZING) {
            if (!EnumSet.of(
                    MeetingState.READY,
                    MeetingState.READY_WITH_WARNINGS,
                    MeetingState.FAILED).contains(afterState)) {
                throw new IllegalArgumentException("first publication requires final state");
            }
        } else if (before.snapshot().state() != afterState
                || before.manifestState() != afterManifestState) {
            throw new IllegalArgumentException("later publication cannot change completion state");
        }
    }

    /** 发布事务锁读所需的完整会议级事实；不属于公开 API 或日志。 */
    record PublicationMeeting(
            MeetingAggregate.MeetingSnapshot snapshot,
            long aggregateVersion,
            FinalizationFact finalization,
            ManifestStatus manifestState) {
        PublicationMeeting {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(finalization, "finalization");
            Objects.requireNonNull(manifestState, "manifestState");
            if (aggregateVersion < 0) {
                throw new IllegalArgumentException("aggregateVersion must not be negative");
            }
        }
    }

    /** 锁定行无法恢复为完整发布事实；只允许映射为固定冲突类别。 */
    static final class PublicationSnapshotException extends Exception {
    }

    /**
     * 用锁读事实把会议原子推进为删除待处理。
     *
     * @return 仅当完整旧事实仍完全一致且恰好更新一行时返回 {@code true}
     */
    static boolean markDeletionPending(
            Connection connection,
            DeletionMeeting before,
            MeetingAggregate.MeetingSnapshot advanced,
            MeetingStatus accepted) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(advanced, "advanced");
        Objects.requireNonNull(accepted, "accepted");
        validateDeletionTransition(before, advanced, accepted);
        MeetingAggregate.MeetingSnapshot snapshot = before.snapshot();
        try (PreparedStatement statement = connection.prepareStatement(
                MARK_DELETION_PENDING_SQL)) {
            statement.setString(1, advanced.state().name());
            statement.setString(2, accepted.manifestState().name());
            statement.setTimestamp(3, Timestamp.from(advanced.updatedAt()));
            statement.setString(4, snapshot.meetingId());
            statement.setString(5, snapshot.deploymentScope());
            statement.setString(6, snapshot.clientSessionId());
            statement.setString(7, snapshot.subjectScope());
            statement.setString(8, snapshot.grantId());
            statement.setTimestamp(9, Timestamp.from(snapshot.grantExpiresAt()));
            statement.setString(10, snapshot.usageReservationId());
            statement.setString(11, snapshot.profileId());
            statement.setInt(12, snapshot.profileRevision());
            statement.setTimestamp(13, Timestamp.from(snapshot.createdAt()));
            statement.setString(14, snapshot.state().name());
            statement.setString(15, before.finalization().inputState().name());
            statement.setString(16, before.finalization().finalizationReceipt());
            statement.setString(17, before.manifestState().name());
            statement.setTimestamp(18, Timestamp.from(snapshot.updatedAt()));
            statement.setLong(19, before.aggregateVersion());
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * 在已锁定 Meeting 行上提交删除终态；{@code deleted_at} 与 {@code updated_at}
     * 使用同一个调用方时间，满足 V012 等价约束。调用方必须先完成全部 item、
     * Manifest 和计划校验，方法本身不访问其它表也不提交事务。
     */
    static boolean markDeleted(
            Connection connection,
            VersionedMeeting before,
            Instant deletedAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(deletedAt, "deletedAt");
        if (deletedAt.getNano() % 1_000 != 0
                || before.snapshot().state() != MeetingState.DELETION_PENDING) {
            throw new IllegalArgumentException("meeting is not deletion pending");
        }
        if (deletedAt.isBefore(before.snapshot().updatedAt())) {
            throw new IllegalArgumentException("deletedAt cannot move backwards");
        }
        MeetingAggregate.MeetingSnapshot snapshot = before.snapshot();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE meeting
                   SET state = 'DELETED', manifest_state = 'DELETED',
                       updated_at = ?, deleted_at = ?,
                       aggregate_version = aggregate_version + 1
                 WHERE meeting_id = ?
                   AND deployment_scope = ? AND client_session_id = ?
                   AND subject_scope = ? AND grant_id <=> ?
                   AND grant_expires_at <=> ?
                   AND usage_reservation_id <=> ?
                   AND profile_id = ? AND profile_revision = ? AND created_at = ?
                   AND state = 'DELETION_PENDING'
                   AND manifest_state = 'DELETION_PENDING'
                   AND updated_at = ? AND aggregate_version = ?
                   AND deleted_at IS NULL
                """)) {
            statement.setTimestamp(1, Timestamp.from(deletedAt));
            statement.setTimestamp(2, Timestamp.from(deletedAt));
            statement.setString(3, snapshot.meetingId());
            statement.setString(4, snapshot.deploymentScope());
            statement.setString(5, snapshot.clientSessionId());
            statement.setString(6, snapshot.subjectScope());
            statement.setString(7, snapshot.grantId());
            if (snapshot.grantExpiresAt() == null) {
                statement.setNull(8, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                statement.setTimestamp(8, Timestamp.from(snapshot.grantExpiresAt()));
            }
            statement.setString(9, snapshot.usageReservationId());
            statement.setString(10, snapshot.profileId());
            statement.setInt(11, snapshot.profileRevision());
            statement.setTimestamp(12, Timestamp.from(snapshot.createdAt()));
            statement.setTimestamp(13, Timestamp.from(snapshot.updatedAt()));
            statement.setLong(14, before.aggregateVersion());
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * 在准备 SQL 之前重新证明删除转换，避免包内调用方绕过领域状态机后直接
     * 覆盖数据库。验证失败抛出固定参数错误，且不会创建 PreparedStatement。
     */
    private static void validateDeletionTransition(
            DeletionMeeting before,
            MeetingAggregate.MeetingSnapshot advanced,
            MeetingStatus accepted) {
        MeetingAggregate.MeetingSnapshot snapshot = before.snapshot();
        if (!DELETION_SOURCE_STATES.contains(snapshot.state())) {
            throw new IllegalArgumentException("before meeting state cannot be deleted");
        }
        if (!DELETION_SOURCE_MANIFEST_STATES.contains(before.manifestState())) {
            throw new IllegalArgumentException("before manifest state cannot be deleted");
        }
        if (advanced.updatedAt().isBefore(snapshot.updatedAt())) {
            throw new IllegalArgumentException("advanced time cannot move backwards");
        }

        final MeetingAggregate.MeetingSnapshot expected;
        try {
            MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(snapshot);
            MeetingState next = aggregate.apply(
                    MeetingEvent.DELETE_REQUESTED, advanced.updatedAt());
            if (next != MeetingState.DELETION_PENDING) {
                throw new IllegalArgumentException("domain transition did not reach pending");
            }
            expected = aggregate.snapshot();
        } catch (RuntimeException invalidTransition) {
            throw new IllegalArgumentException("invalid deletion domain transition");
        }
        if (!expected.equals(advanced)) {
            throw new IllegalArgumentException("advanced snapshot is not the domain result");
        }
        if (!accepted.meetingId().equals(advanced.meetingId())
                || accepted.state() != MeetingState.DELETION_PENDING
                || accepted.manifestState() != ManifestStatus.DELETION_PENDING
                || !accepted.finalization().equals(before.finalization())
                || !accepted.updatedAt().equals(advanced.updatedAt())) {
            throw new IllegalArgumentException("accepted status does not match transition");
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

    /** 删除锁读的显式结果，防止 AccessDenied 分支携带或解析完整事实。 */
    sealed interface DeletionLockResult
            permits DeletionNotFound, DeletionAccessDenied,
            DeletionSnapshotInconsistent, DeletionFound {
    }

    /** 目标会议不存在。 */
    record DeletionNotFound() implements DeletionLockResult {
    }

    /** 原始部署、主体或 legacy 归属不允许当前调用者访问。 */
    record DeletionAccessDenied() implements DeletionLockResult {
    }

    /** 归属放行后发现锁定行不能恢复为完整删除读模型。 */
    record DeletionSnapshotInconsistent() implements DeletionLockResult {
    }

    /** 已锁定并完成归属验证的完整 owned 删除事实。 */
    record DeletionFound(DeletionMeeting meeting) implements DeletionLockResult {
        DeletionFound {
            Objects.requireNonNull(meeting, "meeting");
        }
    }

    /**
     * 删除 CAS 与重放一致性校验所需的完整事实；不会进入公开协议或日志。
     */
    record DeletionMeeting(
            MeetingAggregate.MeetingSnapshot snapshot,
            long aggregateVersion,
            FinalizationFact finalization,
            ManifestStatus manifestState) {
        DeletionMeeting {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(finalization, "finalization");
            Objects.requireNonNull(manifestState, "manifestState");
            if (aggregateVersion < 0) {
                throw new IllegalArgumentException("aggregateVersion must not be negative");
            }
            if (MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(
                    snapshot.subjectScope())) {
                throw new IllegalArgumentException("deletion meeting must be owned");
            }
        }

        MeetingStatus status() {
            return new MeetingStatus(
                    snapshot.meetingId(), snapshot.state(), finalization,
                    manifestState, snapshot.updatedAt());
        }

        MeetingStatus pendingStatus(MeetingAggregate advanced) {
            Objects.requireNonNull(advanced, "advanced");
            MeetingAggregate.MeetingSnapshot after = advanced.snapshot();
            if (!snapshot.meetingId().equals(after.meetingId())
                    || after.state() != MeetingState.DELETION_PENDING) {
                throw new IllegalArgumentException(
                        "aggregate must represent this meeting in DELETION_PENDING");
            }
            return new MeetingStatus(
                    after.meetingId(), after.state(), finalization,
                    ManifestStatus.DELETION_PENDING, after.updatedAt());
        }
    }

    private static DeletionMeeting readDeletionMeeting(
            ResultSet rows,
            String storedDeployment,
            String storedSubject) throws SQLException, DeletionSnapshotException {
        // JDBC 数值 getter 会把 SQL NULL 折叠为 0；必须紧邻 getter 调用
        // wasNull()，否则 NULL aggregate_version 会被误认为合法的初始版本。
        int profileRevision = rows.getInt("profile_revision");
        boolean profileRevisionNull = rows.wasNull();
        long aggregateVersion = rows.getLong("aggregate_version");
        boolean aggregateVersionNull = rows.wasNull();
        Timestamp grantExpiresAt = rows.getTimestamp("grant_expires_at");
        Timestamp createdAt = rows.getTimestamp("created_at");
        Timestamp updatedAt = rows.getTimestamp("updated_at");
        String meetingId = rows.getString("meeting_id");
        String clientSessionId = rows.getString("client_session_id");
        String grantId = rows.getString("grant_id");
        String usageReservationId = rows.getString("usage_reservation_id");
        String profileId = rows.getString("profile_id");
        String state = rows.getString("state");
        String inputState = rows.getString("input_state");
        String finalizationReceipt = rows.getString("finalization_receipt_id");
        String manifestState = rows.getString("manifest_state");

        if (profileRevisionNull || aggregateVersionNull) {
            throw new DeletionSnapshotException();
        }
        try {
            MeetingAggregate.MeetingSnapshot snapshot =
                    new MeetingAggregate.MeetingSnapshot(
                            meetingId,
                            storedDeployment,
                            clientSessionId,
                            storedSubject,
                            grantId,
                            grantExpiresAt == null ? null : grantExpiresAt.toInstant(),
                            usageReservationId,
                            profileId,
                            profileRevision,
                            MeetingState.valueOf(state),
                            createdAt == null ? null : createdAt.toInstant(),
                            updatedAt == null ? null : updatedAt.toInstant());
            FinalizationFact finalization = new FinalizationFact(
                    InputState.valueOf(inputState), finalizationReceipt);
            return new DeletionMeeting(
                    snapshot,
                    aggregateVersion,
                    finalization,
                    ManifestStatus.valueOf(manifestState));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new DeletionSnapshotException();
        }
    }

    /** 只标识持久化事实不能恢复；禁止携带数据库原始值或动态异常文本。 */
    private static final class DeletionSnapshotException extends Exception {
    }

    /** MySQL 当前 V001 直接选择固定归属列，历史列兼容查询保持不变。 */
    private static String mysqlColumns(boolean versioned) {
        // STORED 为 MySQL 5.7 生成列关键字，不使用未加引号的 stored 别名。
        return "SELECT m.meeting_id, m.deployment_scope, m.client_session_id,"
                + " m.subject_scope, m.profile_id, m.profile_revision, m.state,"
                + (versioned ? " m.aggregate_version," : "")
                + " m.created_at, m.updated_at, TRUE AS ownership_columns_present,"
                + " m.grant_id, m.grant_expires_at, m.usage_reservation_id FROM "
                + JdbcDatabaseTable.MEETING.physicalName(JdbcDatabaseEngine.MYSQL) + " AS m";
    }

    private static String findSql(JdbcDatabaseEngine engine, boolean versioned) {
        return mysqlColumns(versioned) + " WHERE m.meeting_id = ?";
    }

    private static MeetingAggregate.MeetingSnapshot readSnapshot(ResultSet resultSet) throws SQLException {
        return readSnapshot(resultSet, JdbcDatabaseEngine.MYSQL);
    }

    private static MeetingAggregate.MeetingSnapshot readSnapshot(
            ResultSet resultSet, JdbcDatabaseEngine engine) throws SQLException {
        if (engine == JdbcDatabaseEngine.MYSQL) {
            return new MeetingAggregate.MeetingSnapshot(
                    JdbcValues.text(resultSet, "meeting_id"),
                    JdbcValues.text(resultSet, "deployment_scope"),
                    JdbcValues.text(resultSet, "client_session_id"),
                    JdbcValues.text(resultSet, "subject_scope"),
                    JdbcValues.text(resultSet, "grant_id"),
                    JdbcValues.instant(resultSet, "grant_expires_at"),
                    JdbcValues.text(resultSet, "usage_reservation_id"),
                    JdbcValues.text(resultSet, "profile_id"), resultSet.getInt("profile_revision"),
                    MeetingState.valueOf(JdbcValues.text(resultSet, "state")),
                    JdbcValues.instant(resultSet, "created_at"), JdbcValues.instant(resultSet, "updated_at"));
        }
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
        bindSnapshot(statement, meeting, JdbcDatabaseEngine.MYSQL);
    }

    private static void bindSnapshot(
            PreparedStatement statement,
            MeetingAggregate.MeetingSnapshot meeting,
            JdbcDatabaseEngine engine) throws SQLException {
        if (engine == JdbcDatabaseEngine.MYSQL) {
            JdbcValues.setText(statement, 1, engine, meeting.meetingId());
            JdbcValues.setText(statement, 2, engine, meeting.deploymentScope());
            JdbcValues.setText(statement, 3, engine, meeting.clientSessionId());
            statement.setString(4, meeting.profileId());
            statement.setInt(5, meeting.profileRevision());
            statement.setString(6, meeting.state().name());
            JdbcValues.setInstant(statement, 7, meeting.createdAt());
            JdbcValues.setInstant(statement, 8, meeting.updatedAt());
            return;
        }
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

    private static void requireLegacyExistingTransition(
            MeetingAggregate.MeetingSnapshot before,
            MeetingAggregate.MeetingSnapshot after) {
        if (!isLegacy(before) || !isLegacy(after)) {
            throw new IllegalArgumentException(
                    "updateLegacyExisting requires legacy meeting snapshots");
        }
        if (!sameImmutableFacts(before, after)) {
            throw new IllegalArgumentException(
                    "updateLegacyExisting cannot change immutable meeting facts");
        }
        if (before.state() == after.state()
                || after.updatedAt().isBefore(before.updatedAt())) {
            throw new IllegalArgumentException(
                    "updateLegacyExisting requires a forward lifecycle transition");
        }
    }

    private static boolean sameImmutableFacts(
            MeetingAggregate.MeetingSnapshot before,
            MeetingAggregate.MeetingSnapshot after) {
        // legacy 快照的三个权益引用均为 null，必须用 Objects.equals 而不是实例 equals。
        return before.meetingId().equals(after.meetingId())
                && before.deploymentScope().equals(after.deploymentScope())
                && before.clientSessionId().equals(after.clientSessionId())
                && before.subjectScope().equals(after.subjectScope())
                && Objects.equals(before.grantId(), after.grantId())
                && Objects.equals(before.grantExpiresAt(), after.grantExpiresAt())
                && Objects.equals(before.usageReservationId(), after.usageReservationId())
                && before.profileId().equals(after.profileId())
                && before.profileRevision() == after.profileRevision()
                && before.createdAt().equals(after.createdAt());
    }

    private static void requireSaved(int affectedRows) throws SQLException {
        requireSaved(affectedRows, JdbcDatabaseEngine.MYSQL);
    }

    private static void requireSaved(int affectedRows, JdbcDatabaseEngine engine)
            throws SQLException {
        if (engine == JdbcDatabaseEngine.MYSQL) {
            // 1=首次插入，2=合法 legacy upsert 命中；0=守卫拒绝或值未变化。
            if (affectedRows != 1 && affectedRows != 2) {
                throw new SQLException("会议归属事实冲突，拒绝覆盖已有快照", "23000");
            }
            return;
        }
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
