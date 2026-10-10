package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.*;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicy;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicyConfig;
import com.czur.reference.application.port.*;
import com.czur.reference.application.port.entitlement.*;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.json.CreateMeetingRequestFactJson;
import com.czur.reference.protocol.json.CreateMeetingResponseFactJson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A 作者的原生 MySQL 5.7 Create/usage/Stop 门禁，不是 HTTP 或真实模型验收。
 * 每例新建 ai_trans_m2_a_ 随机库；仅删除本例创建的库，M1及主控现场完全保留。
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_MYSQL_DATABASE_URL", matches = ".+")
class JdbcMysqlCreationAndStopIntegrationTest {
    private static final JdbcDatabaseEngine MYSQL = JdbcDatabaseEngine.MYSQL;
    private static final String DEPLOYMENT = "m2-a部署";
    private static final String SUBJECT = "主体😀 A ";
    private static final String KEY = "create-key-123456789 ";
    private static final Instant RESERVED_AT = Instant.parse("2030-01-01T00:00:00.940612929Z");
    private static final Instant CREATED_AT = Instant.parse("2030-01-01T00:00:10.123456Z");
    private static final Set<String> CAPABILITIES = Set.of("transcript.realtime");
    private String databaseName;
    private JdbcDatabaseContext database;

    @BeforeEach
    void initializeOnlyNewTaskDatabase() throws Exception {
        databaseName = "ai_trans_m2_a_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = admin(); var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + databaseName + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        try (var c = DriverManager.getConnection(scopedUrl(), user(), password()); var s = c.createStatement()) {
            s.execute("SET NAMES utf8mb4");
            s.execute("CREATE TABLE legacy_business_sentinel (id BIGINT PRIMARY KEY, body VARCHAR(80)) ENGINE=InnoDB");
            s.execute("INSERT INTO legacy_business_sentinel VALUES (1, '原业务哨兵😀')");
        }
        Path script = List.of(Path.of("..", "deploy", "migrate-mysql.sh"), Path.of("deploy", "migrate-mysql.sh"))
                .stream().filter(Files::isRegularFile).findFirst().orElseThrow().toAbsolutePath();
        ProcessBuilder builder = new ProcessBuilder("bash", script.toString()).redirectErrorStream(true);
        builder.environment().put("DATABASE_URL", scopedUrl());
        builder.environment().put("DATABASE_USER", user());
        builder.environment().put("DATABASE_PASSWORD", password());
        builder.environment().remove("MIGRATION_DIR");
        Process process = builder.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "独立库初始化超时");
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        database = JdbcDatabaseContext.start(DatabaseRuntimeConfig.of(scopedUrl(), user(), password(),
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(20), 8, 0));
    }

    @AfterEach
    void preserveSentinelAndCleanOnlyOwnDatabase() throws Exception {
        if (database != null) {
            try (var c = connection(); var s = c.createStatement();
                 var rows = s.executeQuery("SELECT body FROM legacy_business_sentinel WHERE id=1")) {
                assertTrue(rows.next());
                assertEquals("原业务哨兵😀", rows.getString(1));
                assertFalse(rows.next());
            } finally {
                database.close();
            }
        }
        if (databaseName != null) {
            assertTrue(databaseName.matches("ai_trans_m2_a_[0-9a-f]{32}"));
            try (var c = admin(); var s = c.createStatement()) {
                s.execute("DROP DATABASE " + databaseName);
            }
        }
    }

    @Test
    void createAndReadOnlyReplayPreserveExactCanonicalAndUtcMicros() throws Exception {
        var reserve = reserve("first", "mtg_first");
        var initial = request(reserve, " 中文😀 title   ", "original-token");
        var first = success(unitOfWork().create(initial));
        assertEquals(CompleteMeetingCreationUnitOfWork.SuccessCode.CREATED, first.code());
        var replay = success(unitOfWork().create(request(reserve, " 中文😀 title   ", "different-token")));
        assertEquals(CompleteMeetingCreationUnitOfWork.SuccessCode.REPLAYED, replay.code());
        assertEquals(CreateMeetingResponseFactJson.encode(first.response()), CreateMeetingResponseFactJson.encode(replay.response()));
        var lookup = new JdbcCompleteMeetingCreateReplayLookup(database);
        var found = assertInstanceOf(CompleteMeetingCreateReplayLookup.Replayed.class,
                lookup.lookup(new CompleteMeetingCreateReplayLookup.Request(DEPLOYMENT, SUBJECT, KEY,
                        initial.requestFact().canonicalRequestDigest())));
        assertEquals(CreateMeetingResponseFactJson.encode(first.response()),
                CreateMeetingResponseFactJson.encode(found.response()));
        var usage = new JdbcUsageReserveLookup(database).lookup(new UsageReserveLookup.Request(
                DEPLOYMENT, SUBJECT, reserve.idempotencyKey(), reserve.requestDigest()));
        assertEquals(reserve, assertInstanceOf(UsageReserveLookup.Replayed.class, usage).result());
        try (var c = connection(); var s = c.createStatement()) {
            assertCreatedOnce(c);
            try (var rows = s.executeQuery("SELECT request_json, response_json, response_sha256, created_at FROM ai_trans_meeting_create_fact")) {
                assertTrue(rows.next());
                assertEquals(CreateMeetingRequestFactJson.encode(initial.requestFact()), rows.getString("request_json"));
                String responseJson = CreateMeetingResponseFactJson.encode(first.response());
                assertEquals(responseJson, rows.getString("response_json"));
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(responseJson.getBytes(StandardCharsets.UTF_8)), rows.getBytes("response_sha256"));
                assertEquals(CREATED_AT, JdbcValues.instant(rows, "created_at"));
            }
        }
        var meeting = new JdbcMeetingRepository(database).find(reserve.reservation().expectedMeetingId()).orElseThrow();
        assertEquals(CREATED_AT, meeting.createdAt());
        assertEquals(SUBJECT, meeting.subjectScope());
    }

    @Test
    void concurrentSameKeyCreatesOnlyOneMeetingBindingFactAndOutbox() throws Exception {
        var reserve = reserve("concurrent", "mtg_concurrent");
        var results = concurrent(6, () -> unitOfWork().create(request(reserve, "竞争😀", "same-token")));
        assertEquals(1, results.stream().map(JdbcMysqlCreationAndStopIntegrationTest::success)
                .filter(r -> r.code() == CompleteMeetingCreationUnitOfWork.SuccessCode.CREATED).count());
        String stable = CreateMeetingResponseFactJson.encode(success(results.getFirst()).response());
        for (var result : results) {
            assertEquals(stable, CreateMeetingResponseFactJson.encode(success(result).response()));
        }
        try (var c = connection()) { assertCreatedOnce(c); }
    }

    @Test
    void necessaryOutboxConflictRollsBackCandidateMeetingBindingAndCreateFact() throws Exception {
        var reserve = reserve("rollback", "mtg_rollback");
        var initial = request(reserve, null, "token");
        var snapshot = MeetingAggregate.owned("mtg_rollback", DEPLOYMENT, "session-rollback", SUBJECT,
                reserve.reservation().grantId(), reserve.grantExpiresAt(), reserve.reservation().reservationId(),
                "standard", 1, CREATED_AT).snapshot();
        var expected = MeetingCommandEventFactory.meetingCreated(snapshot, CREATED_AT);
        var wrong = new OutboxStore.OutboxEvent(expected.eventId(), expected.aggregateType(), expected.aggregateId(),
                expected.eventType(), "{\"different\":true}", expected.occurredAt(), expected.availableAt(),
                expected.status(), expected.attemptCount(), null, null, null, null);
        assertEquals(OutboxStore.AppendStatus.APPENDED, new JdbcOutboxStore(database).append(wrong).status());
        var failure = assertInstanceOf(CompleteMeetingCreationUnitOfWork.Failure.class, unitOfWork().create(initial));
        assertEquals(CompleteMeetingCreationUnitOfWork.ErrorCode.INTERNAL, failure.code());
        try (var c = connection()) {
            assertEquals(0, count(c, "ai_trans_meeting"));
            assertEquals(0, count(c, "ai_trans_meeting_create_fact"));
            assertEquals(1, count(c, "ai_trans_outbox_event"));
            try (var s = c.createStatement(); var rows = s.executeQuery("SELECT bound_meeting_id, version FROM ai_trans_usage_reservation")) {
                assertTrue(rows.next()); assertNull(rows.getBytes(1)); assertEquals(0, rows.getLong(2));
            }
        }
    }

    @Test
    void concurrentCommitReplaysStableWinnerAfterReservationLockWait() throws Exception {
        var reserve = reserve("usage", "mtg_usage");
        success(unitOfWork().create(request(reserve, null, "token")));
        var provider = provider("not-used", CREATED_AT.plusSeconds(1));
        var request = new CommitUsageRequest(DEPLOYMENT, SUBJECT, UsageOperationScopes.COMMIT,
                "commit-key-123456789", "commit-digest", reserve.reservation().reservationId(), 1,
                new UsageActual(UsageUnit.MEETING_DURATION_MS, 123));
        var results = concurrent(6, () -> provider.commitUsage(request));
        var first = results.getFirst().valueOrThrow();
        for (var result : results) { assertEquals(first, result.valueOrThrow()); }
        assertEquals(UsageReservation.State.COMMITTED, first.reservation().state());
        assertEquals(2, first.reservation().version());
        try (var c = connection()) { assertEquals(2, count(c, "ai_trans_usage_operation")); }
    }

    private JdbcCompleteMeetingCreationUnitOfWork unitOfWork() {
        return new JdbcCompleteMeetingCreationUnitOfWork(database, Clock.fixed(CREATED_AT, ZoneOffset.UTC));
    }

    private UsageOperationResult reserve(String suffix, String meetingId) {
        var provider = provider(suffix, RESERVED_AT);
        var estimate = new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000);
        var grant = provider.authorizeStart(new AuthorizeStartRequest(DEPLOYMENT, new Subject(SUBJECT),
                "standard", 1, CAPABILITIES, estimate, "session-" + suffix)).valueOrThrow();
        var request = new ReserveUsageRequest(DEPLOYMENT, SUBJECT, UsageOperationScopes.RESERVE,
                "reserve-key-" + suffix, "reserve-digest-" + suffix, grant, estimate, meetingId);
        var first = provider.reserveUsage(request).valueOrThrow();
        assertEquals(first, provider.reserveUsage(request).valueOrThrow());
        assertEquals(RESERVED_AT, first.reservation().createdAt());
        return first;
    }

    private JdbcReferenceDevEntitlementProvider provider(String suffix, Instant at) {
        Clock clock = Clock.fixed(at, ZoneOffset.UTC);
        var policy = new ReferenceDevEntitlementPolicy(new ReferenceDevEntitlementPolicyConfig(
                DEPLOYMENT, "standard", 1, CAPABILITIES, 3_600_000,
                Duration.ofSeconds(120), Duration.ofSeconds(90)), clock,
                new EntitlementIdGenerator() {
                    public String newGrantId() { return "grant-" + suffix; }
                    public String newReservationId() { return "reservation-" + suffix; }
                    public String newLeaseToken() { return "unused"; }
                });
        return new JdbcReferenceDevEntitlementProvider(database, policy, clock);
    }

    private static CompleteMeetingCreationUnitOfWork.CreateRequest request(UsageOperationResult reserve, String title, String token) {
        String suffix = reserve.reservation().reservationId().substring("reservation-".length());
        var snapshot = new CreateMeetingRequestSnapshot("session-" + suffix,
                new CreateMeetingRequestSnapshot.ProfileRef("standard", 1),
                new CreateMeetingRequestSnapshot.ClientDescriptor("app", "1", "v1", "device"),
                title, "Asia/Shanghai", new CreateMeetingRequestSnapshot.LanguageSelection("zh-CN", List.of()),
                AudioProfile.pcm16kMono(100), CAPABILITIES,
                new CreateMeetingRequestSnapshot.ArtifactPolicy(false, false), KEY);
        var request = new CreateMeetingRequestFact(snapshot, MeetingRequestCanonicalizer.digest(snapshot), 1);
        var response = new CreateMeetingResponseFact(reserve.reservation().expectedMeetingId(), MeetingState.CREATED,
                snapshot.profile(), CAPABILITIES, List.of(), AudioProfile.pcm16kMono(100),
                new CreateMeetingResponseFact.RealtimeSession(URI.create("wss://example.test/realtime"),
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, token, CREATED_AT.plusSeconds(120)),
                new CreateMeetingResponseFact.Limits(3_600_000, 64_000, 200, null));
        return new CompleteMeetingCreationUnitOfWork.CreateRequest(DEPLOYMENT, SUBJECT, request, response, reserve);
    }

    private static CompleteMeetingCreationUnitOfWork.Success success(CompleteMeetingCreationUnitOfWork.Result result) {
        return assertInstanceOf(CompleteMeetingCreationUnitOfWork.Success.class, result);
    }

    private static <T> List<T> concurrent(int total, Callable<T> action) throws Exception {
        try (var executor = Executors.newFixedThreadPool(total)) {
            var start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < total; i++) { futures.add(executor.submit(() -> { start.await(); return action.call(); })); }
            start.countDown();
            List<T> result = new ArrayList<>();
            for (var future : futures) { result.add(future.get(20, TimeUnit.SECONDS)); }
            return result;
        }
    }

    private static void assertCreatedOnce(Connection c) throws Exception {
        for (String table : List.of("ai_trans_meeting", "ai_trans_usage_reservation", "ai_trans_meeting_create_fact", "ai_trans_outbox_event")) {
            assertEquals(1, count(c, table));
        }
        try (var s = c.createStatement(); var rows = s.executeQuery("SELECT bound_meeting_id, bound_at, created_at, version FROM ai_trans_usage_reservation")) {
            assertTrue(rows.next()); assertNotNull(rows.getBytes("bound_meeting_id"));
            assertEquals(CREATED_AT, JdbcValues.instant(rows, "bound_at"));
            assertEquals(RESERVED_AT.truncatedTo(ChronoUnit.MICROS), JdbcValues.instant(rows, "created_at"));
            assertEquals(1, rows.getLong("version"));
        }
    }

    private static long count(Connection c, String table) throws Exception {
        try (var s = c.createStatement(); var rows = s.executeQuery("SELECT COUNT(*) FROM " + table)) { rows.next(); return rows.getLong(1); }
    }
    private Connection connection() throws Exception { return database.dataSource().getConnection(); }
    private static Connection admin() throws Exception { return DriverManager.getConnection(required("REFERENCE_TEST_MYSQL_DATABASE_URL"), user(), password()); }
    private static String user() { return required("REFERENCE_TEST_MYSQL_DATABASE_USER"); }
    private static String password() { return required("REFERENCE_TEST_MYSQL_DATABASE_PASSWORD"); }
    private static String required(String name) { return Objects.requireNonNull(System.getenv(name), name + " required"); }
    private String scopedUrl() {
        String url = required("REFERENCE_TEST_MYSQL_DATABASE_URL");
        int query = url.indexOf('?');
        String base = query < 0 ? url : url.substring(0, query);
        return base.substring(0, base.lastIndexOf('/') + 1) + databaseName;
    }
}
