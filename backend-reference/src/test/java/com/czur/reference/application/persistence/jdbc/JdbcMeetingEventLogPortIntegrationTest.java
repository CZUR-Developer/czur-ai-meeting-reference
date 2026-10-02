package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.MeetingEventLogPort;
import com.czur.reference.application.port.MeetingEventPage;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
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
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcMeetingEventLogPort} 在全新 PostgreSQL 16 V001→V006 上的事实门禁。
 *
 * <p>每个用例创建独立 schema，顺序执行全部迁移并在结束后精确删除。测试覆盖
 * 会议存在性、归属隔离、历史/畸形事件关闭式失败、混合逻辑流全局分页、空页和
 * 超前游标。测试只使用显式传入的临时数据库地址，不创建或访问持久化数据卷。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcMeetingEventLogPortIntegrationTest {
    private static final String DEPLOYMENT = "deployment-event-a";
    private static final String SUBJECT = "subject-event-a";
    private static final Instant BASE = Instant.parse("2026-09-20T10:00:00Z");

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createFreshV006Schema() throws Exception {
        adminDataSource = postgres(null);
        schema = "event_port_" + UUID.randomUUID().toString().replace("-", "");
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
                "V006__meeting_read_model.sql")) {
            applyMigration(migration);
        }
    }

    @AfterEach
    void dropSchema() throws Exception {
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
    void readsMixedStreamsByMeetingGlobalSequenceAndPreservesEmptyCursors()
            throws Exception {
        insertMeeting("meeting-events", DEPLOYMENT, SUBJECT);
        JdbcMeetingEventLogStore store = new JdbcMeetingEventLogStore(dataSource);
        store.append(event("meeting-events", 1, "stream-a", "event-1"));
        store.append(event("meeting-events", 2, "stream-b", "event-2"));
        store.append(event("meeting-events", 3, "stream-a", "event-3"));
        JdbcMeetingEventLogPort port = new JdbcMeetingEventLogPort(dataSource);
        MeetingCommandContext owner = context(DEPLOYMENT, SUBJECT);

        MeetingEventPage first = assertInstanceOf(
                MeetingEventLogPort.Page.class,
                port.read(request(owner, "meeting-events", 0, 2))).page();
        assertEquals(List.of(1L, 2L), first.items().stream()
                .map(EventLog::eventSequence).toList());
        assertEquals(List.of("stream-a", "stream-b"), first.items().stream()
                .map(EventLog::streamId).toList());
        assertEquals(2, first.lastSequence());
        assertTrue(first.hasMore());

        MeetingEventPage second = assertInstanceOf(
                MeetingEventLogPort.Page.class,
                port.read(request(owner, "meeting-events", 2, 2))).page();
        assertEquals(List.of(3L), second.items().stream()
                .map(EventLog::eventSequence).toList());
        assertEquals("stream-a", second.items().getFirst().streamId());
        assertEquals(3, second.lastSequence());
        assertFalse(second.hasMore());

        MeetingEventPage exactEnd = assertInstanceOf(
                MeetingEventLogPort.Page.class,
                port.read(request(owner, "meeting-events", 3, 10))).page();
        assertTrue(exactEnd.items().isEmpty());
        assertEquals(3, exactEnd.lastSequence());
        assertFalse(exactEnd.hasMore());

        MeetingEventLogPort.Conflict ahead = assertInstanceOf(
                MeetingEventLogPort.Conflict.class,
                port.read(request(owner, "meeting-events", 99, 10)));
        assertEquals(MeetingEventLogPort.ConflictReason.INVALID_CURSOR, ahead.reason());
    }

    @Test
    void distinguishesMissingFromDeniedWithoutExposingBadForeignEvents() throws Exception {
        insertMeeting("meeting-owned", DEPLOYMENT, SUBJECT);
        insertMeeting("meeting-foreign-subject", DEPLOYMENT, "subject-other");
        insertMeeting("meeting-foreign-deployment", "deployment-other", SUBJECT);
        insertMeeting("meeting-foreign-invalid-json", DEPLOYMENT, "subject-other");
        insertMeeting("meeting-legacy", DEPLOYMENT, "LEGACY_UNOWNED");
        insertHistoricalEvent("meeting-foreign-subject", "foreign-subject-event");
        insertHistoricalEvent("meeting-foreign-deployment", "foreign-deployment-event");
        insertHistoricalEvent("meeting-legacy", "legacy-event");
        new JdbcMeetingEventLogStore(dataSource).append(event(
                "meeting-foreign-invalid-json", 1, "stream-a", "foreign-invalid-json"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE meeting_event DROP CONSTRAINT "
                    + "meeting_event_envelope_group_ck");
            statement.execute("UPDATE meeting_event SET canonical_payload = 'not-json' "
                    + "WHERE meeting_id = 'meeting-foreign-invalid-json'");
        }
        JdbcMeetingEventLogPort port = new JdbcMeetingEventLogPort(dataSource);
        MeetingCommandContext owner = context(DEPLOYMENT, SUBJECT);

        assertInstanceOf(MeetingEventLogPort.NotFound.class,
                port.read(request(owner, "meeting-absent", 0, 10)));
        assertInstanceOf(MeetingEventLogPort.AccessDenied.class,
                port.read(request(owner, "meeting-foreign-subject", 0, 10)));
        assertInstanceOf(MeetingEventLogPort.AccessDenied.class,
                port.read(request(owner, "meeting-foreign-deployment", 0, 10)));
        assertInstanceOf(MeetingEventLogPort.AccessDenied.class,
                port.read(request(owner, "meeting-foreign-invalid-json", 0, 10)));
        assertInstanceOf(MeetingEventLogPort.AccessDenied.class,
                port.read(request(owner, "meeting-legacy", 0, 10)));

        MeetingEventPage ownedEmpty = assertInstanceOf(
                MeetingEventLogPort.Page.class,
                port.read(request(owner, "meeting-owned", 0, 10))).page();
        assertTrue(ownedEmpty.items().isEmpty());
        assertEquals(0, ownedEmpty.lastSequence());

        MeetingEventLogPort.Conflict emptyMeetingAhead = assertInstanceOf(
                MeetingEventLogPort.Conflict.class,
                port.read(request(owner, "meeting-owned", 1, 10)));
        assertEquals(MeetingEventLogPort.ConflictReason.INVALID_CURSOR,
                emptyMeetingAhead.reason());
    }

    @Test
    void ownerHistoricalEnvelopeGapIsFactFailureNotExpiredHistory() throws Exception {
        insertMeeting("meeting-owner-history", DEPLOYMENT, SUBJECT);
        insertHistoricalEvent("meeting-owner-history", "historical-event");

        MeetingEventLogPort.Unavailable unavailable = assertInstanceOf(
                MeetingEventLogPort.Unavailable.class,
                new JdbcMeetingEventLogPort(dataSource).read(request(
                        context(DEPLOYMENT, SUBJECT), "meeting-owner-history", 0, 10)));

        assertEquals(MeetingEventLogPort.UnavailableReason.STORAGE_FAILURE,
                unavailable.reason());
        assertFalse(unavailable.retryable());
    }

    @Test
    void ownerMalformedEventsBeforeTheCursorFailWithoutClaimingExpiry() throws Exception {
        List<String> meetingIds = List.of(
                "meeting-blank-canonical",
                "meeting-blank-spec",
                "meeting-tab-spec",
                "meeting-java-control-spec",
                "meeting-mixed-control-spec",
                "meeting-newline-event-type",
                "meeting-blank-stream",
                "meeting-blank-event-id",
                "meeting-blank-event-type",
                "meeting-null-occurred-at",
                "meeting-invalid-sequence",
                "meeting-invalid-json",
                "meeting-payload-mismatch");
        JdbcMeetingEventLogStore store = new JdbcMeetingEventLogStore(dataSource);
        for (int index = 0; index < meetingIds.size(); index++) {
            String meetingId = meetingIds.get(index);
            insertMeeting(meetingId, DEPLOYMENT, SUBJECT);
            store.append(event(meetingId, 1, "stream-a", "malformed-event-" + index));
        }

        // 只破坏本测试的随机 schema，用于模拟外部运维或历史半迁移留下的事实。
        // 生产迁移本身仍保留严格约束，测试结束后整个 schema 会被删除。非法
        // canonical JSON 必须由 pg_input_is_valid 安全识别，不能在 Guard 前抛错。
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE meeting_event DROP CONSTRAINT "
                    + "meeting_event_envelope_group_ck");
            statement.execute("ALTER TABLE meeting_event ALTER COLUMN occurred_at DROP NOT NULL");
            statement.execute("ALTER TABLE meeting_event DROP CONSTRAINT "
                    + "meeting_event_event_sequence_check");
            statement.execute("UPDATE meeting_event SET canonical_payload = ' ' "
                    + "WHERE meeting_id = 'meeting-blank-canonical'");
            statement.execute("UPDATE meeting_event SET spec_version = ' ' "
                    + "WHERE meeting_id = 'meeting-blank-spec'");
            statement.execute("UPDATE meeting_event SET spec_version = E'\\t' "
                    + "WHERE meeting_id = 'meeting-tab-spec'");
            statement.execute("UPDATE meeting_event SET spec_version = chr(28) "
                    + "WHERE meeting_id = 'meeting-java-control-spec'");
            statement.execute("UPDATE meeting_event SET spec_version = E'\\t' || chr(28) "
                    + "WHERE meeting_id = 'meeting-mixed-control-spec'");
            statement.execute("UPDATE meeting_event SET event_type = E'\\n' "
                    + "WHERE meeting_id = 'meeting-newline-event-type'");
            statement.execute("UPDATE meeting_event SET stream_id = ' ' "
                    + "WHERE meeting_id = 'meeting-blank-stream'");
            statement.execute("UPDATE meeting_event SET event_id = ' ' "
                    + "WHERE meeting_id = 'meeting-blank-event-id'");
            statement.execute("UPDATE meeting_event SET event_type = ' ' "
                    + "WHERE meeting_id = 'meeting-blank-event-type'");
            statement.execute("UPDATE meeting_event SET occurred_at = NULL "
                    + "WHERE meeting_id = 'meeting-null-occurred-at'");
            statement.execute("UPDATE meeting_event SET event_sequence = 0 "
                    + "WHERE meeting_id = 'meeting-invalid-sequence'");
            statement.execute("UPDATE meeting_event SET canonical_payload = 'not-json' "
                    + "WHERE meeting_id = 'meeting-invalid-json'");
            statement.execute("UPDATE meeting_event SET canonical_payload = '{\"sequence\":999}' "
                    + "WHERE meeting_id = 'meeting-payload-mismatch'");
        }

        JdbcMeetingEventLogPort port = new JdbcMeetingEventLogPort(dataSource);
        for (String meetingId : meetingIds) {
            MeetingEventLogPort.Unavailable unavailable = assertInstanceOf(
                    MeetingEventLogPort.Unavailable.class,
                    port.read(request(
                            context(DEPLOYMENT, SUBJECT), meetingId, 1, 10)),
                    "游标越过坏事件后仍必须关闭式失败: " + meetingId);
            assertEquals(MeetingEventLogPort.UnavailableReason.STORAGE_FAILURE,
                    unavailable.reason());
            assertFalse(unavailable.retryable());
        }
    }

    @Test
    void postgresBlankPredicateCoversEveryJavaWhitespaceCodePoint() throws Exception {
        String sql = """
                SELECT translate(?, chr(28) || chr(29) || chr(30) || chr(31), '')
                       !~ '[^[:space:]]' AS is_blank
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int javaWhitespaceCount = 0;
            for (int codePoint = Character.MIN_CODE_POINT;
                 codePoint <= Character.MAX_CODE_POINT;
                 codePoint++) {
                if (!Character.isWhitespace(codePoint)) {
                    continue;
                }
                String value = new String(Character.toChars(codePoint));
                assertTrue(value.isBlank());
                statement.setString(1, value);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertTrue(rows.getBoolean("is_blank"),
                            "PostgreSQL 未覆盖 Java isBlank codePoint=U+"
                                    + Integer.toHexString(codePoint).toUpperCase());
                }
                javaWhitespaceCount++;
            }
            assertTrue(javaWhitespaceCount > 0);

            // Java 明确不视为 blank 的不换行空格，以及普通可见字符，不能被 SQL
            // 误判为坏事实。这里与上面的完整 Character.isWhitespace 集合互补。
            for (String visible : List.of("x", "\u00A0", "\u2007", "\u202F")) {
                assertFalse(visible.isBlank());
                statement.setString(1, visible);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertFalse(rows.getBoolean("is_blank"),
                            "可见或不换行空格不能被误判为空白");
                }
            }
        }
    }

    private static MeetingEventLogPort.Request request(
            MeetingCommandContext context,
            String meetingId,
            long afterSequence,
            int limit) {
        return new MeetingEventLogPort.Request(context, meetingId, afterSequence, limit);
    }

    private void insertMeeting(String meetingId, String deployment, String subject)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting (
                         meeting_id, deployment_scope, client_session_id, subject_scope,
                         grant_id, grant_expires_at, usage_reservation_id,
                         profile_id, profile_revision, state, aggregate_version,
                         input_state, finalization_receipt_id, manifest_state,
                         created_at, updated_at, metadata)
                     VALUES (?, ?, ?, ?, NULL, NULL, NULL,
                             'profile-reference', 1, 'RUNNING', 0,
                             'INPUT_OPEN', NULL, 'NOT_STARTED', ?, ?, '{}'::jsonb)
                     """)) {
            statement.setString(1, meetingId);
            statement.setString(2, deployment);
            statement.setString(3, "session-" + meetingId);
            statement.setString(4, subject);
            statement.setTimestamp(5, Timestamp.from(BASE));
            statement.setTimestamp(6, Timestamp.from(BASE));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void insertHistoricalEvent(String meetingId, String eventId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting_event (
                         meeting_id, event_sequence, event_id, event_type,
                         payload, occurred_at,
                         spec_version, stream_id, canonical_payload)
                     VALUES (?, 1, ?, 'transcriptUpsert',
                             '{"text":"historical-private"}'::jsonb, ?,
                             NULL, NULL, NULL)
                     """)) {
            statement.setString(1, meetingId);
            statement.setString(2, eventId);
            statement.setTimestamp(3, Timestamp.from(BASE.plusSeconds(1)));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static EventLog event(
            String meetingId,
            long sequence,
            String streamId,
            String eventId) {
        return new EventLog(
                "0.1",
                meetingId,
                streamId,
                eventId,
                sequence,
                BASE.plusSeconds(sequence),
                "transcriptUpsert",
                "{\"sequence\":" + sequence + '}');
    }

    private void applyMigration(String filename) throws Exception {
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

    private static MeetingCommandContext context(String deployment, String subject) {
        Instant issued = Instant.parse("2026-09-20T00:00:00Z");
        return new MeetingCommandContext(
                deployment,
                new VerifiedIdentity(
                        new Subject(subject),
                        Set.of("USER"),
                        Set.of("meeting:read"),
                        issued,
                        issued.plusSeconds(86_400),
                        "credential-reference"));
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
