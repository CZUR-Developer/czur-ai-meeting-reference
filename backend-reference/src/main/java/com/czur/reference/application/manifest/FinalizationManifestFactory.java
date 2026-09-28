package com.czur.reference.application.manifest;

import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.TerminalFailure;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore.ReadyMetadata;
import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.protocol.json.FinalizationExecutionFactJson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 从 Admission 已冻结事实构造首版最终 Manifest 的纯应用层规则。
 *
 * <p>字段来源被刻意固定：execution fact 提供 Profile、标题和开始时间；V008 输入
 * 事实提供结束时间和语言；V009 最终用量提供 {@code durationMs}；checkpoint 提供
 * 稳定 Artifact 集合；READY metadata 的数据库 {@code recordedAt} 提供每个 READY
 * 引用的 {@code createdAt}。工厂不读取当前配置、当前时钟、模型或对象存储。</p>
 *
 * <p>READY 构造要求 metadata 与 checkpoint artifact 一一对应。FAILED 构造允许
 * metadata 是其子集：已发布内容保持 READY，未发布内容使用 checkpoint 中已冻结的
 * spec/claim 形成 FAILED 引用。两条路径都拒绝额外、重复、漂移或跨会议 descriptor。</p>
 */
public final class FinalizationManifestFactory {
    public static final String SPEC_VERSION = "0.1";
    public static final int INITIAL_REVISION = 1;

    private FinalizationManifestFactory() {
    }

    /** 构造 READY 或 READY_WITH_WARNINGS 的首版完整结果。 */
    public static MeetingManifest ready(
            FinalizationExecutionFact execution,
            FinalizationInputFact input,
            FinalUsageFact finalUsage,
            FinalizationCheckpoint checkpoint,
            List<ReadyMetadata> metadata) {
        Context context = validateCommon(
                execution, input, finalUsage, checkpoint, metadata);
        if (context.readyByArtifactId().size() != checkpoint.artifacts().size()) {
            throw invalid();
        }
        List<MeetingManifest.Warning> warnings = warnings(execution);
        return manifest(
                execution,
                input,
                finalUsage,
                warnings.isEmpty() ? ManifestStatus.READY : ManifestStatus.READY_WITH_WARNINGS,
                readyArtifactRefs(checkpoint, context.readyByArtifactId()),
                warnings);
    }

    /** 构造保留 READY 子集、并明确标记其余 Artifact 失败的首版 FAILED 结果。 */
    public static MeetingManifest failed(
            FinalizationExecutionFact execution,
            FinalizationInputFact input,
            FinalUsageFact finalUsage,
            FinalizationCheckpoint checkpoint,
            List<ReadyMetadata> metadata,
            TerminalFailure failure,
            Instant failedAt) {
        Context context = validateCommon(
                execution, input, finalUsage, checkpoint, metadata);
        Objects.requireNonNull(failure, "failure");
        MeetingManifest.requireProtocolInstant("failedAt", failedAt);
        List<MeetingManifest.ArtifactRef> artifacts = new ArrayList<>();
        for (FinalizationCheckpoint.ArtifactMaterial material : checkpoint.artifacts()) {
            ReadyMetadata ready = context.readyByArtifactId()
                    .get(material.spec().artifactId());
            artifacts.add(ready == null
                    ? failedArtifactRef(material, failure, failedAt)
                    : readyArtifactRef(material, ready));
        }
        return manifest(
                execution,
                input,
                finalUsage,
                ManifestStatus.FAILED,
                artifacts,
                warnings(execution));
    }

    private static Context validateCommon(
            FinalizationExecutionFact execution,
            FinalizationInputFact input,
            FinalUsageFact finalUsage,
            FinalizationCheckpoint checkpoint,
            List<ReadyMetadata> metadata) {
        try {
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(finalUsage, "finalUsage");
            Objects.requireNonNull(checkpoint, "checkpoint");
            Objects.requireNonNull(metadata, "metadata");
            String meetingId = execution.meetingId();
            if (!meetingId.equals(input.meetingId())
                    || !meetingId.equals(finalUsage.meetingId())
                    || !meetingId.equals(checkpoint.meetingId())
                    || execution.startedAt().isAfter(input.acceptedAt())
                    || !checkpoint.inputBinding().equals(input.inputBinding())
                    || !checkpoint.inputFactDigest().equals(
                    FinalizationCheckpoint.inputFactDigest(input))
                    || !checkpoint.executionFactDigest().equals(
                    FinalizationExecutionFactJson.sha256(execution))
                    || !finalUsage.equals(FinalUsageFact.fromInput(input))
                    || !finalUsage.equals(checkpoint.finalUsageFact())) {
                throw invalid();
            }

            Map<String, FinalizationCheckpoint.ArtifactMaterial> expected = new HashMap<>();
            for (FinalizationCheckpoint.ArtifactMaterial material : checkpoint.artifacts()) {
                expected.put(material.spec().artifactId(), material);
            }
            Map<String, ReadyMetadata> ready = new HashMap<>();
            for (ReadyMetadata value : metadata) {
                Objects.requireNonNull(value, "metadata item");
                ArtifactDescriptor descriptor = value.artifact();
                WriteIntentSpec spec = descriptor.intent().spec();
                FinalizationCheckpoint.ArtifactMaterial material = expected.get(
                        spec.artifactId());
                if (material == null
                        || ready.putIfAbsent(spec.artifactId(), value) != null
                        || !material.spec().equals(spec)
                        || !material.claim().equals(descriptor.content())) {
                    throw invalid();
                }
            }
            return new Context(Map.copyOf(ready));
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static MeetingManifest manifest(
            FinalizationExecutionFact execution,
            FinalizationInputFact input,
            FinalUsageFact finalUsage,
            ManifestStatus status,
            List<MeetingManifest.ArtifactRef> artifacts,
            List<MeetingManifest.Warning> warnings) {
        return new MeetingManifest(
                SPEC_VERSION,
                execution.meetingId(),
                INITIAL_REVISION,
                new MeetingManifest.Profile(
                        execution.productProfile().id(),
                        execution.productProfile().revision()),
                execution.title(),
                status,
                execution.startedAt(),
                input.acceptedAt(),
                finalUsage.actual().amount(),
                new MeetingManifest.Languages(
                        input.sourceLanguage(), input.targetLanguages()),
                artifacts,
                warnings);
    }

    private static List<MeetingManifest.ArtifactRef> readyArtifactRefs(
            FinalizationCheckpoint checkpoint,
            Map<String, ReadyMetadata> metadata) {
        List<MeetingManifest.ArtifactRef> refs = new ArrayList<>();
        for (FinalizationCheckpoint.ArtifactMaterial material : checkpoint.artifacts()) {
            refs.add(readyArtifactRef(
                    material,
                    Objects.requireNonNull(
                            metadata.get(material.spec().artifactId()),
                            "READY metadata")));
        }
        return List.copyOf(refs);
    }

    private static MeetingManifest.ArtifactRef readyArtifactRef(
            FinalizationCheckpoint.ArtifactMaterial material,
            ReadyMetadata metadata) {
        WriteIntentSpec spec = material.spec();
        return new MeetingManifest.ArtifactRef(
                spec.artifactId(),
                spec.artifactType(),
                spec.mediaType(),
                spec.language(),
                spec.revision(),
                metadata.artifact().content().size(),
                metadata.artifact().content().checksum(),
                MeetingManifest.ArtifactStatus.READY,
                metadata.recordedAt(),
                null,
                null);
    }

    private static MeetingManifest.ArtifactRef failedArtifactRef(
            FinalizationCheckpoint.ArtifactMaterial material,
            TerminalFailure failure,
            Instant failedAt) {
        WriteIntentSpec spec = material.spec();
        return new MeetingManifest.ArtifactRef(
                spec.artifactId(),
                spec.artifactType(),
                spec.mediaType(),
                spec.language(),
                spec.revision(),
                material.claim().size(),
                material.claim().checksum(),
                MeetingManifest.ArtifactStatus.FAILED,
                failedAt,
                null,
                new MeetingManifest.ArtifactFailure(
                        failure.domain(),
                        failure.code(),
                        failure.retryable(),
                        failure.diagnosticId()));
    }

    /**
     * 把冻结能力计划中的真实降级转换成稳定公开告警。
     *
     * <p>{@code NOT_REQUESTED} 只表示产品没有请求该 optional 能力，不是运行降级，
     * 因而不会把正常的精简 Profile 标记为 READY_WITH_WARNINGS。其它 warning code
     * 原样进入 {@code code}，messageKey 只使用冒号前的稳定类别，避免依赖或 fallback
     * 列表进入本地化 key；能力运行时没有额外 diagnosticId，因此固定为 null。
     * 该规则是 Completion 的正式生成契约，不复用早期 fixture 中手写且命名不统一的
     * messageKey。</p>
     */
    private static List<MeetingManifest.Warning> warnings(
            FinalizationExecutionFact execution) {
        List<MeetingManifest.Warning> warnings = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (CapabilityDecision decision : execution.capabilityPlan().decisions()) {
            String code = decision.warningCode();
            if (code == null || "NOT_REQUESTED".equals(code)) {
                continue;
            }
            String identity = decision.capability() + '\u0000' + code;
            if (!identities.add(identity)) {
                throw invalid();
            }
            warnings.add(new MeetingManifest.Warning(
                    code,
                    decision.capability(),
                    "ai_meeting.capability."
                            + warningCategory(code).toLowerCase(Locale.ROOT),
                    null));
        }
        return List.copyOf(warnings);
    }

    private static String warningCategory(String code) {
        int separator = code.indexOf(':');
        String category = separator < 0 ? code : code.substring(0, separator);
        if (category.isEmpty()
                || !category.chars().allMatch(character ->
                character == '_' || character >= 'A' && character <= 'Z')) {
            throw invalid();
        }
        return category;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Finalization Manifest input is inconsistent");
    }

    private record Context(Map<String, ReadyMetadata> readyByArtifactId) {
    }
}
