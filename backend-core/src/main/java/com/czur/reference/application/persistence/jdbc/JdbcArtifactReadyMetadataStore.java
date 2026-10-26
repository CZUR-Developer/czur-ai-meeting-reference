package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore;
import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * V011 READY-only artifact 元数据的连接级 JDBC 原语。
 *
 * <p>调用方必须先由外部 {@code ArtifactStore.completeWrite} 确认内容已经原子
 * READY，再把 descriptor 交给本类。本类只保存 descriptor 与数据库首次记录时间，
 * 不访问文件系统、对象存储或网络，也不推进会议状态。连接生命周期和事务提交、回滚
 * 仍由调用方负责。</p>
 *
 * <p>幂等判断同时检查四条唯一身份轴：artifactId、(meetingId, artifactType,
 * revision)、intentId 和 storageBinding。候选可能让不同轴命中不同历史行，此时只返回
 * CONFLICT，不伪造“赢家”。数据库读回会重新构造完整 descriptor，并校验字段格式、
 * checksum、大小、revision、时间精度和 descriptor 内部一致性。</p>
 */
final class JdbcArtifactReadyMetadataStore implements ArtifactReadyMetadataStore {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcArtifactReadyMetadataStore.class.getName());

    private static final String PIN_SEARCH_PATH_SQL = """
            SELECT set_config(
                'search_path', format('%I, pg_temp', current_schema()), TRUE)
            """;
    private static final String FIND_BY_ARTIFACT_ID_SQL = """
            SELECT artifact_id, meeting_id, artifact_type, media_type, language,
                   revision, intent_id, storage_binding, content_size, content_checksum,
                   recorded_at
              FROM %s
             WHERE artifact_id = ?
            """;
    private static final String FIND_BY_MEETING_ID_SQL = """
            SELECT artifact_id, meeting_id, artifact_type, media_type, language,
                   revision, intent_id, storage_binding, content_size, content_checksum,
                   recorded_at
              FROM %s
             WHERE meeting_id = ?
             ORDER BY artifact_id
            """;
    private static final String FIND_MATCHING_AXES_SQL = """
            SELECT artifact_id, meeting_id, artifact_type, media_type, language,
                   revision, intent_id, storage_binding, content_size, content_checksum,
                   recorded_at
              FROM %s
             WHERE artifact_id = ?
                OR (meeting_id = ? AND artifact_type = ? AND revision = ?)
                OR intent_id = ?
                OR storage_binding = ?
            """;
    private static final String INSERT_SQL = """
            INSERT INTO %s (
                artifact_id, meeting_id, artifact_type, media_type, language,
                revision, intent_id, storage_binding, content_size, content_checksum)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            %s
            """;

    private final Connection connection;
    private final JdbcDatabaseEngine engine;

    /** 绑定调用方当前连接；构造过程不获取或释放连接。 */
    JdbcArtifactReadyMetadataStore(Connection connection) {
        this(connection, JdbcDatabaseEngine.MYSQL);
    }

    /** 截图和后续结果收口共用同一元数据原语，不接管连接或事务。 */
    JdbcArtifactReadyMetadataStore(Connection connection, JdbcDatabaseEngine engine) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public Optional<ArtifactReadyMetadataStore.ReadyMetadata> findByArtifactId(
            String artifactId) {
        requireText("artifactId", artifactId);
        requireExplicitTransaction();
        try {
            pinSearchPath();
            try (PreparedStatement statement = connection.prepareStatement(
                    FIND_BY_ARTIFACT_ID_SQL.formatted(table()))) {
                JdbcValues.setText(statement, 1, engine, artifactId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        logResult("FIND", "EMPTY");
                        return Optional.empty();
                    }
                    ArtifactReadyMetadataStore.ReadyMetadata result = readMetadata(rows);
                    if (rows.next()) {
                        throw corruptData();
                    }
                    logResult("FIND", "PRESENT");
                    return Optional.of(result);
                }
            }
        } catch (StoredFactException failure) {
            logFailure("FIND", failure.kind(), "N/A");
            throw failure;
        } catch (SQLException failure) {
            throw sqlFailure("FIND", failure);
        } catch (RuntimeException failure) {
            throw runtimeFailure("FIND");
        }
    }

    @Override
    public List<ArtifactReadyMetadataStore.ReadyMetadata> findByMeetingId(
            String meetingId) {
        requireText("meetingId", meetingId);
        requireExplicitTransaction();
        try {
            pinSearchPath();
            try (PreparedStatement statement = connection.prepareStatement(
                    FIND_BY_MEETING_ID_SQL.formatted(table()))) {
                JdbcValues.setText(statement, 1, engine, meetingId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<ArtifactReadyMetadataStore.ReadyMetadata> result =
                            new ArrayList<>();
                    String previousArtifactId = null;
                    while (rows.next()) {
                        ArtifactReadyMetadataStore.ReadyMetadata metadata =
                                readMetadata(rows);
                        String artifactId = metadata.artifact().intent().spec().artifactId();
                        if (!meetingId.equals(
                                metadata.artifact().intent().spec().meetingId())
                                || previousArtifactId != null
                                && previousArtifactId.compareTo(artifactId) >= 0) {
                            throw corruptData();
                        }
                        previousArtifactId = artifactId;
                        result.add(metadata);
                    }
                    logResult("FIND_MEETING", result.isEmpty() ? "EMPTY" : "PRESENT");
                    return List.copyOf(result);
                }
            }
        } catch (StoredFactException failure) {
            logFailure("FIND_MEETING", failure.kind(), "N/A");
            throw failure;
        } catch (SQLException failure) {
            throw sqlFailure("FIND_MEETING", failure);
        } catch (RuntimeException failure) {
            throw runtimeFailure("FIND_MEETING");
        }
    }

    @Override
    public ArtifactReadyMetadataStore.PersistResult persistReady(
            ArtifactDescriptor candidate) {
        Objects.requireNonNull(candidate, "candidate");
        validateCandidate(candidate);
        requireExplicitTransaction();
        try {
            pinSearchPath();
            int inserted;
            try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL.formatted(
                    table(), engine == JdbcDatabaseEngine.MYSQL ? "" : "ON CONFLICT DO NOTHING"))) {
                WriteIntentSpec spec = candidate.intent().spec();
                JdbcValues.setText(statement, 1, engine, spec.artifactId());
                JdbcValues.setText(statement, 2, engine, spec.meetingId());
                JdbcValues.setText(statement, 3, engine, spec.artifactType());
                statement.setString(4, spec.mediaType());
                if (spec.language() == null) {
                    statement.setNull(5, java.sql.Types.VARCHAR);
                } else {
                    statement.setString(5, spec.language());
                }
                statement.setInt(6, spec.revision());
                JdbcValues.setText(statement, 7, engine, candidate.intent().intentId());
                JdbcValues.setText(statement, 8, engine, candidate.intent().binding().value());
                statement.setLong(9, candidate.content().size());
                statement.setString(10, candidate.content().checksum());
                try {
                    inserted = statement.executeUpdate();
                } catch (SQLException duplicate) {
                    if (!JdbcSqlErrors.isDuplicate(engine, duplicate)) throw duplicate;
                    inserted = 0;
                }
            }

            List<ArtifactReadyMetadataStore.ReadyMetadata> matches = selectMatching(candidate);
            if (inserted == 1) {
                if (matches.size() != 1
                        || !matches.get(0).artifact().equals(candidate)) {
                    throw corruptData();
                }
                logResult("PERSIST", "APPLIED");
                return new ArtifactReadyMetadataStore.PersistResult(
                        ArtifactReadyMetadataStore.PersistStatus.APPLIED,
                        matches.get(0));
            }
            if (matches.size() == 1 && matches.get(0).artifact().equals(candidate)) {
                logResult("PERSIST", "REPLAYED");
                return new ArtifactReadyMetadataStore.PersistResult(
                        ArtifactReadyMetadataStore.PersistStatus.REPLAYED,
                        matches.get(0));
            }
            if (matches.isEmpty()) {
                // ON CONFLICT 返回 0 但读不到任一身份轴，说明事务快照或表内容已
                // 不一致；不能把这种不可解释的情况伪装成普通业务冲突。
                throw corruptData();
            }
            logResult("PERSIST", "CONFLICT");
            return ArtifactReadyMetadataStore.PersistResult.conflict();
        } catch (StoredFactException failure) {
            logFailure("PERSIST", failure.kind(), "N/A");
            throw failure;
        } catch (SQLException failure) {
            throw sqlFailure("PERSIST", failure);
        } catch (RuntimeException failure) {
            throw runtimeFailure("PERSIST");
        }
    }

    private List<ArtifactReadyMetadataStore.ReadyMetadata> selectMatching(
            ArtifactDescriptor candidate) throws SQLException {
        WriteIntentSpec spec = candidate.intent().spec();
        // 写入/重复键之后必须读取当前唯一赢家，不复用外层较早建立的 RR 快照。
        try (PreparedStatement statement = connection.prepareStatement(
                FIND_MATCHING_AXES_SQL.formatted(table())
                        + (engine == JdbcDatabaseEngine.MYSQL ? " FOR UPDATE" : ""))) {
            JdbcValues.setText(statement, 1, engine, spec.artifactId());
            JdbcValues.setText(statement, 2, engine, spec.meetingId());
            JdbcValues.setText(statement, 3, engine, spec.artifactType());
            statement.setInt(4, spec.revision());
            JdbcValues.setText(statement, 5, engine, candidate.intent().intentId());
            JdbcValues.setText(statement, 6, engine, candidate.intent().binding().value());
            try (ResultSet rows = statement.executeQuery()) {
                List<ArtifactReadyMetadataStore.ReadyMetadata> matches = new ArrayList<>();
                while (rows.next()) {
                    matches.add(readMetadata(rows));
                }
                return matches;
            }
        }
    }

    private static ArtifactReadyMetadataStore.ReadyMetadata readMetadata(ResultSet rows)
            throws SQLException {
        try {
            String artifactId = requiredText(rows, "artifact_id");
            String meetingId = requiredText(rows, "meeting_id");
            String artifactType = requiredText(rows, "artifact_type");
            String mediaType = requiredText(rows, "media_type");
            String language = rows.getString("language");
            int revision = rows.getInt("revision");
            if (rows.wasNull()) {
                throw corruptData();
            }
            String intentId = requiredText(rows, "intent_id");
            String binding = requiredText(rows, "storage_binding");
            long contentSize = rows.getLong("content_size");
            if (rows.wasNull()) {
                throw corruptData();
            }
            String checksum = requiredText(rows, "content_checksum");
            Instant recordedInstant = JdbcValues.instant(rows, "recorded_at");
            if (recordedInstant == null) {
                throw corruptData();
            }
            if (recordedInstant.getNano() % 1_000 != 0) {
                throw corruptData();
            }
            WriteIntentSpec spec = new WriteIntentSpec(
                    artifactId, meetingId, artifactType, mediaType, language,
                    revision, contentSize, checksum);
            ArtifactDescriptor descriptor = new ArtifactDescriptor(
                    new WriteIntent(intentId, new StorageBinding(binding), spec),
                    new ContentClaim(contentSize, checksum));
            return new ArtifactReadyMetadataStore.ReadyMetadata(descriptor, recordedInstant);
        } catch (StoredFactException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw corruptData();
        }
    }

    /**
     * ArtifactStore 的值对象保证了字段的业务关系；这里额外执行数据库共享的严格
     * UTF-8/4096 边界，避免 JDBC 驱动在发送未配对 surrogate 时进行静默替换。
     */
    private static void validateCandidate(ArtifactDescriptor candidate) {
        WriteIntentSpec spec = candidate.intent().spec();
        requireText("artifactId", spec.artifactId());
        requireText("meetingId", spec.meetingId());
        requireText("artifactType", spec.artifactType());
        requireText("mediaType", spec.mediaType());
        if (spec.language() != null) {
            requireText("language", spec.language());
        }
        requireText("intentId", candidate.intent().intentId());
        requireText("storageBinding", candidate.intent().binding().value());
        requireText("contentChecksum", candidate.content().checksum());
    }

    private void pinSearchPath() throws SQLException {
        if (engine == JdbcDatabaseEngine.MYSQL) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_schema()")) {
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("schema is unavailable");
                }
                String schema = rows.getString(1);
                if (schema == null || schema.startsWith("pg_temp")) {
                    throw new SQLException("temporary schema is not a deployment schema");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(PIN_SEARCH_PATH_SQL)) {
            statement.execute();
        }
    }

    private void requireExplicitTransaction() {
        try {
            if (connection.getAutoCommit()) {
                throw new StoredFactException(FailureKind.TRANSACTION_REQUIRED);
            }
        } catch (StoredFactException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw sqlFailure("TRANSACTION_CHECK", failure);
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            if (encoded.remaining() > 4096) {
                throw new IllegalArgumentException(name + " must not exceed 4096 UTF-8 bytes");
            }
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(name + " must be valid UTF-8 text");
        }
    }

    private static String requiredText(ResultSet rows, String column) throws SQLException {
        String value = switch (column) {
            case "artifact_id", "meeting_id", "artifact_type", "intent_id", "storage_binding" ->
                    JdbcValues.text(rows, column);
            default -> rows.getString(column);
        };
        try {
            requireText(column, value);
        } catch (IllegalArgumentException malformed) {
            throw corruptData();
        }
        return value;
    }

    private static StoredFactException corruptData() {
        return new StoredFactException(FailureKind.CORRUPT_DATA);
    }

    private String table() {
        return JdbcDatabaseTable.ARTIFACT.physicalName(engine);
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

    private static void logResult(String phase, String result) {
        LOGGER.log(Level.FINE, "Artifact READY metadata store phase={0} result={1}",
                new Object[]{phase, result});
    }

    private static void logFailure(String phase, FailureKind kind, String sqlState) {
        LOGGER.log(Level.WARNING,
                "Artifact READY metadata store failure phase={0} kind={1} sqlState={2}",
                new Object[]{phase, kind.name(), sqlState});
    }

    /** 固定故障类别，不携带 SQL detail、标识、binding 或 checksum。 */
    enum FailureKind {
        CORRUPT_DATA,
        STORAGE_UNAVAILABLE,
        TRANSACTION_REQUIRED
    }

    /** 供调用方区分元数据损坏、数据库不可用和缺少显式事务。 */
    static final class StoredFactException extends IllegalStateException {
        private final FailureKind kind;

        private StoredFactException(FailureKind kind) {
            super(switch (kind) {
                case CORRUPT_DATA -> "artifact READY metadata is inconsistent";
                case STORAGE_UNAVAILABLE -> "artifact READY metadata storage is unavailable";
                case TRANSACTION_REQUIRED ->
                        "artifact READY metadata store requires an explicit transaction";
            });
            this.kind = kind;
        }

        FailureKind kind() {
            return kind;
        }
    }
}
