package com.czur.reference.application.port;

import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MeetingStartExecutionUnitOfWork} 公共输入、结果和脱敏契约门禁。 */
class MeetingStartExecutionUnitOfWorkContractTest {
    private static final String MEETING_ID = "meeting-start-contract";
    private static final Instant CREATED_AT = Instant.parse("2026-09-28T08:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-09-28T08:00:01.123456Z");

    @Test
    void requestDefensivelyCopiesCapabilitiesAndRedactsDiagnostics() {
        Set<String> requested = new HashSet<>(Set.of("asr"));
        var request = new MeetingStartExecutionUnitOfWork.StartRequest(
                "deployment", "subject", MEETING_ID, STARTED_AT, "敏感标题",
                profile(), requested, new CapabilityDescriptor(Set.of("asr")),
                DeploymentLimits.NONE);
        requested.add("summary");

        assertEquals(Set.of("asr"), request.requestedCapabilities());
        assertThrows(UnsupportedOperationException.class,
                () -> request.requestedCapabilities().add("other"));
        assertEquals("MeetingStartExecutionUnitOfWork.StartRequest[redacted]",
                request.toString());
        assertFalse(request.toString().contains(MEETING_ID));
        assertFalse(request.toString().contains("敏感标题"));
    }

    @Test
    void requestRejectsLegacySubjectSubMicrosecondTimeAndInvalidProfileText() {
        assertThrows(IllegalArgumentException.class, () -> request(
                "LEGACY_UNOWNED", STARTED_AT, profile()));
        assertThrows(IllegalArgumentException.class, () -> request(
                "subject", STARTED_AT.plusNanos(1), profile()));
        assertThrows(IllegalArgumentException.class, () -> request(
                "subject", Instant.parse("+10000-01-01T00:00:00Z"), profile()));
        assertThrows(IllegalArgumentException.class, () -> new MeetingStartExecutionUnitOfWork.StartRequest(
                "deployment", "subject", MEETING_ID, STARTED_AT, null,
                profile(), Set.of(), new CapabilityDescriptor(Set.of("asr")), null));
    }

    @Test
    void failureRetryabilityIsOwnedByErrorCode() {
        for (MeetingStartExecutionUnitOfWork.ErrorCode code
                : MeetingStartExecutionUnitOfWork.ErrorCode.values()) {
            var failure = new MeetingStartExecutionUnitOfWork.Failure(code);
            assertEquals(code.retryable(), failure.retryable());
            assertThrows(IllegalArgumentException.class,
                    () -> new MeetingStartExecutionUnitOfWork.Failure(
                            code, !code.retryable()));
        }
        assertTrue(new MeetingStartExecutionUnitOfWork.Failure(
                MeetingStartExecutionUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE).retryable());
        assertTrue(new MeetingStartExecutionUnitOfWork.Failure(
                MeetingStartExecutionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN).retryable());
    }

    @Test
    void successRequiresPostStartOwnedMeetingAndRedactsFact() {
        MeetingAggregate starting = owned();
        starting.apply(MeetingEvent.AUTHORIZE, STARTED_AT);
        starting.apply(MeetingEvent.START, STARTED_AT);
        FinalizationExecutionFact fact = fact();
        var started = new MeetingStartExecutionUnitOfWork.Success(
                MeetingStartExecutionUnitOfWork.SuccessCode.STARTED,
                starting.snapshot(), fact);

        assertEquals("MeetingStartExecutionUnitOfWork.Success[code=STARTED, "
                        + "meetingState=STARTING, fact=redacted]",
                started.toString());
        assertFalse(started.toString().contains(MEETING_ID));
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingStartExecutionUnitOfWork.Success(
                        MeetingStartExecutionUnitOfWork.SuccessCode.REPLAYED,
                        owned().snapshot(), fact));
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingStartExecutionUnitOfWork.Success(
                        MeetingStartExecutionUnitOfWork.SuccessCode.STARTED,
                        starting.snapshot(), FinalizationExecutionFact.freeze(
                                "other-meeting", STARTED_AT, null, profile(), plan())));

        MeetingAggregate wrongProfile = MeetingAggregate.owned(
                MEETING_ID, "deployment", "session", "subject", "grant",
                CREATED_AT.plusSeconds(3_600), "reservation", "other-profile", 1,
                CREATED_AT);
        wrongProfile.apply(MeetingEvent.AUTHORIZE, STARTED_AT);
        wrongProfile.apply(MeetingEvent.START, STARTED_AT);
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingStartExecutionUnitOfWork.Success(
                        MeetingStartExecutionUnitOfWork.SuccessCode.STARTED,
                        wrongProfile.snapshot(), fact));

        MeetingAggregate wrongStartTime = owned();
        wrongStartTime.apply(MeetingEvent.AUTHORIZE, STARTED_AT.plusSeconds(1));
        wrongStartTime.apply(MeetingEvent.START, STARTED_AT.plusSeconds(1));
        assertThrows(IllegalArgumentException.class,
                () -> new MeetingStartExecutionUnitOfWork.Success(
                        MeetingStartExecutionUnitOfWork.SuccessCode.STARTED,
                        wrongStartTime.snapshot(), fact));
    }

    private static MeetingStartExecutionUnitOfWork.StartRequest request(
            String subject,
            Instant startedAt,
            ProductProfile profile) {
        return new MeetingStartExecutionUnitOfWork.StartRequest(
                "deployment", subject, MEETING_ID, startedAt, null, profile,
                Set.of("asr"), new CapabilityDescriptor(Set.of("asr")), null);
    }

    private static MeetingAggregate owned() {
        return MeetingAggregate.owned(
                MEETING_ID, "deployment", "session", "subject", "grant",
                CREATED_AT.plusSeconds(3_600), "reservation", "profile", 1,
                CREATED_AT);
    }

    private static FinalizationExecutionFact fact() {
        return FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, "敏感标题", profile(), plan());
    }

    private static com.czur.reference.domain.capability.CapabilityPlan plan() {
        return new CapabilityPlanner().plan(
                new CapabilityDescriptor(Set.of("asr")), profile(), Set.of("asr"),
                Set.of("asr"), DeploymentLimits.NONE, 1L);
    }

    private static ProductProfile profile() {
        return new ProductProfile(
                "profile", 1, Set.of("asr"), Set.of(), Map.of(), Map.of(), Map.of(),
                Map.of("asr", List.of("caption")),
                Map.of("asr", List.of("transcript.final")));
    }
}
