package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不启动数据库也能核对 V015 的最小事实边界。 */
class JdbcV015DurableAudioMigrationTest {
    @Test
    void containsOnlyCurrentWriterAndFramesWithAtomicRegistration() throws Exception {
        String sql = null;
        for (Path path : new Path[]{
                Path.of("deploy", "migrations", "V015__durable_audio_input.sql"),
                Path.of("..", "deploy", "migrations", "V015__durable_audio_input.sql")}) {
            if (Files.isRegularFile(path)) {
                sql = Files.readString(path, StandardCharsets.UTF_8);
                break;
            }
        }
        assertTrue(sql != null, "V015 migration missing");
        assertTrue(sql.contains("CREATE TABLE meeting_audio_input ("));
        assertTrue(sql.contains("CREATE TABLE meeting_audio_frame ("));
        assertTrue(sql.contains("writer_epoch"));
        assertTrue(sql.contains("PRIMARY KEY (meeting_id, stream_sequence)"));
        assertTrue(sql.contains("octet_length(audio) = duration_ms * 32"));
        assertTrue(sql.contains("INSERT INTO schema_migrations (version)"));
        assertFalse(sql.contains("CREATE TABLE IF NOT EXISTS"));
        assertFalse(sql.contains("writer_history"));
        assertFalse(sql.contains("ALTER TABLE meeting "));
    }
}
