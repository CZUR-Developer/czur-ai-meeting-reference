package com.czur.reference.application.port.artifact;

import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 已由外部 {@link ArtifactStore} 原子发布为 READY 的产物元数据持久化端口。
 *
 * <p>调用方只能把 {@link ArtifactStore#completeWrite} 成功返回的
 * {@link ArtifactDescriptor} 交给本端口。端口把 descriptor 视为可信编排层提供的
 * READY attestation；它不访问文件系统或网络，也不重新调用 {@code stat/openRead}。
 * 数据库存在元数据只表示对应内容已被调用方确认 READY，不等价于 Meeting READY。</p>
 *
 * <p>幂等身份同时受 artifactId、{@code (meetingId, artifactType, revision)}、
 * intentId 和 storage binding 约束。一个冲突候选可能让不同身份轴命中多条历史行，
 * 因而 {@link PersistStatus#CONFLICT} 不承诺存在单一赢家对象。</p>
 */
public interface ArtifactReadyMetadataStore {
    /**
     * 按逻辑 artifactId 读取已经持久化并通过结构校验的 READY metadata。
     *
     * @param artifactId 逻辑产物标识
     * @return 已保存 metadata；不存在时为空
     */
    Optional<ReadyMetadata> findByArtifactId(String artifactId);

    /**
     * 按 artifactId 字典序返回同一会议已经记录的全部 READY metadata。
     *
     * <p>Completion 使用该全集同时拒绝 checkpoint 外额外行、遗漏 descriptor
     * 已经存在的半状态，以及不同请求遗留的身份漂移。实现不得只按调用方给出的
     * artifactId 逐项读取后忽略同会议其它行。</p>
     */
    List<ReadyMetadata> findByMeetingId(String meetingId);

    /**
     * 持久化一份 READY descriptor，或按四个唯一身份轴返回稳定裁决。
     *
     * <p>{@link ReadyMetadata#recordedAt()} 由数据库在第一次写入时生成，不由调用方
     * 提供、不参与 descriptor 的重放等价性，并且在冲突或重放时不得更新。</p>
     *
     * @param candidate 外部 Artifact Store 已确认 READY 的不可变 descriptor
     * @return 首次写入、等价重放或身份冲突
     */
    PersistResult persistReady(ArtifactDescriptor candidate);

    /** READY metadata 写入裁决。 */
    enum PersistStatus {
        /** descriptor 首次写入数据库。 */
        APPLIED,
        /** 四个身份轴命中同一行，且完整 descriptor 相同。 */
        REPLAYED,
        /** 任一身份轴发生漂移，或多个身份轴分别命中不同历史行。 */
        CONFLICT
    }

    /**
     * 数据库中已经确认 READY 的 descriptor 及首次记录时间。
     *
     * <p>PostgreSQL {@code TIMESTAMPTZ} 只能无损表示到微秒；构造时拒绝更高精度，
     * 防止读回值在不同 JVM/驱动中产生不稳定比较。诊断文本不输出 descriptor 或
     * 时间值。</p>
     */
    record ReadyMetadata(ArtifactDescriptor artifact, Instant recordedAt) {
        public ReadyMetadata {
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(recordedAt, "recordedAt");
            if (recordedAt.getNano() % 1_000 != 0) {
                throw new IllegalArgumentException(
                        "recordedAt must have at most microsecond precision");
            }
        }

        @Override
        public String toString() {
            return "ArtifactReadyMetadataStore.ReadyMetadata[artifact=redacted"
                    + ", recordedAt=redacted]";
        }
    }

    /**
     * READY metadata 持久化结果。
     *
     * <p>APPLIED/REPLAYED 必须携带已读回的数据库 metadata；CONFLICT 禁止携带
     * 任意一行，避免在多轴分别命中不同历史行时伪造“赢家”。</p>
     */
    record PersistResult(PersistStatus status, ReadyMetadata metadata) {
        public PersistResult {
            Objects.requireNonNull(status, "status");
            if ((status == PersistStatus.CONFLICT) == (metadata != null)) {
                throw new IllegalArgumentException(
                        "only applied or replayed results may carry metadata");
            }
        }

        /** 返回已确认的数据库 metadata；冲突时为空。 */
        public Optional<ReadyMetadata> optionalMetadata() {
            return Optional.ofNullable(metadata);
        }

        /** 构造不携带虚构赢家的冲突结果。 */
        public static PersistResult conflict() {
            return new PersistResult(PersistStatus.CONFLICT, null);
        }

        @Override
        public String toString() {
            return "ArtifactReadyMetadataStore.PersistResult[status=" + status
                    + ", metadataPresent=" + (metadata != null) + ']';
        }
    }
}
