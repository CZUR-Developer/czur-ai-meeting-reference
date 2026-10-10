package com.czur.reference.application.port;

import java.util.Objects;

/**
 * 在创建事务结果未知后，只读核对会议创建持久事实的公共端口。
 *
 * <p>调用方必须直接传入已经完成部署、主体、会议、Reservation 和首次授权快照
 * 校验的 {@link OwnedMeetingCreationUnitOfWork.CreateRequest}。本端口不重新定义
 * 一份较弱的恢复请求，也不接收原始凭据或允许调用方覆盖内部幂等 scope。</p>
 *
 * <p>实现必须联合观察首次创建 Outcome、Meeting 和请求对应的 Reservation，且
 * 全程只读：不得取消 Reservation、补写 Outcome、修复 Meeting，或调用身份、
 * 权益和其它外部 Provider。返回 {@link NotFound} 需要同时证明 Outcome 与 Meeting
 * 均不存在，并且当前 Reservation 仍是请求对应的初始未绑定事实。仅仅没有查到
 * Outcome 不足以返回 {@code NOT_FOUND}。</p>
 *
 * <p>{@link Replayed} 成功分支必须携带数据库中原始持久化的 {@code CREATED}
 * Outcome；即使当前 Meeting 已经进入运行、停止或其它后续状态，也禁止从当前
 * Meeting 重建创建响应。Outcome 缺失但 Meeting 或已绑定 Reservation 存在，
 * 以及失败 Outcome 与 Meeting 或已绑定 Reservation 并存，均属于
 * {@link Inconsistent}。这些矛盾必须暴露给后续人工或专用修复流程，不能通过
 * cancel 或补写推断结果掩盖。</p>
 *
 * <p>创建成功 Outcome 的操作、状态和创建时间不变量唯一由
 * {@link MeetingCreateOutcome.Success} 维护；本端口只组合既有 Outcome，不复制
 * 这些规则。</p>
 */
public interface MeetingRecoveryLookup {
    /**
     * 只读核对一次 owned 会议创建请求的持久事实。
     *
     * @param request 已通过 {@code CreateRequest} 构造契约校验的原始创建输入
     * @return 五状态之一；结果不包含数据库异常正文或任何敏感请求字段
     */
    Result lookup(OwnedMeetingCreationUnitOfWork.CreateRequest request);

    /** 调用方必须穷举处理的五种稳定恢复查询状态。 */
    enum Status {
        NOT_FOUND,
        REPLAYED,
        CONFLICT,
        INCONSISTENT,
        UNAVAILABLE
    }

    /** 多份持久事实不能安全解释为稳定创建结果的固定原因。 */
    enum InconsistencyReason {
        STORED_OUTCOME_INVALID,
        OUTCOME_MISSING_WITH_MEETING,
        OUTCOME_MISSING_WITH_BOUND_RESERVATION,
        SUCCESS_OUTCOME_FACT_MISMATCH,
        FAILURE_OUTCOME_WITH_MEETING,
        FAILURE_OUTCOME_WITH_BOUND_RESERVATION,
        RESERVATION_FACT_MISMATCH
    }

    /** 只读存储不可用的固定原因；reason 与 retryable 必须保持一致。 */
    enum UnavailableReason {
        TRANSIENT_STORAGE_FAILURE,
        STORAGE_FAILURE
    }

    /** 稳定查询结果联合类型；不得使用异常代替正常五状态分支。 */
    sealed interface Result permits NotFound, Replayed, Conflict, Inconsistent, Unavailable {
        Status status();
    }

    /**
     * 未发现任何已提交创建事实，且 Reservation 仍是请求中的初始未绑定事实。
     *
     * <p>该状态本身不执行终态失败竞争或取消，只允许后续编排决定下一步。</p>
     */
    record NotFound() implements Result {
        @Override
        public Status status() {
            return Status.NOT_FOUND;
        }

        @Override
        public String toString() {
            return "MeetingRecoveryLookup.NotFound";
        }
    }

    /** 从持久化 Outcome 原样取得的创建成功或确定失败。 */
    record Replayed(MeetingCreateOutcome outcome) implements Result {
        public Replayed {
            // Outcome 自己维护 Success/Failure 的形状与成功快照唯一不变量；这里
            // 只拒绝空联合分支，避免恢复端口复制 operation/state/time 规则。
            Objects.requireNonNull(outcome, "outcome");
        }

        @Override
        public Status status() {
            return Status.REPLAYED;
        }

        @Override
        public String toString() {
            return "MeetingRecoveryLookup.Replayed[outcome=redacted]";
        }
    }

    /** 相同恢复范围和幂等键已有不同请求摘要；既有正文不得继续解释。 */
    record Conflict() implements Result {
        @Override
        public Status status() {
            return Status.CONFLICT;
        }

        @Override
        public String toString() {
            return "MeetingRecoveryLookup.Conflict";
        }
    }

    /** Outcome、Meeting 或 Reservation 之间存在不能自动修复的矛盾。 */
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
            return "MeetingRecoveryLookup.Inconsistent[reason=" + reason + ']';
        }
    }

    /** 数据库读取失败；不携带 SQL、连接地址或驱动异常正文。 */
    record Unavailable(UnavailableReason reason, boolean retryable) implements Result {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            if ((reason == UnavailableReason.TRANSIENT_STORAGE_FAILURE) != retryable) {
                throw new IllegalArgumentException(
                        "retryable must match meeting recovery unavailable reason");
            }
        }

        @Override
        public Status status() {
            return Status.UNAVAILABLE;
        }

        @Override
        public String toString() {
            return "MeetingRecoveryLookup.Unavailable[reason=" + reason
                    + ", retryable=" + retryable + ']';
        }
    }
}
