package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageActual;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用量幂等结果独立 JSON 的完整快照、版本和范围校验测试。 */
class UsageOperationResultJsonTest {
    private static final String DEPLOYMENT = "deployment-\"中文";
    private static final String SUBJECT = "subject\\scope";
    private static final String KEY = "reserve-key-sensitive";
    private static final String DIGEST = "d".repeat(64);
    private static final Instant CREATED_AT = Instant.parse("2026-09-17T04:00:00.123456789Z");

    @Test
    void roundTripPreservesCompleteReservedSnapshotAndExplicitNulls() {
        UsageOperationResult expected = result(
                UsageOperationScopes.RESERVE, reserved(Long.MAX_VALUE));

        String encoded = UsageOperationResultJson.encode(expected);
        UsageOperationResult decoded = decode(encoded, UsageOperationScopes.RESERVE);

        assertEquals(expected, decoded);
        assertTrue(encoded.contains("\"schemaVersion\":1"));
        assertTrue(encoded.contains(
                "\"grantExpiresAt\":\"2026-09-17T04:05:01.123456789Z\""));
        assertTrue(encoded.contains("\"amount\":9223372036854775807"));
        assertTrue(encoded.contains("\"boundMeetingId\":null"));
        assertTrue(encoded.contains("\"actualUsage\":null"));
        assertTrue(encoded.contains("\"committedAt\":null"));
        assertTrue(encoded.contains("\"cancelledAt\":null"));
        assertTrue(encoded.contains("\"cancellationReasonCode\":null"));
        assertEquals(CREATED_AT, decoded.reservation().createdAt());
    }

    @Test
    void roundTripPreservesCommittedAndCancelledTerminalFacts() {
        UsageReservation initial = reserved(60_000);
        UsageReservation committed = initial
                .bind("meeting-expected", CREATED_AT.plusSeconds(1), 0)
                .commit(new UsageActual(UsageUnit.MEETING_DURATION_MS, 42_001),
                        CREATED_AT.plusSeconds(700), 1);
        UsageReservation cancelled = initial.cancel(
                "EXPIRED_UNBOUND", CREATED_AT.plusSeconds(700), 0);

        assertEquals(result(UsageOperationScopes.COMMIT, committed),
                decode(UsageOperationResultJson.encode(
                                result(UsageOperationScopes.COMMIT, committed)),
                        UsageOperationScopes.COMMIT));
        assertEquals(result(UsageOperationScopes.CANCEL, cancelled),
                decode(UsageOperationResultJson.encode(
                                result(UsageOperationScopes.CANCEL, cancelled)),
                        UsageOperationScopes.CANCEL));
    }

    @Test
    void decodeRejectsUnknownVersionMissingExtraDuplicateAndInexactNumbers() {
        String valid = UsageOperationResultJson.encode(
                result(UsageOperationScopes.RESERVE, reserved(60_000)));

        for (String invalid : List.of(
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                valid.replace("\"schemaVersion\":1,", ""),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"extra\":0"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                valid.replace(
                        "\"grantExpiresAt\":\"2026-09-17T04:05:01.123456789Z\",", ""),
                valid.replace("\"version\":0", "\"version\":0.0"),
                valid.replace("\"version\":0", "\"version\":1e0"),
                valid.replace("\"version\":0", "\"version\":9223372036854775808"),
                valid.replace("\"unit\":\"MEETING_DURATION_MS\"",
                        "\"unit\":\"UNKNOWN\""))) {
            assertSafeFailure(invalid, UsageOperationScopes.RESERVE);
        }

        String extraReservation = valid.replace(
                "\"reservationId\":", "\"extraNested\":null,\"reservationId\":");
        assertSafeFailure(extraReservation, UsageOperationScopes.RESERVE);
        String missingNullable = valid.replace("\"boundMeetingId\":null,", "");
        assertSafeFailure(missingNullable, UsageOperationScopes.RESERVE);
    }

    @Test
    void decodeRevalidatesEveryExpectedScopeKeyAndDigest() {
        String encoded = UsageOperationResultJson.encode(
                result(UsageOperationScopes.RESERVE, reserved(60_000)));

        assertThrows(IllegalArgumentException.class, () -> UsageOperationResultJson.decode(
                encoded, "other-deployment", SUBJECT, UsageOperationScopes.RESERVE, KEY, DIGEST));
        assertThrows(IllegalArgumentException.class, () -> UsageOperationResultJson.decode(
                encoded, DEPLOYMENT, "other-subject", UsageOperationScopes.RESERVE, KEY, DIGEST));
        assertThrows(IllegalArgumentException.class, () -> UsageOperationResultJson.decode(
                encoded, DEPLOYMENT, SUBJECT, UsageOperationScopes.COMMIT, KEY, DIGEST));
        assertThrows(IllegalArgumentException.class, () -> UsageOperationResultJson.decode(
                encoded, DEPLOYMENT, SUBJECT, UsageOperationScopes.RESERVE, "other-key", DIGEST));
        assertThrows(IllegalArgumentException.class, () -> UsageOperationResultJson.decode(
                encoded, DEPLOYMENT, SUBJECT, UsageOperationScopes.RESERVE, KEY, "other-digest"));

        String bodyScopeChanged = encoded.replace(
                "deployment-\\\"中文", "deployment-other");
        assertSafeFailure(bodyScopeChanged, UsageOperationScopes.RESERVE);
    }

    @Test
    void failuresNeverEchoJsonPayloadScopeKeyOrDigest() {
        String encoded = UsageOperationResultJson.encode(
                result(UsageOperationScopes.RESERVE, reserved(60_000)));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> UsageOperationResultJson.decode(
                        encoded, DEPLOYMENT, SUBJECT, UsageOperationScopes.RESERVE,
                        KEY, "wrong-digest-sensitive"));

        for (String forbidden : List.of(
                encoded, DEPLOYMENT, SUBJECT, KEY, DIGEST, "wrong-digest-sensitive")) {
            assertFalse(failure.getMessage().contains(forbidden));
        }
        assertTrue(failure.getMessage().contains("requestDigest"));
    }

    private static UsageOperationResult decode(String json, String operationScope) {
        return UsageOperationResultJson.decode(
                json, DEPLOYMENT, SUBJECT, operationScope, KEY, DIGEST);
    }

    private static UsageOperationResult result(
            String operationScope, UsageReservation reservation) {
        return new UsageOperationResult(
                DEPLOYMENT, SUBJECT, operationScope, KEY, DIGEST,
                UsageOperationScopes.RESERVE.equals(operationScope)
                        ? CREATED_AT.plusSeconds(301) : null,
                reservation);
    }

    private static UsageReservation reserved(long amount) {
        Instant expiresAt = CREATED_AT.plusSeconds(300);
        MeetingGrant grant = new MeetingGrant(
                "grant-\"opaque", DEPLOYMENT, SUBJECT, "profile-reference", 3,
                Set.of("transcript.realtime"), Set.of("transcript.realtime"),
                Long.MAX_VALUE, CREATED_AT.minusSeconds(1), expiresAt.plusSeconds(1));
        return UsageReservation.reserved(
                "reservation-\\opaque",
                grant,
                "meeting-expected",
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, amount),
                CREATED_AT,
                expiresAt);
    }

    private static void assertSafeFailure(String json, String operationScope) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> decode(json, operationScope));
        for (String forbidden : List.of(json, DEPLOYMENT, SUBJECT, KEY, DIGEST)) {
            assertFalse(failure.getMessage().contains(forbidden));
        }
    }
}
