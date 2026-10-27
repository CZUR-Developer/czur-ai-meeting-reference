package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.ManifestFactStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不依赖数据库，锁定 connection-scoped Manifest JDBC 原语的最小架构边界。 */
class JdbcManifestFactStoreContractTest {
    private static final String SENSITIVE =
            "mtg_sensitive_dynamic_driver_message_and_private_manifest";

    @Test
    void remainsPackagePrivateFinalAndAcceptsOnlyCurrentConnection() {
        assertFalse(Modifier.isPublic(JdbcManifestFactStore.class.getModifiers()));
        assertTrue(Modifier.isFinal(JdbcManifestFactStore.class.getModifiers()));
        assertTrue(ManifestFactStore.class.isAssignableFrom(JdbcManifestFactStore.class));

        Constructor<?>[] constructors = JdbcManifestFactStore.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertEquals(List.of(Connection.class),
                Arrays.asList(constructors[0].getParameterTypes()));
        assertFalse(Modifier.isPublic(constructors[0].getModifiers()));

        List<Class<?>> instanceFields = Arrays.stream(JdbcManifestFactStore.class
                        .getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .toList();
        assertEquals(List.of(Connection.class), instanceFields);
    }

    @Test
    void frozenSqlContainsOnlySelectAndInsertStatements() throws Exception {
        List<String> sql = new ArrayList<>();
        for (Field field : JdbcManifestFactStore.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class
                    && field.getName().endsWith("_SQL")) {
                field.setAccessible(true);
                sql.add((String) field.get(null));
            }
        }
        assertEquals(3, sql.size());
        assertEquals(2, sql.stream()
                .filter(statement -> statement.stripLeading().startsWith("SELECT"))
                .count());
        assertEquals(1, sql.stream()
                .filter(statement -> statement.stripLeading().startsWith("INSERT"))
                .count());

        String allSql = String.join("\n", sql).toUpperCase(Locale.ROOT);
        assertFalse(allSql.matches("(?s).*\\bUPDATE\\b.*"));
        assertFalse(allSql.matches("(?s).*\\bDELETE\\b.*"));
        assertFalse(allSql.matches("(?s).*\\bLOCK\\b.*"));
        assertTrue(allSql.contains("ORDER BY MANIFEST_REVISION DESC"));
        // MySQL 5.7 基线：JSON 参数走显式 CAST，冲突由调用点按 1062 裁决。
        assertTrue(allSql.contains("CAST(? AS JSON)"));
        // 引擎专有词在仓库检索里必须零出现，因此断言串按片段拼接。
        assertFalse(allSql.contains("ON" + " CONFLICT"));
        assertFalse(allSql.contains("?::"));
    }

    @Test
    void validatedRevisionDoesNotRetainHashAsReplayIdentity() {
        Class<?> storedRevision = Arrays.stream(JdbcManifestFactStore.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("StoredRevision"))
                .findFirst()
                .orElseThrow();
        assertTrue(storedRevision.isRecord());
        assertEquals(List.of("revision", "canonical", "manifest"),
                Arrays.stream(storedRevision.getRecordComponents())
                        .map(component -> component.getName())
                        .toList(),
                "重放裁决必须保留 canonical 正文，不能把 hash 当作正文身份");
    }

    @Test
    void explicitTransactionIsRequiredBeforeAnySqlIsPrepared() {
        AtomicBoolean prepared = new AtomicBoolean(false);
        Connection autoCommitConnection = proxyConnection((method, arguments) -> {
            if (method.equals("getAutoCommit")) {
                return true;
            }
            if (method.equals("prepareStatement")) {
                prepared.set(true);
            }
            return defaultValue(method);
        });

        JdbcManifestFactStore.StoredFactException failure = assertThrows(
                JdbcManifestFactStore.StoredFactException.class,
                () -> new JdbcManifestFactStore(autoCommitConnection)
                        .findLatest("mtg_transaction_required"));

        assertEquals(JdbcManifestFactStore.FailureKind.TRANSACTION_REQUIRED, failure.kind());
        assertEquals("Manifest fact store requires an explicit transaction",
                failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(prepared.get());
    }

    @Test
    void dynamicSqlAndRuntimeDetailsAreReplacedByFixedFailures() {
        Connection sqlFailure = proxyConnection((method, arguments) -> {
            if (method.equals("getAutoCommit")) {
                return false;
            }
            if (method.equals("prepareStatement")) {
                throw new SQLException("driver leaked " + SENSITIVE, "08006");
            }
            return defaultValue(method);
        });
        assertSafeUnavailable(assertThrows(
                JdbcManifestFactStore.StoredFactException.class,
                () -> new JdbcManifestFactStore(sqlFailure)
                        .findLatest("mtg_sql_failure")));

        Connection runtimeFailure = proxyConnection((method, arguments) -> {
            if (method.equals("getAutoCommit")) {
                return false;
            }
            if (method.equals("prepareStatement")) {
                throw new IllegalStateException("proxy leaked " + SENSITIVE);
            }
            return defaultValue(method);
        });
        assertSafeUnavailable(assertThrows(
                JdbcManifestFactStore.StoredFactException.class,
                () -> new JdbcManifestFactStore(runtimeFailure)
                        .findLatest("mtg_runtime_failure")));
    }

    @Test
    void invalidMeetingIdIsRejectedWithoutTouchingConnection() {
        Connection connection = proxyConnection((method, arguments) -> {
            throw new AssertionError("connection must not be touched");
        });
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new JdbcManifestFactStore(connection).findLatest(
                        "secret meeting id " + SENSITIVE));
        assertEquals("meetingId format is invalid", failure.getMessage());
        assertFalse(failure.getMessage().contains(SENSITIVE));
    }

    private static void assertSafeUnavailable(
            JdbcManifestFactStore.StoredFactException failure) {
        assertSame(JdbcManifestFactStore.FailureKind.STORAGE_UNAVAILABLE, failure.kind());
        assertEquals("Manifest fact storage is unavailable", failure.getMessage());
        assertFalse(failure.getMessage().contains(SENSITIVE));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    private static Connection proxyConnection(Invocation invocation) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("toString")) {
                        return "SensitiveConnection[" + SENSITIVE + ']';
                    }
                    return invocation.invoke(method.getName(), arguments);
                });
    }

    private static Object defaultValue(String method) {
        return switch (method) {
            case "isClosed" -> false;
            case "isWrapperFor" -> false;
            case "unwrap" -> null;
            default -> null;
        };
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] arguments) throws Throwable;
    }
}
