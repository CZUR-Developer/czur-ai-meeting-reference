package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.manifest.ManifestResultAccessPolicy;
import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.json.MeetingManifestJson;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 结果 URL 使用的 JDBC Manifest 查询端口。
 *
 * <p>一次 {@code SELECT} statement 通过 PostgreSQL {@code LATERAL} 子查询读取
 * meeting 行和该会议的最新 Manifest revision。READ COMMITTED 下这两个读取共享
 * 同一个 statement snapshot，避免先查会议、再查结果时把生成中的状态和另一版
 * Manifest 拼成不存在的组合。该类只读取事实，不创建独立业务事务，也不负责
 * 登录、结果链接生成或 Artifact 内容下载。</p>
 *
 * <p>Manifest JSON 在返回前仍按 canonical 文本、JSONB 镜像、SHA-256、身份、
 * revision 和前序链做关闭式校验。删除状态由访问策略优先撤权，即使事实表仍
 * 保留旧 revision 也不会返回旧结果。</p>
 */
public final class JdbcMeetingManifestQueryPort implements MeetingManifestPort {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcMeetingManifestQueryPort.class.getName());

    /**
     * latest 使用单条 lateral 查询；previous_exists 也在同一 statement snapshot
     * 中确认，避免把一个不存在前序的 latest 当成完整结果。
     */
    private static final String GET_SQL = """
            SELECT m.meeting_id, m.profile_id, m.profile_revision, m.state,
                   m.input_state, m.finalization_receipt_id, m.manifest_state,
                   m.updated_at,
                   latest.manifest_revision, latest.previous_revision,
                   latest.canonical_json, latest.manifest_json_text,
                   latest.content_sha256, latest.chain_valid
              FROM meeting AS m
              LEFT JOIN LATERAL (
                   SELECT r.manifest_revision,
                          r.previous_revision,
                          r.canonical_json,
                          r.manifest_json::text AS manifest_json_text,
                          r.content_sha256,
                          CASE
                            WHEN r.manifest_revision = 1
                              THEN r.previous_revision IS NULL
                            ELSE r.previous_revision IS NOT NULL
                              AND EXISTS (
                                  SELECT 1
                                    FROM meeting_manifest_revision AS p
                                   WHERE p.meeting_id = r.meeting_id
                                     AND p.manifest_revision = r.previous_revision)
                          END AS chain_valid
                     FROM meeting_manifest_revision AS r
                    WHERE r.meeting_id = m.meeting_id
                    ORDER BY r.manifest_revision DESC
                    LIMIT 1
              ) AS latest ON TRUE
             WHERE m.meeting_id = ?
            """;

    private final DataSource dataSource;
    private final ManifestResultAccessPolicy accessPolicy;

    /**
     * 创建查询端口。
     *
     * @param dataSource 由统一数据库运行时管理生命周期的连接池
     * @param accessPolicy genTime 和状态访问策略；TTL 不在 JDBC 中硬编码
     */
    public JdbcMeetingManifestQueryPort(
            DataSource dataSource,
            ManifestResultAccessPolicy accessPolicy) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    @Override
    public Result get(Request request) {
        Objects.requireNonNull(request, "request");
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            Result result = readOne(connection, request);
            return logged(result);
        } catch (SQLException failure) {
            return fromSqlFailure(failure);
        } catch (CorruptManifestSnapshot failure) {
            return logged(new Conflict(ConflictReason.SNAPSHOT_INCONSISTENT));
        } catch (RuntimeException failure) {
            // DataSource、时钟或 JDBC 代理可能抛出没有 SQLState 的运行时异常；
            // 不把驱动消息和数据库地址透传给结果协议。
            LOGGER.log(Level.WARNING,
                    "Manifest 结果查询失败 exception={0}",
                    failure.getClass().getSimpleName());
            return logged(new Unavailable(UnavailableReason.STORAGE_FAILURE, false));
        } finally {
            closeQuietly(connection);
        }
    }

    /** 单条 statement 读取并先做不需要 Manifest 正文的访问决策。 */
    private Result readOne(Connection connection, Request request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(GET_SQL)) {
            statement.setString(1, request.meetingId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return new NotFound();
                }
                RawSnapshot snapshot = readRawSnapshot(rows);
                if (rows.next()) {
                    throw new CorruptManifestSnapshot();
                }

                // 访问策略先用空 latest 运行：删除撤权、genTime 过期和生成中状态
                // 都不需要解析正文。这样删除后即使历史 JSON 已损坏，也只返回
                // AccessDenied，不把内部事实健康状况暴露给匿名结果链接。
                Result preliminary = accessPolicy.evaluate(
                        request, snapshot.meeting(), Optional.empty());
                if (preliminary.status() == Status.ACCESS_DENIED) {
                    return preliminary;
                }
                if (snapshot.latestRevision() == null) {
                    return preliminary;
                }
                if (snapshot.meeting().manifestState() == ManifestStatus.NOT_STARTED
                        || snapshot.meeting().manifestState() == ManifestStatus.GENERATING) {
                    // Pending 只允许 revision 数为零；有任意 revision 都是状态损坏。
                    return new Conflict(ConflictReason.SNAPSHOT_INCONSISTENT);
                }
                MeetingManifest latest = decodeLatest(snapshot);
                return accessPolicy.evaluate(request, snapshot.meeting(), Optional.of(latest));
            }
        }
    }

    private static RawSnapshot readRawSnapshot(ResultSet rows) throws SQLException {
        try {
            String meetingId = requiredText(rows, "meeting_id");
            String profileId = requiredText(rows, "profile_id");
            int profileRevision = rows.getInt("profile_revision");
            if (rows.wasNull() || profileRevision < 1) {
                throw new CorruptManifestSnapshot();
            }
            MeetingState meetingState = readEnum(rows, "state", MeetingState.class);
            if (meetingState == MeetingState.UNKNOWN) {
                throw new CorruptManifestSnapshot();
            }
            InputState inputState = readEnum(rows, "input_state", InputState.class);
            String receipt = rows.getString("finalization_receipt_id");
            ManifestStatus manifestState = readEnum(
                    rows, "manifest_state", ManifestStatus.class);
            Timestamp updatedAt = rows.getTimestamp("updated_at");
            if (updatedAt == null) {
                throw new CorruptManifestSnapshot();
            }
            MeetingStatus status = new MeetingStatus(
                    meetingId,
                    meetingState,
                    new FinalizationFact(inputState, receipt),
                    manifestState,
                    updatedAt.toInstant());

            int latestRevisionValue = rows.getInt("manifest_revision");
            Integer latestRevision = rows.wasNull() ? null : latestRevisionValue;
            int previousRevisionValue = rows.getInt("previous_revision");
            Integer previousRevision = rows.wasNull() ? null : previousRevisionValue;
            String canonicalJson = rows.getString("canonical_json");
            String manifestJsonText = rows.getString("manifest_json_text");
            byte[] contentSha256 = rows.getBytes("content_sha256");
            Boolean chainValid = nullableChainValid(rows);
            if (latestRevision == null) {
                // LEFT JOIN 的其余列必须全部为空；半行不是“没有结果”。
                if (previousRevision != null
                        || canonicalJson != null
                        || manifestJsonText != null
                        || contentSha256 != null) {
                    throw new CorruptManifestSnapshot();
                }
                if (chainValid != null) {
                    throw new CorruptManifestSnapshot();
                }
            } else if (latestRevision < 1
                    || (latestRevision == 1 && previousRevision != null)
                    || (latestRevision > 1
                        && (previousRevision == null
                            || previousRevision != latestRevision - 1))
                    || !Boolean.TRUE.equals(chainValid)) {
                throw new CorruptManifestSnapshot();
            }
            return new RawSnapshot(
                    status,
                    profileId,
                    profileRevision,
                    latestRevision,
                    previousRevision,
                    canonicalJson,
                    manifestJsonText,
                    contentSha256,
                    chainValid);
        } catch (CorruptManifestSnapshot failure) {
            throw failure;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new CorruptManifestSnapshot();
        }
    }

    private static MeetingManifest decodeLatest(RawSnapshot snapshot) {
        if (snapshot.latestRevision() == null
                || snapshot.canonicalJson() == null
                || snapshot.manifestJsonText() == null
                || snapshot.contentSha256() == null
                || snapshot.contentSha256().length != 32
                || !Boolean.TRUE.equals(snapshot.chainValid())) {
            throw new CorruptManifestSnapshot();
        }
        String canonical = snapshot.canonicalJson();
        MeetingManifest manifest = MeetingManifestJson.decode(canonical);
        if (!canonical.equals(MeetingManifestJson.encode(manifest))
                || !snapshot.meeting().meetingId().equals(manifest.meetingId())
                || snapshot.latestRevision() != manifest.manifestRevision()
                || !MessageDigest.isEqual(
                        snapshot.contentSha256(), sha256(canonical))) {
            throw new CorruptManifestSnapshot();
        }
        MeetingManifest mirror = MeetingManifestJson.decode(snapshot.manifestJsonText());
        if (!manifest.equals(mirror)
                || !canonical.equals(MeetingManifestJson.encode(mirror))) {
            throw new CorruptManifestSnapshot();
        }
        // 结果页不能把另一个 Profile 的产物当成本会议结果；Profile 是 meeting
        // 事实，不属于 Manifest 内的可选展示字段，因此这里显式核对。
        if (!snapshot.profileId().equals(manifest.profile().id())
                || snapshot.profileRevision() != manifest.profile().revision()) {
            throw new CorruptManifestSnapshot();
        }
        return manifest;
    }

    private static <E extends Enum<E>> E readEnum(
            ResultSet rows, String column, Class<E> type) throws SQLException {
        String value = requiredText(rows, column);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException failure) {
            throw new CorruptManifestSnapshot();
        }
    }

    private static String requiredText(ResultSet rows, String column) throws SQLException {
        String value = rows.getString(column);
        if (value == null || value.isBlank()) {
            throw new CorruptManifestSnapshot();
        }
        return value;
    }

    private static Boolean nullableChainValid(ResultSet rows) throws SQLException {
        boolean value = rows.getBoolean("chain_valid");
        return rows.wasNull() ? null : value;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static Result fromSqlFailure(SQLException failure) {
        JdbcReadLookupSupport.FailureKind kind = JdbcReadLookupSupport.classify(failure);
        if (kind == JdbcReadLookupSupport.FailureKind.INCONSISTENT) {
            return logged(new Conflict(ConflictReason.SNAPSHOT_INCONSISTENT), failure);
        }
        boolean retryable = kind == JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE;
        return logged(new Unavailable(
                retryable ? UnavailableReason.TRANSIENT_STORAGE_FAILURE
                        : UnavailableReason.STORAGE_FAILURE,
                retryable), failure);
    }

    private static Result logged(Result result) {
        Level level = result.status() == Status.UNAVAILABLE ? Level.WARNING : Level.FINE;
        LOGGER.log(level, "Manifest 结果查询完成 status={0}", result.status());
        return result;
    }

    private static Result logged(Result result, SQLException failure) {
        Level level = result.status() == Status.UNAVAILABLE ? Level.WARNING : Level.FINE;
        LOGGER.log(level, "Manifest 结果查询完成 status={0} sqlState={1}",
                new Object[]{result.status(), JdbcReadLookupSupport.safeSqlState(failure)});
        return result;
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "Manifest 结果查询关闭连接失败");
        }
    }

    private record RawSnapshot(
            MeetingStatus meeting,
            String profileId,
            int profileRevision,
            Integer latestRevision,
            Integer previousRevision,
            String canonicalJson,
            String manifestJsonText,
            byte[] contentSha256,
            Boolean chainValid) {
        private RawSnapshot {
            Objects.requireNonNull(meeting, "meeting");
            Objects.requireNonNull(profileId, "profileId");
        }
    }

    /** 固定异常不携带数据库列值、正文、会议标识或驱动消息。 */
    private static final class CorruptManifestSnapshot extends RuntimeException {
        private CorruptManifestSnapshot() {
            super("stored Manifest query snapshot is invalid", null, false, false);
        }
    }
}
