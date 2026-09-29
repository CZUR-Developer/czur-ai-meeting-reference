package com.czur.reference.application.port;

import java.time.Instant;
import java.util.Objects;

/**
 * 删除工作流的终态收口端口。
 *
 * <p>实现必须在一个本地事务中核对冻结计划的全部 item，追加（如果原本存在
 * 完整 Manifest）唯一的 DELETED revision，并把 Meeting、计划、Workflow 与
 * {@code MeetingDeleted} Outbox 一起提交。逐项删除由
 * {@link ArtifactDeletionItemUnitOfWork} 完成，本端口不访问对象存储。</p>
 */
public interface ArtifactDeletionTerminalUnitOfWork {
    Result complete(CompleteRequest request);

    /** 提交确认未知后的只读事实恢复；不得再次执行终态写入。 */
    Result recover(CompleteRequest request);

    /** 当前删除 Workflow lease 及本次终态时间。 */
    record CompleteRequest(
            WorkflowStepStore.LeaseToken lease,
            Instant completedAt) {
        public CompleteRequest {
            Objects.requireNonNull(lease, "lease");
            if (!com.czur.reference.application.deletion.ArtifactDeletionPlan.STEP_TYPE
                    .equals(lease.key().stepType())) {
                throw new IllegalArgumentException("lease is not an artifact deletion workflow");
            }
            Objects.requireNonNull(completedAt, "completedAt");
            if (completedAt.getNano() % 1_000 != 0) {
                throw new IllegalArgumentException(
                        "completedAt must use PostgreSQL microsecond precision");
            }
        }
    }

    sealed interface Result permits Success, Failure {
    }

    /** 所有本地终态事实已由同一事务提交；revision 仅在原本存在 Manifest 时存在。 */
    record Success(SuccessCode code, Integer deletedManifestRevision) implements Result {
        public Success {
            Objects.requireNonNull(code, "code");
            if (deletedManifestRevision != null && deletedManifestRevision < 1) {
                throw new IllegalArgumentException("deletedManifestRevision must be positive");
            }
            if (code == SuccessCode.COMPLETED && deletedManifestRevision == null) {
                // 空计划或早期终态可以没有 Manifest；null 是合法的严格语义。
            }
        }
    }

    enum SuccessCode {
        COMPLETED,
        REPLAYED
    }

    /** 关闭式失败分类；失败不会把 Meeting 提前推进为 DELETED。 */
    record Failure(ErrorCode code, boolean retryable) implements Result {
        public Failure {
            Objects.requireNonNull(code, "code");
            if (retryable != code.retryable()) {
                throw new IllegalArgumentException("retryability does not match error code");
            }
        }
    }

    enum ErrorCode {
        NOT_FOUND(false),
        NOT_OWNER(false),
        LEASE_EXPIRED(true),
        ALREADY_COMPLETED(false),
        ALREADY_DEAD(false),
        PLAN_NOT_FOUND(true),
        PLAN_NOT_PENDING(false),
        ITEM_NOT_CONFIRMED(true),
        SNAPSHOT_INCONSISTENT(false),
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
