package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.Duration;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcDatabaseRuntimeTest {
    @Test
    void startupFailureIsFailClosedAndDoesNotExposeConnectionDetails() {
        String dbValue = "runtime-secret";
        String url = "jdbc:postgresql://127.0.0.1:1/unavailable";
        DatabaseRuntimeConfig config = DatabaseRuntimeConfig.of(
                url, "runtime-user", dbValue,
                Duration.ofMillis(250), Duration.ofMillis(250), 1, 0);

        DatabaseStartupException failure = assertThrows(
                DatabaseStartupException.class,
                () -> JdbcDatabaseRuntime.start(config));

        assertFalse(failure.getMessage().contains(url));
        assertFalse(failure.getMessage().contains(dbValue));
        // 启动异常不保留可能由 JDBC 驱动携带 URL/参数的 cause 链。
        assertNull(failure.getCause());
    }

    @Test
    void startupDiagnosticsUseJulAndRemainRedacted() {
        String dbValue = "runtime-log-value";
        String url = "jdbc:postgresql://127.0.0.1:1/log-unavailable";
        DatabaseRuntimeConfig config = DatabaseRuntimeConfig.of(
                url, "runtime-log-user", dbValue,
                Duration.ofMillis(250), Duration.ofMillis(250), 1, 0);
        Logger root = Logger.getLogger("");
        StringWriter output = new StringWriter();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                output.append(record.getLevel().getName())
                        .append(' ')
                        .append(record.getLoggerName())
                        .append(' ')
                        .append(record.getMessage())
                        .append('\n');
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.INFO);
        root.addHandler(capture);
        try {
            assertThrows(DatabaseStartupException.class,
                    () -> JdbcDatabaseRuntime.start(config));
        } finally {
            root.removeHandler(capture);
        }
        String logs = output.toString();
        assertFalse(logs.contains("runtime-log-user"));
        assertFalse(logs.contains(dbValue));
        assertFalse(logs.contains(url));
        assertFalse(logs.contains("StaticLoggerBinder"));
    }
}
