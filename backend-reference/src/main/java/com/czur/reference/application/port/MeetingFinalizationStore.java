package com.czur.reference.application.port;

import java.time.Instant;
import java.util.Objects;

/**
 * 会议级输入责任和最终收口事实的持久化端口。
 *
 * <p>该端口刻意把“停止意图”和“后端已经接管输入”分开。Stop 事务只能调用
 * {@link #requestInputCompletion(String)} 把会议从 {@code INPUT_OPEN} 推进到
 * {@code INPUT_COMPLETE_PENDING}；后续 Worker 在真正完成输入持久化接管后才可
 * 调用 {@link #acceptInput(String, Instant)}。因此 Stop 的幂等响应即使保存的是
 * 首次 {@code STOPPING} 快照，也不能被当成最终收口响应；REST、查询和实时事件
 * 必须另外读取本端口中的当前 {@link FinalizationFact}。</p>
 *
 * <p>receipt 是 meeting-level 的稳定事实，不属于某个 Worker 进程，也不从
 * {@code meeting_stream} 的连接游标推导。实现必须在同一个事务内锁定会议、
 * 检查当前输入状态、生成并保存 receipt。重复 Worker、并发 Worker或数据库重启
 * 后都必须返回第一次成功提交的同一个 receipt。</p>
 */
public interface MeetingFinalizationStore {
    /** 从持久事实源读取会议当前输入责任状态。 */
    Result find(String meetingId);

    /**
     * 持久化 Stop 已经提出输入完成请求的事实。
     *
     * <p>{@code INPUT_OPEN} 首次变为 {@code INPUT_COMPLETE_PENDING}；pending 或
     * accepted 重试返回 {@link Replayed}，不得降级或覆盖 receipt；cancelled
     * 返回冲突。</p>
     */
    Result requestInputCompletion(String meetingId);

    /**
     * 在真正接管输入后原子推进到 {@code INPUT_ACCEPTED} 并保存稳定 receipt。
     *
     * <p>只有 pending 可以首次推进；accepted 重跑返回原 receipt，不能再次生成；
     * open/cancelled 或不一致的会议生命周期返回冲突。{@code acceptedAt} 同时作为
     *会议进入 {@code FINALIZING} 的事实时间，必须不早于当前会议更新时间。</p>
     */
    Result acceptInput(String meetingId, Instant acceptedAt);

    /** 所有正常分支的稳定联合类型；数据库故障由实现抛出脱敏异常。 */
    sealed interface Result permits Applied, Replayed, Found, NotFound, Conflict {
        Status status();
    }

    /** 本次调用首次提交了新的持久事实。 */
    record Applied(FinalizationFact fact) implements Result {
        public Applied {
            Objects.requireNonNull(fact, "fact");
        }

        @Override
        public Status status() {
            return Status.APPLIED;
        }

        @Override
        public String toString() {
            return "MeetingFinalizationStore.Applied[fact=redacted]";
        }
    }

    /** 请求已经由相同或更后的持久事实满足。 */
    record Replayed(FinalizationFact fact) implements Result {
        public Replayed {
            Objects.requireNonNull(fact, "fact");
        }

        @Override
        public Status status() {
            return Status.REPLAYED;
        }

        @Override
        public String toString() {
            return "MeetingFinalizationStore.Replayed[fact=redacted]";
        }
    }

    /** 只读查询命中数据库中的当前事实。 */
    record Found(FinalizationFact fact) implements Result {
        public Found {
            Objects.requireNonNull(fact, "fact");
        }

        @Override
        public Status status() {
            return Status.FOUND;
        }

        @Override
        public String toString() {
            return "MeetingFinalizationStore.Found[fact=redacted]";
        }
    }

    /** 会议不存在；不得据此自动创建会议或 receipt。 */
    record NotFound() implements Result {
        @Override
        public Status status() {
            return Status.NOT_FOUND;
        }

        @Override
        public String toString() {
            return "MeetingFinalizationStore.NotFound";
        }
    }

    /** 当前持久事实不能按冻结状态机执行请求。 */
    record Conflict(ConflictReason reason) implements Result {
        public Conflict {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public Status status() {
            return Status.CONFLICT;
        }

        @Override
        public String toString() {
            return "MeetingFinalizationStore.Conflict[reason=" + reason + ']';
        }
    }

    enum Status {
        APPLIED,
        REPLAYED,
        FOUND,
        NOT_FOUND,
        CONFLICT
    }

    enum ConflictReason {
        INVALID_INPUT_STATE,
        INVALID_MEETING_STATE,
        FACT_INCONSISTENT,
        FACT_TIME_BEFORE_CURRENT
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /** 公共实现调用的输入校验，避免适配器对空 meetingId 产生不同语义。 */
    static String requireMeetingId(String meetingId) {
        requireText("meetingId", meetingId);
        return meetingId;
    }
}
