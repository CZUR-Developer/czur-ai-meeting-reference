package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.error.IdempotencyConflictException;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** opaque scope、外部 key 和 JDBC 动态正文的异常脱敏回归。 */
class JdbcIdempotencySafetyTest {

    @Test
    void conflictAndJdbcFailuresDoNotExposeOwnedScopeOrExternalKey() {
        String deployment = "private-deployment";
        String subject = "private-subject";
        String key = "external-secret-key";
        String encodedScope = IdempotencyScopeCodec.meetingCreate(deployment, subject);

        IdempotencyConflictException conflict =
                new IdempotencyConflictException(encodedScope, key);
        assertRedacted(conflict, deployment, subject, key, encodedScope);

        DataSource failing = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    if ("getConnection".equals(method.getName())) {
                        throw new SQLException(
                                "driver detail " + encodedScope + " " + key, "08001");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        IllegalStateException jdbcFailure = assertThrows(IllegalStateException.class,
                () -> new JdbcIdempotencyStore(failing).find(encodedScope, key));
        assertRedacted(jdbcFailure, deployment, subject, key, encodedScope);
        assertNull(jdbcFailure.getCause());
    }

    private static void assertRedacted(Throwable failure, String... sensitiveValues) {
        for (String sensitive : sensitiveValues) {
            assertFalse(failure.getMessage().contains(sensitive));
        }
    }
}
