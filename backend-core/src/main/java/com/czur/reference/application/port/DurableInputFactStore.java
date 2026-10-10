package com.czur.reference.application.port;

import java.util.Objects;
import java.util.Optional;

/**
 * durable input fact 的最小应用端口。
 *
 * <p>端口只冻结“按 meetingId 保存一份不可变输入事实”的业务语义，不规定
 * 数据库表、事务、摘要算法、序列化格式或 Worker 编排。实现必须先按会议标识
 * 查找已有事实，再裁决写入：第一次写入返回 {@link PersistStatus#APPLIED}；同一
 * meetingId 且完整事实相等的重试返回 {@link PersistStatus#REPLAYED}；已有事实
 * 与候选事实不同则返回 {@link PersistStatus#CONFLICT}，并且不得覆盖原事实。</p>
 *
 * <p>该端口是生成 INPUT_ACCEPTED/receipt 前的前置事实边界。它本身不生成
 * {@code finalizationReceipt}，也不推进 {@link MeetingFinalizationStore} 的输入状态；
 * 具体实现必须由更高层在同一持久化协同边界中决定何时使用已提交事实。</p>
 */
public interface DurableInputFactStore {
    /**
     * 读取会议已经持久化的完整输入事实。
     *
     * @param meetingId 会议标识，必须为非空文本
     * @return 已存在的不可变事实；不存在时返回空
     */
    Optional<FinalizationInputFact> find(String meetingId);

    /**
     * 原子保存一份输入事实，或返回已存在的裁决结果。
     *
     * <p>实现必须按完整事实比较，而不能只比较 contentDigest、sequence 或
     * receipt。返回结果中的 {@code fact} 是当前已持久化的赢家事实：首次写入和
     * 重放时它等于候选，冲突时它保留此前已提交的事实，便于调用方显式查询而不
     * 误把冲突候选当成已接管输入。</p>
     *
     * @param fact 候选输入事实，不得为空
     * @return 稳定的首次写入、重放或冲突结果
     */
    PersistResult persist(FinalizationInputFact fact);

    /** 一次持久化裁决的稳定状态。 */
    enum PersistStatus {
        /** 候选事实首次写入并成为会议的持久事实。 */
        APPLIED,
        /** 相同完整事实已经存在，本次是幂等重放。 */
        REPLAYED,
        /** 会议已有不同事实，候选被拒绝且原事实保持不变。 */
        CONFLICT
    }

    /**
     * 持久化裁决和当前赢家事实。
     *
     * <p>结果显式携带事实是为了让超时重试可以使用同一个已提交值；它不会改变
     * {@link FinalizationInputFact} 的不可变性。诊断输出只显示状态，不输出会议、
     * binding、语言、摘要或任何未来附加的 opaque receipt。</p>
     */
    record PersistResult(PersistStatus status, FinalizationInputFact fact) {
        public PersistResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(fact, "fact");
        }

        @Override
        public String toString() {
            return "DurableInputFactStore.PersistResult[status=" + status
                    + ", fact=redacted]";
        }
    }
}
