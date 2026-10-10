package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V011 执行事实 JDBC 原语的无数据库边界门禁。 */
class JdbcFinalizationExecutionFactStoreContractTest {
    @Test
    void storeIsConnectionScopedAndNeverOwnsTransactionLifecycle() throws Exception {
        String source = readSource("JdbcFinalizationExecutionFactStore.java");
        assertTrue(source.contains("implements FinalizationExecutionFactStore"));
        assertTrue(source.contains("connection.getAutoCommit()"));
        assertTrue(source.contains("requires an explicit transaction"));
        assertFalse(source.contains("javax.sql.DataSource"));
        assertFalse(source.contains("connection.commit("));
        assertFalse(source.contains("connection.rollback("));
        assertFalse(source.contains("connection.close("));
    }

    @Test
    void storeUsesAtomicConflictRetryAndChecksAllPhysicalMirrors() throws Exception {
        String source = readSource("JdbcFinalizationExecutionFactStore.java");
        assertTrue(source.contains("ON CONFLICT (meeting_id) DO NOTHING"));
        assertTrue(source.contains("FinalizationExecutionFactJson.decode(canonical)"));
        assertTrue(source.contains("FinalizationExecutionFactJson.decode(mirror)"));
        assertTrue(source.contains("MessageDigest.isEqual(storedDigest, sha256(canonical))"));
        assertTrue(source.contains("canonical.equals(FinalizationExecutionFactJson.encode(fact))"));
        assertTrue(source.contains("format('%I, pg_temp', current_schema())"));
        assertTrue(source.contains("current_schema()"));
        assertTrue(source.contains("startsWith(\"pg_temp\")"));
    }

    @Test
    void diagnosticsDoNotIncludeBusinessIdentifiers() throws Exception {
        String source = readSource("JdbcFinalizationExecutionFactStore.java");
        assertTrue(source.contains("phase={0} result={1}"));
        assertFalse(source.contains("meetingId={0}"));
        assertFalse(source.contains("canonical={0}"));
    }

    private static String readSource(String name) throws Exception {
        Path[] candidates = {
                Path.of("..", "backend-core", "src", "main", "java", "com", "czur", "reference",
                        "application", "persistence", "jdbc", name),
                Path.of("backend-core", "src", "main", "java", "com", "czur",
                        "reference", "application", "persistence", "jdbc", name)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException(name + " source file not found");
    }
}
