package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.port.MeetingCreateFailure;
import com.czur.reference.application.port.MeetingCreateOutcome;
import com.czur.reference.application.port.MeetingCreateOutcomeStore;
import com.czur.reference.application.port.MeetingCreateOutcomeStore.OutcomeRecord;
import com.czur.reference.application.port.MeetingCreateOutcomeStore.Reservation;
import com.czur.reference.application.port.MeetingCreateOutcomeStore.ReservationStatus;
import com.czur.reference.application.port.MeetingCreateOutcomeStore.StoreErrorCode;
import com.czur.reference.application.port.MeetingCreateOutcomeStore.StoreException;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork.ErrorCode;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingOwnership;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** authenticated-create 终态 Store 的真实 PostgreSQL 16 兼容、并发和清理门禁。 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcMeetingCreateOutcomeStoreIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00.123456Z");
    private static final String SCOPE = "opaque-owned-create-scope";

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createSchemaAndMigrate() throws Exception {
        adminDataSource = postgres(null);
        schema = "create_outcome_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        dataSource = postgres(schema);
        applyMigration("V001__initial_schema.sql");
        applyMigration("V002__outbox_lease_fencing.sql");
        applyMigration("V003__identity_entitlement.sql");
    }

    @AfterEach
    void dropSchema() throws Exception {
        if (adminDataSource == null || schema == null) {
            return;
        }
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schema) + " CASCADE");
        }
    }

    @Test
    void successFailureReplayConflictAndRestartPreserveFirstOutcome() throws Exception {
        JdbcMeetingCreateOutcomeStore firstStore = store(dataSource);
        OutcomeRecord success = successRecord("success", "digest-success");
        OutcomeRecord failure = failureRecord("failure", "digest-failure");

        assertEquals(ReservationStatus.ACQUIRED, firstStore.reserve(success).status());
        assertEquals(ReservationStatus.ACQUIRED, firstStore.reserve(failure).status());

        JdbcMeetingCreateOutcomeStore restarted = store(dataSource);
        assertEquals(success, restarted.reserve(success).record());
        assertEquals(ReservationStatus.REPLAY, restarted.reserve(success).status());
        Reservation failureReplay = restarted.reserve(new OutcomeRecord(
                SCOPE, "failure", "digest-failure",
                MeetingCreateOutcome.failure(new MeetingCreateFailure(
                        ErrorCode.VERSION_CONFLICT, false))));
        assertEquals(ReservationStatus.REPLAY, failureReplay.status());
        assertEquals(failure.outcome(), failureReplay.record().outcome(),
                "相同摘要不能用新失败码覆盖首次终态");
        assertEquals(ReservationStatus.CONFLICT,
                restarted.reserve(successRecord("success", "different-digest")).status());

        try (Connection connection = dataSource.getConnection()) {
            assertEquals(200, queryStatus(connection, "success"));
            assertEquals(409, queryStatus(connection, "failure"));
            assertEquals(2, queryCount(connection));
        }
    }

    @Test
    void concurrentSuccessAndFailureHaveExactlyOneWinner() throws Exception {
        OutcomeRecord success = successRecord("race", "digest-race");
        OutcomeRecord failure = failureRecord("race", "digest-race");
        CountDownLatch start = new CountDownLatch(1);
        List<Reservation> results;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Reservation> successFuture = executor.submit(() -> {
                start.await();
                return store(dataSource).reserve(success);
            });
            Future<Reservation> failureFuture = executor.submit(() -> {
                start.await();
                return store(dataSource).reserve(failure);
            });
            start.countDown();
            results = List.of(successFuture.get(), failureFuture.get());
        }

        assertEquals(1, results.stream()
                .filter(result -> result.status() == ReservationStatus.ACQUIRED)
                .count());
        assertEquals(1, results.stream()
                .filter(result -> result.status() == ReservationStatus.REPLAY)
                .count());
        assertEquals(results.get(0).record().outcome(), results.get(1).record().outcome());
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(1, queryCount(connection));
        }
    }

    @Test
    void historicalV1AndCurrentV2SuccessRemainReadable() throws Exception {
        String v1 = "{\"operation\":\"CREATED\",\"meeting\":{"
                + "\"meetingId\":\"legacy\",\"deploymentScope\":\"deployment\","
                + "\"clientSessionId\":\"session\",\"profileId\":\"profile\","
                + "\"profileRevision\":1,\"state\":\"CREATED\","
                + "\"createdAt\":\"2026-09-17T12:00:00Z\","
                + "\"updatedAt\":\"2026-09-17T12:00:00Z\"}}";
        insertRaw("legacy-v1", "digest-v1", 200, v1);
        OutcomeRecord v2 = successRecord("owned-v2", "digest-v2");
        assertEquals(ReservationStatus.ACQUIRED, store(dataSource).reserve(v2).status());

        MeetingCreateOutcome.Success legacy = assertInstanceOf(
                MeetingCreateOutcome.Success.class,
                store(dataSource).find(SCOPE, "legacy-v1").orElseThrow().outcome());
        assertEquals(MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE,
                legacy.result().meeting().subjectScope());
        assertEquals(v2, store(dataSource).find(SCOPE, "owned-v2").orElseThrow());
    }

    @Test
    void damagedStatusOrBodyFailsClosedWithDataExceptionSqlState() throws Exception {
        OutcomeRecord success = successRecord("bad-status-success", "digest-a");
        OutcomeRecord failure = failureRecord("bad-status-failure", "digest-b");
        store(dataSource).reserve(success);
        store(dataSource).reserve(failure);
        update("UPDATE idempotency_record SET response_status=409 WHERE idempotency_key=?",
                "bad-status-success");
        update("UPDATE idempotency_record SET response_status=200 WHERE idempotency_key=?",
                "bad-status-failure");
        insertRaw("bad-body", "digest-c", 200,
                "{\"schemaVersion\":3,\"operation\":\"MEETING_CREATE\","
                        + "\"outcome\":\"FAILURE\",\"errorCode\":\"INTERNAL\"}");

        for (String key : List.of(
                "bad-status-success", "bad-status-failure", "bad-body")) {
            try (Connection connection = dataSource.getConnection()) {
                SQLException corrupted = assertThrows(SQLException.class,
                        () -> JdbcMeetingCreateOutcomeStore.find(connection, SCOPE, key));
                assertEquals("22000", corrupted.getSQLState());
                assertFalse(corrupted.getMessage().contains(key));
                assertNull(corrupted.getCause());
            }
            StoreException publicFailure = assertThrows(StoreException.class,
                    () -> store(dataSource).find(SCOPE, key));
            assertEquals(StoreErrorCode.CORRUPT_DATA, publicFailure.code());
            assertFalse(publicFailure.retryable());
            assertNull(publicFailure.getCause());
        }

        assertThrows(IllegalArgumentException.class,
                () -> store(dataSource).find(" ", "key"));
        assertThrows(IllegalArgumentException.class,
                () -> store(dataSource).find(SCOPE, " "));
    }

    @Test
    void transactionCleanupNeverRestoresAutoCommitAfterUnknownOrUnconfirmedRollback()
            throws Exception {
        for (FaultPoint point : List.of(
                FaultPoint.INSERT_AND_ROLLBACK_SQL,
                FaultPoint.INSERT_AND_ROLLBACK_RUNTIME)) {
            FaultObservation observation = new FaultObservation();
            StoreException failure = assertThrows(StoreException.class,
                    () -> store(faultInjectingDataSource(dataSource, point, observation))
                            .reserve(successRecord("rollback-" + point, "digest")));
            assertEquals(StoreErrorCode.STORAGE_UNAVAILABLE, failure.code());
            assertFalse(observation.restoreAutoCommitAttempted.get());
            assertTrue(observation.closeAttempted.get());
            assertNull(failure.getCause());
            assertTrue(store(dataSource).find(SCOPE, "rollback-" + point).isEmpty());
        }

        for (FaultPoint point : List.of(FaultPoint.BEFORE_COMMIT, FaultPoint.AFTER_COMMIT)) {
            FaultObservation observation = new FaultObservation();
            String key = "commit-" + point;
            StoreException failure = assertThrows(StoreException.class,
                    () -> store(faultInjectingDataSource(dataSource, point, observation))
                            .reserve(successRecord(key, "digest")));
            assertEquals(StoreErrorCode.OUTCOME_UNKNOWN, failure.code());
            assertFalse(observation.restoreAutoCommitAttempted.get());
            assertTrue(observation.closeAttempted.get());
            assertNull(failure.getCause());
            assertEquals(point == FaultPoint.AFTER_COMMIT,
                    store(dataSource).find(SCOPE, key).isPresent(),
                    "commit 调用开始后的相同 OUTCOME_UNKNOWN 允许数据库事实存在或不存在");
        }
    }

    @Test
    void restoreAndCloseSqlOrRuntimeFailuresCannotOverrideCommittedWinner()
            throws Exception {
        for (FaultPoint point : List.of(
                FaultPoint.RESTORE_SQL,
                FaultPoint.RESTORE_RUNTIME,
                FaultPoint.CLOSE_SQL,
                FaultPoint.CLOSE_RUNTIME)) {
            FaultObservation observation = new FaultObservation();
            String key = "cleanup-" + point;
            Reservation result = store(
                    faultInjectingDataSource(dataSource, point, observation))
                    .reserve(successRecord(key, "digest"));
            assertEquals(ReservationStatus.ACQUIRED, result.status());
            assertTrue(observation.restoreAutoCommitAttempted.get());
            assertTrue(observation.closeAttempted.get());
            assertTrue(store(dataSource).find(SCOPE, key).isPresent());
        }
    }

    private JdbcMeetingCreateOutcomeStore store(DataSource source) {
        return new JdbcMeetingCreateOutcomeStore(
                source, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OutcomeRecord successRecord(String key, String digest) {
        return new OutcomeRecord(SCOPE, key, digest,
                MeetingCreateOutcome.success(createdResult(key)));
    }

    private static OutcomeRecord failureRecord(String key, String digest) {
        return new OutcomeRecord(SCOPE, key, digest,
                MeetingCreateOutcome.failure(new MeetingCreateFailure(
                        ErrorCode.RESERVATION_EXPIRED, false)));
    }

    private static MeetingCommandResult createdResult(String suffix) {
        return new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED,
                MeetingAggregate.MeetingSnapshot.owned(
                        "meeting-" + suffix, "deployment", "session-" + suffix,
                        "subject", "grant", NOW.plusSeconds(600), "reservation",
                        "profile", 1, MeetingState.CREATED, NOW, NOW));
    }

    private void insertRaw(String key, String digest, int status, String body)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO idempotency_record (
                         subject_scope, idempotency_key, request_hash,
                         response_status, response_body, created_at)
                     VALUES (?, ?, ?, ?, ?::jsonb, ?)
                     """)) {
            statement.setString(1, SCOPE);
            statement.setString(2, key);
            statement.setString(3, digest);
            statement.setInt(4, status);
            statement.setString(5, body);
            statement.setTimestamp(6, Timestamp.from(NOW));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void update(String sql, String key) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static int queryStatus(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT response_status FROM idempotency_record WHERE idempotency_key=?")) {
            statement.setString(1, key);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static int queryCount(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM idempotency_record")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static DataSource faultInjectingDataSource(
            DataSource delegate, FaultPoint point, FaultObservation observation) {
        return new DelegatingDataSource(delegate) {
            @Override
            public Connection getConnection() throws SQLException {
                return faultInjectingConnection(super.getConnection(), point, observation);
            }
        };
    }

    private static Connection faultInjectingConnection(
            Connection delegate, FaultPoint point, FaultObservation observation) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if ((point == FaultPoint.INSERT_AND_ROLLBACK_SQL
                            || point == FaultPoint.INSERT_AND_ROLLBACK_RUNTIME)
                            && "prepareStatement".equals(method.getName())
                            && arguments != null && arguments.length > 0
                            && arguments[0] instanceof String sql
                            && sql.contains("INSERT INTO idempotency_record")) {
                        throw new SQLException("dynamic insert detail", "08006");
                    }
                    if ("rollback".equals(method.getName())) {
                        if (point == FaultPoint.INSERT_AND_ROLLBACK_SQL) {
                            throw new SQLException("dynamic rollback detail", "08006");
                        }
                        if (point == FaultPoint.INSERT_AND_ROLLBACK_RUNTIME) {
                            throw new IllegalStateException("dynamic rollback detail");
                        }
                    }
                    if ("commit".equals(method.getName())) {
                        if (point == FaultPoint.BEFORE_COMMIT) {
                            throw new SQLException("dynamic commit detail", "08006");
                        }
                        if (point == FaultPoint.AFTER_COMMIT) {
                            delegate.commit();
                            throw new SQLException("dynamic commit detail", "08006");
                        }
                    }
                    if ("setAutoCommit".equals(method.getName())
                            && arguments != null && arguments.length == 1
                            && Boolean.TRUE.equals(arguments[0])) {
                        observation.restoreAutoCommitAttempted.set(true);
                        if (point == FaultPoint.RESTORE_SQL) {
                            throw new SQLException("dynamic restore detail", "08006");
                        }
                        if (point == FaultPoint.RESTORE_RUNTIME) {
                            throw new IllegalStateException("dynamic restore detail");
                        }
                    }
                    if ("close".equals(method.getName())) {
                        observation.closeAttempted.set(true);
                        if (point == FaultPoint.CLOSE_SQL) {
                            delegate.close();
                            throw new SQLException("dynamic close detail", "08006");
                        }
                        if (point == FaultPoint.CLOSE_RUNTIME) {
                            delegate.close();
                            throw new IllegalStateException("dynamic close detail");
                        }
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException reflected) {
                        throw reflected.getCause();
                    }
                });
    }

    private void applyMigration(String filename) throws Exception {
        if (!"V001__initial_schema.sql".equals(filename)) {
            return;
        }
        Path[] candidates = {
                Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename)
        };
        Path migration = null;
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                migration = candidate;
                break;
            }
        }
        if (migration == null) {
            throw new IllegalStateException("migration file not found: " + filename);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(migration, StandardCharsets.UTF_8));
        }
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

    private enum FaultPoint {
        INSERT_AND_ROLLBACK_SQL,
        INSERT_AND_ROLLBACK_RUNTIME,
        BEFORE_COMMIT,
        AFTER_COMMIT,
        RESTORE_SQL,
        RESTORE_RUNTIME,
        CLOSE_SQL,
        CLOSE_RUNTIME
    }

    private static final class FaultObservation {
        private final AtomicBoolean restoreAutoCommitAttempted = new AtomicBoolean();
        private final AtomicBoolean closeAttempted = new AtomicBoolean();
    }

    private static class DelegatingDataSource implements DataSource {
        private final DataSource delegate;

        private DelegatingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return delegate.getConnection(username, password);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
