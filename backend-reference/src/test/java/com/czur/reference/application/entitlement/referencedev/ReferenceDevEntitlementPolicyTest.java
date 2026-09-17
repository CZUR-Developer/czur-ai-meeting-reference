package com.czur.reference.application.entitlement.referencedev;

import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.identity.Subject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** reference-dev 固定权益策略的纯读授权、预占边界和日志脱敏测试。 */
class ReferenceDevEntitlementPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-17T02:00:00Z");
    private static final String DEPLOYMENT = "deployment-reference";
    private static final String SUBJECT = "subject-sensitive-42";
    private static final String PROFILE = "profile-reference";
    private static final Set<String> SUPPORTED = Set.of(
            "transcript.realtime", "summary.structured");

    @Test
    void configurationDefensivelyCopiesCapabilitiesAndValidatesTtls() {
        HashSet<String> mutable = new HashSet<>(SUPPORTED);
        ReferenceDevEntitlementPolicyConfig config = new ReferenceDevEntitlementPolicyConfig(
                DEPLOYMENT, PROFILE, 7, mutable, 3_600_000,
                Duration.ofMinutes(10), Duration.ofMinutes(5));
        mutable.add("speaker.cross_meeting");

        assertEquals(SUPPORTED, config.supportedCapabilities());
        assertThrows(UnsupportedOperationException.class,
                () -> config.supportedCapabilities().add("translation.realtime"));
        assertThrows(IllegalArgumentException.class, () ->
                new ReferenceDevEntitlementPolicyConfig(
                        DEPLOYMENT, PROFILE, 7, SUPPORTED, 3_600_000,
                        Duration.ofMinutes(5), Duration.ofMinutes(6)));
        assertThrows(IllegalArgumentException.class, () ->
                new ReferenceDevEntitlementPolicyConfig(
                        DEPLOYMENT, PROFILE, 7, Set.of(), 3_600_000,
                        Duration.ofMinutes(5), Duration.ofMinutes(1)));
    }

    @Test
    void authorizeIsPurePolicyReadAndNeverExpandsCapabilities() {
        FixedIds ids = new FixedIds("opaque-grant-random", "opaque-reservation-random");
        ReferenceDevEntitlementPolicy policy = policyAt(NOW, ids);
        AuthorizeStartRequest request = authorizeRequest(
                DEPLOYMENT, PROFILE, 7, Set.of("transcript.realtime"), 60_000);

        EntitlementProviderResult<MeetingGrant> result = policy.authorizeStart(request);

        assertTrue(result.isSuccess());
        MeetingGrant grant = result.valueOrThrow();
        assertEquals("opaque-grant-random", grant.grantId());
        assertEquals(request.requestedCapabilities(), grant.requestedCapabilities());
        assertEquals(request.requestedCapabilities(), grant.grantedCapabilities(),
                "策略只能授权本次申请能力，不能把配置中的其它能力一起下发");
        assertEquals(NOW, grant.issuedAt());
        assertEquals(NOW.plusSeconds(600), grant.expiresAt());
        assertEquals(3_600_000, grant.maxDurationMs());
        assertEquals(1, ids.grantCalls);
        assertEquals(0, ids.reservationCalls);
    }

    @Test
    void authorizeRejectsWrongDeploymentProfileRevisionCapabilityAndDuration() {
        ReferenceDevEntitlementPolicy policy = policyAt(NOW, new FixedIds("g", "r"));

        assertError(policy.authorizeStart(authorizeRequest(
                        "other-deployment", PROFILE, 7,
                        Set.of("transcript.realtime"), 1)),
                EntitlementProviderError.Code.ENTITLEMENT_DENIED);
        assertError(policy.authorizeStart(authorizeRequest(
                        DEPLOYMENT, "other-profile", 7,
                        Set.of("transcript.realtime"), 1)),
                EntitlementProviderError.Code.ENTITLEMENT_DENIED);
        assertError(policy.authorizeStart(authorizeRequest(
                        DEPLOYMENT, PROFILE, 8,
                        Set.of("transcript.realtime"), 1)),
                EntitlementProviderError.Code.ENTITLEMENT_DENIED);
        assertError(policy.authorizeStart(authorizeRequest(
                        DEPLOYMENT, PROFILE, 7,
                        Set.of("speaker.cross_meeting"), 1)),
                EntitlementProviderError.Code.CAPABILITY_NOT_GRANTED);
        assertError(policy.authorizeStart(authorizeRequest(
                        DEPLOYMENT, PROFILE, 7,
                        Set.of("transcript.realtime"), 3_600_001)),
                EntitlementProviderError.Code.ENTITLEMENT_DENIED);
        assertError(policy.authorizeStart(authorizeRequest(
                        DEPLOYMENT, PROFILE, 7, Set.of(), 1)),
                EntitlementProviderError.Code.CAPABILITY_NOT_GRANTED);
        assertError(policy.authorizeStart(null),
                EntitlementProviderError.Code.INVALID_REQUEST);
    }

    @Test
    void prepareReservationUsesSingleNowAndExactPolicyTtlBoundary() {
        FixedIds ids = new FixedIds("opaque-grant-random", "opaque-reservation-random");
        SingleReadClock clock = new SingleReadClock(NOW);
        ReferenceDevEntitlementPolicy policy = policyWithClock(clock, ids);
        MeetingGrant grant = new MeetingGrant(
                "grant-from-authorize", DEPLOYMENT, SUBJECT, PROFILE, 7,
                Set.of("transcript.realtime"), Set.of("transcript.realtime"),
                120_000, NOW.minusSeconds(1), NOW.plusSeconds(500));

        EntitlementProviderResult<UsageReservation> result =
                policy.prepareReservation(reserveRequest(grant, 60_000));

        assertTrue(result.isSuccess());
        UsageReservation reservation = result.valueOrThrow();
        assertEquals("opaque-reservation-random", reservation.reservationId());
        assertEquals(60_000, reservation.reservedUsage().amount());
        assertEquals(NOW, reservation.createdAt());
        assertEquals(NOW.plusSeconds(300), reservation.expiresAt(),
                "截止时间应精确等于同一次 now 加 reservation TTL");
        assertEquals(1, clock.reads(), "一次预占只能读取一次时钟");
        assertEquals(0, reservation.version());
        assertFalse(reservation.isBound());
    }

    @Test
    void prepareReservationUsesEarlierGrantExpiryAndEnforcesGrantLimits() {
        FixedIds ids = new FixedIds("opaque-grant-random", "opaque-reservation-random");
        ReferenceDevEntitlementPolicy policy = policyAt(NOW, ids);
        MeetingGrant grant = new MeetingGrant(
                "grant-from-authorize", DEPLOYMENT, SUBJECT, PROFILE, 7,
                Set.of("transcript.realtime"), Set.of("transcript.realtime"),
                120_000, NOW.minusSeconds(1), NOW.plusSeconds(120));
        ReserveUsageRequest request = reserveRequest(grant, 60_000);

        EntitlementProviderResult<UsageReservation> result =
                policy.prepareReservation(request);

        assertTrue(result.isSuccess());
        UsageReservation reservation = result.valueOrThrow();
        assertEquals("opaque-reservation-random", reservation.reservationId());
        assertEquals(60_000, reservation.reservedUsage().amount());
        assertEquals(NOW, reservation.createdAt());
        assertEquals(NOW.plusSeconds(120), reservation.expiresAt());

        MeetingGrant narrowGrant = new MeetingGrant(
                "narrow", DEPLOYMENT, SUBJECT, PROFILE, 7,
                Set.of("transcript.realtime"), Set.of("transcript.realtime"),
                30_000, NOW.minusSeconds(1), NOW.plusSeconds(300));
        assertError(policy.prepareReservation(reserveRequest(narrowGrant, 30_001)),
                EntitlementProviderError.Code.ENTITLEMENT_DENIED);

        MeetingGrant expandedGrant = new MeetingGrant(
                "expanded", DEPLOYMENT, SUBJECT, PROFILE, 7,
                Set.of("transcript.realtime"), SUPPORTED,
                120_000, NOW.minusSeconds(1), NOW.plusSeconds(300));
        assertError(policy.prepareReservation(reserveRequest(expandedGrant, 1)),
                EntitlementProviderError.Code.ENTITLEMENT_DENIED);
    }

    @Test
    void prepareReservationRejectsFutureExpiredAndReservationTimeOverflow() {
        ReferenceDevEntitlementPolicy policy = policyAt(NOW, new FixedIds("g", "r"));
        MeetingGrant future = grant(NOW.plusSeconds(1), NOW.plusSeconds(60), 60_000);
        assertError(policy.prepareReservation(reserveRequest(future, 1)),
                EntitlementProviderError.Code.ENTITLEMENT_DENIED);

        MeetingGrant expired = grant(NOW.minusSeconds(60), NOW, 60_000);
        assertError(policy.prepareReservation(reserveRequest(expired, 1)),
                EntitlementProviderError.Code.GRANT_EXPIRED);

        Instant nearMaximum = Instant.MAX.minusSeconds(60);
        ReferenceDevEntitlementPolicy overflowPolicy = policyAt(
                nearMaximum, new FixedIds("g", "r"));
        MeetingGrant overflow = new MeetingGrant(
                "overflow", DEPLOYMENT, SUBJECT, PROFILE, 7,
                Set.of("transcript.realtime"), Set.of("transcript.realtime"),
                60_000, Instant.MAX.minusSeconds(600), Instant.MAX);
        assertError(overflowPolicy.prepareReservation(new ReserveUsageRequest(
                        DEPLOYMENT, SUBJECT, UsageOperationScopes.RESERVE,
                        "reserve-key", digest(), overflow,
                        new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 1),
                        "meeting-expected")),
                EntitlementProviderError.Code.INTERNAL);
    }

    @Test
    void logsContainOnlyStableReasonsAndNeverContainBusinessIdentifiers() {
        Logger logger = Logger.getLogger(ReferenceDevEntitlementPolicy.class.getName());
        Level oldLevel = logger.getLevel();
        boolean oldParents = logger.getUseParentHandlers();
        List<String> messages = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                Object[] parameters = record.getParameters();
                messages.add(parameters == null
                        ? record.getMessage()
                        : java.text.MessageFormat.format(record.getMessage(), parameters));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(capture);
        try {
            ReferenceDevEntitlementPolicy policy = policyAt(
                    NOW, new FixedIds("secret-grant-id", "secret-reservation-id"));
            assertTrue(policy.authorizeStart(authorizeRequest(
                    DEPLOYMENT, PROFILE, 7,
                    Set.of("transcript.realtime"), 1)).isSuccess());
            assertError(policy.authorizeStart(authorizeRequest(
                            "secret-other-deployment", PROFILE, 7,
                            Set.of("transcript.realtime"), 1)),
                    EntitlementProviderError.Code.ENTITLEMENT_DENIED);
        } finally {
            logger.removeHandler(capture);
            logger.setUseParentHandlers(oldParents);
            logger.setLevel(oldLevel);
        }

        String logs = String.join("\n", messages);
        assertTrue(logs.contains("DEPLOYMENT_NOT_ALLOWED"));
        for (String forbidden : List.of(
                SUBJECT, DEPLOYMENT, PROFILE, "reserve-key", digest(),
                "secret-grant-id", "secret-reservation-id", "secret-other-deployment")) {
            assertFalse(logs.contains(forbidden), "日志泄露了业务字段: " + forbidden);
        }
    }

    private static ReferenceDevEntitlementPolicy policyAt(
            Instant now, EntitlementIdGenerator ids) {
        return policyWithClock(Clock.fixed(now, ZoneOffset.UTC), ids);
    }

    private static ReferenceDevEntitlementPolicy policyWithClock(
            Clock clock, EntitlementIdGenerator ids) {
        return new ReferenceDevEntitlementPolicy(
                new ReferenceDevEntitlementPolicyConfig(
                        DEPLOYMENT, PROFILE, 7, SUPPORTED, 3_600_000,
                        Duration.ofMinutes(10), Duration.ofMinutes(5)),
                clock,
                ids);
    }

    private static AuthorizeStartRequest authorizeRequest(
            String deployment,
            String profile,
            int revision,
            Set<String> capabilities,
            long durationMs) {
        return new AuthorizeStartRequest(
                deployment,
                new Subject(SUBJECT),
                profile,
                revision,
                capabilities,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, durationMs),
                "client-session-sensitive");
    }

    private static MeetingGrant grant(Instant issuedAt, Instant expiresAt, long maxDuration) {
        return new MeetingGrant(
                "grant-input", DEPLOYMENT, SUBJECT, PROFILE, 7,
                Set.of("transcript.realtime"), Set.of("transcript.realtime"),
                maxDuration, issuedAt, expiresAt);
    }

    private static ReserveUsageRequest reserveRequest(MeetingGrant grant, long amount) {
        return new ReserveUsageRequest(
                DEPLOYMENT,
                SUBJECT,
                UsageOperationScopes.RESERVE,
                "reserve-key-sensitive",
                digest(),
                grant,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, amount),
                "meeting-expected-sensitive");
    }

    private static String digest() {
        return "a".repeat(64);
    }

    private static <T> void assertError(
            EntitlementProviderResult<T> result,
            EntitlementProviderError.Code code) {
        assertFalse(result.isSuccess());
        assertEquals(code, result.errorOrThrow().code());
        assertFalse(result.errorOrThrow().retryable());
    }

    private static final class FixedIds implements EntitlementIdGenerator {
        private final String grantId;
        private final String reservationId;
        private int grantCalls;
        private int reservationCalls;

        private FixedIds(String grantId, String reservationId) {
            this.grantId = grantId;
            this.reservationId = reservationId;
        }

        @Override
        public String newGrantId() {
            grantCalls++;
            return grantId;
        }

        @Override
        public String newReservationId() {
            reservationCalls++;
            return reservationId;
        }

        @Override
        public String newLeaseToken() {
            return "opaque-lease-random";
        }
    }

    /**
     * 只允许读取一次的固定时钟，用于防止实现分别读取 createdAt 和 expiresAt 的
     * 基准时间，从而在真实可变时钟下产生内部不一致的 Reservation 快照。
     */
    private static final class SingleReadClock extends Clock {
        private final Instant instant;
        private int reads;

        private SingleReadClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            reads++;
            if (reads > 1) {
                throw new AssertionError("reservation policy read clock more than once");
            }
            return instant;
        }

        private int reads() {
            return reads;
        }
    }
}
