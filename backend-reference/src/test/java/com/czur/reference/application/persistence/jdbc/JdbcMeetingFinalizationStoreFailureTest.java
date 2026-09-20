package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 最终收口 JDBC 边界在非 SQLException 故障下的脱敏门禁。 */
class JdbcMeetingFinalizationStoreFailureTest {
    private static final String SENSITIVE =
            "jdbc:postgresql://secret-db Token=secret-token";

    @Test
    void findSanitizesDataSourceRuntimeFailure() {
        var store = new JdbcMeetingFinalizationStore(
                runtimeFailingDataSource(), () -> "unused");

        assertSanitized(assertThrows(IllegalStateException.class,
                () -> store.find("meeting-runtime-find")));
    }

    @Test
    void transactionSanitizesDataSourceRuntimeFailure() {
        var store = new JdbcMeetingFinalizationStore(
                runtimeFailingDataSource(), () -> "unused");

        assertSanitized(assertThrows(IllegalStateException.class,
                () -> store.requestInputCompletion("meeting-runtime-write")));
    }

    private static DataSource runtimeFailingDataSource() {
        return (DataSource) Proxy.newProxyInstance(
                JdbcMeetingFinalizationStoreFailureTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection")) {
                        throw new IllegalStateException(SENSITIVE);
                    }
                    if (method.getName().equals("toString")) {
                        return "RuntimeFailingDataSource";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void assertSanitized(IllegalStateException failure) {
        String diagnostic = failure.toString();
        assertFalse(diagnostic.contains("secret-db"));
        assertFalse(diagnostic.contains("secret-token"));
        assertEquals(null, failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }
}
