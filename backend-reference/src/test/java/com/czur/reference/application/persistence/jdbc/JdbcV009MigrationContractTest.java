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
 * 不依赖 PostgreSQL 的 V009 最终用量事实源代码契约门。
 *
 * <p>本测试只保护迁移文件中的字段、单位/金额/来源约束、外键删除语义和
 * 不执行业务 DML 的边界；真实 PostgreSQL 的列类型、约束验证和 TIMESTAMPTZ
 * 微秒往返由 JdbcFinalUsageFactStore 集成测试负责。</p>
 */
class JdbcV009MigrationContractTest {
    private static final Pattern COLUMN = Pattern.compile(
            "^\\s{4}(meeting_id|actual_unit|actual_amount|source_input_binding|"
                    + "source_input_digest|source_accepted_at)\\s+",
            Pattern.MULTILINE);

    @Test
    void migrationFreezesSixColumnsAndOnePrimaryKeyOneForeignKeyFourChecks() {
        String sql = readMigrationUnchecked();
        var columns = COLUMN.matcher(sql);
        int columnCount = 0;
        while (columns.find()) {
            columnCount++;
        }
        assertEquals(6, columnCount);

        assertEquals(1, occurrences(sql, "CONSTRAINT final_usage_fact_pkey"));
        assertEquals(1, occurrences(sql, "CONSTRAINT final_usage_fact_meeting_fk"));
        for (String constraint : new String[]{
                "final_usage_fact_actual_unit_ck",
                "final_usage_fact_actual_amount_ck",
                "final_usage_fact_source_input_binding_ck",
                "final_usage_fact_source_input_digest_ck"}) {
            assertEquals(1, occurrences(sql, "CONSTRAINT " + constraint + " CHECK"));
        }
    }

    @Test
    void migrationClosesUnitAmountSourceAndRestrictDelete() {
        String compact = readMigrationUnchecked().replaceAll("\\s+", " ");
        assertTrue(compact.contains("actual_unit TEXT NOT NULL"));
        assertTrue(compact.contains("actual_amount BIGINT NOT NULL"));
        assertTrue(compact.contains("actual_unit IN ('MEETING_DURATION_MS')"));
        assertTrue(compact.contains("actual_amount >= 0"));
        assertTrue(compact.contains("length(btrim(source_input_binding)) > 0"));
        assertTrue(compact.contains("length(btrim(source_input_digest)) > 0"));
        assertTrue(compact.contains("source_accepted_at TIMESTAMPTZ NOT NULL"));
        assertTrue(compact.contains("durable_input_fact"));
        assertTrue(compact.contains("请先执行 V008"));
        assertTrue(compact.contains(
                "pg_get_constraintdef(actual.oid, TRUE)"));
        assertTrue(compact.contains(
                "CHECK (actual_unit = ''MEETING_DURATION_MS''::text)"));
        assertTrue(compact.contains("'CHECK (actual_amount >= 0)'"));
        assertTrue(compact.contains(
                "'CHECK (length(btrim(source_input_binding)) > 0)'"));
        assertTrue(compact.contains(
                "'CHECK (length(btrim(source_input_digest)) > 0)'"));
        assertFalse(compact.contains("position("));
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
                .matcher(lower).find(), "V009 must not perform business DML");
        assertTrue(lower.contains("create table if not exists final_usage_fact"));
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

    private static String readMigrationUnchecked() {
        Path[] candidates = {
                Path.of("deploy", "migrations", "V009__final_usage_fact.sql"),
                Path.of("..", "deploy", "migrations", "V009__final_usage_fact.sql")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException failure) {
                    throw new IllegalStateException("V009 migration unreadable", failure);
                }
            }
        }
        throw new IllegalStateException("V009 migration file not found");
    }
}
