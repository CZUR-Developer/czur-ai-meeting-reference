package com.czur.reference.application.port.artifact;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 不可变会议产物内容与会议编排之间的最小存储端口。
 *
 * <p>端口使用显式的两段式发布协议：调用方先以稳定 {@code artifactId} 创建
 * {@link WriteIntent}，再把内容写入 Store 的未发布区域，最后用同一份 size 和
 * SHA-256 声明完成写入。只有 {@link #completeWrite(WriteIntent, ContentClaim)}
 * 成功或幂等重放以后，{@link #stat(StorageBinding)} 才允许返回
 * {@link StoreState#READY}，{@link #openRead(StorageBinding)} 才允许打开内容。</p>
 *
 * <p>{@code artifactId} 是逻辑产物的稳定幂等身份，而
 * {@link StorageBinding} 是 Store 返回的不透明内容定位符。调用方不得把 binding
 * 解释成 Bucket、对象 Key 或文件路径。相同 artifactId 和完整 spec 必须重放同一
 * intent/binding；相同 artifactId 的任一不可变字段不同都必须返回冲突并保留原赢家。
 * Store 实现不得使用当前时间或随机数改变这个映射。</p>
 *
 * <p>Storage READY 只证明不可变内容已经通过 size/checksum 校验并原子发布；它不
 * 等价于 Meeting READY。实现不得写 Manifest、推进会议状态、提交用量、完成工作流
 * lease 或签发公共访问权限。公开 Artifact API 的身份/权限和短期访问上下文属于更高
 * 层应用边界。</p>
 */
public interface ArtifactStore {
    /** 创建或幂等读取一个稳定写入意图。 */
    PrepareResult createWriteIntent(WriteIntentSpec spec) throws ArtifactStoreException;

    /**
     * 把内容写入未发布区域并校验实际 size/checksum。
     *
     * <p>调用方保留 {@code content} 的生命周期所有权，本方法不会关闭它。实现必须在
     * 超过声明大小或部署大小上限时立即停止读取。返回成功仍只表示内容已经完整暂存，
     * 不能使 {@link #stat(StorageBinding)} 返回 READY。</p>
     */
    StageResult writeContent(WriteIntent intent, InputStream content)
            throws ArtifactStoreException;

    /**
     * 原子发布一份已经完整暂存的内容。
     *
     * <p>{@code claim}、intent 中的 expected 值和 Store 实测值必须三方完全一致。
     * 调用成功以后同一 binding 的内容不可覆盖。</p>
     */
    CompleteResult completeWrite(WriteIntent intent, ContentClaim claim)
            throws ArtifactStoreException;

    /** 查询不透明 binding 的持久状态，不打开内容流。 */
    StatResult stat(StorageBinding binding) throws ArtifactStoreException;

    /**
     * 为 READY Artifact 打开一个从位置零开始的新读取流。
     * 返回句柄由调用方关闭；PENDING、DELETING、DELETED 和不存在均关闭式失败。
     */
    ReadHandle openRead(StorageBinding binding) throws ArtifactStoreException;

    /**
     * 幂等删除 binding，并持久保留防止同一 binding 被迟到 Writer 复活的 tombstone。
     * 只有返回成功才证明本次调用观察到内容和暂存文件已经物理移除。
     */
    DeleteResult delete(StorageBinding binding) throws ArtifactStoreException;

    /** 同一逻辑 Artifact 版本的不可变写入定义。 */
    record WriteIntentSpec(
            String artifactId,
            String meetingId,
            String artifactType,
            String mediaType,
            String language,
            int revision,
            long expectedSize,
            String expectedChecksum) {
        private static final Pattern CHECKSUM =
                Pattern.compile("^sha256:[0-9a-f]{64}$");

        public WriteIntentSpec {
            requireText("artifactId", artifactId);
            requireText("meetingId", meetingId);
            requireText("artifactType", artifactType);
            requireText("mediaType", mediaType);
            if (language != null) {
                requireText("language", language);
            }
            if (revision <= 0) {
                throw new IllegalArgumentException("revision must be positive");
            }
            if (expectedSize < 0) {
                throw new IllegalArgumentException("expectedSize must not be negative");
            }
            if (expectedChecksum == null
                    || !CHECKSUM.matcher(expectedChecksum).matches()) {
                throw new IllegalArgumentException(
                        "expectedChecksum must be canonical lowercase sha256");
            }
        }

        @Override
        public String toString() {
            return "WriteIntentSpec[artifactId=redacted, meetingId=redacted"
                    + ", metadata=redacted, revision=" + revision
                    + ", expectedSize=redacted, expectedChecksumPresent=true]";
        }
    }

    /** Store 私有实现可以解释、应用层只能原样保存和回传的不透明 binding。 */
    record StorageBinding(String value) {
        public StorageBinding {
            requireText("storageBinding", value);
        }

        @Override
        public String toString() {
            return "StorageBinding[value=redacted]";
        }
    }

    /** 已持久化的写入意图；intentId 和 binding 都不能被调用方自行拼接。 */
    record WriteIntent(String intentId, StorageBinding binding, WriteIntentSpec spec) {
        public WriteIntent {
            requireText("intentId", intentId);
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(spec, "spec");
        }

        @Override
        public String toString() {
            return "WriteIntent[intentId=redacted, binding=redacted, spec=redacted]";
        }
    }

    /** 内容长度和摘要的不可变声明。 */
    record ContentClaim(long size, String checksum) {
        private static final Pattern CHECKSUM =
                Pattern.compile("^sha256:[0-9a-f]{64}$");

        public ContentClaim {
            if (size < 0) {
                throw new IllegalArgumentException("size must not be negative");
            }
            if (checksum == null || !CHECKSUM.matcher(checksum).matches()) {
                throw new IllegalArgumentException(
                        "checksum must be canonical lowercase sha256");
            }
        }

        @Override
        public String toString() {
            return "ContentClaim[size=redacted, checksumPresent=true]";
        }
    }

    /** 已经完成原子发布、可供后续元数据和 Manifest 使用的内容描述。 */
    record ArtifactDescriptor(WriteIntent intent, ContentClaim content) {
        public ArtifactDescriptor {
            Objects.requireNonNull(intent, "intent");
            Objects.requireNonNull(content, "content");
            if (content.size() != intent.spec().expectedSize()
                    || !content.checksum().equals(intent.spec().expectedChecksum())) {
                throw new IllegalArgumentException(
                        "ready content must match write intent expectation");
            }
        }

        @Override
        public String toString() {
            return "ArtifactDescriptor[intent=redacted, content=redacted]";
        }
    }

    enum PrepareStatus {
        APPLIED,
        REPLAYED,
        CONFLICT
    }

    /** create intent 的裁决；冲突分支也返回原赢家，绝不返回候选。 */
    record PrepareResult(PrepareStatus status, WriteIntent intent) {
        public PrepareResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(intent, "intent");
        }

        @Override
        public String toString() {
            return "PrepareResult[status=" + status + ", intent=redacted]";
        }
    }

    enum StageStatus {
        APPLIED,
        REPLAYED
    }

    /** 完整内容已经暂存并通过校验，但尚未 READY。 */
    record StageResult(StageStatus status, WriteIntent intent, ContentClaim content) {
        public StageResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(intent, "intent");
            Objects.requireNonNull(content, "content");
            if (content.size() != intent.spec().expectedSize()
                    || !content.checksum().equals(intent.spec().expectedChecksum())) {
                throw new IllegalArgumentException(
                        "staged content must match write intent expectation");
            }
        }

        @Override
        public String toString() {
            return "StageResult[status=" + status
                    + ", intent=redacted, content=redacted]";
        }
    }

    enum CompleteStatus {
        APPLIED,
        REPLAYED
    }

    /** 原子发布裁决。 */
    record CompleteResult(CompleteStatus status, ArtifactDescriptor artifact) {
        public CompleteResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(artifact, "artifact");
        }

        @Override
        public String toString() {
            return "CompleteResult[status=" + status + ", artifact=redacted]";
        }
    }

    /** Store 自身的内容状态；READY 不代表会议业务终态。 */
    enum StoreState {
        NOT_FOUND,
        PENDING,
        READY,
        DELETING,
        DELETED
    }

    /** READY 必须携带 descriptor，其它状态禁止携带虚构内容描述。 */
    record StatResult(StoreState state, ArtifactDescriptor artifact) {
        public StatResult {
            Objects.requireNonNull(state, "state");
            if ((state == StoreState.READY) != (artifact != null)) {
                throw new IllegalArgumentException(
                        "only READY stat may carry artifact descriptor");
            }
        }

        public static StatResult withoutArtifact(StoreState state) {
            if (state == StoreState.READY) {
                throw new IllegalArgumentException("READY requires artifact descriptor");
            }
            return new StatResult(state, null);
        }

        public Optional<ArtifactDescriptor> optionalArtifact() {
            return Optional.ofNullable(artifact);
        }

        @Override
        public String toString() {
            return "StatResult[state=" + state
                    + ", artifactPresent=" + (artifact != null) + ']';
        }
    }

    /** READY Artifact 的一次读取句柄；诊断文本不输出流、binding 或内容。 */
    record ReadHandle(ArtifactDescriptor artifact, InputStream content)
            implements AutoCloseable {
        public ReadHandle {
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(content, "content");
        }

        @Override
        public void close() throws java.io.IOException {
            content.close();
        }

        @Override
        public String toString() {
            return "ReadHandle[artifact=redacted, content=redacted]";
        }
    }

    enum DeleteStatus {
        DELETED,
        ALREADY_DELETED
    }

    /** 删除成功结果；binding 只用于继续幂等调用，诊断文本始终脱敏。 */
    record DeleteResult(DeleteStatus status, StorageBinding binding) {
        public DeleteResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(binding, "binding");
        }

        @Override
        public String toString() {
            return "DeleteResult[status=" + status + ", binding=redacted]";
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > 4096) {
            throw new IllegalArgumentException(name + " must not exceed 4096 UTF-8 bytes");
        }
    }
}
