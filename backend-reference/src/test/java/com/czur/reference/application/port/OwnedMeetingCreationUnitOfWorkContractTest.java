package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** owned 会议创建工作单元的纯 Java 公共契约门禁。 */
class OwnedMeetingCreationUnitOfWorkContractTest {
    private static final Instant CREATED = Instant.parse("2026-09-17T10:00:00Z");
    private static final Instant EXPIRES = CREATED.plusSeconds(600);
    private static final String DEPLOYMENT = "deployment-owned";
    private static final String SUBJECT = "subject-owned";
    private static final String MEETING = "meeting-owned";

    @Test
    void requestDerivesProfileOnlyFromFirstAuthorizedSnapshot() {
        OwnedMeetingCreationUnitOfWork.CreateRequest request = request(reserveResult());

        assertEquals(List.of(
                        "deploymentScope", "subjectScope", "meetingId", "clientSessionId",
                        "idempotencyKey", "canonicalRequestDigest", "reserveResult"),
                Arrays.stream(OwnedMeetingCreationUnitOfWork.CreateRequest.class
                                .getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals("profile-first", request.profileId());
        assertEquals(7, request.profileRevision());
        assertEquals(Set.of("transcript.realtime"),
                request.authorizedProfileSnapshot().requestedCapabilities());
        assertFalse(request.toString().contains(SUBJECT));
        assertFalse(request.toString().contains("canonical-digest-secret"));
        assertFalse(request.toString().contains("reservation-owned"));
    }

    @Test
    void requestRejectsScopeMeetingAndHistoricalReserveMismatches() {
        UsageOperationResult reserve = reserveResult();

        assertThrows(IllegalArgumentException.class, () -> newRequest(
                "other-deployment", SUBJECT, MEETING, reserve));
        assertThrows(IllegalArgumentException.class, () -> newRequest(
                DEPLOYMENT, "other-subject", MEETING, reserve));
        assertThrows(IllegalArgumentException.class, () -> newRequest(
                DEPLOYMENT, SUBJECT, "other-meeting", reserve));

        UsageOperationResult legacy = UsageOperationResult.restoreLegacyV1Reserve(
                DEPLOYMENT, SUBJECT, "reserve-key", "reserve-digest", EXPIRES,
                reserve.reservation());
        assertThrows(IllegalArgumentException.class, () -> request(legacy));
    }

    @Test
    void resultCodesAreFrozenAndFailureContainsNoDynamicMessage() {
        assertEquals(EnumSet.of(
                        OwnedMeetingCreationUnitOfWork.ErrorCode.IDEMPOTENCY_CONFLICT,
                        OwnedMeetingCreationUnitOfWork.ErrorCode.RESERVATION_NOT_FOUND,
                        OwnedMeetingCreationUnitOfWork.ErrorCode.RESERVATION_EXPIRED,
                        OwnedMeetingCreationUnitOfWork.ErrorCode.RESERVATION_STATE_CONFLICT,
                        OwnedMeetingCreationUnitOfWork.ErrorCode.VERSION_CONFLICT,
                        OwnedMeetingCreationUnitOfWork.ErrorCode.MEETING_CONFLICT,
                        OwnedMeetingCreationUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE,
                        OwnedMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                        OwnedMeetingCreationUnitOfWork.ErrorCode.INTERNAL),
                EnumSet.allOf(OwnedMeetingCreationUnitOfWork.ErrorCode.class));

        OwnedMeetingCreationUnitOfWork.Failure failure =
                new OwnedMeetingCreationUnitOfWork.Failure(
                        OwnedMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, true);
        assertEquals("Failure[code=OUTCOME_UNKNOWN, retryable=true]", failure.toString());

        var success = new OwnedMeetingCreationUnitOfWork.Success(
                OwnedMeetingCreationUnitOfWork.SuccessCode.CREATED,
                new MeetingCommandResult(MeetingCommandResult.Operation.CREATED,
                        ownedSnapshot()));
        assertEquals(OwnedMeetingCreationUnitOfWork.SuccessCode.CREATED, success.code());
        assertFalse(success.toString().contains(SUBJECT));
        assertFalse(success.toString().contains("grant-owned"));
        assertFalse(success.toString().contains("reservation-owned"));
        assertThrows(IllegalArgumentException.class, () ->
                new OwnedMeetingCreationUnitOfWork.Success(
                        OwnedMeetingCreationUnitOfWork.SuccessCode.CREATED,
                        new MeetingCommandResult(MeetingCommandResult.Operation.STOP_ACCEPTED,
                                ownedSnapshot())));
    }

    private static OwnedMeetingCreationUnitOfWork.CreateRequest request(
            UsageOperationResult reserve) {
        return newRequest(DEPLOYMENT, SUBJECT, MEETING, reserve);
    }

    private static OwnedMeetingCreationUnitOfWork.CreateRequest newRequest(
            String deployment,
            String subject,
            String meeting,
            UsageOperationResult reserve) {
        return new OwnedMeetingCreationUnitOfWork.CreateRequest(
                deployment, subject, meeting, "client-session-owned",
                "meeting-create-key", "canonical-digest-secret", reserve);
    }

    private static UsageOperationResult reserveResult() {
        MeetingGrant grant = grant();
        UsageReservation reservation = UsageReservation.reserved(
                "reservation-owned", grant, MEETING,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                CREATED, CREATED.plusSeconds(300));
        return UsageOperationResult.reserve(
                DEPLOYMENT, SUBJECT, "reserve-key", "reserve-digest", EXPIRES,
                AuthorizedProfileSnapshot.from(grant), reservation);
    }

    private static MeetingGrant grant() {
        return new MeetingGrant(
                "grant-owned", DEPLOYMENT, SUBJECT, "profile-first", 7,
                Set.of("transcript.realtime"), Set.of("transcript.realtime"),
                3_600_000, CREATED.minusSeconds(1), EXPIRES);
    }

    private static MeetingAggregate.MeetingSnapshot ownedSnapshot() {
        return MeetingAggregate.MeetingSnapshot.owned(
                MEETING, DEPLOYMENT, "client-session-owned", SUBJECT,
                "grant-owned", EXPIRES, "reservation-owned",
                "profile-first", 7, MeetingState.CREATED, CREATED, CREATED);
    }
}
