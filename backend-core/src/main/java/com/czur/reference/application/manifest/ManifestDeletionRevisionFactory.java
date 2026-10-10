package com.czur.reference.application.manifest;

import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;

import java.util.Objects;

/**
 * 从最后一版完整结果构造删除待处理 revision 的纯应用层规则。
 *
 * <p>删除受理不能创建缺少语言、时间或产物的空壳 Manifest。只有已经存在
 * {@code READY}、{@code READY_WITH_WARNINGS} 或 {@code FAILED} 完整 revision
 * 时，调用方才可以使用本工厂生成下一版 {@code DELETION_PENDING} 事实。新
 * revision 除版本号和状态外逐字段保留原值，使后续删除 Worker 可以使用冻结的
 * Artifact 集合执行清理，也避免 JDBC 适配器重新解释公开协议正文。</p>
 *
 * <p>本类不读取数据库、不追加 revision，也不推进 Meeting。调用方必须把源
 * revision 的读取、新 revision 的追加、Meeting 状态推进、幂等事实和 Outbox
 * 放进同一个受 meeting 行锁保护的事务。</p>
 */
public final class ManifestDeletionRevisionFactory {
    private ManifestDeletionRevisionFactory() {
    }

    /**
     * 生成紧随完整源 revision 的删除待处理版本。
     *
     * @param latest 已经通过存储边界完整校验的当前最新 Manifest
     * @return revision 加一、状态为 {@code DELETION_PENDING} 且其余字段不变的新值
     * @throws IllegalArgumentException 源状态不是完整结果，或 revision 已无法递增
     */
    public static MeetingManifest deletionPendingAfter(MeetingManifest latest) {
        Objects.requireNonNull(latest, "latest");
        if (latest.status() != ManifestStatus.READY
                && latest.status() != ManifestStatus.READY_WITH_WARNINGS
                && latest.status() != ManifestStatus.FAILED) {
            throw new IllegalArgumentException(
                    "deletion revision requires a complete source manifest");
        }
        if (latest.manifestRevision() == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("manifest revision cannot be incremented");
        }
        return new MeetingManifest(
                latest.specVersion(),
                latest.meetingId(),
                latest.manifestRevision() + 1,
                latest.profile(),
                latest.title(),
                ManifestStatus.DELETION_PENDING,
                latest.startedAt(),
                latest.endedAt(),
                latest.durationMs(),
                latest.languages(),
                latest.artifacts(),
                latest.warnings());
    }

    /**
     * 从删除待处理 revision 构造唯一的 DELETED tombstone revision。
     *
     * <p>Manifest 正文仍保留原始完整字段；状态和 revision 是唯一变化。调用方
     * 必须在同一 Meeting 锁事务中追加该值，并将已存在的 DELETED revision 作为
     * 幂等重放处理，不能再次生成新版本。</p>
     */
    public static MeetingManifest deletedAfter(MeetingManifest latest) {
        Objects.requireNonNull(latest, "latest");
        if (latest.status() != ManifestStatus.DELETION_PENDING) {
            throw new IllegalArgumentException(
                    "deleted revision requires a deletion-pending source manifest");
        }
        if (latest.manifestRevision() == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("manifest revision cannot be incremented");
        }
        return new MeetingManifest(
                latest.specVersion(),
                latest.meetingId(),
                latest.manifestRevision() + 1,
                latest.profile(),
                latest.title(),
                ManifestStatus.DELETED,
                latest.startedAt(),
                latest.endedAt(),
                latest.durationMs(),
                latest.languages(),
                latest.artifacts(),
                latest.warnings());
    }
}
