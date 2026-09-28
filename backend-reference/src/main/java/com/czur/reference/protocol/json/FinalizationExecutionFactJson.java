package com.czur.reference.protocol.json;

import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.finalization.ProductProfileSnapshot;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.capability.ProductProfile;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * {@link FinalizationExecutionFact} 的严格 canonical JSON 与 SHA-256 边界。
 *
 * <p>解码使用仓库既有 Jackson tree/streaming 基础设施风格，但不把 Jackson 类型或
 * 注解带入 application/domain。所有对象采用关闭式字段集合，重复、未知、缺失字段、
 * 错误类型、数值强制转换、非法枚举、尾随 token 和非法时间均被拒绝；公开异常只
 * 返回固定文本且不保留 cause，避免 meetingId、title、capability 或映射正文泄漏。</p>
 *
 * <p>编码使用显式 streaming 字段顺序。Profile 的集合与 map key、Plan 的决策都已
 * 在领域事实中排序，map 中的 workflow/artifact 列表则保留冻结配置顺序。因此同一
 * 执行事实总能得到完全相同的 UTF-8 字节和 {@code sha256:} 小写十六进制摘要；输入
 * JSON 的对象字段顺序不会影响重新编码结果。canonical 正文及摘要的存储事务属于
 * 后续持久化切片，不属于本 codec。</p>
 */
public final class FinalizationExecutionFactJson {
    /** 当前冻结执行事实协议版本。 */
    public static final String SPEC_VERSION = "finalization-execution-fact/v1";

    private static final String DECODE_FAILURE = "Finalization execution fact JSON is invalid";
    private static final String ENCODE_FAILURE = "Finalization execution fact JSON encoding failed";
    /** 固定四位年份、禁止 24:00/闰秒、小数秒最多六位的 RFC 3339 子集。 */
    private static final Pattern POSTGRES_MICROSECOND_INSTANT = Pattern.compile(
            "^(?:(?:0000|9999)-[0-9]{2}-[0-9]{2}T"
                    + "(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
                    + "(?:\\.[0-9]{1,6})?Z|"
                    + "(?!(?:0000|9999)-)[0-9]{4}-[0-9]{2}-[0-9]{2}T"
                    + "(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
                    + "(?:\\.[0-9]{1,6})?"
                    + "(?:Z|[+-](?:(?:0[0-9]|1[0-7]):[0-5][0-9]|18:00)))$");
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper(JSON_FACTORY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Set<String> ROOT_FIELDS = Set.of(
            "specVersion", "meetingId", "startedAt", "title",
            "productProfile", "capabilityPlan");
    private static final Set<String> PROFILE_FIELDS = Set.of(
            "id", "revision", "requiredCapabilities", "optionalCapabilities",
            "dependencies", "missingPolicies", "fallbackCapabilities",
            "affectedWorkflows", "producedArtifacts");
    private static final Set<String> PLAN_FIELDS = Set.of("planRevision", "decisions");
    private static final Set<String> DECISION_FIELDS = Set.of(
            "capability", "availability", "requiredByProfile", "action",
            "fallbackCapability", "affectedWorkflows", "producedArtifacts",
            "warningCode", "planRevision");

    private FinalizationExecutionFactJson() {
    }

    /**
     * 解码单个完整 JSON 文档并重新执行全部 Profile/Plan 对应校验。
     *
     * @param json 任意字段顺序的单个 JSON 对象文档
     * @return 通过协议形状、领域值域及映射一致性校验的执行事实
     * @throws IllegalArgumentException 文档为空、损坏、漂移或不满足关闭式协议
     */
    public static FinalizationExecutionFact decode(String json) {
        try {
            if (json == null) {
                throw invalid("document must not be null");
            }
            try (JsonParser parser = JSON_FACTORY.createParser(json)) {
                JsonNode tree = MAPPER.readTree(parser);
                if (tree == null || parser.nextToken() != null) {
                    throw invalid("document must contain exactly one value");
                }
                return decodeFact(requireObject(tree, "root"));
            }
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException(DECODE_FAILURE);
        }
    }

    /**
     * 编码为字段顺序固定、无尾随换行的 canonical JSON 文本。
     *
     * @param fact 已通过完整领域校验的冻结执行事实
     * @return canonical JSON 文本
     * @throws IllegalStateException 编码器或事实状态异常
     */
    public static String encode(FinalizationExecutionFact fact) {
        try {
            if (fact == null) {
                throw new IllegalArgumentException("fact must not be null");
            }
            StringWriter output = new StringWriter(2048);
            try (JsonGenerator generator = JSON_FACTORY.createGenerator(output)) {
                writeFact(generator, fact);
            }
            return output.toString();
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException(ENCODE_FAILURE);
        }
    }

    /**
     * 返回 canonical JSON 的 UTF-8 字节副本。
     *
     * <p>显式提供字节入口可防止调用方使用平台默认字符集计算不同摘要。</p>
     */
    public static byte[] encodeUtf8(FinalizationExecutionFact fact) {
        return encode(fact).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 计算 canonical UTF-8 字节的 SHA-256，返回 {@code sha256:} 加 64 位小写
     * 十六进制文本。
     */
    public static String sha256(FinalizationExecutionFact fact) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encodeUtf8(fact));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256");
        }
    }

    private static FinalizationExecutionFact decodeFact(ObjectNode root) {
        requireShape(root, ROOT_FIELDS);
        String specVersion = requiredText(root, "specVersion");
        if (!SPEC_VERSION.equals(specVersion)) {
            throw invalid("unsupported specVersion");
        }
        return new FinalizationExecutionFact(
                requiredText(root, "meetingId"),
                requiredInstant(root, "startedAt"),
                requiredNullableText(root, "title"),
                decodeProfile(requiredObject(root, "productProfile")),
                decodePlan(requiredObject(root, "capabilityPlan")));
    }

    private static ProductProfileSnapshot decodeProfile(ObjectNode profile) {
        requireShape(profile, PROFILE_FIELDS);
        return new ProductProfileSnapshot(
                requiredText(profile, "id"),
                requiredInt(profile, "revision"),
                decodeUniqueTextSet(
                        requiredArray(profile, "requiredCapabilities"),
                        "requiredCapabilities"),
                decodeUniqueTextSet(
                        requiredArray(profile, "optionalCapabilities"),
                        "optionalCapabilities"),
                decodeSetMap(requiredObject(profile, "dependencies"), "dependencies"),
                decodeEnumMap(
                        requiredObject(profile, "missingPolicies"),
                        ProductProfile.MissingCapabilityPolicy.class),
                decodeTextMap(requiredObject(profile, "fallbackCapabilities")),
                decodeListMap(requiredObject(profile, "affectedWorkflows"), "affectedWorkflows"),
                decodeListMap(requiredObject(profile, "producedArtifacts"), "producedArtifacts"));
    }

    private static CapabilityPlan decodePlan(ObjectNode plan) {
        requireShape(plan, PLAN_FIELDS);
        long planRevision = requiredLong(plan, "planRevision");
        ArrayNode decisions = requiredArray(plan, "decisions");
        List<CapabilityDecision> decoded = new ArrayList<>(decisions.size());
        for (int index = 0; index < decisions.size(); index++) {
            ObjectNode decision = requireObject(decisions.get(index), "decision");
            requireShape(decision, DECISION_FIELDS);
            decoded.add(new CapabilityDecision(
                    requiredText(decision, "capability"),
                    requiredEnum(decision, "availability", CapabilityDecision.Availability.class),
                    requiredBoolean(decision, "requiredByProfile"),
                    requiredEnum(decision, "action", CapabilityDecision.Action.class),
                    requiredNullableText(decision, "fallbackCapability"),
                    decodeTextList(requiredArray(decision, "affectedWorkflows"),
                            "affectedWorkflows"),
                    decodeTextList(requiredArray(decision, "producedArtifacts"),
                            "producedArtifacts"),
                    requiredNullableText(decision, "warningCode"),
                    requiredLong(decision, "planRevision")));
        }
        return new CapabilityPlan(planRevision, decoded);
    }

    private static void writeFact(JsonGenerator generator, FinalizationExecutionFact fact)
            throws IOException {
        generator.writeStartObject();
        generator.writeStringField("specVersion", SPEC_VERSION);
        generator.writeStringField("meetingId", fact.meetingId());
        generator.writeStringField("startedAt", fact.startedAt().toString());
        writeNullableStringField(generator, "title", fact.title());
        generator.writeFieldName("productProfile");
        writeProfile(generator, fact.productProfile());
        generator.writeFieldName("capabilityPlan");
        writePlan(generator, fact.capabilityPlan());
        generator.writeEndObject();
    }

    private static void writeProfile(JsonGenerator generator, ProductProfileSnapshot profile)
            throws IOException {
        generator.writeStartObject();
        generator.writeStringField("id", profile.id());
        generator.writeNumberField("revision", profile.revision());
        writeTextArrayField(generator, "requiredCapabilities", profile.requiredCapabilities());
        writeTextArrayField(generator, "optionalCapabilities", profile.optionalCapabilities());

        generator.writeObjectFieldStart("dependencies");
        for (Map.Entry<String, Set<String>> entry : profile.dependencies().entrySet()) {
            writeTextArrayField(generator, entry.getKey(), entry.getValue());
        }
        generator.writeEndObject();

        generator.writeObjectFieldStart("missingPolicies");
        for (Map.Entry<String, ProductProfile.MissingCapabilityPolicy> entry
                : profile.missingPolicies().entrySet()) {
            generator.writeStringField(entry.getKey(), entry.getValue().name());
        }
        generator.writeEndObject();

        generator.writeObjectFieldStart("fallbackCapabilities");
        for (Map.Entry<String, String> entry : profile.fallbackCapabilities().entrySet()) {
            generator.writeStringField(entry.getKey(), entry.getValue());
        }
        generator.writeEndObject();

        writeListMap(generator, "affectedWorkflows", profile.affectedWorkflows());
        writeListMap(generator, "producedArtifacts", profile.producedArtifacts());
        generator.writeEndObject();
    }

    private static void writePlan(JsonGenerator generator, CapabilityPlan plan) throws IOException {
        generator.writeStartObject();
        generator.writeNumberField("planRevision", plan.planRevision());
        generator.writeArrayFieldStart("decisions");
        for (CapabilityDecision decision : plan.decisions()) {
            generator.writeStartObject();
            generator.writeStringField("capability", decision.capability());
            generator.writeStringField("availability", decision.availability().name());
            generator.writeBooleanField("requiredByProfile", decision.requiredByProfile());
            generator.writeStringField("action", decision.action().name());
            writeNullableStringField(
                    generator, "fallbackCapability", decision.fallbackCapability());
            writeTextArrayField(
                    generator, "affectedWorkflows", decision.affectedWorkflows());
            writeTextArrayField(
                    generator, "producedArtifacts", decision.producedArtifacts());
            writeNullableStringField(generator, "warningCode", decision.warningCode());
            generator.writeNumberField("planRevision", decision.planRevision());
            generator.writeEndObject();
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }

    private static void writeListMap(
            JsonGenerator generator,
            String field,
            Map<String, List<String>> values) throws IOException {
        generator.writeObjectFieldStart(field);
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            writeTextArrayField(generator, entry.getKey(), entry.getValue());
        }
        generator.writeEndObject();
    }

    private static void writeTextArrayField(
            JsonGenerator generator,
            String field,
            Iterable<String> values) throws IOException {
        generator.writeArrayFieldStart(field);
        for (String value : values) {
            generator.writeString(value);
        }
        generator.writeEndArray();
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

    private static Set<String> decodeUniqueTextSet(ArrayNode array, String name) {
        List<String> values = decodeTextList(array, name);
        Set<String> unique = new HashSet<>(values);
        if (unique.size() != values.size()) {
            throw invalid(name + " must not contain duplicates");
        }
        return unique;
    }

    private static Map<String, Set<String>> decodeSetMap(ObjectNode object, String name) {
        Map<String, Set<String>> values = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            values.put(field.getKey(), decodeUniqueTextSet(
                    requireArray(field.getValue(), name + " value"), name + " value"));
        }
        return values;
    }

    private static <E extends Enum<E>> Map<String, E> decodeEnumMap(
            ObjectNode object,
            Class<E> enumType) {
        Map<String, E> values = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isTextual()) {
                throw invalid("map value must be a string");
            }
            values.put(field.getKey(), parseEnum(field.getValue().textValue(), enumType));
        }
        return values;
    }

    private static Map<String, String> decodeTextMap(ObjectNode object) {
        Map<String, String> values = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isTextual()) {
                throw invalid("map value must be a string");
            }
            values.put(field.getKey(), field.getValue().textValue());
        }
        return values;
    }

    private static Map<String, List<String>> decodeListMap(ObjectNode object, String name) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            values.put(field.getKey(), decodeTextList(
                    requireArray(field.getValue(), name + " value"), name + " value"));
        }
        return values;
    }

    private static List<String> decodeTextList(ArrayNode array, String name) {
        List<String> values = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonNode value = array.get(index);
            if (!value.isTextual()) {
                throw invalid(name + " item must be a string");
            }
            values.add(value.textValue());
        }
        return values;
    }

    private static void requireShape(ObjectNode object, Set<String> fields) {
        for (String field : fields) {
            if (!object.has(field)) {
                throw invalid("object is missing a required field");
            }
        }
        Iterator<String> actual = object.fieldNames();
        while (actual.hasNext()) {
            if (!fields.contains(actual.next())) {
                throw invalid("object contains an unknown field");
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
        return requireArray(parent.get(field), field);
    }

    private static ArrayNode requireArray(JsonNode node, String name) {
        if (!(node instanceof ArrayNode array)) {
            throw invalid(name + " must be an array");
        }
        return array;
    }

    private static String requiredText(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(field + " must be a string");
        }
        return value.textValue();
    }

    private static String requiredNullableText(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null) {
            throw invalid(field + " is required");
        }
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field + " must be a string or null");
        }
        return value.textValue();
    }

    private static int requiredInt(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(field + " must be a 32-bit integer");
        }
        return value.intValue();
    }

    private static long requiredLong(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(field + " must be a 64-bit integer");
        }
        return value.longValue();
    }

    private static boolean requiredBoolean(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid(field + " must be a boolean");
        }
        return value.booleanValue();
    }

    private static Instant requiredInstant(ObjectNode parent, String field) {
        String value = requiredText(parent, field);
        if (!POSTGRES_MICROSECOND_INSTANT.matcher(value).matches()) {
            throw invalid(field + " must use the frozen RFC 3339 subset");
        }
        return OffsetDateTime.parse(value).toInstant();
    }

    private static <E extends Enum<E>> E requiredEnum(
            ObjectNode parent,
            String field,
            Class<E> enumType) {
        return parseEnum(requiredText(parent, field), enumType);
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> enumType) {
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException failure) {
            throw invalid("value is outside the frozen enum");
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason);
    }
}
