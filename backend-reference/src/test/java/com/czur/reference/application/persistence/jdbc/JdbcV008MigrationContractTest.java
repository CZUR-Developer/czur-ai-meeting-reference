package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不依赖 PostgreSQL 的 V008 durable input fact 源码契约门。
 *
 * <p>本测试只保护迁移文件的字段、命名约束、外键删除语义和重放边界；它不把
 * 静态文本检查当成真实 PostgreSQL catalog 或事务门禁。显式数据库环境下的
 * V008 重复执行、CHECK 拒绝和 RESTRICT 行为由集成测试负责。</p>
 */
class JdbcV008MigrationContractTest {
    private static final Pattern COLUMN = Pattern.compile(
            "^\\s{4}(meeting_id|input_binding|last_accepted_sequence|audio_duration_ms|"
                    + "source_language|target_languages|accepted_at|content_digest)\\s+",
            Pattern.MULTILINE);

    @Test
    void migrationFreezesEightColumnsAndOnePrimaryKeyOneForeignKeySixChecks() throws Exception {
        String sql = readMigration();
        var columns = COLUMN.matcher(sql);
        int columnCount = 0;
        while (columns.find()) {
            columnCount++;
        }
        assertEquals(8, columnCount);

        assertEquals(1, occurrences(sql, "CONSTRAINT durable_input_fact_pkey"));
        assertEquals(1, occurrences(sql, "CONSTRAINT durable_input_fact_meeting_fk"));
        for (String constraint : new String[]{
                "durable_input_fact_sequence_ck",
                "durable_input_fact_duration_ck",
                "durable_input_fact_input_binding_ck",
                "durable_input_fact_source_language_ck",
                "durable_input_fact_target_languages_ck",
                "durable_input_fact_content_digest_ck"}) {
            assertEquals(1, occurrences(sql, "CONSTRAINT " + constraint + " CHECK"));
        }
    }

    @Test
    void migrationClosesCountersStringsArrayAndRestrictDelete() {
        String compact = readMigrationUnchecked().replaceAll("\\s+", " ");
        assertTrue(compact.contains("last_accepted_sequence >= 0"));
        assertTrue(compact.contains("audio_duration_ms >= 0"));
        assertTrue(compact.contains("length(btrim(input_binding)) > 0"));
        assertTrue(compact.contains("length(btrim(source_language)) > 0"));
        assertTrue(compact.contains("length(btrim(content_digest)) > 0"));
        assertTrue(compact.contains("target_languages TEXT[] NOT NULL"));
        assertTrue(compact.contains("array_position(target_languages, NULL) IS NULL"));
        assertTrue(compact.contains("array_position(target_languages, '') IS NULL"));
        assertTrue(compact.contains(
                "REFERENCES meeting (meeting_id) ON DELETE RESTRICT"));
    }

    @Test
    void migrationDoesNotPerformBusinessDmlOrCreateExtraIndexes() {
        String lower = readMigrationUnchecked().toLowerCase();
        assertFalse(lower.contains("create extension"));
        assertFalse(lower.contains("create trigger"));
        assertFalse(lower.contains("create index"));
        assertFalse(Pattern.compile("\\b(insert|update|delete)\\s+(into|meeting|from)\\b")
                .matcher(lower).find(), "V008 must not perform business DML");
        assertTrue(lower.contains("create table if not exists durable_input_fact"));
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static String readMigration() throws IOException {
        return readMigrationUnchecked();
    }

    private static String readMigrationUnchecked() {
        Path[] candidates = {
                Path.of("deploy", "migrations", "V008__durable_input_fact.sql"),
                Path.of("..", "deploy", "migrations", "V008__durable_input_fact.sql")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException failure) {
                    throw new IllegalStateException("V008 migration unreadable", failure);
                }
            }
        }
        throw new IllegalStateException("V008 migration file not found");
    }
}
