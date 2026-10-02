package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandEventFactory;
import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.IdempotencyStore;
import com.czur.reference.application.port.OutboxStore;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageReservation;
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
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-T12 5B1 connection-scoped 持久化原语的真实 PostgreSQL 16 门禁。
 *
 * <p>测试只手工组合底层原语以验证事务性质，不提供生产事务编排；完整的
 * {@code JdbcOwnedMeetingCreationUnitOfWork} 留给 5B2。每个用例使用独立 schema，
 * 结束后删除，不触碰调用方 public schema 或其它测试事实。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcOwnedMeetingCreationPrimitivesIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-17T11:00:00Z");
    private static final String DEPLOYMENT = "owned-primitives-deployment";
    private static final String SUBJECT = "owned-primitives-subject";

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createSchemaAndMigrate() throws Exception {
        adminDataSource = dataSource(null);
        schema = "owned_primitives_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
        }
        dataSource = dataSource(schema);
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
    void bindUsesStrictExpiryCasAndFullReservationFacts() throws Exception {
        UsageReservation reservation = insertReservation("strict", NOW.plusSeconds(60));

        try (Connection connection = transaction()) {
            assertFalse(JdbcUsageReservationStore.bindForOwnedMeeting(
                    connection, reservation, reservation.expiresAt()));
            assertFalse(lock(connection, reservation).isBound());
            connection.rollback();
        }

        Instant lastValidMicrosecond = reservation.expiresAt().minusNanos(1_000);
        try (Connection connection = transaction()) {
            assertTrue(JdbcUsageReservationStore.bindForOwnedMeeting(
                    connection, reservation, lastValidMicrosecond));
            UsageReservation bound = lock(connection, reservation);
            assertTrue(bound.isBound());
            assertEquals(lastValidMicrosecond, bound.boundAt());
            assertEquals(1, bound.version());

            // 相同旧快照不能第二次命中，证明 version 与 unbound 条件共同形成 CAS。
            assertFalse(JdbcUsageReservationStore.bindForOwnedMeeting(
                    connection, reservation, lastValidMicrosecond));
            connection.rollback();
        }

        UsageReservation wrongGrant = copyWithGrant(reservation, "other-grant");
        try (Connection connection = transaction()) {
            assertFalse(JdbcUsageReservationStore.bindForOwnedMeeting(
                    connection, wrongGrant, NOW));
            assertFalse(lock(connection, reservation).isBound());
            connection.rollback();
        }
    }

    @Test
    void activeLeaseBlocksBindAndExpiredLeaseIsClearedWithoutResettingFence() throws Exception {
        UsageReservation reservation = insertReservation("lease", NOW.plusSeconds(120));
        Instant leaseUntil = NOW.plusSeconds(30);
        setLease(reservation, leaseUntil, 9);

        try (Connection connection = transaction()) {
            assertFalse(JdbcUsageReservationStore.bindForOwnedMeeting(
                    connection, reservation, leaseUntil.minusNanos(1_000)));
            connection.rollback();
        }

        try (Connection connection = transaction()) {
            assertTrue(JdbcUsageReservationStore.bindForOwnedMeeting(
                    connection, reservation, leaseUntil));
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT lease_owner, lease_token, lease_until, lease_version
                      FROM usage_reservation WHERE reservation_id = ?
                    """)) {
                statement.setString(1, reservation.reservationId());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertNull(rows.getString("lease_owner"));
                    assertNull(rows.getString("lease_token"));
                    assertNull(rows.getTimestamp("lease_until"));
                    assertEquals(9, rows.getLong("lease_version"));
                }
            }
            connection.rollback();
        }
    }

    @Test
    void ownedInsertIsInsertOnlyAndPersistsCompleteOwnership() throws Exception {
        UsageReservation reservation = insertReservation("insert", NOW.plusSeconds(120));
        MeetingAggregate.MeetingSnapshot snapshot = ownedSnapshot(reservation, NOW);

        try (Connection connection = transaction()) {
            assertTrue(JdbcUsageReservationStore.bindForOwnedMeeting(
                    connection, reservation, NOW));
            JdbcMeetingRepository.insertOwned(connection, snapshot);
            connection.commit();
        }

        assertEquals(snapshot, new JdbcMeetingRepository(dataSource)
                .find(snapshot.meetingId()).orElseThrow());
        try (Connection connection = transaction()) {
            assertThrows(SQLException.class,
                    () -> JdbcMeetingRepository.insertOwned(connection, snapshot));
            connection.rollback();
        }
        MeetingAggregate.MeetingSnapshot legacy = MeetingAggregate.MeetingSnapshot.legacyUnowned(
                "legacy", DEPLOYMENT, "legacy-session", "profile", 1,
                MeetingState.CREATED, NOW, NOW);
        try (Connection connection = transaction()) {
            assertThrows(IllegalArgumentException.class,
                    () -> JdbcMeetingRepository.insertOwned(connection, legacy));
            connection.rollback();
        }
    }

    @Test
    void connectionReservePersistsTheExplicitFactTime() throws Exception {
        UsageReservation reservation = insertReservation("idem-time", NOW.plusSeconds(120));
        MeetingAggregate.MeetingSnapshot snapshot = ownedSnapshot(reservation, NOW);
        IdempotencyStore.IdempotencyRecord candidate = idempotency(snapshot, "idem-time");
        Instant explicitTime = NOW.plusNanos(123_456_000);

        try (Connection connection = transaction()) {
            assertEquals(IdempotencyStore.ReservationStatus.ACQUIRED,
                    JdbcIdempotencyStore.reserve(
                            connection, candidate, explicitTime).status());
            connection.commit();
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT created_at FROM idempotency_record
                      WHERE subject_scope = ? AND idempotency_key = ?
                     """)) {
            statement.setString(1, candidate.scope());
            statement.setString(2, candidate.key());
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(explicitTime, rows.getTimestamp(1).toInstant());
            }
        }
    }

    @Test
    void manualPrimitiveCompositionRollsBackWithoutPartialFacts() throws Exception {
        UsageReservation reservation = insertReservation("rollback", NOW.plusSeconds(120));
        MeetingAggregate.MeetingSnapshot snapshot = ownedSnapshot(reservation, NOW);
        IdempotencyStore.IdempotencyRecord candidate = idempotency(snapshot, "rollback");
        var event = MeetingCommandEventFactory.meetingCreated(snapshot, NOW);

        try (Connection connection = transaction()) {
            assertTrue(JdbcUsageReservationStore.bindForOwnedMeeting(
                    connection, reservation, NOW));
            JdbcMeetingRepository.insertOwned(connection, snapshot);
            assertEquals(IdempotencyStore.ReservationStatus.ACQUIRED,
                    JdbcIdempotencyStore.reserve(connection, candidate, NOW).status());
            assertEquals(OutboxStore.AppendStatus.APPENDED,
                    JdbcOutboxStore.append(connection, event).status());
            connection.rollback();
        }

        try (Connection connection = dataSource.getConnection()) {
            assertFalse(lock(connection, reservation).isBound());
            assertTrue(JdbcMeetingRepository.find(connection, snapshot.meetingId()).isEmpty());
            assertTrue(JdbcIdempotencyStore.find(
                    connection, candidate.scope(), candidate.key()).isEmpty());
            assertTrue(JdbcOutboxStore.find(connection, event.eventId()).isEmpty());
        }
    }

    private UsageReservation insertReservation(String suffix, Instant expiresAt)
            throws SQLException {
        MeetingGrant grant = new MeetingGrant(
                "grant-" + suffix, DEPLOYMENT, SUBJECT, "profile-first", 4,
                Set.of("transcript.realtime"), Set.of("transcript.realtime"),
                3_600_000, NOW.minusSeconds(60), expiresAt.plusSeconds(60));
        UsageReservation reservation = UsageReservation.reserved(
                "reservation-" + suffix, grant, "meeting-" + suffix,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                NOW.minusSeconds(30), expiresAt);
        try (Connection connection = dataSource.getConnection()) {
            JdbcUsageReservationStore.insertReservation(connection, reservation);
        }
        return reservation;
    }

    private static MeetingAggregate.MeetingSnapshot ownedSnapshot(
            UsageReservation reservation, Instant createdAt) {
        return MeetingAggregate.MeetingSnapshot.owned(
                reservation.expectedMeetingId(), reservation.deploymentScope(),
                "session-" + reservation.reservationId(), reservation.subjectScope(),
                reservation.grantId(), reservation.expiresAt().plusSeconds(60),
                reservation.reservationId(), "profile-first", 4,
                MeetingState.CREATED, createdAt, createdAt);
    }

    private static IdempotencyStore.IdempotencyRecord idempotency(
            MeetingAggregate.MeetingSnapshot snapshot, String suffix) {
        return new IdempotencyStore.IdempotencyRecord(
                IdempotencyScopeCodec.meetingCreate(DEPLOYMENT, SUBJECT),
                "meeting-key-" + suffix,
                "canonical-digest-" + suffix,
                new MeetingCommandResult(MeetingCommandResult.Operation.CREATED, snapshot));
    }

    private static UsageReservation copyWithGrant(
            UsageReservation source, String grantId) {
        return new UsageReservation(
                source.reservationId(), source.deploymentScope(), source.subjectScope(),
                grantId, source.expectedMeetingId(), source.boundMeetingId(), source.boundAt(),
                source.reservedUsage(), source.actualUsage(), source.createdAt(),
                source.expiresAt(), source.state(), source.version(), source.committedAt(),
                source.cancelledAt(), source.cancellationReasonCode());
    }

    private UsageReservation lock(Connection connection, UsageReservation expected)
            throws SQLException {
        Optional<UsageReservation> current = JdbcUsageReservationStore.lockReservation(
                connection, expected.deploymentScope(), expected.subjectScope(),
                expected.reservationId());
        return current.orElseThrow();
    }

    private void setLease(
            UsageReservation reservation, Instant leaseUntil, long leaseVersion)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE usage_reservation
                        SET lease_owner = 'worker', lease_token = 'lease-token',
                            lease_until = ?, lease_version = ?
                      WHERE reservation_id = ?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(leaseUntil));
            statement.setLong(2, leaseVersion);
            statement.setString(3, reservation.reservationId());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private Connection transaction() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
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
        assertNotNull(migration);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(migration, StandardCharsets.UTF_8));
        }
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

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

}
