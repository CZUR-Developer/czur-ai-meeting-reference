package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.deletion.ArtifactDeletionTarget;
import com.czur.reference.application.deletion.MeetingDeletionPlan;
import com.czur.reference.application.deletion.MeetingDeletionPlan.Item;
import com.czur.reference.application.deletion.MeetingDeletionPlan.ItemStatus;
import com.czur.reference.application.deletion.MeetingDeletionPlan.PlanStatus;
import com.czur.reference.application.port.ArtifactDeletionPlanStore;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.protocol.json.ArtifactDeletionPlanJson;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * V012 删除计划和逐项审计的连接级 JDBC 参考实现。
 *
 * <p>本类只接受调用方已经取得且已关闭 auto-commit 的连接，不创建、提交、回滚或
 * 关闭连接。注册先锁定 Meeting 行以串行化同一会议的首次写入，再以 {@code NOWAIT}
 * 锁定已有计划行；因此正常并发注册会在 Meeting 锁上排队并得到 APPLIED/REPLAYED，
 * 外部持有计划行锁时则明确返回 {@link StoreErrorCode#LOCK_CONFLICT}。本切片不推进
 * Meeting、Workflow 或 Artifact Store。</p>
 *
 * <p>读取会重新解码 canonical JSON、核对原始 SHA-256、JSONB 语义镜像、来源列、
 * target_count 以及逐项 ordinal/type/revision/status。任何持久事实漂移都以
 * CORRUPT_DATA 关闭式失败；异常和日志不携带会议、产物、正文、摘要、binding 或
 * 底层 SQL 信息。</p>
 */
public final class JdbcArtifactDeletionPlanStore implements ArtifactDeletionPlanStore {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcArtifactDeletionPlanStore.class.getName());
    private static final String FIND_PLAN_SQL = """
            SELECT meeting_id, accepted_at, source_manifest_revision,
                   source_workflow_step_type, source_workflow_step_key,
                   source_workflow_checkpoint_type, source_workflow_content_digest,
                   target_count, canonical_json, deletion_plan_json::text AS jsonb_text,
                   deletion_plan_json = canonical_json::jsonb AS jsonb_matches,
                   content_sha256, status, completed_at
              FROM meeting_deletion_plan
             WHERE meeting_id = ?
            """;
    private static final String FIND_PLAN_FOR_UPDATE_NOWAIT_SQL = FIND_PLAN_SQL
            + " FOR UPDATE NOWAIT";
    private static final String LOCK_MEETING_SQL = """
            SELECT meeting_id FROM meeting WHERE meeting_id = ? FOR UPDATE
            """;
    private static final String INSERT_PLAN_SQL = """
            INSERT INTO meeting_deletion_plan (
                meeting_id, accepted_at, source_manifest_revision,
                source_workflow_step_type, source_workflow_step_key,
                source_workflow_checkpoint_type, source_workflow_content_digest,
                target_count, canonical_json, deletion_plan_json, content_sha256, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING')
            ON CONFLICT (meeting_id) DO NOTHING
            """;
    private static final String INSERT_ITEM_SQL = """
            INSERT INTO artifact_deletion_item (
                meeting_id, ordinal, artifact_id, artifact_type, revision, status)
            VALUES (?, ?, ?, ?, ?, 'PENDING')
            """;

    private final Connection connection;

    /** 绑定调用方当前连接；构造过程不访问数据库，也不接管连接生命周期。 */
    public JdbcArtifactDeletionPlanStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public Optional<MeetingDeletionPlan> find(String meetingId) {
        requireMeetingId(meetingId);
        requireExplicitTransaction();
        try {
            MeetingDeletionPlan result = select(meetingId, false).orElse(null);
            logResult("FIND", result == null ? "EMPTY" : "PRESENT");
            return Optional.ofNullable(result);
        } catch (StoredFactException failure) {
            logFailure("FIND", failure.code(), "N/A");
            throw failure;
        } catch (SQLException failure) {
            throw sqlFailure("FIND", failure);
        } catch (RuntimeException failure) {
            throw runtimeFailure("FIND");
        }
    }

    /** 在终态事务中锁定计划行；调用方必须已经锁定 Meeting 与 Workflow。 */
    Optional<MeetingDeletionPlan> findForUpdate(String meetingId) {
        requireMeetingId(meetingId);
        requireExplicitTransaction();
        try {
            return select(meetingId, false, true);
        } catch (StoredFactException failure) {
            logFailure("FIND_FOR_UPDATE", failure.code(), "N/A");
            throw failure;
        } catch (SQLException failure) {
            throw sqlFailure("FIND_FOR_UPDATE", failure);
        } catch (RuntimeException failure) {
            throw runtimeFailure("FIND_FOR_UPDATE");
        }
    }

    @Override
    public PersistResult register(ArtifactDeletionPlan candidate) {
        Objects.requireNonNull(candidate, "candidate");
        requireExplicitTransaction();
        final String canonical = ArtifactDeletionPlanJson.encode(candidate);
        final byte[] digest = sha256(canonical);
        try {
            // Meeting 是 V012 所有删除事实的上游锁；同一会议的正常并发在此排队。
            lockMeeting(candidate.meetingId());
            int inserted;
            try (PreparedStatement statement = connection.prepareStatement(INSERT_PLAN_SQL)) {
                bindPlan(statement, candidate, canonical, digest);
                inserted = statement.executeUpdate();
            } catch (SQLException failure) {
                if (isLockConflict(failure)) {
                    throw new StoredFactException(StoreErrorCode.LOCK_CONFLICT);
                }
                throw failure;
            }
            if (inserted == 1) {
                insertItems(candidate);
            }
            // 读取计划行时 NOWAIT，能把外部仅持有删除计划行的竞争明确分类。
            MeetingDeletionPlan winner = select(candidate.meetingId(), true)
                    .orElseThrow(JdbcArtifactDeletionPlanStore::corruptData);
            if (inserted == 1) {
                if (!winner.plan().equals(candidate)
                        || !canonical.equals(ArtifactDeletionPlanJson.encode(winner.plan()))
                        || !MessageDigest.isEqual(digest, sha256(canonical))) {
                    throw corruptData();
                }
                logResult("REGISTER", "APPLIED");
                return new PersistResult(PersistStatus.APPLIED, winner);
            }
            PersistStatus status = winner.plan().equals(candidate)
                    ? PersistStatus.REPLAYED : PersistStatus.CONFLICT;
            logResult("REGISTER", status.name());
            return new PersistResult(status, winner);
        } catch (StoredFactException failure) {
            logFailure("REGISTER", failure.code(), "N/A");
            throw failure;
        } catch (SQLException failure) {
            throw sqlFailure("REGISTER", failure);
        } catch (RuntimeException failure) {
            throw runtimeFailure("REGISTER");
        }
    }

    private void lockMeeting(String meetingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_MEETING_SQL)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null
                        || !meetingId.equals(rows.getString(1)) || rows.next()) {
                    throw corruptData();
                }
            }
        }
    }

    /** 将已锁定且所有 item 已 DELETED 的计划置为 COMPLETED。 */
    static boolean complete(Connection connection, String meetingId, Instant completedAt)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(meetingId, "meetingId");
        requirePersistentInstant(completedAt);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE meeting_deletion_plan
                   SET status = 'COMPLETED', completed_at = ?
                 WHERE meeting_id = ? AND status = 'PENDING' AND completed_at IS NULL
                """)) {
            statement.setTimestamp(1, Timestamp.from(completedAt));
            statement.setString(2, meetingId);
            return statement.executeUpdate() == 1;
        }
    }

    private Optional<MeetingDeletionPlan> select(String meetingId, boolean nowait)
            throws SQLException {
        return select(meetingId, nowait, false);
    }

    private Optional<MeetingDeletionPlan> select(
            String meetingId, boolean nowait, boolean forUpdate) throws SQLException {
        String sql = nowait ? FIND_PLAN_FOR_UPDATE_NOWAIT_SQL
                : (forUpdate ? FIND_PLAN_SQL + " FOR UPDATE" : FIND_PLAN_SQL);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                MeetingDeletionPlan result = readPlan(rows, meetingId);
                if (rows.next()) {
                    throw corruptData();
                }
                return Optional.of(result);
            }
        } catch (SQLException failure) {
            if (nowait && isLockConflict(failure)) {
                throw new StoredFactException(StoreErrorCode.LOCK_CONFLICT);
            }
            throw failure;
        }
    }

    private MeetingDeletionPlan readPlan(ResultSet rows, String requestedMeetingId)
            throws SQLException {
        try {
            String meetingId = requiredText(rows, "meeting_id");
            requireStored(meetingId.equals(requestedMeetingId));
            Timestamp accepted = rows.getTimestamp("accepted_at");
            requireStored(accepted != null);
            Instant acceptedAt = accepted.toInstant();
            requirePersistentInstant(acceptedAt);
            int sourceRevision = rows.getInt("source_manifest_revision");
            Integer sourceManifestRevision = rows.wasNull() ? null : sourceRevision;
            String sourceStepType = nullableText(rows, "source_workflow_step_type");
            String sourceStepKey = nullableText(rows, "source_workflow_step_key");
            String sourceCheckpoint = nullableText(rows, "source_workflow_checkpoint_type");
            String sourceDigest = nullableText(rows, "source_workflow_content_digest");
            int targetCount = rows.getInt("target_count");
            requireStored(!rows.wasNull() && targetCount >= 0
                    && targetCount <= ArtifactDeletionPlan.MAX_TARGETS);
            String canonical = requiredText(rows, "canonical_json");
            String jsonbText = requiredText(rows, "jsonb_text");
            requireStored(rows.getBoolean("jsonb_matches") && !rows.wasNull());
            byte[] storedDigest = rows.getBytes("content_sha256");
            requireStored(storedDigest != null && storedDigest.length == 32);
            requireStored(MessageDigest.isEqual(storedDigest, sha256(canonical)));

            ArtifactDeletionPlan plan = ArtifactDeletionPlanJson.decode(canonical);
            requireStored(plan.meetingId().equals(meetingId)
                    && plan.acceptedAt().equals(acceptedAt)
                    && plan.targets().size() == targetCount);
            requireStored(java.util.Objects.equals(plan.sourceManifestRevision(),
                    sourceManifestRevision));
            ArtifactDeletionPlan.SourceFinalizationWorkflow source =
                    plan.sourceFinalizationWorkflow();
            if (source == null) {
                requireStored(sourceStepType == null && sourceStepKey == null
                        && sourceCheckpoint == null && sourceDigest == null);
            } else {
                requireStored(source.key().stepType().equals(sourceStepType)
                        && source.key().stepKey().equals(sourceStepKey)
                        && source.checkpointType().equals(sourceCheckpoint)
                        && source.contentDigest().equals(sourceDigest));
            }
            requireStored(canonical.equals(ArtifactDeletionPlanJson.encode(plan)));

            String statusText = requiredText(rows, "status");
            PlanStatus status;
            try {
                status = PlanStatus.valueOf(statusText);
            } catch (IllegalArgumentException malformed) {
                throw corruptData();
            }
            Timestamp completed = rows.getTimestamp("completed_at");
            Instant completedAt = completed == null ? null : completed.toInstant();
            if (completedAt != null) {
                requirePersistentInstant(completedAt);
            }
            List<Item> items = readItems(meetingId, plan);
            return new MeetingDeletionPlan(plan, status, items, completedAt);
        } catch (StoredFactException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw corruptData();
        }
    }

    private List<Item> readItems(String meetingId, ArtifactDeletionPlan plan)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ordinal, artifact_id, artifact_type, revision, status,
                       storage_binding, deleted_at
                  FROM artifact_deletion_item
                 WHERE meeting_id = ?
                 ORDER BY ordinal
                """)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Item> result = new ArrayList<>();
                while (rows.next()) {
                    int ordinal = rows.getInt("ordinal");
                    requireStored(!rows.wasNull() && ordinal == result.size()
                            && ordinal < plan.targets().size());
                    String artifactId = requiredText(rows, "artifact_id");
                    String artifactType = requiredText(rows, "artifact_type");
                    int revision = rows.getInt("revision");
                    requireStored(!rows.wasNull());
                    ArtifactDeletionTarget expected = plan.targets().get(ordinal);
                    WriteIntentSpec spec = expected.spec();
                    requireStored(expected.artifactId().equals(artifactId)
                            && spec.artifactType().equals(artifactType)
                            && spec.revision() == revision);
                    ItemStatus status;
                    try {
                        status = ItemStatus.valueOf(requiredText(rows, "status"));
                    } catch (IllegalArgumentException malformed) {
                        throw corruptData();
                    }
                    String binding = nullableText(rows, "storage_binding");
                    Timestamp deleted = rows.getTimestamp("deleted_at");
                    Instant deletedAt = deleted == null ? null : deleted.toInstant();
                    if (deletedAt != null) {
                        requirePersistentInstant(deletedAt);
                    }
                    result.add(new Item(ordinal, expected, status, binding, deletedAt));
                }
                requireStored(result.size() == plan.targets().size());
                return List.copyOf(result);
            }
        }
    }

    private void insertItems(ArtifactDeletionPlan candidate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_ITEM_SQL)) {
            for (int ordinal = 0; ordinal < candidate.targets().size(); ordinal++) {
                ArtifactDeletionTarget target = candidate.targets().get(ordinal);
                statement.setString(1, candidate.meetingId());
                statement.setInt(2, ordinal);
                statement.setString(3, target.artifactId());
                statement.setString(4, target.spec().artifactType());
                statement.setInt(5, target.spec().revision());
                if (statement.executeUpdate() != 1) {
                    throw corruptData();
                }
            }
        }
    }

    private static void bindPlan(PreparedStatement statement, ArtifactDeletionPlan plan,
            String canonical, byte[] digest) throws SQLException {
        statement.setString(1, plan.meetingId());
        statement.setTimestamp(2, Timestamp.from(plan.acceptedAt()));
        if (plan.sourceManifestRevision() == null) {
            statement.setNull(3, Types.INTEGER);
        } else {
            statement.setInt(3, plan.sourceManifestRevision());
        }
        ArtifactDeletionPlan.SourceFinalizationWorkflow source =
                plan.sourceFinalizationWorkflow();
        if (source == null) {
            statement.setNull(4, Types.VARCHAR);
            statement.setNull(5, Types.VARCHAR);
            statement.setNull(6, Types.VARCHAR);
            statement.setNull(7, Types.VARCHAR);
        } else {
            statement.setString(4, source.key().stepType());
            statement.setString(5, source.key().stepKey());
            statement.setString(6, source.checkpointType());
            statement.setString(7, source.contentDigest());
        }
        statement.setInt(8, plan.targets().size());
        statement.setString(9, canonical);
        statement.setString(10, canonical);
        statement.setBytes(11, digest);
    }

    private void requireExplicitTransaction() {
        try {
            if (connection.getAutoCommit()) {
                throw new StoredFactException(StoreErrorCode.TRANSACTION_REQUIRED);
            }
        } catch (StoredFactException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw new StoredFactException(StoreErrorCode.STORAGE_UNAVAILABLE);
        }
    }

    private static String requiredText(ResultSet rows, String column) throws SQLException {
        String value = rows.getString(column);
        if (value == null || value.isBlank()) {
            throw corruptData();
        }
        return value;
    }

    private static String nullableText(ResultSet rows, String column) throws SQLException {
        String value = rows.getString(column);
        if (value != null && value.isBlank()) {
            throw corruptData();
        }
        return value;
    }

    private static void requireMeetingId(String meetingId) {
        if (meetingId == null || meetingId.isBlank()) {
            throw new IllegalArgumentException("meetingId must not be blank");
        }
    }

    private static void requirePersistentInstant(Instant value) {
        if (value == null || value.getNano() % 1_000 != 0) {
            throw corruptData();
        }
    }

    private static void requireStored(boolean condition) {
        if (!condition) {
            throw corruptData();
        }
    }

    private static byte[] sha256(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256");
        }
    }

    private static boolean isLockConflict(SQLException failure) {
        return "55P03".equals(failure.getSQLState());
    }

    private static StoredFactException corruptData() {
        return new StoredFactException(StoreErrorCode.CORRUPT_DATA);
    }

    private static StoredFactException sqlFailure(String phase, SQLException failure) {
        StoreErrorCode code = isLockConflict(failure)
                ? StoreErrorCode.LOCK_CONFLICT : StoreErrorCode.STORAGE_UNAVAILABLE;
        logFailure(phase, code, JdbcReadLookupSupport.safeSqlState(failure));
        return new StoredFactException(code);
    }

    private static StoredFactException runtimeFailure(String phase) {
        logFailure(phase, StoreErrorCode.STORAGE_UNAVAILABLE, "N/A");
        return new StoredFactException(StoreErrorCode.STORAGE_UNAVAILABLE);
    }

    private static void logResult(String phase, String result) {
        LOGGER.log(Level.FINE, "JDBC 删除计划 Store phase={0} result={1}",
                new Object[]{phase, result});
    }

    private static void logFailure(String phase, StoreErrorCode code, String sqlState) {
        LOGGER.log(Level.WARNING,
                "JDBC 删除计划 Store failure phase={0} code={1} sqlState={2}",
                new Object[]{phase, code, sqlState});
    }

    static final class StoredFactException extends ArtifactDeletionPlanStore.StoreException {
        private StoredFactException(StoreErrorCode code) {
            super(code);
        }
    }
}
