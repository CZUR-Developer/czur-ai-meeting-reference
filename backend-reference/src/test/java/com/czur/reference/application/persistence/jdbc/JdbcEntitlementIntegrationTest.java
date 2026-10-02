package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicy;
import com.czur.reference.application.entitlement.referencedev.ReferenceDevEntitlementPolicyConfig;
import com.czur.reference.application.command.CompleteMeetingReserveFacts;
import com.czur.reference.application.command.CreateMeetingRequestFact;
import com.czur.reference.application.command.CreateMeetingRequestSnapshot;
import com.czur.reference.application.command.MeetingRequestCanonicalizer;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.CancelReservationRequest;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.ExpiredReservationStore;
import com.czur.reference.application.port.entitlement.LeaseAwareCancelReservationRequest;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageReserveLookup;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.model.AudioProfile;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void completeCreateReserveKeyCoexistsWithLegacyAndReplaysPersistedWinner()
            throws Exception {
        String externalKey = "shared-external-key-12345";
        CreateMeetingRequestFact complete = completeRequestFact(externalKey, null);
        String internalKey = CompleteMeetingReserveFacts.internalReserveKey(complete);
        JdbcReferenceDevEntitlementProvider provider = provider(policyAt(NOW), NOW);

        // V003 操作范围保持 usage.reserve.v1；通常的相同外部键不能复用旧结果。
        var legacy = provider.reserveUsage(reserveRequest(
                grantAt(NOW.minusSeconds(1), NOW.plusSeconds(599)), externalKey,
                "legacy-digest", "mtg_legacy")).valueOrThrow();
        var firstGrant = grantAt(NOW.minusSeconds(1), NOW.plusSeconds(599));
        var firstRequest = reserveRequest(firstGrant, internalKey,
                complete.canonicalRequestDigest(), "mtg_v2_winner");
        var first = provider.reserveUsage(firstRequest).valueOrThrow();
        assertNotEquals(legacy.reservation().reservationId(), first.reservation().reservationId());
        assertEquals(2L, count("SELECT count(*) FROM usage_operation"));
        assertEquals(2L, count("SELECT count(*) FROM usage_reservation"));
        assertTrue(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                first, complete, DEPLOYMENT, SUBJECT));

        // 顺序重放也不能拿新 Grant 或候选会议 ID 否定已经保存的首次赢家。
        MeetingGrant loserGrant = new MeetingGrant("new-grant", DEPLOYMENT, SUBJECT,
                PROFILE, 1, Set.of("transcript.realtime"),
                Set.of("transcript.realtime"), 3_600_000,
                NOW.minusSeconds(1), NOW.plusSeconds(300));
        var loser = provider(policyAt(NOW.plusSeconds(20)), NOW.plusSeconds(20))
                .reserveUsage(reserveRequest(loserGrant, internalKey,
                        complete.canonicalRequestDigest(), "mtg_loser_candidate"))
                .valueOrThrow();
        assertEquals(first, loser);
        assertEquals("mtg_v2_winner", loser.reservation().expectedMeetingId());
        assertTrue(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                loser, complete, DEPLOYMENT, SUBJECT));

        var lookedUp = new JdbcUsageReserveLookup(dataSource).lookup(
                new UsageReserveLookup.Request(DEPLOYMENT, SUBJECT,
                        internalKey, complete.canonicalRequestDigest()));
        assertTrue(lookedUp instanceof UsageReserveLookup.Replayed);
        assertEquals(first, ((UsageReserveLookup.Replayed) lookedUp).result());

        CreateMeetingRequestFact changedBody = completeRequestFact(
                externalKey, "different title");
        assertEquals(internalKey, CompleteMeetingReserveFacts.internalReserveKey(changedBody));
        assertError(provider.reserveUsage(reserveRequest(loserGrant, internalKey,
                        changedBody.canonicalRequestDigest(), "mtg_conflict")),
                EntitlementProviderError.Code.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void completeCreateTwoObservedFirstMissesConvergeOnOnePersistedReserve()
            throws Exception {
        CreateMeetingRequestFact complete = completeRequestFact(
                "concurrent-external-key-12345", null);
        String internalKey = CompleteMeetingReserveFacts.internalReserveKey(complete);
        String digest = complete.canonicalRequestDigest();
        MeetingGrant grantA = grantAt(NOW.minusSeconds(1), NOW.plusSeconds(599));
        MeetingGrant grantB = new MeetingGrant("different-grant", DEPLOYMENT, SUBJECT,
                PROFILE, 1, Set.of("transcript.realtime"),
                Set.of("transcript.realtime"), 3_600_000,
                NOW.minusSeconds(1), NOW.plusSeconds(300));
        assertNotEquals(grantA.grantId(), grantB.grantId());
        assertNotEquals(grantA.expiresAt(), grantB.expiresAt());
        ReserveUsageRequest requestA = reserveRequest(
                grantA, internalKey, digest, "mtg_candidate_a");
        ReserveUsageRequest requestB = reserveRequest(
                grantB, internalKey, digest, "mtg_candidate_b");

        // 每个任务先用独立连接观察 V003 未命中；双方都到达 barrier 后才允许
        // 调用 reserveUsage。这样测试的是两方首次 miss，而非顺序重放。
        CountDownLatch bothObservedMiss = new CountDownLatch(2);
        CountDownLatch startReserve = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ReserveAttempt> a = executor.submit(() -> reserveAfterLookup(
                    requestA, bothObservedMiss, startReserve));
            Future<ReserveAttempt> b = executor.submit(() -> reserveAfterLookup(
                    requestB, bothObservedMiss, startReserve));
            assertTrue(bothObservedMiss.await(10, TimeUnit.SECONDS),
                    "两个调用方未在期限内完成各自的首次 lookup");
            startReserve.countDown();
            ReserveAttempt resultA = a.get(10, TimeUnit.SECONDS);
            ReserveAttempt resultB = b.get(10, TimeUnit.SECONDS);
            assertEquals(UsageReserveLookup.Status.NOT_FOUND, resultA.before());
            assertEquals(UsageReserveLookup.Status.NOT_FOUND, resultB.before());
            assertTrue(resultA.reserved().isSuccess());
            assertTrue(resultB.reserved().isSuccess());
            UsageOperationResult winner = resultA.reserved().valueOrThrow();
            assertEquals(winner, resultB.reserved().valueOrThrow());
            assertTrue(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                    winner, complete, DEPLOYMENT, SUBJECT));
            assertTrue(Set.of("mtg_candidate_a", "mtg_candidate_b")
                    .contains(winner.reservation().expectedMeetingId()));
            String loserMeetingId = "mtg_candidate_a".equals(
                    winner.reservation().expectedMeetingId())
                    ? "mtg_candidate_b" : "mtg_candidate_a";
            assertEquals(1L, count("SELECT count(*) FROM usage_operation"));
            assertEquals(1L, count("SELECT count(*) FROM usage_reservation"));
            assertEquals(0L, count("SELECT count(*) FROM usage_reservation "
                    + "WHERE expected_meeting_id = '" + loserMeetingId + "'"));
        } finally {
            startReserve.countDown();
            executor.shutdownNow();
        }
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
    void expiredClaimRoundsNanosecondsLikePostgresAndKeepsReleaseAndCancelUsable()
            throws Exception {
        JdbcExpiredReservationStore store = new JdbcExpiredReservationStore(
                dataSource, new TestIds());

        insertExpired("claim-round-cancel", SUBJECT, "grant-round-cancel",
                "meeting-round-cancel");
        Instant rawClaimedAt = NOW.plusNanos(123_456_500);
        Instant expectedClaimedAt = NOW.plusNanos(123_457_000);
        Instant expectedLeaseUntil = expectedClaimedAt.plusSeconds(30);
        ExpiredReservationStore.ClaimBatch roundedBatch = store.claimExpired(
                new ExpiredReservationStore.ClaimRequest(
                        DEPLOYMENT, "worker-round-cancel", rawClaimedAt,
                        Duration.ofSeconds(30), 1));
        ExpiredReservationStore.LeaseToken roundedLease =
                roundedBatch.claims().get(0).lease();

        // ClaimBatch 返回数据库实际使用的规范时间，不能继续暴露写库前的纳秒值。
        assertEquals(expectedClaimedAt, roundedBatch.request().now());
        assertEquals(expectedLeaseUntil, roundedBatch.request().leaseUntil());
        assertEquals(expectedClaimedAt, roundedLease.claimedAt());
        assertEquals(expectedLeaseUntil, roundedLease.expiresAt());
        assertEquals(expectedLeaseUntil, leaseUntil("claim-round-cancel"));

        CancelReservationRequest cancel = new CancelReservationRequest(
                DEPLOYMENT, SUBJECT, UsageOperationScopes.CANCEL,
                "cancel-round", "cancel-round-digest", roundedLease.reservationId(),
                "meeting-round-cancel", 0, "EXPIRED_UNBOUND");
        EntitlementProviderResult<UsageOperationResult> cancelled =
                provider(policyAt(expectedClaimedAt), expectedClaimedAt.plusSeconds(1))
                        .cancelReservationWithLease(
                                new LeaseAwareCancelReservationRequest(cancel, roundedLease));
        assertTrue(cancelled.isSuccess(), () -> cancelled.errorOrThrow().code().name());
        assertEquals(UsageReservation.State.CANCELLED,
                cancelled.valueOrThrow().reservation().state());

        insertExpired("claim-round-carry", SUBJECT, "grant-round-carry",
                "meeting-round-carry");
        Instant rawCarryAt = NOW.plusSeconds(2).plusNanos(999_999_500);
        Instant expectedCarryAt = NOW.plusSeconds(3);
        Instant expectedCarryUntil = NOW.plusSeconds(13);
        ExpiredReservationStore.ClaimBatch carryBatch = store.claimExpired(
                new ExpiredReservationStore.ClaimRequest(
                        DEPLOYMENT, "worker-round-carry", rawCarryAt,
                        Duration.ofSeconds(10), 1));
        ExpiredReservationStore.LeaseToken carryLease = carryBatch.claims().get(0).lease();

        // 999999500ns 必须像 pgjdbc 一样进位到下一秒，写库值与返回句柄仍完全相同。
        assertEquals(expectedCarryAt, carryLease.claimedAt());
        assertEquals(expectedCarryUntil, carryLease.expiresAt());
        assertEquals(expectedCarryUntil, leaseUntil("claim-round-carry"));
        assertEquals(ExpiredReservationStore.ReleaseStatus.RELEASED,
                store.release(carryLease, expectedCarryAt.plusSeconds(1)).status());
        assertNull(leaseUntil("claim-round-carry"));

        insertExpired("claim-round-collapsed", SUBJECT, "grant-round-collapsed",
                "meeting-round-collapsed");
        Instant collapsedAt = NOW.plusSeconds(20).plusNanos(400);
        assertThrows(IllegalArgumentException.class,
                () -> store.claimExpired(new ExpiredReservationStore.ClaimRequest(
                        DEPLOYMENT, "worker-round-collapsed", collapsedAt,
                        Duration.ofNanos(50), 1)));
        assertNull(leaseUntil("claim-round-collapsed"));
    }

    @Test
    void leaseAwareCancelRejectsStaleWorkerAfterReclaimAndReplaysCommittedFact()
            throws Exception {
        insertExpired("cleanup-lease", SUBJECT, "grant-cleanup", "meeting-cleanup");
        JdbcExpiredReservationStore store = new JdbcExpiredReservationStore(
                dataSource, new TestIds());
        ExpiredReservationStore.LeaseToken workerA = store.claimExpired(
                new ExpiredReservationStore.ClaimRequest(
                        DEPLOYMENT, "cleanup-worker-a", NOW,
                        Duration.ofSeconds(30), 1))
                .claims().get(0).lease();
        ExpiredReservationStore.LeaseToken workerB = store.claimExpired(
                new ExpiredReservationStore.ClaimRequest(
                        DEPLOYMENT, "cleanup-worker-b", NOW.plusSeconds(31),
                        Duration.ofSeconds(30), 1))
                .claims().get(0).lease();
        assertTrue(workerB.leaseVersion() > workerA.leaseVersion());

        CancelReservationRequest staleCancel = new CancelReservationRequest(
                DEPLOYMENT, SUBJECT, UsageOperationScopes.CANCEL,
                "cleanup-a", "cleanup-a-digest", workerA.reservationId(),
                "meeting-cleanup", 0, "EXPIRED_UNBOUND");
        EntitlementProviderResult<UsageOperationResult> stale =
                provider(policyAt(NOW), NOW.plusSeconds(32))
                        .cancelReservationWithLease(
                                new LeaseAwareCancelReservationRequest(staleCancel, workerA));
        assertError(stale, EntitlementProviderError.Code.RESERVATION_STATE_CONFLICT);
        assertEquals(0L, count("SELECT count(*) FROM usage_operation"));
        assertEquals(1L, count("""
                SELECT count(*) FROM usage_reservation
                 WHERE reservation_id = 'cleanup-lease' AND status = 'RESERVED'
                   AND lease_owner = 'cleanup-worker-b'
                """));

        CancelReservationRequest winningCancel = new CancelReservationRequest(
                DEPLOYMENT, SUBJECT, UsageOperationScopes.CANCEL,
                "cleanup-b", "cleanup-b-digest", workerB.reservationId(),
                "meeting-cleanup", 0, "EXPIRED_UNBOUND");
        JdbcReferenceDevEntitlementProvider winningProvider =
                provider(policyAt(NOW), NOW.plusSeconds(32));
        EntitlementProviderResult<UsageOperationResult> cancelled =
                winningProvider.cancelReservationWithLease(
                        new LeaseAwareCancelReservationRequest(winningCancel, workerB));
        assertTrue(cancelled.isSuccess(), () -> cancelled.errorOrThrow().code().name());
        assertEquals(UsageReservation.State.CANCELLED,
                cancelled.valueOrThrow().reservation().state());
        assertEquals(1L, count("SELECT count(*) FROM usage_operation"));
        assertEquals(1L, count("""
                SELECT count(*) FROM usage_reservation
                 WHERE reservation_id = 'cleanup-lease' AND status = 'CANCELLED'
                   AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL
                """));

        // 取消事实已经写入 usage_operation 后，旧 lease 即使完全过期也只能稳定重放。
        EntitlementProviderResult<UsageOperationResult> replay =
                provider(policyAt(NOW), NOW.plusSeconds(90))
                        .cancelReservationWithLease(
                                new LeaseAwareCancelReservationRequest(winningCancel, workerB));
        assertTrue(replay.isSuccess());
        assertEquals(cancelled.valueOrThrow(), replay.valueOrThrow());
        assertEquals(1L, count("SELECT count(*) FROM usage_operation"));
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

    private ReserveAttempt reserveAfterLookup(ReserveUsageRequest request,
            CountDownLatch bothObservedMiss, CountDownLatch startReserve) throws Exception {
        UsageReserveLookup.Status before = new JdbcUsageReserveLookup(dataSource).lookup(
                new UsageReserveLookup.Request(DEPLOYMENT, SUBJECT,
                        request.idempotencyKey(), request.requestDigest())).status();
        bothObservedMiss.countDown();
        assertTrue(startReserve.await(10, TimeUnit.SECONDS),
                "未在期限内释放 reserve 并发屏障");
        return new ReserveAttempt(before, provider(policyAt(NOW), NOW).reserveUsage(request));
    }

    private record ReserveAttempt(UsageReserveLookup.Status before,
                                  EntitlementProviderResult<UsageOperationResult> reserved) {
    }

    private static CreateMeetingRequestFact completeRequestFact(String key, String title) {
        var snapshot = new CreateMeetingRequestSnapshot(
                "complete-session", new CreateMeetingRequestSnapshot.ProfileRef(PROFILE, 1),
                new CreateMeetingRequestSnapshot.ClientDescriptor("app", "1", "v1", "device"),
                title, "Asia/Shanghai",
                new CreateMeetingRequestSnapshot.LanguageSelection("zh-CN", List.of()),
                AudioProfile.pcm16kMono(100), Set.of("transcript.realtime"),
                new CreateMeetingRequestSnapshot.ArtifactPolicy(false, false), key);
        return new CreateMeetingRequestFact(snapshot,
                MeetingRequestCanonicalizer.digest(snapshot), 1);
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

    /** 直接读取持久化 lease 截止时间，验证返回句柄没有隐藏 pgjdbc 舍入差异。 */
    private Instant leaseUntil(String reservationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT lease_until
                       FROM usage_reservation
                      WHERE deployment_scope = ? AND reservation_id = ?
                     """)) {
            statement.setString(1, DEPLOYMENT);
            statement.setString(2, reservationId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                java.sql.Timestamp value = rows.getTimestamp(1);
                assertFalse(rows.next());
                return value == null ? null : value.toInstant();
            }
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
