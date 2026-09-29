package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V012 删除物理契约的无数据库静态门禁。
 *
 * <p>本门禁负责冻结列集合、对象命名、迁移两态、锁序和禁止的修补式 DDL。约束的
 * PostgreSQL catalog 身份、SQLState、事务回滚及实际写入行为由同版本集成测试证明。</p>
 */
class JdbcV012MigrationContractTest {

    @Test
    void freezesExactDeletionPlanAndItemShapes() throws Exception {
        String sql = readMigration();

        assertColumns(tableBlock(sql, "meeting_deletion_plan"), List.of(
                "meeting_id", "accepted_at", "source_manifest_revision",
                "source_workflow_step_type", "source_workflow_step_key",
                "source_workflow_checkpoint_type", "source_workflow_content_digest",
                "target_count", "canonical_json", "deletion_plan_json",
                "content_sha256", "status", "completed_at"));
        assertColumns(tableBlock(sql, "artifact_deletion_item"), List.of(
                "meeting_id", "ordinal", "artifact_id", "artifact_type", "revision",
                "status", "storage_binding", "deleted_at"));

        assertTrue(sql.contains("CONSTRAINT meeting_deleted_at_equivalence_ck"));
        assertTrue(sql.contains("CONSTRAINT meeting_deletion_plan_pkey"));
        assertTrue(sql.contains("CONSTRAINT meeting_deletion_plan_manifest_fk"));
        assertTrue(sql.contains("CONSTRAINT meeting_deletion_plan_workflow_fk"));
        assertTrue(sql.contains("CONSTRAINT artifact_deletion_item_plan_fk"));
        assertTrue(sql.contains("CONSTRAINT artifact_deletion_item_artifact_id_uq"));
        assertTrue(sql.contains("CONSTRAINT artifact_deletion_item_revision_uq"));
        assertFalse(sql.contains("REFERENCES artifact"),
                "item audit must survive READY metadata deletion");
    }

    @Test
    void freezesCanonicalDocumentHashStatusAndClosedMigrationStates() throws Exception {
        String sql = readMigration();
        String compact = sql.replaceAll("\\s+", " ");

        for (String root : List.of(
                "specVersion", "meetingId", "acceptedAt", "sourceManifestRevision",
                "sourceFinalizationWorkflow", "targets")) {
            assertTrue(sql.contains("'" + root + "'"), root);
        }
        assertTrue(sql.contains("'artifact-deletion-plan/v1'"));
        assertTrue(sql.contains("'mock-finalization'"));
        assertTrue(sql.contains("'mock-finalization-checkpoint/v1'"));
        assertTrue(sql.contains("canonical_json::JSONB = deletion_plan_json"));
        assertTrue(sql.contains("content_sha256 = sha256(convert_to(canonical_json, 'UTF8'))"));
        assertEquals(2, occurrences(sql, ") IS TRUE),"),
                "source and document checks must reject PostgreSQL UNKNOWN");
        assertTrue(compact.contains(
                "source_workflow_step_type IS NULL AND source_workflow_step_key IS NULL AND source_workflow_checkpoint_type IS NULL AND source_workflow_content_digest IS NULL"));
        assertTrue(compact.contains(
                "source_workflow_step_type IS NOT NULL AND source_workflow_step_key IS NOT NULL AND source_workflow_checkpoint_type IS NOT NULL AND source_workflow_content_digest IS NOT NULL"));
        for (String field : List.of(
                "meetingId", "stepType", "stepKey", "checkpointType", "contentDigest")) {
            assertTrue(compact.contains(
                    "#> '{sourceFinalizationWorkflow," + field + "}') = 'string'"), field);
        }
        assertTrue(compact.contains("target_count >= 0 AND target_count <= 256"));
        assertTrue(compact.contains("ordinal >= 0 AND ordinal < 256"));
        assertTrue(sql.contains("status = 'PENDING'"));
        assertTrue(sql.contains("status = 'COMPLETED'"));
        assertTrue(sql.contains("status = 'DELETED'"));

        assertTrue(compact.contains(
                "state IN ('DELETION_PENDING', 'DELETED') OR manifest_state IN ('DELETION_PENDING', 'DELETED') OR deleted_at IS NOT NULL"));
        assertTrue(compact.contains(
                "IF plan_table IS NULL OR item_table IS NULL OR equivalence_constraint IS NULL"));
        assertFalse(Pattern.compile(
                        "(?i)\\b(?:CREATE\\s+(?:TABLE|INDEX)|ADD\\s+(?:COLUMN|CONSTRAINT))\\s+IF\\s+NOT\\s+EXISTS\\b")
                .matcher(sql).find(),
                "V012 must classify first execution versus exact rerun, not repair DDL");
        assertFalse(Pattern.compile("(?i)\\b(?:INSERT|UPDATE|DELETE)\\s+(?:INTO\\s+|FROM\\s+)?(?:meeting|meeting_deletion_plan|artifact_deletion_item)\\b")
                .matcher(sql).find(), "migration must not rewrite or infer business rows");
    }

    @Test
    void pinsSchemaSerializesAndUsesTheFrozenLockOrder() throws Exception {
        String sql = readMigration();
        String compact = sql.replaceAll("\\s+", " ");
        int schema = compact.indexOf("format('%I, pg_temp', current_schema())");
        int advisory = compact.indexOf("pg_advisory_xact_lock(hashtextextended(");
        int meeting = compact.indexOf("LOCK TABLE ONLY meeting IN ACCESS EXCLUSIVE MODE");
        int workflow = compact.indexOf("LOCK TABLE ONLY workflow_step IN ACCESS SHARE MODE");
        int manifest = compact.indexOf(
                "LOCK TABLE ONLY meeting_manifest_revision IN ACCESS SHARE MODE");
        int existing = compact.indexOf("'meeting_deletion_plan', 'artifact_deletion_item'");
        int oldData = compact.indexOf("V012 首次迁移发现无法证明来源的旧删除状态");

        assertTrue(schema >= 0 && schema < advisory);
        assertTrue(advisory < meeting && meeting < workflow && workflow < manifest);
        assertTrue(manifest < existing && existing < oldData);
        assertTrue(sql.contains("confdeltype = 'r'"));
        assertTrue(sql.contains("NOT actual.condeferrable"));
        assertTrue(sql.contains("NOT actual.condeferred"));
        assertTrue(sql.contains("actual.convalidated"));
        assertTrue(sql.contains("indexprs IS NOT NULL OR indpred IS NOT NULL"));
    }

    private static void assertColumns(String table, List<String> expected) {
        Pattern declaration = Pattern.compile("(?m)^\\s{12}([a-z0-9_]+)\\s+[A-Z]+(?:\\s|,)");
        Matcher matcher = declaration.matcher(table);
        var actual = new java.util.ArrayList<String>();
        while (matcher.find()) {
            actual.add(matcher.group(1));
        }
        assertEquals(expected, actual);
    }

    private static String tableBlock(String sql, String table) {
        String marker = "CREATE TABLE " + table + " (";
        int start = sql.indexOf(marker);
        assertTrue(start >= 0, marker);
        int end = sql.indexOf("\n        )\n    $ddl$;", start);
        assertTrue(end > start, table);
        return sql.substring(start, end);
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
        Path[] candidates = {
                Path.of("deploy", "migrations", "V012__artifact_deletion_plan.sql"),
                Path.of("..", "deploy", "migrations", "V012__artifact_deletion_plan.sql")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("V012 migration file not found");
    }
}
