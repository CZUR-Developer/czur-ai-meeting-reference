package com.czur.reference.contract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 Manifest 查询的 HTTP 分支与完整 Manifest Schema 之间的静态边界。
 *
 * <p>本测试不解析或改写协议文件，只检查会影响传输适配器机械映射的三个事实：
 * HTTP 200 必须引用完整 Manifest Schema，HTTP 202 必须引用 MeetingStatus，完整
 * Schema 自身只能声明最终或删除状态。这样生成中的 NOT_STARTED/GENERATING
 * 不会被空 Manifest 冒充。</p>
 */
class MeetingManifestProtocolDriftTest {
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
