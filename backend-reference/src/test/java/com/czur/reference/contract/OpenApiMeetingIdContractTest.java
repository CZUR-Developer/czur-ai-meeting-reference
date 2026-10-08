package com.czur.reference.contract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防止 OpenAPI 中独立声明的 MeetingId 属性与领域/数据库字节上限漂移。
 *
 * <p>OpenAPI {@code maxLength} 按 Unicode code point 计数，而 Java 领域和
 * PostgreSQL 按 UTF-8 bytes 限制为 1024。如果属性遗漏 ASCII pattern，协议会
 * 错误接受 1024 个中文字符。因此路径参数和每一个公开 {@code meetingId}
 * 属性都必须同时声明同一个 ASCII pattern 与 {@code maxLength: 1024}。</p>
 */
class OpenApiMeetingIdContractTest {
    private static final String ASCII_PATTERN = "pattern: \"^mtg_[A-Za-z0-9_-]+$\"";
    private static final String MAX_LENGTH = "maxLength: 1024";

    @Test
    void everyPublicMeetingIdUsesTheSameAsciiAndLengthBoundary() throws Exception {
        List<String> lines = Files.readAllLines(openApi(), StandardCharsets.UTF_8);
        List<String> propertyBlocks = blocksNamed(lines, "meetingId:");
        List<String> parameterBlocks = blocksNamed(lines, "MeetingId:");

        // 现有SubmitClientSummaryResponse和SummarySessionGrant另增两处，共12处。
        // 只同步既有协议的计数；所有独立声明仍逐项校验ASCII与1024上限。
        assertEquals(12, propertyBlocks.size(),
                "新增或删除公开 meetingId 属性时必须显式复核统一边界（含两项总结响应）");
        assertEquals(1, parameterBlocks.size(), "MeetingId path parameter must be unique");
        for (String block : propertyBlocks) {
            assertMeetingIdBoundary(block);
        }
        assertMeetingIdBoundary(parameterBlocks.getFirst());
    }

    private static void assertMeetingIdBoundary(String block) {
        assertTrue(block.contains(ASCII_PATTERN),
                "meetingId declaration is missing the shared ASCII pattern");
        assertTrue(block.contains(MAX_LENGTH),
                "meetingId declaration is missing maxLength 1024");
    }

    /**
     * 按 YAML 缩进截取指定键的完整块，不依赖额外 YAML 测试库。返回块包含嵌套
     * schema，因此既能校验属性，也能校验 components.parameters.MeetingId。
     */
    private static List<String> blocksNamed(List<String> lines, String key) {
        List<String> blocks = new ArrayList<>();
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
            blocks.add(block.toString());
        }
        return blocks;
    }

    private static int indentation(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static Path openApi() throws IOException {
        for (Path candidate : List.of(
                Path.of("contracts", "ai-meeting-openapi.yaml"),
                Path.of("..", "contracts", "ai-meeting-openapi.yaml"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IOException("ai-meeting-openapi.yaml not found");
    }
}
