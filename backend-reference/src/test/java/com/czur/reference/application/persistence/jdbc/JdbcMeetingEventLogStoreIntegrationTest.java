package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.EventLogStore;
import com.czur.reference.application.port.MeetingEventPage;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC 事件日志的真实 PostgreSQL 门禁。
 *
 * <p>测试只有显式设置 {@code REFERENCE_TEST_DATABASE_URL} 时运行。每个测试使用
 * 随机 schema，迁移和数据均限制在该 schema 内；不会连接、启动、删除或修改任何
 * Docker 数据卷。未设置数据库时，默认 Maven 测试会将本类全部跳过。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcMeetingEventLogStoreIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource schemaDataSource;
    private String schema;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        adminDataSource = dataSource(null);
        schema = "event_log_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quotedIdentifier(schema));
        }
        schemaDataSource = dataSource(schema);
        applyMigration("V001__initial_schema.sql");
        insertMeeting("meeting-event-log");
    }

    @AfterEach
    void dropIsolatedSchema() throws Exception {
        if (adminDataSource == null || schema == null) {
            return;
        }
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + quotedIdentifier(schema) + " CASCADE");
        }
    }

    @Test
    void appendsReplaysConflictsAndPagesInStrictSequenceOrder() throws Exception {
        applyMigration("V004__meeting_event_envelope.sql");
        JdbcMeetingEventLogStore store = new JdbcMeetingEventLogStore(schemaDataSource);

        EventLog first = event(1, "event-1", "TRANSCRIPT", "{\"a\":1,\"b\":2}");
        EventLogStore.AppendResult appended = store.append(first);
        assertEquals(EventLogStore.AppendStatus.APPENDED, appended.status());
        assertEquals(first.canonicalPayloadJson(), appended.event().canonicalPayloadJson(),
                "数据库返回必须保留 canonical 原文，不得返回 JSONB 重排文本");

        // 调用方重复提交同一份 canonical JSON。JDBC 必须跨越 JSONB 存储展示
        // 差异仍然识别重放，但这里不把任意重排 JSON 定义成公开契约。
        EventLogStore.AppendResult replay = store.append(first);
        assertEquals(EventLogStore.AppendStatus.REPLAYED, replay.status());
        assertEquals(first.eventId(), replay.event().eventId());
        assertEquals(first.canonicalPayloadJson(), replay.event().canonicalPayloadJson());

        EventLogStore.AppendResult reorderedConflict = store.append(
                event(1, "event-1", "TRANSCRIPT", "{\"b\":2,\"a\":1}"));
        assertEquals(EventLogStore.AppendStatus.CONFLICT, reorderedConflict.status(),
                "上游 canonical 原文不同不能被 JDBC 擅自放宽为重放");

        EventLogStore.AppendResult conflict = store.append(
                event(1, "event-1", "TRANSCRIPT", "{\"a\":999}"));
        assertEquals(EventLogStore.AppendStatus.CONFLICT, conflict.status());
        assertEquals(first.eventType(), conflict.event().eventType());

        store.append(event(2, "event-2", "TRANSCRIPT", "{\"text\":\"two\"}"));
        store.append(event(3, "event-3", "TRANSCRIPT", "{\"text\":\"three\"}"));
        MeetingEventPage firstPage = store.readPage("meeting-event-log", "realtime", 0, 2);
        assertEquals(2, firstPage.items().size());
        assertEquals(2, firstPage.lastSequence());
        assertTrue(firstPage.hasMore());
        MeetingEventPage secondPage = store.readPage("meeting-event-log", "realtime", 2, 2);
        assertEquals(1, secondPage.items().size());
        assertEquals(3, secondPage.lastSequence());
        assertTrue(!secondPage.hasMore());
        assertEquals(0, store.readPage("meeting-event-log", "other", 0, 10).items().size());
    }

    @Test
    void rejectsGapsAndInvalidJsonWithoutLeavingRows() throws Exception {
        applyMigration("V004__meeting_event_envelope.sql");
        JdbcMeetingEventLogStore store = new JdbcMeetingEventLogStore(schemaDataSource);

        assertThrows(IllegalArgumentException.class,
                () -> store.append(event(2, "event-gap", "TRANSCRIPT", "{}")));
        assertThrows(IllegalStateException.class,
                () -> store.append(event(1, "event-invalid-json", "TRANSCRIPT", "not-json")));
        assertEquals(0, store.readPage("meeting-event-log", "realtime", 0, 10).items().size());
    }

    @Test
    void databaseAndRollbackFailuresNeverExposeCanonicalPayload() throws Exception {
        applyMigration("V004__meeting_event_envelope.sql");
        String payloadSentinel = "SENTINEL_AI_MEETING_PAYLOAD_4c43e8";
        String rollbackSentinel = "SENTINEL_AI_MEETING_ROLLBACK_7c81a1";
        EventLog invalidJson = event(
                1, "event-sensitive-error", "TRANSCRIPT",
                "{\"secret\":\"" + payloadSentinel + "\"");

        JdbcMeetingEventLogStore store = new JdbcMeetingEventLogStore(schemaDataSource);
        IllegalStateException databaseFailure = assertThrows(
                IllegalStateException.class, () -> store.append(invalidJson));
        assertThrowableDoesNotContain(databaseFailure, payloadSentinel);
        assertNull(databaseFailure.getCause(), "公开数据库异常不得保留原始 SQLException cause");

        JdbcMeetingEventLogStore rollbackFailingStore = new JdbcMeetingEventLogStore(
                rollbackFailingDataSource(schemaDataSource, rollbackSentinel));
        IllegalStateException rollbackFailure = assertThrows(
                IllegalStateException.class, () -> rollbackFailingStore.append(invalidJson));
        assertTrue(rollbackFailure.getSuppressed().length > 0,
                "合成 rollback 失败必须保留不含正文的安全诊断");
        assertThrowableDoesNotContain(rollbackFailure, payloadSentinel);
        assertThrowableDoesNotContain(rollbackFailure, rollbackSentinel);
        assertNull(rollbackFailure.getCause(), "rollback 失败也不得恢复原始数据库 cause");

        assertEquals(0, store.readPage("meeting-event-log", "realtime", 0, 10).items().size(),
                "数据库错误和 rollback 错误都不能留下部分事件");
    }

    @Test
    void postInsertClientFailureAndRollbackFailureDiscardsConnectionWithoutCommit() throws Exception {
        applyMigration("V004__meeting_event_envelope.sql");
        String payloadSentinel = "SENTINEL_POST_INSERT_PAYLOAD_b8a3c1";
        String executeSentinel = "SENTINEL_POST_INSERT_EXECUTE_42a91d";
        String rollbackSentinel = "SENTINEL_POST_INSERT_ROLLBACK_559c2e";
        AtomicBoolean autoCommitRestoreAttempted = new AtomicBoolean(false);
        AtomicBoolean abortCalled = new AtomicBoolean(false);
        DataSource faultingDataSource = postInsertFailureDataSource(
                schemaDataSource,
                executeSentinel,
                rollbackSentinel,
                autoCommitRestoreAttempted,
                abortCalled);
        EventLog event = event(
                1, "event-post-insert-failure", "TRANSCRIPT",
                "{\"secret\":\"" + payloadSentinel + "\"}");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new JdbcMeetingEventLogStore(faultingDataSource).append(event));

        assertThrowableDoesNotContain(failure, payloadSentinel);
        assertThrowableDoesNotContain(failure, executeSentinel);
        assertThrowableDoesNotContain(failure, rollbackSentinel);
        assertNull(failure.getCause());
        assertFalse(autoCommitRestoreAttempted.get(),
                "rollback 未确认时禁止 setAutoCommit(true)，否则可能隐式提交");
        assertTrue(abortCalled.get(), "rollback 未确认时必须主动废弃连接");

        JdbcMeetingEventLogStore cleanStore = new JdbcMeetingEventLogStore(schemaDataSource);
        assertEquals(0,
                cleanStore.readPage("meeting-event-log", "realtime", 0, 10).items().size(),
                "服务端 INSERT 后客户端失败也必须通过连接废弃回滚为零行");
        assertEquals(EventLogStore.AppendStatus.APPENDED, cleanStore.append(event).status(),
                "故障连接废弃后同一事件应仍可作为首次写入");
    }

    @Test
    void normalSuccessRestoresConnectionForSingleConnectionPoolReuse() throws Exception {
        applyMigration("V004__meeting_event_envelope.sql");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("REFERENCE_TEST_DATABASE_URL"));
        String user = System.getenv("REFERENCE_TEST_DATABASE_USER");
        String password = System.getenv("REFERENCE_TEST_DATABASE_PASSWORD");
        if (user != null && !user.isBlank()) {
            config.setUsername(user);
        }
        if (password != null) {
            config.setPassword(password);
        }
        config.addDataSourceProperty("currentSchema", schema);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setPoolName("event-log-reuse-test");

        try (HikariDataSource pool = new HikariDataSource(config)) {
            JdbcMeetingEventLogStore store = new JdbcMeetingEventLogStore(pool);
            assertEquals(EventLogStore.AppendStatus.APPENDED,
                    store.append(event(1, "event-reuse-1", "TRANSCRIPT", "{\"value\":1}")).status());
            assertEquals(EventLogStore.AppendStatus.APPENDED,
                    store.append(event(2, "event-reuse-2", "TRANSCRIPT", "{\"value\":2}")).status());
            assertEquals(2, store.readPage("meeting-event-log", "realtime", 0, 10).items().size());
            assertEquals(1, pool.getHikariPoolMXBean().getTotalConnections(),
                    "成功路径应恢复并复用唯一物理连接，而不是每次废弃");
        }
    }

    @Test
    void nanosecondTimestampIsNormalizedBeforePersistingAndReplaysExactly() throws Exception {
        applyMigration("V004__meeting_event_envelope.sql");
        JdbcMeetingEventLogStore store = new JdbcMeetingEventLogStore(schemaDataSource);
        EventLog event = new EventLog(
                "0.1", "meeting-event-log", "realtime", "event-time-precision", 1,
                Instant.parse("2026-09-20T08:00:00.123456789Z"),
                "TRANSCRIPT", "{\"time\":true}");

        assertEquals(Instant.parse("2026-09-20T08:00:00.123456Z"), event.emittedAt());
        assertEquals(EventLogStore.AppendStatus.APPENDED, store.append(event).status());
        assertEquals(EventLogStore.AppendStatus.REPLAYED, store.append(event).status());
        assertEquals(event.emittedAt(),
                store.readPage("meeting-event-log", "realtime", 0, 10)
                        .items().getFirst().emittedAt());
    }

    @Test
    void historicalEnvelopeWithoutV004MetadataFailsClosed() throws Exception {
        insertHistoricalEvent();
        applyMigration("V004__meeting_event_envelope.sql");
        JdbcMeetingEventLogStore store = new JdbcMeetingEventLogStore(schemaDataSource);

        assertThrows(IllegalStateException.class,
                () -> store.readPage("meeting-event-log", "realtime", 0, 10));
    }

    @Test
    void concurrentRetryOfTheSameEventIsOneAppendAndOneReplay() throws Exception {
        applyMigration("V004__meeting_event_envelope.sql");
        JdbcMeetingEventLogStore store = new JdbcMeetingEventLogStore(schemaDataSource);
        EventLog event = event(1, "event-concurrent", "TRANSCRIPT", "{\"same\":true}");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<EventLogStore.AppendStatus> task = () -> store.append(event).status();
            Future<EventLogStore.AppendStatus> first = executor.submit(task);
            Future<EventLogStore.AppendStatus> second = executor.submit(task);
            EventLogStore.AppendStatus firstStatus = first.get();
            EventLogStore.AppendStatus secondStatus = second.get();
            assertEquals(1,
                    java.util.stream.Stream.of(firstStatus, secondStatus)
                            .filter(status -> status == EventLogStore.AppendStatus.APPENDED)
                            .count());
            assertEquals(1,
                    java.util.stream.Stream.of(firstStatus, secondStatus)
                            .filter(status -> status == EventLogStore.AppendStatus.REPLAYED)
                            .count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentFirstSequencesForSameMeetingCannotBothCommit() throws Exception {
        applyMigration("V004__meeting_event_envelope.sql");
        JdbcMeetingEventLogStore store = new JdbcMeetingEventLogStore(schemaDataSource);
        EventLog candidateA = event(1, "event-concurrent-a", "TRANSCRIPT", "{\"from\":\"a\"}");
        EventLog candidateB = event(1, "event-concurrent-b", "TRANSCRIPT", "{\"from\":\"b\"}");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<String> taskA = () -> appendOrSequenceRejected(store, candidateA);
            Callable<String> taskB = () -> appendOrSequenceRejected(store, candidateB);
            Future<String> firstFuture = executor.submit(taskA);
            Future<String> secondFuture = executor.submit(taskB);
            String first = firstFuture.get();
            String second = secondFuture.get();
            assertEquals(1,
                    java.util.stream.Stream.of(first, second)
                            .filter("APPENDED"::equals)
                            .count());
            assertEquals(1,
                    java.util.stream.Stream.of(first, second)
                            .filter("SEQUENCE_REJECTED"::equals)
                            .count());
            assertEquals(1, store.readPage("meeting-event-log", "realtime", 0, 10).items().size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameEventIdAcrossMeetingsIsGloballyUniqueAndNeverOverwritesWinner() throws Exception {
        applyMigration("V004__meeting_event_envelope.sql");
        insertSecondMeeting();
        JdbcMeetingEventLogStore store = new JdbcMeetingEventLogStore(schemaDataSource);
        EventLog firstMeeting = event(1, "event-global", "TRANSCRIPT", "{\"owner\":1}");
        EventLog secondMeeting = new EventLog(
                "0.1", "meeting-event-log-2", "realtime", "event-global", 1,
                NOW, "TRANSCRIPT", "{\"owner\":2}");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<EventLogStore.AppendResult> first = executor.submit(() -> store.append(firstMeeting));
            Future<EventLogStore.AppendResult> second = executor.submit(() -> store.append(secondMeeting));
            EventLogStore.AppendResult firstResult = first.get();
            EventLogStore.AppendResult secondResult = second.get();
            assertEquals(1,
                    java.util.stream.Stream.of(firstResult, secondResult)
                            .filter(result -> result.status() == EventLogStore.AppendStatus.APPENDED)
                            .count());
            assertEquals(1,
                    java.util.stream.Stream.of(firstResult, secondResult)
                            .filter(result -> result.status() == EventLogStore.AppendStatus.CONFLICT)
                            .count());
            EventLog winner = firstResult.status() == EventLogStore.AppendStatus.APPENDED
                    ? firstResult.event() : secondResult.event();
            EventLog conflictRecord = firstResult.status() == EventLogStore.AppendStatus.CONFLICT
                    ? firstResult.event() : secondResult.event();
            assertEquals(winner, conflictRecord, "冲突结果必须返回数据库中保留的赢家记录");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void connectionScopedAppendRequiresTransactionAndRollsBackWithCaller() throws Exception {
        applyMigration("V004__meeting_event_envelope.sql");
        EventLog event = event(1, "event-transaction", "TRANSCRIPT", "{\"tx\":true}");
        try (Connection connection = schemaDataSource.getConnection()) {
            assertTrue(connection.getAutoCommit());
            assertThrows(IllegalStateException.class,
                    () -> JdbcMeetingEventLogStore.append(connection, event));

            connection.setAutoCommit(false);
            assertEquals(EventLogStore.AppendStatus.APPENDED,
                    JdbcMeetingEventLogStore.append(connection, event).status());
            connection.rollback();
        }

        JdbcMeetingEventLogStore store = new JdbcMeetingEventLogStore(schemaDataSource);
        assertEquals(EventLogStore.AppendStatus.APPENDED, store.append(event).status(),
                "调用方回滚后同一事件应能够首次写入");
    }

    private EventLog event(long sequence, String eventId, String type, String payload) {
        return new EventLog(
                "0.1", "meeting-event-log", "realtime", eventId, sequence,
                NOW, type, payload);
    }

    private static String appendOrSequenceRejected(
            JdbcMeetingEventLogStore store, EventLog event) {
        try {
            return store.append(event).status().name();
        } catch (IllegalArgumentException expected) {
            return "SEQUENCE_REJECTED";
        }
    }

    private static DataSource rollbackFailingDataSource(DataSource delegate, String sentinel) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    Object result = invoke(delegate, method, arguments);
                    if (result instanceof Connection connection
                            && method.getName().equals("getConnection")) {
                        return rollbackFailingConnection(connection, sentinel);
                    }
                    return result;
                });
    }

    private static DataSource postInsertFailureDataSource(
            DataSource delegate,
            String executeSentinel,
            String rollbackSentinel,
            AtomicBoolean autoCommitRestoreAttempted,
            AtomicBoolean abortCalled) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    Object result = invoke(delegate, method, arguments);
                    if (result instanceof Connection connection
                            && method.getName().equals("getConnection")) {
                        return postInsertFailureConnection(
                                connection,
                                executeSentinel,
                                rollbackSentinel,
                                autoCommitRestoreAttempted,
                                abortCalled);
                    }
                    return result;
                });
    }

    private static Connection postInsertFailureConnection(
            Connection delegate,
            String executeSentinel,
            String rollbackSentinel,
            AtomicBoolean autoCommitRestoreAttempted,
            AtomicBoolean abortCalled) {
        AtomicBoolean insertExecuted = new AtomicBoolean(false);
        AtomicBoolean rollbackFailed = new AtomicBoolean(false);
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("prepareStatement")
                            && arguments != null
                            && arguments.length > 0
                            && arguments[0] instanceof String sql) {
                        Object prepared = invoke(delegate, method, arguments);
                        if (sql.contains("INSERT INTO meeting_event")) {
                            return postInsertFailureStatement(
                                    (PreparedStatement) prepared, executeSentinel, insertExecuted);
                        }
                        return prepared;
                    }
                    if (method.getName().equals("rollback")
                            && (arguments == null || arguments.length == 0)
                            && insertExecuted.get()
                            && rollbackFailed.compareAndSet(false, true)) {
                        throw new SQLException(
                                "synthetic rollback transport detail " + rollbackSentinel,
                                "08006");
                    }
                    if (method.getName().equals("setAutoCommit")
                            && arguments != null
                            && arguments.length == 1
                            && Boolean.TRUE.equals(arguments[0])
                            && insertExecuted.get()) {
                        autoCommitRestoreAttempted.set(true);
                    }
                    if (method.getName().equals("abort")) {
                        abortCalled.set(true);
                    }
                    return invoke(delegate, method, arguments);
                });
    }

    private static PreparedStatement postInsertFailureStatement(
            PreparedStatement delegate, String sentinel, AtomicBoolean insertExecuted) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("executeUpdate")
                            && (arguments == null || arguments.length == 0)) {
                        Object result = invoke(delegate, method, arguments);
                        insertExecuted.set(true);
                        throw new SQLException(
                                "synthetic post-insert transport detail " + sentinel,
                                "08006");
                    }
                    return invoke(delegate, method, arguments);
                });
    }

    private static Connection rollbackFailingConnection(Connection delegate, String sentinel) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("rollback")
                            && (arguments == null || arguments.length == 0)) {
                        throw new SQLException("synthetic rollback detail " + sentinel, "08006");
                    }
                    return invoke(delegate, method, arguments);
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments)
            throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static void assertThrowableDoesNotContain(Throwable failure, String sentinel) {
        assertFalse(String.valueOf(failure.getMessage()).contains(sentinel),
                "异常 message 不得包含敏感 sentinel");
        if (failure.getCause() != null) {
            assertThrowableDoesNotContain(failure.getCause(), sentinel);
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            assertThrowableDoesNotContain(suppressed, sentinel);
        }
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(sentinel),
                "完整异常堆栈不得包含敏感 sentinel");
    }

    private void insertMeeting(String meetingId) throws SQLException {
        try (Connection connection = schemaDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO meeting (
                        meeting_id, deployment_scope, client_session_id, subject_scope,
                        profile_id, profile_revision, state, created_at, updated_at)
                    VALUES ('meeting-event-log', 'reference', 'session-event-log', 'reference-dev',
                            'default', 1, 'CREATED', TIMESTAMP WITH TIME ZONE '2026-09-20 08:00:00+00',
                            TIMESTAMP WITH TIME ZONE '2026-09-20 08:00:00+00')
                    """);
        }
    }

    private void insertHistoricalEvent() throws SQLException {
        try (Connection connection = schemaDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO meeting_event (
                        meeting_id, event_sequence, event_id, event_type, payload, occurred_at)
                    VALUES ('meeting-event-log', 1, 'event-history', 'TRANSCRIPT', '{}',
                            TIMESTAMP WITH TIME ZONE '2026-09-20 08:00:00+00')
                    """);
        }
    }

    private void insertSecondMeeting() throws SQLException {
        try (Connection connection = schemaDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO meeting (
                        meeting_id, deployment_scope, client_session_id, subject_scope,
                        profile_id, profile_revision, state, created_at, updated_at)
                    VALUES ('meeting-event-log-2', 'reference', 'session-event-log-2', 'reference-dev',
                            'default', 1, 'CREATED', TIMESTAMP WITH TIME ZONE '2026-09-20 08:00:00+00',
                            TIMESTAMP WITH TIME ZONE '2026-09-20 08:00:00+00')
                    """);
        }
    }

    private void applyMigration(String filename) throws Exception {
        String sql = Files.readString(findMigration(filename), StandardCharsets.UTF_8);
        try (Connection connection = schemaDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Path findMigration(String filename) {
        Path[] candidates = {
                Path.of("..", "deploy", "migrations", filename),
                Path.of("deploy", "migrations", filename),
                Path.of("..", "..", "deploy", "migrations", filename)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到迁移文件：" + filename);
    }

    private static PGSimpleDataSource dataSource(String currentSchema) {
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

    private static String quotedIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
