package com.czur.reference.application.finalization;

import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.capability.ProductProfile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 从持久化输入事实和本场已冻结能力计划派生 P2 确定性 Mock 收口计划。
 *
 * <p>本类是纯 Java 计划组件，不读取时钟、数据库、模型或文件系统，也不执行
 * Manifest 发布、Meeting 状态推进、Entitlement usage 提交或 Workflow lease 操作。
 * 它只冻结后续 Worker 可以重放的四类值：最终用量事实、Workflow Definition、
 * Artifact 逻辑身份以及 Mock 内容。T14-09 可以复用这些值而无需复制派生公式。</p>
 *
 * <p>产物集合完全取自 {@link CapabilityPlan} 中本场可执行决策的
 * {@link CapabilityDecision#producedArtifacts()}，并核对这些值确实来自传入
 * {@link ProductProfile} 的显式映射。实现不会根据 capability 名称猜测产物；
 * {@code DISABLE}/{@code FAIL_MEETING} 决策也不会生成产物。当前文件系统 Mock 只支持
 * {@code transcript.final} 和 {@code summary.structured}，其它显式产物会关闭式失败，
 * 而不是静默丢弃。</p>
 *
 * <p>逻辑 identity 刻意不包含输入摘要、能力计划摘要或内容 checksum：stepKey 只由
 * meeting identity 派生，artifactId 只由 meeting、artifact type 和 revision 派生。
 * 因此同一逻辑工作发生 source/profile/plan 漂移时会命中同一个 Workflow Key 或
 * artifactId，并由完整 Definition/spec 比较返回 CONFLICT；不会因为把易漂移 digest
 * 塞进 identity 而旁路出第二份“赢家”。</p>
 */
public final class DeterministicMockFinalizationPlanner {
    private static final String STEP_TYPE = "mock-finalization";
    private static final String CHECKPOINT_TYPE = "mock-finalization-plan/v1";
    private static final String MEDIA_TYPE = "application/json";
    private static final int ARTIFACT_REVISION = 1;
    private static final Set<String> SUPPORTED_ARTIFACT_TYPES = Set.of(
            "transcript.final", "summary.structured");

    /**
     * 派生一份完整且可跨进程重建的计划。
     *
     * @param input 已从 V008 等持久事实源读取的完整输入事实
     * @param profile 创建会议时冻结身份的 Product Profile 配置
     * @param capabilityPlan 本场已经冻结的可执行能力计划
     * @return 不执行任何外部副作用的确定性计划
     */
    public Plan derive(
            FinalizationInputFact input,
            ProductProfile profile,
            CapabilityPlan capabilityPlan) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(capabilityPlan, "capabilityPlan");
        requirePersistentTimestamp(input);
        validatePlanMatchesProfile(profile, capabilityPlan);
        if (capabilityPlan.failed()) {
            throw new IllegalArgumentException(
                    "failed capability plan cannot produce final artifacts");
        }

        List<CapabilityDecision> decisions = capabilityPlan.decisions().stream()
                .sorted((left, right) -> left.capability().compareTo(right.capability()))
                .toList();
        TreeSet<String> artifactTypes = new TreeSet<>();
        for (CapabilityDecision decision : decisions) {
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

        String sourceFingerprint = sourceFingerprint(input);
        String executionFingerprint = executionFingerprint(profile, capabilityPlan, decisions);
        List<ArtifactWritePlan> artifacts = new ArrayList<>(artifactTypes.size());
        for (String artifactType : artifactTypes) {
            artifacts.add(artifact(
                    input, profile, capabilityPlan, artifactType,
                    sourceFingerprint, executionFingerprint));
        }

        String canonicalPayload = canonicalPayload(
                profile, capabilityPlan, sourceFingerprint, executionFingerprint, artifacts);
        String checkpointDigest = checksum(canonicalPayload.getBytes(StandardCharsets.UTF_8));
        WorkflowStep workflowStep = WorkflowStep.pending(
                new WorkflowStep.Key(
                        input.meetingId(),
                        STEP_TYPE,
                        "mock_finalize_v1_" + digest(
                                "mock-finalization-step-key/v1", input.meetingId())),
                CHECKPOINT_TYPE,
                canonicalPayload,
                checkpointDigest,
                input.acceptedAt(),
                input.acceptedAt());
        return new Plan(
                FinalUsageFact.fromInput(input),
                workflowStep,
                artifacts);
    }

    private static ArtifactWritePlan artifact(
            FinalizationInputFact input,
            ProductProfile profile,
            CapabilityPlan capabilityPlan,
            String artifactType,
            String sourceFingerprint,
            String executionFingerprint) {
        String artifactId = "art_mock_v1_" + digest(
                "mock-finalization-artifact-id/v1",
                input.meetingId(), artifactType, Integer.toString(ARTIFACT_REVISION));
        String canonicalContent = "{\"artifactType\":\"" + artifactType
                + "\",\"mock\":true,\"planRevision\":"
                + capabilityPlan.planRevision()
                + ",\"profileRevision\":" + profile.revision()
                + ",\"schemaVersion\":\"mock-finalization/v1\""
                + ",\"sourceFingerprint\":\"" + sourceFingerprint
                + "\",\"executionFingerprint\":\"" + executionFingerprint
                + "\"}\n";
        byte[] bytes = canonicalContent.getBytes(StandardCharsets.UTF_8);
        ContentClaim claim = new ContentClaim(bytes.length, checksum(bytes));
        WriteIntentSpec spec = new WriteIntentSpec(
                artifactId,
                input.meetingId(),
                artifactType,
                MEDIA_TYPE,
                input.sourceLanguage(),
                ARTIFACT_REVISION,
                claim.size(),
                claim.checksum());
        return new ArtifactWritePlan(spec, canonicalContent, claim);
    }

    private static String canonicalPayload(
            ProductProfile profile,
            CapabilityPlan capabilityPlan,
            String sourceFingerprint,
            String executionFingerprint,
            List<ArtifactWritePlan> artifacts) {
        StringBuilder payload = new StringBuilder()
                .append("schema=mock-finalization-plan/v1\n")
                .append("profileIdentity=")
                .append(digest("mock-finalization-profile-identity/v1",
                        profile.id(), Integer.toString(profile.revision())))
                .append('\n')
                .append("planRevision=").append(capabilityPlan.planRevision()).append('\n')
                .append("sourceFingerprint=").append(sourceFingerprint).append('\n')
                .append("executionFingerprint=").append(executionFingerprint).append('\n')
                .append("artifactCount=").append(artifacts.size()).append('\n');
        for (ArtifactWritePlan artifact : artifacts) {
            WriteIntentSpec spec = artifact.spec();
            payload.append("artifact=")
                    .append(spec.artifactType()).append('|')
                    .append(spec.artifactId()).append('|')
                    .append(spec.revision()).append('|')
                    .append(spec.expectedSize()).append('|')
                    .append(spec.expectedChecksum()).append('\n');
        }
        return payload.toString();
    }

    private static String sourceFingerprint(FinalizationInputFact input) {
        List<String> fields = new ArrayList<>();
        fields.add(input.meetingId());
        fields.add(input.inputBinding());
        fields.add(Long.toString(input.lastAcceptedSequence()));
        fields.add(Long.toString(input.audioDurationMs()));
        fields.add(input.sourceLanguage());
        fields.add(Integer.toString(input.targetLanguages().size()));
        fields.addAll(input.targetLanguages());
        fields.add(input.acceptedAt().toString());
        fields.add(input.contentDigest());
        return digest("mock-finalization-source/v1", fields.toArray(String[]::new));
    }

    private static String executionFingerprint(
            ProductProfile profile,
            CapabilityPlan capabilityPlan,
            List<CapabilityDecision> decisions) {
        List<String> fields = new ArrayList<>();
        fields.add(profile.id());
        fields.add(Integer.toString(profile.revision()));
        fields.add(Long.toString(capabilityPlan.planRevision()));
        fields.add(Integer.toString(decisions.size()));
        for (CapabilityDecision decision : decisions) {
            fields.add(decision.capability());
            fields.add(decision.availability().name());
            fields.add(Boolean.toString(decision.requiredByProfile()));
            fields.add(decision.action().name());
            fields.add(Objects.toString(decision.fallbackCapability(), "<absent>"));
            fields.add(Integer.toString(decision.affectedWorkflows().size()));
            fields.addAll(decision.affectedWorkflows());
            fields.add(Integer.toString(decision.producedArtifacts().size()));
            fields.addAll(decision.producedArtifacts());
            fields.add(Objects.toString(decision.warningCode(), "<absent>"));
            fields.add(Long.toString(decision.planRevision()));
        }
        return digest("mock-finalization-execution/v1", fields.toArray(String[]::new));
    }

    private static void validatePlanMatchesProfile(
            ProductProfile profile,
            CapabilityPlan capabilityPlan) {
        Set<String> seen = new HashSet<>();
        for (CapabilityDecision decision : capabilityPlan.decisions()) {
            if (!seen.add(decision.capability())) {
                throw new IllegalArgumentException(
                        "capability plan must contain one decision per capability");
            }
            if (!profile.allCapabilities().contains(decision.capability())) {
                throw new IllegalArgumentException(
                        "capability plan contains capability outside profile");
            }
            String mappingCapability = decision.action()
                    == CapabilityDecision.Action.USE_FALLBACK
                    ? decision.fallbackCapability() : decision.capability();
            if (mappingCapability == null
                    || !profile.allCapabilities().contains(mappingCapability)
                    || !profile.affectedWorkflowsOf(mappingCapability)
                            .equals(decision.affectedWorkflows())
                    || !profile.producedArtifactsOf(mappingCapability)
                            .equals(decision.producedArtifacts())) {
                throw new IllegalArgumentException(
                        "capability plan execution mapping does not match profile");
            }
        }
        if (!seen.equals(profile.allCapabilities())) {
            throw new IllegalArgumentException(
                    "capability plan does not cover complete profile");
        }
    }

    private static boolean isExecutable(CapabilityDecision decision) {
        return decision.action() == CapabilityDecision.Action.ENABLE
                || decision.action() == CapabilityDecision.Action.USE_FALLBACK;
    }

    private static void requirePersistentTimestamp(FinalizationInputFact input) {
        if (input.acceptedAt().getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "acceptedAt must use PostgreSQL microsecond precision");
        }
    }

    private static String checksum(byte[] content) {
        return "sha256:" + HexFormat.of().formatHex(sha256().digest(content));
    }

    /** 长度前缀避免字段连接歧义；domain separator 保证不同 identity 空间不互换。 */
    private static String digest(String domain, String... fields) {
        MessageDigest digest = sha256();
        update(digest, domain);
        for (String field : fields) {
            update(digest, Objects.requireNonNull(field, "digest field"));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    /** 不带外部副作用、可由任意 Worker 进程重新派生的完整计划。 */
    public record Plan(
            FinalUsageFact finalUsage,
            WorkflowStep workflowStep,
            List<ArtifactWritePlan> artifacts) {
        public Plan {
            Objects.requireNonNull(finalUsage, "finalUsage");
            Objects.requireNonNull(workflowStep, "workflowStep");
            artifacts = List.copyOf(artifacts);
            if (artifacts.isEmpty()) {
                throw new IllegalArgumentException("artifacts must not be empty");
            }
        }

        @Override
        public String toString() {
            return "DeterministicMockFinalizationPlanner.Plan[finalUsage=redacted"
                    + ", workflowStep=redacted, artifactCount=" + artifacts.size() + ']';
        }
    }

    /** 单份确定性 Mock 内容及其不可变 Artifact Store spec。 */
    public record ArtifactWritePlan(
            WriteIntentSpec spec,
            String canonicalContent,
            ContentClaim claim) {
        public ArtifactWritePlan {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(canonicalContent, "canonicalContent");
            Objects.requireNonNull(claim, "claim");
            byte[] bytes = canonicalContent.getBytes(StandardCharsets.UTF_8);
            if (bytes.length != claim.size()
                    || !checksum(bytes).equals(claim.checksum())
                    || claim.size() != spec.expectedSize()
                    || !claim.checksum().equals(spec.expectedChecksum())) {
                throw new IllegalArgumentException(
                        "canonical content, claim and spec must match");
            }
        }

        /** 每次调用都返回从位置零开始的新流，调用方拥有并关闭该流。 */
        public InputStream openContent() {
            return new ByteArrayInputStream(canonicalContent.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String toString() {
            return "ArtifactWritePlan[spec=redacted, content=redacted, claim=redacted]";
        }
    }
}
