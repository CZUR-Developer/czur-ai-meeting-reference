package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.MeetingCreateFailure;
import com.czur.reference.application.port.MeetingCreateOutcome;
import com.czur.reference.application.port.MeetingCreateOutcomeStore;
import com.czur.reference.application.port.MeetingCreateReplayLookup;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageReserveLookup;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 两个 typed replay lookup 的真实 PostgreSQL 16 只读、兼容和故障分类门禁。 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcReplayLookupIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00.123456Z");
    private static final String DEPLOYMENT = "lookup-deployment";
    private static final String SUBJECT = "lookup-subject";
    private static final String SESSION = "lookup-session";

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createSchemaAndMigrate() throws Exception {
        adminDataSource = postgres(null);
        schema = "replay_lookup_" + UUID.randomUUID().toString().replace("-", "");
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
    void meetingLookupReplaysV2SuccessAndV3FailureWithScopeIsolationAndNoWrites()
            throws Exception {
        String scope = IdempotencyScopeCodec.meetingCreate(DEPLOYMENT, SUBJECT);
        MeetingCreateOutcome success = MeetingCreateOutcome.success(
                createdResult("meeting-success", DEPLOYMENT, SUBJECT, SESSION));
        MeetingCreateOutcome failure = MeetingCreateOutcome.failure(
                new MeetingCreateFailure(
                        OwnedMeetingCreationUnitOfWork.ErrorCode.RESERVATION_EXPIRED,
                        false));
        JdbcMeetingCreateOutcomeStore store = new JdbcMeetingCreateOutcomeStore(
                dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
        store.reserve(new MeetingCreateOutcomeStore.OutcomeRecord(
                scope, "meeting-success-key", "meeting-success-digest", success));
        store.reserve(new MeetingCreateOutcomeStore.OutcomeRecord(
                scope, "meeting-failure-key", "meeting-failure-digest", failure));
        TableCounts before = tableCounts();

        JdbcMeetingCreateReplayLookup lookup = new JdbcMeetingCreateReplayLookup(dataSource);
        MeetingCreateReplayLookup.Replayed replayed = assertInstanceOf(
                MeetingCreateReplayLookup.Replayed.class,
                lookup.lookup(meetingRequest(
                        "meeting-success-key", "meeting-success-digest", SESSION)));
        assertEquals(success, replayed.outcome());
        MeetingCreateReplayLookup.Replayed failed = assertInstanceOf(
                MeetingCreateReplayLookup.Replayed.class,
                lookup.lookup(meetingRequest(
                        "meeting-failure-key", "meeting-failure-digest", SESSION)));
        assertEquals(failure, failed.outcome(), "v3 确定失败必须原样重放");

        assertInstanceOf(MeetingCreateReplayLookup.NotFound.class,
                lookup.lookup(new MeetingCreateReplayLookup.Request(
                        "other-deployment", SUBJECT, SESSION,
                        "meeting-success-key", "meeting-success-digest")));
        assertInstanceOf(MeetingCreateReplayLookup.NotFound.class,
                lookup.lookup(new MeetingCreateReplayLookup.Request(
                        DEPLOYMENT, "other-subject", SESSION,
                        "meeting-success-key", "meeting-success-digest")));

        // 重建适配器模拟进程重启；结果必须完全来自数据库首次事实。
        MeetingCreateReplayLookup.Replayed afterAdapterRestart = assertInstanceOf(
                MeetingCreateReplayLookup.Replayed.class,
                new JdbcMeetingCreateReplayLookup(dataSource).lookup(meetingRequest(
                        "meeting-success-key", "meeting-success-digest", SESSION)));
        assertEquals(success, afterAdapterRestart.outcome());
        assertEquals(before, tableCounts(), "只读查询不得改变任何业务表行数");
    }

    @Test
    void meetingLookupRejectsV1AndMismatchedSuccessAndChecksDigestBeforeBody()
            throws Exception {
        String scope = IdempotencyScopeCodec.meetingCreate(DEPLOYMENT, SUBJECT);
        String legacyV1 = "{\"operation\":\"CREATED\",\"meeting\":{"
                + "\"meetingId\":\"legacy\",\"deploymentScope\":\"" + DEPLOYMENT + "\","
                + "\"clientSessionId\":\"" + SESSION + "\","
                + "\"profileId\":\"profile\",\"profileRevision\":1,"
                + "\"state\":\"CREATED\",\"createdAt\":\"2026-09-18T00:00:00Z\","
                + "\"updatedAt\":\"2026-09-18T00:00:00Z\"}}";
        insertRawOutcome(scope, "legacy-key", "legacy-digest", 200, legacyV1);

        JdbcMeetingCreateOutcomeStore store = new JdbcMeetingCreateOutcomeStore(
                dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
        store.reserve(outcomeRecord("scope-mismatch-key", "scope-mismatch-digest",
                createdResult("scope-mismatch", "other-deployment", SUBJECT, SESSION)));
        store.reserve(outcomeRecord("session-mismatch-key", "session-mismatch-digest",
                createdResult("session-mismatch", DEPLOYMENT, SUBJECT, "other-session")));
        insertRawOutcome(scope, "damaged-key", "stored-digest", 200,
                "{\"schemaVersion\":99}");
        TableCounts before = tableCounts();

        JdbcMeetingCreateReplayLookup lookup = new JdbcMeetingCreateReplayLookup(dataSource);
        assertEquals(MeetingCreateReplayLookup.InconsistencyReason.SUCCESS_NOT_OWNED,
                assertInstanceOf(MeetingCreateReplayLookup.Inconsistent.class,
                        lookup.lookup(meetingRequest(
                                "legacy-key", "legacy-digest", SESSION))).reason());
        assertEquals(MeetingCreateReplayLookup.InconsistencyReason.SUCCESS_SCOPE_MISMATCH,
                assertInstanceOf(MeetingCreateReplayLookup.Inconsistent.class,
                        lookup.lookup(meetingRequest(
                                "scope-mismatch-key", "scope-mismatch-digest", SESSION))).reason());
        assertEquals(MeetingCreateReplayLookup.InconsistencyReason.SUCCESS_SESSION_MISMATCH,
                assertInstanceOf(MeetingCreateReplayLookup.Inconsistent.class,
                        lookup.lookup(meetingRequest(
                                "session-mismatch-key", "session-mismatch-digest", SESSION))).reason());

        assertInstanceOf(MeetingCreateReplayLookup.Conflict.class,
                lookup.lookup(meetingRequest("damaged-key", "different-digest", SESSION)));
        assertEquals(MeetingCreateReplayLookup.InconsistencyReason.STORED_OUTCOME_INVALID,
                assertInstanceOf(MeetingCreateReplayLookup.Inconsistent.class,
                        lookup.lookup(meetingRequest(
                                "damaged-key", "stored-digest", SESSION))).reason());
        assertEquals(before, tableCounts());
    }

    @Test
    void usageLookupReplaysOnlyFirstV2SnapshotAndNeverReadsCurrentReservation()
            throws Exception {
        UsageOperationResult stored = reserveResult(
                "reserve-key", "reserve-digest", "reservation-first",
                profile(Set.of("transcript.realtime"), Set.of("transcript.realtime"), 60_000),
                60_000);
        insertReserveResult(stored);
        TableCounts before = tableCounts();
        JdbcUsageReserveLookup lookup = new JdbcUsageReserveLookup(dataSource);

        UsageReserveLookup.Replayed replayed = assertInstanceOf(
                UsageReserveLookup.Replayed.class,
                lookup.lookup(usageRequest("reserve-key", "reserve-digest")));
        assertEquals(stored, replayed.result());
        assertInstanceOf(UsageReserveLookup.NotFound.class,
                lookup.lookup(new UsageReserveLookup.Request(
                        "other-deployment", SUBJECT, "reserve-key", "reserve-digest")));
        assertInstanceOf(UsageReserveLookup.NotFound.class,
                lookup.lookup(new UsageReserveLookup.Request(
                        DEPLOYMENT, "other-subject", "reserve-key", "reserve-digest")));
        assertEquals(before, tableCounts());

        // 当前 Reservation 已发生变化，首次 result_body 仍必须原样重放。
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE usage_reservation SET reserved_amount = 30000
                      WHERE reservation_id = 'reservation-first'
                     """)) {
            assertEquals(1, statement.executeUpdate());
        }
        TableCounts afterCurrentMutation = tableCounts();
        UsageReserveLookup.Replayed afterCurrentFactChanged = assertInstanceOf(
                UsageReserveLookup.Replayed.class,
                new JdbcUsageReserveLookup(dataSource).lookup(
                        usageRequest("reserve-key", "reserve-digest")));
        assertEquals(60_000,
                afterCurrentFactChanged.result().reservation().reservedUsage().amount());
        assertEquals(afterCurrentMutation, tableCounts(),
                "查询当前事实变化后的首次结果仍不得写入任何业务表");
    }

    @Test
    void usageLookupRejectsV1ReferencesAndBusinessInvariantsAndChecksDigestFirst()
            throws Exception {
        UsageOperationResult legacySource = reserveResult(
                "legacy-reserve", "legacy-digest", "reservation-legacy",
                profile(Set.of("transcript.realtime"), Set.of("transcript.realtime"), 60_000),
                60_000);
        insertReservation(legacySource.reservation());
        insertRawUsageOperation(
                legacySource, legacySource.reservation().reservationId(),
                toLegacyV1(UsageOperationResultJson.encode(legacySource)));

        UsageOperationResult damagedSource = reserveResult(
                "damaged-reserve", "damaged-digest", "reservation-damaged",
                profile(Set.of("transcript.realtime"), Set.of("transcript.realtime"), 60_000),
                60_000);
        insertReservation(damagedSource.reservation());
        insertRawUsageOperation(
                damagedSource, damagedSource.reservation().reservationId(),
                "{\"schemaVersion\":99}");

        UsageOperationResult referenced = reserveResult(
                "reference-mismatch", "reference-digest", "reservation-body",
                profile(Set.of("transcript.realtime"), Set.of("transcript.realtime"), 60_000),
                60_000);
        UsageOperationResult other = reserveResult(
                "unused-other-key", "unused-other-digest", "reservation-row",
                profile(Set.of("transcript.realtime"), Set.of("transcript.realtime"), 60_000),
                60_000);
        insertReservation(referenced.reservation());
        insertReservation(other.reservation());
        insertRawUsageOperation(
                referenced, other.reservation().reservationId(),
                UsageOperationResultJson.encode(referenced));

        UsageOperationResult capabilitiesInvalid = reserveResult(
                "capability-invalid", "capability-digest", "reservation-capability",
                profile(Set.of("transcript.realtime", "summary.structured"),
                        Set.of("transcript.realtime"), 60_000),
                60_000);
        insertReserveResult(capabilitiesInvalid);
        UsageOperationResult amountInvalid = reserveResult(
                "amount-invalid", "amount-digest", "reservation-amount",
                profile(Set.of("transcript.realtime"), Set.of("transcript.realtime"), 30_000),
                60_000);
        insertReserveResult(amountInvalid);
        TableCounts before = tableCounts();

        JdbcUsageReserveLookup lookup = new JdbcUsageReserveLookup(dataSource);
        assertEquals(UsageReserveLookup.InconsistencyReason.OWNED_RESERVE_INVARIANT_VIOLATION,
                inconsistent(lookup.lookup(
                        usageRequest("legacy-reserve", "legacy-digest"))).reason());
        assertInstanceOf(UsageReserveLookup.Conflict.class,
                lookup.lookup(usageRequest("damaged-reserve", "different-digest")));
        assertEquals(UsageReserveLookup.InconsistencyReason.STORED_RESULT_INVALID,
                inconsistent(lookup.lookup(
                        usageRequest("damaged-reserve", "damaged-digest"))).reason());
        assertEquals(UsageReserveLookup.InconsistencyReason.RESERVATION_REFERENCE_MISMATCH,
                inconsistent(lookup.lookup(
                        usageRequest("reference-mismatch", "reference-digest"))).reason());
        assertEquals(UsageReserveLookup.InconsistencyReason.OWNED_RESERVE_INVARIANT_VIOLATION,
                inconsistent(lookup.lookup(
                        usageRequest("capability-invalid", "capability-digest"))).reason());
        assertEquals(UsageReserveLookup.InconsistencyReason.OWNED_RESERVE_INVARIANT_VIOLATION,
                inconsistent(lookup.lookup(
                        usageRequest("amount-invalid", "amount-digest"))).reason());
        assertEquals(before, tableCounts());
    }

    @Test
    void disconnectedDatabaseIsRetryableButWrongSchemaIsNot() {
        PGSimpleDataSource disconnected = new PGSimpleDataSource();
        disconnected.setUrl("jdbc:postgresql://127.0.0.1:1/reference?connectTimeout=1");
        disconnected.setUser("reference");
        disconnected.setPassword("reference");
        MeetingCreateReplayLookup.Unavailable meetingDisconnected = assertInstanceOf(
                MeetingCreateReplayLookup.Unavailable.class,
                new JdbcMeetingCreateReplayLookup(disconnected).lookup(
                        meetingRequest("missing", "digest", SESSION)));
        UsageReserveLookup.Unavailable usageDisconnected = assertInstanceOf(
                UsageReserveLookup.Unavailable.class,
                new JdbcUsageReserveLookup(disconnected).lookup(
                        usageRequest("missing", "digest")));
        assertTrue(meetingDisconnected.retryable());
        assertTrue(usageDisconnected.retryable());

        PGSimpleDataSource wrongSchema = postgres("missing_schema_" + UUID.randomUUID());
        MeetingCreateReplayLookup.Unavailable meetingWrongSchema = assertInstanceOf(
                MeetingCreateReplayLookup.Unavailable.class,
                new JdbcMeetingCreateReplayLookup(wrongSchema).lookup(
                        meetingRequest("missing", "digest", SESSION)));
        UsageReserveLookup.Unavailable usageWrongSchema = assertInstanceOf(
                UsageReserveLookup.Unavailable.class,
                new JdbcUsageReserveLookup(wrongSchema).lookup(
                        usageRequest("missing", "digest")));
        assertFalse(meetingWrongSchema.retryable());
        assertFalse(usageWrongSchema.retryable());
    }

    private MeetingCreateReplayLookup.Request meetingRequest(
            String key, String digest, String session) {
        return new MeetingCreateReplayLookup.Request(
                DEPLOYMENT, SUBJECT, session, key, digest);
    }

    private UsageReserveLookup.Request usageRequest(String key, String digest) {
        return new UsageReserveLookup.Request(DEPLOYMENT, SUBJECT, key, digest);
    }

    private static UsageReserveLookup.Inconsistent inconsistent(
            UsageReserveLookup.Result result) {
        return assertInstanceOf(UsageReserveLookup.Inconsistent.class, result);
    }

    private MeetingCreateOutcomeStore.OutcomeRecord outcomeRecord(
            String key, String digest, MeetingCommandResult result) {
        return new MeetingCreateOutcomeStore.OutcomeRecord(
                IdempotencyScopeCodec.meetingCreate(DEPLOYMENT, SUBJECT),
                key, digest, MeetingCreateOutcome.success(result));
    }

    private static MeetingCommandResult createdResult(
            String meetingId, String deployment, String subject, String session) {
        return new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED,
                MeetingAggregate.MeetingSnapshot.owned(
                        meetingId, deployment, session, subject,
                        "grant-" + meetingId, NOW.plusSeconds(600),
                        "reservation-" + meetingId, "profile", 1,
                        MeetingState.CREATED, NOW, NOW));
    }

    private static AuthorizedProfileSnapshot profile(
            Set<String> requested, Set<String> granted, long maxDurationMs) {
        return new AuthorizedProfileSnapshot(
                "profile", 1, requested, granted, maxDurationMs);
    }

    private static UsageOperationResult reserveResult(
            String key,
            String digest,
            String reservationId,
            AuthorizedProfileSnapshot profile,
            long amount) {
        UsageReservation reservation = new UsageReservation(
                reservationId, DEPLOYMENT, SUBJECT, "grant-" + reservationId,
                "meeting-" + reservationId, null, null,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, amount),
                null, NOW, NOW.plusSeconds(300),
                UsageReservation.State.RESERVED, 0, null, null, null);
        return UsageOperationResult.reserve(
                DEPLOYMENT, SUBJECT, key, digest,
                NOW.plusSeconds(600), profile, reservation);
    }

    private void insertReserveResult(UsageOperationResult result) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcUsageReservationStore.insertReservation(connection, result.reservation());
            assertTrue(JdbcUsageReservationStore.insertOperation(connection, result, NOW));
            connection.commit();
        }
    }

    private void insertReservation(UsageReservation reservation) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            JdbcUsageReservationStore.insertReservation(connection, reservation);
        }
    }

    private void insertRawUsageOperation(
            UsageOperationResult identity, String reservationId, String resultBody)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO usage_operation (
                         deployment_scope, subject_scope, operation_scope, idempotency_key,
                         request_digest, reservation_id, result_body, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                     """)) {
            statement.setString(1, identity.deploymentScope());
            statement.setString(2, identity.subjectScope());
            statement.setString(3, UsageOperationScopes.RESERVE);
            statement.setString(4, identity.idempotencyKey());
            statement.setString(5, identity.requestDigest());
            statement.setString(6, reservationId);
            statement.setString(7, resultBody);
            statement.setTimestamp(8, Timestamp.from(NOW));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void insertRawOutcome(
            String scope, String key, String digest, int status, String body)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO idempotency_record (
                         subject_scope, idempotency_key, request_hash,
                         response_status, response_body, created_at)
                     VALUES (?, ?, ?, ?, ?::jsonb, ?)
                     """)) {
            statement.setString(1, scope);
            statement.setString(2, key);
            statement.setString(3, digest);
            statement.setInt(4, status);
            statement.setString(5, body);
            statement.setTimestamp(6, Timestamp.from(NOW));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String toLegacyV1(String v2) {
        int fieldStart = v2.indexOf(",\"authorizedProfileSnapshot\":");
        int reservationStart = v2.indexOf(",\"reservation\":", fieldStart);
        if (fieldStart < 0 || reservationStart < 0) {
            throw new AssertionError("v2 fixture is missing authorized profile field");
        }
        return (v2.substring(0, fieldStart) + v2.substring(reservationStart))
                .replace("\"schemaVersion\":2", "\"schemaVersion\":1");
    }

    private TableCounts tableCounts() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return new TableCounts(
                    count(connection, "idempotency_record"),
                    count(connection, "usage_operation"),
                    count(connection, "usage_reservation"),
                    count(connection, "meeting"),
                    count(connection, "outbox_event"));
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        // 表名只来自本测试中的固定常量，绝不拼接外部输入。
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
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

    private record TableCounts(
            long idempotency,
            long usageOperation,
            long usageReservation,
            long meeting,
            long outbox) {
    }
}
