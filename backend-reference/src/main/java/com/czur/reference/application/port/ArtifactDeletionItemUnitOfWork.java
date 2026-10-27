package com.czur.reference.application.port;

import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.port.WorkflowStepStore.LeaseToken;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个删除 Workflow item 的 fenced 确认工作单元。
 *
 * <p>调用方必须把删除受理时冻结的完整 {@link WriteIntentSpec} 原样带入。实现先
 * 锁定并校验当前 Workflow lease，再调用 ArtifactStore 的按 spec 原语；任何数据库
 * 事实只会在 Store 成功后写入同一事务。Store 成功而数据库提交结果未知时，调用方
 * 可以用新 lease 重放同一个请求；item 的 DELETED 审计行和 Store tombstone 会共同
 * 把重放收敛为幂等结果。此端口不推进 Meeting、Manifest 或 Workflow 终态。</p>
 */
public interface ArtifactDeletionItemUnitOfWork {
    /** 删除并确认一个冻结 Artifact item。 */
    Result delete(DeleteRequest request);

    /** 当前 lease 与删除计划 ordinal 一起构成完整 fencing 输入。 */
    record DeleteRequest(
            LeaseToken lease,
            int ordinal,
            WriteIntentSpec spec,
            Instant deletedAt) {
        public DeleteRequest {
            Objects.requireNonNull(lease, "lease");
            if (!ArtifactDeletionPlan.STEP_TYPE.equals(lease.key().stepType())) {
                throw new IllegalArgumentException("lease is not an artifact deletion workflow");
            }
            if (ordinal < 0 || ordinal >= ArtifactDeletionPlan.MAX_TARGETS) {
                throw new IllegalArgumentException("ordinal is invalid");
            }
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(deletedAt, "deletedAt");
            if (deletedAt.getNano() % 1_000 != 0) {
                throw new IllegalArgumentException("deletedAt must use database microsecond precision");
            }
        }

        @Override
        public String toString() {
            return "ArtifactDeletionItemUnitOfWork.DeleteRequest[lease=redacted"
                    + ", ordinal=" + ordinal + ", spec=redacted, deletedAt=" + deletedAt + ']';
        }
    }

    sealed interface Result permits Success, Failure {
    }

    /** 数据库和 Store 均已观察到删除；REPLAYED 不会再次访问 Store。 */
    record Success(SuccessCode code, StorageBinding binding) implements Result {
        public Success {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(binding, "binding");
        }

        @Override
        public String toString() {
            return "ArtifactDeletionItemUnitOfWork.Success[code=" + code
                    + ", binding=redacted]";
        }
    }

    enum SuccessCode {
        DELETED,
        REPLAYED
    }

    /** 固定、脱敏的拒绝或暂时失败分类。 */
    record Failure(ErrorCode code, boolean retryable) implements Result {
        public Failure {
            Objects.requireNonNull(code, "code");
            if (retryable != code.retryable()) {
                throw new IllegalArgumentException("retryability does not match error code");
            }
        }

        @Override
        public String toString() {
            return "ArtifactDeletionItemUnitOfWork.Failure[code=" + code
                    + ", retryable=" + retryable + ']';
        }
    }

    enum ErrorCode {
        NOT_FOUND(false),
        NOT_OWNER(false),
        LEASE_EXPIRED(true),
        ALREADY_COMPLETED(false),
        ALREADY_DEAD(false),
        PLAN_NOT_FOUND(true),
        SPEC_MISMATCH(false),
        ITEM_MISMATCH(false),
        BINDING_MISMATCH(false),
        SNAPSHOT_INCONSISTENT(false),
        STORE_REJECTED(false),
        STORE_FAILURE(true),
        STORAGE_FAILURE(true),
        OUTCOME_UNKNOWN(true);

        private final boolean retryable;

        ErrorCode(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
