package com.czur.reference.application.finalization;

import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Mock 最终收口 Workflow 的完整、不可变恢复 checkpoint。
 *
 * <p>checkpoint 不重复内嵌 V008 或 execution fact 正文。它只保存稳定 input binding、
 * 覆盖 V008 全字段的摘要和 execution canonical JSON 摘要；输入/Profile/Plan 漂移会
 * 保持原 Workflow/Artifact 逻辑 identity，再由完整 Definition/spec 比较产生冲突。
 * Artifact 则保存完整 {@link WriteIntentSpec}、精确内容字节和 {@link ContentClaim}，
 * 权益部分保存完整 {@link FinalizationCommitPlan}，因此 Worker 无需原始事实即可恢复
 * Artifact 写入、最终用量事实和 commit 请求。</p>
 *
 * <p>Artifact 数量固定为一到两份，每份 Mock 内容最多 4096 bytes。构造会按
 * artifact type、revision、artifactId 排序，使集合输入重排不改变 canonical JSON。
 * 本对象不含 receipt、lease token、凭据、真实会议/模型正文、预签名 URL、Storage
 * binding 或本地文件路径。</p>
 */
public final class FinalizationCheckpoint {
    public static final String CHECKPOINT_TYPE = "mock-finalization-checkpoint/v1";
    public static final String STEP_TYPE = "mock-finalization";
    public static final int MAX_ARTIFACT_CONTENT_BYTES = 4096;
    public static final int MAX_ARTIFACTS = 2;

    private static final String INVALID_CHECKPOINT = "Finalization checkpoint is invalid";
    private static final Pattern SHA256 = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private final String meetingId;
    private final String inputBinding;
    private final String inputFactDigest;
    private final String executionFactDigest;
    private final List<ArtifactMaterial> artifacts;
    private final FinalizationCommitPlan commitPlan;

    /**
     * 从 canonical JSON 恢复 checkpoint；所有嵌套值必须已经完整提供。
     */
    public FinalizationCheckpoint(
            String meetingId,
            String inputBinding,
            String inputFactDigest,
            String executionFactDigest,
            List<ArtifactMaterial> artifacts,
            FinalizationCommitPlan commitPlan) {
        try {
            MeetingIdPolicy.requireValid(meetingId);
            FinalizationOperationIdentity.requireText(inputBinding);
            requireDigest(inputFactDigest);
            requireDigest(executionFactDigest);
            Objects.requireNonNull(artifacts, "artifacts");
            if (artifacts.isEmpty() || artifacts.size() > MAX_ARTIFACTS) {
                throw invalid();
            }
            ArrayList<ArtifactMaterial> sorted = new ArrayList<>(artifacts);
            sorted.sort(Comparator
                    .comparing((ArtifactMaterial value) -> value.spec().artifactType())
                    .thenComparingInt(value -> value.spec().revision())
                    .thenComparing(value -> value.spec().artifactId()));
            Set<String> artifactIds = new HashSet<>();
            Set<String> artifactTypes = new HashSet<>();
            for (ArtifactMaterial artifact : sorted) {
                Objects.requireNonNull(artifact, "artifact");
                if (!meetingId.equals(artifact.spec().meetingId())
                        || !artifactIds.add(artifact.spec().artifactId())
                        || !artifactTypes.add(artifact.spec().artifactType())
                        || !removeSha256Prefix(inputFactDigest).equals(
                                artifact.content().sourceFingerprint())
                        || !removeSha256Prefix(executionFactDigest).equals(
                                artifact.content().executionFingerprint())) {
                    throw invalid();
                }
            }
            Objects.requireNonNull(commitPlan, "commitPlan");
            if (!meetingId.equals(commitPlan.meetingId())
                    || !inputBinding.equals(commitPlan.sourceInputBinding())) {
                throw invalid();
            }
            this.meetingId = meetingId;
            this.inputBinding = inputBinding;
            this.inputFactDigest = inputFactDigest;
            this.executionFactDigest = executionFactDigest;
            this.artifacts = List.copyOf(sorted);
            this.commitPlan = commitPlan;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 计算覆盖 V008 {@link FinalizationInputFact} 全字段的稳定摘要。
     */
    public static String inputFactDigest(FinalizationInputFact input) {
        try {
            Objects.requireNonNull(input, "input");
            MeetingIdPolicy.requireValid(input.meetingId());
            FinalizationOperationIdentity.requireText(input.inputBinding());
            FinalizationOperationIdentity.requireText(input.sourceLanguage());
            FinalizationOperationIdentity.requireText(input.contentDigest());
            FinalizationOperationIdentity.requirePersistentInstant(input.acceptedAt());
            List<String> fields = new ArrayList<>();
            fields.add(input.meetingId());
            fields.add(input.inputBinding());
            fields.add(Long.toString(input.lastAcceptedSequence()));
            fields.add(Long.toString(input.audioDurationMs()));
            fields.add(input.sourceLanguage());
            fields.add(Integer.toString(input.targetLanguages().size()));
            for (String target : input.targetLanguages()) {
                FinalizationOperationIdentity.requireText(target);
                fields.add(target);
            }
            fields.add(input.acceptedAt().toString());
            fields.add(input.contentDigest());
            return "sha256:" + FinalizationOperationIdentity.digest(
                    "finalization-input-fact/v1", fields.toArray(String[]::new));
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 稳定 Artifact identity；只绑定 meeting、固定类型和 revision，不含摘要、
     * checksum、内容或时钟。
     */
    public static String artifactId(String meetingId, String artifactType, int revision) {
        try {
            MeetingIdPolicy.requireValid(meetingId);
            if (!MockFinalizationArtifactContent.ARTIFACT_TYPES.contains(artifactType)
                    || revision != MockFinalizationArtifactContent.REVISION) {
                throw invalid();
            }
            return "art_mock_v1_" + FinalizationOperationIdentity.digest(
                    "mock-finalization-artifact-id/v1",
                    meetingId, artifactType, Integer.toString(revision));
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /** 稳定 Workflow key；不含摘要、checksum、Reservation seed 或时钟。 */
    public WorkflowStep.Key workflowKey() {
        return workflowKey(meetingId);
    }

    /** 仅由已知 meeting identity 定位持久 Workflow，不重新派生 checkpoint。 */
    public static WorkflowStep.Key workflowKey(String meetingId) {
        MeetingIdPolicy.requireValid(meetingId);
        return new WorkflowStep.Key(
                meetingId,
                STEP_TYPE,
                "mock_finalize_v1_" + FinalizationOperationIdentity.digest(
                        "mock-finalization-step-key/v1", meetingId));
    }

    /** Artifact 与权益重放使用的会议 identity。 */
    public String meetingId() {
        return meetingId;
    }

    public String inputBinding() {
        return inputBinding;
    }

    public String inputFactDigest() {
        return inputFactDigest;
    }

    public String executionFactDigest() {
        return executionFactDigest;
    }

    public List<ArtifactMaterial> artifacts() {
        return artifacts;
    }

    public FinalizationCommitPlan commitPlan() {
        return commitPlan;
    }

    public FinalUsageFact finalUsageFact() {
        return commitPlan.finalUsageFact();
    }

    public CommitUsageRequest commitUsageRequest() {
        return commitPlan.commitUsageRequest();
    }

    /** Workflow 初次注册时间取 durable input 的稳定接管时间，不读取当前时钟。 */
    public Instant registeredAt() {
        return commitPlan.sourceAcceptedAt();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FinalizationCheckpoint that)) {
            return false;
        }
        return meetingId.equals(that.meetingId)
                && inputBinding.equals(that.inputBinding)
                && inputFactDigest.equals(that.inputFactDigest)
                && executionFactDigest.equals(that.executionFactDigest)
                && artifacts.equals(that.artifacts)
                && commitPlan.equals(that.commitPlan);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                meetingId, inputBinding, inputFactDigest,
                executionFactDigest, artifacts, commitPlan);
    }

    @Override
    public String toString() {
        return "FinalizationCheckpoint[meetingId=redacted, input=redacted"
                + ", execution=redacted, artifactCount=" + artifacts.size()
                + ", commitPlan=redacted]";
    }

    private static void requireDigest(String value) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw invalid();
        }
    }

    private static String removeSha256Prefix(String digest) {
        return digest.substring("sha256:".length());
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID_CHECKPOINT);
    }

    /**
     * 单份 Artifact 的完整恢复材料。内容以字节保存，getter 返回副本。
     */
    public static final class ArtifactMaterial {
        private final WriteIntentSpec spec;
        private final byte[] canonicalContent;
        private final ContentClaim claim;
        private final MockFinalizationArtifactContent content;

        public ArtifactMaterial(
                WriteIntentSpec spec,
                byte[] canonicalContent,
                ContentClaim claim) {
            try {
                Objects.requireNonNull(spec, "spec");
                Objects.requireNonNull(canonicalContent, "canonicalContent");
                Objects.requireNonNull(claim, "claim");
                requireStrictText(spec.artifactId());
                MeetingIdPolicy.requireValid(spec.meetingId());
                requireStrictText(spec.artifactType());
                requireStrictText(spec.mediaType());
                requireStrictText(spec.language());
                if (canonicalContent.length == 0
                        || canonicalContent.length > MAX_ARTIFACT_CONTENT_BYTES) {
                    throw invalid();
                }
                MockFinalizationArtifactContent decoded =
                        MockFinalizationArtifactContent.decodeCanonical(canonicalContent);
                if (!MockFinalizationArtifactContent.ARTIFACT_TYPES.contains(
                                spec.artifactType())
                        || !MockFinalizationArtifactContent.MEDIA_TYPE.equals(spec.mediaType())
                        || spec.revision() != MockFinalizationArtifactContent.REVISION
                        || !artifactId(
                                spec.meetingId(), spec.artifactType(), spec.revision())
                                .equals(spec.artifactId())
                        || !spec.artifactType().equals(decoded.artifactType())
                        || !spec.language().equals(decoded.language())
                        || spec.revision() != decoded.revision()) {
                    throw invalid();
                }
                if (claim.size() != canonicalContent.length
                        || spec.expectedSize() != canonicalContent.length
                        || !claim.checksum().equals(spec.expectedChecksum())
                        || !claim.checksum().equals(
                                FinalizationOperationIdentity.checksum(canonicalContent))) {
                    throw invalid();
                }
                this.spec = spec;
                this.canonicalContent = canonicalContent.clone();
                this.claim = claim;
                this.content = decoded;
            } catch (RuntimeException failure) {
                throw invalid();
            }
        }

        public WriteIntentSpec spec() {
            return spec;
        }

        public byte[] canonicalContentBytes() {
            return canonicalContent.clone();
        }

        /** 仅供 Mock 门禁核对；构造时已验证内容是严格 UTF-8。 */
        public String canonicalContent() {
            return new String(canonicalContent, StandardCharsets.UTF_8);
        }

        public ContentClaim claim() {
            return claim;
        }

        /** 返回已经通过关闭式 canonical 解码的 Mock 语义值。 */
        public MockFinalizationArtifactContent content() {
            return content;
        }

        /** 每次返回位置为零的新流，调用方拥有并关闭。 */
        public InputStream openContent() {
            return new ByteArrayInputStream(canonicalContent);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ArtifactMaterial that)) {
                return false;
            }
            return spec.equals(that.spec)
                    && Arrays.equals(canonicalContent, that.canonicalContent)
                    && claim.equals(that.claim)
                    && content.equals(that.content);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(spec, claim, content)
                    + Arrays.hashCode(canonicalContent);
        }

        @Override
        public String toString() {
            return "ArtifactMaterial[spec=redacted, content=redacted, claim=redacted]";
        }

        private static void requireStrictText(String value) {
            FinalizationOperationIdentity.requireText(value);
        }
    }
}
