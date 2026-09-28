package com.czur.reference.application.finalization;

import com.czur.reference.application.finalization.FinalizationCheckpoint.ArtifactMaterial;
import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.protocol.json.FinalizationCheckpointJson;
import com.czur.reference.protocol.json.FinalizationExecutionFactJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 从 Admission 已锁定事实生成完整、可跨进程恢复的 Mock 最终收口计划。
 *
 * <p>Planner 必须接收 09-01 的 {@link FinalizationExecutionFact}，不能在 Worker
 * 重试时重新读取当前 Product Profile 或重新规划 Capability Plan；Reservation
 * 同样必须是 Admission 行锁下读取的已绑定 RESERVED 快照。实现为纯 Java，不读取
 * 数据库、文件系统、当前时钟或配置中心，也不执行任何外部副作用。</p>
 *
 * <p>Workflow key、Artifact ID 和 Entitlement operation key 都是稳定逻辑 identity，
 * 不含 input/execution digest、内容 checksum、Reservation seed 或时钟。输入、执行
 * 事实或 Reservation 漂移仍命中原 identity，再由 checkpoint Definition、Artifact
 * spec 或 commit request digest 比较产生冲突。</p>
 */
public final class DeterministicMockFinalizationPlanner
        implements FinalizationAdmissionPlanner {
    private static final int ARTIFACT_REVISION = 1;
    private static final Set<String> SUPPORTED_ARTIFACT_TYPES = Set.of(
            "transcript.final", "summary.structured");

    /**
     * 冻结完整 checkpoint 和 Workflow Definition。
     *
     * @param deploymentScope Admission 已核对的 Meeting deployment scope
     * @param subjectScope Admission 已核对的 Meeting subject scope
     * @param input 已持久接管的完整 V008 输入事实
     * @param executionFact 创建会议前冻结并在 Admission 中核对的 09-01 执行事实
     * @param lockedReservation Admission 行锁下读取的已绑定 RESERVED Reservation
     */
    @Override
    public Plan derive(
            String deploymentScope,
            String subjectScope,
            FinalizationInputFact input,
            FinalizationExecutionFact executionFact,
            UsageReservation lockedReservation) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(executionFact, "executionFact");
        Objects.requireNonNull(lockedReservation, "lockedReservation");
        if (!input.meetingId().equals(executionFact.meetingId())) {
            throw new IllegalArgumentException(
                    "finalization facts must belong to the same meeting");
        }
        CapabilityPlan capabilityPlan = executionFact.capabilityPlan();
        if (capabilityPlan.failed()) {
            throw new IllegalArgumentException(
                    "failed capability plan cannot produce final artifacts");
        }

        String inputFactDigest = FinalizationCheckpoint.inputFactDigest(input);
        String executionFactDigest = FinalizationExecutionFactJson.sha256(executionFact);
        String sourceFingerprint = removeSha256Prefix(inputFactDigest);
        String executionFingerprint = removeSha256Prefix(executionFactDigest);

        TreeSet<String> artifactTypes = new TreeSet<>();
        for (CapabilityDecision decision : capabilityPlan.decisions()) {
            if (!isExecutable(decision)) {
                continue;
            }
            if (!decision.producedArtifacts().isEmpty()
                    && decision.affectedWorkflows().isEmpty()) {
                throw new IllegalArgumentException(
                        "artifact-producing capability requires explicit workflow mapping");
            }
            for (String artifactType : decision.producedArtifacts()) {
                if (!SUPPORTED_ARTIFACT_TYPES.contains(artifactType)) {
                    throw new IllegalArgumentException(
                            "capability plan contains unsupported mock artifact type");
                }
                artifactTypes.add(artifactType);
            }
        }
        if (artifactTypes.isEmpty()) {
            throw new IllegalArgumentException(
                    "capability plan must explicitly produce at least one mock artifact");
        }

        List<ArtifactMaterial> artifacts = new ArrayList<>(artifactTypes.size());
        for (String artifactType : artifactTypes) {
            artifacts.add(artifact(
                    input,
                    artifactType,
                    sourceFingerprint,
                    executionFingerprint));
        }

        FinalUsageFact finalUsage = FinalUsageFact.fromInput(input);
        FinalizationCommitPlan commitPlan = FinalizationCommitPlan.freeze(
                deploymentScope, subjectScope, finalUsage, lockedReservation);
        FinalizationCheckpoint checkpoint = new FinalizationCheckpoint(
                input.meetingId(),
                input.inputBinding(),
                inputFactDigest,
                executionFactDigest,
                artifacts,
                commitPlan);
        return new Plan(
                checkpoint,
                FinalizationCheckpointJson.workflowStep(checkpoint));
    }

    private static ArtifactMaterial artifact(
            FinalizationInputFact input,
            String artifactType,
            String sourceFingerprint,
            String executionFingerprint) {
        String artifactId = FinalizationCheckpoint.artifactId(
                input.meetingId(), artifactType, ARTIFACT_REVISION);
        byte[] bytes = new MockFinalizationArtifactContent(
                artifactType,
                executionFingerprint,
                input.sourceLanguage(),
                ARTIFACT_REVISION,
                sourceFingerprint).canonicalBytes();
        ContentClaim claim = new ContentClaim(
                bytes.length, FinalizationOperationIdentity.checksum(bytes));
        WriteIntentSpec spec = new WriteIntentSpec(
                artifactId,
                input.meetingId(),
                artifactType,
                MockFinalizationArtifactContent.MEDIA_TYPE,
                input.sourceLanguage(),
                ARTIFACT_REVISION,
                claim.size(),
                claim.checksum());
        return new ArtifactMaterial(spec, bytes, claim);
    }

    private static boolean isExecutable(CapabilityDecision decision) {
        return decision.action() == CapabilityDecision.Action.ENABLE
                || decision.action() == CapabilityDecision.Action.USE_FALLBACK;
    }

    private static String removeSha256Prefix(String digest) {
        return digest.substring("sha256:".length());
    }

    /**
     * 不带副作用的完整 Admission 输出。所有便利 getter 均只读取 checkpoint。
     */
    public record Plan(
            FinalizationCheckpoint checkpoint,
            WorkflowStep workflowStep) {
        public Plan {
            Objects.requireNonNull(checkpoint, "checkpoint");
            Objects.requireNonNull(workflowStep, "workflowStep");
            WorkflowStep rebuilt = FinalizationCheckpointJson.workflowStep(checkpoint);
            if (!sameDefinition(rebuilt, workflowStep)) {
                throw new IllegalArgumentException(
                        "workflow definition must match finalization checkpoint");
            }
        }

        public FinalUsageFact finalUsage() {
            return checkpoint.finalUsageFact();
        }

        public List<ArtifactMaterial> artifacts() {
            return checkpoint.artifacts();
        }

        public CommitUsageRequest commitUsageRequest() {
            return checkpoint.commitUsageRequest();
        }

        @Override
        public String toString() {
            return "DeterministicMockFinalizationPlanner.Plan[checkpoint=redacted"
                    + ", workflowStep=redacted, artifactCount="
                    + checkpoint.artifacts().size() + ']';
        }

        private static boolean sameDefinition(WorkflowStep left, WorkflowStep right) {
            return left.key().equals(right.key())
                    && left.checkpointType().equals(right.checkpointType())
                    && left.canonicalPayload().equals(right.canonicalPayload())
                    && left.contentDigest().equals(right.contentDigest())
                    && left.status() == right.status()
                    && left.attempt() == right.attempt()
                    && Objects.equals(left.nextAttemptAt(), right.nextAttemptAt())
                    && left.leaseOwner() == null && right.leaseOwner() == null
                    && left.leaseUntil() == null && right.leaseUntil() == null
                    && left.leaseVersion() == right.leaseVersion()
                    && left.errorCode() == null && right.errorCode() == null
                    && left.errorSummary() == null && right.errorSummary() == null
                    && left.updatedAt().equals(right.updatedAt());
        }
    }
}
