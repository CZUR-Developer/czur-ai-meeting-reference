package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.CreateMeetingRequestFact;
import com.czur.reference.application.command.CreateMeetingRequestSnapshot;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.command.MeetingCommandEventFactory;
import com.czur.reference.application.command.MeetingRequestCanonicalizer;
import com.czur.reference.application.port.CompleteMeetingCreationUnitOfWork;
import com.czur.reference.application.port.CompleteMeetingCreateReplayLookup;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完整 Create 的 PostgreSQL 原子性门禁。每例只操作独立 schema；连接地址须由
 * 调用者提供随机端口的一次性 PostgreSQL 16，不接触共享测试库。
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcCompleteMeetingCreationUnitOfWorkIntegrationTest {
    private static final String DEPLOYMENT = "complete-create-test";
    private static final String SUBJECT = "user-1";
    private static final String KEY = "create-key-123456789";
    private static final Instant RESERVED_AT = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2030-01-01T00:00:10.123456Z");
    private static final Set<String> CAPABILITIES = Set.of("transcript.realtime");

    private PGSimpleDataSource admin;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        admin = postgres(null);
        schema = "complete_create_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        dataSource = postgres(schema);
        for (String migration : List.of("V001__initial_schema.sql",
                "V002__outbox_lease_fencing.sql", "V003__identity_entitlement.sql",
                "V013__meeting_create_fact.sql")) {
            apply(migration);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (admin != null && schema != null) {
            try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + quote(schema) + " CASCADE");
            }
        }
    }

    @Test
    void firstCreatePersistsAllFactsAndReplayUsesOriginalResponse() throws Exception {
        UsageOperationResult reserve = insertReserve("first", "mtg_complete_first");
        var initial = request(reserve, KEY, null, "first-token");
        var first = success(unitOfWork(CREATED_AT).create(initial));
        assertEquals(CompleteMeetingCreationUnitOfWork.SuccessCode.CREATED, first.code());
        assertEquals("first-token", first.response().realtime().sessionToken());

        // 重试端传入不同的新候选 Token 和较晚时钟；响应必须取 V013 原事实。
        var replay = success(unitOfWork(CREATED_AT.plusSeconds(20)).create(
                request(reserve, KEY, null, "new-token-must-not-win")));
        assertEquals(CompleteMeetingCreationUnitOfWork.SuccessCode.REPLAYED, replay.code());
        assertEquals("first-token", replay.response().realtime().sessionToken());

        String scope = IdempotencyScopeCodec.meetingCreateV2(DEPLOYMENT, SUBJECT);
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM meeting WHERE meeting_id = 'mtg_complete_first'"));
            assertEquals(1, count(connection, "SELECT count(*) FROM meeting_create_fact"));
            assertEquals(0, count(connection, "SELECT count(*) FROM idempotency_record"));
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM outbox_event WHERE aggregate_id = 'mtg_complete_first'"));
            assertEquals(1, count(connection, """
                    SELECT count(*) FROM usage_reservation
                     WHERE reservation_id = 'reservation-first'
                       AND bound_meeting_id = 'mtg_complete_first' AND version = 1
                    """));
            assertEquals(CREATED_AT, instant(connection,
                    "SELECT created_at FROM meeting WHERE meeting_id = 'mtg_complete_first'"));
            assertEquals(CREATED_AT, instant(connection,
                    "SELECT created_at FROM meeting_create_fact WHERE scope = ?", scope));
            assertEquals(CREATED_AT, instant(connection,
                    "SELECT bound_at FROM usage_reservation WHERE reservation_id = 'reservation-first'"));
            assertEquals(CREATED_AT, instant(connection,
                    "SELECT occurred_at FROM outbox_event WHERE aggregate_id = 'mtg_complete_first'"));
        }
        assertFalse(scope.equals(IdempotencyScopeCodec.meetingCreate(DEPLOYMENT, SUBJECT)));
    }

    @Test
    void v2ReadOnlyReplayMissThenReturnsOriginalResponseAndConflictsWithoutDecodingResponse()
            throws Exception {
        var reserve = insertReserve("lookup", "mtg_complete_lookup");
        var first = request(reserve, KEY, null, "original-token");
        var lookup = new JdbcCompleteMeetingCreateReplayLookup(dataSource);
        var firstKey = lookupRequest(first);
        assertEquals(CompleteMeetingCreateReplayLookup.Status.NOT_FOUND,
                lookup.lookup(firstKey).status());
        success(unitOfWork(CREATED_AT).create(first));
        var replay = (CompleteMeetingCreateReplayLookup.Replayed) lookup.lookup(firstKey);
        assertEquals("original-token", replay.response().realtime().sessionToken());
        assertEquals(CompleteMeetingCreateReplayLookup.Status.NOT_FOUND,
                lookup.lookup(new CompleteMeetingCreateReplayLookup.Request(
                        DEPLOYMENT, "another-subject", KEY,
                        first.requestFact().canonicalRequestDigest())).status());

        // 即使旧响应已损坏，异摘要请求仍只能看到冲突，不能触碰响应解码。
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE meeting_create_fact DROP CONSTRAINT meeting_create_fact_response_ck");
            statement.execute("UPDATE meeting_create_fact SET response_sha256 = decode(repeat('0', 64), 'hex')");
        }
        var another = request(reserve, KEY, "different title", "irrelevant-token");
        assertEquals(CompleteMeetingCreateReplayLookup.Status.CONFLICT,
                lookup.lookup(lookupRequest(another)).status());
        assertEquals(CompleteMeetingCreateReplayLookup.Status.INCONSISTENT,
                lookup.lookup(firstKey).status());
    }

    @Test
    void v2ReadOnlyReplayRejectsValidShapeButIncorrectStoredRequestDigest() throws Exception {
        var reserve = insertReserve("lookup-digest", "mtg_complete_lookup_digest");
        var first = request(reserve, KEY, null, "first-token");
        success(unitOfWork(CREATED_AT).create(first));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE meeting_create_fact SET request_digest = repeat('a', 64)");
        }
        var lookup = new JdbcCompleteMeetingCreateReplayLookup(dataSource);
        assertEquals(CompleteMeetingCreateReplayLookup.Status.INCONSISTENT,
                lookup.lookup(lookupRequest(first)).status());
        var another = request(reserve, KEY, "different title", "ignored-token");
        assertEquals(CompleteMeetingCreateReplayLookup.Status.INCONSISTENT,
                lookup.lookup(lookupRequest(another)).status());
    }

    @Test
    void v2ReadOnlyReplayTreatsDatabaseFailureAsUnavailableNotMiss() throws Exception {
        var reserve = insertReserve("lookup-db", "mtg_complete_lookup_db");
        var first = request(reserve, KEY, null, "first-token");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE meeting_create_fact");
        }
        assertEquals(CompleteMeetingCreateReplayLookup.Status.UNAVAILABLE,
                new JdbcCompleteMeetingCreateReplayLookup(dataSource)
                        .lookup(lookupRequest(first)).status());
    }

    @Test
    void differentDigestConflictsWithoutParsingCorruptOldResponse() throws Exception {
        UsageOperationResult reserve = insertReserve("conflict", "mtg_complete_conflict");
        var first = request(reserve, KEY, null, "first-token");
        success(unitOfWork(CREATED_AT).create(first));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE meeting_create_fact DROP CONSTRAINT meeting_create_fact_response_ck");
            statement.execute("UPDATE meeting_create_fact SET response_sha256 = decode(repeat('0', 64), 'hex')");
        }

        var conflict = failure(unitOfWork(CREATED_AT.plusSeconds(1)).create(
                request(reserve, KEY, "different title", "ignored-token")));
        assertEquals(CompleteMeetingCreationUnitOfWork.ErrorCode.IDEMPOTENCY_CONFLICT,
                conflict.code());
        // 同摘要路径必须严格解码并检查旧响应哈希，不能把坏事实当成成功重放。
        var corruptReplay = failure(unitOfWork(CREATED_AT.plusSeconds(1)).create(first));
        assertEquals(CompleteMeetingCreationUnitOfWork.ErrorCode.INTERNAL, corruptReplay.code());
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(1, count(connection, "SELECT count(*) FROM meeting_create_fact"));
            assertEquals(1, count(connection, "SELECT count(*) FROM meeting"));
            assertEquals(1, count(connection, "SELECT count(*) FROM outbox_event"));
        }
    }

    @Test
    void validLookingButWrongStoredRequestDigestFailsClosedBeforeConflict() throws Exception {
        UsageOperationResult reserve = insertReserve("digest-corrupt", "mtg_complete_digest_corrupt");
        var first = request(reserve, KEY, null, "first-token");
        success(unitOfWork(CREATED_AT).create(first));
        // V013 CHECK 只约束摘要形状；探针必须与规范请求 JSON 重算比对。
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE meeting_create_fact SET request_digest = repeat('a', 64)");
        }
        var replay = failure(unitOfWork(CREATED_AT.plusSeconds(1)).create(first));
        var anotherIntent = failure(unitOfWork(CREATED_AT.plusSeconds(1)).create(
                request(reserve, KEY, "different title", "ignored-token")));
        assertEquals(CompleteMeetingCreationUnitOfWork.ErrorCode.INTERNAL, replay.code());
        assertEquals(CompleteMeetingCreationUnitOfWork.ErrorCode.INTERNAL,
                anotherIntent.code());
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(1, count(connection, "SELECT count(*) FROM meeting"));
            assertEquals(1, count(connection, "SELECT count(*) FROM meeting_create_fact"));
        }
    }

    @Test
    void outboxConflictRollsBackBindMeetingAndV013Together() throws Exception {
        UsageOperationResult reserve = insertReserve("rollback", "mtg_complete_rollback");
        MeetingAggregate.MeetingSnapshot meeting = MeetingAggregate.owned(
                "mtg_complete_rollback", DEPLOYMENT, "session-rollback", SUBJECT,
                reserve.reservation().grantId(), reserve.grantExpiresAt(),
                reserve.reservation().reservationId(), "standard", 1, CREATED_AT).snapshot();
        String eventId = MeetingCommandEventFactory.meetingCreated(meeting, CREATED_AT).eventId();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement =
                     connection.prepareStatement("""
                         INSERT INTO outbox_event (event_id, aggregate_type, aggregate_id,
                             event_type, payload, occurred_at, available_at, status, attempt_count)
                         VALUES (?, 'Meeting', 'mtg_complete_rollback', 'MeetingCreated',
                             '{"different":true}'::jsonb, ?, ?, 'PENDING', 0)
                         """)) {
            statement.setString(1, eventId);
            statement.setTimestamp(2, Timestamp.from(CREATED_AT));
            statement.setTimestamp(3, Timestamp.from(CREATED_AT));
            statement.executeUpdate();
        }

        var failed = failure(unitOfWork(CREATED_AT).create(
                request(reserve, KEY, null, "first-token")));
        assertEquals(CompleteMeetingCreationUnitOfWork.ErrorCode.INTERNAL, failed.code());
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(0, count(connection, "SELECT count(*) FROM meeting"));
            assertEquals(0, count(connection, "SELECT count(*) FROM meeting_create_fact"));
            assertEquals(0, count(connection, "SELECT count(*) FROM idempotency_record"));
            assertEquals(1, count(connection, """
                    SELECT count(*) FROM usage_reservation
                     WHERE reservation_id = 'reservation-rollback'
                       AND bound_meeting_id IS NULL AND version = 0
                    """));
            assertEquals(1, count(connection, "SELECT count(*) FROM outbox_event"));
        }
    }

    @Test
    void meetingUniqueConflictRollsBackReservationAndDoesNotWriteV013() throws Exception {
        UsageOperationResult existing = insertReserve("existing", "mtg_complete_existing");
        success(unitOfWork(CREATED_AT).create(
                request(existing, "first-key-123456789", null, "first-token")));
        UsageOperationResult candidate = insertReserve("candidate", "mtg_complete_candidate");
        // V001 的 deployment/clientSession 唯一键与已提交会议冲突。
        var sameSession = request(candidate, KEY, null, "candidate-token");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE meeting SET client_session_id = 'session-candidate' "
                    + "WHERE meeting_id = 'mtg_complete_existing'");
        }
        var failed = failure(unitOfWork(CREATED_AT.plusSeconds(1)).create(sameSession));
        assertEquals(CompleteMeetingCreationUnitOfWork.ErrorCode.MEETING_CONFLICT,
                failed.code());
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(1, count(connection, "SELECT count(*) FROM meeting"));
            assertEquals(1, count(connection, "SELECT count(*) FROM meeting_create_fact"));
            assertEquals(1, count(connection, """
                    SELECT count(*) FROM usage_reservation
                     WHERE reservation_id = 'reservation-candidate'
                       AND bound_meeting_id IS NULL AND version = 0
                    """));
        }
    }

    @Test
    void v013InsertFailureRollsBackMeetingReservationAndOutbox() throws Exception {
        UsageOperationResult reserve = insertReserve("fact-failure", "mtg_complete_fact_failure");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION reject_create_fact() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'injected V013 failure'; END $$
                    """);
            statement.execute("""
                    CREATE TRIGGER reject_create_fact BEFORE INSERT ON meeting_create_fact
                    FOR EACH ROW EXECUTE FUNCTION reject_create_fact()
                    """);
        }
        var failed = failure(unitOfWork(CREATED_AT).create(
                request(reserve, KEY, null, "first-token")));
        assertEquals(CompleteMeetingCreationUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE, failed.code());
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(0, count(connection, "SELECT count(*) FROM meeting"));
            assertEquals(0, count(connection, "SELECT count(*) FROM meeting_create_fact"));
            assertEquals(0, count(connection, "SELECT count(*) FROM outbox_event"));
            assertEquals(1, count(connection, """
                    SELECT count(*) FROM usage_reservation
                     WHERE reservation_id = 'reservation-fact-failure'
                       AND bound_meeting_id IS NULL AND version = 0
                    """));
        }
    }

    @Test
    void rejectsAssembledResponseThatDisagreesWithFirstAuthorization() throws Exception {
        UsageOperationResult reserve = insertReserve("validation", "mtg_complete_validation");
        var baseline = request(reserve, KEY, null, "first-token");
        CreateMeetingResponseFact invalidProfile = new CreateMeetingResponseFact(
                "mtg_complete_validation", MeetingState.CREATED,
                new CreateMeetingRequestSnapshot.ProfileRef("other", 1), CAPABILITIES,
                List.of(), AudioProfile.pcm16kMono(100), baseline.responseFact().realtime(),
                baseline.responseFact().limits());
        assertThrows(IllegalArgumentException.class, () ->
                new CompleteMeetingCreationUnitOfWork.CreateRequest(
                        DEPLOYMENT, SUBJECT, baseline.requestFact(), invalidProfile, reserve));

        CreateMeetingResponseFact invalidAudio = new CreateMeetingResponseFact(
                "mtg_complete_validation", MeetingState.CREATED,
                baseline.responseFact().acceptedProfile(), CAPABILITIES,
                List.of(), AudioProfile.pcm16kMono(20), baseline.responseFact().realtime(),
                baseline.responseFact().limits());
        assertThrows(IllegalArgumentException.class, () ->
                new CompleteMeetingCreationUnitOfWork.CreateRequest(
                        DEPLOYMENT, SUBJECT, baseline.requestFact(), invalidAudio, reserve));

        CreateMeetingResponseFact invalidLimit = new CreateMeetingResponseFact(
                "mtg_complete_validation", MeetingState.CREATED,
                baseline.responseFact().acceptedProfile(), CAPABILITIES,
                List.of(), AudioProfile.pcm16kMono(100), baseline.responseFact().realtime(),
                new CreateMeetingResponseFact.Limits(3_600_001, 64_000, 200, null));
        assertThrows(IllegalArgumentException.class, () ->
                new CompleteMeetingCreationUnitOfWork.CreateRequest(
                        DEPLOYMENT, SUBJECT, baseline.requestFact(), invalidLimit, reserve));
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(0, count(connection, "SELECT count(*) FROM meeting"));
            assertEquals(0, count(connection, "SELECT count(*) FROM meeting_create_fact"));
        }
    }

    private JdbcCompleteMeetingCreationUnitOfWork unitOfWork(Instant at) {
        return new JdbcCompleteMeetingCreationUnitOfWork(
                dataSource, Clock.fixed(at, ZoneOffset.UTC));
    }

    private static CompleteMeetingCreateReplayLookup.Request lookupRequest(
            CompleteMeetingCreationUnitOfWork.CreateRequest request) {
        return new CompleteMeetingCreateReplayLookup.Request(
                request.deploymentScope(), request.subjectScope(),
                request.requestFact().requestSnapshot().idempotencyKey(),
                request.requestFact().canonicalRequestDigest());
    }

    private UsageOperationResult insertReserve(String suffix, String meetingId) throws Exception {
        MeetingGrant grant = new MeetingGrant("grant-" + suffix, DEPLOYMENT, SUBJECT,
                "standard", 1, CAPABILITIES, CAPABILITIES, 3_600_000,
                RESERVED_AT.minusSeconds(10), RESERVED_AT.plusSeconds(120));
        UsageReservation reservation = UsageReservation.reserved(
                "reservation-" + suffix, grant, meetingId,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                RESERVED_AT, RESERVED_AT.plusSeconds(90));
        UsageOperationResult result = UsageOperationResult.reserve(
                DEPLOYMENT, SUBJECT, "reserve-key-" + suffix, "reserve-digest-" + suffix,
                grant.expiresAt(), AuthorizedProfileSnapshot.from(grant), reservation);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcUsageReservationStore.insertReservation(connection, reservation);
            assertTrue(JdbcUsageReservationStore.insertOperation(connection, result, RESERVED_AT));
            connection.commit();
        }
        return result;
    }

    private static CompleteMeetingCreationUnitOfWork.CreateRequest request(
            UsageOperationResult reserve, String key, String title, String token) {
        CreateMeetingRequestSnapshot snapshot = new CreateMeetingRequestSnapshot(
                "session-" + reserve.reservation().reservationId().substring("reservation-".length()),
                new CreateMeetingRequestSnapshot.ProfileRef("standard", 1),
                new CreateMeetingRequestSnapshot.ClientDescriptor("app", "1", "v1", "device"),
                title, "Asia/Shanghai",
                new CreateMeetingRequestSnapshot.LanguageSelection("zh-CN", List.of()),
                AudioProfile.pcm16kMono(100), CAPABILITIES,
                new CreateMeetingRequestSnapshot.ArtifactPolicy(false, false), key);
        CreateMeetingRequestFact request = new CreateMeetingRequestFact(snapshot,
                MeetingRequestCanonicalizer.digest(snapshot), 1);
        CreateMeetingResponseFact response = new CreateMeetingResponseFact(
                reserve.reservation().expectedMeetingId(), MeetingState.CREATED,
                new CreateMeetingRequestSnapshot.ProfileRef("standard", 1),
                CAPABILITIES, List.of(), AudioProfile.pcm16kMono(100),
                new CreateMeetingResponseFact.RealtimeSession(
                        URI.create("wss://example.test/realtime"),
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                        token, CREATED_AT.plusSeconds(120)),
                new CreateMeetingResponseFact.Limits(3_600_000, 64_000, 200, null));
        return new CompleteMeetingCreationUnitOfWork.CreateRequest(
                DEPLOYMENT, SUBJECT, request, response, reserve);
    }

    private static CompleteMeetingCreationUnitOfWork.Success success(
            CompleteMeetingCreationUnitOfWork.Result result) {
        assertTrue(result instanceof CompleteMeetingCreationUnitOfWork.Success,
                () -> "expected success, got " + result);
        return (CompleteMeetingCreationUnitOfWork.Success) result;
    }

    private static CompleteMeetingCreationUnitOfWork.Failure failure(
            CompleteMeetingCreationUnitOfWork.Result result) {
        assertTrue(result instanceof CompleteMeetingCreationUnitOfWork.Failure,
                () -> "expected failure, got " + result);
        return (CompleteMeetingCreationUnitOfWork.Failure) result;
    }

    private static long count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static Instant instant(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getTimestamp(1).toInstant();
        }
    }

    private static Instant instant(Connection connection, String sql, String arg) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, arg);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getTimestamp(1).toInstant();
            }
        }
    }

    private void apply(String filename) throws Exception {
        if (!filename.equals("V001__initial_schema.sql")) return;
        Path path = Path.of("..", "deploy", "migrations", filename);
        if (!Files.isRegularFile(path)) {
            path = Path.of("deploy", "migrations", filename);
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(path, StandardCharsets.UTF_8));
        }
    }

    private static PGSimpleDataSource postgres(String targetSchema) {
        String url = System.getenv("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException("shared local database is forbidden");
        }
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(System.getenv("REFERENCE_TEST_DATABASE_USER"));
        source.setPassword(System.getenv("REFERENCE_TEST_DATABASE_PASSWORD"));
        if (targetSchema != null) {
            source.setCurrentSchema(targetSchema);
        }
        return source;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
