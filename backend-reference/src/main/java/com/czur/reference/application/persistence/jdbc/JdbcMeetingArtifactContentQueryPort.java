package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.finalization.MockFinalizationArtifactContent;
import com.czur.reference.application.port.MeetingArtifactContentPort;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore.ReadyMetadata;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.application.port.artifact.ArtifactStoreException;

import javax.sql.DataSource;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 按同一匿名 Manifest 访问决定读取客户端字幕/译文/纪要或 Mock 最终转写的持久字节。
 *
 * <p>artifactId 只用于从 V011 READY metadata 找到所属会议，不当作存储路径。
 * 在任何内容读取前，先由既有 {@link MeetingManifestPort} 对该会议执行 genTime、
 * 删除撤权和最新 Manifest 完整性判断，再要求公开 ArtifactRef 与 READY descriptor
 * 完全相符。Store 仅接收数据库持有的不透明 binding；旧 Mock 正文维持 4096 字节
 * 上限，客户端字幕正文上限为 1 MiB。实际字节还要通过 size、SHA-256 校验；
 * 旧 Mock 结果沿用 canonical JSON 校验。本端口不拥有 DataSource 或 ArtifactStore
 * 生命周期，也不把正文或底层异常写入日志。</p>
 */
public final class JdbcMeetingArtifactContentQueryPort
        implements MeetingArtifactContentPort {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcMeetingArtifactContentQueryPort.class.getName());
    private static final String OWNER_SQL =
            "SELECT meeting_id FROM artifact WHERE artifact_id = ?";
    private static final String PIN_SEARCH_PATH_SQL = """
            SELECT set_config(
                'search_path', format('%I, pg_temp', current_schema()), TRUE)
            """;
    private static final int MAX_MOCK_CONTENT_BYTES = 4096;
    private static final int MAX_CLIENT_CONTENT_BYTES = 1024 * 1024;

    private final DataSource dataSource;
    private final MeetingManifestPort manifestPort;
    private final ArtifactStore artifactStore;

    /** 所有依赖均由外层装配共享；此构造器不打开数据库或存储。 */
    public JdbcMeetingArtifactContentQueryPort(
            DataSource dataSource,
            MeetingManifestPort manifestPort,
            ArtifactStore artifactStore) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.manifestPort = Objects.requireNonNull(manifestPort, "manifestPort");
        this.artifactStore = artifactStore;
    }

    @Override
    public Result get(Request request) {
        Objects.requireNonNull(request, "request");
        try {
            if (artifactStore == null) return new Unavailable(false);
            Optional<String> owner = findOwner(request.artifactId());
            if (owner.isEmpty()) {
                return new NotFound();
            }
            MeetingManifestPort.Result access = manifestPort.get(
                    new MeetingManifestPort.Request(owner.orElseThrow(), request.genTime()));
            if (access instanceof MeetingManifestPort.AccessDenied) {
                return new AccessDenied();
            }
            if (access instanceof MeetingManifestPort.Conflict) {
                return new Conflict();
            }
            if (access instanceof MeetingManifestPort.Unavailable unavailable) {
                return new Unavailable(unavailable.retryable());
            }
            if (access instanceof MeetingManifestPort.Pending pending) {
                return new Pending(pending.meetingStatus());
            }
            if (!(access instanceof MeetingManifestPort.Found found)) {
                return new NotFound();
            }

            MeetingManifest.ArtifactRef reference = found.manifest().artifacts().stream()
                    .filter(item -> request.artifactId().equals(item.artifactId()))
                    .findFirst().orElse(null);
            if (reference == null || reference.status() != MeetingManifest.ArtifactStatus.READY
                    || !("transcript.final".equals(reference.type())
                        || (request.artifactId().startsWith("art_client_presentation_")
                            && "presentation.capture".equals(reference.type()))
                        || (request.artifactId().startsWith("art_client_translation_")
                            && "translation.final".equals(reference.type()))
                        || (request.artifactId().startsWith("art_client_summary_")
                            && "summary.structured".equals(reference.type())))) {
                return new NotFound();
            }
            Optional<ReadyMetadata> metadata = findMetadata(request.artifactId());
            if (metadata.isEmpty() || !matches(
                    owner.orElseThrow(), reference, metadata.orElseThrow())) {
                return new Conflict();
            }
            ArtifactDescriptor expected = metadata.orElseThrow().artifact();
            try (ArtifactStore.ReadHandle read = artifactStore.openRead(
                    expected.intent().binding())) {
                if (!expected.equals(read.artifact())) {
                    return new Conflict();
                }
                int limit = request.artifactId().startsWith("art_client_")
                        ? MAX_CLIENT_CONTENT_BYTES : MAX_MOCK_CONTENT_BYTES;
                byte[] bytes = read.content().readNBytes(limit + 1);
                if (bytes.length != expected.content().size()
                        || !expected.content().checksum().equals(checksum(bytes))) {
                    return new Conflict();
                }
                if (!request.artifactId().startsWith("art_client_")) {
                    MockFinalizationArtifactContent decoded =
                            MockFinalizationArtifactContent.decodeCanonical(bytes);
                    if (!"transcript.final".equals(decoded.artifactType())
                            || !Objects.equals(reference.language(), decoded.language())) {
                        return new Conflict();
                    }
                }
                // Store 读取与数据库结果查询不是一个原子事务；返回前再读一次同一
                // 访问端口，避免并发删除已受理后仍发送刚读取的正文。
                MeetingManifestPort.Result current = manifestPort.get(
                        new MeetingManifestPort.Request(owner.orElseThrow(), request.genTime()));
                if (current instanceof MeetingManifestPort.AccessDenied) {
                    return new AccessDenied();
                }
                if (current instanceof MeetingManifestPort.Unavailable unavailable) {
                    return new Unavailable(unavailable.retryable());
                }
                if (!(current instanceof MeetingManifestPort.Found latest)
                        || !latest.manifest().equals(found.manifest())) {
                    return new Conflict();
                }
                return new Found(bytes);
            }
        } catch (JdbcArtifactReadyMetadataStore.StoredFactException failure) {
            return failure.kind() == JdbcArtifactReadyMetadataStore.FailureKind.CORRUPT_DATA
                    ? new Conflict() : new Unavailable(true);
        } catch (ArtifactStoreException failure) {
            return failure.retryable() ? new Unavailable(true) : new Conflict();
        } catch (SQLException | IOException failure) {
            LOGGER.log(Level.WARNING,
                    "Artifact 正文读取失败 reason=STORAGE_UNAVAILABLE exception={0}",
                    failure.getClass().getSimpleName());
            return new Unavailable(true);
        } catch (IllegalArgumentException failure) {
            return new Conflict();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING,
                    "Artifact 正文读取失败 reason=INTERNAL_ERROR exception={0}",
                    failure.getClass().getSimpleName());
            return new Unavailable(false);
        }
    }

    private Optional<String> findOwner(String artifactId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement schema = connection.prepareStatement(
                    "SELECT current_schema()");
                 ResultSet rows = schema.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null
                        || rows.getString(1).startsWith("pg_temp")) {
                    throw new SQLException("deployment schema is unavailable");
                }
            }
            try (PreparedStatement pin = connection.prepareStatement(PIN_SEARCH_PATH_SQL)) {
                pin.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement(OWNER_SQL)) {
                statement.setString(1, artifactId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    String meetingId = rows.getString(1);
                    if (meetingId == null || meetingId.isBlank() || rows.next()) {
                        throw new IllegalStateException("artifact owner fact is inconsistent");
                    }
                    return Optional.of(meetingId);
                }
            }
        }
    }

    private Optional<ReadyMetadata> findMetadata(String artifactId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            Optional<ReadyMetadata> result =
                    new JdbcArtifactReadyMetadataStore(connection).findByArtifactId(artifactId);
            connection.rollback();
            return result;
        }
    }

    private static boolean matches(String meetingId, MeetingManifest.ArtifactRef reference,
                                   ReadyMetadata metadata) {
        ArtifactDescriptor artifact = metadata.artifact();
        WriteIntentSpec spec = artifact.intent().spec();
        return meetingId.equals(spec.meetingId())
                && reference.artifactId().equals(spec.artifactId())
                && reference.type().equals(spec.artifactType())
                && "application/json".equals(reference.mediaType())
                && reference.mediaType().equals(spec.mediaType())
                && Objects.equals(reference.language(), spec.language())
                && reference.revision() == spec.revision()
                && reference.size() == artifact.content().size()
                && reference.size() == spec.expectedSize()
                && reference.checksum().equals(artifact.content().checksum())
                && reference.checksum().equals(spec.expectedChecksum())
                && reference.createdAt().equals(metadata.recordedAt());
    }

    private static String checksum(byte[] content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
