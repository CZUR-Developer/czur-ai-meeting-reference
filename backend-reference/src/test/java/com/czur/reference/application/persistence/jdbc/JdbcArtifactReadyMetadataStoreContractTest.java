package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V011 READY metadata JDBC 原语的无数据库边界门禁。 */
class JdbcArtifactReadyMetadataStoreContractTest {
    @Test
    void storeIsConnectionScopedAndDoesNotPerformExternalIo() throws Exception {
        String source = readSource();
        assertTrue(source.contains("implements ArtifactReadyMetadataStore"));
        assertTrue(source.contains("connection.getAutoCommit()"));
        assertFalse(source.contains("javax.sql.DataSource"));
        assertFalse(source.contains("connection.commit("));
        assertFalse(source.contains("connection.rollback("));
        assertFalse(source.contains("connection.close("));
        assertFalse(source.contains("Files."));
        assertFalse(source.contains("HttpClient"));
    }

    @Test
    void storeUsesAllFourIdentityAxesAndRejectsUnexplainedEmptyConflictRead() throws Exception {
        String source = readSource();
        assertTrue(source.contains("ON CONFLICT DO NOTHING"));
        assertTrue(source.contains("artifact_id = ?"));
        assertTrue(source.contains("meeting_id = ? AND artifact_type = ? AND revision = ?"));
        assertTrue(source.contains("intent_id = ?"));
        assertTrue(source.contains("storage_binding = ?"));
        assertTrue(source.contains("if (matches.isEmpty())"));
        assertTrue(source.contains("throw corruptData()"));
        assertTrue(source.contains("recordedInstant.getNano() % 1_000 != 0"));
    }

    @Test
    void storePinsSearchPathAndRejectsTemporaryCurrentSchema() throws Exception {
        String source = readSource();
        assertTrue(source.contains("format('%I, pg_temp', current_schema())"));
        assertTrue(source.contains("startsWith(\"pg_temp\")"));
        assertTrue(source.contains("CodingErrorAction.REPORT"));
        assertTrue(source.contains("encoded.remaining() > 4096"));
    }

    private static String readSource() throws Exception {
        Path[] candidates = {
                Path.of("src", "main", "java", "com", "czur", "reference",
                        "application", "persistence", "jdbc",
                        "JdbcArtifactReadyMetadataStore.java"),
                Path.of("backend-reference", "src", "main", "java", "com", "czur",
                        "reference", "application", "persistence", "jdbc",
                        "JdbcArtifactReadyMetadataStore.java")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("JdbcArtifactReadyMetadataStore source file not found");
    }
}
