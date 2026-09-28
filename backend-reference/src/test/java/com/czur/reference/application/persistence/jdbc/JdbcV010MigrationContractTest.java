package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不依赖 PostgreSQL 的 V010 工作流步骤迁移契约门。 */
class JdbcV010MigrationContractTest {
    @Test
    void migrationAddsCheckpointAndFencingColumnsAndClosedStateChecks() {
        String sql = readMigration().replaceAll("\\s+", " ");
        for (String column : new String[]{"checkpoint_type", "canonical_payload",
                "content_digest", "initial_available_at", "next_attempt_at",
                "lease_token", "lease_version"}) {
            assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS " + column), column);
        }
        for (String constraint : new String[]{"workflow_step_status_ck",
                "workflow_step_definition_ck", "workflow_step_lease_token_ck",
                "workflow_step_facts_ck"}) {
            assertEquals(1, occurrences(sql, "CONSTRAINT " + constraint), constraint);
        }
        assertTrue(sql.contains("status IN ('PENDING', 'LEASED', 'COMPLETED', 'DEAD')"));
        assertTrue(sql.contains("lease_version > 0"));
        assertTrue(sql.contains("lease_until > updated_at"));
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS workflow_step_claim_v010_idx"));
        assertTrue(sql.contains("workflow_step_attempt_count_check"));
        assertTrue(sql.contains("workflow_step_key_uq"));
        assertTrue(sql.contains("workflow_step_meeting_id_fkey"));
        assertTrue(sql.contains("conkey = ARRAY[2, 3, 4]::SMALLINT[]"));
        assertTrue(sql.contains("confdeltype = 'a'"));
        assertTrue(sql.contains("a.attname = 'step_id'"));
        assertTrue(sql.contains("format_type(a.atttypid, a.atttypmod) = 'bigint'"));
        assertTrue(sql.contains("a.attidentity = 'd'"));
        assertTrue(sql.contains("d.oid IS NULL"));
        assertTrue(sql.contains("workflow_step_claim_v010_idx"));
        assertTrue(readMigration().contains("只优化领取扫描"));
    }

    @Test
    void migrationRequiresV001AndRejectsUnprovableLegacyRowsWithoutBusinessDml() {
        String lower = readMigration().toLowerCase();
        assertTrue(lower.contains("请先执行 v001"));
        assertFalse(lower.contains("create extension"));
        assertFalse(lower.matches(
                "(?s).*\\b(insert\\s+into|update\\s+workflow_step|delete\\s+from)\\b.*"));
        assertTrue(lower.contains("已有无法证明 definition 的旧行"));
        assertFalse(lower.contains("legacy-v001"));
    }

    @Test
    void catalogGateComparesCompleteConstraintDefinitions() {
        String sql = readMigration();
        assertTrue(sql.contains("pg_get_constraintdef(actual.oid, TRUE)"));
        assertTrue(sql.contains("<> expected.expected_definition"));
        assertFalse(sql.contains("pg_get_constraintdef(actual.oid, TRUE) NOT LIKE"));
        assertFalse(sql.contains("pg_get_constraintdef(actual.oid, TRUE) LIKE"));
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

    private static String readMigration() {
        for (Path candidate : new Path[]{
                Path.of("deploy", "migrations", "V010__workflow_step_fencing.sql"),
                Path.of("..", "deploy", "migrations", "V010__workflow_step_fencing.sql")}) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException failure) {
                    throw new IllegalStateException("V010 migration unreadable", failure);
                }
            }
        }
        throw new IllegalStateException("V010 migration file not found");
    }
}
