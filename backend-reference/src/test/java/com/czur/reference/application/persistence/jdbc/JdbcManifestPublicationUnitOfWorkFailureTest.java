package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.ManifestPublicationUnitOfWork;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Manifest 发布 commit/rollback 未确认时的连接生命周期门禁。 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcManifestPublicationUnitOfWorkFailureTest {
    private static final Instant CREATED = Instant.parse("2026-09-21T08:00:00Z");
    private static final Instant CLOCK_VALUE = Instant.parse("2026-09-21T09:00:00Z");
    private static final String MEETING = "mtg_publication_failure";

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createFreshSchema() throws Exception {
        adminDataSource = postgres(null);
        schema = "publication_failure_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        dataSource = postgres(schema);
        for (String migration : List.of(
                "V001__initial_schema.sql",
                "V002__outbox_lease_fencing.sql",
                "V003__identity_entitlement.sql",
                "V004__meeting_event_envelope.sql",
                "V005__meeting_finalization_fact.sql",
                "V006__meeting_read_model.sql",
                "V007__meeting_manifest_revision.sql")) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(readMigration(migration));
            }
        }
        createMeeting();
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
    void rollbackFailureReturnsOutcomeUnknownAndAbortsConnection() {
        AtomicBoolean rollbackCalled = new AtomicBoolean();
        AtomicBoolean abortCalled = new AtomicBoolean();
        AtomicBoolean closeCalled = new AtomicBoolean();
        DataSource failingRollback = wrappedDataSource(
                rollbackCalled, abortCalled, closeCalled, false, true);
        ManifestPublicationUnitOfWork port = new JdbcManifestPublicationUnitOfWork(
                failingRollback, Clock.fixed(CLOCK_VALUE, ZoneOffset.UTC));

        ManifestPublicationUnitOfWork.ExpectedMeetingFacts wrong = expected("wrong-receipt");
        ManifestPublicationUnitOfWork.BeginResult result = port.begin(
                new ManifestPublicationUnitOfWork.BeginRequest(MEETING, wrong));

        ManifestPublicationUnitOfWork.Unavailable unavailable = assertInstanceOf(
                ManifestPublicationUnitOfWork.Unavailable.class, result);
        assertEquals(ManifestPublicationUnitOfWork.UnavailableReason.OUTCOME_UNKNOWN,
                unavailable.reason());
        assertTrue(rollbackCalled.get());
        assertTrue(abortCalled.get());
        assertTrue(closeCalled.get());
        assertFalse(unavailable.toString().contains(MEETING));
    }

    @Test
    void commitFailureAfterDatabaseCommitReturnsOutcomeUnknownAndDoesNotRestorePoolState()
            throws Exception {
        AtomicBoolean rollbackCalled = new AtomicBoolean();
        AtomicBoolean abortCalled = new AtomicBoolean();
        AtomicBoolean closeCalled = new AtomicBoolean();
        DataSource failingCommit = wrappedDataSource(
                rollbackCalled, abortCalled, closeCalled, true, false);
        ManifestPublicationUnitOfWork port = new JdbcManifestPublicationUnitOfWork(
                failingCommit, Clock.fixed(CLOCK_VALUE, ZoneOffset.UTC));

        ManifestPublicationUnitOfWork.BeginResult result = port.begin(
                new ManifestPublicationUnitOfWork.BeginRequest(MEETING, expected("receipt-failure")));
        ManifestPublicationUnitOfWork.Unavailable unavailable = assertInstanceOf(
                ManifestPublicationUnitOfWork.Unavailable.class, result);
        assertEquals(ManifestPublicationUnitOfWork.UnavailableReason.OUTCOME_UNKNOWN,
                unavailable.reason());
        assertFalse(rollbackCalled.get());
        assertTrue(abortCalled.get());
        assertTrue(closeCalled.get());
        assertEquals("GENERATING", scalar("SELECT manifest_state FROM meeting"));
        assertEquals("FINALIZING", scalar("SELECT state FROM meeting"));
    }

    private ManifestPublicationUnitOfWork.ExpectedMeetingFacts expected(String receipt) {
        return new ManifestPublicationUnitOfWork.ExpectedMeetingFacts(
                "failure-deployment",
                "LEGACY_UNOWNED",
                "failure-session",
                "meeting-minutes/v1",
                3,
                FinalizationFact.accepted(receipt));
    }

    private void createMeeting() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting (
                         meeting_id, deployment_scope, client_session_id, subject_scope,
                         profile_id, profile_revision, state, aggregate_version,
                         finalization_receipt_id, created_at, updated_at, metadata,
                         input_state, manifest_state)
                     VALUES (?, 'failure-deployment', 'failure-session', 'LEGACY_UNOWNED',
                             'meeting-minutes/v1', 3, 'FINALIZING', 0, ?, ?, ?, '{}'::jsonb,
                             'INPUT_ACCEPTED', 'NOT_STARTED')
                     """)) {
            statement.setString(1, MEETING);
            statement.setString(2, "receipt-failure");
            statement.setObject(3, java.sql.Timestamp.from(CREATED));
            statement.setObject(4, java.sql.Timestamp.from(CREATED));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private DataSource wrappedDataSource(
            AtomicBoolean rollbackCalled,
            AtomicBoolean abortCalled,
            AtomicBoolean closeCalled,
            boolean failCommit,
            boolean failRollback) {
        return (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("getConnection")) {
                        return invoke(dataSource, method, arguments);
                    }
                    Connection delegate = dataSource.getConnection();
                    return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[]{Connection.class},
                            (connectionProxy, connectionMethod, connectionArguments) -> {
                                switch (connectionMethod.getName()) {
                                    case "rollback" -> {
                                        rollbackCalled.set(true);
                                        if (failRollback) {
                                            throw new SQLException("sensitive rollback", "08006");
                                        }
                                    }
                                    case "abort" -> abortCalled.set(true);
                                    case "close" -> closeCalled.set(true);
                                    case "commit" -> {
                                        invoke(delegate, connectionMethod, connectionArguments);
                                        if (failCommit) {
                                            throw new SQLException("sensitive commit", "08006");
                                        }
                                        return null;
                                    }
                                    default -> {
                                        // 其它 JDBC 操作透明代理到底层测试连接。
                                    }
                                }
                                return invoke(delegate, connectionMethod, connectionArguments);
                            });
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static String readMigration(String filename) throws Exception {
        if (!"V001__initial_schema.sql".equals(filename)) {
            return "SELECT 1";
        }
        for (Path candidate : List.of(
                Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename))) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("required migration is missing");
    }

    private static PGSimpleDataSource postgres(String currentSchema) {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setUrl(System.getenv("REFERENCE_TEST_DATABASE_URL"));
        String user = System.getenv("REFERENCE_TEST_DATABASE_USER");
        String password = System.getenv("REFERENCE_TEST_DATABASE_PASSWORD");
        if (user != null && !user.isBlank()) {
            postgres.setUser(user);
        }
        if (password != null) {
            postgres.setPassword(password);
        }
        if (currentSchema != null) {
            postgres.setCurrentSchema(currentSchema);
        }
        return postgres;
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
