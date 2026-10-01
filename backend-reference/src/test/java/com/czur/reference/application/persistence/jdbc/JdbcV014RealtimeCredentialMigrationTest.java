package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V014 在没有 PostgreSQL 时仍可执行的物理边界静态门禁。 */
class JdbcV014RealtimeCredentialMigrationTest {
    @Test
    void freezesSeparateGrantFactAndExactRerunGate() throws Exception {
        String sql = readMigration();
        assertTrue(sql.contains("CREATE TABLE meeting_realtime_token_grant ("));
        assertTrue(sql.contains("PRIMARY KEY (meeting_id, idempotency_key)"));
        assertTrue(sql.contains("UNIQUE (token_sha256)"));
        assertTrue(sql.contains("REFERENCES meeting (meeting_id) ON DELETE RESTRICT"));
        assertTrue(sql.contains("grant_sha256 = sha256(convert_to(grant_json, 'UTF8'))"));
        assertTrue(sql.contains("token_sha256 = sha256(convert_to("));
        assertTrue(sql.contains("'expiresAt')::TIMESTAMPTZ = expires_at"));
        assertTrue(sql.contains("pg_get_constraintdef(actual.oid, TRUE) = expected.definition"));
        assertTrue(sql.contains("count(*) FROM pg_index WHERE indrelid = grant_table) <> 3"));
        assertTrue(sql.contains("CREATE INDEX meeting_realtime_token_grant_active_idx"));
        assertFalse(sql.contains("CREATE TABLE IF NOT EXISTS"));
        assertFalse(sql.contains("ALTER TABLE meeting_create_fact"));
        assertFalse(sql.contains("INSERT INTO meeting_create_fact"));
    }

    private static String readMigration() throws Exception {
        for (Path path : new Path[]{
                Path.of("deploy", "migrations", "V014__realtime_token_grant.sql"),
                Path.of("..", "deploy", "migrations", "V014__realtime_token_grant.sql")}) {
            if (Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("V014 migration file not found");
    }
}
