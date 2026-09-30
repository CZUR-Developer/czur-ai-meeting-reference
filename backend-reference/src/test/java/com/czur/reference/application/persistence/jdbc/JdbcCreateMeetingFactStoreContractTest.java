package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** JDBC Create 事实 Store 的事务边界纯 Java 契约。 */
class JdbcCreateMeetingFactStoreContractTest {
    @Test
    void rejectsAutoCommitConnectionBeforeAnySqlOrLifecycleAction() {
        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("getAutoCommit".equals(method.getName())) {
                        return true;
                    }
                    throw new AssertionError("unexpected connection call: " + method.getName());
                });
        JdbcCreateMeetingFactStore store = new JdbcCreateMeetingFactStore(connection);

        JdbcCreateMeetingFactStore.StoredFactException failure = assertThrows(
                JdbcCreateMeetingFactStore.StoredFactException.class,
                () -> store.find("opaque-scope", "create-fact-port-key-1"));
        assertEquals(JdbcCreateMeetingFactStore.FailureKind.TRANSACTION_REQUIRED,
                failure.kind());
        assertEquals("Create fact store requires an explicit transaction", failure.getMessage());
    }
}
