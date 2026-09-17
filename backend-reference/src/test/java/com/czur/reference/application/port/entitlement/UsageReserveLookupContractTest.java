package com.czur.reference.application.port.entitlement;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 用量预占首次结果查询的五状态、重试标记和脱敏契约。 */
class UsageReserveLookupContractTest {
    @Test
    void requestAndResultsAreTypedAndRedacted() {
        String deployment = "deployment-secret";
        String subject = "subject-secret";
        String key = "reserve-key-secret";
        String digest = "reserve-digest-secret";
        var request = new UsageReserveLookup.Request(deployment, subject, key, digest);
        UsageOperationResult result = reserveResult(deployment, subject, key, digest);
        List<UsageReserveLookup.Result> results = List.of(
                new UsageReserveLookup.NotFound(),
                new UsageReserveLookup.Replayed(result),
                new UsageReserveLookup.Conflict(),
                new UsageReserveLookup.Inconsistent(
                        UsageReserveLookup.InconsistencyReason
                                .RESERVATION_REFERENCE_MISMATCH),
                new UsageReserveLookup.Unavailable(
                        UsageReserveLookup.UnavailableReason.TRANSIENT_STORAGE_FAILURE,
                        true));

        assertEquals(List.of(
                        UsageReserveLookup.Status.NOT_FOUND,
                        UsageReserveLookup.Status.REPLAYED,
                        UsageReserveLookup.Status.CONFLICT,
                        UsageReserveLookup.Status.INCONSISTENT,
                        UsageReserveLookup.Status.UNAVAILABLE),
                results.stream().map(UsageReserveLookup.Result::status).toList());
        String output = request + "\n" + results;
        for (String secret : List.of(
                deployment, subject, key, digest, "grant-secret", "reservation-secret")) {
            assertFalse(output.contains(secret), "toString 泄露字段: " + secret);
        }
    }

    @Test
    void constructorsRejectInvalidIdentityAndRetryabilityMismatch() {
        assertThrows(IllegalArgumentException.class, () ->
                new UsageReserveLookup.Request("deployment", " ", "key", "digest"));
        assertThrows(IllegalArgumentException.class, () ->
                new UsageReserveLookup.Request(
                        "deployment", "LEGACY_UNOWNED", "key", "digest"));
        assertThrows(IllegalArgumentException.class, () ->
                new UsageReserveLookup.Unavailable(
                        UsageReserveLookup.UnavailableReason.TRANSIENT_STORAGE_FAILURE,
                        false));
    }

    private static UsageOperationResult reserveResult(
            String deployment, String subject, String key, String digest) {
        Instant now = Instant.parse("2026-09-18T00:00:00Z");
        UsageReservation reservation = new UsageReservation(
                "reservation-secret", deployment, subject, "grant-secret",
                "meeting", null, null,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                null, now, now.plusSeconds(300),
                UsageReservation.State.RESERVED, 0, null, null, null);
        AuthorizedProfileSnapshot profile = new AuthorizedProfileSnapshot(
                "profile", 1, Set.of("transcript.realtime"),
                Set.of("transcript.realtime"), 60_000);
        return UsageOperationResult.reserve(
                deployment, subject, key, digest,
                now.plusSeconds(600), profile, reservation);
    }
}
