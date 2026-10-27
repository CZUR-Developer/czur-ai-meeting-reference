package com.czur.reference.application.port;

import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.port.WorkflowStepStore.LeaseToken;
import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 使用当前 Workflow lease 原子提交最终 Manifest、Meeting 和 Workflow 终态的端口。
 *
 * <p>成功路径只接受已经由 Artifact Store 确认 READY 的完整 descriptor 集合，并且
 * 必须先从本地持久事实证明 checkpoint 中冻结的权益提交已经完成。实现随后在一个
 * 数据库事务中保存 READY metadata、追加 Manifest revision 1、推进 Meeting，
 * 并把同一 lease 完成为 {@code COMPLETED}。</p>
 *
 * <p>失败路径允许保留已经 READY 的 descriptor 子集；其余 checkpoint artifact 会
 * 由 Manifest 工厂稳定表达为 FAILED。只有同一事务中的 Workflow 失败裁决确实进入
 * {@code DEAD} 时，FAILED Manifest 和 Meeting 才能一起提交。旧、过期或已经被重领
 * 的 lease 不能写入任何 metadata、Manifest 或 Meeting 终态。</p>
 *
 * <p>提交确认未知后，调用方只能把同一请求交给对应 {@code recover} 方法。恢复实现
 * 必须使用全新只读连接核对 leaseVersion、终态时间和全部最终事实，不得再次执行
 * terminal 写入。本端口不调用模型、Artifact Store 或 Entitlement Provider。</p>
 */
public interface FinalizationCompletionUnitOfWork {

    /** 原子发布 READY 或 READY_WITH_WARNINGS 结果。 */
    Result complete(CompletionRequest request);

    /** 原子发布 FAILED 结果，并把当前 Workflow lease 裁决为 DEAD。 */
    Result fail(FailureRequest request);

    /** 在成功路径提交确认未知后，只读恢复同一请求的确定结果。 */
    Result recoverCompletion(CompletionRequest request);

    /** 在失败路径提交确认未知后，只读恢复同一请求的确定结果。 */
    Result recoverFailure(FailureRequest request);

    /**
     * READY 终态所需的完整、可信输入。
     *
     * <p>{@code readyArtifacts} 必须与 checkpoint 的 artifact 集合一一对应；顺序不
     * 参与身份。{@code completedAt} 同时是 Workflow 终态时间与 Meeting 更新时间，
     * 必须严格位于 lease 有效区间内并可由数据库微秒精度无损保存。</p>
     */
    record CompletionRequest(
            LeaseToken lease,
            FinalizationCheckpoint checkpoint,
            List<ArtifactDescriptor> readyArtifacts,
            Instant completedAt) {
        public CompletionRequest {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(checkpoint, "checkpoint");
            readyArtifacts = immutableArtifacts(readyArtifacts);
            requirePersistentInstant("completedAt", completedAt);
            requireLeaseCheckpointMatch(lease, checkpoint);
            requireOperationWithinLease(lease, completedAt);
        }

        @Override
        public String toString() {
            return "FinalizationCompletionUnitOfWork.CompletionRequest[lease=redacted"
                    + ", checkpoint=redacted, artifactCount=" + readyArtifacts.size()
                    + ", completedAt=" + completedAt + ']';
        }
    }

    /**
     * FAILED 终态所需的完整、可信输入。
     *
     * <p>{@code readyArtifacts} 可以是 checkpoint 集合的任意子集（包括空集和
     * 全集）；工厂会把缺少 READY metadata 的 artifact 标记为 FAILED。
     * {@code leasedAttempt} 必须是领取当前 lease 时观察到的次数；工作单元会固定使用
     * {@code leasedAttempt + 1} 作为最大次数，使本次失败必然进入 DEAD。调用方不能
     * 通过重试策略参数改变最终收口的原子终态语义。</p>
     */
    record FailureRequest(
            LeaseToken lease,
            FinalizationCheckpoint checkpoint,
            List<ArtifactDescriptor> readyArtifacts,
            TerminalFailure failure,
            Instant failedAt,
            int leasedAttempt) {
        public FailureRequest {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(checkpoint, "checkpoint");
            readyArtifacts = immutableArtifacts(readyArtifacts);
            Objects.requireNonNull(failure, "failure");
            requirePersistentInstant("failedAt", failedAt);
            if (leasedAttempt < 0) {
                throw new IllegalArgumentException("leasedAttempt must not be negative");
            }
            Math.addExact(leasedAttempt, 1);
            requireLeaseCheckpointMatch(lease, checkpoint);
            requireOperationWithinLease(lease, failedAt);
        }

        @Override
        public String toString() {
            return "FinalizationCompletionUnitOfWork.FailureRequest[lease=redacted"
                    + ", checkpoint=redacted, artifactCount=" + readyArtifacts.size()
                    + ", failure=redacted, failedAt=" + failedAt
                    + ", leasedAttempt=" + leasedAttempt + ']';
        }
    }

    /**
     * 可写入 Workflow 和公开 FAILED Artifact 的稳定失败摘要。
     *
     * <p>字段必须由 Worker 按关闭式错误映射产生，不得放入供应商响应、URL、路径、
     * SQL detail、凭据或用户正文。{@code domain} 取值受 Manifest 公共协议约束。</p>
     */
    record TerminalFailure(
            String domain,
            String code,
            boolean retryable,
            String diagnosticId,
            String workflowSummary) {
        private static final Set<String> DOMAINS = Set.of(
                "MODEL", "STORAGE", "SUMMARY", "INTERNAL");
        private static final int MAX_TEXT_UTF8_BYTES = 4096;

        public TerminalFailure {
            requireText("domain", domain);
            if (!DOMAINS.contains(domain)) {
                throw new IllegalArgumentException(
                        "domain is not supported by the public Manifest protocol");
            }
            requireBoundedText("code", code, MAX_TEXT_UTF8_BYTES);
            requireBoundedText("diagnosticId", diagnosticId, MAX_TEXT_UTF8_BYTES);
            requireBoundedText("workflowSummary", workflowSummary, MAX_TEXT_UTF8_BYTES);
        }

        @Override
        public String toString() {
            return "FinalizationCompletionUnitOfWork.TerminalFailure[domain=" + domain
                    + ", code=" + code + ", retryable=" + retryable
                    + ", diagnosticIdPresent=true, workflowSummary=redacted]";
        }
    }

    sealed interface Result permits Success, Failure {
    }

    /** 已确认提交，或由完整持久事实证明的稳定重放结果。 */
    record Success(SuccessCode code, MeetingManifest manifest) implements Result {
        public Success {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(manifest, "manifest");
            boolean ready = manifest.status() == ManifestStatus.READY
                    || manifest.status() == ManifestStatus.READY_WITH_WARNINGS;
            if ((code == SuccessCode.COMPLETED || code == SuccessCode.COMPLETION_REPLAYED)
                    != ready) {
                throw new IllegalArgumentException(
                        "completion result code must match manifest terminal class");
            }
            if ((code == SuccessCode.FAILED || code == SuccessCode.FAILURE_REPLAYED)
                    != (manifest.status() == ManifestStatus.FAILED)) {
                throw new IllegalArgumentException(
                        "failure result code must match FAILED manifest");
            }
        }

        @Override
        public String toString() {
            return "FinalizationCompletionUnitOfWork.Success[code=" + code
                    + ", manifest=redacted]";
        }
    }

    /** 固定错误分类，不携带数据库、业务标识或外部响应正文。 */
    record Failure(ErrorCode code, boolean retryable) implements Result {
        public Failure {
            Objects.requireNonNull(code, "code");
            if (retryable != code.retryable()) {
                throw new IllegalArgumentException(
                        "retryable must match the stable error-code policy");
            }
        }

        public Failure(ErrorCode code) {
            this(Objects.requireNonNull(code, "code"), code.retryable());
        }
    }

    enum SuccessCode {
        COMPLETED,
        COMPLETION_REPLAYED,
        FAILED,
        FAILURE_REPLAYED
    }

    /** Completion 工作单元的关闭式稳定错误分类。 */
    enum ErrorCode {
        MEETING_NOT_FOUND(false),
        INVALID_MEETING_STATE(false),
        EXECUTION_FACT_MISSING(false),
        INPUT_FACT_MISSING(false),
        FINAL_USAGE_MISSING(false),
        CHECKPOINT_CONFLICT(false),
        ARTIFACT_CONFLICT(false),
        ENTITLEMENT_NOT_COMMITTED(false),
        WORKFLOW_NOT_FOUND(false),
        LEASE_NOT_OWNER(false),
        LEASE_EXPIRED(false),
        WORKFLOW_TERMINAL_CONFLICT(false),
        MANIFEST_CONFLICT(false),
        FACT_INCONSISTENT(false),
        COMPLETION_NOT_FOUND(false),
        STORAGE_UNAVAILABLE(true),
        OUTCOME_UNKNOWN(true),
        INTERNAL(false);

        private final boolean retryable;

        ErrorCode(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private static List<ArtifactDescriptor> immutableArtifacts(
            List<ArtifactDescriptor> artifacts) {
        Objects.requireNonNull(artifacts, "readyArtifacts");
        for (ArtifactDescriptor artifact : artifacts) {
            Objects.requireNonNull(artifact, "readyArtifacts item");
        }
        return List.copyOf(artifacts);
    }

    private static void requireLeaseCheckpointMatch(
            LeaseToken lease,
            FinalizationCheckpoint checkpoint) {
        if (!lease.key().equals(checkpoint.workflowKey())) {
            throw new IllegalArgumentException(
                    "lease key must match checkpoint workflow key");
        }
    }

    private static void requireOperationWithinLease(
            LeaseToken lease,
            Instant operationAt) {
        if (operationAt.isBefore(lease.claimedAt())
                || !operationAt.isBefore(lease.expiresAt())) {
            throw new IllegalArgumentException(
                    "terminal operation time must be inside the lease interval");
        }
    }

    private static void requirePersistentInstant(String name, Instant value) {
        Objects.requireNonNull(value, name);
        if (value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    name + " must use database microsecond precision");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        MeetingIdPolicy.utf8Length(value);
    }

    private static void requireBoundedText(String name, String value, int maxBytes) {
        requireText(name, value);
        if (MeetingIdPolicy.utf8Length(value) > maxBytes) {
            throw new IllegalArgumentException(
                    name + " must not exceed " + maxBytes + " UTF-8 bytes");
        }
    }
}
