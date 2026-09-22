package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不依赖数据库的最终用量 JDBC 原语事务边界与字段映射契约门。 */
class JdbcFinalUsageFactStoreContractTest {
    @Test
    void primitiveUsesCallerConnectionAndNeverOwnsTransactionLifecycle() throws Exception {
        String source = readSource();

        assertTrue(source.contains("static Optional<FinalUsageFact> find("));
        assertTrue(source.contains("static FinalUsageFactStore.PersistResult persist("));
        assertTrue(source.contains("connection.getAutoCommit()"));
        assertTrue(source.contains("requires autoCommit=false"));
        assertFalse(source.contains("import javax.sql.DataSource"));
        assertFalse(source.contains("private final DataSource"));
        assertFalse(source.contains("connection.commit("));
        assertFalse(source.contains("connection.rollback("));
        assertFalse(source.contains("connection.close("));
    }

    @Test
    void primitiveMapsAllFactFieldsAndClosesTimestampPrecision() throws Exception {
        String source = readSource();

        for (String column : new String[]{
                "meeting_id", "actual_unit", "actual_amount", "source_input_binding",
                "source_input_digest", "source_accepted_at"}) {
            assertTrue(source.contains(column), column);
        }
        assertTrue(source.contains("UsageUnit.MEETING_DURATION_MS.name()"));
        assertTrue(source.contains("actualAmount < 0"));
        assertTrue(source.contains("value.getNano() % 1_000 != 0"));
        assertTrue(source.contains("Timestamp.from(fact.sourceAcceptedAt())"));
        assertTrue(source.contains("sourceAcceptedAt.toInstant()"));
        assertTrue(source.contains("LEFT JOIN durable_input_fact d"));
        assertTrue(source.contains("FinalUsageFact.fromInput(readSourceFact(rows))"));
        assertFalse(source.contains("persisted-final-usage"));
        assertTrue(source.contains("new FinalizationInputFact("));
    }

    private static String readSource() throws Exception {
        Path[] candidates = {
                Path.of("src", "main", "java", "com", "czur", "reference",
                        "application", "persistence", "jdbc",
                        "JdbcFinalUsageFactStore.java"),
                Path.of("backend-reference", "src", "main", "java", "com", "czur",
                        "reference", "application", "persistence", "jdbc",
                        "JdbcFinalUsageFactStore.java")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("JdbcFinalUsageFactStore source file not found");
    }
}
