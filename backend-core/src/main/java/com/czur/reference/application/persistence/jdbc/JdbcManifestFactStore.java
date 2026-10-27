package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.ManifestFactStore;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.protocol.json.MeetingManifestJson;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 在调用方当前 JDBC 事务中读写追加式 Manifest revision 的最小存储原语。
 *
 * <p>该类刻意保持包可见，且构造参数只能是调用方已经取得的 {@link Connection}。
 * 它不创建、提交、回滚、恢复或关闭连接，也不改变 auto-commit；T14-03 后续发布
 * 工作单元负责 meeting 行锁、状态推进和事务结果裁决。每次操作都要求连接已经处于
 * 显式事务中，避免多条读取与插入语句在不同数据库事务中执行。</p>
 *
 * <p>写入只接受 {@link MeetingManifestJson#encode(MeetingManifest)} 产生的 canonical
 * 文本。JSON 参数来自同一文本，摘要是该文本 UTF-8 字节的原始 SHA-256。读取会
 * 重新计算摘要、严格解码并重新编码 canonical 文本、验证 JSON 镜像，并核对行键、
 * 文档身份和 revision 链；任一不一致都关闭式失败。</p>
 *
 * <p>日志和异常只包含固定阶段、结果类别、revision、异常类别及白名单 SQLState，
 * 绝不输出 meetingId、Manifest 正文、标题、产物、告警、摘要或数据库地址。</p>
 */
final class JdbcManifestFactStore implements ManifestFactStore {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcManifestFactStore.class.getName());
    private static final Pattern MEETING_ID = Pattern.compile("^mtg_[A-Za-z0-9_-]+$");

    private static final String FIND_LATEST_SQL = """
            SELECT meeting_id, manifest_revision, previous_revision,
                   canonical_json, manifest_json AS manifest_json_text,
                   content_sha256, recorded_at
              FROM meeting_manifest_revision
             WHERE meeting_id = ?
             ORDER BY manifest_revision DESC
             LIMIT 1
            """;
    private static final String FIND_REVISION_SQL = """
            SELECT meeting_id, manifest_revision, previous_revision,
                   canonical_json, manifest_json AS manifest_json_text,
                   content_sha256, recorded_at
              FROM meeting_manifest_revision
             WHERE meeting_id = ? AND manifest_revision = ?
            """;
    private static final String INSERT_REVISION_SQL = """
            INSERT INTO meeting_manifest_revision (
                meeting_id, manifest_revision, previous_revision,
                canonical_json, manifest_json, content_sha256)
            VALUES (?, ?, ?, ?, CAST(? AS JSON), ?)
            """;

    private final Connection connection;

    /**
     * 绑定调用方当前连接；构造过程不访问数据库，也不接管连接生命周期。
     *
     * @param connection 后续发布工作单元持有的当前 JDBC 连接
     */
    JdbcManifestFactStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 在当前事务快照中按复合主键的 revision 逆序读取最新事实。
     *
     * <p>查询不会锁 meeting 行，也不会打开独立事务。调用方若需要把读取与会议状态
     * 推进组成原子操作，必须先按 T14-03 规则锁定 meeting 行。</p>
     */
    @Override
    public Optional<MeetingManifest> findLatest(String meetingId) {
        requireMeetingId(meetingId);
        return findLatestInCurrentTransaction(meetingId);
    }

    /**
     * 读取已由同一事务锁定的 Meeting 所对应的 latest revision。
     *
     * <p>历史兼容 Meeting 的标识可能早于公开 {@code mtg_...} 协议，因此删除
     * 受理不能在确认“没有 revision”之前先用新协议格式拒绝它。该内部入口只供
     * 已经按数据库 meeting 主键完成锁读和归属校验的工作单元使用；若历史标识
     * 没有 revision，就返回空并允许只推进 Meeting 删除状态。若数据库中反而
     * 存在这种 revision，严格 Manifest 解码仍会把它判为损坏，不能绕过公开
     * 协议校验。</p>
     */
    Optional<MeetingManifest> findLatestForLockedMeeting(String meetingId) {
        if (meetingId == null || meetingId.isBlank()) {
            throw new IllegalArgumentException("locked meetingId must not be blank");
        }
        return findLatestInCurrentTransaction(meetingId);
    }

    private Optional<MeetingManifest> findLatestInCurrentTransaction(String meetingId) {
        requireExplicitTransaction();
        try {
            Optional<StoredRevision> latest = selectLatest(meetingId);
            LOGGER.log(Level.FINE,
                    "Manifest latest 读取完成 result={0} revision={1}",
                    new Object[]{latest.isPresent() ? "PRESENT" : "EMPTY",
                            latest.map(StoredRevision::revision).orElse(0)});
            return latest.map(StoredRevision::manifest);
        } catch (StoredFactException safeFailure) {
            logFailure("FIND_LATEST", safeFailure.kind(), "N/A");
            throw safeFailure;
        } catch (SQLException failure) {
            throw sqlFailure("FIND_LATEST", failure);
        } catch (RuntimeException failure) {
            throw runtimeFailure("FIND_LATEST");
        }
    }

    /**
     * 按“目标 revision 优先、latest 前序其次、插入冲突后重读”的顺序追加事实。
     *
     * <p>先读取目标 revision 能使超时重试即使落后于后续 revision，也稳定返回
     * REPLAYED 或 CONTENT_CONFLICT。只有目标不存在时才读取 latest 并比较调用方
     * 声明的前序。插入把重复键 1062 解释为“未取得写入权”；并发输家必须重读赢家
     * 的完整事实后再分类，不能仅凭摘要判断重放。</p>
     */
    @Override
    public AppendResult append(AppendRequest request) {
        Objects.requireNonNull(request, "request");
        requireExplicitTransaction();
        MeetingManifest candidate = request.manifest();
        String canonical = MeetingManifestJson.encode(candidate);
        byte[] contentSha256 = sha256(canonical);
        int revision = candidate.manifestRevision();
        try {
            Optional<StoredRevision> target = selectRevision(
                    candidate.meetingId(), revision);
            if (target.isPresent()) {
                return loggedResult(revision, classifyExisting(target.get(), canonical));
            }

            Optional<StoredRevision> latest = selectLatest(candidate.meetingId());
            if (!matchesExpectedPrevious(latest, request.expectedPreviousRevision())) {
                return loggedResult(revision,
                        new AppendResult(AppendStatus.VERSION_CONFLICT));
            }

            int inserted = insert(request, canonical, contentSha256);
            if (inserted != 0 && inserted != 1) {
                throw new StoredFactException(FailureKind.CORRUPT_DATA);
            }

            StoredRevision persisted = selectRevision(candidate.meetingId(), revision)
                    .orElseThrow(() -> new StoredFactException(FailureKind.CORRUPT_DATA));
            AppendResult classified = classifyExisting(persisted, canonical);
            if (inserted == 1) {
                if (classified.status() != AppendStatus.REPLAYED) {
                    throw new StoredFactException(FailureKind.CORRUPT_DATA);
                }
                return loggedResult(revision, new AppendResult(AppendStatus.APPENDED));
            }
            return loggedResult(revision, classified);
        } catch (StoredFactException safeFailure) {
            logFailure("APPEND", safeFailure.kind(), "N/A");
            throw safeFailure;
        } catch (SQLException failure) {
            throw sqlFailure("APPEND", failure);
        } catch (RuntimeException failure) {
            throw runtimeFailure("APPEND");
        }
    }

    private Optional<StoredRevision> selectLatest(String meetingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_LATEST_SQL)) {
            statement.setString(1, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                return readSingle(rows, meetingId, null);
            }
        }
    }

    private Optional<StoredRevision> selectRevision(String meetingId, int revision)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_REVISION_SQL)) {
            statement.setString(1, meetingId);
            statement.setInt(2, revision);
            try (ResultSet rows = statement.executeQuery()) {
                return readSingle(rows, meetingId, revision);
            }
        }
    }

    private static Optional<StoredRevision> readSingle(
            ResultSet rows, String requestedMeetingId, Integer requestedRevision)
            throws SQLException {
        if (!rows.next()) {
            return Optional.empty();
        }
        StoredRevision revision = validateRow(rows, requestedMeetingId, requestedRevision);
        if (rows.next()) {
            throw new StoredFactException(FailureKind.CORRUPT_DATA);
        }
        return Optional.of(revision);
    }

    private static StoredRevision validateRow(
            ResultSet rows, String requestedMeetingId, Integer requestedRevision)
            throws SQLException {
        try {
            String rowMeetingId = rows.getString("meeting_id");
            int rowRevision = rows.getInt("manifest_revision");
            if (rows.wasNull()) {
                throw new StoredFactException(FailureKind.CORRUPT_DATA);
            }
            int previousValue = rows.getInt("previous_revision");
            Integer previousRevision = rows.wasNull() ? null : previousValue;
            String canonical = rows.getString("canonical_json");
            String jsonMirror = rows.getString("manifest_json_text");
            byte[] storedSha256 = rows.getBytes("content_sha256");
            Timestamp recordedAt = rows.getTimestamp("recorded_at");

            requireStored(rowMeetingId != null && rowMeetingId.equals(requestedMeetingId));
            requireStored(rowRevision >= 1);
            requireStored(requestedRevision == null || rowRevision == requestedRevision);
            requireStored(rowRevision == 1
                    ? previousRevision == null
                    : Objects.equals(previousRevision, rowRevision - 1));
            requireStored(canonical != null && jsonMirror != null && recordedAt != null);
            requireStored(storedSha256 != null && storedSha256.length == 32);
            requireStored(MessageDigest.isEqual(storedSha256, sha256(canonical)));

            MeetingManifest manifest = MeetingManifestJson.decode(canonical);
            requireStored(canonical.equals(MeetingManifestJson.encode(manifest)));
            requireStored(rowMeetingId.equals(manifest.meetingId()));
            requireStored(rowRevision == manifest.manifestRevision());

            MeetingManifest mirror = MeetingManifestJson.decode(jsonMirror);
            requireStored(canonical.equals(MeetingManifestJson.encode(mirror)));
            requireStored(manifest.equals(mirror));
            return new StoredRevision(rowRevision, canonical, manifest);
        } catch (StoredFactException safeFailure) {
            throw safeFailure;
        } catch (RuntimeException malformed) {
            throw new StoredFactException(FailureKind.CORRUPT_DATA);
        }
    }

    private int insert(AppendRequest request, String canonical, byte[] contentSha256)
            throws SQLException {
        MeetingManifest manifest = request.manifest();
        try (PreparedStatement statement = connection.prepareStatement(INSERT_REVISION_SQL)) {
            statement.setString(1, manifest.meetingId());
            statement.setInt(2, manifest.manifestRevision());
            if (request.expectedPreviousRevision()
                    instanceof ExpectedPreviousRevision.Present present) {
                statement.setInt(3, present.revision());
            } else {
                statement.setNull(3, Types.INTEGER);
            }
            statement.setString(4, canonical);
            // canonical_json 与 JSON 参数必须来自同一个 String 实例的内容。
            statement.setString(5, canonical);
            statement.setBytes(6, contentSha256);
            try {
                return statement.executeUpdate();
            } catch (SQLException duplicate) {
                // 复合主键重复 = 并发输家；其它完整性错误必须回到调用方。
                if (JdbcSqlErrors.isDuplicate(JdbcDatabaseEngine.MYSQL, duplicate)) {
                    return 0;
                }
                throw duplicate;
            }
        }
    }

    private static AppendResult classifyExisting(
            StoredRevision existing, String candidateCanonical) {
        return new AppendResult(existing.canonical().equals(candidateCanonical)
                ? AppendStatus.REPLAYED : AppendStatus.CONTENT_CONFLICT);
    }

    private static boolean matchesExpectedPrevious(
            Optional<StoredRevision> latest,
            ExpectedPreviousRevision expectedPreviousRevision) {
        if (expectedPreviousRevision instanceof ExpectedPreviousRevision.None) {
            return latest.isEmpty();
        }
        int expected = ((ExpectedPreviousRevision.Present) expectedPreviousRevision).revision();
        return latest.isPresent() && latest.get().revision() == expected;
    }

    private static AppendResult loggedResult(int revision, AppendResult result) {
        Level level = switch (result.status()) {
            case APPENDED, REPLAYED -> Level.FINE;
            case CONTENT_CONFLICT, VERSION_CONFLICT -> Level.WARNING;
        };
        LOGGER.log(level,
                "Manifest revision 追加完成 revision={0} status={1}",
                new Object[]{revision, result.status().name()});
        return result;
    }

    private void requireExplicitTransaction() {
        try {
            if (connection.getAutoCommit()) {
                throw new StoredFactException(FailureKind.TRANSACTION_REQUIRED);
            }
        } catch (StoredFactException safeFailure) {
            throw safeFailure;
        } catch (SQLException failure) {
            throw sqlFailure("TRANSACTION_CHECK", failure);
        } catch (RuntimeException failure) {
            throw runtimeFailure("TRANSACTION_CHECK");
        }
    }

    private static void requireMeetingId(String meetingId) {
        if (meetingId == null
                || meetingId.length() > 1024
                || !MEETING_ID.matcher(meetingId).matches()) {
            throw new IllegalArgumentException("meetingId format is invalid");
        }
    }

    private static void requireStored(boolean condition) {
        if (!condition) {
            throw new StoredFactException(FailureKind.CORRUPT_DATA);
        }
    }

    private static byte[] sha256(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static StoredFactException sqlFailure(String phase, SQLException failure) {
        FailureKind kind = JdbcReadLookupSupport.classify(failure)
                == JdbcReadLookupSupport.FailureKind.INCONSISTENT
                ? FailureKind.CORRUPT_DATA : FailureKind.STORAGE_UNAVAILABLE;
        logFailure(phase, kind, JdbcReadLookupSupport.safeSqlState(failure));
        return new StoredFactException(kind);
    }

    private static StoredFactException runtimeFailure(String phase) {
        logFailure(phase, FailureKind.STORAGE_UNAVAILABLE, "N/A");
        return new StoredFactException(FailureKind.STORAGE_UNAVAILABLE);
    }

    private static void logFailure(String phase, FailureKind kind, String sqlState) {
        LOGGER.log(Level.WARNING,
                "Manifest 事实存储失败 phase={0} kind={1} sqlState={2}",
                new Object[]{phase, kind.name(), sqlState});
    }

    /** 已完成全部关闭式校验、可供本次事务继续裁决的不可变数据库事实。 */
    private record StoredRevision(
            int revision,
            String canonical,
            MeetingManifest manifest) {
    }

    /** 固定且不携带 cause 的故障类别；防止 JDBC detail 或正文越过适配器边界。 */
    static final class StoredFactException extends IllegalStateException {
        private final FailureKind kind;

        private StoredFactException(FailureKind kind) {
            super(switch (kind) {
                case CORRUPT_DATA -> "stored Manifest fact is invalid";
                case STORAGE_UNAVAILABLE -> "Manifest fact storage is unavailable";
                case TRANSACTION_REQUIRED -> "Manifest fact store requires an explicit transaction";
            });
            this.kind = kind;
        }

        FailureKind kind() {
            return kind;
        }
    }

    /** 仅供同包工作单元和门禁区分固定故障类别，不包含动态数据库信息。 */
    enum FailureKind {
        CORRUPT_DATA,
        STORAGE_UNAVAILABLE,
        TRANSACTION_REQUIRED
    }
}
