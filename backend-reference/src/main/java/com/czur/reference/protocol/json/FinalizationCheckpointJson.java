package com.czur.reference.protocol.json;

import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.finalization.FinalizationCheckpoint.ArtifactMaterial;
import com.czur.reference.application.finalization.FinalizationCommitPlan;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.application.port.entitlement.UsageUnit;
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
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@link FinalizationCheckpoint} 的关闭式 canonical JSON、UTF-8 与摘要边界。
 *
 * <p>对象字段顺序固定，Artifact 顺序由领域对象收敛；内容字节使用带 padding 的
 * RFC 4648 canonical Base64。解码拒绝重复、未知、缺失字段、错误类型、数值强制、
 * 非 canonical Base64、非法 UTF-8/UTF-16、亚微秒时间和尾随 token。完整 checkpoint
 * 的 canonical UTF-8 上限为 65536 bytes，每份 decoded Mock 内容上限由领域对象固定
 * 为 4096 bytes。</p>
 */
public final class FinalizationCheckpointJson {
    public static final String SPEC_VERSION = FinalizationCheckpoint.CHECKPOINT_TYPE;
    public static final int MAX_CHECKPOINT_BYTES = 65_536;

    private static final String DECODE_FAILURE = "Finalization checkpoint JSON is invalid";
    private static final String ENCODE_FAILURE = "Finalization checkpoint JSON encoding failed";
    private static final Pattern MICROSECOND_INSTANT = Pattern.compile(
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
            "specVersion", "meetingId", "inputBinding", "inputFactDigest",
            "executionFactDigest", "artifacts", "commitPlan");
    private static final Set<String> ARTIFACT_FIELDS = Set.of(
            "spec", "contentBase64", "claim");
    private static final Set<String> SPEC_FIELDS = Set.of(
            "artifactId", "meetingId", "artifactType", "mediaType", "language",
            "revision", "expectedSize", "expectedChecksum");
    private static final Set<String> CLAIM_FIELDS = Set.of("size", "checksum");
    private static final Set<String> COMMIT_FIELDS = Set.of(
            "meetingId", "deploymentScope", "subjectScope", "operationScope",
            "idempotencyKey", "requestDigest", "reservationId", "expectedVersion",
            "usageUnit", "usageAmount", "sourceInputBinding", "sourceInputDigest",
            "sourceAcceptedAt");

    private FinalizationCheckpointJson() {
    }

    /** 解码单个完整 String 文档；Java 输入中的非法 surrogate 关闭式拒绝。 */
    public static FinalizationCheckpoint decode(String json) {
        try {
            if (json == null || json.isEmpty()) {
                throw invalid();
            }
            MeetingIdPolicy.utf8Length(json);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CHECKPOINT_BYTES) {
                throw invalid();
            }
            try (JsonParser parser = JSON_FACTORY.createParser(json)) {
                JsonNode tree = MAPPER.readTree(parser);
                if (tree == null || parser.nextToken() != null) {
                    throw invalid();
                }
                return decodeCheckpoint(requireObject(tree));
            }
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException(DECODE_FAILURE);
        }
    }

    /** 严格解码 UTF-8 字节；损坏、多值或超限文档一律拒绝。 */
    public static FinalizationCheckpoint decodeUtf8(byte[] json) {
        try {
            if (json == null || json.length == 0 || json.length > MAX_CHECKPOINT_BYTES) {
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

    /** 编码为无空白、无尾随换行且字段顺序固定的 canonical JSON。 */
    public static String encode(FinalizationCheckpoint checkpoint) {
        try {
            if (checkpoint == null) {
                throw invalid();
            }
            StringWriter output = new StringWriter(4096);
            try (JsonGenerator generator = JSON_FACTORY.createGenerator(output)) {
                writeCheckpoint(generator, checkpoint);
            }
            String json = output.toString();
            MeetingIdPolicy.utf8Length(json);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CHECKPOINT_BYTES) {
                throw invalid();
            }
            return json;
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException(ENCODE_FAILURE);
        }
    }

    public static byte[] encodeUtf8(FinalizationCheckpoint checkpoint) {
        return encode(checkpoint).getBytes(StandardCharsets.UTF_8);
    }

    public static String sha256(FinalizationCheckpoint checkpoint) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(encodeUtf8(checkpoint)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256");
        }
    }

    /**
     * 只依赖 checkpoint 恢复完整 Workflow Definition。调度时间使用冻结的
     * sourceAcceptedAt，不读取当前时钟。
     */
    public static WorkflowStep workflowStep(FinalizationCheckpoint checkpoint) {
        String payload = encode(checkpoint);
        Instant registeredAt = checkpoint.registeredAt();
        return WorkflowStep.pending(
                checkpoint.workflowKey(),
                SPEC_VERSION,
                payload,
                sha256(checkpoint),
                registeredAt,
                registeredAt);
    }

    private static FinalizationCheckpoint decodeCheckpoint(ObjectNode root) {
        requireShape(root, ROOT_FIELDS);
        if (!SPEC_VERSION.equals(requiredText(root, "specVersion"))) {
            throw invalid();
        }
        ArrayNode artifactNodes = requiredArray(root, "artifacts");
        if (artifactNodes.isEmpty()
                || artifactNodes.size() > FinalizationCheckpoint.MAX_ARTIFACTS) {
            throw invalid();
        }
        List<ArtifactMaterial> artifacts = new ArrayList<>(artifactNodes.size());
        for (JsonNode node : artifactNodes) {
            artifacts.add(decodeArtifact(requireObject(node)));
        }
        FinalizationCommitPlan commitPlan = decodeCommitPlan(
                requiredObject(root, "commitPlan"));
        return new FinalizationCheckpoint(
                requiredText(root, "meetingId"),
                requiredText(root, "inputBinding"),
                requiredText(root, "inputFactDigest"),
                requiredText(root, "executionFactDigest"),
                artifacts,
                commitPlan);
    }

    private static ArtifactMaterial decodeArtifact(ObjectNode artifact) {
        requireShape(artifact, ARTIFACT_FIELDS);
        ObjectNode spec = requiredObject(artifact, "spec");
        requireShape(spec, SPEC_FIELDS);
        WriteIntentSpec writeSpec = new WriteIntentSpec(
                requiredText(spec, "artifactId"),
                requiredText(spec, "meetingId"),
                requiredText(spec, "artifactType"),
                requiredText(spec, "mediaType"),
                requiredNullableText(spec, "language"),
                requiredInt(spec, "revision"),
                requiredLong(spec, "expectedSize"),
                requiredText(spec, "expectedChecksum"));
        byte[] content = decodeCanonicalBase64(requiredText(artifact, "contentBase64"));
        ObjectNode claim = requiredObject(artifact, "claim");
        requireShape(claim, CLAIM_FIELDS);
        return new ArtifactMaterial(
                writeSpec,
                content,
                new ContentClaim(
                        requiredLong(claim, "size"),
                        requiredText(claim, "checksum")));
    }

    private static FinalizationCommitPlan decodeCommitPlan(ObjectNode commit) {
        requireShape(commit, COMMIT_FIELDS);
        return FinalizationCommitPlan.restore(
                requiredText(commit, "meetingId"),
                requiredText(commit, "deploymentScope"),
                requiredText(commit, "subjectScope"),
                requiredText(commit, "operationScope"),
                requiredText(commit, "idempotencyKey"),
                requiredText(commit, "requestDigest"),
                requiredText(commit, "reservationId"),
                requiredLong(commit, "expectedVersion"),
                requiredEnum(commit, "usageUnit", UsageUnit.class),
                requiredLong(commit, "usageAmount"),
                requiredText(commit, "sourceInputBinding"),
                requiredText(commit, "sourceInputDigest"),
                requiredInstant(commit, "sourceAcceptedAt"));
    }

    private static void writeCheckpoint(
            JsonGenerator generator,
            FinalizationCheckpoint checkpoint) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("specVersion", SPEC_VERSION);
        generator.writeStringField("meetingId", checkpoint.meetingId());
        generator.writeStringField("inputBinding", checkpoint.inputBinding());
        generator.writeStringField("inputFactDigest", checkpoint.inputFactDigest());
        generator.writeStringField("executionFactDigest", checkpoint.executionFactDigest());
        generator.writeArrayFieldStart("artifacts");
        for (ArtifactMaterial artifact : checkpoint.artifacts()) {
            writeArtifact(generator, artifact);
        }
        generator.writeEndArray();
        generator.writeFieldName("commitPlan");
        writeCommitPlan(generator, checkpoint.commitPlan());
        generator.writeEndObject();
    }

    private static void writeArtifact(
            JsonGenerator generator,
            ArtifactMaterial artifact) throws IOException {
        generator.writeStartObject();
        generator.writeObjectFieldStart("spec");
        WriteIntentSpec spec = artifact.spec();
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
        generator.writeStringField("contentBase64", Base64.getEncoder().encodeToString(
                artifact.canonicalContentBytes()));
        generator.writeObjectFieldStart("claim");
        generator.writeNumberField("size", artifact.claim().size());
        generator.writeStringField("checksum", artifact.claim().checksum());
        generator.writeEndObject();
        generator.writeEndObject();
    }

    private static void writeCommitPlan(
            JsonGenerator generator,
            FinalizationCommitPlan plan) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("meetingId", plan.meetingId());
        generator.writeStringField("deploymentScope", plan.deploymentScope());
        generator.writeStringField("subjectScope", plan.subjectScope());
        generator.writeStringField("operationScope", plan.identity().operationScope());
        generator.writeStringField("idempotencyKey", plan.identity().idempotencyKey());
        generator.writeStringField("requestDigest", plan.identity().requestDigest());
        generator.writeStringField("reservationId", plan.reservationId());
        generator.writeNumberField("expectedVersion", plan.expectedVersion());
        generator.writeStringField("usageUnit", plan.actual().unit().name());
        generator.writeNumberField("usageAmount", plan.actual().amount());
        generator.writeStringField("sourceInputBinding", plan.sourceInputBinding());
        generator.writeStringField("sourceInputDigest", plan.sourceInputDigest());
        generator.writeStringField("sourceAcceptedAt", plan.sourceAcceptedAt().toString());
        generator.writeEndObject();
    }

    private static byte[] decodeCanonicalBase64(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (!Base64.getEncoder().encodeToString(decoded).equals(value)
                    || decoded.length > FinalizationCheckpoint.MAX_ARTIFACT_CONTENT_BYTES) {
                throw invalid();
            }
            return decoded;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static void requireShape(ObjectNode object, Set<String> fields) {
        for (String field : fields) {
            if (!object.has(field)) {
                throw invalid();
            }
        }
        Iterator<String> actual = object.fieldNames();
        while (actual.hasNext()) {
            if (!fields.contains(actual.next())) {
                throw invalid();
            }
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

    private static Instant requiredInstant(ObjectNode parent, String field) {
        String value = requiredText(parent, field);
        if (!MICROSECOND_INSTANT.matcher(value).matches()) {
            throw invalid();
        }
        return OffsetDateTime.parse(value).toInstant();
    }

    private static <E extends Enum<E>> E requiredEnum(
            ObjectNode parent,
            String field,
            Class<E> type) {
        try {
            return Enum.valueOf(type, requiredText(parent, field));
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(DECODE_FAILURE);
    }
}
