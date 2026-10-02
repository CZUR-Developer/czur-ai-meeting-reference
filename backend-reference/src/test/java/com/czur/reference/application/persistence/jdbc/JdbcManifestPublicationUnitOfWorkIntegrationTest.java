package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.ManifestPublicationUnitOfWork;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manifest 发布工作单元的 PostgreSQL 16 事务门禁。
 *
 * <p>测试只使用本用例创建的随机 schema；它验证 meeting 状态、Manifest revision、
 * manifest_state、aggregate_version 和 updated_at 的同事务提交，以及首版/后续
 * revision 的重放和状态边界。不会访问既有容器或数据卷。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcManifestPublicationUnitOfWorkIntegrationTest {
    private static final Instant CREATED = Instant.parse("2026-09-21T08:00:00Z");
    private static final Instant CLOCK_VALUE = Instant.parse("2026-09-21T09:00:00.123456789Z");
    private static final String DEPLOYMENT = "publication-deployment";
    private static final String SUBJECT = "publication-subject";
    private static final String SESSION = "publication-session";
    private static final String PROFILE = "meeting-minutes/v1";
    private static final int PROFILE_REVISION = 3;

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createFreshSchema() throws Exception {
        adminDataSource = postgres(null);
        schema = "publication_uow_" + UUID.randomUUID().toString().replace("-", "");
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
    void beginPublishAndReplayCommitAllFactsTogether() throws Exception {
        String meetingId = "mtg_publication_closed_loop";
        createFinalizingMeeting(meetingId);
        ManifestPublicationUnitOfWork.ExpectedMeetingFacts expected = expectedFacts();
        JdbcManifestPublicationUnitOfWork port = port();

        assertInstanceOf(ManifestPublicationUnitOfWork.BeginApplied.class,
                port.begin(new ManifestPublicationUnitOfWork.BeginRequest(meetingId, expected)));
        assertRow(meetingId, MeetingState.FINALIZING, ManifestStatus.GENERATING, 1, 0);
        assertEquals(0L, revisionCount(meetingId));

        assertInstanceOf(ManifestPublicationUnitOfWork.BeginReplayed.class,
                port.begin(new ManifestPublicationUnitOfWork.BeginRequest(meetingId, expected)));
        assertRow(meetingId, MeetingState.FINALIZING, ManifestStatus.GENERATING, 1, 0);

        MeetingManifest first = manifest(meetingId, 1, ManifestStatus.READY);
        ManifestPublicationUnitOfWork.PublishRequest publish =
                new ManifestPublicationUnitOfWork.PublishRequest(meetingId, expected, first);
        ManifestPublicationUnitOfWork.PublishResult applied = port.publish(publish);
        ManifestPublicationUnitOfWork.PublishApplied result = assertInstanceOf(
                ManifestPublicationUnitOfWork.PublishApplied.class, applied);
        assertEquals(ManifestStatus.READY, result.manifestState());
        assertRow(meetingId, MeetingState.READY, ManifestStatus.READY, 2, 1);

        assertInstanceOf(ManifestPublicationUnitOfWork.PublishReplayed.class,
                port.publish(publish));
        assertRow(meetingId, MeetingState.READY, ManifestStatus.READY, 2, 1);

        MeetingManifest second = manifest(meetingId, 2, ManifestStatus.READY);
        ManifestPublicationUnitOfWork.PublishResult secondResult = port.publish(
                new ManifestPublicationUnitOfWork.PublishRequest(meetingId, expected, second));
        assertInstanceOf(ManifestPublicationUnitOfWork.PublishApplied.class, secondResult);
        assertRow(meetingId, MeetingState.READY, ManifestStatus.READY, 3, 2);
    }

    @Test
    void identityProfileInputAndCompletionStateMismatchesFailClosed() throws Exception {
        String meetingId = "mtg_publication_mismatch";
        createFinalizingMeeting(meetingId);
        JdbcManifestPublicationUnitOfWork port = port();
        ManifestPublicationUnitOfWork.ExpectedMeetingFacts expected = expectedFacts();

        ManifestPublicationUnitOfWork.ExpectedMeetingFacts wrongIdentity =
                new ManifestPublicationUnitOfWork.ExpectedMeetingFacts(
                        DEPLOYMENT, "other-subject", SESSION, PROFILE, PROFILE_REVISION,
                        FinalizationFact.accepted("receipt-publication"));
        assertConflict(port.begin(new ManifestPublicationUnitOfWork.BeginRequest(
                meetingId, wrongIdentity)), ManifestPublicationUnitOfWork.ConflictReason.IDENTITY_MISMATCH);
        assertRow(meetingId, MeetingState.FINALIZING, ManifestStatus.NOT_STARTED, 0, 0);

        assertInstanceOf(ManifestPublicationUnitOfWork.BeginApplied.class,
                port.begin(new ManifestPublicationUnitOfWork.BeginRequest(meetingId, expected)));
        MeetingManifest profileMismatch = new MeetingManifest(
                "0.1", meetingId, 1,
                new MeetingManifest.Profile("transcript-only/v1", 1),
                "Publication test", ManifestStatus.READY,
                CREATED, CREATED.plusSeconds(300), 300_000,
                new MeetingManifest.Languages("zh-CN", List.of("en-US")),
                List.of(), List.of());
        assertConflict(port.publish(new ManifestPublicationUnitOfWork.PublishRequest(
                        meetingId, expected, profileMismatch)),
                ManifestPublicationUnitOfWork.ConflictReason.PROFILE_MISMATCH);
        ManifestPublicationUnitOfWork.ExpectedMeetingFacts wrongInput =
                new ManifestPublicationUnitOfWork.ExpectedMeetingFacts(
                        DEPLOYMENT, SUBJECT, SESSION, PROFILE, PROFILE_REVISION,
                        FinalizationFact.accepted("other-receipt"));
        assertConflict(port.publish(new ManifestPublicationUnitOfWork.PublishRequest(
                        meetingId, wrongInput, manifest(meetingId, 1, ManifestStatus.READY))),
                ManifestPublicationUnitOfWork.ConflictReason.INPUT_MISMATCH);
        assertEquals(0L, revisionCount(meetingId));
        assertRow(meetingId, MeetingState.FINALIZING, ManifestStatus.GENERATING, 1, 0);
    }

    @Test
    void laterRevisionMustBeLatestPlusOneAndKeepCompletionState() throws Exception {
        String meetingId = "mtg_publication_revision_rules";
        createFinalizingMeeting(meetingId);
        JdbcManifestPublicationUnitOfWork port = port();
        ManifestPublicationUnitOfWork.ExpectedMeetingFacts expected = expectedFacts();
        port.begin(new ManifestPublicationUnitOfWork.BeginRequest(meetingId, expected));
        port.publish(new ManifestPublicationUnitOfWork.PublishRequest(
                meetingId, expected, manifest(meetingId, 1, ManifestStatus.READY)));

        assertConflict(port.publish(new ManifestPublicationUnitOfWork.PublishRequest(
                        meetingId, expected, manifest(meetingId, 3, ManifestStatus.READY))),
                ManifestPublicationUnitOfWork.ConflictReason.REVISION_CONFLICT);
        assertConflict(port.publish(new ManifestPublicationUnitOfWork.PublishRequest(
                        meetingId, expected, manifest(meetingId, 2, ManifestStatus.READY_WITH_WARNINGS))),
                ManifestPublicationUnitOfWork.ConflictReason.REVISION_CONFLICT);
        assertRow(meetingId, MeetingState.READY, ManifestStatus.READY, 2, 1);
    }

    private JdbcManifestPublicationUnitOfWork port() {
        return new JdbcManifestPublicationUnitOfWork(
                dataSource,
                Clock.fixed(CLOCK_VALUE, ZoneOffset.UTC));
    }

    private ManifestPublicationUnitOfWork.ExpectedMeetingFacts expectedFacts() {
        return new ManifestPublicationUnitOfWork.ExpectedMeetingFacts(
                DEPLOYMENT,
                SUBJECT,
                SESSION,
                PROFILE,
                PROFILE_REVISION,
                FinalizationFact.accepted("receipt-publication"));
    }

    private void createFinalizingMeeting(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement reservation = connection.prepareStatement("""
                     INSERT INTO usage_reservation (
                         reservation_id, deployment_scope, subject_scope, grant_id,
                         expected_meeting_id, usage_unit, reserved_amount, expires_at,
                         version, lease_version, record_format, status, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, 'MEETING_DURATION_MS', 1000, ?, 0, 0, 'V3',
                             'RESERVED', ?, ?)
                     """)) {
                reservation.setString(1, "res-" + meetingId);
                reservation.setString(2, DEPLOYMENT);
                reservation.setString(3, SUBJECT);
                reservation.setString(4, "grant-publication");
                reservation.setString(5, meetingId);
                reservation.setObject(6, java.sql.Timestamp.from(CREATED.plusSeconds(3600)));
                reservation.setObject(7, java.sql.Timestamp.from(CREATED));
                reservation.setObject(8, java.sql.Timestamp.from(CREATED));
                assertEquals(1, reservation.executeUpdate());
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting (
                         meeting_id, deployment_scope, client_session_id, subject_scope,
                         grant_id, grant_expires_at, usage_reservation_id,
                         profile_id, profile_revision, state, aggregate_version,
                         finalization_receipt_id, created_at, updated_at, metadata,
                         input_state, manifest_state)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'FINALIZING', 0, ?, ?, ?, '{}'::jsonb,
                             'INPUT_ACCEPTED', 'NOT_STARTED')
                     """)) {
            statement.setString(1, meetingId);
            statement.setString(2, DEPLOYMENT);
            statement.setString(3, SESSION);
            statement.setString(4, SUBJECT);
            statement.setString(5, "grant-publication");
            statement.setObject(6, java.sql.Timestamp.from(CREATED.plusSeconds(3600)));
            statement.setString(7, "res-" + meetingId);
            statement.setString(8, PROFILE);
            statement.setInt(9, PROFILE_REVISION);
            statement.setString(10, "receipt-publication");
            statement.setObject(11, java.sql.Timestamp.from(CREATED));
            statement.setObject(12, java.sql.Timestamp.from(CREATED));
            assertEquals(1, statement.executeUpdate());
            }
            try (PreparedStatement bind = connection.prepareStatement("""
                     UPDATE usage_reservation
                        SET bound_meeting_id=?, bound_at=?, version=1
                      WHERE reservation_id=?
                     """)) {
                bind.setString(1, meetingId);
                bind.setObject(2, java.sql.Timestamp.from(CREATED));
                bind.setString(3, "res-" + meetingId);
                assertEquals(1, bind.executeUpdate());
            }
            connection.commit();
        }
    }

    private void assertRow(
            String meetingId,
            MeetingState state,
            ManifestStatus manifestState,
            long version,
            long revisionCount) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT state, manifest_state, aggregate_version
                       FROM meeting WHERE meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(state.name(), row.getString(1));
                assertEquals(manifestState.name(), row.getString(2));
                assertEquals(version, row.getLong(3));
            }
        }
        assertEquals(revisionCount, revisionCount(meetingId));
    }

    private long revisionCount(String meetingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM meeting_manifest_revision WHERE meeting_id=?")) {
            statement.setString(1, meetingId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getLong(1);
            }
        }
    }

    private static void assertConflict(
            Object result,
            ManifestPublicationUnitOfWork.ConflictReason expected) {
        ManifestPublicationUnitOfWork.Conflict conflict = assertInstanceOf(
                ManifestPublicationUnitOfWork.Conflict.class, result);
        assertEquals(expected, conflict.reason());
    }

    private static MeetingManifest manifest(
            String meetingId, int revision, ManifestStatus status) {
        return new MeetingManifest(
                "0.1",
                meetingId,
                revision,
                new MeetingManifest.Profile(PROFILE, PROFILE_REVISION),
                "Publication test",
                status,
                CREATED,
                CREATED.plusSeconds(300),
                300_000,
                new MeetingManifest.Languages("zh-CN", List.of("en-US")),
                List.of(),
                List.of());
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
