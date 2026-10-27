package com.czur.reference.application.finalization;

import com.czur.reference.application.port.FinalizationCompletionUnitOfWork;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.CompletionRequest;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.ErrorCode;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.FailureRequest;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.TerminalFailure;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.WorkflowStepStore.ClaimedStep;
import com.czur.reference.application.port.WorkflowStepStore.FailResult;
import com.czur.reference.application.port.WorkflowStepStore.LeaseToken;
import com.czur.reference.application.port.WorkflowStepStore.PersistLeaseMarkerRequest;
import com.czur.reference.application.port.WorkflowStepStore.PersistLeaseMarkerResult;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.PrepareResult;
import com.czur.reference.application.port.artifact.ArtifactStore.StatResult;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.StoreState;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.application.port.artifact.ArtifactStoreException;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.protocol.json.FinalizationCheckpointJson;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 参考后端的可恢复 Mock 最终收口 Worker。
 *
 * <p>本类只提供调用方显式驱动的 {@link #runOnce()}，不创建线程、定时器或内存
 * 队列。每一轮只领取 {@link FinalizationCheckpoint#STEP_TYPE}，并且只从已经持久化
 * 的 canonical checkpoint 恢复 Artifact 内容和权益提交请求；它不会重新运行
 * Planner，也不会读取当前 Product Profile、当前模型能力或当前配置。</p>
 *
 * <p>Artifact Store、权益 Provider 与数据库之间没有跨资源事务。Worker 先按
 * 稳定 Artifact identity 幂等发布内容，再用 checkpoint 冻结的幂等键提交权益，
 * 最后调用 {@link FinalizationCompletionUnitOfWork} 原子提交数据库终态。任一步骤
 * 崩溃后，下一次领取都重放相同 identity。Worker 本身不执行 SQL，也不把外部成功
 * 误写成数据库已经完成。</p>
 *
 * <p>只有 Completion 工作单元可以把 Workflow 写成 DEAD，同时发布 FAILED
 * Manifest 和 Meeting 终态。普通可重试失败调用 {@link WorkflowStepStore#fail}
 * 时只释放为 PENDING；达到最大次数或不可重试失败必须转交 Completion。这样不会
 * 出现“Workflow 已 DEAD，但 Meeting/Manifest 仍在生成”的半终态。</p>
 */
public final class MockFinalizationWorker {
    private static final Logger LOGGER =
            Logger.getLogger(MockFinalizationWorker.class.getName());

    private static final String CHECKPOINT_INVALID = "CHECKPOINT_INVALID";
    private static final String CHECKPOINT_INVALID_SUMMARY =
            "PERSISTED_FINALIZATION_CHECKPOINT_INVALID";
    private static final String TERMINAL_OPERATION_MARKER_INVALID =
            "TERMINAL_OPERATION_MARKER_INVALID";
    private static final Pattern STABLE_REASON_CODE =
            Pattern.compile("^[A-Z][A-Z0-9_]{0,127}$");
    private static final Set<String> COMPLETED_REASONS =
            Set.of("COMPLETED", "COMPLETION_REPLAYED");
    private static final Set<String> FAILED_REASONS =
            Set.of("FAILED", "FAILURE_REPLAYED");

    private final WorkflowStepStore workflowStore;
    private final ArtifactStore artifactStore;
    private final EntitlementProvider entitlementProvider;
    private final FinalizationCompletionUnitOfWork completion;
    private final Config config;

    public MockFinalizationWorker(
            WorkflowStepStore workflowStore,
            ArtifactStore artifactStore,
            EntitlementProvider entitlementProvider,
            FinalizationCompletionUnitOfWork completion,
            Config config) {
        this.workflowStore = Objects.requireNonNull(workflowStore, "workflowStore");
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.entitlementProvider =
                Objects.requireNonNull(entitlementProvider, "entitlementProvider");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.config = Objects.requireNonNull(config, "config");
        LOGGER.log(Level.INFO, "Mock 最终收口 Worker 已创建（无后台线程）");
    }

    /**
     * 执行一次领取和收口循环。
     *
     * <p>持久化端口自身抛出的运行时故障会原样穿透；这类故障无法安全消耗一次业务
     * attempt。已关闭式分类的 Artifact、权益和 Completion 失败才会进入稳定重试或
     * FAILED 终态。</p>
     */
    public RunResult runOnce() {
        Instant claimAt = now();
        List<ClaimedStep> claimed = workflowStore.claim(
                new WorkflowStepStore.ClaimRequest(
                        Set.of(FinalizationCheckpoint.STEP_TYPE),
                        config.leaseOwner(),
                        claimAt,
                        config.leaseDuration(),
                        config.batchLimit()));
        if (claimed.isEmpty()) {
            LOGGER.log(Level.FINE, "Mock 最终收口 Worker 本轮无可领取任务");
            return new RunResult(0, List.of());
        }

        List<ItemResult> items = new ArrayList<>(claimed.size());
        for (ClaimedStep item : claimed) {
            items.add(process(item));
        }
        return new RunResult(claimed.size(), items);
    }

    private ItemResult process(ClaimedStep claimed) {
        WorkflowStep step = claimed.step();
        LeaseContext lease = new LeaseContext(claimed.lease());
        FinalizationCheckpoint checkpoint;
        try {
            checkpoint = decodeCheckpoint(step);
        } catch (IllegalArgumentException invalidCheckpoint) {
            // 没有可信 checkpoint 就无法构造 Completion.fail 所需的 Artifact 集合、
            // Manifest 输入和权益计划。单独把 Workflow 标 DEAD 会制造半终态，因此
            // 这里只返回稳定阻断并让 lease 到期，等待运维修复持久事实。
            LOGGER.log(Level.SEVERE,
                    "最终收口 checkpoint 损坏，拒绝伪造 FAILED 终态 reason={0}",
                    CHECKPOINT_INVALID);
            return item(step, ItemStatus.INCONSISTENT, CHECKPOINT_INVALID);
        }

        if (FinalizationTerminalOperationMarker.SUCCESS_CODE.equals(step.errorCode())
                || FinalizationTerminalOperationMarker.FAILURE_CODE.equals(
                step.errorCode())) {
            FinalizationTerminalOperationMarker marker;
            try {
                marker = FinalizationTerminalOperationMarker.decode(
                        step.errorCode(), step.errorSummary(), checkpoint);
            } catch (IllegalArgumentException invalidMarker) {
                // 终态请求一旦持久化就不能被重新推导。marker 损坏时既不能回到
                // Artifact/权益链，也不能用不完整信息构造另一个终态请求。
                LOGGER.log(Level.SEVERE,
                        "最终收口操作 marker 损坏，关闭式停止 reason={0}",
                        TERMINAL_OPERATION_MARKER_INVALID);
                return item(step, ItemStatus.INCONSISTENT,
                        TERMINAL_OPERATION_MARKER_INVALID);
            }
            return resumeTerminalOperation(step, lease, checkpoint, marker);
        }

        List<ArtifactDescriptor> readyArtifacts = new ArrayList<>(
                checkpoint.artifacts().size());
        for (FinalizationCheckpoint.ArtifactMaterial material : checkpoint.artifacts()) {
            ItemResult leaseStop = ensureLeaseOrStop(step, lease);
            if (leaseStop != null) {
                return leaseStop;
            }
            try {
                readyArtifacts.add(publishArtifact(material));
            } catch (WorkerFailure failure) {
                return handleBusinessFailure(
                        step, lease, checkpoint, readyArtifacts, failure.detail());
            }
        }

        ItemResult leaseStop = ensureLeaseOrStop(step, lease);
        if (leaseStop != null) {
            return leaseStop;
        }
        EntitlementProviderResult<UsageOperationResult> entitlement =
                entitlementProvider.commitUsage(checkpoint.commitUsageRequest());
        if (!entitlement.isSuccess()) {
            return handleBusinessFailure(
                    step,
                    lease,
                    checkpoint,
                    readyArtifacts,
                    entitlementFailure(entitlement.errorOrThrow()));
        }
        if (!committedUsageMatches(checkpoint, entitlement.valueOrThrow())) {
            return handleBusinessFailure(
                    step,
                    lease,
                    checkpoint,
                    readyArtifacts,
                    stableFailure(
                            "INTERNAL",
                            "ENTITLEMENT_RESULT_CONFLICT",
                            false,
                            "ENTITLEMENT_RESULT_CONFLICT",
                            "ENTITLEMENT_COMMIT_RESULT_INVALID"));
        }

        leaseStop = ensureLeaseOrStop(step, lease);
        if (leaseStop != null) {
            return leaseStop;
        }
        Instant completedAt = now();
        if (!insideLease(lease.current(), completedAt)) {
            return leaseFailure(step, "LEASE_EXPIRED");
        }
        CompletionRequest request = new CompletionRequest(
                lease.current(), checkpoint, readyArtifacts, completedAt);
        FinalizationTerminalOperationMarker marker;
        try {
            marker = FinalizationTerminalOperationMarker.forCompletion(
                    request, step.attempt());
        } catch (IllegalArgumentException invalidMarker) {
            LOGGER.log(Level.SEVERE,
                    "成功终态请求无法 canonical 编码，拒绝调用 Completion");
            return item(step, ItemStatus.INCONSISTENT,
                    TERMINAL_OPERATION_MARKER_INVALID);
        }
        ItemResult markerStop = persistTerminalMarker(
                step,
                lease,
                step.errorCode(),
                step.errorSummary(),
                marker);
        if (markerStop != null) {
            return markerStop;
        }
        return executeCompletion(step, request);
    }

    /**
     * 恢复已经持久化的终态请求。
     *
     * <p>该路径禁止访问 Artifact Store、再次提交权益或重新推导失败。首先只读恢复
     * marker 中的旧请求；只有明确得到 COMPLETION_NOT_FOUND，且当前 Worker 已持有
     * 与旧请求不同的有效 lease，才把旧 marker CAS 为新请求并直接重发一次终态事务。</p>
     */
    private ItemResult resumeTerminalOperation(
            WorkflowStep step,
            LeaseContext lease,
            FinalizationCheckpoint checkpoint,
            FinalizationTerminalOperationMarker marker) {
        FinalizationCompletionUnitOfWork.Result recovered;
        if (marker.kind() == FinalizationTerminalOperationMarker.Kind.SUCCESS) {
            recovered = completion.recoverCompletion(
                    marker.completionRequest(checkpoint));
        } else {
            recovered = completion.recoverFailure(marker.failureRequest(checkpoint));
        }
        ItemResult terminal = terminalSuccessOrStableFailure(step, marker.kind(), recovered);
        if (terminal != null) {
            return terminal;
        }

        FinalizationCompletionUnitOfWork.Failure recoveryFailure =
                (FinalizationCompletionUnitOfWork.Failure) recovered;
        if (recoveryFailure.code() == ErrorCode.STORAGE_UNAVAILABLE
                || recoveryFailure.code() == ErrorCode.OUTCOME_UNKNOWN) {
            LOGGER.log(Level.WARNING,
                    "终态请求只读恢复暂不可用，保留 marker 等待下一轮");
            return item(step, ItemStatus.OUTCOME_UNRESOLVED,
                    recoveryFailure.code().name());
        }
        if (recoveryFailure.code() != ErrorCode.COMPLETION_NOT_FOUND) {
            return completionFailureAfterRecovery(step, recoveryFailure.code());
        }
        if (lease.current().equals(marker.lease())) {
            // 同一 lease 内不循环重发结果未知的请求。等待 lease 旋转后，新的 owner
            // 才能以独立栅栏证明旧请求未提交并 CAS 出新的请求 identity。
            return item(step, ItemStatus.OUTCOME_UNRESOLVED,
                    ErrorCode.COMPLETION_NOT_FOUND.name());
        }
        ItemResult leaseStop = ensureLeaseOrStop(step, lease);
        if (leaseStop != null) {
            return leaseStop;
        }
        return rotateAndExecuteTerminal(step, lease, checkpoint, marker);
    }

    private FinalizationCheckpoint decodeCheckpoint(WorkflowStep step) {
        if (!FinalizationCheckpoint.STEP_TYPE.equals(step.key().stepType())
                || !FinalizationCheckpoint.CHECKPOINT_TYPE.equals(step.checkpointType())) {
            throw new IllegalArgumentException(CHECKPOINT_INVALID_SUMMARY);
        }
        FinalizationCheckpoint checkpoint =
                FinalizationCheckpointJson.decode(step.canonicalPayload());
        if (!step.key().equals(checkpoint.workflowKey())
                || !step.canonicalPayload().equals(
                        FinalizationCheckpointJson.encode(checkpoint))
                || !step.contentDigest().equals(
                        FinalizationCheckpointJson.sha256(checkpoint))) {
            throw new IllegalArgumentException(CHECKPOINT_INVALID_SUMMARY);
        }
        return checkpoint;
    }

    private ArtifactDescriptor publishArtifact(
            FinalizationCheckpoint.ArtifactMaterial material) throws WorkerFailure {
        PrepareResult prepared;
        try {
            prepared = artifactStore.createWriteIntent(material.spec());
        } catch (ArtifactStoreException failure) {
            throw new WorkerFailure(artifactFailure(failure));
        }
        if (prepared.status() == ArtifactStore.PrepareStatus.CONFLICT
                || !prepared.intent().spec().equals(material.spec())) {
            throw new WorkerFailure(stableFailure(
                    "STORAGE",
                    "ARTIFACT_INTENT_CONFLICT",
                    false,
                    "ARTIFACT_INTENT_CONFLICT",
                    "ARTIFACT_WRITE_INTENT_CONFLICT"));
        }

        WriteIntent intent = prepared.intent();
        try (InputStream content = material.openContent()) {
            ArtifactStore.StageResult staged = artifactStore.writeContent(intent, content);
            if (!staged.intent().equals(intent)
                    || !staged.content().equals(material.claim())) {
                throw new WorkerFailure(stableFailure(
                        "STORAGE",
                        "ARTIFACT_STAGE_CONFLICT",
                        false,
                        "ARTIFACT_STAGE_CONFLICT",
                        "ARTIFACT_STAGED_CONTENT_INVALID"));
            }
        } catch (ArtifactStoreException failure) {
            if (failure.code() == ArtifactStoreException.Code.COMMIT_UNKNOWN) {
                ArtifactDescriptor recovered = recoverReady(intent, material);
                if (recovered != null) {
                    return recovered;
                }
            }
            throw new WorkerFailure(artifactFailure(failure));
        } catch (IOException impossibleForCheckpointBytes) {
            throw new WorkerFailure(stableFailure(
                    "INTERNAL",
                    "ARTIFACT_CONTENT_CLOSE_FAILED",
                    false,
                    "ARTIFACT_CONTENT_CLOSE_FAILED",
                    "ARTIFACT_CONTENT_STREAM_FAILED"));
        }

        try {
            ArtifactStore.CompleteResult completed =
                    artifactStore.completeWrite(intent, material.claim());
            ArtifactDescriptor descriptor = completed.artifact();
            if (!descriptor.intent().equals(intent)
                    || !descriptor.content().equals(material.claim())) {
                throw new WorkerFailure(stableFailure(
                        "STORAGE",
                        "ARTIFACT_READY_CONFLICT",
                        false,
                        "ARTIFACT_READY_CONFLICT",
                        "ARTIFACT_READY_DESCRIPTOR_INVALID"));
            }
            return descriptor;
        } catch (ArtifactStoreException failure) {
            if (failure.code() == ArtifactStoreException.Code.COMMIT_UNKNOWN) {
                ArtifactDescriptor recovered = recoverReady(intent, material);
                if (recovered != null) {
                    return recovered;
                }
            }
            throw new WorkerFailure(artifactFailure(failure));
        }
    }

    /**
     * complete/stage 返回提交结果未知时，只读核对同一 binding。READY 且 descriptor
     * 完整相同才算恢复成功；PENDING/NOT_FOUND 交给稳定重试，绝不猜测成功。
     */
    private ArtifactDescriptor recoverReady(
            WriteIntent intent,
            FinalizationCheckpoint.ArtifactMaterial material) throws WorkerFailure {
        try {
            StatResult stat = artifactStore.stat(intent.binding());
            if (stat.state() == StoreState.READY) {
                ArtifactDescriptor descriptor = stat.artifact();
                if (descriptor != null
                        && descriptor.intent().equals(intent)
                        && descriptor.content().equals(material.claim())) {
                    LOGGER.log(Level.INFO,
                            "Artifact 提交结果未知后已由只读状态恢复为 READY");
                    return descriptor;
                }
                throw new WorkerFailure(stableFailure(
                        "STORAGE",
                        "ARTIFACT_READY_CONFLICT",
                        false,
                        "ARTIFACT_READY_CONFLICT",
                        "ARTIFACT_READY_DESCRIPTOR_INVALID"));
            }
            if (stat.state() == StoreState.DELETED
                    || stat.state() == StoreState.DELETING) {
                throw new WorkerFailure(stableFailure(
                        "STORAGE",
                        "ARTIFACT_DELETED_DURING_FINALIZATION",
                        false,
                        "ARTIFACT_DELETED_DURING_FINALIZATION",
                        "ARTIFACT_STORAGE_STATE_INVALID"));
            }
            return null;
        } catch (ArtifactStoreException recoveryFailure) {
            // 读恢复本身失败时，使用恢复操作的关闭式分类；两次异常正文均不记录。
            throw new WorkerFailure(artifactFailure(recoveryFailure));
        } catch (WorkerFailure classified) {
            throw classified;
        }
    }

    private ItemResult executeCompletion(
            WorkflowStep step,
            CompletionRequest request) {
        FinalizationCompletionUnitOfWork.Result result = completion.complete(request);
        return handleTerminalExecutionResult(
                step,
                FinalizationTerminalOperationMarker.Kind.SUCCESS,
                request,
                null,
                result);
    }

    private ItemResult executeFailure(
            WorkflowStep step,
            FailureRequest request) {
        FinalizationCompletionUnitOfWork.Result result = completion.fail(request);
        return handleTerminalExecutionResult(
                step,
                FinalizationTerminalOperationMarker.Kind.FAILURE,
                null,
                request,
                result);
    }

    /**
     * 处理已经有 durable marker 保护的终态写入结果。
     *
     * <p>从此处开始禁止把任何 Completion 故障重新解释为普通业务失败；否则成功
     * marker 可能在重试中被反转为 FAILED。存储暂不可用或提交结果仍未知时保留
     * marker，等待后续 owner 仅执行只读恢复。</p>
     */
    private ItemResult handleTerminalExecutionResult(
            WorkflowStep step,
            FinalizationTerminalOperationMarker.Kind kind,
            CompletionRequest completionRequest,
            FailureRequest failureRequest,
            FinalizationCompletionUnitOfWork.Result result) {
        ItemResult terminal = terminalSuccessOrStableFailure(step, kind, result);
        if (terminal != null) {
            return terminal;
        }
        FinalizationCompletionUnitOfWork.Failure failure =
                (FinalizationCompletionUnitOfWork.Failure) result;
        if (failure.code() == ErrorCode.OUTCOME_UNKNOWN) {
            FinalizationCompletionUnitOfWork.Result recovered =
                    kind == FinalizationTerminalOperationMarker.Kind.SUCCESS
                            ? completion.recoverCompletion(completionRequest)
                            : completion.recoverFailure(failureRequest);
            terminal = terminalSuccessOrStableFailure(step, kind, recovered);
            if (terminal != null) {
                return terminal;
            }
            FinalizationCompletionUnitOfWork.Failure recoveryFailure =
                    (FinalizationCompletionUnitOfWork.Failure) recovered;
            if (recoveryFailure.code() == ErrorCode.STORAGE_UNAVAILABLE
                    || recoveryFailure.code() == ErrorCode.OUTCOME_UNKNOWN
                    || recoveryFailure.code() == ErrorCode.COMPLETION_NOT_FOUND) {
                LOGGER.log(Level.WARNING,
                        "终态提交尚未得到确定结论，保留 marker code={0}",
                        recoveryFailure.code());
                return item(step, ItemStatus.OUTCOME_UNRESOLVED,
                        recoveryFailure.code().name());
            }
            return completionFailureAfterRecovery(step, recoveryFailure.code());
        }
        if (failure.code() == ErrorCode.STORAGE_UNAVAILABLE) {
            LOGGER.log(Level.WARNING,
                    "终态事务暂不可写，保留 marker 等待下一轮恢复");
            return item(step, ItemStatus.OUTCOME_UNRESOLVED,
                    failure.code().name());
        }
        if (isLeaseFailure(failure.code())) {
            return completionFenceFailure(step, failure.code());
        }
        LOGGER.log(Level.SEVERE,
                "已冻结终态请求被稳定事实拒绝，关闭式停止 reason={0}",
                failure.code());
        return item(step, ItemStatus.INCONSISTENT,
                "TERMINALIZATION_" + failure.code().name());
    }

    private ItemResult terminalSuccessOrStableFailure(
            WorkflowStep step,
            FinalizationTerminalOperationMarker.Kind kind,
            FinalizationCompletionUnitOfWork.Result result) {
        if (!(result instanceof FinalizationCompletionUnitOfWork.Success success)) {
            return null;
        }
        if (kind == FinalizationTerminalOperationMarker.Kind.SUCCESS) {
            if (!isCompletionSuccess(success.code())) {
                LOGGER.log(Level.SEVERE,
                        "成功终态请求返回了错误终态类别 code={0}", success.code());
                return item(step, ItemStatus.INCONSISTENT,
                        "COMPLETION_SUCCESS_CLASS_INVALID");
            }
            LOGGER.log(Level.INFO, "最终收口成功确认 code={0}", success.code());
            return item(step, ItemStatus.COMPLETED, success.code().name());
        }
        if (!isFailureSuccess(success.code())) {
            LOGGER.log(Level.SEVERE,
                    "失败终态请求返回了错误终态类别 code={0}", success.code());
            return item(step, ItemStatus.INCONSISTENT,
                    "FAILURE_SUCCESS_CLASS_INVALID");
        }
        LOGGER.log(Level.WARNING,
                "最终收口已原子进入 FAILED/DEAD code={0}", success.code());
        return item(step, ItemStatus.FAILED_DEAD, success.code().name());
    }

    private ItemResult handleBusinessFailure(
            WorkflowStep step,
            LeaseContext lease,
            FinalizationCheckpoint checkpoint,
            List<ArtifactDescriptor> readyArtifacts,
            FailureDetail failure) {
        boolean maxReached = step.attempt() >= config.maxAttempts() - 1;
        if (failure.terminal().retryable() && !maxReached) {
            return scheduleRetry(step, lease, failure);
        }
        return terminalizeFailure(step, lease, checkpoint, readyArtifacts, failure);
    }

    private ItemResult scheduleRetry(
            WorkflowStep step,
            LeaseContext lease,
            FailureDetail failure) {
        ItemResult leaseStop = ensureLeaseOrStop(step, lease);
        if (leaseStop != null) {
            return leaseStop;
        }
        Instant failedAt = now();
        Instant nextAttemptAt;
        try {
            nextAttemptAt = failedAt.plus(config.retryDelay());
        } catch (RuntimeException overflow) {
            return item(step, ItemStatus.BLOCKED, "RETRY_TIME_INVALID");
        }
        return recordPendingRetry(
                step,
                lease.current(),
                failedAt,
                nextAttemptAt,
                failure.terminal().code(),
                failure.terminal().workflowSummary());
    }

    private ItemResult terminalizeFailure(
            WorkflowStep step,
            LeaseContext lease,
            FinalizationCheckpoint checkpoint,
            List<ArtifactDescriptor> readyArtifacts,
            FailureDetail failure) {
        ItemResult leaseStop = ensureLeaseOrStop(step, lease);
        if (leaseStop != null) {
            return leaseStop;
        }
        Instant failedAt = now();
        if (!insideLease(lease.current(), failedAt)) {
            return leaseFailure(step, "LEASE_EXPIRED");
        }
        FailureRequest request = new FailureRequest(
                lease.current(),
                checkpoint,
                readyArtifacts,
                failure.terminal(),
                failedAt,
                step.attempt());
        FinalizationTerminalOperationMarker marker;
        try {
            marker = FinalizationTerminalOperationMarker.forFailure(request);
        } catch (IllegalArgumentException invalidMarker) {
            LOGGER.log(Level.SEVERE,
                    "失败终态请求无法 canonical 编码，拒绝调用 Completion");
            return item(step, ItemStatus.INCONSISTENT,
                    TERMINAL_OPERATION_MARKER_INVALID);
        }
        ItemResult markerStop = persistTerminalMarker(
                step,
                lease,
                step.errorCode(),
                step.errorSummary(),
                marker);
        if (markerStop != null) {
            return markerStop;
        }
        return executeFailure(step, request);
    }

    private ItemResult rotateAndExecuteTerminal(
            WorkflowStep step,
            LeaseContext lease,
            FinalizationCheckpoint checkpoint,
            FinalizationTerminalOperationMarker previous) {
        Instant terminalAt = now();
        if (!insideLease(lease.current(), terminalAt)) {
            return leaseFailure(step, "LEASE_EXPIRED");
        }
        try {
            if (previous.kind() == FinalizationTerminalOperationMarker.Kind.SUCCESS) {
                CompletionRequest request = new CompletionRequest(
                        lease.current(),
                        checkpoint,
                        previous.readyArtifacts(),
                        terminalAt);
                FinalizationTerminalOperationMarker next =
                        FinalizationTerminalOperationMarker.forCompletion(
                                request, step.attempt());
                ItemResult markerStop = persistTerminalMarker(
                        step,
                        lease,
                        previous.markerCode(),
                        previous.encodedSummary(),
                        next);
                return markerStop == null
                        ? executeCompletion(step, request)
                        : markerStop;
            }
            FailureRequest request = new FailureRequest(
                    lease.current(),
                    checkpoint,
                    previous.readyArtifacts(),
                    previous.failure(),
                    terminalAt,
                    step.attempt());
            FinalizationTerminalOperationMarker next =
                    FinalizationTerminalOperationMarker.forFailure(request);
            ItemResult markerStop = persistTerminalMarker(
                    step,
                    lease,
                    previous.markerCode(),
                    previous.encodedSummary(),
                    next);
            return markerStop == null ? executeFailure(step, request) : markerStop;
        } catch (IllegalArgumentException invalidMarker) {
            LOGGER.log(Level.SEVERE,
                    "跨 lease 终态请求无法 canonical 重建，关闭式停止");
            return item(step, ItemStatus.INCONSISTENT,
                    TERMINAL_OPERATION_MARKER_INVALID);
        }
    }

    /**
     * 用当前完整 lease 栅栏持久化终态 marker，并处理 commit acknowledgement unknown。
     *
     * <p>返回 {@code null} 表示 marker 已由写结果或新只读会话精确确认，调用方才可
     * 进入 Completion 工作单元。任何未确认状态都会返回稳定 ItemResult，绝不把
     * “数据库可能写入”猜测成成功，也不会调用 Workflow fail 清除 marker。</p>
     */
    private ItemResult persistTerminalMarker(
            WorkflowStep step,
            LeaseContext lease,
            String expectedCode,
            String expectedSummary,
            FinalizationTerminalOperationMarker marker) {
        PersistLeaseMarkerRequest request = new PersistLeaseMarkerRequest(
                lease.current(),
                marker.terminalAt(),
                expectedCode,
                expectedSummary,
                marker.markerCode(),
                marker.encodedSummary());
        PersistLeaseMarkerResult persisted = workflowStore.persistLeaseMarker(request);
        if (persisted.status()
                == WorkflowStepStore.PersistLeaseMarkerStatus.OUTCOME_UNKNOWN) {
            persisted = workflowStore.recoverLeaseMarker(request);
        }
        return switch (persisted.status()) {
            case PERSISTED, REPLAYED -> null;
            case STORAGE_UNAVAILABLE, OUTCOME_UNKNOWN -> {
                LOGGER.log(Level.WARNING,
                        "终态 marker 尚未得到确定持久化结论 status={0}",
                        persisted.status());
                yield item(step, ItemStatus.OUTCOME_UNRESOLVED,
                        persisted.status().name());
            }
            case NOT_FOUND, NOT_OWNER, LEASE_EXPIRED ->
                    leaseFailure(step, persisted.status().name());
            case ALREADY_COMPLETED, ALREADY_DEAD -> item(
                    step, ItemStatus.TERMINAL_CONFLICT, persisted.status().name());
            case CONFLICT -> {
                LOGGER.log(Level.SEVERE,
                        "终态 marker CAS 发现不同持久请求，关闭式停止");
                yield item(step, ItemStatus.INCONSISTENT, "TERMINAL_MARKER_CONFLICT");
            }
        };
    }

    private ItemResult recordPendingRetry(
            WorkflowStep step,
            LeaseToken lease,
            Instant failedAt,
            Instant nextAttemptAt,
            String errorCode,
            String errorSummary) {
        int releaseOnlyMax;
        try {
            releaseOnlyMax = Math.addExact(step.attempt(), 2);
        } catch (ArithmeticException exhausted) {
            LOGGER.log(Level.SEVERE,
                    "Workflow attempt 已耗尽整数值域，无法安全释放为 PENDING");
            return item(step, ItemStatus.BLOCKED, "ATTEMPT_RANGE_EXHAUSTED");
        }
        FailResult failed = workflowStore.fail(
                lease,
                failedAt,
                nextAttemptAt,
                errorCode,
                errorSummary,
                releaseOnlyMax);
        return switch (failed.status()) {
            case RETRY_SCHEDULED -> item(
                    step, ItemStatus.RETRY_SCHEDULED, errorCode);
            case NOT_FOUND, NOT_OWNER, LEASE_EXPIRED -> leaseFailure(
                    step, failed.status().name());
            case ALREADY_COMPLETED, ALREADY_DEAD -> item(
                    step, ItemStatus.TERMINAL_CONFLICT, failed.status().name());
            case DEAD -> {
                // 按 releaseOnlyMax 计算，本分支不应由合规 Store 产生。拒绝把它
                // 伪装成完整 FAILED，因为 Meeting/Manifest 未在同一事务中更新。
                LOGGER.log(Level.SEVERE,
                        "Workflow Store 非预期单独写入 DEAD，检测到半终态风险");
                yield item(step, ItemStatus.INCONSISTENT, "UNEXPECTED_WORKFLOW_DEAD");
            }
        };
    }

    private ItemResult ensureLeaseOrStop(WorkflowStep step, LeaseContext lease) {
        LeaseGuard guard = lease.ensureUsable();
        return switch (guard.status()) {
            case ACTIVE -> null;
            case LOST -> leaseFailure(step, guard.reasonCode());
            case TERMINAL -> item(
                    step, ItemStatus.TERMINAL_CONFLICT, guard.reasonCode());
            case BLOCKED -> item(step, ItemStatus.INCONSISTENT, guard.reasonCode());
        };
    }

    private ItemResult completionFailureAfterRecovery(
            WorkflowStep step,
            ErrorCode code) {
        if (isLeaseFailure(code)) {
            return completionFenceFailure(step, code);
        }
        LOGGER.log(Level.SEVERE,
                "Completion 只读恢复发现稳定冲突，拒绝继续写终态 reason={0}", code);
        return item(step, ItemStatus.INCONSISTENT,
                "RECOVERY_" + code.name());
    }

    private static boolean isLeaseFailure(ErrorCode code) {
        return code == ErrorCode.WORKFLOW_NOT_FOUND
                || code == ErrorCode.LEASE_NOT_OWNER
                || code == ErrorCode.LEASE_EXPIRED
                || code == ErrorCode.WORKFLOW_TERMINAL_CONFLICT;
    }

    private static boolean isCompletionSuccess(
            FinalizationCompletionUnitOfWork.SuccessCode code) {
        return code == FinalizationCompletionUnitOfWork.SuccessCode.COMPLETED
                || code == FinalizationCompletionUnitOfWork.SuccessCode.COMPLETION_REPLAYED;
    }

    private static boolean isFailureSuccess(
            FinalizationCompletionUnitOfWork.SuccessCode code) {
        return code == FinalizationCompletionUnitOfWork.SuccessCode.FAILED
                || code == FinalizationCompletionUnitOfWork.SuccessCode.FAILURE_REPLAYED;
    }

    private static boolean insideLease(LeaseToken lease, Instant operationAt) {
        return !operationAt.isBefore(lease.claimedAt())
                && operationAt.isBefore(lease.expiresAt());
    }

    private ItemResult completionFenceFailure(WorkflowStep step, ErrorCode code) {
        if (code == ErrorCode.WORKFLOW_TERMINAL_CONFLICT) {
            LOGGER.log(Level.WARNING,
                    "最终收口观察到 Workflow 已进入其它终态，停止后续写入");
            return item(step, ItemStatus.TERMINAL_CONFLICT, code.name());
        }
        return leaseFailure(step, code.name());
    }

    private static FailureDetail artifactFailure(ArtifactStoreException failure) {
        return stableFailure(
                "STORAGE",
                "ARTIFACT_" + failure.code().name(),
                failure.retryable(),
                "ARTIFACT_" + failure.code().name(),
                "ARTIFACT_STORE_OPERATION_FAILED");
    }

    private static FailureDetail entitlementFailure(EntitlementProviderError failure) {
        return stableFailure(
                "INTERNAL",
                "ENTITLEMENT_" + failure.code().name(),
                failure.retryable(),
                "ENTITLEMENT_" + failure.code().name(),
                "ENTITLEMENT_COMMIT_FAILED");
    }

    private static FailureDetail stableFailure(
            String domain,
            String code,
            boolean retryable,
            String diagnosticId,
            String summary) {
        return new FailureDetail(new TerminalFailure(
                domain, code, retryable, diagnosticId, summary));
    }

    private static boolean committedUsageMatches(
            FinalizationCheckpoint checkpoint,
            UsageOperationResult result) {
        CommitUsageRequest request = checkpoint.commitUsageRequest();
        UsageReservation reservation = result.reservation();
        long expectedCommittedVersion;
        try {
            expectedCommittedVersion = Math.addExact(request.expectedVersion(), 1L);
        } catch (ArithmeticException overflow) {
            return false;
        }
        return request.deploymentScope().equals(result.deploymentScope())
                && request.subjectScope().equals(result.subjectScope())
                && request.operationScope().equals(result.operationScope())
                && UsageOperationScopes.COMMIT.equals(result.operationScope())
                && request.idempotencyKey().equals(result.idempotencyKey())
                && request.requestDigest().equals(result.requestDigest())
                && request.reservationId().equals(reservation.reservationId())
                && request.deploymentScope().equals(reservation.deploymentScope())
                && request.subjectScope().equals(reservation.subjectScope())
                && reservation.state() == UsageReservation.State.COMMITTED
                && reservation.isBound()
                && checkpoint.meetingId().equals(reservation.expectedMeetingId())
                && checkpoint.meetingId().equals(reservation.boundMeetingId())
                && request.actual().equals(reservation.actualUsage())
                && reservation.version() == expectedCommittedVersion;
    }

    private ItemResult leaseFailure(WorkflowStep step, String reason) {
        LOGGER.log(Level.WARNING,
                "最终收口 lease 已失效，停止后续副作用 reason={0}", reason);
        ItemStatus status = "LEASE_EXPIRED".equals(reason)
                ? ItemStatus.LEASE_EXPIRED : ItemStatus.LEASE_LOST;
        return item(step, status, reason);
    }

    private static ItemResult item(
            WorkflowStep step,
            ItemStatus status,
            String reasonCode) {
        return new ItemResult(step.key(), status, reasonCode);
    }

    /**
     * 固定运行参数。renewBefore 必须小于 leaseDuration；进入该剩余窗口后，Worker
     * 会在下一个外部操作前显式续租。续租由当前线程完成，不存在第二条后台链路。
     */
    public record Config(
            String leaseOwner,
            Clock clock,
            Duration leaseDuration,
            Duration renewBefore,
            Duration retryDelay,
            int batchLimit,
            int maxAttempts) {
        public Config {
            requireText("leaseOwner", leaseOwner);
            Objects.requireNonNull(clock, "clock");
            requirePositive("leaseDuration", leaseDuration);
            Objects.requireNonNull(renewBefore, "renewBefore");
            Objects.requireNonNull(retryDelay, "retryDelay");
            requireMicrosecondDuration("leaseDuration", leaseDuration);
            requireMicrosecondDuration("renewBefore", renewBefore);
            requireMicrosecondDuration("retryDelay", retryDelay);
            if (renewBefore.isNegative()
                    || renewBefore.compareTo(leaseDuration) >= 0) {
                throw new IllegalArgumentException(
                        "renewBefore must be non-negative and shorter than leaseDuration");
            }
            if (retryDelay.isNegative()) {
                throw new IllegalArgumentException("retryDelay must not be negative");
            }
            if (batchLimit <= 0) {
                throw new IllegalArgumentException("batchLimit must be positive");
            }
            if (maxAttempts <= 0) {
                throw new IllegalArgumentException("maxAttempts must be positive");
            }
            Instant probe = clock.instant().truncatedTo(ChronoUnit.MICROS);
            try {
                probe.plus(leaseDuration);
                probe.plus(retryDelay);
            } catch (RuntimeException invalidRange) {
                throw new IllegalArgumentException(
                        "configured duration exceeds Instant range", invalidRange);
            }
        }

        public static Config defaults(String leaseOwner) {
            return new Config(
                    leaseOwner,
                    Clock.systemUTC(),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(5),
                    10,
                    3);
        }

        @Override
        public String toString() {
            return "MockFinalizationWorker.Config[leaseOwner=redacted, clockPresent=true"
                    + ", leaseDuration=" + leaseDuration
                    + ", renewBefore=" + renewBefore
                    + ", retryDelay=" + retryDelay
                    + ", batchLimit=" + batchLimit
                    + ", maxAttempts=" + maxAttempts + ']';
        }
    }

    /** 一次调用的稳定汇总；items 数量不得超过实际领取数量。 */
    public record RunResult(int claimedCount, List<ItemResult> items) {
        public RunResult {
            if (claimedCount < 0) {
                throw new IllegalArgumentException("claimedCount must not be negative");
            }
            Objects.requireNonNull(items, "items");
            if (items.size() != claimedCount) {
                throw new IllegalArgumentException(
                        "items size must equal claimedCount");
            }
            items = List.copyOf(items);
        }

        public boolean idle() {
            return claimedCount == 0;
        }
    }

    /** 单个已领取步骤的稳定结果；Key 的诊断输出已在领域对象中脱敏。 */
    public record ItemResult(
            WorkflowStep.Key key,
            ItemStatus status,
            String reasonCode) {
        public ItemResult {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(status, "status");
            requireStableReasonCode(reasonCode);
            if (status == ItemStatus.COMPLETED
                    && !COMPLETED_REASONS.contains(reasonCode)) {
                throw new IllegalArgumentException(
                        "COMPLETED requires a completion success reason");
            }
            if (status == ItemStatus.FAILED_DEAD
                    && !FAILED_REASONS.contains(reasonCode)) {
                throw new IllegalArgumentException(
                        "FAILED_DEAD requires a failure success reason");
            }
        }

        @Override
        public String toString() {
            return "MockFinalizationWorker.ItemResult[key=redacted, status="
                    + status + ", reasonCode=redacted]";
        }
    }

    public enum ItemStatus {
        COMPLETED,
        FAILED_DEAD,
        RETRY_SCHEDULED,
        LEASE_LOST,
        LEASE_EXPIRED,
        TERMINAL_CONFLICT,
        OUTCOME_UNRESOLVED,
        INCONSISTENT,
        BLOCKED
    }

    private Instant now() {
        // JDBC Workflow 与 Completion 契约都要求数据库微秒精度；Worker 在
        // 最外层统一规范，避免驱动静默舍入后 lease 栅栏出现两个时间语义。
        return Objects.requireNonNull(config.clock().instant(), "clock.instant()")
                .truncatedTo(ChronoUnit.MICROS);
    }

    private final class LeaseContext {
        private LeaseToken current;

        private LeaseContext(LeaseToken initial) {
            this.current = Objects.requireNonNull(initial, "initial");
        }

        private LeaseToken current() {
            return current;
        }

        private LeaseGuard ensureUsable() {
            Instant checkedAt = now();
            if (checkedAt.isBefore(current.claimedAt())) {
                return new LeaseGuard(LeaseGuardStatus.BLOCKED, "CLOCK_MOVED_BACKWARDS");
            }
            if (!checkedAt.isBefore(current.expiresAt())) {
                return new LeaseGuard(LeaseGuardStatus.LOST, "LEASE_EXPIRED");
            }
            Duration remaining = Duration.between(checkedAt, current.expiresAt());
            if (remaining.compareTo(config.renewBefore()) > 0) {
                return new LeaseGuard(LeaseGuardStatus.ACTIVE, "ACTIVE");
            }
            WorkflowStepStore.RenewResult renewed = workflowStore.renew(
                    current, checkedAt, config.leaseDuration());
            return switch (renewed.status()) {
                case RENEWED -> {
                    current = renewed.lease();
                    yield new LeaseGuard(LeaseGuardStatus.ACTIVE, "RENEWED");
                }
                case ALREADY_COMPLETED, ALREADY_DEAD -> new LeaseGuard(
                        LeaseGuardStatus.TERMINAL, renewed.status().name());
                case NOT_FOUND, NOT_OWNER, LEASE_EXPIRED -> new LeaseGuard(
                        LeaseGuardStatus.LOST, renewed.status().name());
            };
        }
    }

    private record FailureDetail(TerminalFailure terminal) {
        private FailureDetail {
            Objects.requireNonNull(terminal, "terminal");
        }
    }

    private static final class WorkerFailure extends Exception {
        private final FailureDetail detail;

        private WorkerFailure(FailureDetail detail) {
            super("finalization worker operation failed");
            this.detail = Objects.requireNonNull(detail, "detail");
        }

        private FailureDetail detail() {
            return detail;
        }
    }

    private enum LeaseGuardStatus {
        ACTIVE,
        LOST,
        TERMINAL,
        BLOCKED
    }

    private record LeaseGuard(LeaseGuardStatus status, String reasonCode) {
        private LeaseGuard {
            Objects.requireNonNull(status, "status");
            requireStableReasonCode(reasonCode);
        }
    }

    private static void requirePositive(String name, Duration duration) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireMicrosecondDuration(String name, Duration duration) {
        if (duration.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    name + " must use microsecond precision");
        }
    }

    private static void requireStableReasonCode(String value) {
        if (value == null || !STABLE_REASON_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "reasonCode must be an internal stable uppercase code");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
