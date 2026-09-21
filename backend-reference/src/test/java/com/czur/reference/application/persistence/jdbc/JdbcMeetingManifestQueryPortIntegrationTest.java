package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.manifest.ManifestResultAccessPolicy;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.json.MeetingManifestJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manifest 查询端口的随机 schema PostgreSQL 门禁。
 *
 * <p>每个用例都执行 V001～V007，不接触仓库外已有容器和数据卷。测试覆盖同一
 * statement 的 Pending/Found、genTime 到期、删除撤权以及 latest 损坏；不使用
 * 第二条“先查会议再查 Manifest”的实现来伪造通过结果。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcMeetingManifestQueryPortIntegrationTest {
    private static final List<String> MIGRATIONS = List.of(
            "V001__initial_schema.sql",
            "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql",
            "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql",
            "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql");
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
    private static final Instant START = NOW.minusSeconds(300);
    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createRandomSchema() throws Exception {
        adminDataSource = postgres(null);
        schema = "manifest_query_" + UUID.randomUUID().toString().replace("-", "");
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
    void dropRandomSchema() throws Exception {
        if (adminDataSource == null || schema == null) {
            return;
        }
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schema) + " CASCADE");
        }
    }

    @Test
    void pendingFoundAndExpiredResultUseManifestPortStates() throws Exception {
        String pendingMeeting = "mtg_query_pending";
        createMeeting(pendingMeeting, MeetingState.FINALIZING, ManifestStatus.GENERATING);
        setAccepted(pendingMeeting, ManifestStatus.GENERATING, MeetingState.FINALIZING);

        JdbcMeetingManifestQueryPort port = port(Duration.ofHours(1));
        assertEquals(MeetingManifestPort.Status.PENDING,
                port.get(new MeetingManifestPort.Request(pendingMeeting, -1)).status());

        String readyMeeting = "mtg_query_ready";
        createMeeting(readyMeeting, MeetingState.READY, ManifestStatus.READY);
        setAccepted(readyMeeting, ManifestStatus.READY, MeetingState.READY);
        MeetingManifest ready = manifest(readyMeeting, 1, ManifestStatus.READY);
        insertManifest(ready);
        assertEquals(MeetingManifestPort.Status.FOUND,
                port.get(new MeetingManifestPort.Request(readyMeeting, -1)).status());

        long oldGeneration = NOW.minus(Duration.ofHours(2)).toEpochMilli();
        assertEquals(MeetingManifestPort.Status.ACCESS_DENIED,
                port.get(new MeetingManifestPort.Request(readyMeeting, oldGeneration)).status());
    }

    @Test
    void missingMeetingDoesNotBecomePendingOrFound() {
        assertEquals(MeetingManifestPort.Status.NOT_FOUND,
                port(Duration.ofDays(1)).get(new MeetingManifestPort.Request(
                        "mtg_query_absent", -1)).status());
    }

    @Test
    void deletionPendingRevokesOldManifestImmediately() throws Exception {
        String meetingId = "mtg_query_deleted";
        createMeeting(meetingId, MeetingState.READY, ManifestStatus.READY);
        setAccepted(meetingId, ManifestStatus.READY, MeetingState.READY);
        insertManifest(manifest(meetingId, 1, ManifestStatus.READY));
        setAccepted(meetingId, ManifestStatus.DELETION_PENDING, MeetingState.DELETION_PENDING);

        assertEquals(MeetingManifestPort.Status.ACCESS_DENIED,
                port(Duration.ofDays(1)).get(
                        new MeetingManifestPort.Request(meetingId, -1)).status());
    }

    @Test
    void inconsistentLatestAndCorruptHashFailClosed() throws Exception {
        String meetingId = "mtg_query_corrupt";
        createMeeting(meetingId, MeetingState.READY, ManifestStatus.READY);
        setAccepted(meetingId, ManifestStatus.READY, MeetingState.READY);
        MeetingManifest manifest = manifest(meetingId, 1, ManifestStatus.READY);
        insertManifest(manifest);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     ALTER TABLE meeting_manifest_revision
                       DROP CONSTRAINT meeting_manifest_revision_hash_ck
                     """)) {
            statement.execute();
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting_manifest_revision
                        SET content_sha256 = decode(repeat('00', 32), 'hex')
                      WHERE meeting_id = ?
                     """)) {
            statement.setString(1, meetingId);
            statement.executeUpdate();
        }

        MeetingManifestPort.Result result = port(Duration.ofDays(1)).get(
                new MeetingManifestPort.Request(meetingId, -1));
        assertEquals(MeetingManifestPort.Status.CONFLICT, result.status());
        assertTrue(result instanceof MeetingManifestPort.Conflict);
    }

    @Test
    void malformedCanonicalJsonFailsClosedAsSnapshotConflict() throws Exception {
        String meetingId = "mtg_query_bad_canonical";
        createMeeting(meetingId, MeetingState.READY, ManifestStatus.READY);
        setAccepted(meetingId, ManifestStatus.READY, MeetingState.READY);
        insertManifest(manifest(meetingId, 1, ManifestStatus.READY));
        dropConstraints("meeting_manifest_revision_document_ck",
                "meeting_manifest_revision_hash_ck");
        String malformed = "not-json";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting_manifest_revision
                        SET canonical_json=?, content_sha256=?
                      WHERE meeting_id=?
                     """)) {
            statement.setString(1, malformed);
            statement.setBytes(2, sha256(malformed));
            statement.setString(3, meetingId);
            statement.executeUpdate();
        }

        assertEquals(MeetingManifestPort.Status.CONFLICT,
                port(Duration.ofDays(1)).get(
                        new MeetingManifestPort.Request(meetingId, -1)).status());
    }

    @Test
    void malformedJsonbMirrorFailsClosedAsSnapshotConflict() throws Exception {
        String meetingId = "mtg_query_bad_jsonb";
        createMeeting(meetingId, MeetingState.READY, ManifestStatus.READY);
        setAccepted(meetingId, ManifestStatus.READY, MeetingState.READY);
        insertManifest(manifest(meetingId, 1, ManifestStatus.READY));
        dropConstraints("meeting_manifest_revision_document_ck");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting_manifest_revision
                        SET manifest_json='{"broken":true}'::jsonb
                      WHERE meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            statement.executeUpdate();
        }

        assertEquals(MeetingManifestPort.Status.CONFLICT,
                port(Duration.ofDays(1)).get(
                        new MeetingManifestPort.Request(meetingId, -1)).status());
    }

    @Test
    void profileMismatchIsClosedEvenWhenManifestJsonIsValid() throws Exception {
        String meetingId = "mtg_query_profile_mismatch";
        createMeeting(meetingId, MeetingState.READY, ManifestStatus.READY);
        setAccepted(meetingId, ManifestStatus.READY, MeetingState.READY);
        insertManifest(manifest(meetingId, 1, ManifestStatus.READY));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting SET profile_id='other-profile' WHERE meeting_id=?
                     """)) {
            statement.setString(1, meetingId);
            statement.executeUpdate();
        }

        assertEquals(MeetingManifestPort.Status.CONFLICT,
                port(Duration.ofDays(1)).get(
                        new MeetingManifestPort.Request(meetingId, -1)).status());
    }

    private JdbcMeetingManifestQueryPort port(Duration ttl) {
        return new JdbcMeetingManifestQueryPort(
                dataSource,
                new ManifestResultAccessPolicy(
                        Clock.fixed(NOW, ZoneOffset.UTC), ttl));
    }

    private void createMeeting(String meetingId, MeetingState state, ManifestStatus manifestState)
            throws Exception {
        new JdbcMeetingRepository(dataSource).save(
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        meetingId,
                        "query-test-deployment",
                        "session-" + meetingId,
                        "profile-query",
                        3,
                        state,
                        START,
                        START));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting SET state=?, manifest_state=? WHERE meeting_id=?
                     """)) {
            statement.setString(1, state.name());
            statement.setString(2, manifestState.name());
            statement.setString(3, meetingId);
            statement.executeUpdate();
        }
    }

    private void setAccepted(String meetingId, ManifestStatus manifestState, MeetingState state)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE meeting SET state=?, manifest_state=?, input_state='INPUT_ACCEPTED',
                         finalization_receipt_id=? WHERE meeting_id=?
                     """)) {
            statement.setString(1, state.name());
            statement.setString(2, manifestState.name());
            statement.setString(3, "receipt-" + meetingId);
            statement.setString(4, meetingId);
            statement.executeUpdate();
        }
    }

    private void insertManifest(MeetingManifest manifest) throws Exception {
        String canonical = MeetingManifestJson.encode(manifest);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO meeting_manifest_revision(
                         meeting_id, manifest_revision, previous_revision,
                         canonical_json, manifest_json, content_sha256)
                     VALUES (?, ?, NULL, ?, ?::jsonb, ?)
                     """)) {
            statement.setString(1, manifest.meetingId());
            statement.setInt(2, manifest.manifestRevision());
            statement.setString(3, canonical);
            statement.setString(4, canonical);
            statement.setBytes(5, sha256(canonical));
            statement.executeUpdate();
        }
    }

    private void dropConstraints(String... names) throws Exception {
        StringBuilder sql = new StringBuilder("ALTER TABLE meeting_manifest_revision ");
        for (int index = 0; index < names.length; index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append("DROP CONSTRAINT ").append(names[index]);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql.toString());
        }
    }

    private static MeetingManifest manifest(
            String meetingId, int revision, ManifestStatus status) {
        return new MeetingManifest(
                "0.1", meetingId, revision,
                new MeetingManifest.Profile("profile-query", 3), null, status,
                START, NOW, 300_000,
                new MeetingManifest.Languages("zh-CN", List.of()), List.of(), List.of());
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readMigration(String filename) throws Exception {
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
