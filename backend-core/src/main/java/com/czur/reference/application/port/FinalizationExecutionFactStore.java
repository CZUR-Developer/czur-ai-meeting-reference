package com.czur.reference.application.port;

import com.czur.reference.application.finalization.FinalizationExecutionFact;

import java.util.Objects;
import java.util.Optional;

/**
 * 最终收口执行事实的不可变持久化端口。
 *
 * <p>每个 {@code meetingId} 只能存在一份完整执行事实。第一次持久化返回
 * {@link PersistStatus#APPLIED}；与数据库赢家完整相同的重试返回
 * {@link PersistStatus#REPLAYED}；同一会议已有不同事实时返回
 * {@link PersistStatus#CONFLICT}，且不得覆盖最初赢家。</p>
 *
 * <p>实现必须比较 {@link FinalizationExecutionFact} 的严格 canonical 表示，不能只
 * 比较会议标识、JSONB 语义或摘要。该端口不读取当前 Product Profile，不重新执行
 * Capability Planner，也不推进 Meeting、Manifest、Workflow 或权益状态。</p>
 */
public interface FinalizationExecutionFactStore {
    /**
     * 按会议读取已经固化且通过完整一致性校验的执行事实。
     *
     * @param meetingId 会议标识；实现必须在访问存储前完成校验
     * @return 已保存事实；不存在时为空
     */
    Optional<FinalizationExecutionFact> find(String meetingId);

    /**
     * 原子保存执行事实，或返回当前数据库赢家的稳定裁决。
     *
     * @param candidate 已冻结的候选执行事实
     * @return 首次写入、等价重放或内容冲突；{@code fact} 始终为当前赢家
     */
    PersistResult persist(FinalizationExecutionFact candidate);

    /** 执行事实持久化的稳定结果。 */
    enum PersistStatus {
        /** 候选首次写入并成为该会议的唯一事实。 */
        APPLIED,
        /** 数据库已经保存完全相同的 canonical 事实。 */
        REPLAYED,
        /** 数据库已经保存同一会议的另一份事实，候选被拒绝。 */
        CONFLICT
    }

    /**
     * 持久化裁决和数据库当前赢家。
     *
     * <p>冲突仍返回原赢家，供上层停止使用候选并进行只读恢复。诊断文本不得输出
     * meetingId、title、Profile、Capability Plan、canonical JSON 或摘要。</p>
     */
    record PersistResult(PersistStatus status, FinalizationExecutionFact fact) {
        public PersistResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(fact, "fact");
        }

        @Override
        public String toString() {
            return "FinalizationExecutionFactStore.PersistResult[status=" + status
                    + ", fact=redacted]";
        }
    }
}
