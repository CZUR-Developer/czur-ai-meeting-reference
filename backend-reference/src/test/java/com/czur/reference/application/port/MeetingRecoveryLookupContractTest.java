package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 会议创建恢复查询的请求复用、五状态、固定原因和脱敏纯 Java 契约。 */
class MeetingRecoveryLookupContractTest {
    private static final Instant CREATED = Instant.parse("2026-09-18T01:00:00Z");
    private static final String DEPLOYMENT = "recovery-deployment-secret";
    private static final String SUBJECT = "recovery-subject-secret";
    private static final String MEETING = "recovery-meeting-secret";

    @Test
    void lookupConsumesTheValidatedCreateRequestDirectly() {
        OwnedMeetingCreationUnitOfWork.CreateRequest request = request();
        MeetingRecoveryLookup lookup = actual -> {
            assertSame(request, actual);
            return new MeetingRecoveryLookup.NotFound();
        };

        assertEquals(MeetingRecoveryLookup.Status.NOT_FOUND,
                lookup.lookup(request).status());
    }

    @Test
    void resultsExposeFrozenStatusesReasonsAndRetryability() {
        MeetingCreateOutcome success = MeetingCreateOutcome.success(
                new MeetingCommandResult(
                        MeetingCommandResult.Operation.CREATED, ownedSnapshot()));
        MeetingCreateOutcome failure = MeetingCreateOutcome.failure(
                new MeetingCreateFailure(
                        OwnedMeetingCreationUnitOfWork.ErrorCode.INTERNAL, false));
        List<MeetingRecoveryLookup.Result> results = List.of(
                new MeetingRecoveryLookup.NotFound(),
                new MeetingRecoveryLookup.Replayed(success),
                new MeetingRecoveryLookup.Replayed(failure),
                new MeetingRecoveryLookup.Conflict(),
                new MeetingRecoveryLookup.Inconsistent(
                        MeetingRecoveryLookup.InconsistencyReason
                                .OUTCOME_MISSING_WITH_BOUND_RESERVATION),
                new MeetingRecoveryLookup.Unavailable(
                        MeetingRecoveryLookup.UnavailableReason
                                .TRANSIENT_STORAGE_FAILURE,
                        true));

        assertEquals(List.of(
                        MeetingRecoveryLookup.Status.NOT_FOUND,
                        MeetingRecoveryLookup.Status.REPLAYED,
                        MeetingRecoveryLookup.Status.REPLAYED,
                        MeetingRecoveryLookup.Status.CONFLICT,
                        MeetingRecoveryLookup.Status.INCONSISTENT,
                        MeetingRecoveryLookup.Status.UNAVAILABLE),
                results.stream().map(MeetingRecoveryLookup.Result::status).toList());
        assertEquals(EnumSet.of(
                        MeetingRecoveryLookup.InconsistencyReason.STORED_OUTCOME_INVALID,
                        MeetingRecoveryLookup.InconsistencyReason.OUTCOME_MISSING_WITH_MEETING,
                        MeetingRecoveryLookup.InconsistencyReason
                                .OUTCOME_MISSING_WITH_BOUND_RESERVATION,
                        MeetingRecoveryLookup.InconsistencyReason
                                .SUCCESS_OUTCOME_FACT_MISMATCH,
                        MeetingRecoveryLookup.InconsistencyReason
                                .FAILURE_OUTCOME_WITH_MEETING,
                        MeetingRecoveryLookup.InconsistencyReason
                                .FAILURE_OUTCOME_WITH_BOUND_RESERVATION,
                        MeetingRecoveryLookup.InconsistencyReason.RESERVATION_FACT_MISMATCH),
                EnumSet.allOf(MeetingRecoveryLookup.InconsistencyReason.class));
        assertEquals(EnumSet.of(
                        MeetingRecoveryLookup.UnavailableReason.TRANSIENT_STORAGE_FAILURE,
                        MeetingRecoveryLookup.UnavailableReason.STORAGE_FAILURE),
                EnumSet.allOf(MeetingRecoveryLookup.UnavailableReason.class));

        assertThrows(NullPointerException.class,
                () -> new MeetingRecoveryLookup.Replayed(null));
        assertThrows(NullPointerException.class,
                () -> new MeetingRecoveryLookup.Inconsistent(null));
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingRecoveryLookup.Unavailable(
                        MeetingRecoveryLookup.UnavailableReason.STORAGE_FAILURE, true));
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingRecoveryLookup.Unavailable(
                        MeetingRecoveryLookup.UnavailableReason
                                .TRANSIENT_STORAGE_FAILURE,
                        false));
    }

    @Test
    void everyResultToStringHidesOutcomeAndRequestFacts() {
        MeetingCreateOutcome success = MeetingCreateOutcome.success(
                new MeetingCommandResult(
                        MeetingCommandResult.Operation.CREATED, ownedSnapshot()));
        List<MeetingRecoveryLookup.Result> results = List.of(
                new MeetingRecoveryLookup.NotFound(),
                new MeetingRecoveryLookup.Replayed(success),
                new MeetingRecoveryLookup.Replayed(MeetingCreateOutcome.failure(
                        new MeetingCreateFailure(
                                OwnedMeetingCreationUnitOfWork.ErrorCode.INTERNAL,
                                false))),
                new MeetingRecoveryLookup.Conflict(),
                new MeetingRecoveryLookup.Inconsistent(
                        MeetingRecoveryLookup.InconsistencyReason
                                .FAILURE_OUTCOME_WITH_BOUND_RESERVATION),
                new MeetingRecoveryLookup.Unavailable(
                        MeetingRecoveryLookup.UnavailableReason.STORAGE_FAILURE,
                        false));

        String output = results.toString();
        for (String secret : Set.of(
                DEPLOYMENT,
                SUBJECT,
                MEETING,
                "recovery-session-secret",
                "recovery-key-secret",
                "recovery-digest-secret",
                "recovery-grant-secret",
                "recovery-reservation-secret")) {
            assertFalse(output.contains(secret), "toString 泄露字段: " + secret);
        }
    }

    private static OwnedMeetingCreationUnitOfWork.CreateRequest request() {
        UsageReservation reservation = new UsageReservation(
                "recovery-reservation-secret", DEPLOYMENT, SUBJECT,
                "recovery-grant-secret", MEETING, null, null,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                null, CREATED, CREATED.plusSeconds(300),
                UsageReservation.State.RESERVED, 0, null, null, null);
        AuthorizedProfileSnapshot profile = new AuthorizedProfileSnapshot(
                "recovery-profile-secret", 1,
                Set.of("transcript.realtime"),
                Set.of("transcript.realtime"), 60_000);
        UsageOperationResult reserve = UsageOperationResult.reserve(
                DEPLOYMENT, SUBJECT, "recovery-reserve-key-secret",
                "recovery-reserve-digest-secret", CREATED.plusSeconds(600),
                profile, reservation);
        return new OwnedMeetingCreationUnitOfWork.CreateRequest(
                DEPLOYMENT, SUBJECT, MEETING, "recovery-session-secret",
                "recovery-key-secret", "recovery-digest-secret", reserve);
    }

    private static MeetingAggregate.MeetingSnapshot ownedSnapshot() {
        return MeetingAggregate.MeetingSnapshot.owned(
                MEETING, DEPLOYMENT, "recovery-session-secret", SUBJECT,
                "recovery-grant-secret", CREATED.plusSeconds(600),
                "recovery-reservation-secret", "recovery-profile-secret", 1,
                MeetingState.CREATED, CREATED, CREATED);
    }
}
