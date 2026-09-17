package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 会议创建终态只读查询的五状态、构造不变量和脱敏契约。 */
class MeetingCreateReplayLookupContractTest {
    @Test
    void requestAndResultsAreTypedAndRedacted() {
        String deployment = "deployment-secret";
        String subject = "subject-secret";
        String session = "session-secret";
        String key = "key-secret";
        String digest = "digest-secret";
        var request = new MeetingCreateReplayLookup.Request(
                deployment, subject, session, key, digest);
        MeetingCreateOutcome outcome = ownedSuccess();
        List<MeetingCreateReplayLookup.Result> results = List.of(
                new MeetingCreateReplayLookup.NotFound(),
                new MeetingCreateReplayLookup.Replayed(outcome),
                new MeetingCreateReplayLookup.Conflict(),
                new MeetingCreateReplayLookup.Inconsistent(
                        MeetingCreateReplayLookup.InconsistencyReason.SUCCESS_SCOPE_MISMATCH),
                new MeetingCreateReplayLookup.Unavailable(
                        MeetingCreateReplayLookup.UnavailableReason
                                .TRANSIENT_STORAGE_FAILURE,
                        true));

        assertEquals(List.of(
                        MeetingCreateReplayLookup.Status.NOT_FOUND,
                        MeetingCreateReplayLookup.Status.REPLAYED,
                        MeetingCreateReplayLookup.Status.CONFLICT,
                        MeetingCreateReplayLookup.Status.INCONSISTENT,
                        MeetingCreateReplayLookup.Status.UNAVAILABLE),
                results.stream().map(MeetingCreateReplayLookup.Result::status).toList());
        String output = request + "\n" + results;
        for (String secret : List.of(deployment, subject, session, key, digest,
                "grant-secret", "reservation-secret")) {
            assertFalse(output.contains(secret), "toString 泄露字段: " + secret);
        }
    }

    @Test
    void constructorsRejectInvalidIdentityLegacySuccessAndRetryabilityMismatch() {
        assertThrows(IllegalArgumentException.class, () ->
                new MeetingCreateReplayLookup.Request(" ", "subject", "session", "key", "d"));
        assertThrows(IllegalArgumentException.class, () ->
                new MeetingCreateReplayLookup.Request(
                        "deployment", "LEGACY_UNOWNED", "session", "key", "d"));
        Instant now = Instant.parse("2026-09-18T00:00:00Z");
        MeetingCommandResult legacy = new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED,
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        "meeting", "deployment", "session", "profile", 1,
                        MeetingState.CREATED, now, now));
        assertThrows(IllegalArgumentException.class, () ->
                new MeetingCreateReplayLookup.Replayed(
                        MeetingCreateOutcome.restoreHistoricalSuccess(legacy)));
        assertThrows(IllegalArgumentException.class, () ->
                new MeetingCreateReplayLookup.Unavailable(
                        MeetingCreateReplayLookup.UnavailableReason.STORAGE_FAILURE,
                        true));
    }

    private static MeetingCreateOutcome ownedSuccess() {
        Instant now = Instant.parse("2026-09-18T00:00:00Z");
        return MeetingCreateOutcome.success(new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED,
                MeetingAggregate.MeetingSnapshot.owned(
                        "meeting-secret", "deployment-secret", "session-secret",
                        "subject-secret", "grant-secret", now.plusSeconds(600),
                        "reservation-secret", "profile", 1,
                        MeetingState.CREATED, now, now)));
    }
}
