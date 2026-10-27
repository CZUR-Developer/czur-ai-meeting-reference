package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.MeetingBodyArtifactBudget;
import com.czur.reference.application.port.MeetingArtifactContentPort;
import com.czur.reference.application.port.MeetingArtifactMediaPort;
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
 * 按同一匿名 Manifest 访问决定读取客户端字幕/译文/纪要或 部署方旧格式最终转写的持久字节。
 *
 * <p>artifactId 只用于从 V011 READY metadata 找到所属会议，不当作存储路径。
 * 在任何内容读取前，先由既有 {@link MeetingManifestPort} 对该会议执行 genTime、
 * 删除撤权和最新 Manifest 完整性判断，再要求公开 ArtifactRef 与 READY descriptor
 * 完全相符。Store 仅接收数据库持有的不透明 binding；旧格式 正文维持 4096 字节
 * 上限，客户端字幕正文上限与发布、HTTP 响应共用
 * {@link MeetingBodyArtifactBudget} 推导的部署预算。实际字节还要通过 size、
 * SHA-256 校验；旧格式 结果沿用 canonical JSON 校验。本端口不拥有 DataSource
 * 或 ArtifactStore 生命周期，也不把正文或底层异常写入日志。</p>
 */
public class JdbcArtifactContentQueryPort
        implements MeetingArtifactContentPort, MeetingArtifactMediaPort {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcArtifactContentQueryPort.class.getName());
    private static final String OWNER_SQL =
            "SELECT meeting_id FROM %s WHERE artifact_id = ?";
    private static final String PIN_SEARCH_PATH_SQL = """
            SELECT set_config(
                'search_path', format('%I, pg_temp', current_schema()), TRUE)
            """;
    private static final int MAX_LEGACY_CONTENT_BYTES = 4096;

    private final DataSource dataSource;
    private final JdbcDatabaseEngine engine;
    private final MeetingManifestPort manifestPort;
    private final ArtifactStore artifactStore;
    private final LegacyArtifactValidator legacyValidator;
    private final int maxClientContentBytes;

    /**
     * 可选旧产物格式校验；共享读取器不认识参考 Mock 的数据格式。
     * 客户端标准产物仍走原有大小、摘要和 Manifest 核对；未注入时不开放旧格式。
     */
    @FunctionalInterface
    public interface LegacyArtifactValidator {
        boolean matches(byte[] bytes, MeetingManifest.ArtifactRef reference);
    }

    /** 所有依赖均由外层装配共享；此构造器不打开数据库或存储。 */
    public JdbcArtifactContentQueryPort(
            DataSource dataSource,
            MeetingManifestPort manifestPort,
            ArtifactStore artifactStore) {
        this(dataSource, JdbcDatabaseEngine.MYSQL, manifestPort, artifactStore, null,
                MeetingBodyArtifactBudget.LEGACY_MAX_BYTES);
    }

    /** 同一 Context／Manifest／Store 读取原产物字节；不另建池或产物状态机。 */
    public JdbcArtifactContentQueryPort(JdbcDatabaseContext database,
            MeetingManifestPort manifestPort, ArtifactStore artifactStore) {
        this(database.dataSource(), database.engine(), manifestPort, artifactStore, null,
                MeetingBodyArtifactBudget.LEGACY_MAX_BYTES);
    }

    /** 显式注入参考／部署方旧格式解析器；不改变存储资源所有权。 */
    public JdbcArtifactContentQueryPort(JdbcDatabaseContext database,
            MeetingManifestPort manifestPort, ArtifactStore artifactStore,
            LegacyArtifactValidator legacyValidator) {
        this(database.dataSource(), database.engine(), manifestPort, artifactStore,
                legacyValidator, MeetingBodyArtifactBudget.LEGACY_MAX_BYTES);
    }

    /**
     * 显式注入与发布、HTTP 响应相同的会议正文预算；旧格式仍只读 4096 字节。
     */
    public JdbcArtifactContentQueryPort(JdbcDatabaseContext database,
            MeetingManifestPort manifestPort, ArtifactStore artifactStore,
            LegacyArtifactValidator legacyValidator, long maxClientContentBytes) {
        this(database.dataSource(), database.engine(), manifestPort, artifactStore,
                legacyValidator, maxClientContentBytes);
    }

    /** 显式预算入口；调用方必须与发布和响应使用同一部署预算。 */
    public JdbcArtifactContentQueryPort(JdbcDatabaseContext database,
            MeetingManifestPort manifestPort, ArtifactStore artifactStore,
            long maxClientContentBytes) {
        this(database.dataSource(), database.engine(), manifestPort, artifactStore, null,
                maxClientContentBytes);
    }

    protected JdbcArtifactContentQueryPort(DataSource dataSource, JdbcDatabaseEngine engine,
            MeetingManifestPort manifestPort, ArtifactStore artifactStore,
            LegacyArtifactValidator legacyValidator) {
        this(dataSource, engine, manifestPort, artifactStore, legacyValidator,
                MeetingBodyArtifactBudget.LEGACY_MAX_BYTES);
    }

    protected JdbcArtifactContentQueryPort(DataSource dataSource, JdbcDatabaseEngine engine,
            MeetingManifestPort manifestPort, ArtifactStore artifactStore,
            LegacyArtifactValidator legacyValidator, long maxClientContentBytes) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.manifestPort = Objects.requireNonNull(manifestPort, "manifestPort");
        this.artifactStore = artifactStore;
        this.legacyValidator = legacyValidator;
        if (maxClientContentBytes <= MAX_LEGACY_CONTENT_BYTES
                || maxClientContentBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("client content budget is invalid");
        }
        this.maxClientContentBytes = (int) maxClientContentBytes;
    }

    @Override
    public MeetingArtifactContentPort.Result get(Request request) {
        Objects.requireNonNull(request, "request");
        try {
            if (artifactStore == null) return new MeetingArtifactContentPort.Unavailable(false);
            Optional<String> owner = findOwner(request.artifactId());
            if (owner.isEmpty()) {
                return new MeetingArtifactContentPort.NotFound();
            }
            MeetingManifestPort.Result access = manifestPort.get(
                    new MeetingManifestPort.Request(owner.orElseThrow(), request.genTime()));
            if (access instanceof MeetingManifestPort.AccessDenied) {
                return new MeetingArtifactContentPort.AccessDenied();
            }
            if (access instanceof MeetingManifestPort.Conflict) {
                return new MeetingArtifactContentPort.Conflict();
            }
            if (access instanceof MeetingManifestPort.Unavailable unavailable) {
                return new MeetingArtifactContentPort.Unavailable(unavailable.retryable());
            }
            if (access instanceof MeetingManifestPort.Pending pending) {
                return new MeetingArtifactContentPort.Pending(pending.meetingStatus());
            }
            if (!(access instanceof MeetingManifestPort.Found found)) {
                return new MeetingArtifactContentPort.NotFound();
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
                            && "summary.structured".equals(reference.type()))
                        || (request.artifactId().startsWith("art_client_speaker_")
                            && "speaker.identity".equals(reference.type())))) {
                return new MeetingArtifactContentPort.NotFound();
            }
            Optional<ReadyMetadata> metadata = findMetadata(request.artifactId());
            if (metadata.isEmpty() || !matches(
                    owner.orElseThrow(), reference, metadata.orElseThrow())) {
                return new MeetingArtifactContentPort.Conflict();
            }
            ArtifactDescriptor expected = metadata.orElseThrow().artifact();
            try (ArtifactStore.ReadHandle read = artifactStore.openRead(
                    expected.intent().binding())) {
                if (!expected.equals(read.artifact())) {
                    return new MeetingArtifactContentPort.Conflict();
                }
                int limit = request.artifactId().startsWith("art_client_")
                        ? maxClientContentBytes : MAX_LEGACY_CONTENT_BYTES;
                byte[] bytes = read.content().readNBytes(limit + 1);
                // 多读 1 字节用于识别超限；上限本身包含 limit 字节，不能接受 limit+1。
                if (bytes.length > limit || bytes.length != expected.content().size()
                        || !expected.content().checksum().equals(checksum(bytes))) {
                    return new MeetingArtifactContentPort.Conflict();
                }
                if (!request.artifactId().startsWith("art_client_")) {
                    if (legacyValidator == null || !legacyValidator.matches(bytes, reference)) {
                        return new MeetingArtifactContentPort.Conflict();
                    }
                }
                // Store 读取与数据库结果查询不是一个原子事务；返回前再读一次同一
                // 访问端口，避免并发删除已受理后仍发送刚读取的正文。
                MeetingManifestPort.Result current = manifestPort.get(
                        new MeetingManifestPort.Request(owner.orElseThrow(), request.genTime()));
                if (current instanceof MeetingManifestPort.AccessDenied) {
                    return new MeetingArtifactContentPort.AccessDenied();
                }
                if (current instanceof MeetingManifestPort.Unavailable unavailable) {
                    return new MeetingArtifactContentPort.Unavailable(unavailable.retryable());
                }
                if (!(current instanceof MeetingManifestPort.Found latest)
                        || !latest.manifest().equals(found.manifest())) {
                    return new MeetingArtifactContentPort.Conflict();
                }
                return new MeetingArtifactContentPort.Found(bytes);
            }
        } catch (JdbcArtifactReadyMetadataStore.StoredFactException failure) {
            return failure.kind() == JdbcArtifactReadyMetadataStore.FailureKind.CORRUPT_DATA
                    ? new MeetingArtifactContentPort.Conflict()
                    : new MeetingArtifactContentPort.Unavailable(true);
        } catch (ArtifactStoreException failure) {
            return failure.retryable() ? new MeetingArtifactContentPort.Unavailable(true)
                    : new MeetingArtifactContentPort.Conflict();
        } catch (SQLException | IOException failure) {
            LOGGER.log(Level.WARNING,
                    "Artifact 正文读取失败 reason=STORAGE_UNAVAILABLE exception={0}",
                    failure.getClass().getSimpleName());
            return new MeetingArtifactContentPort.Unavailable(true);
        } catch (IllegalArgumentException failure) {
            return new MeetingArtifactContentPort.Conflict();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING,
                    "Artifact 正文读取失败 reason=INTERNAL_ERROR exception={0}",
                    failure.getClass().getSimpleName());
            return new MeetingArtifactContentPort.Unavailable(false);
        }
    }

    /**
     * 按统一 Manifest 访问决定打开长媒体流；不会把录音读入内存，也不会通过 JSON
     * 内容端口返回。只开放已经登记为 {@code audio.original} 的 READY 产物。
     */
    @Override
    public MeetingArtifactMediaPort.Result open(Request request) {
        Objects.requireNonNull(request, "request");
        try {
            if (artifactStore == null) return new MeetingArtifactMediaPort.Unavailable(false);
            Optional<String> owner = findOwner(request.artifactId());
            if (owner.isEmpty()) return new MeetingArtifactMediaPort.NotFound();
            MeetingManifestPort.Result access = manifestPort.get(
                    new MeetingManifestPort.Request(owner.orElseThrow(), request.genTime()));
            if (access instanceof MeetingManifestPort.AccessDenied) {
                return new MeetingArtifactMediaPort.AccessDenied();
            }
            if (access instanceof MeetingManifestPort.Conflict) {
                return new MeetingArtifactMediaPort.Conflict();
            }
            if (access instanceof MeetingManifestPort.Unavailable unavailable) {
                return new MeetingArtifactMediaPort.Unavailable(unavailable.retryable());
            }
            if (access instanceof MeetingManifestPort.Pending pending) {
                return new MeetingArtifactMediaPort.Pending(pending.meetingStatus());
            }
            if (!(access instanceof MeetingManifestPort.Found found)) {
                return new MeetingArtifactMediaPort.NotFound();
            }
            MeetingManifest.ArtifactRef reference = found.manifest().artifacts().stream()
                    .filter(item -> request.artifactId().equals(item.artifactId()))
                    .findFirst().orElse(null);
            if (reference == null || reference.status() != MeetingManifest.ArtifactStatus.READY
                    || !"audio.original".equals(reference.type())) {
                return new MeetingArtifactMediaPort.NotFound();
            }
            Optional<ReadyMetadata> metadata = findMetadata(request.artifactId());
            if (metadata.isEmpty() || !matchesMedia(
                    owner.orElseThrow(), reference, metadata.orElseThrow())) {
                return new MeetingArtifactMediaPort.Conflict();
            }
            ArtifactDescriptor expected = metadata.orElseThrow().artifact();
            ArtifactStore.ReadHandle read = artifactStore.openRead(expected.intent().binding());
            boolean handedOff = false;
            try {
                if (!expected.equals(read.artifact())) {
                    return new MeetingArtifactMediaPort.Conflict();
                }
                MeetingManifestPort.Result current = manifestPort.get(
                        new MeetingManifestPort.Request(owner.orElseThrow(), request.genTime()));
                if (current instanceof MeetingManifestPort.AccessDenied) {
                    return new MeetingArtifactMediaPort.AccessDenied();
                }
                if (current instanceof MeetingManifestPort.Unavailable unavailable) {
                    return new MeetingArtifactMediaPort.Unavailable(unavailable.retryable());
                }
                if (!(current instanceof MeetingManifestPort.Found latest)
                        || !latest.manifest().equals(found.manifest())) {
                    return new MeetingArtifactMediaPort.Conflict();
                }
                MeetingArtifactMediaPort.Found result =
                        new MeetingArtifactMediaPort.Found(reference, read);
                handedOff = true;
                return result;
            } finally {
                if (!handedOff) read.close();
            }
        } catch (JdbcArtifactReadyMetadataStore.StoredFactException failure) {
            return failure.kind() == JdbcArtifactReadyMetadataStore.FailureKind.CORRUPT_DATA
                    ? new MeetingArtifactMediaPort.Conflict()
                    : new MeetingArtifactMediaPort.Unavailable(true);
        } catch (ArtifactStoreException failure) {
            return failure.retryable() ? new MeetingArtifactMediaPort.Unavailable(true)
                    : new MeetingArtifactMediaPort.Conflict();
        } catch (SQLException | IOException failure) {
            LOGGER.log(Level.WARNING,
                    "Artifact 媒体流打开失败 reason=STORAGE_UNAVAILABLE exception={0}",
                    failure.getClass().getSimpleName());
            return new MeetingArtifactMediaPort.Unavailable(true);
        } catch (IllegalArgumentException failure) {
            return new MeetingArtifactMediaPort.Conflict();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING,
                    "Artifact 媒体流打开失败 reason=INTERNAL_ERROR exception={0}",
                    failure.getClass().getSimpleName());
            return new MeetingArtifactMediaPort.Unavailable(false);
        }
    }

    private Optional<String> findOwner(String artifactId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(OWNER_SQL.formatted(
                    JdbcDatabaseTable.ARTIFACT.physicalName(engine)))) {
                JdbcValues.setText(statement, 1, engine, artifactId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    String meetingId = JdbcValues.text(rows, "meeting_id");
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
                    new JdbcArtifactReadyMetadataStore(connection, engine).findByArtifactId(artifactId);
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

    private static boolean matchesMedia(String meetingId, MeetingManifest.ArtifactRef reference,
                                        ReadyMetadata metadata) {
        ArtifactDescriptor artifact = metadata.artifact();
        WriteIntentSpec spec = artifact.intent().spec();
        return meetingId.equals(spec.meetingId())
                && reference.artifactId().equals(spec.artifactId())
                && "audio.original".equals(reference.type())
                && reference.type().equals(spec.artifactType())
                && reference.mediaType().equals(spec.mediaType())
                && (reference.mediaType().startsWith("audio/")
                    || "application/octet-stream".equals(reference.mediaType()))
                && reference.language() == null && spec.language() == null
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
