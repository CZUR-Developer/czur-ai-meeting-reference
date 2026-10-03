package com.czur.reference.application.artifact.s3;

import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStoreException;
import com.czur.reference.application.port.artifact.ArtifactStoreException.Code;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单进程参考后端使用的 S3 兼容 Artifact Store。
 *
 * <p>每个 artifactId 映射到固定对象前缀。intent 保存不可变写入定义，blob 先暂存；
 * READY 标记最后写入，它是对读取者可见的发布点。删除标记先于对象清理写入，
 * 因而晚到的同进程写入不能使已删除产物再次可见。此实现面向参考后端的单进程
 * 部署，不提供多个后端进程间的事务协调。</p>
 *
 * <p>S3 连接和密钥只在此基础设施适配器内使用。业务层只保存不透明 binding；
 * 不向 Web 或 Android 暴露对象地址、Bucket 或访问密钥。</p>
 */
public final class S3ArtifactStore implements ArtifactStore, AutoCloseable {
    private static final String BINDING_PREFIX = "s3_v1_";
    private static final String INTENT_PREFIX = "s3_intent_v1_";
    private static final int FORMAT_VERSION = 1;
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private final ObjectsApi objects;
    private final String objectPrefix;
    private final String lockNamespace;
    private final long maxArtifactBytes;

    /**
     * 使用显式部署材料建立 S3 客户端。endpoint 为空时使用该 Region 的标准 S3
     * 地址；设置 endpoint 时启用 path-style，以兼容局域网对象存储。
     */
    public S3ArtifactStore(URI endpoint, String region, String bucket, String prefix,
            String accessKeyId, String secretAccessKey, long maxArtifactBytes) {
        if (maxArtifactBytes <= 0) throw new IllegalArgumentException("maxArtifactBytes");
        requireNonBlank(region, "region");
        requireNonBlank(bucket, "bucket");
        requireNonBlank(accessKeyId, "accessKeyId");
        requireNonBlank(secretAccessKey, "secretAccessKey");
        String normalizedPrefix = normalizePrefix(prefix);
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(endpoint != null).build());
        if (endpoint != null) builder.endpointOverride(endpoint);
        S3Client client = builder.build();
        this.objects = new AwsObjectsApi(client, bucket);
        this.objectPrefix = normalizedPrefix;
        this.lockNamespace = bucket + "/" + normalizedPrefix;
        this.maxArtifactBytes = maxArtifactBytes;
    }

    /** 测试入口：仅替换对象传输，不改变 Store 状态机。 */
    S3ArtifactStore(ObjectsApi objects, String objectPrefix, long maxArtifactBytes) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.objectPrefix = Objects.requireNonNull(objectPrefix, "objectPrefix");
        this.lockNamespace = "test/" + objectPrefix;
        if (maxArtifactBytes <= 0) throw new IllegalArgumentException("maxArtifactBytes");
        this.maxArtifactBytes = maxArtifactBytes;
    }

    @Override
    public PrepareResult createWriteIntent(WriteIntentSpec spec) throws ArtifactStoreException {
        Objects.requireNonNull(spec, "spec");
        requireSize(spec);
        WriteIntent candidate = derive(spec);
        synchronized (lock(candidate.binding())) {
            if (objects.get(key(candidate.binding(), "deleted")) != null) {
                throw error(Code.DELETED);
            }
            byte[] existing = objects.get(key(candidate.binding(), "intent"));
            if (existing == null) {
                boolean created = objects.putAbsent(key(candidate.binding(), "intent"), encode(spec));
                if (created) return new PrepareResult(PrepareStatus.APPLIED, candidate);
                existing = objects.get(key(candidate.binding(), "intent"));
            }
            if (existing == null) throw error(Code.STORAGE_UNAVAILABLE);
            WriteIntent winner = derive(decode(existing));
            return new PrepareResult(winner.spec().equals(spec)
                    ? PrepareStatus.REPLAYED : PrepareStatus.CONFLICT, winner);
        }
    }

    @Override
    public StageResult writeContent(WriteIntent intent, InputStream content)
            throws ArtifactStoreException {
        Objects.requireNonNull(content, "content");
        requireIntent(intent);
        synchronized (lock(intent.binding())) {
            requireLiveIntent(intent);
            byte[] existing = objects.get(key(intent.binding(), "blob"));
            if (existing != null) {
                verify(existing, intent.spec());
                return new StageResult(StageStatus.REPLAYED, intent, claim(intent.spec()));
            }
            byte[] bytes = readBounded(content, intent.spec().expectedSize());
            verify(bytes, intent.spec());
            if (!objects.putAbsent(key(intent.binding(), "blob"), bytes)) {
                existing = objects.get(key(intent.binding(), "blob"));
                if (existing == null) throw error(Code.STORAGE_UNAVAILABLE);
                verify(existing, intent.spec());
                return new StageResult(StageStatus.REPLAYED, intent, claim(intent.spec()));
            }
            return new StageResult(StageStatus.APPLIED, intent, claim(intent.spec()));
        }
    }

    @Override
    public CompleteResult completeWrite(WriteIntent intent, ContentClaim claim)
            throws ArtifactStoreException {
        requireIntent(intent);
        Objects.requireNonNull(claim, "claim");
        if (claim.size() != intent.spec().expectedSize()) throw error(Code.SIZE_MISMATCH);
        if (!claim.checksum().equals(intent.spec().expectedChecksum())) {
            throw error(Code.CHECKSUM_MISMATCH);
        }
        synchronized (lock(intent.binding())) {
            requireLiveIntent(intent);
            byte[] blob = objects.get(key(intent.binding(), "blob"));
            if (blob == null) throw error(Code.NOT_READY);
            verify(blob, intent.spec());
            boolean created = objects.putAbsent(key(intent.binding(), "ready"), new byte[]{1});
            return new CompleteResult(created ? CompleteStatus.APPLIED : CompleteStatus.REPLAYED,
                    new ArtifactDescriptor(intent, claim));
        }
    }

    @Override
    public StatResult stat(StorageBinding binding) throws ArtifactStoreException {
        requireBinding(binding);
        synchronized (lock(binding)) {
            return statLocked(binding, null);
        }
    }

    @Override
    public InspectionResult inspect(WriteIntentSpec spec) throws ArtifactStoreException {
        Objects.requireNonNull(spec, "spec");
        StorageBinding binding = derive(spec).binding();
        synchronized (lock(binding)) {
            StatResult state = statLocked(binding, spec);
            return state.state() == StoreState.READY
                    ? new InspectionResult(StoreState.READY, binding, state.artifact())
                    : InspectionResult.withoutArtifact(state.state(), binding);
        }
    }

    @Override
    public ReadHandle openRead(StorageBinding binding) throws ArtifactStoreException {
        requireBinding(binding);
        synchronized (lock(binding)) {
            StatResult state = statLocked(binding, null);
            if (state.state() != StoreState.READY) throw error(switch (state.state()) {
                case NOT_FOUND -> Code.NOT_FOUND;
                case DELETED -> Code.DELETED;
                default -> Code.NOT_READY;
            });
            byte[] bytes = objects.get(key(binding, "blob"));
            if (bytes == null) throw error(Code.STORAGE_CORRUPT);
            verify(bytes, state.artifact().intent().spec());
            return new ReadHandle(state.artifact(), new ByteArrayInputStream(bytes));
        }
    }

    @Override
    public DeleteResult delete(StorageBinding binding) throws ArtifactStoreException {
        requireBinding(binding);
        synchronized (lock(binding)) {
            return deleteLocked(binding, null);
        }
    }

    @Override
    public DeleteResult deleteBySpec(WriteIntentSpec spec) throws ArtifactStoreException {
        Objects.requireNonNull(spec, "spec");
        StorageBinding binding = derive(spec).binding();
        synchronized (lock(binding)) {
            return deleteLocked(binding, spec);
        }
    }

    @Override
    public void close() {
        objects.close();
    }

    private StatResult statLocked(StorageBinding binding, WriteIntentSpec expected)
            throws ArtifactStoreException {
        byte[] deleted = objects.get(key(binding, "deleted"));
        if (deleted != null) {
            if (expected != null && deleted.length > 0 && !decode(deleted).equals(expected)) {
                throw error(Code.STORAGE_CONFLICT);
            }
            return StatResult.withoutArtifact(StoreState.DELETED);
        }
        byte[] rawIntent = objects.get(key(binding, "intent"));
        if (rawIntent == null) {
            if (objects.get(key(binding, "ready")) != null) throw error(Code.STORAGE_CORRUPT);
            return StatResult.withoutArtifact(StoreState.NOT_FOUND);
        }
        WriteIntent intent = derive(decode(rawIntent));
        if (!intent.binding().equals(binding)) throw error(Code.STORAGE_CORRUPT);
        if (expected != null && !intent.spec().equals(expected)) throw error(Code.STORAGE_CONFLICT);
        if (objects.get(key(binding, "ready")) == null) {
            return StatResult.withoutArtifact(StoreState.PENDING);
        }
        return new StatResult(StoreState.READY,
                new ArtifactDescriptor(intent, claim(intent.spec())));
    }

    private DeleteResult deleteLocked(StorageBinding binding, WriteIntentSpec expected)
            throws ArtifactStoreException {
        byte[] deleted = objects.get(key(binding, "deleted"));
        if (deleted != null) {
            if (expected != null && deleted.length > 0 && !decode(deleted).equals(expected)) {
                throw error(Code.STORAGE_CONFLICT);
            }
            removeContent(binding);
            return new DeleteResult(DeleteStatus.ALREADY_DELETED, binding);
        }
        byte[] rawIntent = objects.get(key(binding, "intent"));
        WriteIntentSpec stored = rawIntent == null ? null : decode(rawIntent);
        if (stored != null && !derive(stored).binding().equals(binding)) {
            throw error(Code.STORAGE_CORRUPT);
        }
        if (expected != null && stored != null && !stored.equals(expected)) {
            throw error(Code.STORAGE_CONFLICT);
        }
        byte[] marker = stored != null ? rawIntent : expected != null ? encode(expected) : new byte[0];
        objects.putAbsent(key(binding, "deleted"), marker);
        removeContent(binding);
        return new DeleteResult(DeleteStatus.DELETED, binding);
    }

    private void removeContent(StorageBinding binding) throws ArtifactStoreException {
        objects.delete(key(binding, "ready"));
        objects.delete(key(binding, "blob"));
        objects.delete(key(binding, "intent"));
    }

    private void requireLiveIntent(WriteIntent intent) throws ArtifactStoreException {
        if (objects.get(key(intent.binding(), "deleted")) != null) throw error(Code.DELETED);
        byte[] raw = objects.get(key(intent.binding(), "intent"));
        if (raw == null) throw error(Code.NOT_FOUND);
        if (!decode(raw).equals(intent.spec())) throw error(Code.STORAGE_CONFLICT);
    }

    private void requireIntent(WriteIntent intent) throws ArtifactStoreException {
        Objects.requireNonNull(intent, "intent");
        if (!derive(intent.spec()).equals(intent)) throw error(Code.INVALID_INTENT);
        requireSize(intent.spec());
    }

    private void requireSize(WriteIntentSpec spec) throws ArtifactStoreException {
        if (spec.expectedSize() > maxArtifactBytes) throw error(Code.CONTENT_TOO_LARGE);
    }

    private byte[] readBounded(InputStream content, long expected) throws ArtifactStoreException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long size = 0;
            int count;
            while ((count = content.read(buffer)) != -1) {
                size += count;
                if (size > expected || size > maxArtifactBytes) throw error(Code.SIZE_MISMATCH);
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (IOException failure) {
            throw error(Code.STORAGE_UNAVAILABLE);
        }
    }

    private static void verify(byte[] bytes, WriteIntentSpec spec) throws ArtifactStoreException {
        if (bytes.length != spec.expectedSize()) throw error(Code.SIZE_MISMATCH);
        if (!checksum(bytes).equals(spec.expectedChecksum())) throw error(Code.CHECKSUM_MISMATCH);
    }

    private static ContentClaim claim(WriteIntentSpec spec) {
        return new ContentClaim(spec.expectedSize(), spec.expectedChecksum());
    }

    private WriteIntent derive(WriteIntentSpec spec) {
        String hash = sha256("artifact-binding-v1\0".getBytes(StandardCharsets.UTF_8),
                spec.artifactId().getBytes(StandardCharsets.UTF_8));
        return new WriteIntent(INTENT_PREFIX + hash,
                new StorageBinding(BINDING_PREFIX + hash), spec);
    }

    private String key(StorageBinding binding, String leaf) {
        return objectPrefix + binding.value().substring(BINDING_PREFIX.length()) + "/" + leaf;
    }

    private Object lock(StorageBinding binding) {
        return LOCKS.computeIfAbsent(lockNamespace + binding.value(), ignored -> new Object());
    }

    private static void requireBinding(StorageBinding binding) throws ArtifactStoreException {
        Objects.requireNonNull(binding, "binding");
        if (!binding.value().matches("s3_v1_[0-9a-f]{64}")) throw error(Code.INVALID_INTENT);
    }

    private static String checksum(byte[] bytes) {
        return "sha256:" + sha256(bytes);
    }

    private static String sha256(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) digest.update(part);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] encode(WriteIntentSpec spec) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(FORMAT_VERSION);
            out.writeUTF(spec.artifactId());
            out.writeUTF(spec.meetingId());
            out.writeUTF(spec.artifactType());
            out.writeUTF(spec.mediaType());
            out.writeBoolean(spec.language() != null);
            if (spec.language() != null) out.writeUTF(spec.language());
            out.writeInt(spec.revision());
            out.writeLong(spec.expectedSize());
            out.writeUTF(spec.expectedChecksum());
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static WriteIntentSpec decode(byte[] bytes) throws ArtifactStoreException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != FORMAT_VERSION) throw error(Code.STORAGE_CORRUPT);
            String artifactId = in.readUTF();
            String meetingId = in.readUTF();
            String artifactType = in.readUTF();
            String mediaType = in.readUTF();
            String language = in.readBoolean() ? in.readUTF() : null;
            int revision = in.readInt();
            long size = in.readLong();
            String digest = in.readUTF();
            if (in.available() != 0) throw error(Code.STORAGE_CORRUPT);
            return new WriteIntentSpec(artifactId, meetingId, artifactType, mediaType,
                    language, revision, size, digest);
        } catch (IOException | IllegalArgumentException malformed) {
            throw error(Code.STORAGE_CORRUPT);
        }
    }

    private static String normalizePrefix(String prefix) {
        String value = prefix == null ? "artifacts/" : prefix.trim();
        if (value.startsWith("/")) throw new IllegalArgumentException("prefix must be relative");
        if (!value.isEmpty() && !value.endsWith("/")) value += "/";
        return value;
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    }

    private static ArtifactStoreException error(Code code) {
        return new ArtifactStoreException(code, "s3-artifact-store");
    }

    /** 对象传输最小边界，供不依赖外部 S3 的本机契约测试替换。 */
    interface ObjectsApi extends AutoCloseable {
        byte[] get(String key) throws ArtifactStoreException;
        boolean putAbsent(String key, byte[] bytes) throws ArtifactStoreException;
        void delete(String key) throws ArtifactStoreException;
        @Override void close();
    }

    private static final class AwsObjectsApi implements ObjectsApi {
        private final S3Client client;
        private final String bucket;

        AwsObjectsApi(S3Client client, String bucket) {
            this.client = client;
            this.bucket = bucket;
        }

        @Override public byte[] get(String key) throws ArtifactStoreException {
            try {
                return client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(bucket).key(key).build()).asByteArray();
            } catch (S3Exception failure) {
                if (failure.statusCode() == 404) return null;
                throw error(Code.STORAGE_UNAVAILABLE);
            } catch (SdkException failure) {
                throw error(Code.STORAGE_UNAVAILABLE);
            }
        }

        @Override public boolean putAbsent(String key, byte[] bytes)
                throws ArtifactStoreException {
            try {
                client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                        .ifNoneMatch("*").build(), RequestBody.fromBytes(bytes));
                return true;
            } catch (S3Exception failure) {
                if (failure.statusCode() == 412 || failure.statusCode() == 409) return false;
                throw error(Code.STORAGE_UNAVAILABLE);
            } catch (SdkException failure) {
                throw error(Code.STORAGE_UNAVAILABLE);
            }
        }

        @Override public void delete(String key) throws ArtifactStoreException {
            try {
                client.deleteObject(DeleteObjectRequest.builder().bucket(bucket)
                        .key(key).build());
            } catch (SdkException failure) {
                throw error(Code.STORAGE_UNAVAILABLE);
            }
        }

        @Override public void close() { client.close(); }
    }
}
