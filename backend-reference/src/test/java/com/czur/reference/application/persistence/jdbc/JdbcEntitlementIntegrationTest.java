package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicy;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicyConfig;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.CancelReservationRequest;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.ExpiredReservationStore;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.identity.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 PostgreSQL 16 的 P2-T12 第 4B 门禁。
 *
 * <p>只有显式设置 {@code REFERENCE_TEST_DATABASE_URL} 才运行。每次测试使用独立
 * schema，先执行 V001/V002/V003，再覆盖 reserve 并发赢家、稳定重放、摘要冲突、
 * commit/cancel 状态、lease 双 worker 重领和旧 token fencing。测试结束删除 schema，
 * 不触碰调用方的 public schema、容器或其它数据。</p>
 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcEntitlementIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-17T08:00:00Z");
    private static final String DEPLOYMENT = "jdbc-entitlement-deployment";
    private static final String SUBJECT = "jdbc-entitlement-subject";
    private static final String PROFILE = "jdbc-profile";

    private PGSimpleDataSource adminDataSource;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void createSchemaAndMigrate() throws Exception {
        adminDataSource = dataSource(null);
        schema = "entitlement_test_" + UUID.randomUUID().toString().replace("-", "");
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
    void reserveHasStableReplayConflictAndConcurrentSingleWinner() throws Exception {
        ReferenceDevEntitlementPolicy policy = policyAt(NOW);
        JdbcReferenceDevEntitlementProvider provider = provider(policy, NOW);
        ReserveUsageRequest request = reserveRequest(
                grantAt(NOW.minusSeconds(1), NOW.plusSeconds(599)),
                "reserve-concurrent", "digest-reserve", "meeting-reserve");

        EntitlementProviderResult<UsageOperationResult> first = provider.reserveUsage(request);
        assertTrue(first.isSuccess());
        assertEquals(NOW.plusSeconds(300),
                first.valueOrThrow().reservation().expiresAt());
        AuthorizedProfileSnapshot firstSnapshot =
                first.valueOrThrow().requireAuthorizedProfileSnapshot();
        assertEquals(PROFILE, firstSnapshot.profileId());
        assertEquals(1, firstSnapshot.profileRevision());
        assertEquals(Set.of("transcript.realtime"), firstSnapshot.grantedCapabilities());
        EntitlementProviderResult<UsageOperationResult> replay = provider.reserveUsage(request);
        assertTrue(replay.isSuccess());
        assertEquals(first.valueOrThrow(), replay.valueOrThrow());

        // 模拟进程重建及随后发生的时钟、reservation TTL、Profile、能力和 Grant
        // 变更。相同四元组与摘要必须返回首次持久化快照，不能重新执行当前策略。
        MeetingGrant changedGrant = new MeetingGrant(
                "grant-after-policy-change", DEPLOYMENT, SUBJECT,
                "profile-after-change", 9,
                Set.of("summary.structured"), Set.of("summary.structured"),
                120_000, NOW.minusSeconds(1), NOW.plusSeconds(500));
        ReserveUsageRequest changedRetry = reserveRequest(
                changedGrant, "reserve-concurrent", "digest-reserve", "meeting-after-change");
        ReferenceDevEntitlementPolicy changedPolicy = new ReferenceDevEntitlementPolicy(
                new ReferenceDevEntitlementPolicyConfig(
                        DEPLOYMENT, "profile-after-change", 9,
                        Set.of("summary.structured"), 120_000,
                        Duration.ofMinutes(10), Duration.ofSeconds(30)),
                Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC), new TestIds());
        EntitlementProviderResult<UsageOperationResult> restarted =
                provider(changedPolicy, NOW).reserveUsage(changedRetry);
        assertTrue(restarted.isSuccess());
        assertEquals(first.valueOrThrow(), restarted.valueOrThrow());
        assertEquals(firstSnapshot, restarted.valueOrThrow().requireAuthorizedProfileSnapshot());
        assertEquals(NOW.plusSeconds(300), restarted.valueOrThrow().reservation().expiresAt(),
                "幂等重放必须保留首次截止时间，不能按新时钟和 30 秒 TTL 重算");
        assertEquals(1L, count("SELECT count(*) FROM usage_reservation WHERE record_format='V3'"));
        assertEquals(1L, count("SELECT count(*) FROM usage_operation"));

        ReserveUsageRequest conflict = reserveRequest(request.grant(),
                "reserve-concurrent", "digest-other", "meeting-other");
        assertError(provider.reserveUsage(conflict),
                EntitlementProviderError.Code.IDEMPOTENCY_CONFLICT);
        assertEquals(1L, count("SELECT count(*) FROM usage_reservation WHERE record_format='V3'"));

        // 相同请求在两个数据库连接上同时竞争；唯一四元组只能产生一个结果和一条行。
        ReserveUsageRequest concurrentRequest = reserveRequest(
                grantAt(NOW.minusSeconds(1), NOW.plusSeconds(599)),
                "reserve-race", "digest-race", "meeting-race");
        JdbcReferenceDevEntitlementProvider firstProvider = provider(policyAt(NOW), NOW);
        JdbcReferenceDevEntitlementProvider secondProvider = provider(policyAt(NOW), NOW);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<EntitlementProviderResult<UsageOperationResult>> a = executor.submit(() -> {
                start.await();
                return firstProvider.reserveUsage(concurrentRequest);
            });
            Future<EntitlementProviderResult<UsageOperationResult>> b = executor.submit(() -> {
                start.await();
                return secondProvider.reserveUsage(concurrentRequest);
            });
            start.countDown();
            EntitlementProviderResult<UsageOperationResult> resultA = a.get();
            EntitlementProviderResult<UsageOperationResult> resultB = b.get();
            assertTrue(resultA.isSuccess(), () -> resultA.errorOrThrow().code().name());
            assertTrue(resultB.isSuccess(), () -> resultB.errorOrThrow().code().name());
            assertEquals(resultA.valueOrThrow(), resultB.valueOrThrow());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(2L, count("SELECT count(*) FROM usage_reservation WHERE record_format='V3'"));
        assertEquals(2L, count("SELECT count(*) FROM usage_operation"));
    }

    @Test
    void legacyV1ReserveReplaysAfterRestartButCannotCreateOwnedMeeting() throws Exception {
        JdbcReferenceDevEntitlementProvider provider = provider(policyAt(NOW), NOW);
        ReserveUsageRequest request = reserveRequest(
                grantAt(NOW.minusSeconds(1), NOW.plusSeconds(599)),
                "reserve-legacy-v1", "digest-legacy-v1", "meeting-legacy-v1");
        assertTrue(provider.reserveUsage(request).isSuccess());

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE usage_operation
                        SET result_body = (result_body - 'authorizedProfileSnapshot')
                                          || '{"schemaVersion":1}'::jsonb
                      WHERE deployment_scope = ? AND subject_scope = ?
                        AND operation_scope = ? AND idempotency_key = ?
                     """)) {
            statement.setString(1, DEPLOYMENT);
            statement.setString(2, SUBJECT);
            statement.setString(3, UsageOperationScopes.RESERVE);
            statement.setString(4, request.idempotencyKey());
            assertEquals(1, statement.executeUpdate());
        }

        UsageOperationResult legacy = provider(policyAt(NOW), NOW)
                .reserveUsage(request).valueOrThrow();
        assertFalse(legacy.supportsOwnedMeetingCreation());
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, legacy::requireAuthorizedProfileSnapshot);
        assertEquals(request.grant().expiresAt(), legacy.grantExpiresAt());
    }

    @Test
    void commitAndCancelAreDurableIdempotentAndScopeBound() throws Exception {
        ReferenceDevEntitlementPolicy policy = policyAt(NOW);
        JdbcReferenceDevEntitlementProvider provider = provider(policy, NOW);
        MeetingGrant grant = grantAt(NOW.minusSeconds(1), NOW.plusSeconds(599));
        UsageOperationResult reserved = provider.reserveUsage(reserveRequest(
                grant, "reserve-commit", "digest-commit", "meeting-commit"))
                .valueOrThrow();
        bind(reserved.reservation(), NOW);

        CommitUsageRequest commitRequest = new CommitUsageRequest(
                DEPLOYMENT, SUBJECT, UsageOperationScopes.COMMIT,
                "commit-key", "digest-commit-final", reserved.reservation().reservationId(),
                1, new com.czur.reference.application.port.entitlement.UsageActual(
                        UsageUnit.MEETING_DURATION_MS, 42_000));
        EntitlementProviderResult<UsageOperationResult> committed =
                provider.commitUsage(commitRequest);
        assertTrue(committed.isSuccess(), () -> committed.errorOrThrow().code().name());
        assertEquals(UsageReservation.State.COMMITTED,
                committed.valueOrThrow().reservation().state());
        assertEquals(committed.valueOrThrow(), provider.commitUsage(commitRequest).valueOrThrow());

        UsageOperationResult cancelReservation = provider.reserveUsage(reserveRequest(
                grantAt(NOW.minusSeconds(1), NOW.plusSeconds(599)),
                "reserve-cancel", "digest-cancel", "meeting-cancel")).valueOrThrow();
        CancelReservationRequest cancelRequest = new CancelReservationRequest(
                DEPLOYMENT, SUBJECT, UsageOperationScopes.CANCEL,
                "cancel-key", "digest-cancel-final", cancelReservation.reservation().reservationId(),
                "meeting-cancel", 0, "EXPIRED_UNBOUND");
        EntitlementProviderResult<UsageOperationResult> cancelled =
                provider.cancelReservation(cancelRequest);
        assertTrue(cancelled.isSuccess());
        assertEquals(UsageReservation.State.CANCELLED,
                cancelled.valueOrThrow().reservation().state());
        assertEquals(cancelled.valueOrThrow(), provider.cancelReservation(cancelRequest).valueOrThrow());

        // 相同 Reservation 但其它主体范围不可见；不能泄露真实记录状态。
        CommitUsageRequest wrongSubject = new CommitUsageRequest(
                DEPLOYMENT, "other-subject", UsageOperationScopes.COMMIT,
                "other-key", "other-digest", reserved.reservation().reservationId(),
                1, new com.czur.reference.application.port.entitlement.UsageActual(
                        UsageUnit.MEETING_DURATION_MS, 1));
        assertError(provider.commitUsage(wrongSubject),
                EntitlementProviderError.Code.RESERVATION_NOT_FOUND);
    }

    @Test
    void expiredClaimUsesSkipLockedLeaseVersionAndOldTokenCannotReleaseNewLease()
            throws Exception {
        insertExpired("claim-a", "subject-a", "grant-a", "meeting-a");
        insertExpired("claim-b", "subject-b", "grant-b", "meeting-b");
        ExpiredReservationStore.ClaimRequest firstRequest = new ExpiredReservationStore.ClaimRequest(
                DEPLOYMENT, "worker-a", NOW, Duration.ofSeconds(30), 2);
        JdbcExpiredReservationStore store = new JdbcExpiredReservationStore(
                dataSource, new TestIds());
        ExpiredReservationStore.ClaimBatch first = store.claimExpired(firstRequest);
        assertEquals(2, first.claims().size());
        assertEquals("claim-a", first.claims().get(0).reservation().reservationId());
        assertTrue(store.claimExpired(new ExpiredReservationStore.ClaimRequest(
                DEPLOYMENT, "worker-b", NOW, Duration.ofSeconds(30), 2)).claims().isEmpty());

        ExpiredReservationStore.LeaseToken old = first.claims().get(0).lease();
        assertEquals(ExpiredReservationStore.ReleaseStatus.NOT_OWNER,
                store.release(new ExpiredReservationStore.LeaseToken(
                        old.deploymentScope(), old.subjectScope(), old.reservationId(),
                        old.owner(), "wrong-token", old.claimedAt(), old.expiresAt(),
                        old.leaseVersion()), NOW.plusSeconds(1)).status());
        assertEquals(ExpiredReservationStore.ReleaseStatus.LEASE_EXPIRED,
                store.release(old, old.expiresAt()).status());

        ExpiredReservationStore.ClaimBatch second = store.claimExpired(
                new ExpiredReservationStore.ClaimRequest(
                        DEPLOYMENT, "worker-b", NOW.plusSeconds(31), Duration.ofSeconds(30), 2));
        assertEquals(2, second.claims().size());
        assertTrue(second.claims().get(0).lease().leaseVersion() > old.leaseVersion());
        assertEquals(ExpiredReservationStore.ReleaseStatus.NOT_OWNER,
                store.release(old, NOW.plusSeconds(32)).status());
        assertEquals(ExpiredReservationStore.ReleaseStatus.RELEASED,
                store.release(second.claims().get(0).lease(), NOW.plusSeconds(32)).status());
        assertEquals(ExpiredReservationStore.ReleaseStatus.RELEASED,
                store.release(second.claims().get(1).lease(), NOW.plusSeconds(32)).status());
        assertEquals(0L, count("SELECT count(*) FROM usage_reservation WHERE lease_owner IS NOT NULL"));
    }

    @Test
    void fractionalNumericIsRejectedAsInternalCorruptFact() throws Exception {
        ReferenceDevEntitlementPolicy policy = policyAt(NOW);
        JdbcReferenceDevEntitlementProvider provider = provider(policy, NOW);
        UsageOperationResult reserved = provider.reserveUsage(reserveRequest(
                grantAt(NOW.minusSeconds(1), NOW.plusSeconds(599)),
                "reserve-fraction", "digest-fraction", "meeting-fraction")).valueOrThrow();
        update("UPDATE usage_reservation SET reserved_amount = 1.5 WHERE reservation_id = ?",
                reserved.reservation().reservationId());
        CommitUsageRequest commit = new CommitUsageRequest(
                DEPLOYMENT, SUBJECT, UsageOperationScopes.COMMIT,
                "commit-fraction", "digest-commit-fraction",
                reserved.reservation().reservationId(), 0,
                new com.czur.reference.application.port.entitlement.UsageActual(
                        UsageUnit.MEETING_DURATION_MS, 1));
        assertError(provider.commitUsage(commit), EntitlementProviderError.Code.INTERNAL);
        assertThrowsCorruptNumeric(BigDecimal.valueOf(Long.MAX_VALUE).add(BigDecimal.ONE));
    }

    @Test
    void damagedOperationJsonFailsInternalWithoutReplayingSuccess() {
        ReferenceDevEntitlementPolicy policy = policyAt(NOW);
        JdbcReferenceDevEntitlementProvider provider = provider(policy, NOW);
        ReserveUsageRequest request = reserveRequest(
                grantAt(NOW.minusSeconds(1), NOW.plusSeconds(599)),
                "reserve-corrupt-json", "digest-corrupt-json", "meeting-corrupt-json");
        assertTrue(provider.reserveUsage(request).isSuccess());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE usage_operation
                        SET result_body = '{"broken":true}'::jsonb
                      WHERE deployment_scope = ? AND subject_scope = ?
                        AND operation_scope = ? AND idempotency_key = ?
                     """)) {
            statement.setString(1, DEPLOYMENT);
            statement.setString(2, SUBJECT);
            statement.setString(3, UsageOperationScopes.RESERVE);
            statement.setString(4, "reserve-corrupt-json");
            assertEquals(1, statement.executeUpdate());
        } catch (SQLException failure) {
            throw new IllegalStateException("更新 JSON 损坏夹具失败", failure);
        }
        EntitlementProviderResult<UsageOperationResult> replay = provider.reserveUsage(request);
        assertError(replay, EntitlementProviderError.Code.INTERNAL);
    }

    @Test
    void damagedGrantExpiryFailsInternalWithoutLeakingSnapshotDetails() throws Exception {
        ReferenceDevEntitlementPolicy policy = policyAt(NOW);
        JdbcReferenceDevEntitlementProvider provider = provider(policy, NOW);
        ReserveUsageRequest request = reserveRequest(
                grantAt(NOW.minusSeconds(1), NOW.plusSeconds(599)),
                "reserve-corrupt-expiry", "digest-corrupt-expiry", "meeting-corrupt-expiry");
        assertTrue(provider.reserveUsage(request).isSuccess());

        updateResultBody("jsonb_set(result_body, '{grantExpiresAt}', 'null'::jsonb)",
                request.idempotencyKey());
        assertError(provider.reserveUsage(request), EntitlementProviderError.Code.INTERNAL);

        updateResultBody("jsonb_set(result_body, '{grantExpiresAt}', "
                        + "to_jsonb('2026-09-17T04:04:59.123456789Z'::text))",
                request.idempotencyKey());
        assertError(provider.reserveUsage(request), EntitlementProviderError.Code.INTERNAL);
    }

    private static void assertThrowsCorruptNumeric(BigDecimal value) {
        try {
            JdbcUsageReservationStore.exactLong(value, "test");
            throw new AssertionError("expected non-exact NUMERIC to be rejected");
        } catch (JdbcUsageReservationStore.CorruptUsageDataException expected) {
            // 固定异常类型即可；不把数值正文带入业务错误。
        }
    }

    private JdbcReferenceDevEntitlementProvider provider(
            ReferenceDevEntitlementPolicy policy, Instant now) {
        return new JdbcReferenceDevEntitlementProvider(dataSource, policy,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private ReferenceDevEntitlementPolicy policyAt(Instant now) {
        return new ReferenceDevEntitlementPolicy(
                new ReferenceDevEntitlementPolicyConfig(
                        DEPLOYMENT, PROFILE, 1,
                        Set.of("transcript.realtime"), 3_600_000,
                        Duration.ofMinutes(10), Duration.ofMinutes(5)),
                Clock.fixed(now, ZoneOffset.UTC), new TestIds());
    }

    private static MeetingGrant grantAt(Instant issuedAt, Instant expiresAt) {
        return new MeetingGrant(
                "grant-input-" + UUID.randomUUID(), DEPLOYMENT, SUBJECT, PROFILE, 1,
                Set.of("transcript.realtime"), Set.of("transcript.realtime"),
                3_600_000, issuedAt, expiresAt);
    }

    private static ReserveUsageRequest reserveRequest(
            MeetingGrant grant, String key, String digest, String meetingId) {
        return new ReserveUsageRequest(
                DEPLOYMENT, SUBJECT, UsageOperationScopes.RESERVE, key, digest, grant,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000), meetingId);
    }

    private void bind(UsageReservation reservation, Instant boundAt) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insertMeeting = connection.prepareStatement("""
                     INSERT INTO meeting (
                         meeting_id, deployment_scope, client_session_id, subject_scope,
                         profile_id, profile_revision, state, aggregate_version,
                         created_at, updated_at, metadata)
                     VALUES (?, ?, ?, ?, ?, 1, 'CREATED', 0, ?, ?, '{}'::jsonb)
                     """)) {
            insertMeeting.setString(1, reservation.expectedMeetingId());
            insertMeeting.setString(2, reservation.deploymentScope());
            insertMeeting.setString(3, "session-" + reservation.expectedMeetingId());
            insertMeeting.setString(4, reservation.subjectScope());
            insertMeeting.setString(5, PROFILE);
            insertMeeting.setTimestamp(6, java.sql.Timestamp.from(boundAt));
            insertMeeting.setTimestamp(7, java.sql.Timestamp.from(boundAt));
            assertEquals(1, insertMeeting.executeUpdate());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE usage_reservation
                        SET bound_meeting_id = expected_meeting_id, bound_at = ?,
                            version = 1, updated_at = ?
                      WHERE deployment_scope = ? AND subject_scope = ?
                        AND reservation_id = ? AND record_format = 'V3'
                     """)) {
            statement.setTimestamp(1, java.sql.Timestamp.from(boundAt));
            statement.setTimestamp(2, java.sql.Timestamp.from(boundAt));
            statement.setString(3, reservation.deploymentScope());
            statement.setString(4, reservation.subjectScope());
            statement.setString(5, reservation.reservationId());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void insertExpired(String reservationId, String subject, String grantId,
                               String meetingId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO usage_reservation (
                         reservation_id, status, created_at, updated_at, record_format,
                         deployment_scope, subject_scope, grant_id, expected_meeting_id,
                         usage_unit, reserved_amount, expires_at, version, lease_version)
                     VALUES (?, 'RESERVED', ?, ?, 'V3', ?, ?, ?, ?,
                             'MEETING_DURATION_MS', 60000, ?, 0, 0)
                     """)) {
            Instant created = NOW.minusSeconds(120);
            statement.setString(1, reservationId);
            statement.setTimestamp(2, java.sql.Timestamp.from(created));
            statement.setTimestamp(3, java.sql.Timestamp.from(created));
            statement.setString(4, DEPLOYMENT);
            statement.setString(5, subject);
            statement.setString(6, grantId);
            statement.setString(7, meetingId);
            statement.setTimestamp(8, java.sql.Timestamp.from(NOW.minusSeconds(60)));
            statement.executeUpdate();
        }
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private void update(String sql, String value) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void updateResultBody(String expression, String idempotencyKey) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE usage_operation SET result_body = " + expression
                             + " WHERE deployment_scope = ? AND subject_scope = ?"
                             + " AND operation_scope = ? AND idempotency_key = ?")) {
            statement.setString(1, DEPLOYMENT);
            statement.setString(2, SUBJECT);
            statement.setString(3, UsageOperationScopes.RESERVE);
            statement.setString(4, idempotencyKey);
            assertEquals(1, statement.executeUpdate());
        }
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
        assertNotNull(migration);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
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

    private static <T> void assertError(
            EntitlementProviderResult<T> result, EntitlementProviderError.Code code) {
        assertFalse(result.isSuccess());
        assertEquals(code, result.errorOrThrow().code());
    }

    private static final class TestIds implements EntitlementIdGenerator {
        @Override
        public String newGrantId() {
            return "grant-generated-" + UUID.randomUUID();
        }

        @Override
        public String newReservationId() {
            return "reservation-generated-" + UUID.randomUUID();
        }

        @Override
        public String newLeaseToken() {
            return "lease-generated-" + UUID.randomUUID();
        }
    }
}
