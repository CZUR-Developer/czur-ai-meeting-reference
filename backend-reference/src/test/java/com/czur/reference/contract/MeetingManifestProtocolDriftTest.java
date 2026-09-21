package com.czur.reference.contract;

import com.czur.reference.application.port.MeetingManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 Manifest 查询的 HTTP 分支与完整 Manifest Schema 之间的静态边界。
 *
 * <p>测试结构化解析 JSON Schema，并检查会影响传输适配器机械映射的事实：
 * HTTP 200 必须引用完整 Manifest Schema，HTTP 202 必须引用 MeetingStatus，完整
 * Schema 自身只能声明最终或删除状态，Java record、数值、空白与时间边界也必须
 * 一致。这样生成中的 NOT_STARTED/GENERATING 不会被空 Manifest 冒充。</p>
 */
class MeetingManifestProtocolDriftTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> COMPLETE_MANIFEST_STATES = List.of(
            "READY",
            "READY_WITH_WARNINGS",
            "FAILED",
            "DELETION_PENDING",
            "DELETED");
    private static final Pattern TOP_LEVEL_STATUS = Pattern.compile(
            "\\\"status\\\"\\s*:\\s*\\{\\s*\\\"enum\\\"\\s*:\\s*\\[([^]]*)]",
            Pattern.DOTALL);
    private static final Pattern QUOTED_VALUE = Pattern.compile("\\\"([^\\\"]+)\\\"");
    private static final String RFC3339_INSTANT =
            "^(?:(?:0000|9999)-[0-9]{2}-[0-9]{2}T"
                    + "(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
                    + "(?:\\.[0-9]{1,9})?Z|"
                    + "(?!(?:0000|9999)-)[0-9]{4}-[0-9]{2}-[0-9]{2}T"
                    + "(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
                    + "(?:\\.[0-9]{1,9})?"
                    + "(?:Z|[+-](?:(?:0[0-9]|1[0-7]):[0-5][0-9]|18:00)))$";

    @Test
    void manifestResponsesKeepSeparateCompleteAndPendingRepresentations() throws Exception {
        List<String> lines = Files.readAllLines(openApi(), StandardCharsets.UTF_8);
        String manifestPath = onlyBlockNamed(
                lines, "/v1/meetings/{meetingId}/manifest:");
        String responses = onlyBlockNamed(
                manifestPath.lines().toList(), "responses:");
        String complete = onlyBlockNamed(responses.lines().toList(), "\"200\":");
        String pending = onlyBlockNamed(responses.lines().toList(), "\"202\":");

        assertTrue(complete.contains("$ref: \"./meeting-manifest.schema.json\""),
                "Manifest HTTP 200 must reference the complete Manifest Schema");
        assertFalse(complete.contains("#/components/schemas/MeetingStatus"),
                "Manifest HTTP 200 must not return a pending MeetingStatus");
        assertTrue(pending.contains("$ref: \"#/components/schemas/MeetingStatus\""),
                "Manifest HTTP 202 must reference MeetingStatus");
        assertFalse(pending.contains("./meeting-manifest.schema.json"),
                "Manifest HTTP 202 must not return a partial Manifest");
    }

    @Test
    void completeManifestSchemaContainsExactlyTheFiveFrozenStates() throws Exception {
        String schema = Files.readString(manifestSchema(), StandardCharsets.UTF_8);
        int definitions = schema.indexOf("\"$defs\"");
        assertTrue(definitions > 0, "Manifest Schema must contain $defs");

        Matcher status = TOP_LEVEL_STATUS.matcher(schema.substring(0, definitions));
        assertTrue(status.find(), "top-level Manifest status enum is missing");
        List<String> schemaStates = quotedValues(status.group(1));
        assertEquals(COMPLETE_MANIFEST_STATES, schemaStates);
        assertFalse(status.find(), "top-level Manifest status must be declared exactly once");
        assertFalse(schemaStates.contains("NOT_STARTED"));
        assertFalse(schemaStates.contains("GENERATING"));
    }

    @Test
    void schemaPropertiesAndRequiredSetsMatchJavaRecordShapes() throws Exception {
        JsonNode schema = JSON.readTree(manifestSchema().toFile());

        assertEquals(recordFields(MeetingManifest.class),
                fieldNames(schema.path("properties")));
        assertEquals(Set.of(
                        "specVersion", "meetingId", "manifestRevision", "profile", "status",
                        "startedAt", "endedAt", "durationMs", "languages", "artifacts",
                        "warnings"),
                textValues(schema.path("required")));

        JsonNode definitions = schema.path("$defs");
        assertRecordDefinition(MeetingManifest.Profile.class,
                definitions.path("profileRef"), Set.of("id", "revision"));
        assertRecordDefinition(MeetingManifest.Languages.class,
                definitions.path("languages"), Set.of("source", "targets"));
        assertRecordDefinition(MeetingManifest.ArtifactRef.class,
                definitions.path("artifactRef"), Set.of(
                        "artifactId", "type", "mediaType", "revision", "size", "checksum",
                        "status", "createdAt"));
        assertRecordDefinition(MeetingManifest.ArtifactFailure.class,
                definitions.path("artifactFailure"), Set.of(
                        "domain", "code", "retryable", "diagnosticId"));
        assertRecordDefinition(MeetingManifest.Warning.class,
                definitions.path("warning"), Set.of("code", "capability", "messageKey"));
    }

    @Test
    void schemaNumericEnumsPatternsAndFailureConditionMatchJavaBoundaries() throws Exception {
        JsonNode schema = JSON.readTree(manifestSchema().toFile());
        JsonNode root = schema.path("properties");
        JsonNode definitions = schema.path("$defs");
        JsonNode artifact = definitions.path("artifactRef").path("properties");

        assertEquals(Integer.MAX_VALUE, root.path("manifestRevision").path("maximum").longValue());
        assertEquals(Long.MAX_VALUE, root.path("durationMs").path("maximum").longValue());
        assertEquals(Integer.MAX_VALUE,
                definitions.path("profileRef").path("properties")
                        .path("revision").path("maximum").longValue());
        assertEquals(Integer.MAX_VALUE, artifact.path("revision").path("maximum").longValue());
        assertEquals(Long.MAX_VALUE, artifact.path("size").path("maximum").longValue());
        assertEquals("^mtg_[A-Za-z0-9_-]+$", root.path("meetingId").path("pattern").textValue());
        assertEquals("^art_[A-Za-z0-9_-]+$",
                artifact.path("artifactId").path("pattern").textValue());
        assertEquals("^sha256:[A-Fa-f0-9]{64}$",
                artifact.path("checksum").path("pattern").textValue());
        assertEquals(enumNames(MeetingManifest.ArtifactStatus.class),
                textValues(artifact.path("status").path("enum")));
        assertEquals(Set.of("MODEL", "STORAGE", "SUMMARY", "INTERNAL"),
                textValues(definitions.path("artifactFailure").path("properties")
                        .path("domain").path("enum")));

        JsonNode failureRule = definitions.path("artifactRef").path("allOf").path(0);
        assertEquals("FAILED", failureRule.path("if").path("properties")
                .path("status").path("const").textValue());
        assertEquals(Set.of("failure"), textValues(failureRule.path("then").path("required")));
        assertEquals("null", failureRule.path("else").path("properties")
                .path("failure").path("type").textValue());

        assertEquals(RFC3339_INSTANT, root.path("startedAt").path("pattern").textValue());
        assertEquals(RFC3339_INSTANT, root.path("endedAt").path("pattern").textValue());
        assertEquals(RFC3339_INSTANT, artifact.path("createdAt").path("pattern").textValue());

        assertNonBlankPattern(definitions.path("profileRef").path("properties"), "id");
        JsonNode languages = definitions.path("languages").path("properties");
        assertNonBlankPattern(languages, "source");
        assertEquals("\\S", languages.path("targets").path("items")
                .path("pattern").textValue());
        assertNonBlankPattern(artifact, "mediaType");
        assertNonBlankPattern(artifact, "language");
        JsonNode failure = definitions.path("artifactFailure").path("properties");
        assertNonBlankPattern(failure, "code");
        assertNonBlankPattern(failure, "diagnosticId");
        JsonNode warning = definitions.path("warning").path("properties");
        assertNonBlankPattern(warning, "code");
        assertNonBlankPattern(warning, "capability");
        assertNonBlankPattern(warning, "messageKey");
        assertNonBlankPattern(warning, "diagnosticId");
    }

    private static void assertNonBlankPattern(JsonNode properties, String field) {
        assertEquals("\\S", properties.path(field).path("pattern").textValue(), field);
    }

    private static void assertRecordDefinition(
            Class<?> recordType,
            JsonNode definition,
            Set<String> required) {
        assertEquals(recordFields(recordType), fieldNames(definition.path("properties")));
        assertEquals(required, textValues(definition.path("required")));
    }

    private static Set<String> recordFields(Class<?> recordType) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getName())
                .forEach(fields::add);
        return fields;
    }

    private static Set<String> fieldNames(JsonNode object) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static Set<String> textValues(JsonNode array) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        array.forEach(value -> values.add(value.textValue()));
        return values;
    }

    private static <E extends Enum<E>> Set<String> enumNames(Class<E> enumType) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Arrays.stream(enumType.getEnumConstants()).map(Enum::name).forEach(names::add);
        return names;
    }

    /** 按 YAML 缩进读取唯一命名块，避免为了两处静态引用引入运行时解析依赖。 */
    private static String onlyBlockNamed(List<String> lines, String key) {
        List<String> matches = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.trim().equals(key)) {
                continue;
            }
            int indentation = indentation(line);
            StringBuilder block = new StringBuilder(line).append('\n');
            for (int next = index + 1; next < lines.size(); next++) {
                String candidate = lines.get(next);
                if (!candidate.isBlank() && indentation(candidate) <= indentation) {
                    break;
                }
                block.append(candidate).append('\n');
            }
            matches.add(block.toString());
        }
        assertEquals(1, matches.size(), "expected one YAML block named " + key);
        return matches.getFirst();
    }

    private static int indentation(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static List<String> quotedValues(String source) {
        List<String> values = new ArrayList<>();
        Matcher matcher = QUOTED_VALUE.matcher(source);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static Path openApi() throws IOException {
        return contract("ai-meeting-openapi.yaml");
    }

    private static Path manifestSchema() throws IOException {
        return contract("meeting-manifest.schema.json");
    }

    /** 支持从仓库根目录或 backend-reference 模块目录运行 Maven。 */
    private static Path contract(String filename) throws IOException {
        for (Path candidate : List.of(
                Path.of("contracts", filename),
                Path.of("..", "contracts", filename))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IOException(filename + " not found");
    }
}
