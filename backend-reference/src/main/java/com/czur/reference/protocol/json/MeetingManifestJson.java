package com.czur.reference.protocol.json;

import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.StringWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@link MeetingManifest} 与公开 JSON Schema 之间的严格编解码边界。
 *
 * <p>实现刻意使用显式 JSON tree 解码与 streaming 编码，而不是把 Jackson 注解
 * 放进领域 record。这样 Jackson 类型不会进入 application port，字段顺序、空值
 * 策略和关闭式校验也不会依赖全局 ObjectMapper 配置。解码拒绝重复字段、未知
 * 字段、缺失字段、错误类型、数值强制转换、溢出、尾随 token、非法时间和协议
 * 闭集之外的值。</p>
 *
 * <p>编码固定输出 Schema 中的字段顺序。可空字段统一显式写为 JSON
 * {@code null}，空数组统一写为 {@code []}；因此同一个 Manifest 值总能得到稳定
 * 的 canonical JSON 文本。该文本可以作为后续内容摘要的输入，但摘要算法与
 * 持久化策略不属于本类职责。</p>
 */
public final class MeetingManifestJson {
    private static final String DECODE_FAILURE = "Manifest JSON 格式非法";
    private static final String ENCODE_FAILURE = "Manifest JSON 编码失败";
    /**
     * 可无损归一化成 {@link Instant} 的严格 RFC 3339 子集：固定四位年份、禁止
     * 24:00 和闰秒、小数秒最多九位，偏移限制为 Java/ISO 支持的正负 18 小时；
     * 边界年份 0000 与 9999 只接受 Z，确保转换后仍在共享协议值域内。
     */
    private static final Pattern RFC3339_INSTANT = Pattern.compile(
            "^(?:(?:0000|9999)-[0-9]{2}-[0-9]{2}T"
                    + "(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
                    + "(?:\\.[0-9]{1,9})?Z|"
                    + "(?!(?:0000|9999)-)[0-9]{4}-[0-9]{2}-[0-9]{2}T"
                    + "(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
                    + "(?:\\.[0-9]{1,9})?"
                    + "(?:Z|[+-](?:(?:0[0-9]|1[0-7]):[0-5][0-9]|18:00)))$");
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper(JSON_FACTORY)
            .registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Set<String> ROOT_REQUIRED = Set.of(
            "specVersion", "meetingId", "manifestRevision", "profile", "status",
            "startedAt", "endedAt", "durationMs", "languages", "artifacts", "warnings");
    private static final Set<String> ROOT_ALLOWED = withOptional(ROOT_REQUIRED, "title");
    private static final Set<String> PROFILE_FIELDS = Set.of("id", "revision");
    private static final Set<String> LANGUAGE_FIELDS = Set.of("source", "targets");
    private static final Set<String> ARTIFACT_REQUIRED = Set.of(
            "artifactId", "type", "mediaType", "revision", "size", "checksum",
            "status", "createdAt");
    private static final Set<String> ARTIFACT_ALLOWED = withOptional(
            ARTIFACT_REQUIRED, "language", "supersedesArtifactId", "failure");
    private static final Set<String> FAILURE_FIELDS = Set.of(
            "domain", "code", "retryable", "diagnosticId");
    private static final Set<String> WARNING_REQUIRED = Set.of(
            "code", "capability", "messageKey");
    private static final Set<String> WARNING_ALLOWED = withOptional(
            WARNING_REQUIRED, "diagnosticId");

    private MeetingManifestJson() {
    }

    /**
     * 将外部 JSON 解码成完整 Manifest。
     *
     * @param json 单个 UTF-16 Java 字符串承载的 JSON 文档
     * @return 通过 JSON 形状和领域不变量双重校验的 Manifest
     * @throws IllegalArgumentException 文档为空、格式错误、存在额外 token，或任意
     *                                  字段不满足公开协议时抛出
     */
    public static MeetingManifest decode(String json) {
        try {
            if (json == null) {
                throw invalid("JSON document must not be null");
            }
            return decodeDocument(json);
        } catch (IOException | RuntimeException failure) {
            // Parser、领域构造器及时间解析的原始消息可能引用外部 JSON 内容；
            // 公共边界只返回固定文本且不保留 cause，避免日志或 HTTP 映射误泄漏。
            throw new IllegalArgumentException(DECODE_FAILURE);
        }
    }

    private static MeetingManifest decodeDocument(String json) throws IOException {
        try (JsonParser parser = JSON_FACTORY.createParser(json)) {
            JsonNode tree = MAPPER.readTree(parser);
            if (tree == null) {
                throw invalid("JSON document must contain one object");
            }
            if (parser.nextToken() != null) {
                throw invalid("JSON document must not contain trailing tokens");
            }
            return decodeManifest(requireObject(tree, "manifest"));
        }
    }

    /**
     * 将完整 Manifest 编码成字段顺序稳定的单行 JSON。
     *
     * @param manifest 已通过领域构造器校验的完整 Manifest
     * @return canonical JSON 文本；所有可空字段均显式存在
     */
    public static String encode(MeetingManifest manifest) {
        try {
            Objects.requireNonNull(manifest, "manifest");
            StringWriter output = new StringWriter(1024);
            try (JsonGenerator generator = JSON_FACTORY.createGenerator(output)) {
                writeManifest(generator, manifest);
            }
            return output.toString();
        } catch (IOException | RuntimeException failure) {
            // 与解码相同，编码边界不向上层暴露字段值、生成器上下文或 cause。
            throw new IllegalStateException(ENCODE_FAILURE);
        }
    }

    private static MeetingManifest decodeManifest(ObjectNode root) {
        requireShape(root, "manifest", ROOT_REQUIRED, ROOT_ALLOWED);
        return new MeetingManifest(
                requiredText(root, "specVersion"),
                requiredText(root, "meetingId"),
                requiredInt(root, "manifestRevision"),
                decodeProfile(requiredObject(root, "profile")),
                optionalNullableText(root, "title"),
                requiredEnum(root, "status", ManifestStatus.class),
                requiredInstant(root, "startedAt"),
                requiredInstant(root, "endedAt"),
                requiredLong(root, "durationMs"),
                decodeLanguages(requiredObject(root, "languages")),
                decodeArtifacts(requiredArray(root, "artifacts")),
                decodeWarnings(requiredArray(root, "warnings")));
    }

    private static MeetingManifest.Profile decodeProfile(ObjectNode profile) {
        requireShape(profile, "profile", PROFILE_FIELDS, PROFILE_FIELDS);
        return new MeetingManifest.Profile(
                requiredText(profile, "id"),
                requiredInt(profile, "revision"));
    }

    private static MeetingManifest.Languages decodeLanguages(ObjectNode languages) {
        requireShape(languages, "languages", LANGUAGE_FIELDS, LANGUAGE_FIELDS);
        return new MeetingManifest.Languages(
                requiredText(languages, "source"),
                decodeTextArray(requiredArray(languages, "targets"), "languages.targets"));
    }

    private static List<MeetingManifest.ArtifactRef> decodeArtifacts(ArrayNode artifacts) {
        List<MeetingManifest.ArtifactRef> decoded = new ArrayList<>(artifacts.size());
        for (int index = 0; index < artifacts.size(); index++) {
            ObjectNode artifact = requireObject(artifacts.get(index), "artifacts[" + index + ']');
            requireShape(artifact, "artifact", ARTIFACT_REQUIRED, ARTIFACT_ALLOWED);
            decoded.add(new MeetingManifest.ArtifactRef(
                    requiredText(artifact, "artifactId"),
                    requiredText(artifact, "type"),
                    requiredText(artifact, "mediaType"),
                    optionalNullableText(artifact, "language"),
                    requiredInt(artifact, "revision"),
                    requiredLong(artifact, "size"),
                    requiredText(artifact, "checksum"),
                    requiredEnum(artifact, "status", MeetingManifest.ArtifactStatus.class),
                    requiredInstant(artifact, "createdAt"),
                    optionalNullableText(artifact, "supersedesArtifactId"),
                    decodeFailure(artifact.get("failure"))));
        }
        return decoded;
    }

    private static MeetingManifest.ArtifactFailure decodeFailure(JsonNode failureNode) {
        if (failureNode == null || failureNode.isNull()) {
            return null;
        }
        ObjectNode failure = requireObject(failureNode, "failure");
        requireShape(failure, "failure", FAILURE_FIELDS, FAILURE_FIELDS);
        return new MeetingManifest.ArtifactFailure(
                requiredText(failure, "domain"),
                requiredText(failure, "code"),
                requiredBoolean(failure, "retryable"),
                requiredText(failure, "diagnosticId"));
    }

    private static List<MeetingManifest.Warning> decodeWarnings(ArrayNode warnings) {
        List<MeetingManifest.Warning> decoded = new ArrayList<>(warnings.size());
        for (int index = 0; index < warnings.size(); index++) {
            ObjectNode warning = requireObject(warnings.get(index), "warnings[" + index + ']');
            requireShape(warning, "warning", WARNING_REQUIRED, WARNING_ALLOWED);
            decoded.add(new MeetingManifest.Warning(
                    requiredText(warning, "code"),
                    requiredText(warning, "capability"),
                    requiredText(warning, "messageKey"),
                    optionalNullableText(warning, "diagnosticId")));
        }
        return decoded;
    }

    /**
     * 使用显式 streaming 写入冻结字段顺序，避免 canonical 文本依赖 ObjectNode、
     * Map 或 Jackson 内部对属性顺序的实现细节。
     */
    private static void writeManifest(JsonGenerator generator, MeetingManifest manifest)
            throws IOException {
        generator.writeStartObject();
        generator.writeStringField("specVersion", manifest.specVersion());
        generator.writeStringField("meetingId", manifest.meetingId());
        generator.writeNumberField("manifestRevision", manifest.manifestRevision());
        generator.writeFieldName("profile");
        writeProfile(generator, manifest.profile());
        writeNullableStringField(generator, "title", manifest.title());
        generator.writeStringField("status", manifest.status().name());
        generator.writeStringField("startedAt", MeetingManifest.toCanonicalInstantText(
                "startedAt", manifest.startedAt()));
        generator.writeStringField("endedAt", MeetingManifest.toCanonicalInstantText(
                "endedAt", manifest.endedAt()));
        generator.writeNumberField("durationMs", manifest.durationMs());
        generator.writeFieldName("languages");
        writeLanguages(generator, manifest.languages());

        generator.writeArrayFieldStart("artifacts");
        for (MeetingManifest.ArtifactRef artifact : manifest.artifacts()) {
            writeArtifact(generator, artifact);
        }
        generator.writeEndArray();
        generator.writeArrayFieldStart("warnings");
        for (MeetingManifest.Warning warning : manifest.warnings()) {
            writeWarning(generator, warning);
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }

    private static void writeProfile(JsonGenerator generator, MeetingManifest.Profile profile)
            throws IOException {
        generator.writeStartObject();
        generator.writeStringField("id", profile.id());
        generator.writeNumberField("revision", profile.revision());
        generator.writeEndObject();
    }

    private static void writeLanguages(
            JsonGenerator generator,
            MeetingManifest.Languages languages) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("source", languages.source());
        generator.writeArrayFieldStart("targets");
        for (String target : languages.targets()) {
            generator.writeString(target);
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }

    private static void writeArtifact(
            JsonGenerator generator,
            MeetingManifest.ArtifactRef artifact) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("artifactId", artifact.artifactId());
        generator.writeStringField("type", artifact.type());
        generator.writeStringField("mediaType", artifact.mediaType());
        writeNullableStringField(generator, "language", artifact.language());
        generator.writeNumberField("revision", artifact.revision());
        generator.writeNumberField("size", artifact.size());
        generator.writeStringField("checksum", artifact.checksum());
        generator.writeStringField("status", artifact.status().name());
        generator.writeStringField("createdAt", MeetingManifest.toCanonicalInstantText(
                "artifact.createdAt", artifact.createdAt()));
        writeNullableStringField(
                generator, "supersedesArtifactId", artifact.supersedesArtifactId());
        generator.writeFieldName("failure");
        if (artifact.failure() == null) {
            generator.writeNull();
        } else {
            writeFailure(generator, artifact.failure());
        }
        generator.writeEndObject();
    }

    private static void writeFailure(
            JsonGenerator generator,
            MeetingManifest.ArtifactFailure failure) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("domain", failure.domain());
        generator.writeStringField("code", failure.code());
        generator.writeBooleanField("retryable", failure.retryable());
        generator.writeStringField("diagnosticId", failure.diagnosticId());
        generator.writeEndObject();
    }

    private static void writeWarning(
            JsonGenerator generator,
            MeetingManifest.Warning warning) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("code", warning.code());
        generator.writeStringField("capability", warning.capability());
        generator.writeStringField("messageKey", warning.messageKey());
        writeNullableStringField(generator, "diagnosticId", warning.diagnosticId());
        generator.writeEndObject();
    }

    private static void writeNullableStringField(
            JsonGenerator generator,
            String field,
            String value) throws IOException {
        if (value == null) {
            generator.writeNullField(field);
        } else {
            generator.writeStringField(field, value);
        }
    }

    private static void requireShape(
            ObjectNode object,
            String objectName,
            Set<String> required,
            Set<String> allowed) {
        for (String field : required) {
            if (!object.has(field)) {
                throw invalid(objectName + " is missing required field " + field);
            }
        }
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                throw invalid(objectName + " contains unknown field " + field);
            }
        }
    }

    private static ObjectNode requiredObject(ObjectNode parent, String field) {
        return requireObject(parent.get(field), field);
    }

    private static ObjectNode requireObject(JsonNode node, String name) {
        if (!(node instanceof ObjectNode object)) {
            throw invalid(name + " must be an object");
        }
        return object;
    }

    private static ArrayNode requiredArray(ObjectNode parent, String field) {
        JsonNode node = parent.get(field);
        if (!(node instanceof ArrayNode array)) {
            throw invalid(field + " must be an array");
        }
        return array;
    }

    private static List<String> decodeTextArray(ArrayNode array, String name) {
        List<String> values = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonNode node = array.get(index);
            if (!node.isTextual()) {
                throw invalid(name + '[' + index + "] must be a string");
            }
            values.add(node.textValue());
        }
        return values;
    }

    private static String requiredText(ObjectNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isTextual()) {
            throw invalid(field + " must be a string");
        }
        return node.textValue();
    }

    private static String optionalNullableText(ObjectNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalid(field + " must be a string or null");
        }
        return node.textValue();
    }

    private static int requiredInt(ObjectNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalid(field + " must be a 32-bit integer");
        }
        return node.intValue();
    }

    private static long requiredLong(ObjectNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid(field + " must be a 64-bit integer");
        }
        return node.longValue();
    }

    private static boolean requiredBoolean(ObjectNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isBoolean()) {
            throw invalid(field + " must be a boolean");
        }
        return node.booleanValue();
    }

    private static Instant requiredInstant(ObjectNode parent, String field) {
        String value = requiredText(parent, field);
        if (!RFC3339_INSTANT.matcher(value).matches()) {
            throw invalid(field + " must use the frozen RFC 3339 subset");
        }
        Instant instant = OffsetDateTime.parse(value).toInstant();
        return MeetingManifest.requireProtocolInstant(field, instant);
    }

    private static <E extends Enum<E>> E requiredEnum(
            ObjectNode parent,
            String field,
            Class<E> enumType) {
        String value = requiredText(parent, field);
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException ignored) {
            throw invalid(field + " is outside the public protocol enum");
        }
    }

    private static Set<String> withOptional(Set<String> required, String... optional) {
        Set<String> fields = new HashSet<>(required);
        fields.addAll(List.of(optional));
        return Set.copyOf(fields);
    }

    private static IllegalArgumentException invalid(String reason) {
        // 内部也不保留 Jackson/时间解析 cause，防止未来重构时绕过 decode 外壳。
        return new IllegalArgumentException(reason);
    }
}
