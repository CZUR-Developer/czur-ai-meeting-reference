package com.czur.reference.application.port;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 会议结果清单的最小完整表示。
 *
 * <p>Manifest 查询一旦返回成功，调用方就需要能够根据同一份结果决定要展示
 * 哪些模块以及从哪里读取产物。因此 {@link MeetingManifestPort.Found} 不能只
 * 返回“正在生成”状态或 finalization receipt，而必须携带 meetingId、Profile
 * 快照、时间范围、语言、Artifact 引用和降级告警。这里
 * 只保存协议字段，不保存模型厂商原始响应、会议正文或对象存储访问凭据。</p>
 *
 * <p>协议字段（{@code specVersion}、{@code meetingId}、嵌套的 {@code profile}、
 * 可空 {@code title} 以及其余结果字段）与 {@code contracts/meeting-manifest.schema.json}
 * 保持一致。结果 URL 使用的 {@code genTime} 只属于查询上下文，不属于 Manifest
 * 正文、revision 或内容摘要。</p>
 */
public record MeetingManifest(
        String specVersion,
        String meetingId,
        int manifestRevision,
        Profile profile,
        String title,
        ManifestStatus status,
        Instant startedAt,
        Instant endedAt,
        long durationMs,
        Languages languages,
        List<ArtifactRef> artifacts,
        List<Warning> warnings) {

    private static final Pattern SPEC_VERSION = Pattern.compile("^[0-9]+\\.[0-9]+$");
    private static final Pattern MEETING_ID = Pattern.compile("^mtg_[A-Za-z0-9_-]+$");
    private static final Pattern ARTIFACT_ID = Pattern.compile("^art_[A-Za-z0-9_-]+$");
    private static final Pattern CHECKSUM = Pattern.compile("^sha256:[A-Fa-f0-9]{64}$");
    private static final Instant MIN_PROTOCOL_INSTANT =
            Instant.parse("0000-01-01T00:00:00Z");
    private static final Instant MAX_PROTOCOL_INSTANT =
            Instant.parse("9999-12-31T23:59:59.999999999Z");
    private static final Set<String> ARTIFACT_TYPES = Set.of(
            "audio.original",
            "transcript.raw",
            "transcript.final",
            "translation.final",
            "speaker.session",
            "speaker.identity",
            "summary.structured",
            "summary.markdown",
            "chapters.structured",
            "action-items.structured",
            "presentation.capture",
            "export.file");
    private static final Set<String> FAILURE_DOMAINS = Set.of(
            "MODEL", "STORAGE", "SUMMARY", "INTERNAL");

    public MeetingManifest {
        requireSpecVersion(specVersion);
        requireMeetingId(meetingId);
        if (manifestRevision < 1) {
            throw new IllegalArgumentException("manifestRevision must be positive");
        }
        Objects.requireNonNull(profile, "profile");
        if (title != null && codePointLength(title) > 256) {
            throw new IllegalArgumentException("title must not exceed 256 Unicode characters");
        }
        Objects.requireNonNull(status, "status");
        requireCompleteStatus(status);
        startedAt = requireProtocolInstant("startedAt", startedAt);
        endedAt = requireProtocolInstant("endedAt", endedAt);
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
        return "MeetingManifest[specVersion=" + specVersion
                + ", meetingId=redacted, manifestRevision=" + manifestRevision
                + ", profile=" + profile
                + ", titlePresent=" + (title != null)
                + ", status=" + status
                + ", artifactCount=" + artifacts.size()
                + ", warningCount=" + warnings.size() + ']';
    }

    /**
     * 兼容仍按扁平字段读取 Manifest 的旧应用层代码；该方法只是
     * {@code profile.id} 的适配映射，不是协议字段，也不会改变嵌套 Profile 的
     * canonical 表示。新代码应直接使用 {@link #profile()}。
     */
    @Deprecated
    public String profileId() {
        return profile.id();
    }

    /**
     * 兼容旧应用层的扁平字段访问；该方法只是 {@code profile.revision} 的适配
     * 映射，不是 Manifest Schema 的顶层字段。新代码应直接使用
     * {@link Profile#revision()}。
     */
    @Deprecated
    public int profileRevision() {
        return profile.revision();
    }

    /**
     * 完整 Manifest 只表达 Schema 已冻结的最终或删除状态。生成尚未开始或仍在
     * 进行时必须使用 {@link MeetingManifestPort.Pending}，不能用带虚构字段的空
     * Manifest 代替。这里不附加 Artifact 数量、告警数量或时长之间的业务推断。
     */
    private static void requireCompleteStatus(ManifestStatus status) {
        switch (status) {
            case READY, READY_WITH_WARNINGS, FAILED, DELETION_PENDING, DELETED -> {
                // 与 meeting-manifest.schema.json 的顶层 status 枚举完全一致。
            }
            case NOT_STARTED, GENERATING -> throw new IllegalArgumentException(
                    "complete manifest must not use an in-progress status");
        }
    }

    private static void requireSpecVersion(String specVersion) {
        requireText("specVersion", specVersion);
        if (!SPEC_VERSION.matcher(specVersion).matches()) {
            throw new IllegalArgumentException("specVersion must match major.minor");
        }
    }

    /** 同包查询端口与 Manifest 正文共享公开 meetingId 的关闭式校验。 */
    static void requireMeetingId(String meetingId) {
        requirePattern("meetingId", meetingId, MEETING_ID);
        // 正则已经把取值限制为 ASCII，因此 Java length 与 Schema maxLength 等价。
        if (meetingId.length() > 1024) {
            throw new IllegalArgumentException("meetingId must not exceed 1024 characters");
        }
    }

    /**
     * 校验所有 Manifest 时间字段共享的公开协议值域。
     *
     * <p>公开 JSON 使用固定四位年份，编码后必须是 UTC {@code Z} 表示，因此领域
     * 值只允许从 {@code 0000-01-01T00:00:00Z} 到
     * {@code 9999-12-31T23:59:59.999999999Z}（含边界）。JSON codec 在解码后和
     * 编码前复用本方法，避免 Java 直接构造与外部协议形成不同值域。</p>
     *
     * @param field 仅用于本地编程错误诊断的字段名，不得包含外部数据
     * @param value 待校验的时间
     * @return 未经转换的原 Instant
     */
    public static Instant requireProtocolInstant(String field, Instant value) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(value, field);
        if (value.isBefore(MIN_PROTOCOL_INSTANT) || value.isAfter(MAX_PROTOCOL_INSTANT)) {
            throw new IllegalArgumentException(
                    field + " must be within UTC canonical year 0000..9999");
        }
        return value;
    }

    /**
     * 防御性校验后生成公开协议使用的 UTC canonical 时间文本。
     *
     * <p>即使 record 构造器已经校验，编码边界仍应调用本方法，以便未来新增恢复
     * 或框架构造路径时继续关闭式保护协议输出。</p>
     */
    public static String toCanonicalInstantText(String field, Instant value) {
        return requireProtocolInstant(field, value).toString();
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
        if (value == null || isEcmaScriptBlank(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * 与 JSON Schema ECMA-262 正则的 {@code \S} 保持同一空白集合。
     *
     * <p>{@link String#isBlank()} 使用 Java 自己的 Unicode whitespace 定义，既不把
     * NBSP（U+00A0）视为空白，也不包含 ECMAScript 明确列出的 BOM（U+FEFF），
     * 因而不能用于协议字段。本方法覆盖 ECMAScript WhiteSpace、LineTerminator
     * 以及 Unicode Space_Separator（Zs），只要字符串包含一个其它 code point，
     * Schema 的 {@code \S} 与 Java 都会判定其为非空白。</p>
     */
    private static boolean isEcmaScriptBlank(String value) {
        if (value.isEmpty()) {
            return true;
        }
        return value.codePoints().allMatch(MeetingManifest::isEcmaScriptWhitespace);
    }

    private static boolean isEcmaScriptWhitespace(int codePoint) {
        return switch (codePoint) {
            // ECMAScript WhiteSpace 中不属于 Unicode Zs 的显式字符。
            case 0x0009, 0x000B, 0x000C, 0xFEFF,
                    // ECMAScript LineTerminator。
                    0x000A, 0x000D, 0x2028, 0x2029 -> true;
            default -> Character.getType(codePoint) == Character.SPACE_SEPARATOR;
        };
    }

    /** Manifest Schema 的规范 Profile 引用；id 与 revision 必须成对出现。 */
    public record Profile(String id, int revision) {
        public Profile {
            requireText("profile.id", id);
            if (revision < 1) {
                throw new IllegalArgumentException("profile.revision must be positive");
            }
        }

        @Override
        public String toString() {
            return "Profile[id=redacted, revision=" + revision + ']';
        }
    }

    /** Manifest 中声明的源语言和翻译目标语言。 */
    public record Languages(String source, List<String> targets) {
        public Languages {
            requireMinCodePoints("languages.source", source, 2);
            targets = immutableLanguageList(targets, "languages.targets");
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
            requirePattern("artifactId", artifactId, ARTIFACT_ID);
            requireAllowed("type", type, ARTIFACT_TYPES);
            requireMinCodePoints("mediaType", mediaType, 3);
            if (language != null) {
                requireMinCodePoints("language", language, 2);
            }
            if (revision < 1) {
                throw new IllegalArgumentException("artifact revision must be positive");
            }
            if (size < 0) {
                throw new IllegalArgumentException("artifact size must not be negative");
            }
            requirePattern("checksum", checksum, CHECKSUM);
            Objects.requireNonNull(status, "status");
            createdAt = requireProtocolInstant("artifact.createdAt", createdAt);
            if (supersedesArtifactId != null) {
                requirePattern("supersedesArtifactId", supersedesArtifactId, ARTIFACT_ID);
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
            requireAllowed("failure.domain", domain, FAILURE_DOMAINS);
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
            if (diagnosticId != null) {
                requireText("warning.diagnosticId", diagnosticId);
            }
        }

        @Override
        public String toString() {
            return "Warning[code=" + code + ", capability=" + capability
                    + ", messageKey=" + messageKey
                    + ", diagnosticIdPresent=" + (diagnosticId != null) + ']';
        }
    }

    private static List<String> immutableLanguageList(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            requireMinCodePoints(name + " item", value, 2);
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    private static void requireAllowed(String name, String value, Set<String> allowed) {
        requireText(name, value);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(name + " is not supported by the public protocol");
        }
    }

    private static void requirePattern(String name, String value, Pattern pattern) {
        requireText(name, value);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " does not match the public protocol");
        }
    }

    private static void requireMinCodePoints(String name, String value, int minimum) {
        requireText(name, value);
        if (codePointLength(value) < minimum) {
            throw new IllegalArgumentException(
                    name + " must contain at least " + minimum + " Unicode characters");
        }
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }
}
