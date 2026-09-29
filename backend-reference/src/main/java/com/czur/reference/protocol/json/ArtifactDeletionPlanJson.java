package com.czur.reference.protocol.json;

import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.deletion.ArtifactDeletionPlan.SourceFinalizationWorkflow;
import com.czur.reference.application.deletion.ArtifactDeletionTarget;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.domain.meeting.MeetingIdPolicy;
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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@link ArtifactDeletionPlan} 的严格 canonical JSON、UTF-8、摘要和 Workflow 边界。
 *
 * <p>对象字段顺序、字符串转义、整数表示和 UTC 时间文本都必须已经是 canonical 形式；
 * 未知、重复、缺失字段、空白、尾随内容、非法 UTF-8、数值强制转换及超限输入全部关闭式
 * 拒绝。targets 是集合语义，输入数组允许任意排列，解码后由领域对象排序，因此不同数组
 * 排列会收敛为同一 canonical JSON 和 digest。</p>
 *
 * <p>公开异常仅返回固定文本且不保留 cause，避免 meetingId、artifactId、binding、
 * checksum、canonical 正文或 digest 出现在日志聚合器。该 codec 不访问数据库、文件
 * Store 或当前时钟，也不判断空计划的业务来源是否合法。</p>
 */
public final class ArtifactDeletionPlanJson {
    public static final String SPEC_VERSION = ArtifactDeletionPlan.CHECKPOINT_TYPE;
    public static final int MAX_PLAN_BYTES = 65_536;

    private static final String DECODE_FAILURE = "Artifact deletion plan JSON is invalid";
    private static final String ENCODE_FAILURE = "Artifact deletion plan JSON encoding failed";
    private static final Pattern CANONICAL_INSTANT = Pattern.compile(
            "^(?:(?:0000|9999)-[0-9]{2}-[0-9]{2}T"
                    + "(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
                    + "(?:\\.[0-9]{1,6})?Z|"
                    + "(?!(?:0000|9999)-)[0-9]{4}-[0-9]{2}-[0-9]{2}T"
                    + "(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
                    + "(?:\\.[0-9]{1,6})?Z)$");
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper(JSON_FACTORY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final List<String> ROOT_FIELDS = List.of(
            "specVersion", "meetingId", "acceptedAt", "sourceManifestRevision",
            "sourceFinalizationWorkflow", "targets");
    private static final List<String> SOURCE_WORKFLOW_FIELDS = List.of(
            "meetingId", "stepType", "stepKey", "checkpointType", "contentDigest");
    private static final List<String> TARGET_FIELDS = List.of("artifactId", "spec");
    private static final List<String> SPEC_FIELDS = List.of(
            "artifactId", "meetingId", "artifactType", "mediaType", "language",
            "revision", "expectedSize", "expectedChecksum");

    private ArtifactDeletionPlanJson() {
    }

    /**
     * 解码单个严格 canonical JSON 文档。
     *
     * <p>只有 targets 数组顺序可变化；对象字段顺序、空白、转义、数字和时间表示都必须
     * 已经规范化。调用方若持有任意等价对象，应先解码再调用 {@link #encode} 取得冻结
     * 字节，不能直接把原始文本作为持久化 Definition。</p>
     */
    public static ArtifactDeletionPlan decode(String json) {
        try {
            if (json == null || json.isEmpty()) {
                throw invalid();
            }
            int length = MeetingIdPolicy.utf8Length(json);
            if (length > MAX_PLAN_BYTES) {
                throw invalid();
            }
            try (JsonParser parser = JSON_FACTORY.createParser(json)) {
                JsonNode tree = MAPPER.readTree(parser);
                if (tree == null || parser.nextToken() != null) {
                    throw invalid();
                }
                // Jackson 的紧凑重写保留对象/数组顺序，却规范化空白、转义和数字表示。
                // 与原文比较可在允许 targets 排列变化的同时拒绝其它非 canonical 表示。
                if (!json.equals(MAPPER.writeValueAsString(tree))) {
                    throw invalid();
                }
                return decodePlan(requireObject(tree));
            }
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException(DECODE_FAILURE);
        }
    }

    /** 严格解码 UTF-8 字节；非法编码、BOM、多值或超限输入全部拒绝。 */
    public static ArtifactDeletionPlan decodeUtf8(byte[] json) {
        try {
            if (json == null || json.length == 0 || json.length > MAX_PLAN_BYTES) {
                throw invalid();
            }
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(json))
                    .toString();
            return decode(text);
        } catch (CharacterCodingException | RuntimeException failure) {
            throw new IllegalArgumentException(DECODE_FAILURE);
        }
    }

    /** 编码为字段和目标顺序固定、无空白、无尾随换行的 canonical JSON。 */
    public static String encode(ArtifactDeletionPlan plan) {
        try {
            if (plan == null) {
                throw invalid();
            }
            StringWriter output = new StringWriter(2048);
            try (JsonGenerator generator = JSON_FACTORY.createGenerator(output)) {
                writePlan(generator, plan);
            }
            String json = output.toString();
            if (MeetingIdPolicy.utf8Length(json) > MAX_PLAN_BYTES) {
                throw invalid();
            }
            return json;
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException(ENCODE_FAILURE);
        }
    }

    public static byte[] encodeUtf8(ArtifactDeletionPlan plan) {
        return encode(plan).getBytes(StandardCharsets.UTF_8);
    }

    /** 返回 canonical UTF-8 字节的小写 SHA-256 摘要。 */
    public static String sha256(ArtifactDeletionPlan plan) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(encodeUtf8(plan)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256");
        }
    }

    /**
     * 从冻结计划生成唯一的初始 Workflow Definition。
     *
     * <p>stepType 固定为 {@code artifact-deletion}，checkpointType 固定为
     * {@code artifact-deletion-plan/v1}，首次可领取时间和注册时间均使用 acceptedAt，
     * 不读取当前时钟。</p>
     */
    public static WorkflowStep workflowStep(ArtifactDeletionPlan plan) {
        String payload = encode(plan);
        return WorkflowStep.pending(
                plan.workflowKey(),
                SPEC_VERSION,
                payload,
                sha256(plan),
                plan.acceptedAt(),
                plan.acceptedAt());
    }

    private static ArtifactDeletionPlan decodePlan(ObjectNode root) {
        requireCanonicalShape(root, ROOT_FIELDS);
        if (!SPEC_VERSION.equals(requiredText(root, "specVersion"))) {
            throw invalid();
        }
        ArrayNode targetNodes = requiredArray(root, "targets");
        if (targetNodes.size() > ArtifactDeletionPlan.MAX_TARGETS) {
            throw invalid();
        }
        List<ArtifactDeletionTarget> targets = new ArrayList<>(targetNodes.size());
        for (JsonNode target : targetNodes) {
            targets.add(decodeTarget(requireObject(target)));
        }
        return new ArtifactDeletionPlan(
                requiredText(root, "meetingId"),
                requiredCanonicalInstant(root, "acceptedAt"),
                requiredNullablePositiveInt(root, "sourceManifestRevision"),
                requiredNullableSourceWorkflow(root, "sourceFinalizationWorkflow"),
                targets);
    }

    private static SourceFinalizationWorkflow requiredNullableSourceWorkflow(
            ObjectNode root,
            String field) {
        JsonNode value = root.get(field);
        if (value == null) {
            throw invalid();
        }
        if (value.isNull()) {
            return null;
        }
        ObjectNode source = requireObject(value);
        requireCanonicalShape(source, SOURCE_WORKFLOW_FIELDS);
        return new SourceFinalizationWorkflow(
                new WorkflowStep.Key(
                        requiredText(source, "meetingId"),
                        requiredText(source, "stepType"),
                        requiredText(source, "stepKey")),
                requiredText(source, "checkpointType"),
                requiredText(source, "contentDigest"));
    }

    private static ArtifactDeletionTarget decodeTarget(ObjectNode target) {
        requireCanonicalShape(target, TARGET_FIELDS);
        ObjectNode spec = requiredObject(target, "spec");
        requireCanonicalShape(spec, SPEC_FIELDS);
        return new ArtifactDeletionTarget(
                requiredText(target, "artifactId"),
                new WriteIntentSpec(
                        requiredText(spec, "artifactId"),
                        requiredText(spec, "meetingId"),
                        requiredText(spec, "artifactType"),
                        requiredText(spec, "mediaType"),
                        requiredNullableText(spec, "language"),
                        requiredInt(spec, "revision"),
                        requiredLong(spec, "expectedSize"),
                        requiredText(spec, "expectedChecksum")));
    }

    private static void writePlan(JsonGenerator generator, ArtifactDeletionPlan plan)
            throws IOException {
        generator.writeStartObject();
        generator.writeStringField("specVersion", SPEC_VERSION);
        generator.writeStringField("meetingId", plan.meetingId());
        generator.writeStringField("acceptedAt", plan.acceptedAt().toString());
        if (plan.sourceManifestRevision() == null) {
            generator.writeNullField("sourceManifestRevision");
        } else {
            generator.writeNumberField(
                    "sourceManifestRevision", plan.sourceManifestRevision());
        }
        writeSourceWorkflow(generator, plan.sourceFinalizationWorkflow());
        generator.writeArrayFieldStart("targets");
        for (ArtifactDeletionTarget target : plan.targets()) {
            writeTarget(generator, target);
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }

    private static void writeSourceWorkflow(
            JsonGenerator generator,
            SourceFinalizationWorkflow source) throws IOException {
        if (source == null) {
            generator.writeNullField("sourceFinalizationWorkflow");
            return;
        }
        generator.writeObjectFieldStart("sourceFinalizationWorkflow");
        generator.writeStringField("meetingId", source.key().meetingId());
        generator.writeStringField("stepType", source.key().stepType());
        generator.writeStringField("stepKey", source.key().stepKey());
        generator.writeStringField("checkpointType", source.checkpointType());
        generator.writeStringField("contentDigest", source.contentDigest());
        generator.writeEndObject();
    }

    private static void writeTarget(
            JsonGenerator generator,
            ArtifactDeletionTarget target) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("artifactId", target.artifactId());
        generator.writeObjectFieldStart("spec");
        WriteIntentSpec spec = target.spec();
        generator.writeStringField("artifactId", spec.artifactId());
        generator.writeStringField("meetingId", spec.meetingId());
        generator.writeStringField("artifactType", spec.artifactType());
        generator.writeStringField("mediaType", spec.mediaType());
        if (spec.language() == null) {
            generator.writeNullField("language");
        } else {
            generator.writeStringField("language", spec.language());
        }
        generator.writeNumberField("revision", spec.revision());
        generator.writeNumberField("expectedSize", spec.expectedSize());
        generator.writeStringField("expectedChecksum", spec.expectedChecksum());
        generator.writeEndObject();
        generator.writeEndObject();
    }

    private static void requireCanonicalShape(ObjectNode object, List<String> fields) {
        Iterator<String> actual = object.fieldNames();
        for (String expected : fields) {
            if (!actual.hasNext() || !expected.equals(actual.next())) {
                throw invalid();
            }
        }
        if (actual.hasNext()) {
            throw invalid();
        }
    }

    private static ObjectNode requiredObject(ObjectNode parent, String field) {
        return requireObject(parent.get(field));
    }

    private static ObjectNode requireObject(JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            throw invalid();
        }
        return object;
    }

    private static ArrayNode requiredArray(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (!(value instanceof ArrayNode array)) {
            throw invalid();
        }
        return array;
    }

    private static String requiredText(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static String requiredNullableText(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null) {
            throw invalid();
        }
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static Integer requiredNullablePositiveInt(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null) {
            throw invalid();
        }
        if (value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw invalid();
        }
        return value.intValue();
    }

    private static int requiredInt(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid();
        }
        return value.intValue();
    }

    private static long requiredLong(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid();
        }
        return value.longValue();
    }

    private static Instant requiredCanonicalInstant(ObjectNode parent, String field) {
        String value = requiredText(parent, field);
        if (!CANONICAL_INSTANT.matcher(value).matches()) {
            throw invalid();
        }
        try {
            Instant instant = Instant.parse(value);
            if (!value.equals(instant.toString())) {
                throw invalid();
            }
            return instant;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(DECODE_FAILURE);
    }
}
