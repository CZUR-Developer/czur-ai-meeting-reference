package com.czur.reference.application.port;

import com.czur.reference.domain.meeting.MeetingOwnership;

import java.util.Objects;

/**
 * 只读查询 authenticated-create 已持久化终态的应用端口。
 *
 * <p>该端口只回答“指定部署、主体、会话、幂等键和请求摘要对应的首次终态是什么”，
 * 不创建会议、不重新调用身份或权益 Provider，也不尝试恢复、补偿或覆盖已有事实。
 * 实现必须通过 {@link IdempotencyScopeCodec#meetingCreate(String, String)} 计算内部
 * opaque scope，调用方不能提交或覆盖该 scope。</p>
 *
 * <p>摘要冲突必须在解析持久化正文之前返回 {@link Status#CONFLICT}。只有同摘要
 * 才允许严格解析成功或失败 Outcome；损坏或与请求归属不一致的事实返回
 * {@link Status#INCONSISTENT}，数据库读取故障返回 {@link Status#UNAVAILABLE}。</p>
 */
public interface MeetingCreateReplayLookup {
    /** 执行一次只读查询；返回值不会携带 JDBC 异常或数据库动态正文。 */
    Result lookup(Request request);

    /**
     * 创建终态查询的完整外部身份。
     *
     * <p>{@code requestDigest} 必须是创建命令使用的同一规范摘要；它与主体、幂等键
     * 都属于敏感材料，因此 {@link #toString()} 始终脱敏。</p>
     */
    record Request(
            String deploymentScope,
            String subjectScope,
            String clientSessionId,
            String idempotencyKey,
            String requestDigest) {
        public Request {
            requireText("deploymentScope", deploymentScope);
            MeetingOwnership.requireOwnedSubjectScope(subjectScope);
            requireText("clientSessionId", clientSessionId);
            requireText("idempotencyKey", idempotencyKey);
            requireText("requestDigest", requestDigest);
        }

        @Override
        public String toString() {
            return "MeetingCreateReplayLookup.Request[redacted]";
        }
    }

    /** 调用方必须穷举处理的五种稳定查询状态。 */
    enum Status {
        NOT_FOUND,
        REPLAYED,
        CONFLICT,
        INCONSISTENT,
        UNAVAILABLE
    }

    /** 已有创建事实不能安全重放的固定原因；不包含数据库或正文动态文本。 */
    enum InconsistencyReason {
        STORED_OUTCOME_INVALID,
        SUCCESS_NOT_OWNED,
        SUCCESS_SCOPE_MISMATCH,
        SUCCESS_SESSION_MISMATCH
    }

    /** 只读存储不可用的固定原因；是否可重试由 {@link Unavailable#retryable()} 冻结。 */
    enum UnavailableReason {
        TRANSIENT_STORAGE_FAILURE,
        STORAGE_FAILURE
    }

    /** 稳定查询结果联合类型；不得以异常代替正常的五状态分支。 */
    sealed interface Result permits NotFound, Replayed, Conflict, Inconsistent, Unavailable {
        Status status();
    }

    /** 完整内部 scope 与幂等键下没有终态。 */
    record NotFound() implements Result {
        @Override
        public Status status() {
            return Status.NOT_FOUND;
        }

        @Override
        public String toString() {
            return "MeetingCreateReplayLookup.NotFound";
        }
    }

    /** 同摘要且通过归属校验的首次稳定 Outcome。 */
    record Replayed(MeetingCreateOutcome outcome) implements Result {
        public Replayed {
            Objects.requireNonNull(outcome, "outcome");
            if (outcome instanceof MeetingCreateOutcome.Success success) {
                MeetingOwnership.requireOwnedSubjectScope(
                        success.result().meeting().subjectScope());
            }
        }

        @Override
        public Status status() {
            return Status.REPLAYED;
        }

        @Override
        public String toString() {
            return "MeetingCreateReplayLookup.Replayed[outcome=redacted]";
        }
    }

    /** 同 scope/key 已有首次终态，但请求摘要不同；既有正文不会被解析。 */
    record Conflict() implements Result {
        @Override
        public Status status() {
            return Status.CONFLICT;
        }

        @Override
        public String toString() {
            return "MeetingCreateReplayLookup.Conflict";
        }
    }

    /** 同摘要的持久事实损坏，或成功事实不属于本次请求。 */
    record Inconsistent(InconsistencyReason reason) implements Result {
        public Inconsistent {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public Status status() {
            return Status.INCONSISTENT;
        }

        @Override
        public String toString() {
            return "MeetingCreateReplayLookup.Inconsistent[reason=" + reason + ']';
        }
    }

    /** 数据库读取失败；结果只保留稳定原因和调用方能否安全重试。 */
    record Unavailable(UnavailableReason reason, boolean retryable) implements Result {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            if ((reason == UnavailableReason.TRANSIENT_STORAGE_FAILURE) != retryable) {
                throw new IllegalArgumentException(
                        "retryable must match meeting replay unavailable reason");
            }
        }

        @Override
        public Status status() {
            return Status.UNAVAILABLE;
        }

        @Override
        public String toString() {
            return "MeetingCreateReplayLookup.Unavailable[reason=" + reason
                    + ", retryable=" + retryable + ']';
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
