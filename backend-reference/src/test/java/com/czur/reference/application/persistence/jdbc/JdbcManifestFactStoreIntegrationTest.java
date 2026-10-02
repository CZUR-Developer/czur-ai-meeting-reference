package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.ManifestFactStore;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.json.MeetingManifestJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcManifestFactStore} 在 PostgreSQL 16 上的事务、并发和坏数据门禁。
 *
 * <p>每个用例使用独立随机 schema 并执行 V001～V007。测试只通过 package-private
 * connection-scoped 原语读写 Manifest；事务开始、提交、回滚和连接关闭始终由测试
 * 扮演的后续发布工作单元负责。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcManifestFactStoreIntegrationTest {
    private static final List<String> MIGRATIONS = List.of(
            "V001__initial_schema.sql",
            "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql",
            "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql",
            "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql");
    private static final Instant START = Instant.parse("2026-09-21T10:00:00Z");
    private static final Instant END = Instant.parse("2026-09-21T10:05:00Z");

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createFreshSchema() throws Exception {
        adminDataSource = postgres(null);
        schema = "manifest_store_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        dataSource = postgres(schema);
        for (String migration : MIGRATIONS) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(readMigration(migration));
            }
        }
    }

    @AfterEach
    void dropFreshSchema() throws Exception {
        if (adminDataSource == null || schema == null) {
            return;
        }
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS "
                    + quoteIdentifier(schema) + " CASCADE");
        }
    }

    @Test
    void emptyLatestThenRevisionOneAndTwoRoundTripExactly() throws Exception {
        String meetingId = "mtg_manifest_round_trip";
        createMeeting(meetingId);
        assertEquals(Optional.empty(), inTransaction(
                store -> store.findLatest(meetingId)));

        MeetingManifest revisionOne = manifest(meetingId, 1, "第一版", START, END, List.of());
        MeetingManifest revisionTwo = manifest(
                meetingId, 2, "第二版", START, END.plusNanos(123_456_789), List.of());
        assertEquals(ManifestFactStore.AppendStatus.APPENDED,
                appendCommitted(revisionOne, new ManifestFactStore.ExpectedPreviousRevision.None()));
        assertEquals(revisionOne, inTransaction(store -> store.findLatest(meetingId)).orElseThrow());
        assertEquals(ManifestFactStore.AppendStatus.APPENDED,
                appendCommitted(revisionTwo,
                        new ManifestFactStore.ExpectedPreviousRevision.Present(1)));
        assertEquals(revisionTwo, inTransaction(store -> store.findLatest(meetingId)).orElseThrow());

        String canonical = MeetingManifestJson.encode(revisionTwo);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT canonical_json, manifest_json::text, content_sha256
                       FROM meeting_manifest_revision
                      WHERE meeting_id=? AND manifest_revision=2
                     """)) {
            statement.setString(1, meetingId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(canonical, row.getString(1));
                assertEquals(revisionTwo, MeetingManifestJson.decode(row.getString(2)));
                assertArrayEquals(sha256(canonical), row.getBytes(3));
                assertFalse(row.next());
            }
        }
    }

    @Test
    void targetRevisionReplayAndContentConflictPrecedeStaleLatestCheck() throws Exception {
        String meetingId = "mtg_target_precedence";
        createMeeting(meetingId);
        MeetingManifest revisionOne = manifest(meetingId, 1, "r1", START, END, List.of());
        MeetingManifest revisionTwo = manifest(meetingId, 2, "r2", START, END, List.of());
        MeetingManifest revisionThree = manifest(meetingId, 3, "r3", START, END, List.of());
        appendCommitted(revisionOne, new ManifestFactStore.ExpectedPreviousRevision.None());
        appendCommitted(revisionTwo, new ManifestFactStore.ExpectedPreviousRevision.Present(1));
        appendCommitted(revisionThree, new ManifestFactStore.ExpectedPreviousRevision.Present(2));

        assertEquals(ManifestFactStore.AppendStatus.REPLAYED,
                appendCommitted(revisionTwo,
                        new ManifestFactStore.ExpectedPreviousRevision.Present(1)));
        assertEquals(ManifestFactStore.AppendStatus.CONTENT_CONFLICT,
                appendCommitted(manifest(meetingId, 2, "另一份 r2", START, END, List.of()),
                        new ManifestFactStore.ExpectedPreviousRevision.Present(1)));
        assertEquals(revisionThree,
                inTransaction(store -> store.findLatest(meetingId)).orElseThrow());
    }

    @Test
    void noneTargetUsesReplayRulesAndMissingPresentPreviousIsVersionConflict() throws Exception {
        String meetingId = "mtg_expected_previous";
        createMeeting(meetingId);
        MeetingManifest revisionOne = manifest(meetingId, 1, "root", START, END, List.of());
        MeetingManifest revisionTwo = manifest(meetingId, 2, "next", START, END, List.of());

        assertEquals(ManifestFactStore.AppendStatus.VERSION_CONFLICT,
                appendCommitted(revisionTwo,
                        new ManifestFactStore.ExpectedPreviousRevision.Present(1)));
        assertEquals(ManifestFactStore.AppendStatus.APPENDED,
                appendCommitted(revisionOne,
                        new ManifestFactStore.ExpectedPreviousRevision.None()));
        assertEquals(ManifestFactStore.AppendStatus.REPLAYED,
                appendCommitted(revisionOne,
                        new ManifestFactStore.ExpectedPreviousRevision.None()));
        assertEquals(ManifestFactStore.AppendStatus.CONTENT_CONFLICT,
                appendCommitted(manifest(meetingId, 1, "other root", START, END, List.of()),
                        new ManifestFactStore.ExpectedPreviousRevision.None()));
        assertEquals(1L, revisionCount(meetingId));
    }

    @Test
    void concurrentSameContentHasOneAppendAndOneReplay() throws Exception {
        String meetingId = "mtg_concurrent_replay";
        createMeeting(meetingId);
        MeetingManifest candidate = manifest(meetingId, 1, "same", START, END, List.of());

        List<ManifestFactStore.AppendStatus> statuses = concurrentAppend(candidate, candidate);
        assertEquals(1, statuses.stream()
                .filter(status -> status == ManifestFactStore.AppendStatus.APPENDED).count());
        assertEquals(1, statuses.stream()
                .filter(status -> status == ManifestFactStore.AppendStatus.REPLAYED).count());
        assertEquals(1L, revisionCount(meetingId));
    }

    @Test
    void concurrentDifferentContentHasOneAppendAndOneContentConflict() throws Exception {
        String meetingId = "mtg_concurrent_conflict";
        createMeeting(meetingId);
        MeetingManifest candidateA = manifest(meetingId, 1, "candidate-a", START, END, List.of());
        MeetingManifest candidateB = manifest(meetingId, 1, "candidate-b", START, END, List.of());

        List<ManifestFactStore.AppendStatus> statuses = concurrentAppend(candidateA, candidateB);
        assertEquals(1, statuses.stream()
                .filter(status -> status == ManifestFactStore.AppendStatus.APPENDED).count());
        assertEquals(1, statuses.stream()
                .filter(status -> status == ManifestFactStore.AppendStatus.CONTENT_CONFLICT).count());
        assertEquals(1L, revisionCount(meetingId));
    }

    @Test
    void uncommittedAppendForOneMeetingDoesNotBlockAnotherMeeting() throws Exception {
        String firstMeeting = "mtg_no_global_lock_a";
        String secondMeeting = "mtg_no_global_lock_b";
        createMeeting(firstMeeting);
        createMeeting(secondMeeting);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection firstConnection = dataSource.getConnection()) {
            firstConnection.setAutoCommit(false);
            JdbcManifestFactStore firstStore = new JdbcManifestFactStore(firstConnection);
            assertEquals(ManifestFactStore.AppendStatus.APPENDED,
                    firstStore.append(new ManifestFactStore.AppendRequest(
                            manifest(firstMeeting, 1, "pending", START, END, List.of()),
                            new ManifestFactStore.ExpectedPreviousRevision.None())).status());

            Future<ManifestFactStore.AppendStatus> second = executor.submit(() ->
                    appendCommitted(
                            manifest(secondMeeting, 1, "independent", START, END, List.of()),
                            new ManifestFactStore.ExpectedPreviousRevision.None()));
            assertEquals(ManifestFactStore.AppendStatus.APPENDED,
                    second.get(5, TimeUnit.SECONDS),
                    "不同 meeting 不得等待第一个 meeting 的未提交 Manifest");
            firstConnection.rollback();
        } finally {
            executor.shutdownNow();
        }
        assertEquals(0L, revisionCount(firstMeeting));
        assertEquals(1L, revisionCount(secondMeeting));
    }

    @Test
    void unicodeLargeManifestAndNanosecondTimesRoundTripWithoutNormalization() throws Exception {
        String meetingId = "mtg_unicode_large";
        createMeeting(meetingId);
        List<MeetingManifest.Warning> warnings = new ArrayList<>();
        for (int index = 0; index < 192; index++) {
            warnings.add(new MeetingManifest.Warning(
                    "WARN_" + index,
                    "summary.section." + index,
                    "会议大正文😀".repeat(80),
                    "diag_" + index));
        }
        Instant nanosStart = Instant.parse("2026-09-21T10:00:00.123456789Z");
        Instant nanosEnd = Instant.parse("2026-09-21T10:05:00.987654321Z");
        MeetingManifest large = manifest(
                meetingId,
                1,
                "中文😀标题".repeat(30),
                nanosStart,
                nanosEnd,
                warnings);
        assertTrue(MeetingManifestJson.encode(large).length() > 100_000);

        assertEquals(ManifestFactStore.AppendStatus.APPENDED,
                appendCommitted(large, new ManifestFactStore.ExpectedPreviousRevision.None()));
        assertEquals(large, inTransaction(store -> store.findLatest(meetingId)).orElseThrow());
        assertEquals(nanosStart,
                inTransaction(store -> store.findLatest(meetingId)).orElseThrow().startedAt());
        assertEquals(nanosEnd,
                inTransaction(store -> store.findLatest(meetingId)).orElseThrow().endedAt());
    }

    @Test
    void callerRetainsCommitRollbackAutoCommitAndCloseOwnership() throws Exception {
        String meetingId = "mtg_connection_owner";
        createMeeting(meetingId);
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        AtomicInteger autoCommitChanges = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();

        try (Connection delegate = dataSource.getConnection()) {
            delegate.setAutoCommit(false);
            Connection observed = observedConnection(
                    delegate, commits, rollbacks, autoCommitChanges, closes);
            JdbcManifestFactStore store = new JdbcManifestFactStore(observed);
            MeetingManifest candidate = manifest(meetingId, 1, "caller-owned", START, END, List.of());
            assertEquals(ManifestFactStore.AppendStatus.APPENDED,
                    store.append(new ManifestFactStore.AppendRequest(
                            candidate,
                            new ManifestFactStore.ExpectedPreviousRevision.None())).status());
            assertEquals(candidate, store.findLatest(meetingId).orElseThrow());
            assertEquals(0, commits.get());
            assertEquals(0, rollbacks.get());
            assertEquals(0, autoCommitChanges.get());
            assertEquals(0, closes.get());
            delegate.rollback();
        }

        assertEquals(ManifestFactStore.AppendStatus.APPENDED,
                appendCommitted(
                        manifest(meetingId, 1, "caller-owned", START, END, List.of()),
                        new ManifestFactStore.ExpectedPreviousRevision.None()),
                "调用方 rollback 后，存储原语不得留下自行提交的 revision");
    }

    @Test
    void corruptedHashJsonMirrorCanonicalIdentityRevisionAndChainFailClosed()
            throws Exception {
        dropValidationChecksForCorruptionFixtures();

        assertCorruption("mtg_corrupt_hash", "秘密哈希", """
                UPDATE meeting_manifest_revision
                   SET content_sha256=decode(repeat('00', 32), 'hex')
                 WHERE meeting_id=?
                """);
        assertCorruption("mtg_corrupt_json", "秘密镜像", """
                UPDATE meeting_manifest_revision
                   SET manifest_json=manifest_json
                       || '{"unexpectedPrivateField":"秘密正文"}'::jsonb
                 WHERE meeting_id=?
                """);
        assertCorruption("mtg_corrupt_noncanonical", "秘密非规范", """
                UPDATE meeting_manifest_revision
                   SET canonical_json=' ' || canonical_json,
                       content_sha256=sha256(convert_to(' ' || canonical_json, 'UTF8'))
                 WHERE meeting_id=?
                """);
        assertCorruption("mtg_corrupt_identity", "秘密身份", """
                UPDATE meeting_manifest_revision
                   SET canonical_json=replace(canonical_json, ?, 'mtg_other_identity'),
                       manifest_json=replace(
                           manifest_json::text, ?, 'mtg_other_identity')::jsonb,
                       content_sha256=sha256(convert_to(
                           replace(canonical_json, ?, 'mtg_other_identity'), 'UTF8'))
                 WHERE meeting_id=?
                """, "mtg_corrupt_identity", "mtg_corrupt_identity",
                "mtg_corrupt_identity", "mtg_corrupt_identity");
        assertCorruption("mtg_corrupt_revision", "秘密版本", """
                UPDATE meeting_manifest_revision
                   SET canonical_json=replace(
                           canonical_json, '"manifestRevision":1', '"manifestRevision":2'),
                       manifest_json=jsonb_set(manifest_json, '{manifestRevision}', '2'::jsonb),
                       content_sha256=sha256(convert_to(replace(
                           canonical_json, '"manifestRevision":1', '"manifestRevision":2'),
                           'UTF8'))
                 WHERE meeting_id=?
                """);
        assertCorruption("mtg_corrupt_chain", "秘密链", """
                UPDATE meeting_manifest_revision
                   SET previous_revision=1
                 WHERE meeting_id=?
                """);
    }

    private List<ManifestFactStore.AppendStatus> concurrentAppend(
            MeetingManifest first, MeetingManifest second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<ManifestFactStore.AppendStatus> firstTask = () ->
                    appendAfterBarrier(first, barrier);
            Callable<ManifestFactStore.AppendStatus> secondTask = () ->
                    appendAfterBarrier(second, barrier);
            Future<ManifestFactStore.AppendStatus> firstFuture = executor.submit(firstTask);
            Future<ManifestFactStore.AppendStatus> secondFuture = executor.submit(secondTask);
            return List.of(
                    firstFuture.get(10, TimeUnit.SECONDS),
                    secondFuture.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private ManifestFactStore.AppendStatus appendAfterBarrier(
            MeetingManifest manifest, CyclicBarrier barrier) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            barrier.await(5, TimeUnit.SECONDS);
            ManifestFactStore.AppendStatus status = new JdbcManifestFactStore(connection)
                    .append(new ManifestFactStore.AppendRequest(
                            manifest,
                            new ManifestFactStore.ExpectedPreviousRevision.None()))
                    .status();
            connection.commit();
            return status;
        }
    }

    private ManifestFactStore.AppendStatus appendCommitted(
            MeetingManifest manifest,
            ManifestFactStore.ExpectedPreviousRevision previous) throws Exception {
        return inTransaction(store -> store.append(
                new ManifestFactStore.AppendRequest(manifest, previous)).status());
    }

    private <T> T inTransaction(Function<JdbcManifestFactStore, T> action) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = action.apply(new JdbcManifestFactStore(connection));
                connection.commit();
                return result;
            } catch (RuntimeException | Error failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void assertCorruption(String meetingId, String sensitiveTitle, String sql,
                                  Object... parameters) throws Exception {
        createMeeting(meetingId);
        MeetingManifest manifest = manifest(
                meetingId, 1, sensitiveTitle, START, END, List.of());
        appendCommitted(manifest, new ManifestFactStore.ExpectedPreviousRevision.None());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameters.length == 0) {
                statement.setString(1, meetingId);
            } else {
                for (int index = 0; index < parameters.length; index++) {
                    statement.setObject(index + 1, parameters[index]);
                }
            }
            assertEquals(1, statement.executeUpdate());
        }

        JdbcManifestFactStore.StoredFactException failure = assertThrows(
                JdbcManifestFactStore.StoredFactException.class,
                () -> inTransaction(store -> store.findLatest(meetingId)));
        assertEquals(JdbcManifestFactStore.FailureKind.CORRUPT_DATA, failure.kind());
        assertEquals("stored Manifest fact is invalid", failure.getMessage());
        assertFalse(failure.getMessage().contains(meetingId));
        assertFalse(failure.getMessage().contains(sensitiveTitle));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    private void dropValidationChecksForCorruptionFixtures() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE meeting_manifest_revision
                    DROP CONSTRAINT meeting_manifest_revision_document_ck,
                    DROP CONSTRAINT meeting_manifest_revision_hash_ck,
                    DROP CONSTRAINT meeting_manifest_revision_chain_ck
                    """);
        }
    }

    private long revisionCount(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*) FROM meeting_manifest_revision WHERE meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getLong(1);
            }
        }
    }

    private void createMeeting(String meetingId) {
        new JdbcMeetingRepository(dataSource).save(
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        meetingId,
                        "manifest-store-test-deployment",
                        "session-" + meetingId,
                        "profile",
                        1,
                        MeetingState.CREATED,
                        START,
                        START));
    }

    private static MeetingManifest manifest(
            String meetingId,
            int revision,
            String title,
            Instant startedAt,
            Instant endedAt,
            List<MeetingManifest.Warning> warnings) {
        return new MeetingManifest(
                "0.1",
                meetingId,
                revision,
                new MeetingManifest.Profile("meeting-minutes/v1", 1),
                title,
                warnings.isEmpty() ? ManifestStatus.READY : ManifestStatus.READY_WITH_WARNINGS,
                startedAt,
                endedAt,
                Math.max(0L, endedAt.toEpochMilli() - startedAt.toEpochMilli()),
                new MeetingManifest.Languages("zh-CN", List.of("en-US")),
                List.of(),
                warnings);
    }

    private static Connection observedConnection(
            Connection delegate,
            AtomicInteger commits,
            AtomicInteger rollbacks,
            AtomicInteger autoCommitChanges,
            AtomicInteger closes) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "commit" -> commits.incrementAndGet();
                        case "rollback" -> rollbacks.incrementAndGet();
                        case "setAutoCommit" -> autoCommitChanges.incrementAndGet();
                        case "close" -> closes.incrementAndGet();
                        default -> {
                            // 其它方法透明委托，保留真实 PostgreSQL 行为。
                        }
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
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
