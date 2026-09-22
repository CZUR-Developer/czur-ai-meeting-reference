package com.czur.reference.application.port;

import java.util.Objects;
import java.util.Optional;

/**
 * 最终用量事实的最小持久化端口。
 *
 * <p>每个 {@code meetingId} 只能保留一份不可变最终用量事实。第一次写入返回
 * {@link PersistStatus#APPLIED}；所有字段完全相同的重试返回
 * {@link PersistStatus#REPLAYED}；任意字段不一致返回
 * {@link PersistStatus#CONFLICT}，并且不得覆盖最初赢家。实现必须比较完整
 * {@link FinalUsageFact}，不能只比较用量数值或摘要。</p>
 *
 * <p>调用方必须先从已经持久化的 {@link DurableInputFactStore} 获取输入事实，
 * 再通过 {@link FinalUsageFact#fromInput(FinalizationInputFact)} 固化候选。该端口
 * 不重新读取当前时钟，不重新计算音频时长，也不负责 Reservation 结算；真实
 * 持久化实现应在更高层的事务中协调这些事实。</p>
 */
public interface FinalUsageFactStore {
    /**
     * 按会议读取已经固化的最终用量事实。
     *
     * @param meetingId 会议标识，必须为非空文本
     * @return 已保存事实；没有事实时返回空
     */
    Optional<FinalUsageFact> find(String meetingId);

    /**
     * 原子保存最终用量事实，或返回已经持久化的裁决结果。
     *
     * @param fact 已与来源 durable input 完整绑定的候选事实
     * @return 首次写入、幂等重放或冲突；返回的 fact 始终是当前赢家
     */
    PersistResult persist(FinalUsageFact fact);

    /** 最终用量事实写入裁决的稳定状态。 */
    enum PersistStatus {
        /** 候选首次写入并成为该会议的赢家。 */
        APPLIED,
        /** 相同完整事实已经存在，本次是幂等重放。 */
        REPLAYED,
        /** 已有不同事实，候选被拒绝且原事实不变。 */
        CONFLICT
    }

    /**
     * 一次最终用量写入裁决及当前赢家事实。
     *
     * <p>结果对象不可变且诊断输出脱敏，便于上层在超时重试后继续使用原赢家，
     * 同时不会把会议标识、输入绑定或内容摘要写入普通日志。</p>
     */
    record PersistResult(PersistStatus status, FinalUsageFact fact) {
        public PersistResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(fact, "fact");
        }

        @Override
        public String toString() {
            return "FinalUsageFactStore.PersistResult[status=" + status
                    + ", fact=redacted]";
        }
    }
}
