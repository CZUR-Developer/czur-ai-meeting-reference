package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
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

/** owned 会议停止工作单元的纯 Java 公共契约门禁。 */
class OwnedMeetingStopUnitOfWorkContractTest {
    private static final Instant CREATED = Instant.parse("2026-09-17T10:00:00Z");
    private static final String DEPLOYMENT = "deployment-private";
    private static final String SUBJECT = "subject-private";
    private static final String KEY = "stop-key-private";
    private static final String DIGEST = "stop-digest-private";

    @Test
    void stopRequestContainsOnlyFrozenInputsAndRedactsSensitiveFields() {
        OwnedMeetingStopUnitOfWork.StopRequest request = request();

        assertEquals(List.of(
                        "context", "meetingId", "idempotencyKey",
                        "canonicalRequestDigest"),
                Arrays.stream(OwnedMeetingStopUnitOfWork.StopRequest.class
                                .getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertFalse(request.toString().contains(DEPLOYMENT));
        assertFalse(request.toString().contains(SUBJECT));
        assertFalse(request.toString().contains(KEY));
        assertFalse(request.toString().contains(DIGEST));
        assertFalse(request.toString().contains("credential-private"));

        assertThrows(NullPointerException.class, () ->
                new OwnedMeetingStopUnitOfWork.StopRequest(
                        null, "meeting-owned", KEY, DIGEST));
        assertThrows(IllegalArgumentException.class, () ->
                new OwnedMeetingStopUnitOfWork.StopRequest(
                        context(), " ", KEY, DIGEST));
        assertThrows(IllegalArgumentException.class, () ->
                new OwnedMeetingStopUnitOfWork.StopRequest(
                        context(), "meeting-owned", " ", DIGEST));
        assertThrows(IllegalArgumentException.class, () ->
                new OwnedMeetingStopUnitOfWork.StopRequest(
                        context(), "meeting-owned", KEY, null));
    }

    @Test
    void resultCodesRetryPolicyAndSuccessShapeAreFrozen() {
        assertEquals(EnumSet.of(
                        OwnedMeetingStopUnitOfWork.ErrorCode.MEETING_NOT_FOUND,
                        OwnedMeetingStopUnitOfWork.ErrorCode.ACCESS_DENIED,
                        OwnedMeetingStopUnitOfWork.ErrorCode.IDEMPOTENCY_CONFLICT,
                        OwnedMeetingStopUnitOfWork.ErrorCode.INVALID_MEETING_STATE,
                        OwnedMeetingStopUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE,
                        OwnedMeetingStopUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                        OwnedMeetingStopUnitOfWork.ErrorCode.INTERNAL),
                EnumSet.allOf(OwnedMeetingStopUnitOfWork.ErrorCode.class));
        for (OwnedMeetingStopUnitOfWork.ErrorCode code
                : OwnedMeetingStopUnitOfWork.ErrorCode.values()) {
            boolean expectedRetryable =
                    code == OwnedMeetingStopUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE
                            || code == OwnedMeetingStopUnitOfWork.ErrorCode.OUTCOME_UNKNOWN;
            assertEquals(expectedRetryable, code.retryable(), code.name());
            assertEquals(expectedRetryable,
                    new OwnedMeetingStopUnitOfWork.Failure(code).retryable());
            assertThrows(IllegalArgumentException.class, () ->
                    new OwnedMeetingStopUnitOfWork.Failure(
                            code, !code.retryable()));
        }

        var stopping = new OwnedMeetingStopUnitOfWork.Success(
                OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED,
                new MeetingCommandResult(
                        MeetingCommandResult.Operation.STOP_ACCEPTED,
                        owned(MeetingState.STOPPING)));
        var finalizing = new OwnedMeetingStopUnitOfWork.Success(
                OwnedMeetingStopUnitOfWork.SuccessCode.REPLAYED,
                new MeetingCommandResult(
                        MeetingCommandResult.Operation.STOP_ACCEPTED,
                        owned(MeetingState.FINALIZING)));
        assertFalse(stopping.toString().contains(SUBJECT));
        assertFalse(stopping.toString().contains("grant-private"));
        assertFalse(finalizing.toString().contains("reservation-private"));

        assertThrows(IllegalArgumentException.class, () ->
                new OwnedMeetingStopUnitOfWork.Success(
                        OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED,
                        new MeetingCommandResult(
                                MeetingCommandResult.Operation.CREATED,
                                owned(MeetingState.STOPPING))));
        assertThrows(IllegalArgumentException.class, () ->
                new OwnedMeetingStopUnitOfWork.Success(
                        OwnedMeetingStopUnitOfWork.SuccessCode.ACCEPTED,
                        new MeetingCommandResult(
                                MeetingCommandResult.Operation.STOP_ACCEPTED,
                                owned(MeetingState.RUNNING))));
    }

    private static OwnedMeetingStopUnitOfWork.StopRequest request() {
        return new OwnedMeetingStopUnitOfWork.StopRequest(
                context(), "meeting-owned", KEY, DIGEST);
    }

    private static MeetingCommandContext context() {
        return new MeetingCommandContext(
                DEPLOYMENT,
                new VerifiedIdentity(
                        new Subject(SUBJECT, "organization-private"),
                        Set.of("ROLE_PRIVATE"),
                        Set.of(MeetingPermissions.STOP),
                        CREATED.minusSeconds(60),
                        CREATED.plusSeconds(60),
                        "credential-private"));
    }

    private static MeetingAggregate.MeetingSnapshot owned(MeetingState state) {
        return MeetingAggregate.MeetingSnapshot.owned(
                "meeting-owned", DEPLOYMENT, "session-private", SUBJECT,
                "grant-private", CREATED.plusSeconds(600), "reservation-private",
                "profile-private", 3, state, CREATED, CREATED.plusSeconds(1));
    }
}
