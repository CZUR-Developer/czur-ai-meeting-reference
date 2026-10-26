package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class JdbcValuesTest {
    @Test
    void identifierBytesRetainUnicodeCaseAndTrailingSpaces() throws Exception {
        var call = new AtomicReference<Object[]>();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> { call.set(new Object[]{method.getName(), args}); return null; });
        String original = "范围😀 A  ";
        JdbcValues.setText(statement, 1, JdbcDatabaseEngine.MYSQL, original);
        assertEquals("setBytes", call.get()[0]);
        assertArrayEquals(original.getBytes(StandardCharsets.UTF_8),
                (byte[]) ((Object[]) call.get()[1])[1]);
        assertFalse(JdbcValues.sameCanonicalText("{} ", "{}"));
    }

    @Test
    void instantBindingIsUtcAndTruncatedNotRounded() throws Exception {
        var arguments = new AtomicReference<Object[]>();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> { arguments.set(args); return null; });
        JdbcValues.setInstant(statement, 1, Instant.parse("2030-01-01T00:00:00.123456999Z"));
        assertEquals(Instant.parse("2030-01-01T00:00:00.123456Z"),
                ((Timestamp) arguments.get()[1]).toInstant());
        assertEquals("UTC", ((Calendar) arguments.get()[2]).getTimeZone().getID());
        assertEquals("CAST(? AS JSON)", JdbcValues.jsonParameter(JdbcDatabaseEngine.MYSQL));
    }
}
