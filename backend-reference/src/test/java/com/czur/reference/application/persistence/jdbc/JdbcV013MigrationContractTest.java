package com.czur.reference.application.persistence.jdbc;

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

/** 不依赖 PostgreSQL 的 V013 Create 事实物理契约静态门禁。 */
class JdbcV013MigrationContractTest {

    @Test
    void freezesTableColumnsKeysAndSuccessfulMeetingBinding() throws Exception {
        String sql = readMigration();
        String table = tableBlock(sql);
        Matcher declarations = Pattern.compile(
                "(?m)^\\s{12}([a-z0-9_]+)\\s+(?:TEXT|INTEGER|BYTEA|TIMESTAMPTZ)\\b")
                .matcher(table);
        List<String> columns = new ArrayList<>();
        while (declarations.find()) {
            columns.add(declarations.group(1));
        }
        assertEquals(List.of("scope", "idempotency_key", "request_schema_version",
                "request_digest", "request_json", "response_schema_version",
                "response_status", "response_type", "response_json",
                "response_sha256", "meeting_id", "created_at"), columns);

        String compact = table.replaceAll("\\s+", " ");
        assertTrue(compact.contains("PRIMARY KEY (scope, idempotency_key)"));
        assertTrue(compact.contains("UNIQUE (meeting_id)"));
        assertTrue(compact.contains("FOREIGN KEY (meeting_id) REFERENCES meeting (meeting_id) ON DELETE RESTRICT"));
        assertTrue(compact.contains("response_type = 'SUCCESS' AND response_status = 201 AND meeting_id IS NOT NULL"));
        assertTrue(compact.contains("response_type = 'FAILURE' AND response_status BETWEEN 400 AND 599 AND meeting_id IS NULL"));
    }

    @Test
    void freezesJsonDigestAndClosedChecksWithoutChangingOldFacts() throws Exception {
        String sql = readMigration();
        String table = tableBlock(sql);
        for (String name : List.of("meeting_create_fact_key_ck",
                "meeting_create_fact_request_ck", "meeting_create_fact_response_ck",
                "meeting_create_fact_outcome_ck")) {
            assertEquals(1, occurrences(table, "CONSTRAINT " + name));
        }
        assertEquals(4, occurrences(table, ") IS TRUE)"));
        assertTrue(table.contains("request_digest ~ '^[0-9a-f]{64}$'"));
        assertTrue(table.contains("jsonb_typeof(request_json::JSONB) = 'object'"));
        assertTrue(table.contains("jsonb_typeof(response_json::JSONB) = 'object'"));
        assertTrue(table.contains("octet_length(response_sha256) = 32"));
        assertTrue(table.contains("response_sha256 = sha256(convert_to(response_json, 'UTF8'))"));
        assertTrue(table.contains("response_json::JSONB ->> 'meetingId' = meeting_id"));
        assertTrue(table.contains("response_json::JSONB ->> 'state' = 'CREATED'"));
        assertEquals(1, occurrences(sql, "BEGIN;"));
        assertEquals(1, occurrences(sql, "COMMIT;"));
        assertFalse(Pattern.compile("(?i)\\b(?:ALTER|UPDATE|INSERT|DELETE|DROP)\\s+(?:TABLE\\s+|INTO\\s+|FROM\\s+)?(?:meeting|idempotency_record|usage_reservation)\\b")
                .matcher(sql).find());
        assertFalse(sql.contains("CREATE TABLE IF NOT EXISTS"));
    }

    private static String tableBlock(String sql) {
        int start = sql.indexOf("CREATE TABLE meeting_create_fact (");
        assertTrue(start >= 0);
        int end = sql.indexOf("\n        );", start);
        assertTrue(end > start);
        return sql.substring(start, end);
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        for (int offset = 0; (offset = text.indexOf(token, offset)) >= 0;
             offset += token.length()) {
            count++;
        }
        return count;
    }

    private static String readMigration() throws IOException {
        for (Path path : new Path[]{
                Path.of("deploy", "migrations", "V013__meeting_create_fact.sql"),
                Path.of("..", "deploy", "migrations", "V013__meeting_create_fact.sql")}) {
            if (Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("V013 migration file not found");
    }
}
