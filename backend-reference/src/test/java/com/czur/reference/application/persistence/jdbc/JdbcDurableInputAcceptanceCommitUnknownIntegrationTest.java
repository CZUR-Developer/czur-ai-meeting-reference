package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.DurableInputAcceptanceUnitOfWork;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 PostgreSQL 上的 commit-unknown 门禁。
 *
 * <p>包装连接先让底层 commit 成功，再伪造 acknowledgement failure。UoW 必须返回
 * 固定的 UNKNOWN、丢弃该连接且不携带底层异常；随后使用未包装的新连接只读确认
 * 数据库事实可能已经提交，测试不根据异常正文猜测提交结果。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcDurableInputAcceptanceCommitUnknownIntegrationTest {
    private static final List<String> MIGRATIONS = List.of(
            "V001__initial_schema.sql",
            "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql",
            "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql",
            "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql",
            "V008__durable_input_fact.sql");
    private static final String MEETING_ID = "durable-input-commit-unknown";
    private static final Instant UPDATED_AT = Instant.parse("2026-09-22T02:00:00.123456Z");

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createFreshOwnedSchema() throws Exception {
        adminDataSource = postgres(null);
        schema = "durable_input_unknown_"
                + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        dataSource = postgres(schema);
        for (int index = 0; index < MIGRATIONS.size(); index++) {
            String migration = MIGRATIONS.get(index);
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(readMigration(migration));
            }
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO schema_migrations(version) VALUES (?)")) {
                statement.setString(1, migration.substring(0, migration.length() - 4));
                statement.executeUpdate();
            }
        }
        createOwnedPendingMeeting();
    }

    @AfterEach
    void dropFreshSchema() throws Exception {
        if (adminDataSource == null || schema == null) {
            return;
        }
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schema) + " CASCADE");
        }
    }

    @Test
    void commitAcknowledgementFailureIsUnknownAndConnectionIsAborted() throws Exception {
        AtomicBoolean abortCalled = new AtomicBoolean();
        AtomicBoolean closeCalled = new AtomicBoolean();
        DataSource failingAcknowledgement = wrappingCommitUnknown(
                dataSource, abortCalled, closeCalled);
        FinalizationInputFact fact = new FinalizationInputFact(
                MEETING_ID, "binding-unknown", 9, 2_345, "zh-CN", List.of("en"),
                UPDATED_AT, "digest-unknown");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new JdbcDurableInputAcceptanceUnitOfWork(
                        failingAcknowledgement, () -> "receipt-unknown")
                        .accept(new DurableInputAcceptanceUnitOfWork.AcceptRequest(
                                MEETING_ID, fact)));
        assertEquals("提交输入接管事实 outcome=UNKNOWN", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("secret-commit-acknowledgement"));
        assertTrue(abortCalled.get(), "unknown commit must abort the uncertain connection");
        assertTrue(closeCalled.get(), "unknown connection must not return to a pool");

        // A fresh, unwrapped connection is the only authority after UNKNOWN. In this
        // injected case delegate.commit() really succeeded, so both facts are visible.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT m.input_state, m.finalization_receipt_id,
                            f.input_binding, f.accepted_at
                       FROM meeting m
                       LEFT JOIN durable_input_fact f ON f.meeting_id = m.meeting_id
                      WHERE m.meeting_id = ?
                     """)) {
            statement.setString(1, MEETING_ID);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("INPUT_ACCEPTED", rows.getString(1));
                assertEquals("receipt-unknown", rows.getString(2));
                assertEquals("binding-unknown", rows.getString(3));
                assertEquals(UPDATED_AT, rows.getTimestamp(4).toInstant());
                assertFalse(rows.next());
            }
        }
    }

    private void createOwnedPendingMeeting() throws Exception {
        String deployment = "commit-unknown-deployment";
        String subject = "commit-unknown-subject";
        Instant createdAt = UPDATED_AT.minusSeconds(20);
        Instant expiresAt = UPDATED_AT.plusSeconds(3_600);
        MeetingGrant grant = new MeetingGrant(
                "grant-commit-unknown", deployment, subject, "profile-commit-unknown", 1,
                Set.of("transcript.realtime"), Set.of("transcript.realtime"),
                3_600_000, createdAt, expiresAt);
        UsageReservation reservation = UsageReservation.reserved(
                "reservation-commit-unknown", grant, MEETING_ID,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                createdAt.plusSeconds(1), expiresAt.minusSeconds(1));
        UsageOperationResult reserve = UsageOperationResult.reserve(
                deployment, subject, "reserve-key-commit-unknown",
                "reserve-digest-commit-unknown", expiresAt,
                AuthorizedProfileSnapshot.from(grant), reservation);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcUsageReservationStore.insertReservation(connection, reservation);
            JdbcUsageReservationStore.insertOperation(connection, reserve, createdAt.plusSeconds(1));
            connection.commit();
        }
        OwnedMeetingCreationUnitOfWork.Result created =
                new JdbcOwnedMeetingCreationUnitOfWork(
                        dataSource, Clock.fixed(createdAt.plusSeconds(2), ZoneOffset.UTC))
                        .create(new OwnedMeetingCreationUnitOfWork.CreateRequest(
                                deployment, subject, MEETING_ID, "session-commit-unknown",
                                "create-key-commit-unknown", "create-digest-commit-unknown", reserve));
        assertTrue(created instanceof OwnedMeetingCreationUnitOfWork.Success);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting
                        SET state='STOPPING', input_state='INPUT_COMPLETE_PENDING',
                            updated_at=?
                      WHERE meeting_id=?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(UPDATED_AT));
            statement.setString(2, MEETING_ID);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static DataSource wrappingCommitUnknown(
            DataSource delegate,
            AtomicBoolean abortCalled,
            AtomicBoolean closeCalled) {
        return (DataSource) Proxy.newProxyInstance(
                JdbcDurableInputAcceptanceCommitUnknownIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        Connection connection = args == null || args.length == 0
                                ? delegate.getConnection()
                                : delegate.getConnection((String) args[0], (String) args[1]);
                        return wrappingConnection(connection, abortCalled, closeCalled);
                    }
                    return method.invoke(delegate, args);
                });
    }

    private static Connection wrappingConnection(
            Connection delegate,
            AtomicBoolean abortCalled,
            AtomicBoolean closeCalled) {
        return (Connection) Proxy.newProxyInstance(
                JdbcDurableInputAcceptanceCommitUnknownIntegrationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("commit")) {
                        delegate.commit();
                        throw new SQLException("secret-commit-acknowledgement", "08006");
                    }
                    if (method.getName().equals("abort")) {
                        abortCalled.set(true);
                        delegate.abort((Executor) args[0]);
                        return null;
                    }
                    if (method.getName().equals("close")) {
                        closeCalled.set(true);
                    }
                    return method.invoke(delegate, args);
                });
    }

    private static PGSimpleDataSource postgres(String currentSchema) {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setUrl(System.getenv("REFERENCE_TEST_DATABASE_URL"));
        postgres.setUser(System.getenv("REFERENCE_TEST_DATABASE_USER"));
        postgres.setPassword(System.getenv("REFERENCE_TEST_DATABASE_PASSWORD"));
        if (currentSchema != null) {
            postgres.setCurrentSchema(currentSchema);
        }
        return postgres;
    }

    private static String readMigration(String filename) throws IOException {
        Path[] candidates = {
                Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("migration file not found: " + filename);
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
