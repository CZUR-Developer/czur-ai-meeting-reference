package com.czur.reference.application.deletion;

import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 删除受理时冻结、可跨进程恢复的 Artifact 删除计划。
 *
 * <p>计划只表达“上层已经裁决后应删除哪些 Artifact”，不自行读取 Meeting、Manifest、
 * 最终收口 Workflow 或存储状态。零目标是合法且严格的空计划，只表示上层已经确认本次
 * 没有 Artifact 需要物理删除；本对象不会猜测会议是否为早期取消、失败或其它业务状态。</p>
 *
 * <p>目标按 artifact type、revision、artifactId 排序。相同 artifactId 或相同
 * type+revision 会关闭式拒绝，避免同一个逻辑产物被重复执行或用不同身份旁路。可空的
 * 来源 Manifest revision 和最终收口 Workflow 身份用于后续事务交叉核对；它们不代表
 * 当前运行状态，也不包含可变 lease、attempt、error 或 checkpoint 正文。</p>
 */
public final class ArtifactDeletionPlan {
    /** 删除 checkpoint 及其 canonical JSON 的版本。 */
    public static final String CHECKPOINT_TYPE = "artifact-deletion-plan/v1";
    /** Workflow 调度注册表中的稳定步骤类型。 */
    public static final String STEP_TYPE = "artifact-deletion";
    /** 防止恶意计划在进入持久层前制造无界集合。 */
    public static final int MAX_TARGETS = 256;

    private static final String INVALID_PLAN = "Artifact deletion plan is invalid";
    private static final String STEP_KEY_PREFIX = "artifact_delete_v1_";
    private static final String STEP_KEY_DOMAIN = "artifact-deletion-step-key/v1";
    private static final Pattern SHA256 = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private final String meetingId;
    private final Instant acceptedAt;
    private final Integer sourceManifestRevision;
    private final SourceFinalizationWorkflow sourceFinalizationWorkflow;
    private final List<ArtifactDeletionTarget> targets;

    /**
     * 构造完整删除 Definition。
     *
     * @param meetingId 被删除会议的稳定身份
     * @param acceptedAt 删除受理时冻结的 PostgreSQL 微秒精度时间
     * @param sourceManifestRevision 受理时观察到的最新 Manifest revision；从未发布则为空
     * @param sourceFinalizationWorkflow 最终收口 Workflow 的不可变身份；严格早期终态可为空
     * @param targets 完整写入 spec 组成的删除目标；允许严格空集合
     */
    public ArtifactDeletionPlan(
            String meetingId,
            Instant acceptedAt,
            Integer sourceManifestRevision,
            SourceFinalizationWorkflow sourceFinalizationWorkflow,
            List<ArtifactDeletionTarget> targets) {
        try {
            MeetingIdPolicy.requireValid(meetingId);
            requirePersistentInstant(acceptedAt);
            if (sourceManifestRevision != null && sourceManifestRevision <= 0) {
                throw invalid();
            }
            if (sourceFinalizationWorkflow != null) {
                sourceFinalizationWorkflow.requireForMeeting(meetingId);
            }
            Objects.requireNonNull(targets, "targets");
            if (targets.size() > MAX_TARGETS) {
                throw invalid();
            }

            ArrayList<ArtifactDeletionTarget> sorted = new ArrayList<>(targets);
            sorted.sort(Comparator
                    .comparing((ArtifactDeletionTarget target) ->
                            target.spec().artifactType())
                    .thenComparingInt(target -> target.spec().revision())
                    .thenComparing(ArtifactDeletionTarget::artifactId));
            Set<String> artifactIds = new HashSet<>();
            Set<TypeRevision> logicalVersions = new HashSet<>();
            for (ArtifactDeletionTarget target : sorted) {
                Objects.requireNonNull(target, "target");
                if (!meetingId.equals(target.spec().meetingId())
                        || !artifactIds.add(target.artifactId())
                        || !logicalVersions.add(new TypeRevision(
                                target.spec().artifactType(), target.spec().revision()))) {
                    throw invalid();
                }
            }

            this.meetingId = meetingId;
            this.acceptedAt = acceptedAt;
            this.sourceManifestRevision = sourceManifestRevision;
            this.sourceFinalizationWorkflow = sourceFinalizationWorkflow;
            this.targets = List.copyOf(sorted);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /** 只由会议身份定位删除 Workflow，不依赖时钟、Manifest 或 Artifact 内容。 */
    public static WorkflowStep.Key workflowKey(String meetingId) {
        try {
            MeetingIdPolicy.requireValid(meetingId);
            return new WorkflowStep.Key(
                    meetingId,
                    STEP_TYPE,
                    STEP_KEY_PREFIX + digest(STEP_KEY_DOMAIN, meetingId));
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    public WorkflowStep.Key workflowKey() {
        return workflowKey(meetingId);
    }

    public String meetingId() {
        return meetingId;
    }

    public Instant acceptedAt() {
        return acceptedAt;
    }

    public Integer sourceManifestRevision() {
        return sourceManifestRevision;
    }

    public SourceFinalizationWorkflow sourceFinalizationWorkflow() {
        return sourceFinalizationWorkflow;
    }

    public List<ArtifactDeletionTarget> targets() {
        return targets;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ArtifactDeletionPlan that)) {
            return false;
        }
        return meetingId.equals(that.meetingId)
                && acceptedAt.equals(that.acceptedAt)
                && Objects.equals(sourceManifestRevision, that.sourceManifestRevision)
                && Objects.equals(sourceFinalizationWorkflow, that.sourceFinalizationWorkflow)
                && targets.equals(that.targets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                meetingId, acceptedAt, sourceManifestRevision,
                sourceFinalizationWorkflow, targets);
    }

    @Override
    public String toString() {
        return "ArtifactDeletionPlan[meetingId=redacted, acceptedAt=redacted"
                + ", sourceManifestRevisionPresent=" + (sourceManifestRevision != null)
                + ", sourceFinalizationWorkflow=redacted, targetCount=" + targets.size() + ']';
    }

    /**
     * 最终收口 Workflow Definition 中可用于跨事务核对的不可变身份。
     *
     * <p>key、checkpointType 和 checkpoint digest 足以定位并核对原始 Definition；
     * lease、attempt、状态和错误字段会随执行变化，禁止进入来源身份。</p>
     */
    public record SourceFinalizationWorkflow(
            WorkflowStep.Key key,
            String checkpointType,
            String contentDigest) {
        public SourceFinalizationWorkflow {
            try {
                Objects.requireNonNull(key, "key");
                requireStrictText(checkpointType);
                requireDigest(contentDigest);
            } catch (RuntimeException failure) {
                throw invalid();
            }
        }

        /** 从任意状态的最终收口 Workflow 提取不可变 Definition 身份。 */
        public static SourceFinalizationWorkflow fromStep(WorkflowStep step) {
            Objects.requireNonNull(step, "step");
            return new SourceFinalizationWorkflow(
                    step.key(), step.checkpointType(), step.contentDigest());
        }

        private void requireForMeeting(String meetingId) {
            if (!FinalizationCheckpoint.workflowKey(meetingId).equals(key)
                    || !FinalizationCheckpoint.CHECKPOINT_TYPE.equals(checkpointType)) {
                throw invalid();
            }
        }

        @Override
        public String toString() {
            return "SourceFinalizationWorkflow[key=redacted, checkpointType=redacted"
                    + ", contentDigestPresent=true]";
        }
    }

    static void requirePersistentInstant(Instant value) {
        Objects.requireNonNull(value, "instant");
        if (value.getNano() % 1_000 != 0
                || value.isBefore(Instant.parse("0000-01-01T00:00:00Z"))
                || value.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) {
            throw invalid();
        }
    }

    private static void requireStrictText(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        MeetingIdPolicy.utf8Length(value);
    }

    private static void requireDigest(String value) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw invalid();
        }
    }

    private static String digest(String domain, String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, domain);
            for (String field : fields) {
                update(digest, field);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256");
        }
    }

    private static void update(MessageDigest digest, String value) {
        requireStrictText(value);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID_PLAN);
    }

    private record TypeRevision(String artifactType, int revision) {
    }
}
