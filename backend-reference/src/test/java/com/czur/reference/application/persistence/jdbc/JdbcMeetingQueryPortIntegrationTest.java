package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.application.port.MeetingSummary;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
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
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcMeetingQueryPort} 在全新 PostgreSQL 16 V001→V006 上的事实门禁。
 *
 * <p>每个用例创建独立 schema，不使用共享数据库对象。测试覆盖详情访问守卫、
 * SQL 归属隔离、降序 keyset 同时间决胜、多页完整性、空列表和 HMAC token 的
 * 伪造/跨范围/错误密钥拒绝。测试只写自身 schema，结束后精确删除。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcMeetingQueryPortIntegrationTest {
    private static final byte[] SECRET = secret((byte) 0x41);
    private static final String DEPLOYMENT = "deployment-query-a";
    private static final String SUBJECT = "subject-query-a";
    private static final Instant BASE = Instant.parse("2026-09-20T09:00:00Z");

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createFreshV006Schema() throws Exception {
        adminDataSource = postgres(null);
        schema = "query_" + UUID.randomUUID().toString().replace("-", "");
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
    void getReadsCompleteFinalizationFactAndUsesSharedAccessGuard() throws Exception {
        insertOwned(new MeetingFixture(
                "meeting-detail",
                DEPLOYMENT,
                SUBJECT,
                MeetingState.FINALIZING,
                InputState.INPUT_ACCEPTED,
                "receipt-detail-private",
                ManifestStatus.GENERATING,
                BASE,
                BASE.plusSeconds(12)));
        insertLegacy("meeting-legacy", DEPLOYMENT, BASE.plusSeconds(1));
        insertMalformedNonOwner(
                "meeting-foreign-malformed", DEPLOYMENT, "subject-other", false);
        insertMalformedNonOwner(
                "meeting-legacy-malformed", DEPLOYMENT, "LEGACY_UNOWNED", true);
        insertMalformedNonOwner(
                "meeting-owner-malformed", DEPLOYMENT, SUBJECT, false);
        JdbcMeetingQueryPort query = query(SECRET);

        MeetingQueryPort.Found found = assertInstanceOf(
                MeetingQueryPort.Found.class,
                query.get(new MeetingQueryPort.GetRequest(
                        context(DEPLOYMENT, SUBJECT), "meeting-detail")));
        MeetingStatus status = found.meeting();
        assertEquals("meeting-detail", status.meetingId());
        assertEquals(MeetingState.FINALIZING, status.state());
        assertEquals(InputState.INPUT_ACCEPTED, status.inputState());
        assertEquals("receipt-detail-private", status.finalizationReceipt());
        assertEquals(ManifestStatus.GENERATING, status.manifestState());
        assertEquals(BASE.plusSeconds(12), status.updatedAt());

        assertInstanceOf(MeetingQueryPort.AccessDenied.class,
                query.get(new MeetingQueryPort.GetRequest(
                        context(DEPLOYMENT, "subject-other"), "meeting-detail")));
        assertInstanceOf(MeetingQueryPort.AccessDenied.class,
                query.get(new MeetingQueryPort.GetRequest(
                        context("deployment-other", SUBJECT), "meeting-detail")));
        assertInstanceOf(MeetingQueryPort.AccessDenied.class,
                query.get(new MeetingQueryPort.GetRequest(
                        context(DEPLOYMENT, SUBJECT), "meeting-legacy")));
        assertInstanceOf(MeetingQueryPort.AccessDenied.class,
                query.get(new MeetingQueryPort.GetRequest(
                        context(DEPLOYMENT, SUBJECT), "meeting-foreign-malformed")));
        assertInstanceOf(MeetingQueryPort.AccessDenied.class,
                query.get(new MeetingQueryPort.GetRequest(
                        context(DEPLOYMENT, SUBJECT), "meeting-legacy-malformed")));
        MeetingQueryPort.Conflict malformedOwner = assertInstanceOf(
                MeetingQueryPort.Conflict.class,
                query.get(new MeetingQueryPort.GetRequest(
                        context(DEPLOYMENT, SUBJECT), "meeting-owner-malformed")));
        assertEquals(MeetingQueryPort.ConflictReason.SNAPSHOT_INCONSISTENT,
                malformedOwner.reason());
        assertInstanceOf(MeetingQueryPort.NotFound.class,
                query.get(new MeetingQueryPort.GetRequest(
                        context(DEPLOYMENT, SUBJECT), "meeting-absent")));
    }

    @Test
    void listFiltersOwnerInSqlKeepsTitleNullAndReturnsEmptyPage() throws Exception {
        insertOwned(fixture("meeting-visible", DEPLOYMENT, SUBJECT, BASE));
        insertOwned(fixture("meeting-other-subject", DEPLOYMENT, "subject-other", BASE));
        insertOwned(fixture("meeting-other-deployment", "deployment-other", SUBJECT, BASE));
        insertLegacy("meeting-legacy", DEPLOYMENT, BASE.plusSeconds(2));
        JdbcMeetingQueryPort query = query(SECRET);

        MeetingQueryPort.ListPage page = assertInstanceOf(
                MeetingQueryPort.ListPage.class,
                query.list(new MeetingQueryPort.ListRequest(
                        context(DEPLOYMENT, SUBJECT), null, 100)));
        assertEquals(List.of("meeting-visible"),
                page.items().stream().map(MeetingSummary::meetingId).toList());
        assertNull(page.items().getFirst().title());
        assertNull(page.nextPageToken());

        MeetingQueryPort.ListPage empty = assertInstanceOf(
                MeetingQueryPort.ListPage.class,
                query.list(new MeetingQueryPort.ListRequest(
                        context(DEPLOYMENT, "subject-empty"), null, 10)));
        assertTrue(empty.items().isEmpty());
        assertNull(empty.nextPageToken());
    }

    @Test
    void descendingKeysetHandlesEqualCreationTimeAcrossEveryPageWithoutGapOrDuplicate()
            throws Exception {
        insertOwned(fixture("meeting-newer", DEPLOYMENT, SUBJECT, BASE.plusSeconds(60)));
        insertOwned(fixture("meeting-tie-c", DEPLOYMENT, SUBJECT, BASE));
        insertOwned(fixture("meeting-tie-b", DEPLOYMENT, SUBJECT, BASE));
        insertOwned(fixture("meeting-tie-a", DEPLOYMENT, SUBJECT, BASE));
        insertOwned(fixture("meeting-older", DEPLOYMENT, SUBJECT, BASE.minusSeconds(60)));
        JdbcMeetingQueryPort query = query(SECRET);
        MeetingCommandContext context = context(DEPLOYMENT, SUBJECT);

        List<String> ids = new ArrayList<>();
        String token = null;
        int pageCount = 0;
        do {
            MeetingQueryPort.ListPage page = assertInstanceOf(
                    MeetingQueryPort.ListPage.class,
                    query.list(new MeetingQueryPort.ListRequest(context, token, 2)));
            ids.addAll(page.items().stream().map(MeetingSummary::meetingId).toList());
            token = page.nextPageToken();
            pageCount++;
            assertTrue(pageCount <= 3, "pagination must make progress");
        } while (token != null);

        assertEquals(List.of(
                "meeting-newer",
                "meeting-tie-c",
                "meeting-tie-b",
                "meeting-tie-a",
                "meeting-older"), ids);
        assertEquals(ids.size(), Set.copyOf(ids).size());
        assertEquals(3, pageCount);
    }

    @Test
    void tokenRejectsTamperCrossSubjectCrossDeploymentAndWrongSecret() throws Exception {
        insertOwned(fixture("meeting-b", DEPLOYMENT, SUBJECT, BASE.plusSeconds(1)));
        insertOwned(fixture("meeting-a", DEPLOYMENT, SUBJECT, BASE));
        MeetingCommandContext owner = context(DEPLOYMENT, SUBJECT);
        JdbcMeetingQueryPort query = query(SECRET);
        MeetingQueryPort.ListPage first = assertInstanceOf(
                MeetingQueryPort.ListPage.class,
                query.list(new MeetingQueryPort.ListRequest(owner, null, 1)));
        String token = first.nextPageToken();
        assertNotNull(token);

        int index = token.charAt(0) == 'A' ? 1 : 0;
        String tampered = token.substring(0, index)
                + (token.charAt(index) == 'A' ? 'B' : 'A')
                + token.substring(index + 1);

        assertInvalidToken(query, owner, tampered);
        assertInvalidToken(query, context(DEPLOYMENT, "subject-other"), token);
        assertInvalidToken(query, context("deployment-other", SUBJECT), token);
        assertInvalidToken(query(secret((byte) 0x72)), owner, token);
        assertInvalidToken(query, owner, "not-a-token");
    }

    @Test
    void aTokenIsStableAcrossNewQueryPortInstancesUsingTheSameDeploymentSecret()
            throws Exception {
        insertOwned(fixture("meeting-b", DEPLOYMENT, SUBJECT, BASE.plusSeconds(1)));
        insertOwned(fixture("meeting-a", DEPLOYMENT, SUBJECT, BASE));
        MeetingCommandContext context = context(DEPLOYMENT, SUBJECT);
        MeetingQueryPort.ListPage first = assertInstanceOf(
                MeetingQueryPort.ListPage.class,
                query(SECRET).list(new MeetingQueryPort.ListRequest(context, null, 1)));

        MeetingQueryPort.ListPage second = assertInstanceOf(
                MeetingQueryPort.ListPage.class,
                query(Arrays.copyOf(SECRET, SECRET.length)).list(
                        new MeetingQueryPort.ListRequest(
                                context, first.nextPageToken(), 1)));

        assertEquals(List.of("meeting-a"),
                second.items().stream().map(MeetingSummary::meetingId).toList());
        assertNull(second.nextPageToken());
    }

    private static void assertInvalidToken(
            JdbcMeetingQueryPort query,
            MeetingCommandContext context,
            String token) {
        MeetingQueryPort.ListConflict conflict = assertInstanceOf(
                MeetingQueryPort.ListConflict.class,
                query.list(new MeetingQueryPort.ListRequest(context, token, 1)));
        assertEquals(MeetingQueryPort.ListConflictReason.INVALID_PAGE_TOKEN,
                conflict.reason());
        assertFalse(conflict.toString().contains(token));
    }

    private JdbcMeetingQueryPort query(byte[] secret) {
        return new JdbcMeetingQueryPort(dataSource, secret);
    }

    private void insertLegacy(String meetingId, String deployment, Instant createdAt) {
        new JdbcMeetingRepository(dataSource).save(
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        meetingId,
                        deployment,
                        "session-" + meetingId,
                        "profile-reference",
                        1,
                        MeetingState.RUNNING,
                        createdAt,
                        createdAt));
    }

    private void insertOwned(MeetingFixture fixture) throws Exception {
        String grantId = "grant-" + fixture.meetingId();
        String reservationId = "reservation-" + fixture.meetingId();
        Instant expiresAt = fixture.createdAt().plusSeconds(86_400);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO meeting (
                            meeting_id, deployment_scope, client_session_id, subject_scope,
                            grant_id, grant_expires_at, usage_reservation_id,
                            profile_id, profile_revision, state, aggregate_version,
                            input_state, finalization_receipt_id, manifest_state,
                            created_at, updated_at, metadata)
                        VALUES (?, ?, ?, ?, ?, ?, NULL, 'profile-reference', 1, ?, 0,
                                ?, ?, ?, ?, ?, '{}'::jsonb)
                        """)) {
                    statement.setString(1, fixture.meetingId());
                    statement.setString(2, fixture.deploymentScope());
                    statement.setString(3, "session-" + fixture.meetingId());
                    statement.setString(4, fixture.subjectScope());
                    statement.setString(5, grantId);
                    statement.setTimestamp(6, Timestamp.from(expiresAt));
                    statement.setString(7, fixture.state().name());
                    statement.setString(8, fixture.inputState().name());
                    statement.setString(9, fixture.receipt());
                    statement.setString(10, fixture.manifestStatus().name());
                    statement.setTimestamp(11, Timestamp.from(fixture.createdAt()));
                    statement.setTimestamp(12, Timestamp.from(fixture.updatedAt()));
                    assertEquals(1, statement.executeUpdate());
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO usage_reservation (
                            reservation_id, status, created_at, updated_at,
                            record_format, deployment_scope, subject_scope, grant_id,
                            expected_meeting_id, bound_meeting_id, bound_at,
                            usage_unit, reserved_amount, expires_at, version, lease_version)
                        VALUES (?, 'RESERVED', ?, ?, 'V3', ?, ?, ?, ?, ?, ?,
                                'MEETING_DURATION_MS', 60000, ?, 1, 0)
                        """)) {
                    statement.setString(1, reservationId);
                    statement.setTimestamp(2, Timestamp.from(fixture.createdAt()));
                    statement.setTimestamp(3, Timestamp.from(fixture.createdAt()));
                    statement.setString(4, fixture.deploymentScope());
                    statement.setString(5, fixture.subjectScope());
                    statement.setString(6, grantId);
                    statement.setString(7, fixture.meetingId());
                    statement.setString(8, fixture.meetingId());
                    statement.setTimestamp(9, Timestamp.from(fixture.createdAt()));
                    statement.setTimestamp(10, Timestamp.from(expiresAt));
                    assertEquals(1, statement.executeUpdate());
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE meeting
                           SET usage_reservation_id = ?
                         WHERE meeting_id = ?
                        """)) {
                    statement.setString(1, reservationId);
                    statement.setString(2, fixture.meetingId());
                    assertEquals(1, statement.executeUpdate());
                }
                connection.commit();
            } catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    /**
     * 插入数据库约束允许、但无法构造完整领域 Snapshot 的非所有者记录。详情
     * 查询必须先执行原始归属 Guard 并返回 AccessDenied，不能因为随后解析这些
     * Grant 字段会失败而向当前主体暴露 SNAPSHOT_INCONSISTENT。
     */
    private void insertMalformedNonOwner(
            String meetingId,
            String deployment,
            String subject,
            boolean legacyWithUnexpectedGrant) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting (
                         meeting_id, deployment_scope, client_session_id, subject_scope,
                         grant_id, grant_expires_at, usage_reservation_id,
                         profile_id, profile_revision, state, aggregate_version,
                         input_state, finalization_receipt_id, manifest_state,
                         created_at, updated_at, metadata)
                     VALUES (?, ?, ?, ?, ?, ?, NULL,
                             'profile-reference', 1, 'RUNNING', 0,
                             'INPUT_OPEN', NULL, 'NOT_STARTED', ?, ?, '{}'::jsonb)
                     """)) {
            statement.setString(1, meetingId);
            statement.setString(2, deployment);
            statement.setString(3, "session-" + meetingId);
            statement.setString(4, subject);
            statement.setString(5,
                    legacyWithUnexpectedGrant ? "unexpected-legacy-grant" : null);
            statement.setTimestamp(6,
                    legacyWithUnexpectedGrant
                            ? Timestamp.from(BASE.plusSeconds(86_400)) : null);
            statement.setTimestamp(7, Timestamp.from(BASE));
            statement.setTimestamp(8, Timestamp.from(BASE));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static MeetingFixture fixture(
            String meetingId,
            String deployment,
            String subject,
            Instant createdAt) {
        return new MeetingFixture(
                meetingId,
                deployment,
                subject,
                MeetingState.RUNNING,
                InputState.INPUT_OPEN,
                null,
                ManifestStatus.NOT_STARTED,
                createdAt,
                createdAt.plusSeconds(1));
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

    private static byte[] secret(byte fill) {
        byte[] secret = new byte[32];
        Arrays.fill(secret, fill);
        return secret;
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private record MeetingFixture(
            String meetingId,
            String deploymentScope,
            String subjectScope,
            MeetingState state,
            InputState inputState,
            String receipt,
            ManifestStatus manifestStatus,
            Instant createdAt,
            Instant updatedAt) {
    }
}
