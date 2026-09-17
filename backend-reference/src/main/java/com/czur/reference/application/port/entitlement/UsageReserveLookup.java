package com.czur.reference.application.port.entitlement;

import com.czur.reference.domain.meeting.MeetingOwnership;

import java.util.Objects;

/**
 * 只读查询首次 {@link UsageOperationScopes#RESERVE} 稳定结果的应用端口。
 *
 * <p>操作 scope 由实现固定为 {@code usage.reserve.v1}，调用方只能提供部署、主体、
 * reserve 幂等键和摘要。查询只读取 {@code usage_operation} 中的首次结果，不读取
 * 当前 {@code usage_reservation} 后再改写首次快照，因此会议后续 bind、commit 或
 * cancel 不会改变本端口返回的历史事实。</p>
 *
 * <p>只有包含授权 Profile 快照、初始未绑定 Reservation 以及现有 owned-create
 * 能力与额度不变量的 v2 reserve 结果可以重放。历史 v1 仍是可识别事实，但不足以
 * 驱动归属会议创建，必须返回 {@link Status#INCONSISTENT}。</p>
 */
public interface UsageReserveLookup {
    /** 执行一次只读 reserve 结果查询。 */
    Result lookup(Request request);

    /** reserve 查询键；操作 scope 固定，不作为调用方字段暴露。 */
    record Request(
            String deploymentScope,
            String subjectScope,
            String reserveIdempotencyKey,
            String reserveRequestDigest) {
        public Request {
            requireText("deploymentScope", deploymentScope);
            MeetingOwnership.requireOwnedSubjectScope(subjectScope);
            requireText("reserveIdempotencyKey", reserveIdempotencyKey);
            requireText("reserveRequestDigest", reserveRequestDigest);
        }

        @Override
        public String toString() {
            return "UsageReserveLookup.Request[redacted]";
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

    /** 首次 reserve 事实不能安全用于 owned-create 的固定原因。 */
    enum InconsistencyReason {
        STORED_RESULT_INVALID,
        RESERVATION_REFERENCE_MISMATCH,
        OWNED_RESERVE_INVARIANT_VIOLATION
    }

    /** 只读存储不可用的固定原因。 */
    enum UnavailableReason {
        TRANSIENT_STORAGE_FAILURE,
        STORAGE_FAILURE
    }

    /** 稳定查询结果联合类型。 */
    sealed interface Result permits NotFound, Replayed, Conflict, Inconsistent, Unavailable {
        Status status();
    }

    /** 完整部署、主体、RESERVE scope 与幂等键下不存在首次结果。 */
    record NotFound() implements Result {
        @Override
        public Status status() {
            return Status.NOT_FOUND;
        }

        @Override
        public String toString() {
            return "UsageReserveLookup.NotFound";
        }
    }

    /** 同摘要且通过完整 v2 owned reserve 校验的首次结果。 */
    record Replayed(UsageOperationResult result) implements Result {
        public Replayed {
            Objects.requireNonNull(result, "result");
        }

        @Override
        public Status status() {
            return Status.REPLAYED;
        }

        @Override
        public String toString() {
            return "UsageReserveLookup.Replayed[result=redacted]";
        }
    }

    /** 已有首次 reserve 结果，但请求摘要不同；正文不会被解析。 */
    record Conflict() implements Result {
        @Override
        public Status status() {
            return Status.CONFLICT;
        }

        @Override
        public String toString() {
            return "UsageReserveLookup.Conflict";
        }
    }

    /** 同摘要的正文、Reservation 引用或 owned reserve 业务不变量不可信。 */
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
            return "UsageReserveLookup.Inconsistent[reason=" + reason + ']';
        }
    }

    /** 数据库读取失败；不携带 SQL、连接串或驱动异常正文。 */
    record Unavailable(UnavailableReason reason, boolean retryable) implements Result {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            if ((reason == UnavailableReason.TRANSIENT_STORAGE_FAILURE) != retryable) {
                throw new IllegalArgumentException(
                        "retryable must match usage reserve unavailable reason");
            }
        }

        @Override
        public Status status() {
            return Status.UNAVAILABLE;
        }

        @Override
        public String toString() {
            return "UsageReserveLookup.Unavailable[reason=" + reason
                    + ", retryable=" + retryable + ']';
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
