package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class JdbcMeetingQueryPortFailureTest {
    private static final String SENSITIVE =
            "jdbc:postgresql://private-db/token-secret?password=hidden";
    private static final byte[] SECRET = secret();

    @Test
    void transientSqlFailureIsTypedWithoutLeakingDriverMessage() {
        JdbcMeetingQueryPort query = new JdbcMeetingQueryPort(
                sqlFailingDataSource(new SQLTransientException(SENSITIVE, "08006")),
                SECRET);

        MeetingQueryPort.Unavailable get = assertInstanceOf(
                MeetingQueryPort.Unavailable.class,
                query.get(new MeetingQueryPort.GetRequest(context(), "meeting-private")));
        MeetingQueryPort.ListUnavailable list = assertInstanceOf(
                MeetingQueryPort.ListUnavailable.class,
                query.list(new MeetingQueryPort.ListRequest(context(), null, 10)));

        assertEquals(MeetingQueryPort.UnavailableReason.TRANSIENT_STORAGE_FAILURE,
                get.reason());
        assertEquals(MeetingQueryPort.UnavailableReason.TRANSIENT_STORAGE_FAILURE,
                list.reason());
        assertFalse(get.toString().contains(SENSITIVE));
        assertFalse(list.toString().contains(SENSITIVE));
    }

    @Test
    void dataSqlFailureMapsToSnapshotConflictWithoutLeakingDatabaseText() {
        JdbcMeetingQueryPort query = new JdbcMeetingQueryPort(
                sqlFailingDataSource(new SQLException(SENSITIVE, "22000")),
                SECRET);

        MeetingQueryPort.Conflict get = assertInstanceOf(
                MeetingQueryPort.Conflict.class,
                query.get(new MeetingQueryPort.GetRequest(context(), "meeting-private")));
        MeetingQueryPort.ListConflict list = assertInstanceOf(
                MeetingQueryPort.ListConflict.class,
                query.list(new MeetingQueryPort.ListRequest(context(), null, 10)));

        assertEquals(MeetingQueryPort.ConflictReason.SNAPSHOT_INCONSISTENT, get.reason());
        assertEquals(MeetingQueryPort.ListConflictReason.SNAPSHOT_INCONSISTENT,
                list.reason());
        assertFalse(get.toString().contains(SENSITIVE));
        assertFalse(list.toString().contains(SENSITIVE));
    }

    @Test
    void runtimeDataSourceFailureMapsToNonRetryableUnavailable() {
        JdbcMeetingQueryPort query = new JdbcMeetingQueryPort(
                runtimeFailingDataSource(), SECRET);

        MeetingQueryPort.Unavailable get = assertInstanceOf(
                MeetingQueryPort.Unavailable.class,
                query.get(new MeetingQueryPort.GetRequest(context(), "meeting-private")));
        MeetingQueryPort.ListUnavailable list = assertInstanceOf(
                MeetingQueryPort.ListUnavailable.class,
                query.list(new MeetingQueryPort.ListRequest(context(), null, 10)));

        assertEquals(MeetingQueryPort.UnavailableReason.STORAGE_FAILURE, get.reason());
        assertEquals(MeetingQueryPort.UnavailableReason.STORAGE_FAILURE, list.reason());
        assertFalse(get.retryable());
        assertFalse(list.retryable());
        assertFalse(get.toString().contains(SENSITIVE));
        assertFalse(list.toString().contains(SENSITIVE));
    }

    private static DataSource sqlFailingDataSource(SQLException failure) {
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

    private static DataSource runtimeFailingDataSource() {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection")) {
                        throw new IllegalStateException(SENSITIVE);
                    }
                    if (method.getName().equals("toString")) {
                        return "SensitiveDataSource[" + SENSITIVE + ']';
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
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

    private static byte[] secret() {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0x5A);
        return secret;
    }
}
