package com.czur.reference.application.deletion;

import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.util.Objects;

/**
 * 删除计划中的单个不可变 Artifact 目标。
 *
 * <p>{@code artifactId} 是删除条目的稳定业务身份，{@code spec} 是创建该 Artifact
 * 时已经冻结的完整写入定义。两者故意同时保存并要求逐字一致：后续数据库审计可以按
 * {@code artifactId} 定位条目，而存储适配器仍能使用完整 spec 解析自己的私有 binding，
 * 不需要 Worker 复制 binding 或哈希公式。</p>
 *
 * <p>本值对象不查询 Artifact 当前状态，也不会把“尚未创建”解释为已经删除。状态检查、
 * tombstone 和结果未知恢复属于后续 Artifact Store 切片。异常和诊断文本均不输出目标
 * 身份、meeting、checksum 或其它 spec 正文。</p>
 */
public record ArtifactDeletionTarget(String artifactId, WriteIntentSpec spec) {
    private static final String INVALID_TARGET = "Artifact deletion target is invalid";

    public ArtifactDeletionTarget {
        try {
            requireStrictText(artifactId);
            Objects.requireNonNull(spec, "spec");
            requireStrictText(spec.artifactId());
            MeetingIdPolicy.requireValid(spec.meetingId());
            requireStrictText(spec.artifactType());
            requireStrictText(spec.mediaType());
            if (spec.language() != null) {
                requireStrictText(spec.language());
            }
            requireStrictText(spec.expectedChecksum());
            if (!artifactId.equals(spec.artifactId())) {
                throw invalid();
            }
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /** 从完整写入定义创建一个身份自洽的删除目标。 */
    public static ArtifactDeletionTarget fromSpec(WriteIntentSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return new ArtifactDeletionTarget(spec.artifactId(), spec);
    }

    @Override
    public String toString() {
        return "ArtifactDeletionTarget[artifactId=redacted, spec=redacted]";
    }

    private static void requireStrictText(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        MeetingIdPolicy.utf8Length(value);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID_TARGET);
    }
}
