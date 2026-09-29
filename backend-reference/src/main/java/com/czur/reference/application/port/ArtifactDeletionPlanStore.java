package com.czur.reference.application.port;

import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.deletion.MeetingDeletionPlan;

import java.util.Objects;
import java.util.Optional;

/**
 * 删除受理时冻结计划及逐项审计的最小持久化端口。
 *
 * <p>首个切片只负责按 {@code meetingId} 注册一个不可变计划并只读恢复它。首次候选
 * 返回 {@link PersistStatus#APPLIED}；canonical JSON 和摘要均完全相同的重复请求返回
 * {@link PersistStatus#REPLAYED} 并携带数据库赢家；任一字段漂移返回
 * {@link PersistStatus#CONFLICT}，绝不覆盖赢家。端口不推进 Meeting、Workflow、
 * Artifact Store 或删除 Worker。</p>
 */
public interface ArtifactDeletionPlanStore {
    /** 读取已注册计划；不存在时返回空。 */
    Optional<MeetingDeletionPlan> find(String meetingId);

    /** 原子注册或重放一份冻结计划。 */
    PersistResult register(ArtifactDeletionPlan candidate);

    /** 与旧事实 Store 命名一致的别名；语义与 {@link #register} 完全相同。 */
    default PersistResult persist(ArtifactDeletionPlan candidate) {
        return register(candidate);
    }

    enum PersistStatus {
        APPLIED,
        REPLAYED,
        CONFLICT
    }

    /** 返回当前数据库赢家；结果默认输出不泄露会议、正文、摘要或 binding。 */
    record PersistResult(PersistStatus status, MeetingDeletionPlan plan) {
        public PersistResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(plan, "plan");
        }

        @Override
        public String toString() {
            return "ArtifactDeletionPlanStore.PersistResult[status=" + status
                    + ", plan=redacted]";
        }
    }

    enum StoreErrorCode {
        CORRUPT_DATA,
        STORAGE_UNAVAILABLE,
        TRANSACTION_REQUIRED,
        LOCK_CONFLICT
    }

    /** 固定故障类别；不保留数据库驱动异常、SQL、计划正文或身份字段。 */
    class StoreException extends IllegalStateException {
        private final StoreErrorCode code;

        public StoreException(StoreErrorCode code) {
            super("artifact deletion plan store failure code="
                    + Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public StoreErrorCode code() {
            return code;
        }

        @Override
        public String toString() {
            return "StoreException[code=" + code + "]";
        }
    }
}
