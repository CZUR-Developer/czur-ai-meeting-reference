package com.czur.reference.application.port;

import java.time.Instant;
import java.util.Objects;

/**
 * 可跨进程恢复的工作流步骤事实。
 *
 * <p>步骤由 {@link Key} 稳定定位。{@code checkpointType} 描述 checkpoint 的关闭式
 * 业务种类，不能用 {@link Key#stepType()} 代替；前者属于不可覆盖的输入 Definition，
 * 后者属于调度路由。{@code canonicalPayload} 是 Worker 恢复时使用的完整规范输入，
 * {@code contentDigest} 是该输入的稳定摘要。checkpointType、payload、digest 与首次
 * 调度时间共同组成不可覆盖的 Definition。</p>
 *
 * <p>该值对象不读取时钟、不生成 token，也不执行外部操作。所有时间均由调用方或
 * Store 操作请求显式提供。诊断输出隐藏会议标识、checkpoint 正文和摘要正文。</p>
 *
 * @param key 会议、步骤类型和步骤业务键组成的稳定标识
 * @param checkpointType checkpoint 的业务种类，不可为空白且独立于 Key.stepType
 * @param canonicalPayload 完整规范 checkpoint，不可为空白
 * @param contentDigest checkpoint 的稳定摘要，不可为空白
 * @param status 当前持久状态
 * @param attempt 已确认失败次数，从零开始
 * @param nextAttemptAt 仅 PENDING 状态必有的最早领取时间；其它状态必须为空
 * @param leaseOwner LEASED 时的当前持有者，其它状态必须为空
 * @param leaseUntil LEASED 时的到期时间，其它状态必须为空
 * @param leaseVersion 每次 claim 或 renew 后单调加一，初始为零
 * @param errorCode 最近失败的稳定错误码；首次/完成状态可为空
 * @param errorSummary 最近失败的脱敏摘要；首次/完成状态可为空
 * @param updatedAt 当前事实最后一次成功变化的调用方时间
 */
public record WorkflowStep(
        Key key,
        String checkpointType,
        String canonicalPayload,
        String contentDigest,
        Status status,
        int attempt,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseUntil,
        long leaseVersion,
        String errorCode,
        String errorSummary,
        Instant updatedAt) {

    public WorkflowStep {
        Objects.requireNonNull(key, "key");
        requireText("checkpointType", checkpointType);
        requireText("canonicalPayload", canonicalPayload);
        requireText("contentDigest", contentDigest);
        Objects.requireNonNull(status, "status");
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative");
        }
        if (status == Status.PENDING) {
            Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        } else if (nextAttemptAt != null) {
            throw new IllegalArgumentException("only PENDING step may contain nextAttemptAt");
        }
        if (leaseVersion < 0) {
            throw new IllegalArgumentException("leaseVersion must not be negative");
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
        boolean leased = status == Status.LEASED;
        if (leased) {
            requireText("leaseOwner", leaseOwner);
            Objects.requireNonNull(leaseUntil, "leaseUntil");
            if (leaseVersion <= 0) {
                throw new IllegalArgumentException("LEASED step requires positive leaseVersion");
            }
            if (!leaseUntil.isAfter(updatedAt)) {
                throw new IllegalArgumentException("leaseUntil must be after updatedAt");
            }
        } else if (leaseOwner != null || leaseUntil != null) {
            throw new IllegalArgumentException("only LEASED step may contain lease fields");
        }
        requireOptionalPair(errorCode, errorSummary);
        if (status == Status.COMPLETED && errorCode != null) {
            throw new IllegalArgumentException("COMPLETED step must not contain error fields");
        }
        if (status == Status.DEAD && errorCode == null) {
            throw new IllegalArgumentException("DEAD step requires error fields");
        }
    }

    /** 创建尚未领取的初始 checkpoint。 */
    public static WorkflowStep pending(
            Key key,
            String checkpointType,
            String canonicalPayload,
            String contentDigest,
            Instant nextAttemptAt,
            Instant registeredAt) {
        return new WorkflowStep(
                key, checkpointType, canonicalPayload, contentDigest, Status.PENDING, 0,
                nextAttemptAt, null, null, 0, null, null, registeredAt);
    }

    /** 状态名称与后续持久化约束保持一致。 */
    public enum Status {
        PENDING,
        LEASED,
        COMPLETED,
        DEAD
    }

    /**
     * 工作流步骤稳定键。自然顺序用于领取候选的确定性次级排序。
     * {@link #toString()} 不输出真实会议标识。
     */
    public record Key(String meetingId, String stepType, String stepKey)
            implements Comparable<Key> {
        public Key {
            requireText("meetingId", meetingId);
            requireText("stepType", stepType);
            requireText("stepKey", stepKey);
        }

        @Override
        public int compareTo(Key other) {
            Objects.requireNonNull(other, "other");
            int byMeeting = meetingId.compareTo(other.meetingId);
            if (byMeeting != 0) {
                return byMeeting;
            }
            int byType = stepType.compareTo(other.stepType);
            return byType != 0 ? byType : stepKey.compareTo(other.stepKey);
        }

        @Override
        public String toString() {
            return "WorkflowStep.Key[meetingId=redacted, stepType=redacted, stepKey=redacted]";
        }
    }

    @Override
    public String toString() {
        return "WorkflowStep[key=redacted, checkpointType=redacted, checkpoint=redacted, status=" + status
                + ", attempt=" + attempt
                + ", nextAttemptAt=" + nextAttemptAt
                + ", leasePresent=" + (leaseOwner != null)
                + ", leaseVersion=" + leaseVersion
                + ", errorPresent=" + (errorCode != null)
                + ", updatedAt=" + updatedAt + ']';
    }

    private static void requireOptionalPair(String code, String summary) {
        if ((code == null) != (summary == null)) {
            throw new IllegalArgumentException("errorCode and errorSummary must appear together");
        }
        if (code != null) {
            requireText("errorCode", code);
            requireText("errorSummary", summary);
        }
    }

    static String requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
