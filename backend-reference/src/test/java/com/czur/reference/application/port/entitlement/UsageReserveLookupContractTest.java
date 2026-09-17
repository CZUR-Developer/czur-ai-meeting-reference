package com.czur.reference.application.port.entitlement;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void replayedAcceptsOnlyInitialOwnedReserveResult() {
        String deployment = "deployment";
        String subject = "subject";
        String key = "key";
        String digest = "digest";
        Instant now = Instant.parse("2026-09-18T00:00:00Z");
        UsageOperationResult valid = reserveResult(deployment, subject, key, digest);
        assertTrue(UsageReserveLookup.isReplayableOwnedReserveResult(valid));
        new UsageReserveLookup.Replayed(valid);

        UsageReservation initial = valid.reservation();
        AuthorizedProfileSnapshot validProfile = valid.requireAuthorizedProfileSnapshot();
        UsageOperationResult legacyV1 = UsageOperationResult.restoreLegacyV1Reserve(
                deployment, subject, key, digest, valid.grantExpiresAt(), initial);
        UsageReservation bound = initial.bind(initial.expectedMeetingId(), now.plusSeconds(1), 0);
        UsageOperationResult boundReserve = UsageOperationResult.reserve(
                deployment, subject, key, digest,
                valid.grantExpiresAt(), validProfile, bound);
        UsageReservation advancedUnbound = reservation(
                deployment, subject,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                null, null, UsageReservation.State.RESERVED, 1,
                null, null, null);
        UsageOperationResult advancedReserve = UsageOperationResult.reserve(
                deployment, subject, key, digest,
                valid.grantExpiresAt(), validProfile, advancedUnbound);
        AuthorizedProfileSnapshot missingCapability = new AuthorizedProfileSnapshot(
                "profile", 1,
                Set.of("transcript.realtime", "summary.structured"),
                Set.of("transcript.realtime"), 60_000);
        UsageOperationResult capabilityMismatch = UsageOperationResult.reserve(
                deployment, subject, key, digest,
                valid.grantExpiresAt(), missingCapability, initial);
        AuthorizedProfileSnapshot smallerProfile = new AuthorizedProfileSnapshot(
                "profile", 1, Set.of("transcript.realtime"),
                Set.of("transcript.realtime"), 30_000);
        UsageOperationResult amountExceedsProfile = UsageOperationResult.reserve(
                deployment, subject, key, digest,
                valid.grantExpiresAt(), smallerProfile, initial);
        UsageReservation committed = bound.commit(
                new UsageActual(UsageUnit.MEETING_DURATION_MS, 10_000),
                now.plusSeconds(2), bound.version());
        UsageOperationResult terminal = UsageOperationResult.terminal(
                deployment, subject, UsageOperationScopes.COMMIT, key, digest, committed);

        for (UsageOperationResult invalid : List.of(
                legacyV1,
                terminal,
                boundReserve,
                advancedReserve,
                capabilityMismatch,
                amountExceedsProfile)) {
            assertFalse(UsageReserveLookup.isReplayableOwnedReserveResult(invalid));
            assertThrows(IllegalArgumentException.class,
                    () -> new UsageReserveLookup.Replayed(invalid));
        }
    }

    private static UsageOperationResult reserveResult(
            String deployment, String subject, String key, String digest) {
        Instant now = Instant.parse("2026-09-18T00:00:00Z");
        UsageReservation reservation = reservation(
                deployment, subject,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                null, null, UsageReservation.State.RESERVED, 0,
                null, null, null);
        AuthorizedProfileSnapshot profile = new AuthorizedProfileSnapshot(
                "profile", 1, Set.of("transcript.realtime"),
                Set.of("transcript.realtime"), 60_000);
        return UsageOperationResult.reserve(
                deployment, subject, key, digest,
                now.plusSeconds(600), profile, reservation);
    }

    private static UsageReservation reservation(
            String deployment,
            String subject,
            UsageEstimate reservedUsage,
            String boundMeetingId,
            Instant boundAt,
            UsageReservation.State state,
            long version,
            UsageActual actualUsage,
            Instant committedAt,
            Instant cancelledAt) {
        Instant now = Instant.parse("2026-09-18T00:00:00Z");
        return new UsageReservation(
                "reservation-secret", deployment, subject, "grant-secret",
                "meeting", boundMeetingId, boundAt, reservedUsage,
                actualUsage, now, now.plusSeconds(300), state, version,
                committedAt, cancelledAt, null);
    }
}
