package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不依赖 PostgreSQL 的 V007 源码契约门。
 *
 * <p>本测试让默认 Maven 门禁也能发现字段、约束、摘要算法或任务边界被意外改变。
 * PostgreSQL catalog 形态、重复执行和真实约束行为由
 * {@link JdbcV007MigrationIntegrationTest} 验证，静态文本检查不冒充数据库验收。</p>
 */
class JdbcV007MigrationContractTest {
    private static final Pattern COLUMN = Pattern.compile(
            "^\\s{4}(meeting_id|manifest_revision|previous_revision|canonical_json|"
                    + "manifest_json|content_sha256|recorded_at)\\s+",
            Pattern.MULTILINE);

    @Test
    void migrationFreezesSevenColumnsAndSixNamedConstraints() throws Exception {
        String sql = readMigration();
        Matcher columns = COLUMN.matcher(sql);
        int columnCount = 0;
        while (columns.find()) {
            columnCount++;
        }
        assertEquals(7, columnCount);

        assertEquals(1, occurrences(sql, "CONSTRAINT meeting_manifest_revision_pkey"));
        assertEquals(1, occurrences(sql, "CONSTRAINT meeting_manifest_revision_meeting_fk"));
        assertEquals(1, occurrences(sql, "CONSTRAINT meeting_manifest_revision_previous_fk"));
        assertEquals(1, occurrences(sql, "CONSTRAINT meeting_manifest_revision_chain_ck CHECK"));
        assertEquals(1, occurrences(sql, "CONSTRAINT meeting_manifest_revision_document_ck CHECK"));
        assertEquals(1, occurrences(sql, "CONSTRAINT meeting_manifest_revision_hash_ck CHECK"));

        String compact = sql.replaceAll("\\s+", " ");
        assertTrue(compact.contains(
                "manifest_revision > 1 AND previous_revision IS NOT NULL "
                        + "AND previous_revision = manifest_revision - 1"),
                "revision > 1 must reject a NULL previous_revision before the self-FK");
        assertTrue(compact.contains(
                "$chain$CHECK (manifest_revision = 1 AND previous_revision IS NULL "
                        + "OR manifest_revision > 1 AND previous_revision IS NOT NULL "
                        + "AND previous_revision = (manifest_revision - 1))$chain$"),
                "the PostgreSQL 16 pg_get_constraintdef fingerprint must stay synchronized");
    }

    @Test
    void migrationEnforcesCanonicalRootIdentityRevisionStatusMirrorAndCoreHash()
            throws Exception {
        String compact = readMigration().replaceAll("\\s+", " ");

        for (String field : new String[]{
                "specVersion", "meetingId", "manifestRevision", "profile", "title",
                "status", "startedAt", "endedAt", "durationMs", "languages",
                "artifacts", "warnings"}) {
            assertTrue(compact.contains("'" + field + "'"), field);
        }
        assertTrue(compact.contains("manifest_json ->> 'meetingId' = meeting_id"));
        assertTrue(compact.contains(
                "(manifest_json ->> 'manifestRevision')::NUMERIC = manifest_revision"));
        assertTrue(compact.contains("'READY_WITH_WARNINGS'"));
        assertTrue(compact.contains("'DELETION_PENDING'"));
        assertTrue(compact.contains("canonical_json::JSONB = manifest_json"));
        assertTrue(compact.contains(
                "content_sha256 = sha256(convert_to(canonical_json, 'UTF8'))"));
        assertTrue(compact.contains("octet_length(content_sha256) = 32"));
    }

    @Test
    void migrationStaysInsideFrozenPhysicalFactBoundary() throws Exception {
        String lower = readMigration().toLowerCase();

        assertFalse(lower.contains("create extension"));
        assertFalse(Pattern.compile("\\b(digest|hmac)\\s*\\(").matcher(lower).find(),
                "V007 must use PostgreSQL 16 core sha256 rather than pgcrypto functions");
        assertFalse(lower.contains("create trigger"));
        assertFalse(lower.contains("create index"));
        assertFalse(lower.contains("is_latest"));
        assertFalse(lower.contains("manifest_head"));
        assertFalse(Pattern.compile("\\b(insert|update|delete)\\s+(into|meeting|from)\\b")
                .matcher(lower).find(), "V007 must not perform business DML");
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
                Path.of("deploy", "migrations", "V007__meeting_manifest_revision.sql"),
                Path.of("..", "deploy", "migrations", "V007__meeting_manifest_revision.sql")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("V007 migration file not found");
    }
}
