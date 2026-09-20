package com.czur.reference.application.port;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 会议结果清单的最小完整表示。
 *
 * <p>Manifest 查询一旦返回成功，调用方就需要能够根据同一份结果决定要展示
 * 哪些模块以及从哪里读取产物。因此 {@link MeetingManifestPort.Found} 不能只
 * 返回“正在生成”状态或 finalization receipt，而必须携带 meetingId、结果 URL
 * 的 genTime、Profile 快照、时间范围、语言、Artifact 引用和降级告警。这里
 * 只保存协议字段，不保存模型厂商原始响应、会议正文或对象存储访问凭据。</p>
 *
 * <p>字段名称和约束与 {@code contracts/meeting-manifest.schema.json} 保持一致；
 * {@code genTime} 是结果 URL 上下文中的毫秒时间戳，-1 表示永不过期，且不把
 * 它序列化为 Manifest 正文中的额外业务字段。</p>
 */
public record MeetingManifest(
        String meetingId,
        long genTime,
        int manifestRevision,
        String profileId,
        int profileRevision,
        ManifestStatus status,
        Instant startedAt,
        Instant endedAt,
        long durationMs,
        Languages languages,
        List<ArtifactRef> artifacts,
        List<Warning> warnings) {

    public MeetingManifest {
        requireText("meetingId", meetingId);
        requireGenTime(genTime);
        if (manifestRevision < 1) {
            throw new IllegalArgumentException("manifestRevision must be positive");
        }
        requireText("profileId", profileId);
        if (profileRevision < 1) {
            throw new IllegalArgumentException("profileRevision must be positive");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt must not be before startedAt");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        Objects.requireNonNull(languages, "languages");
        artifacts = immutableList(artifacts, "artifacts");
        warnings = immutableList(warnings, "warnings");
    }

    /** 诊断只显示清单规模和稳定版本，不输出会议正文、产物地址或告警文本。 */
    @Override
    public String toString() {
        return "MeetingManifest[meetingId=redacted, genTime=" + genTime
                + ", manifestRevision=" + manifestRevision
                + ", profileId=redacted, profileRevision=" + profileRevision
                + ", status=" + status
                + ", artifactCount=" + artifacts.size()
                + ", warningCount=" + warnings.size() + ']';
    }

    /** 结果 URL 的生成时间：非负毫秒时间戳，或 -1（永不过期）。 */
    public static void requireGenTime(long genTime) {
        if (genTime < -1) {
            throw new IllegalArgumentException("genTime must be -1 or non-negative milliseconds");
        }
    }

    private static <T> List<T> immutableList(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " item"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /** Manifest 中声明的源语言和翻译目标语言。 */
    public record Languages(String source, List<String> targets) {
        public Languages {
            requireText("languages.source", source);
            targets = immutableTextList(targets, "languages.targets");
            if (new HashSet<>(targets).size() != targets.size()) {
                throw new IllegalArgumentException("languages.targets must be unique");
            }
        }

        @Override
        public String toString() {
            return "Languages[source=" + source + ", targetCount=" + targets.size() + ']';
        }
    }

    /** 一个可被 Web 或 App 继续读取的结果产物引用。 */
    public record ArtifactRef(
            String artifactId,
            String type,
            String mediaType,
            String language,
            int revision,
            long size,
            String checksum,
            ArtifactStatus status,
            Instant createdAt,
            String supersedesArtifactId,
            ArtifactFailure failure) {

        public ArtifactRef {
            requireText("artifactId", artifactId);
            requireText("type", type);
            requireText("mediaType", mediaType);
            if (language != null && language.isBlank()) {
                throw new IllegalArgumentException("language must be null or non-blank");
            }
            if (revision < 1) {
                throw new IllegalArgumentException("artifact revision must be positive");
            }
            if (size < 0) {
                throw new IllegalArgumentException("artifact size must not be negative");
            }
            requireText("checksum", checksum);
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(createdAt, "createdAt");
            if (supersedesArtifactId != null && supersedesArtifactId.isBlank()) {
                throw new IllegalArgumentException(
                        "supersedesArtifactId must be null or non-blank");
            }
            if (status == ArtifactStatus.FAILED && failure == null) {
                throw new IllegalArgumentException("failed artifact requires failure");
            }
            if (status != ArtifactStatus.FAILED && failure != null) {
                throw new IllegalArgumentException("only failed artifact may carry failure");
            }
        }

        /** 产物引用不输出校验值、对象存储地址或失败诊断正文。 */
        @Override
        public String toString() {
            return "ArtifactRef[artifactId=redacted, type=" + type
                    + ", mediaType=" + mediaType
                    + ", revision=" + revision
                    + ", status=" + status + ']';
        }
    }

    /** 产物状态与 Manifest Schema 对齐。 */
    public enum ArtifactStatus {
        PENDING,
        READY,
        FAILED,
        DELETED
    }

    /** 产物失败时可安全向客户端公开的稳定错误摘要。 */
    public record ArtifactFailure(String domain, String code, boolean retryable,
                                  String diagnosticId) {
        public ArtifactFailure {
            requireText("failure.domain", domain);
            requireText("failure.code", code);
            requireText("failure.diagnosticId", diagnosticId);
        }

        @Override
        public String toString() {
            return "ArtifactFailure[domain=" + domain
                    + ", code=" + code
                    + ", retryable=" + retryable
                    + ", diagnosticIdPresent=true]";
        }
    }

    /** 可供 UI 本地化的降级/缺失能力告警。 */
    public record Warning(String code, String capability, String messageKey,
                          String diagnosticId) {
        public Warning {
            requireText("warning.code", code);
            requireText("warning.capability", capability);
            requireText("warning.messageKey", messageKey);
            if (diagnosticId != null && diagnosticId.isBlank()) {
                throw new IllegalArgumentException(
                        "warning.diagnosticId must be null or non-blank");
            }
        }

        @Override
        public String toString() {
            return "Warning[code=" + code + ", capability=" + capability
                    + ", messageKey=" + messageKey
                    + ", diagnosticIdPresent=" + (diagnosticId != null) + ']';
        }
    }

    private static List<String> immutableTextList(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            requireText(name + " item", value);
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }
}
