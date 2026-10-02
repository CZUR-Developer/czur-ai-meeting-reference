package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.MeetingEventLogPort;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JDBC 事件认证读取的故障分类、访问顺序、资源释放和脱敏门禁。 */
class JdbcMeetingEventLogPortFailureTest {
    private static final String SENSITIVE =
            "jdbc:postgresql://private-db/event-body?password=hidden";

    @Test
    void sqlAndRuntimeFailuresMapToStableTypedResults() {
        MeetingEventLogPort.Unavailable transientFailure = assertInstanceOf(
                MeetingEventLogPort.Unavailable.class,
                port(sqlFailure(new SQLTransientException(SENSITIVE, "08006"))).read(request()));
        assertEquals(MeetingEventLogPort.UnavailableReason.TRANSIENT_STORAGE_FAILURE,
                transientFailure.reason());
        assertTrue(transientFailure.retryable());

        MeetingEventLogPort.Unavailable recoverableFailure = assertInstanceOf(
                MeetingEventLogPort.Unavailable.class,
                port(sqlFailure(new SQLRecoverableException(SENSITIVE, "08001"))).read(request()));
        assertEquals(MeetingEventLogPort.UnavailableReason.TRANSIENT_STORAGE_FAILURE,
                recoverableFailure.reason());
        assertTrue(recoverableFailure.retryable());

        MeetingEventLogPort.Unavailable dataFailure = assertInstanceOf(
                MeetingEventLogPort.Unavailable.class,
                port(sqlFailure(new SQLException(SENSITIVE, "22000"))).read(request()));
        assertEquals(MeetingEventLogPort.UnavailableReason.STORAGE_FAILURE,
                dataFailure.reason());
        assertFalse(dataFailure.retryable());

        MeetingEventLogPort.Unavailable storageFailure = assertInstanceOf(
                MeetingEventLogPort.Unavailable.class,
                port(sqlFailure(new SQLException(SENSITIVE, "42000"))).read(request()));
        assertEquals(MeetingEventLogPort.UnavailableReason.STORAGE_FAILURE,
                storageFailure.reason());
        assertFalse(storageFailure.retryable());

        MeetingEventLogPort.Unavailable runtimeFailure = assertInstanceOf(
                MeetingEventLogPort.Unavailable.class,
                port(runtimeFailure()).read(request()));
        assertEquals(MeetingEventLogPort.UnavailableReason.STORAGE_FAILURE,
                runtimeFailure.reason());
        assertFalse(runtimeFailure.retryable());

        for (MeetingEventLogPort.Result result : List.of(
                transientFailure, recoverableFailure, dataFailure,
                storageFailure, runtimeFailure)) {
            assertFalse(result.toString().contains(SENSITIVE));
        }
    }

    @Test
    void deniedMeetingNeverReadsEventColumnsAndClosesEveryJdbcResource() {
        ResourceState resources = new ResourceState();
        AtomicBoolean eventColumnRead = new AtomicBoolean(false);
        Map<String, Object> row = validRow();
        row.put("subject_scope", "subject-other");
        DataSource dataSource = oneRowDataSource(row, resources, column -> {
            if (!column.equals("deployment_scope") && !column.equals("subject_scope")) {
                eventColumnRead.set(true);
                throw new AssertionError("access guard must run before event decoding");
            }
        });

        assertInstanceOf(MeetingEventLogPort.AccessDenied.class,
                port(dataSource).read(request()));

        assertFalse(eventColumnRead.get());
        resources.assertAllClosed();
        assertEquals(1, resources.preparedStatements.get());
        assertEquals(1, resources.executions.get());
    }

    @Test
    void malformedOwnerEventClosesResourcesWithoutClaimingReplayExpiry() {
        ResourceState resources = new ResourceState();
        Map<String, Object> row = validRow();
        row.put("canonical_payload", " ");

        MeetingEventLogPort.Unavailable unavailable = assertInstanceOf(
                MeetingEventLogPort.Unavailable.class,
                port(oneRowDataSource(row, resources, ignored -> { })).read(request()));

        assertEquals(MeetingEventLogPort.UnavailableReason.STORAGE_FAILURE,
                unavailable.reason());
        assertFalse(unavailable.retryable());
        resources.assertAllClosed();
    }

    @Test
    void logsNeverContainMeetingScopeBodyOrDatabaseExceptionText() {
        Logger logger = Logger.getLogger(JdbcMeetingEventLogPort.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level oldLevel = logger.getLevel();
        boolean oldParent = logger.getUseParentHandlers();
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        try {
            MeetingEventLogPort.Result result = port(sqlFailure(
                    new SQLTransientException(SENSITIVE, "08006"))).read(
                    new MeetingEventLogPort.Request(
                            context(), "meeting-sensitive-private", 7, 10));
            String logs = handler.output.toString();
            assertInstanceOf(MeetingEventLogPort.Unavailable.class, result);
            for (String secret : Set.of(
                    SENSITIVE,
                    "meeting-sensitive-private",
                    "deployment-private",
                    "subject-private",
                    "event-body")) {
                assertFalse(logs.contains(secret), "日志泄露敏感字段: " + secret);
                assertFalse(result.toString().contains(secret),
                        "结果诊断泄露敏感字段: " + secret);
            }
            assertTrue(logs.contains("08006"));
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(oldParent);
            logger.setLevel(oldLevel);
        }
    }

    private static JdbcMeetingEventLogPort port(DataSource dataSource) {
        return new JdbcMeetingEventLogPort(dataSource);
    }

    private static MeetingEventLogPort.Request request() {
        return new MeetingEventLogPort.Request(context(), "meeting-private", 0, 10);
    }

    private static MeetingCommandContext context() {
        Instant issued = Instant.parse("2026-09-20T00:00:00Z");
        return new MeetingCommandContext(
                "deployment-private",
                new VerifiedIdentity(
                        new Subject("subject-private"),
                        Set.of("USER"),
                        Set.of("meeting:read"),
                        issued,
                        issued.plusSeconds(3600),
                        "credential-private"));
    }

    private static Map<String, Object> validRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("deployment_scope", "deployment-private");
        row.put("subject_scope", "subject-private");
        row.put("has_invalid_event", false);
        row.put("max_event_sequence", 1L);
        row.put("event_present", true);
        row.put("meeting_id", "meeting-private");
        row.put("event_sequence", 1L);
        row.put("event_id", "event-private");
        row.put("event_type", "transcriptUpsert");
        row.put("canonical_payload", "{\"text\":\"event-body\"}");
        row.put("occurred_at", Timestamp.from(Instant.parse("2026-09-20T00:00:01Z")));
        row.put("spec_version", "0.1");
        row.put("stream_id", "stream-private");
        return row;
    }

    private static DataSource sqlFailure(SQLException failure) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection")) {
                        throw failure;
                    }
                    if (method.getName().equals("toString")) {
                        return "SensitiveDataSource[" + SENSITIVE + ']';
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static DataSource runtimeFailure() {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection")) {
                        throw new IllegalStateException(SENSITIVE);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static DataSource oneRowDataSource(
            Map<String, Object> row,
            ResourceState resources,
            ColumnAccess columnAccess) {
        AtomicInteger cursor = new AtomicInteger();
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "next" -> cursor.getAndIncrement() == 0;
                    case "getString" -> {
                        String column = (String) arguments[0];
                        columnAccess.onAccess(column);
                        Object value = row.get(column);
                        yield value == null ? null : value.toString();
                    }
                    case "getBoolean" -> {
                        String column = (String) arguments[0];
                        columnAccess.onAccess(column);
                        yield Boolean.TRUE.equals(row.get(column));
                    }
                    case "getLong" -> {
                        String column = (String) arguments[0];
                        columnAccess.onAccess(column);
                        yield ((Number) row.get(column)).longValue();
                    }
                    case "getTimestamp" -> {
                        String column = (String) arguments[0];
                        columnAccess.onAccess(column);
                        yield row.get(column);
                    }
                    case "close" -> {
                        resources.resultSetClosed.set(true);
                        yield null;
                    }
                    case "toString" -> "SensitiveResultSet[" + SENSITIVE + ']';
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "setLong", "setInt", "setString" -> null;
                    case "executeQuery" -> {
                        resources.executions.incrementAndGet();
                        yield resultSet;
                    }
                    case "close" -> {
                        resources.statementClosed.set(true);
                        yield null;
                    }
                    case "toString" -> "SensitiveStatement[" + SENSITIVE + ']';
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        resources.preparedStatements.incrementAndGet();
                        yield statement;
                    }
                    case "close" -> {
                        resources.connectionClosed.set(true);
                        yield null;
                    }
                    case "toString" -> "SensitiveConnection[" + SENSITIVE + ']';
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getConnection" -> connection;
                    case "toString" -> "SensitiveDataSource[" + SENSITIVE + ']';
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @FunctionalInterface
    private interface ColumnAccess {
        void onAccess(String column);
    }

    private static final class ResourceState {
        private final AtomicBoolean resultSetClosed = new AtomicBoolean();
        private final AtomicBoolean statementClosed = new AtomicBoolean();
        private final AtomicBoolean connectionClosed = new AtomicBoolean();
        private final AtomicInteger preparedStatements = new AtomicInteger();
        private final AtomicInteger executions = new AtomicInteger();

        private void assertAllClosed() {
            assertTrue(resultSetClosed.get(), "ResultSet 必须关闭");
            assertTrue(statementClosed.get(), "PreparedStatement 必须关闭");
            assertTrue(connectionClosed.get(), "Connection 必须关闭");
        }
    }

    private static final class CapturingHandler extends Handler {
        private final StringBuilder output = new StringBuilder();

        @Override
        public void publish(LogRecord record) {
            output.append(record.getMessage());
            if (record.getParameters() != null) {
                for (Object parameter : record.getParameters()) {
                    output.append('|').append(parameter);
                }
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
