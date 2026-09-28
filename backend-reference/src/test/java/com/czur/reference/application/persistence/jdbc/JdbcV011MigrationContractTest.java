package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不依赖 PostgreSQL 的 V011 执行事实与 READY artifact 迁移契约门。
 *
 * <p>真实 catalog 指纹、事务回滚、重复执行及约束行为由
 * {@link JdbcV011MigrationIntegrationTest} 在 PostgreSQL 16 验证；本测试保证默认
 * Maven 也能发现字段、约束数或边界词被意外改回开放式 metadata 模型。</p>
 */
class JdbcV011MigrationContractTest {
    @Test
    void executionFactFreezesFourColumnsFourConstraintsAndCanonicalHash() throws Exception {
        String sql = readMigration();
        String table = tableBlock(sql, "finalization_execution_fact");

        assertColumns(table, List.of(
                "meeting_id", "canonical_json", "execution_fact_json", "content_sha256"));
        for (String constraint : List.of(
                "finalization_execution_fact_pkey",
                "finalization_execution_fact_meeting_fk",
                "finalization_execution_fact_document_ck",
                "finalization_execution_fact_hash_ck")) {
            assertEquals(1, occurrences(table, "CONSTRAINT " + constraint), constraint);
        }
        for (String field : List.of(
                "specVersion", "meetingId", "startedAt", "title",
                "productProfile", "capabilityPlan")) {
            assertTrue(table.contains("'" + field + "'"), field);
        }
        String compact = table.replaceAll("\\s+", " ");
        assertTrue(compact.contains("'finalization-execution-fact/v1'"));
        assertTrue(compact.contains("execution_fact_json ->> 'meetingId' = meeting_id"));
        assertTrue(compact.contains("[0-9]{1,6}"), "startedAt must cap fractional seconds at microseconds");
        assertTrue(compact.contains("canonical_json::JSONB = execution_fact_json"));
        assertTrue(compact.contains(
                "content_sha256 = sha256(convert_to(canonical_json, 'UTF8'))"));
        assertTrue(compact.contains("ON DELETE RESTRICT"));
    }

    @Test
    void artifactIsElevenColumnReadyOnlyMetadataWithTenConstraints() throws Exception {
        String table = tableBlock(readMigration(), "artifact");

        assertColumns(table, List.of(
                "artifact_id", "meeting_id", "artifact_type", "media_type", "language",
                "revision", "intent_id", "storage_binding", "content_size",
                "content_checksum", "recorded_at"));
        for (String constraint : List.of(
                "artifact_pkey", "artifact_meeting_fk", "artifact_revision_uq",
                "artifact_intent_id_uq", "artifact_storage_binding_uq",
                "artifact_text_ck", "artifact_language_ck", "artifact_revision_ck",
                "artifact_content_size_ck", "artifact_content_checksum_ck")) {
            assertEquals(1, occurrences(table, "CONSTRAINT " + constraint), constraint);
        }
        assertEquals(10, occurrences(table, "CONSTRAINT artifact_"));
        String compact = table.replaceAll("\\s+", " ");
        assertTrue(compact.contains("UNIQUE (meeting_id, artifact_type, revision)"));
        assertTrue(compact.contains("UNIQUE (intent_id)"));
        assertTrue(compact.contains("UNIQUE (storage_binding)"));
        assertTrue(compact.contains("content_checksum ~ '^sha256:[0-9a-f]{64}$'"));
        assertTrue(compact.contains("recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP"));
        assertTrue(compact.contains("octet_length(convert_to(artifact_id, 'UTF8')) <= 4096"));
        assertTrue(compact.contains("language IS NULL OR"));
        assertTrue(compact.contains("length(translate(artifact_id, concat( chr(9), chr(10)"));
        assertTrue(compact.contains("chr(8287), chr(12288)), '')) > 0"));
        assertFalse(compact.contains("length(btrim(artifact_id))"));

        for (String forbiddenColumn : List.of(
                "status", "content_type", "byte_length", "storage_binding_id", "sha256",
                "metadata", "checkpoint", "profile", "lease", "manifest", "url",
                "path", "credential")) {
            assertFalse(Pattern.compile("(?m)^\\s{4}" + forbiddenColumn + "\\s+")
                    .matcher(table.toLowerCase()).find(), forbiddenColumn);
        }
    }

    @Test
    void migrationOnlyConvertsExactEmptyV001AndSupportsExactV011Rerun() throws Exception {
        String sql = readMigration();
        String compact = sql.replaceAll("\\s+", " ");

        assertTrue(sql.stripLeading().startsWith("--"));
        assertEquals(1, occurrences(sql, "BEGIN;"));
        assertEquals(1, occurrences(sql, "COMMIT;"));
        assertTrue(compact.contains("v001_shape := actual_columns ="));
        assertTrue(compact.contains("v011_shape := actual_columns ="));
        assertTrue(compact.contains("execution_v011_shape := actual_columns ="));
        assertTrue(compact.contains("WHERE conrelid = artifact_table) = 5"));
        assertTrue(compact.contains("WHERE conrelid = artifact_table) = 10"));
        assertTrue(compact.contains("IF EXISTS (SELECT 1 FROM artifact LIMIT 1)"));
        assertEquals(1, occurrences(sql, "DROP TABLE artifact;"));
        assertTrue(compact.contains("IF v011_shape THEN"));
        assertTrue(compact.contains("IF NOT execution_v011_shape THEN"));
        assertTrue(compact.contains("IF execution_table IS NOT NULL THEN"));
        assertTrue(compact.contains("RETURN; END IF"));
        assertTrue(compact.contains("relkind = 'r'"));
        assertTrue(compact.contains("relpersistence = 'p'"));
        assertTrue(compact.contains("NOT c.relispartition"));
        assertTrue(compact.contains("FROM pg_inherits i"));
        assertTrue(compact.contains("i.inhrelid = c.oid OR i.inhparent = c.oid"));
        assertTrue(compact.contains("a.attnum = 1"));
        assertTrue(compact.contains("a.attname = 'meeting_id'"));
        assertTrue(compact.contains("format_type(a.atttypid, a.atttypmod) = 'text'"));
        assertTrue(compact.contains("AND a.attnotnull"));
        assertTrue(compact.contains("conname = 'meeting_pkey'"));
        assertTrue(compact.contains("AND NOT condeferrable"));
        assertTrue(compact.contains("AND NOT condeferred"));
        int pathPin = compact.indexOf("format('%I, pg_temp', current_schema())");
        int migrationLock = compact.indexOf("pg_advisory_xact_lock(hashtextextended(");
        int meetingLock = compact.indexOf(
                "LOCK TABLE ONLY meeting IN ACCESS SHARE MODE;");
        int executionLock = compact.indexOf(
                "LOCK TABLE ONLY %I.%I IN ACCESS SHARE MODE");
        int exclusiveLock = compact.indexOf(
                "LOCK TABLE ONLY artifact IN ACCESS EXCLUSIVE MODE;");
        int shapeCheck = compact.indexOf("v001_shape := actual_columns =");
        int emptyCheck = compact.indexOf("IF EXISTS (SELECT 1 FROM artifact LIMIT 1)");
        assertTrue(pathPin >= 0 && pathPin < migrationLock,
                "target schema must precede the explicit pg_temp fallback");
        assertTrue(migrationLock >= 0 && migrationLock < meetingLock,
                "same-version migrations must serialize before resolving artifact");
        assertTrue(meetingLock < executionLock && executionLock < exclusiveLock,
                "migration lock order must match meeting -> execution -> artifact writes");
        assertTrue(exclusiveLock >= 0 && exclusiveLock < shapeCheck, "lock must precede shape check");
        assertTrue(exclusiveLock < emptyCheck, "lock must precede empty-table check");
        assertTrue(compact.contains("artifact_byte_length_check"));
        assertTrue(compact.contains("artifact_meeting_id_fkey"));
        assertTrue(compact.contains("confdeltype = 'a'"), "V001 FK must be NO ACTION");
        assertTrue(compact.contains("confdeltype = 'r'"), "V011 FK must be RESTRICT");
        assertFalse(sql.toLowerCase().contains("flyway"));
        assertFalse(Pattern.compile("(?i)\\b(?:insert\\s+into|update\\s+artifact|delete\\s+from)\\b")
                .matcher(sql).find(), "V011 must not rewrite or infer business rows");
    }

    private static void assertColumns(String table, List<String> expected) {
        Pattern declaration = Pattern.compile("(?m)^\\s{4}([a-z0-9_]+)\\s+[A-Z]+(?:\\s|,)");
        var matcher = declaration.matcher(table);
        var actual = new java.util.ArrayList<String>();
        while (matcher.find()) {
            actual.add(matcher.group(1));
        }
        assertEquals(expected, actual);
    }

    private static String tableBlock(String sql, String table) {
        String marker = "CREATE TABLE IF NOT EXISTS " + table + " (";
        int start = sql.indexOf(marker);
        assertTrue(start >= 0, marker);
        int end = sql.indexOf("\n);", start);
        assertTrue(end > start, table);
        return sql.substring(start, end + 3);
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
                Path.of("deploy", "migrations",
                        "V011__finalization_execution_artifact_ready.sql"),
                Path.of("..", "deploy", "migrations",
                        "V011__finalization_execution_artifact_ready.sql")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("V011 migration file not found");
    }
}
