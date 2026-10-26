package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

/** 窄错误分类不能把 FK、截断或所有 23000 都误吞为幂等重放。 */
class JdbcSqlErrorsTest {
    @Test
    void duplicateMatchesOnlyEngineSpecificUniqueViolation() {
        assertTrue(JdbcSqlErrors.isDuplicate(JdbcDatabaseEngine.MYSQL,
                new SQLException("test", "23000", 1062)));
        assertFalse(JdbcSqlErrors.isDuplicate(JdbcDatabaseEngine.MYSQL,
                new SQLException("test", "23000", 1452)));
        assertFalse(JdbcSqlErrors.isDuplicate(JdbcDatabaseEngine.MYSQL,
                new SQLException("test", "22001", 1406)));
        assertFalse(JdbcSqlErrors.isDuplicate(JdbcDatabaseEngine.MYSQL,
                new SQLException("test", "23505")));
    }

    @Test
    void onlyNecessaryLockFailuresAreTransientTransactions() {
        for (int code : new int[]{1205, 1213}) {
            var failure = new SQLException("test", "HY000", code);
            assertTrue(JdbcSqlErrors.isTransientTransaction(JdbcDatabaseEngine.MYSQL, failure));
            assertEquals(JdbcReadLookupSupport.FailureKind.RETRYABLE_UNAVAILABLE,
                    JdbcReadLookupSupport.classify(JdbcDatabaseEngine.MYSQL, failure));
        }
        assertFalse(JdbcSqlErrors.isTransientTransaction(JdbcDatabaseEngine.MYSQL,
                new SQLException("test", "23000", 1452)));
    }
}
