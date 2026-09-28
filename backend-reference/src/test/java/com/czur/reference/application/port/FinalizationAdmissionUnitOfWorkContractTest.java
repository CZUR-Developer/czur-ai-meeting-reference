package com.czur.reference.application.port;

import com.czur.reference.application.port.entitlement.UsageActual;
import com.czur.reference.application.port.entitlement.UsageUnit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalizationAdmissionUnitOfWorkContractTest {
    private static final Instant ACCEPTED_AT =
            Instant.parse("2026-09-28T08:10:00.123456Z");

    @Test
    void requestRequiresOwnedMatchingMeetingAndPersistentTimestamp() {
        FinalizationInputFact input = input("meeting-admission");
        var request = new FinalizationAdmissionUnitOfWork.AdmissionRequest(
                "deployment", "subject", "meeting-admission", input);
        assertEquals("meeting-admission", request.meetingId());
        assertFalse(request.toString().contains("meeting-admission"));

        assertThrows(IllegalArgumentException.class,
                () -> new FinalizationAdmissionUnitOfWork.AdmissionRequest(
                        "deployment", "LEGACY_UNOWNED", "meeting-admission", input));
        assertThrows(IllegalArgumentException.class,
                () -> new FinalizationAdmissionUnitOfWork.AdmissionRequest(
                        "deployment", "subject", "other-meeting", input));
        FinalizationInputFact nanos = new FinalizationInputFact(
                "meeting-admission", "binding", 1, 100,
                "zh-CN", List.of(),
                Instant.parse("2026-09-28T08:10:00.123456789Z"),
                "sha256:input");
        assertThrows(IllegalArgumentException.class,
                () -> new FinalizationAdmissionUnitOfWork.AdmissionRequest(
                        "deployment", "subject", "meeting-admission", nanos));
    }

    @Test
    void successRequiresAcceptedFactAndMatchingWorkflowIdentity() {
        FinalUsageFact usage = FinalUsageFact.restore(
                "meeting-admission",
                new UsageActual(UsageUnit.MEETING_DURATION_MS, 100),
                "binding", "sha256:input", ACCEPTED_AT);
        WorkflowStep.Key key = new WorkflowStep.Key(
                "meeting-admission", "finalization", "stable-key");
        var success = new FinalizationAdmissionUnitOfWork.Success(
                FinalizationAdmissionUnitOfWork.SuccessCode.ADMITTED,
                FinalizationFact.accepted("receipt"),
                usage,
                key);
        assertEquals(FinalizationAdmissionUnitOfWork.SuccessCode.ADMITTED,
                success.code());
        assertFalse(success.toString().contains("receipt"));

        assertThrows(IllegalArgumentException.class,
                () -> new FinalizationAdmissionUnitOfWork.Success(
                        FinalizationAdmissionUnitOfWork.SuccessCode.ADMITTED,
                        FinalizationFact.pending(InputState.INPUT_COMPLETE_PENDING),
                        usage,
                        key));
        assertThrows(IllegalArgumentException.class,
                () -> new FinalizationAdmissionUnitOfWork.Success(
                        FinalizationAdmissionUnitOfWork.SuccessCode.ADMITTED,
                        FinalizationFact.accepted("receipt"),
                        usage,
                        new WorkflowStep.Key("other-meeting", "finalization", "stable-key")));
    }

    @Test
    void retryablePolicyIsStable() {
        assertTrue(FinalizationAdmissionUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE.retryable());
        assertTrue(FinalizationAdmissionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN.retryable());
        assertFalse(FinalizationAdmissionUnitOfWork.ErrorCode.ADMISSION_NOT_FOUND.retryable());
        assertFalse(FinalizationAdmissionUnitOfWork.ErrorCode.FACT_INCONSISTENT.retryable());
        assertThrows(IllegalArgumentException.class,
                () -> new FinalizationAdmissionUnitOfWork.Failure(
                        FinalizationAdmissionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                        false));
    }

    private static FinalizationInputFact input(String meetingId) {
        return new FinalizationInputFact(
                meetingId,
                "binding",
                1,
                100,
                "zh-CN",
                List.of("en-US"),
                ACCEPTED_AT,
                "sha256:input");
    }
}
