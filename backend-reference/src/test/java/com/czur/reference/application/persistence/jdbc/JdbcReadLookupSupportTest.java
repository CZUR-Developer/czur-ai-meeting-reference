package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** typed lookup 共用摘要与 SQL 分类规则的纯 Java 门禁。 */
class JdbcReadLookupSupportTest {
    @Test
    void digestComparisonUsesExactUtf8Bytes() {
        assertTrue(JdbcReadLookupSupport.sameDigest("digest", "digest"));
        assertTrue(JdbcReadLookupSupport.sameDigest("摘要", "摘要"));
        assertFalse(JdbcReadLookupSupport.sameDigest("digest", "digest-other"));
    }

    @Test
    void sqlStateAndJdbcExceptionTypesMapToFrozenCategories() {
        assertEquals(JdbcReadLookupSupport.FailureKind.INCONSISTENT,
                JdbcReadLookupSupport.classify(new SQLException("hidden", "22000")));
        for (String state : new String[]{
                "08006", "40001", "53000", "54000", "55000", "57000", "58000"
        }) {
            assertEquals(JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE,
                    JdbcReadLookupSupport.classify(new SQLException("hidden", state)));
        }
        assertEquals(JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE,
                JdbcReadLookupSupport.classify(
                        new SQLTransientException("hidden", "42000")));
        assertEquals(JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE,
                JdbcReadLookupSupport.classify(
                        new SQLRecoverableException("hidden", (String) null)));
        assertEquals(JdbcReadLookupSupport.FailureKind.NON_RETRYABLE_UNAVAILABLE,
                JdbcReadLookupSupport.classify(new SQLException("hidden", "42000")));
        assertEquals(JdbcReadLookupSupport.FailureKind.NON_RETRYABLE_UNAVAILABLE,
                JdbcReadLookupSupport.classify(
                        new SQLException("hidden", (String) null)));
    }
}
