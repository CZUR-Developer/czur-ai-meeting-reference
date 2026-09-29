package com.czur.reference.application.artifact.filesystem;

import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.artifact.ArtifactStore.CompleteResult;
import com.czur.reference.application.port.artifact.ArtifactStore.CompleteStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.DeleteResult;
import com.czur.reference.application.port.artifact.ArtifactStore.DeleteStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.InspectionResult;
import com.czur.reference.application.port.artifact.ArtifactStore.PrepareResult;
import com.czur.reference.application.port.artifact.ArtifactStore.PrepareStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.ReadHandle;
import com.czur.reference.application.port.artifact.ArtifactStore.StageResult;
import com.czur.reference.application.port.artifact.ArtifactStore.StageStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.StatResult;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.StoreState;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.application.port.artifact.ArtifactStoreException;
import com.czur.reference.application.port.artifact.ArtifactStoreException.Code;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用持久文件系统模拟对象存储两段式发布语义的无网络测试替身。
 *
 * <p>这个实现用于 P2 Worker 的崩溃恢复和幂等门禁，不是生产 Filesystem Store。
 * 它没有用户授权、短期 URL、加密、配额、备份或安全擦除能力，部署代码不得把它直接
 * 暴露为公共 Artifact HTTP 服务。</p>
 *
 * <p>逻辑 Artifact 的 {@code artifactId} 经 domain-separated SHA-256 映射为稳定
 * binding；业务标识、语言、媒体类型和正文都不会进入文件名。写入先进入 staging，
 * size/checksum 校验通过后仍保持 PENDING；只有 READY marker 的原子 rename 才是发布
 * 线性化点。READY marker、严格版本化 metadata 和 blob 三者不一致时读取失败关闭。</p>
 *
 * <p>同一 binding 在 JVM 内使用 keyed lock，在进程间使用 {@link FileLock}。删除先
 * 持久化 DELETING tombstone 来阻止迟到 Writer，再清理全部内容，最后原子转成
 * DELETED tombstone。删除后的相同 binding 永远不能复活。</p>
 */
public final class PersistentFileSystemMockArtifactStore implements ArtifactStore {
    private static final Logger LOGGER = Logger.getLogger(
            PersistentFileSystemMockArtifactStore.class.getName());
    private static final ConcurrentHashMap<String, ReentrantLock> JVM_LOCKS =
            new ConcurrentHashMap<>();

    private static final int METADATA_MAGIC = 0x435A4152; // CZAR
    private static final int METADATA_VERSION = 1;
    private static final int KIND_INTENT = 1;
    private static final int KIND_STAGED = 2;
    private static final int KIND_READY = 3;
    private static final int KIND_TOMBSTONE = 4;
    private static final int KIND_SPEC_TOMBSTONE = 5;
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private static final int MAX_FIELD_BYTES = 4096;
    private static final int BUFFER_BYTES = 16 * 1024;
    private static final String BINDING_PREFIX = "fsmock_v1_";
    private static final String INTENT_PREFIX = "fsmock_intent_v1_";
    private static final Pattern BINDING_PATTERN =
            Pattern.compile("^fsmock_v1_([0-9a-f]{64})$");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final Path root;
    private final Path intentsDirectory;
    private final Path stagingDirectory;
    private final Path objectsDirectory;
    private final Path tombstonesDirectory;
    private final Path locksDirectory;
    private final List<DirectoryIdentity> directoryIdentities;
    private final long maxArtifactBytes;
    private final Supplier<String> diagnosticIds;
    private final TombstonePublishFaultInjector tombstonePublishFaultInjector;

    /**
     * 创建显式大小受限的持久 Mock。
     *
     * @param root 专用且可信的存储根；最终路径组件不能是 symlink，祖先 alias 会被
     *             一次性解析并固定到 canonical root
     * @param maxArtifactBytes 单个 Artifact 可接受的最大字节数，必须大于零
     */
    public PersistentFileSystemMockArtifactStore(Path root, long maxArtifactBytes)
            throws ArtifactStoreException {
        this(root, maxArtifactBytes, () -> UUID.randomUUID().toString(),
                TombstonePublishFaultInjector.NONE);
    }

    PersistentFileSystemMockArtifactStore(
            Path root,
            long maxArtifactBytes,
            Supplier<String> diagnosticIds) throws ArtifactStoreException {
        this(root, maxArtifactBytes, diagnosticIds, TombstonePublishFaultInjector.NONE);
    }

    /**
     * 仅供同包故障门在 tombstone 原子 rename 已完成、目录 fsync 尚未确认时注入失败。
     * 生产构造器始终使用空注入器，不能从应用配置打开该能力。
     */
    PersistentFileSystemMockArtifactStore(
            Path root,
            long maxArtifactBytes,
            Supplier<String> diagnosticIds,
            TombstonePublishFaultInjector tombstonePublishFaultInjector)
            throws ArtifactStoreException {
        Objects.requireNonNull(root, "root");
        if (maxArtifactBytes <= 0) {
            throw new IllegalArgumentException("maxArtifactBytes must be positive");
        }
        this.maxArtifactBytes = maxArtifactBytes;
        this.diagnosticIds = Objects.requireNonNull(diagnosticIds, "diagnosticIds");
        this.tombstonePublishFaultInjector = Objects.requireNonNull(
                tombstonePublishFaultInjector, "tombstonePublishFaultInjector");
        StoreLayout layout = initializeLayout(root.toAbsolutePath().normalize());
        this.root = layout.root();
        this.intentsDirectory = layout.intents();
        this.stagingDirectory = layout.staging();
        this.objectsDirectory = layout.objects();
        this.tombstonesDirectory = layout.tombstones();
        this.locksDirectory = layout.locks();
        this.directoryIdentities = layout.identities();
    }

    @Override
    public PrepareResult createWriteIntent(WriteIntentSpec spec)
            throws ArtifactStoreException {
        Objects.requireNonNull(spec, "spec");
        requireWithinLimit(spec);
        WriteIntent candidate = deriveIntent(spec);
        return withBindingLock(candidate.binding(), hash -> {
            rejectDeleted(hash, candidate);
            Path persistedPath = intentPath(hash);
            if (exists(persistedPath)) {
                WriteIntent persisted = readIntent(persistedPath);
                requireStoredIntentMatchesBinding(persisted, candidate.binding());
                PrepareStatus status = persisted.spec().equals(spec)
                        ? PrepareStatus.REPLAYED : PrepareStatus.CONFLICT;
                logSuccess("create-intent", status.name());
                return new PrepareResult(status, persisted);
            }
            if (hasContentStateWithoutIntent(hash)) {
                throw failure(Code.STORAGE_CORRUPT);
            }
            writeMetadataAtomically(
                    intentTemporaryPath(hash), persistedPath, encodeIntent(candidate));
            logSuccess("create-intent", PrepareStatus.APPLIED.name());
            return new PrepareResult(PrepareStatus.APPLIED, candidate);
        });
    }

    @Override
    public StageResult writeContent(WriteIntent intent, InputStream content)
            throws ArtifactStoreException {
        Objects.requireNonNull(content, "content");
        validateCallerIntent(intent);
        requireWithinLimit(intent.spec());
        return withBindingLock(intent.binding(), hash -> {
            rejectDeleted(hash, intent);
            requireExactPersistedIntent(hash, intent);

            if (exists(readyPath(hash))) {
                ArtifactDescriptor ready = readReadyAndVerify(hash, intent.binding());
                if (!ready.intent().equals(intent)) {
                    throw failure(Code.STORAGE_CONFLICT);
                }
                logSuccess("write-content", StageStatus.REPLAYED.name());
                return new StageResult(StageStatus.REPLAYED, intent, ready.content());
            }

            Path verifiedPath = verifiedPath(hash);
            if (exists(verifiedPath)) {
                StagedMetadata staged = readStaged(verifiedPath);
                requireStoredIntentMatchesBinding(staged.intent(), intent.binding());
                if (!staged.intent().equals(intent)) {
                    throw failure(Code.STORAGE_CONFLICT);
                }
                Path contentPath = exists(stagedPath(hash))
                        ? stagedPath(hash) : objectPath(hash);
                if (!exists(contentPath)) {
                    throw failure(Code.STORAGE_CORRUPT);
                }
                verifyContent(contentPath, staged.content());
                logSuccess("write-content", StageStatus.REPLAYED.name());
                return new StageResult(StageStatus.REPLAYED, intent, staged.content());
            }

            deleteRegularFileIfExists(partPath(hash));
            deleteRegularFileIfExists(stagedPath(hash));
            ContentClaim actual = writeAndMeasurePart(hash, intent.spec(), content);
            moveAtomically(partPath(hash), stagedPath(hash));
            writeMetadataAtomically(
                    verifiedTemporaryPath(hash),
                    verifiedPath,
                    encodeStaged(new StagedMetadata(intent, actual)));
            logSuccess("write-content", StageStatus.APPLIED.name());
            return new StageResult(StageStatus.APPLIED, intent, actual);
        });
    }

    @Override
    public CompleteResult completeWrite(WriteIntent intent, ContentClaim claim)
            throws ArtifactStoreException {
        validateCallerIntent(intent);
        Objects.requireNonNull(claim, "claim");
        requireClaimMatchesSpec(intent.spec(), claim);
        return withBindingLock(intent.binding(), hash -> {
            rejectDeleted(hash, intent);
            requireExactPersistedIntent(hash, intent);

            if (exists(readyPath(hash))) {
                ArtifactDescriptor ready = readReadyAndVerify(hash, intent.binding());
                if (!ready.intent().equals(intent) || !ready.content().equals(claim)) {
                    throw failure(Code.STORAGE_CONFLICT);
                }
                logSuccess("complete-write", CompleteStatus.REPLAYED.name());
                return new CompleteResult(CompleteStatus.REPLAYED, ready);
            }

            if (!exists(verifiedPath(hash))) {
                throw failure(Code.NOT_READY);
            }
            StagedMetadata staged = readStaged(verifiedPath(hash));
            requireStoredIntentMatchesBinding(staged.intent(), intent.binding());
            if (!staged.intent().equals(intent)) {
                throw failure(Code.STORAGE_CONFLICT);
            }
            requireClaimsEqual(staged.content(), claim);

            Path stagedBlob = stagedPath(hash);
            Path objectBlob = objectPath(hash);
            if (exists(objectBlob)) {
                verifyContent(objectBlob, claim);
                if (exists(stagedBlob)) {
                    verifyContent(stagedBlob, claim);
                    deleteRegularFileIfExists(stagedBlob);
                    forceDirectory(stagingDirectory);
                }
            } else {
                if (!exists(stagedBlob)) {
                    throw failure(Code.STORAGE_CORRUPT);
                }
                verifyContent(stagedBlob, claim);
                moveAtomically(stagedBlob, objectBlob);
            }

            ArtifactDescriptor descriptor = new ArtifactDescriptor(intent, claim);
            writeMetadataAtomically(
                    readyTemporaryPath(hash), readyPath(hash), encodeReady(descriptor));
            logSuccess("complete-write", CompleteStatus.APPLIED.name());
            return new CompleteResult(CompleteStatus.APPLIED, descriptor);
        });
    }

    @Override
    public StatResult stat(StorageBinding binding) throws ArtifactStoreException {
        Objects.requireNonNull(binding, "binding");
        return withBindingLock(binding, hash -> statLocked(hash, binding, null));
    }

    @Override
    public InspectionResult inspect(WriteIntentSpec spec) throws ArtifactStoreException {
        Objects.requireNonNull(spec, "spec");
        WriteIntent candidate = deriveIntent(spec);
        return withBindingLock(candidate.binding(), hash -> {
            StatResult result = statLocked(hash, candidate.binding(), spec);
            if (result.state() == StoreState.READY) {
                return new InspectionResult(
                        StoreState.READY, candidate.binding(), result.artifact());
            }
            return InspectionResult.withoutArtifact(result.state(), candidate.binding());
        });
    }

    @Override
    public ReadHandle openRead(StorageBinding binding) throws ArtifactStoreException {
        Objects.requireNonNull(binding, "binding");
        return withBindingLock(binding, hash -> {
            StatResult stat = statLocked(hash, binding, null);
            if (stat.state() != StoreState.READY) {
                Code code = switch (stat.state()) {
                    case NOT_FOUND -> Code.NOT_FOUND;
                    case PENDING, DELETING -> Code.NOT_READY;
                    case DELETED -> Code.DELETED;
                    case READY -> throw new IllegalStateException("unreachable READY branch");
                };
                throw failure(code);
            }
            Path object = objectPath(hash);
            requireRegularFile(object);
            try {
                FileChannel channel = FileChannel.open(
                        object, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                logSuccess("open-read", "READY");
                return new ReadHandle(
                        stat.artifact(), Channels.newInputStream(channel));
            } catch (IOException failure) {
                throw failure(Code.STORAGE_UNAVAILABLE, failure);
            }
        });
    }

    @Override
    public DeleteResult delete(StorageBinding binding) throws ArtifactStoreException {
        Objects.requireNonNull(binding, "binding");
        return withBindingLock(binding,
                hash -> deleteLocked(hash, binding, null, false));
    }

    @Override
    public DeleteResult deleteBySpec(WriteIntentSpec spec) throws ArtifactStoreException {
        Objects.requireNonNull(spec, "spec");
        WriteIntent candidate = deriveIntent(spec);
        return withBindingLock(candidate.binding(), hash -> {
            validateDeleteBySpecPreconditions(hash, candidate);
            return deleteLocked(hash, candidate.binding(), candidate, true);
        });
    }

    private StatResult statLocked(
            String hash,
            StorageBinding binding,
            WriteIntentSpec expectedSpec)
            throws ArtifactStoreException, IOException {
        boolean deletingExists = exists(deletingPath(hash));
        boolean deletedExists = exists(deletedPath(hash));
        if (deletingExists && deletedExists) {
            throw failure(Code.STORAGE_CORRUPT);
        }
        if (deletingExists) {
            TombstoneMetadata tombstone = readTombstone(deletingPath(hash), binding);
            validateTombstoneSpec(hash, tombstone, expectedSpec);
            requireNoTombstoneTemporary(hash);
            return StatResult.withoutArtifact(StoreState.DELETING);
        }
        if (deletedExists) {
            TombstoneMetadata tombstone = readTombstone(deletedPath(hash), binding);
            validateTombstoneSpec(hash, tombstone, expectedSpec);
            if (hasResidualArtifactFiles(hash) || exists(deletingTemporaryPath(hash))) {
                throw failure(Code.STORAGE_CORRUPT);
            }
            return StatResult.withoutArtifact(StoreState.DELETED);
        }
        if (exists(readyPath(hash))) {
            ArtifactDescriptor ready = readReadyAndVerify(hash, binding);
            validateReadyStateConsistency(hash, ready);
            requireExpectedSpec(ready.intent().spec(), expectedSpec);
            return new StatResult(StoreState.READY, ready);
        }
        if (hasPendingArtifactFiles(hash)) {
            WriteIntent persisted = validatePendingMetadata(hash, binding);
            if (persisted != null) {
                requireExpectedSpec(persisted.spec(), expectedSpec);
            }
            return StatResult.withoutArtifact(StoreState.PENDING);
        }
        return StatResult.withoutArtifact(StoreState.NOT_FOUND);
    }

    private WriteIntent validatePendingMetadata(String hash, StorageBinding binding)
            throws ArtifactStoreException, IOException {
        WriteIntent persisted = null;
        if (exists(intentPath(hash))) {
            persisted = readIntent(intentPath(hash));
            requireStoredIntentMatchesBinding(persisted, binding);
        }
        boolean contentStateExists = exists(partPath(hash))
                || exists(stagedPath(hash))
                || exists(verifiedTemporaryPath(hash))
                || exists(verifiedPath(hash))
                || exists(objectPath(hash))
                || exists(readyTemporaryPath(hash));
        // createWriteIntent 可能在原子发布 intent marker 前崩溃，仅留下 intent.tmp；
        // 任何内容文件都只能发生在正式 intent 已经持久化之后。缺失 intent 的 blob、
        // verified/ready 临时 marker 不是可恢复状态，必须按篡改或损坏关闭式失败。
        if (persisted == null && contentStateExists) {
            throw failure(Code.STORAGE_CORRUPT);
        }
        if (exists(verifiedPath(hash))) {
            StagedMetadata staged = readStaged(verifiedPath(hash));
            requireStoredIntentMatchesBinding(staged.intent(), binding);
            if (persisted == null || !persisted.equals(staged.intent())) {
                throw failure(Code.STORAGE_CORRUPT);
            }
            Path content = exists(stagedPath(hash)) ? stagedPath(hash) : objectPath(hash);
            if (!exists(content)) {
                throw failure(Code.STORAGE_CORRUPT);
            }
            verifyContent(content, staged.content());
        }
        requireSafeKnownFileIfExists(partPath(hash));
        requireSafeKnownFileIfExists(stagedPath(hash));
        requireSafeKnownFileIfExists(objectPath(hash));
        requireSafeKnownFileIfExists(intentTemporaryPath(hash));
        requireSafeKnownFileIfExists(verifiedTemporaryPath(hash));
        requireSafeKnownFileIfExists(readyTemporaryPath(hash));
        return persisted;
    }

    private ArtifactDescriptor readReadyAndVerify(String hash, StorageBinding binding)
            throws ArtifactStoreException, IOException {
        ArtifactDescriptor descriptor = readReady(readyPath(hash));
        requireStoredIntentMatchesBinding(descriptor.intent(), binding);
        WriteIntent persisted = readIntent(intentPath(hash));
        if (!persisted.equals(descriptor.intent())) {
            throw failure(Code.STORAGE_CORRUPT);
        }
        verifyContent(objectPath(hash), descriptor.content());
        return descriptor;
    }

    /** READY 是 intent、verified、object、ready 的单一一致快照，不能夹带半写文件。 */
    private void validateReadyStateConsistency(
            String hash,
            ArtifactDescriptor descriptor) throws ArtifactStoreException, IOException {
        for (Path impossible : new Path[]{
                intentTemporaryPath(hash), partPath(hash), stagedPath(hash),
                verifiedTemporaryPath(hash), readyTemporaryPath(hash)}) {
            requireSafeKnownFileIfExists(impossible);
            if (exists(impossible)) {
                throw failure(Code.STORAGE_CORRUPT);
            }
        }
        if (!exists(verifiedPath(hash))) {
            throw failure(Code.STORAGE_CORRUPT);
        }
        StagedMetadata staged = readStaged(verifiedPath(hash));
        if (!staged.intent().equals(descriptor.intent())
                || !staged.content().equals(descriptor.content())) {
            throw failure(Code.STORAGE_CORRUPT);
        }
    }

    private ContentClaim writeAndMeasurePart(
            String hash,
            WriteIntentSpec spec,
            InputStream content) throws ArtifactStoreException {
        Path part = partPath(hash);
        MessageDigest digest = sha256Digest();
        long size = 0;
        try (FileChannel channel = FileChannel.open(
                part,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            secureFilePermissions(part);
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = content.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                size = Math.addExact(size, read);
                if (size > maxArtifactBytes) {
                    throw failure(Code.CONTENT_TOO_LARGE);
                }
                if (size > spec.expectedSize()) {
                    throw failure(Code.SIZE_MISMATCH);
                }
                digest.update(buffer, 0, read);
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
            }
            channel.force(true);
        } catch (ArtifactStoreException failure) {
            bestEffortDelete(part);
            throw failure;
        } catch (ArithmeticException failure) {
            bestEffortDelete(part);
            throw failure(Code.CONTENT_TOO_LARGE, failure);
        } catch (IOException failure) {
            bestEffortDelete(part);
            throw failure(Code.STORAGE_UNAVAILABLE, failure);
        }

        String checksum = "sha256:" + HexFormat.of().formatHex(digest.digest());
        if (size != spec.expectedSize()) {
            bestEffortDelete(part);
            throw failure(Code.SIZE_MISMATCH);
        }
        if (!checksum.equals(spec.expectedChecksum())) {
            bestEffortDelete(part);
            throw failure(Code.CHECKSUM_MISMATCH);
        }
        return new ContentClaim(size, checksum);
    }

    private void verifyContent(Path path, ContentClaim expected)
            throws ArtifactStoreException, IOException {
        requireRegularFile(path);
        if (Files.size(path) != expected.size()) {
            throw failure(Code.STORAGE_CORRUPT);
        }
        MessageDigest digest = sha256Digest();
        long size = 0;
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             InputStream input = Channels.newInputStream(channel)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                size = Math.addExact(size, read);
                // maxArtifactBytes 是新建/写入保护，不是历史数据可见性上限。部署缩小
                // 配置后，READY 校验、inspect、读取和删除仍必须按持久 claim 工作，
                // 否则旧大对象会既不可验证又不可删除。
                if (size > expected.size()) {
                    throw failure(Code.STORAGE_CORRUPT);
                }
                digest.update(buffer, 0, read);
            }
        } catch (ArithmeticException failure) {
            throw failure(Code.STORAGE_CORRUPT, failure);
        }
        String checksum = "sha256:" + HexFormat.of().formatHex(digest.digest());
        if (size != expected.size() || !checksum.equals(expected.checksum())) {
            throw failure(Code.STORAGE_CORRUPT);
        }
    }

    private void requireExactPersistedIntent(String hash, WriteIntent expected)
            throws ArtifactStoreException, IOException {
        if (!exists(intentPath(hash))) {
            throw failure(Code.INVALID_INTENT);
        }
        WriteIntent persisted = readIntent(intentPath(hash));
        requireStoredIntentMatchesBinding(persisted, expected.binding());
        if (!persisted.equals(expected)) {
            throw failure(Code.INVALID_INTENT);
        }
    }

    private void rejectDeleted(String hash, WriteIntent candidate)
            throws ArtifactStoreException, IOException {
        boolean deletingExists = exists(deletingPath(hash));
        boolean deletedExists = exists(deletedPath(hash));
        if (deletingExists && deletedExists) {
            throw failure(Code.STORAGE_CORRUPT);
        }
        Path tombstonePath = deletingExists
                ? deletingPath(hash) : (deletedExists ? deletedPath(hash) : null);
        if (tombstonePath != null) {
            TombstoneMetadata tombstone = readTombstone(
                    tombstonePath, candidate.binding());
            validateTombstoneSpec(hash, tombstone, candidate.spec());
            throw failure(Code.DELETED);
        }
    }

    /**
     * 在 deleteBySpec 产生任何写入前完成冲突与损坏检查，确保冲突时文件树逐字节不变。
     */
    private void validateDeleteBySpecPreconditions(String hash, WriteIntent candidate)
            throws ArtifactStoreException, IOException {
        boolean deletingExists = exists(deletingPath(hash));
        boolean deletedExists = exists(deletedPath(hash));
        if (deletingExists && deletedExists) {
            throw failure(Code.STORAGE_CORRUPT);
        }

        Path tombstonePath = deletingExists
                ? deletingPath(hash) : (deletedExists ? deletedPath(hash) : null);
        if (tombstonePath != null) {
            TombstoneMetadata tombstone = readTombstone(
                    tombstonePath, candidate.binding());
            validateTombstoneSpec(hash, tombstone, candidate.spec());
            // DELETED 后的残留由本次重放清理；这里不能调用 statLocked，因为只读 stat
            // 会把这种矛盾状态正确地报告为 STORAGE_CORRUPT。
            return;
        }

        // 没有 tombstone 时复用只读检查，验证 READY 内容、PENDING metadata、symlink
        // 与孤儿状态。该调用不会制造 intent 或 tombstone。
        statLocked(hash, candidate.binding(), candidate.spec());
    }

    private DeleteResult deleteLocked(
            String hash,
            StorageBinding binding,
            WriteIntent candidate,
            boolean preserveFullSpec) throws ArtifactStoreException, IOException {
        Path deleting = deletingPath(hash);
        Path deleted = deletedPath(hash);
        boolean deletingExists = exists(deleting);
        boolean completedBefore = exists(deleted);
        if (deletingExists && completedBefore) {
            throw failure(Code.STORAGE_CORRUPT);
        }

        if (completedBefore) {
            TombstoneMetadata tombstone = readTombstone(deleted, binding);
            if (candidate != null) {
                validateTombstoneSpec(hash, tombstone, candidate.spec());
            }
            deleteRegularFileIfExists(deletingTemporaryPath(hash));
            if (!hasResidualArtifactFiles(hash)) {
                logSuccess(preserveFullSpec ? "delete-by-spec" : "delete",
                        DeleteStatus.ALREADY_DELETED.name());
                return new DeleteResult(DeleteStatus.ALREADY_DELETED, binding);
            }
        }

        if (deletingExists) {
            TombstoneMetadata tombstone = readTombstone(deleting, binding);
            if (candidate != null) {
                validateTombstoneSpec(hash, tombstone, candidate.spec());
            }
            deleteRegularFileIfExists(deletingTemporaryPath(hash));
        } else if (!completedBefore) {
            byte[] encoded = preserveFullSpec
                    ? encodeSpecTombstone(Objects.requireNonNull(candidate, "candidate"))
                    : encodeTombstone(binding);
            writeMetadataAtomically(
                    deletingTemporaryPath(hash), deleting, encoded,
                    TombstonePublishPoint.DELETING);
        }

        deleteArtifactFiles(hash);
        forceDirectory(intentsDirectory);
        forceDirectory(stagingDirectory);
        forceDirectory(objectsDirectory);

        if (completedBefore) {
            deleteRegularFileIfExists(deleting);
        } else {
            moveAtomically(deleting, deleted, TombstonePublishPoint.DELETED);
        }
        if (hasResidualArtifactFiles(hash)) {
            throw failure(Code.COMMIT_UNKNOWN);
        }
        logSuccess(preserveFullSpec ? "delete-by-spec" : "delete",
                DeleteStatus.DELETED.name());
        return new DeleteResult(DeleteStatus.DELETED, binding);
    }

    /**
     * 校验候选 spec、正式 intent 与完整 tombstone 的不可变身份。
     *
     * <p>旧版 tombstone 只有 binding，无法反推出完整 spec，因此只做 binding 校验。
     * 新版 tombstone 与正式 intent 任一不一致都返回 STORAGE_CONFLICT；这是合法赢家
     * 冲突而不是磁盘损坏，且检查发生在任何清理之前。</p>
     */
    private void validateTombstoneSpec(
            String hash,
            TombstoneMetadata tombstone,
            WriteIntentSpec expectedSpec) throws ArtifactStoreException, IOException {
        WriteIntent tombstoneIntent = tombstone.intent();
        if (tombstoneIntent != null) {
            requireStoredIntentMatchesBinding(tombstoneIntent, tombstone.binding());
            requireExpectedSpec(tombstoneIntent.spec(), expectedSpec);
        }
        if (exists(intentPath(hash))) {
            WriteIntent persisted = readIntent(intentPath(hash));
            requireStoredIntentMatchesBinding(persisted, tombstone.binding());
            if (tombstoneIntent != null && !persisted.spec().equals(tombstoneIntent.spec())) {
                throw failure(Code.STORAGE_CONFLICT);
            }
            requireExpectedSpec(persisted.spec(), expectedSpec);
        }
    }

    private void requireExpectedSpec(
            WriteIntentSpec actual,
            WriteIntentSpec expected) throws ArtifactStoreException {
        if (expected != null && !actual.equals(expected)) {
            throw failure(Code.STORAGE_CONFLICT);
        }
    }

    private void requireNoTombstoneTemporary(String hash) throws ArtifactStoreException {
        Path temporary = deletingTemporaryPath(hash);
        requireSafeKnownFileIfExists(temporary);
        if (exists(temporary)) {
            throw failure(Code.STORAGE_CORRUPT);
        }
    }

    private void validateCallerIntent(WriteIntent intent) throws ArtifactStoreException {
        Objects.requireNonNull(intent, "intent");
        WriteIntent derived = deriveIntent(intent.spec());
        if (!derived.equals(intent)) {
            throw failure(Code.INVALID_INTENT);
        }
    }

    private void requireStoredIntentMatchesBinding(
            WriteIntent intent,
            StorageBinding binding) throws ArtifactStoreException {
        WriteIntent derived = deriveIntent(intent.spec());
        if (!derived.equals(intent) || !intent.binding().equals(binding)) {
            throw failure(Code.STORAGE_CORRUPT);
        }
    }

    private void requireWithinLimit(WriteIntentSpec spec) throws ArtifactStoreException {
        if (spec.expectedSize() > maxArtifactBytes) {
            throw failure(Code.CONTENT_TOO_LARGE);
        }
    }

    private void requireClaimMatchesSpec(WriteIntentSpec spec, ContentClaim claim)
            throws ArtifactStoreException {
        if (claim.size() != spec.expectedSize()) {
            throw failure(Code.SIZE_MISMATCH);
        }
        if (!claim.checksum().equals(spec.expectedChecksum())) {
            throw failure(Code.CHECKSUM_MISMATCH);
        }
    }

    private void requireClaimsEqual(ContentClaim actual, ContentClaim claimed)
            throws ArtifactStoreException {
        if (actual.size() != claimed.size()) {
            throw failure(Code.SIZE_MISMATCH);
        }
        if (!actual.checksum().equals(claimed.checksum())) {
            throw failure(Code.CHECKSUM_MISMATCH);
        }
    }

    private WriteIntent deriveIntent(WriteIntentSpec spec) {
        String hash = sha256Hex(
                "artifact-binding-v1\0".getBytes(StandardCharsets.UTF_8),
                spec.artifactId().getBytes(StandardCharsets.UTF_8));
        StorageBinding binding = new StorageBinding(BINDING_PREFIX + hash);
        return new WriteIntent(INTENT_PREFIX + hash, binding, spec);
    }

    private <T> T withBindingLock(StorageBinding binding, LockedOperation<T> operation)
            throws ArtifactStoreException {
        String hash = requireBindingHash(binding);
        String lockKey = root + "|" + hash;
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        jvmLock.lock();
        try {
            validateStoreDirectories();
            Path lockPath = lockPath(hash);
            requireSafeKnownFileIfExists(lockPath);
            try (FileChannel channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                secureFilePermissions(lockPath);
                try (FileLock ignored = channel.lock()) {
                    validateStoreDirectories();
                    return operation.execute(hash);
                }
            } catch (ArtifactStoreException failure) {
                throw failure;
            } catch (IOException failure) {
                throw failure(Code.STORAGE_UNAVAILABLE, failure);
            }
        } finally {
            jvmLock.unlock();
        }
    }

    /**
     * 每次 binding 操作都重新验证 canonical 路径和目录身份。
     *
     * <p>{@code NOFOLLOW_LINKS} 只约束最终文件；如果可信 root 下的某个子目录在 Store
     * 构造后被替换为 symlink，单独检查最终 blob 仍会跟随父路径写到 root 外。这里在
     * 打开 lock 或业务文件前关闭式拒绝 root 及全部固定子目录的祖先改写、替换或
     * delete/recreate。</p>
     */
    private void validateStoreDirectories() throws ArtifactStoreException {
        for (DirectoryIdentity identity : directoryIdentities) {
            Path directory = identity.path();
            try {
                if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(directory)
                        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                        || !directory.toRealPath().equals(directory)) {
                    throw failure(Code.UNSAFE_FILESYSTEM);
                }
                BasicFileAttributes attributes = Files.readAttributes(
                        directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory()
                        || attributes.fileKey() == null
                        || !attributes.fileKey().equals(identity.fileKey())) {
                    throw failure(Code.UNSAFE_FILESYSTEM);
                }
            } catch (ArtifactStoreException failure) {
                throw failure;
            } catch (IOException failure) {
                throw failure(Code.UNSAFE_FILESYSTEM, failure);
            }
        }
    }

    private String requireBindingHash(StorageBinding binding) throws ArtifactStoreException {
        Matcher matcher = BINDING_PATTERN.matcher(binding.value());
        if (!matcher.matches()) {
            throw failure(Code.INVALID_INTENT);
        }
        return matcher.group(1);
    }

    private StoreLayout initializeLayout(Path requestedRoot) throws ArtifactStoreException {
        try {
            if (Files.isSymbolicLink(requestedRoot)) {
                throw failure(Code.UNSAFE_FILESYSTEM);
            }
            createSecureDirectory(requestedRoot);

            Path canonicalRoot = requestedRoot.toRealPath();
            Path canonicalIntents = canonicalRoot.resolve("intents");
            Path canonicalStaging = canonicalRoot.resolve("staging");
            Path canonicalObjects = canonicalRoot.resolve("objects");
            Path canonicalTombstones = canonicalRoot.resolve("tombstones");
            Path canonicalLocks = canonicalRoot.resolve("locks");
            createSecureDirectory(canonicalIntents);
            createSecureDirectory(canonicalStaging);
            createSecureDirectory(canonicalObjects);
            createSecureDirectory(canonicalTombstones);
            createSecureDirectory(canonicalLocks);

            List<DirectoryIdentity> identities = List.of(
                    captureDirectoryIdentity(canonicalRoot),
                    captureDirectoryIdentity(canonicalIntents),
                    captureDirectoryIdentity(canonicalStaging),
                    captureDirectoryIdentity(canonicalObjects),
                    captureDirectoryIdentity(canonicalTombstones),
                    captureDirectoryIdentity(canonicalLocks));
            return new StoreLayout(
                    canonicalRoot,
                    canonicalIntents,
                    canonicalStaging,
                    canonicalObjects,
                    canonicalTombstones,
                    canonicalLocks,
                    identities);
        } catch (ArtifactStoreException failure) {
            throw failure;
        } catch (IOException failure) {
            throw failure(Code.STORAGE_UNAVAILABLE, failure);
        }
    }

    private DirectoryIdentity captureDirectoryIdentity(Path directory)
            throws IOException, ArtifactStoreException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.toRealPath().equals(directory)) {
            throw failure(Code.UNSAFE_FILESYSTEM);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.fileKey() == null) {
            throw failure(Code.UNSAFE_FILESYSTEM);
        }
        return new DirectoryIdentity(directory, attributes.fileKey());
    }

    private void createSecureDirectory(Path directory)
            throws IOException, ArtifactStoreException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(Code.UNSAFE_FILESYSTEM);
            }
        } else {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(Code.UNSAFE_FILESYSTEM);
            }
        }
        try {
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 测试文件系统仍由 NOFOLLOW_LINKS 和固定路径保护。
        }
    }

    private void secureFilePermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 测试文件系统不提供权限位。
        }
    }

    private void writeMetadataAtomically(Path temporary, Path target, byte[] bytes)
            throws IOException, ArtifactStoreException {
        writeMetadataAtomically(temporary, target, bytes, null);
    }

    private void writeMetadataAtomically(
            Path temporary,
            Path target,
            byte[] bytes,
            TombstonePublishPoint publishPoint)
            throws IOException, ArtifactStoreException {
        if (bytes.length > MAX_METADATA_BYTES) {
            throw failure(Code.STORAGE_CORRUPT);
        }
        deleteRegularFileIfExists(temporary);
        if (exists(target)) {
            throw failure(Code.STORAGE_CORRUPT);
        }
        try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            secureFilePermissions(temporary);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        moveAtomically(temporary, target, publishPoint);
    }

    private void moveAtomically(Path source, Path target)
            throws IOException, ArtifactStoreException {
        moveAtomically(source, target, null);
    }

    private void moveAtomically(
            Path source,
            Path target,
            TombstonePublishPoint publishPoint)
            throws IOException, ArtifactStoreException {
        requireRegularFile(source);
        if (exists(target)) {
            throw failure(Code.STORAGE_CORRUPT);
        }
        try {
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            throw failure(Code.ATOMIC_PUBLISH_UNSUPPORTED, failure);
        }
        try {
            if (publishPoint != null) {
                tombstonePublishFaultInjector.afterAtomicPublish(publishPoint);
            }
            forceDirectory(target.getParent());
        } catch (IOException failure) {
            throw failure(Code.COMMIT_UNKNOWN, failure);
        }
    }

    private void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private byte[] encodeIntent(WriteIntent intent) throws ArtifactStoreException {
        return encode(KIND_INTENT, output -> writeIntent(output, intent));
    }

    private byte[] encodeStaged(StagedMetadata staged) throws ArtifactStoreException {
        return encode(KIND_STAGED, output -> {
            writeIntent(output, staged.intent());
            writeClaim(output, staged.content());
        });
    }

    private byte[] encodeReady(ArtifactDescriptor descriptor) throws ArtifactStoreException {
        return encode(KIND_READY, output -> {
            writeIntent(output, descriptor.intent());
            writeClaim(output, descriptor.content());
        });
    }

    private byte[] encodeTombstone(StorageBinding binding) throws ArtifactStoreException {
        return encode(KIND_TOMBSTONE, output -> writeString(output, binding.value()));
    }

    private byte[] encodeSpecTombstone(WriteIntent intent) throws ArtifactStoreException {
        return encode(KIND_SPEC_TOMBSTONE, output -> writeIntent(output, intent));
    }

    private byte[] encode(int kind, MetadataWriter writer) throws ArtifactStoreException {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(METADATA_MAGIC);
                output.writeInt(METADATA_VERSION);
                output.writeInt(kind);
                writer.write(output);
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_METADATA_BYTES) {
                throw failure(Code.STORAGE_CORRUPT);
            }
            return encoded;
        } catch (IOException failure) {
            throw failure(Code.STORAGE_UNAVAILABLE, failure);
        }
    }

    private WriteIntent readIntent(Path path) throws ArtifactStoreException, IOException {
        return decode(path, KIND_INTENT, this::readIntentBody);
    }

    private StagedMetadata readStaged(Path path) throws ArtifactStoreException, IOException {
        return decode(path, KIND_STAGED,
                input -> new StagedMetadata(readIntentBody(input), readClaim(input)));
    }

    private ArtifactDescriptor readReady(Path path) throws ArtifactStoreException, IOException {
        return decode(path, KIND_READY,
                input -> new ArtifactDescriptor(readIntentBody(input), readClaim(input)));
    }

    private TombstoneMetadata readTombstone(Path path, StorageBinding expected)
            throws ArtifactStoreException, IOException {
        requireRegularFile(path);
        long length = Files.size(path);
        if (length <= 0 || length > MAX_METADATA_BYTES) {
            throw failure(Code.STORAGE_CORRUPT);
        }
        byte[] bytes = Files.readAllBytes(path);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != METADATA_MAGIC || input.readInt() != METADATA_VERSION) {
                throw failure(Code.STORAGE_CORRUPT);
            }
            int kind = input.readInt();
            TombstoneMetadata decoded = switch (kind) {
                case KIND_TOMBSTONE -> new TombstoneMetadata(
                        new StorageBinding(readString(input)), null);
                case KIND_SPEC_TOMBSTONE -> {
                    WriteIntent intent = readIntentBody(input);
                    yield new TombstoneMetadata(intent.binding(), intent);
                }
                default -> throw failure(Code.STORAGE_CORRUPT);
            };
            if (input.available() != 0 || !decoded.binding().equals(expected)) {
                throw failure(Code.STORAGE_CORRUPT);
            }
            if (decoded.intent() != null) {
                requireStoredIntentMatchesBinding(decoded.intent(), expected);
            }
            return decoded;
        } catch (ArtifactStoreException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw failure(Code.STORAGE_CORRUPT, failure);
        }
    }

    private <T> T decode(Path path, int expectedKind, MetadataReader<T> reader)
            throws ArtifactStoreException, IOException {
        requireRegularFile(path);
        long length = Files.size(path);
        if (length <= 0 || length > MAX_METADATA_BYTES) {
            throw failure(Code.STORAGE_CORRUPT);
        }
        byte[] bytes = Files.readAllBytes(path);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != METADATA_MAGIC
                    || input.readInt() != METADATA_VERSION
                    || input.readInt() != expectedKind) {
                throw failure(Code.STORAGE_CORRUPT);
            }
            T result = reader.read(input);
            if (input.available() != 0) {
                throw failure(Code.STORAGE_CORRUPT);
            }
            return result;
        } catch (ArtifactStoreException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw failure(Code.STORAGE_CORRUPT, failure);
        }
    }

    private void writeIntent(DataOutputStream output, WriteIntent intent) throws IOException {
        writeString(output, intent.intentId());
        writeString(output, intent.binding().value());
        WriteIntentSpec spec = intent.spec();
        writeString(output, spec.artifactId());
        writeString(output, spec.meetingId());
        writeString(output, spec.artifactType());
        writeString(output, spec.mediaType());
        output.writeBoolean(spec.language() != null);
        if (spec.language() != null) {
            writeString(output, spec.language());
        }
        output.writeInt(spec.revision());
        output.writeLong(spec.expectedSize());
        writeString(output, spec.expectedChecksum());
    }

    private WriteIntent readIntentBody(DataInputStream input) throws IOException {
        String intentId = readString(input);
        StorageBinding binding = new StorageBinding(readString(input));
        String artifactId = readString(input);
        String meetingId = readString(input);
        String artifactType = readString(input);
        String mediaType = readString(input);
        String language = input.readBoolean() ? readString(input) : null;
        int revision = input.readInt();
        long expectedSize = input.readLong();
        String checksum = readString(input);
        return new WriteIntent(intentId, binding, new WriteIntentSpec(
                artifactId, meetingId, artifactType, mediaType, language,
                revision, expectedSize, checksum));
    }

    private void writeClaim(DataOutputStream output, ContentClaim claim) throws IOException {
        output.writeLong(claim.size());
        writeString(output, claim.checksum());
    }

    private ContentClaim readClaim(DataInputStream input) throws IOException {
        return new ContentClaim(input.readLong(), readString(input));
    }

    private void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 0 || bytes.length > MAX_FIELD_BYTES) {
            throw new IOException("metadata field length is invalid");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > MAX_FIELD_BYTES || length > input.available()) {
            throw new IOException("metadata field length is invalid");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("metadata field is truncated");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new IOException("metadata field is not UTF-8");
        }
    }

    private void deleteArtifactFiles(String hash) throws ArtifactStoreException, IOException {
        deleteRegularFileIfExists(intentTemporaryPath(hash));
        deleteRegularFileIfExists(intentPath(hash));
        deleteRegularFileIfExists(partPath(hash));
        deleteRegularFileIfExists(stagedPath(hash));
        deleteRegularFileIfExists(verifiedTemporaryPath(hash));
        deleteRegularFileIfExists(verifiedPath(hash));
        deleteRegularFileIfExists(readyTemporaryPath(hash));
        deleteRegularFileIfExists(readyPath(hash));
        deleteRegularFileIfExists(objectPath(hash));
    }

    private boolean hasResidualArtifactFiles(String hash)
            throws ArtifactStoreException {
        for (Path path : artifactFiles(hash)) {
            requireSafeKnownFileIfExists(path);
            if (exists(path)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPendingArtifactFiles(String hash) throws ArtifactStoreException {
        for (Path path : artifactFiles(hash)) {
            requireSafeKnownFileIfExists(path);
            if (exists(path)) {
                return true;
            }
        }
        return false;
    }

    /** intent.tmp 可由 prepare 崩溃留下；其它内容状态都要求正式 intent 已存在。 */
    private boolean hasContentStateWithoutIntent(String hash) throws ArtifactStoreException {
        for (Path path : new Path[]{
                partPath(hash), stagedPath(hash),
                verifiedTemporaryPath(hash), verifiedPath(hash),
                objectPath(hash), readyTemporaryPath(hash), readyPath(hash)}) {
            requireSafeKnownFileIfExists(path);
            if (exists(path)) {
                return true;
            }
        }
        return false;
    }

    private Path[] artifactFiles(String hash) {
        return new Path[]{
                intentTemporaryPath(hash), intentPath(hash),
                partPath(hash), stagedPath(hash),
                verifiedTemporaryPath(hash), verifiedPath(hash),
                readyTemporaryPath(hash), readyPath(hash), objectPath(hash)
        };
    }

    private void deleteRegularFileIfExists(Path path)
            throws ArtifactStoreException, IOException {
        requireSafeKnownFileIfExists(path);
        Files.deleteIfExists(path);
    }

    private void bestEffortDelete(Path path) {
        try {
            if (!Files.isSymbolicLink(path)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // 原错误仍是权威结果；残留 .part 永远不是 READY，后续重试会再次清理。
        }
    }

    private void requireSafeKnownFileIfExists(Path path) throws ArtifactStoreException {
        if (exists(path) && (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
            throw failure(Code.UNSAFE_FILESYSTEM);
        }
    }

    private void requireRegularFile(Path path) throws ArtifactStoreException {
        if (!exists(path) || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(Code.UNSAFE_FILESYSTEM);
        }
    }

    private static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private Path intentPath(String hash) {
        return intentsDirectory.resolve(hash + ".intent");
    }

    private Path intentTemporaryPath(String hash) {
        return intentsDirectory.resolve(hash + ".intent.tmp");
    }

    private Path partPath(String hash) {
        return stagingDirectory.resolve(hash + ".blob.part");
    }

    private Path stagedPath(String hash) {
        return stagingDirectory.resolve(hash + ".blob");
    }

    private Path verifiedPath(String hash) {
        return stagingDirectory.resolve(hash + ".verified");
    }

    private Path verifiedTemporaryPath(String hash) {
        return stagingDirectory.resolve(hash + ".verified.tmp");
    }

    private Path objectPath(String hash) {
        return objectsDirectory.resolve(hash + ".blob");
    }

    private Path readyPath(String hash) {
        return objectsDirectory.resolve(hash + ".ready");
    }

    private Path readyTemporaryPath(String hash) {
        return objectsDirectory.resolve(hash + ".ready.tmp");
    }

    private Path deletingPath(String hash) {
        return tombstonesDirectory.resolve(hash + ".deleting");
    }

    private Path deletingTemporaryPath(String hash) {
        return tombstonesDirectory.resolve(hash + ".deleting.tmp");
    }

    private Path deletedPath(String hash) {
        return tombstonesDirectory.resolve(hash + ".deleted");
    }

    private Path lockPath(String hash) {
        return locksDirectory.resolve(hash + ".lock");
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String sha256Hex(byte[]... values) {
        MessageDigest digest = sha256Digest();
        for (byte[] value : values) {
            digest.update(value);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private ArtifactStoreException failure(Code code) {
        return failure(code, null);
    }

    private ArtifactStoreException failure(Code code, Throwable cause) {
        String diagnosticId = diagnosticIds.get();
        if (diagnosticId == null || diagnosticId.isBlank()) {
            diagnosticId = "diagnostic-id-unavailable";
        }
        Level level = switch (code) {
            case NOT_FOUND, NOT_READY -> Level.FINE;
            case STORAGE_UNAVAILABLE, COMMIT_UNKNOWN -> Level.WARNING;
            default -> Level.WARNING;
        };
        if (cause == null) {
            LOGGER.log(level,
                    "filesystem artifact operation failed code={0} retryable={1} diagnosticId={2}",
                    new Object[]{code, code.retryable(), diagnosticId});
        } else {
            LOGGER.log(level,
                    "filesystem artifact operation failed code={0} retryable={1} "
                            + "diagnosticId={2} causeType={3}",
                    new Object[]{code, code.retryable(), diagnosticId,
                            cause.getClass().getSimpleName()});
        }
        return new ArtifactStoreException(code, diagnosticId);
    }

    private static void logSuccess(String operation, String status) {
        LOGGER.log(Level.FINE,
                "filesystem artifact operation completed operation={0} status={1}",
                new Object[]{operation, status});
    }

    private record StagedMetadata(WriteIntent intent, ContentClaim content) {
        private StagedMetadata {
            Objects.requireNonNull(intent, "intent");
            Objects.requireNonNull(content, "content");
            if (content.size() != intent.spec().expectedSize()
                    || !content.checksum().equals(intent.spec().expectedChecksum())) {
                throw new IllegalArgumentException("staged metadata does not match intent");
            }
        }
    }

    private record TombstoneMetadata(StorageBinding binding, WriteIntent intent) {
        private TombstoneMetadata {
            Objects.requireNonNull(binding, "binding");
            if (intent != null && !intent.binding().equals(binding)) {
                throw new IllegalArgumentException(
                        "tombstone intent must match tombstone binding");
            }
        }
    }

    /** tombstone 的两个持久线性化点，仅供同包故障测试精确注入。 */
    enum TombstonePublishPoint {
        DELETING,
        DELETED
    }

    /**
     * package-private 故障注入边界。回调发生在原子 rename 成功之后、目录 fsync 之前，
     * 因此抛出 IOException 必须被映射为 COMMIT_UNKNOWN，调用方只能通过 inspect 恢复。
     */
    @FunctionalInterface
    interface TombstonePublishFaultInjector {
        TombstonePublishFaultInjector NONE = ignored -> { };

        void afterAtomicPublish(TombstonePublishPoint point) throws IOException;
    }

    private record DirectoryIdentity(Path path, Object fileKey) {
        private DirectoryIdentity {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(fileKey, "fileKey");
        }
    }

    private record StoreLayout(
            Path root,
            Path intents,
            Path staging,
            Path objects,
            Path tombstones,
            Path locks,
            List<DirectoryIdentity> identities) {
        private StoreLayout {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(intents, "intents");
            Objects.requireNonNull(staging, "staging");
            Objects.requireNonNull(objects, "objects");
            Objects.requireNonNull(tombstones, "tombstones");
            Objects.requireNonNull(locks, "locks");
            identities = List.copyOf(identities);
        }
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T execute(String hash) throws IOException, ArtifactStoreException;
    }

    @FunctionalInterface
    private interface MetadataWriter {
        void write(DataOutputStream output) throws IOException;
    }

    @FunctionalInterface
    private interface MetadataReader<T> {
        T read(DataInputStream input) throws IOException;
    }
}
