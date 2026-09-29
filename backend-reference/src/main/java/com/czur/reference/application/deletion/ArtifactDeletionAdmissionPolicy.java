package com.czur.reference.application.deletion;

import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.domain.meeting.MeetingState;

import java.util.Objects;

/**
 * 删除受理在进入 JDBC 事务以前使用的纯 Java 状态裁决契约。
 *
 * <p>这个策略只比较已经从同一个事实快照读出的枚举和完整性标记，不读取时钟、
 * 数据库或 Artifact Store，也不创建计划。它把“可构造删除计划”的四条路径和
 * 关闭式拒绝原因固定下来，避免 JDBC 编排在 READY、失败收口和早期取消之间猜测。
 * 目标集合的实际构造仍由事务编排从最终收口 checkpoint 完整恢复。</p>
 */
public final class ArtifactDeletionAdmissionPolicy {
    private ArtifactDeletionAdmissionPolicy() {
    }

    /** 删除计划允许的来源分类。 */
    public enum Kind {
        READY,
        READY_WITH_WARNINGS,
        FINAL_FAILED,
        EARLY_EMPTY
    }

    /** 关闭式拒绝原因；不携带会议、Artifact 或数据库正文。 */
    public enum Rejection {
        INVALID_MEETING_STATE,
        GENERATING,
        MISSING_MANIFEST,
        HALF_WORKFLOW,
        FACT_DRIFT
    }

    /** 纯事实输入；字段由事务编排在锁内取得。 */
    public record Facts(
            MeetingState meetingState,
            ManifestStatus manifestStatus,
            boolean manifestPresent,
            boolean workflowPresent,
            WorkflowStep.Status workflowStatus,
            boolean inputAccepted,
            boolean durableInputPresent,
            boolean finalUsagePresent,
            int checkpointArtifactCount,
            int readyMetadataCount,
            boolean metadataExact,
            boolean metadataSubset) {
        public Facts {
            Objects.requireNonNull(meetingState, "meetingState");
            Objects.requireNonNull(manifestStatus, "manifestStatus");
            if (!workflowPresent && workflowStatus != null
                    || workflowPresent && workflowStatus == null) {
                throw new IllegalArgumentException("workflow presence/status mismatch");
            }
            if (checkpointArtifactCount < 0 || readyMetadataCount < 0) {
                throw new IllegalArgumentException("artifact counts must not be negative");
            }
        }
    }

    /** 策略结果；拒绝不包含动态诊断正文。 */
    public sealed interface Result permits Accepted, Rejected {
    }

    public record Accepted(Kind kind) implements Result {
        public Accepted {
            Objects.requireNonNull(kind, "kind");
        }
    }

    public record Rejected(Rejection reason) implements Result {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** checkpoint 目标与已落库 READY metadata 的集合比较结果。 */
    public record MetadataCheck(boolean exact, boolean subset) {
    }

    /**
     * 按冻结顺序裁决删除来源。
     *
     * <p>早期空计划只允许明确的 CANCELLED/FAILED + NOT_STARTED，且没有任何输入、
     * 用量、Workflow 或 READY metadata 事实。GENERATING 永远拒绝；READY 及失败收口
     * 必须带完整 Manifest、输入事实、最终用量和正确终态 Workflow。</p>
     */
    public static Result evaluate(Facts facts) {
        Objects.requireNonNull(facts, "facts");
        if (!isInitialMeetingState(facts.meetingState())) {
            return new Rejected(Rejection.INVALID_MEETING_STATE);
        }
        if (facts.manifestStatus() == ManifestStatus.GENERATING) {
            return new Rejected(Rejection.GENERATING);
        }

        boolean earlyState = facts.meetingState() == MeetingState.CANCELLED
                || facts.meetingState() == MeetingState.FAILED;
        boolean noFinalizationFacts = !facts.workflowPresent()
                && !facts.inputAccepted()
                && !facts.durableInputPresent()
                && !facts.finalUsagePresent()
                && facts.readyMetadataCount() == 0;
        if (earlyState && facts.manifestStatus() == ManifestStatus.NOT_STARTED
                && !facts.manifestPresent() && noFinalizationFacts) {
            return new Accepted(Kind.EARLY_EMPTY);
        }

        if (!facts.manifestPresent()) {
            return new Rejected(Rejection.MISSING_MANIFEST);
        }
        if (!facts.workflowPresent() || facts.workflowStatus() == null) {
            return new Rejected(Rejection.HALF_WORKFLOW);
        }
        if (!facts.inputAccepted() || !facts.durableInputPresent()
                || !facts.finalUsagePresent()) {
            return new Rejected(Rejection.FACT_DRIFT);
        }

        if (facts.meetingState() == MeetingState.READY
                && facts.manifestStatus() == ManifestStatus.READY
                && facts.workflowStatus() == WorkflowStep.Status.COMPLETED) {
            return facts.readyMetadataCount() == facts.checkpointArtifactCount()
                    && facts.metadataExact()
                    ? new Accepted(Kind.READY)
                    : new Rejected(Rejection.FACT_DRIFT);
        }
        if (facts.meetingState() == MeetingState.READY_WITH_WARNINGS
                && facts.manifestStatus() == ManifestStatus.READY_WITH_WARNINGS
                && facts.workflowStatus() == WorkflowStep.Status.COMPLETED) {
            return facts.readyMetadataCount() == facts.checkpointArtifactCount()
                    && facts.metadataExact()
                    ? new Accepted(Kind.READY_WITH_WARNINGS)
                    : new Rejected(Rejection.FACT_DRIFT);
        }
        if (facts.meetingState() == MeetingState.FAILED
                && facts.manifestStatus() == ManifestStatus.FAILED
                && facts.workflowStatus() == WorkflowStep.Status.DEAD) {
            return facts.readyMetadataCount() <= facts.checkpointArtifactCount()
                    && facts.metadataSubset()
                    ? new Accepted(Kind.FINAL_FAILED)
                    : new Rejected(Rejection.FACT_DRIFT);
        }
        return new Rejected(Rejection.HALF_WORKFLOW);
    }

    private static boolean isInitialMeetingState(MeetingState state) {
        return state == MeetingState.READY
                || state == MeetingState.READY_WITH_WARNINGS
                || state == MeetingState.FAILED
                || state == MeetingState.CANCELLED;
    }
}
