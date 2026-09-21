package com.czur.reference.application.port;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只验证 durable input 接管协同端口的 Java 值契约，不连接数据库、不实现 Worker。
 */
class DurableInputAcceptanceUnitOfWorkContractTest {
    private static final String MEETING_ID = "meeting-acceptance-sensitive";
    private static final String INPUT_BINDING = "input-binding-sensitive";
    private static final String DIGEST = "sha256-digest-sensitive";
    private static final String RECEIPT = "receipt-sensitive-value";
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-21T03:04:05Z");

    @Test
    void requestRequiresMeetingAndMatchingInputFact() {
        FinalizationInputFact fact = fact(MEETING_ID);

        assertThrows(IllegalArgumentException.class,
                () -> new DurableInputAcceptanceUnitOfWork.AcceptRequest(null, fact));
        assertThrows(IllegalArgumentException.class,
                () -> new DurableInputAcceptanceUnitOfWork.AcceptRequest(" ", fact));
        assertThrows(NullPointerException.class,
                () -> new DurableInputAcceptanceUnitOfWork.AcceptRequest(MEETING_ID, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DurableInputAcceptanceUnitOfWork.AcceptRequest(
                        "another-meeting", fact));
    }

    @Test
    void resultRequiresBothStateMachineResults() {
        MeetingFinalizationStore.Result finalization = acceptedApplied();

        assertThrows(NullPointerException.class,
                () -> new DurableInputAcceptanceUnitOfWork.Result(null, finalization));
        assertThrows(NullPointerException.class,
                () -> new DurableInputAcceptanceUnitOfWork.Result(
                        DurableInputFactStore.PersistStatus.APPLIED, null));
    }

    @Test
    void appliedAndReplayedStatusesRemainVisibleTogether() {
        for (DurableInputFactStore.PersistStatus inputStatus : List.of(
                DurableInputFactStore.PersistStatus.APPLIED,
                DurableInputFactStore.PersistStatus.REPLAYED)) {
            for (MeetingFinalizationStore.Result finalization : List.of(
                    acceptedApplied(), acceptedReplayed())) {
                DurableInputAcceptanceUnitOfWork.Result result =
                        new DurableInputAcceptanceUnitOfWork.Result(inputStatus, finalization);

                assertEquals(inputStatus, result.inputStatus());
                assertEquals(finalization, result.finalization());
                assertEquals(inputStatus == DurableInputFactStore.PersistStatus.APPLIED,
                        result.inputApplied());
                assertTrue(result.receiptAvailable());
            }
        }
    }

    @Test
    void foundAcceptedIsOnlyAReplayedCommitUnknownReadback() {
        DurableInputAcceptanceUnitOfWork.Result readback =
                new DurableInputAcceptanceUnitOfWork.Result(
                        DurableInputFactStore.PersistStatus.REPLAYED,
                        new MeetingFinalizationStore.Found(FinalizationFact.accepted(RECEIPT)));
        assertTrue(readback.receiptAvailable());
        assertEquals(MeetingFinalizationStore.Status.FOUND,
                readback.finalization().status());

        assertThrows(IllegalArgumentException.class,
                () -> new DurableInputAcceptanceUnitOfWork.Result(
                        DurableInputFactStore.PersistStatus.APPLIED,
                        new MeetingFinalizationStore.Found(FinalizationFact.accepted(RECEIPT))));
    }

    @Test
    void deterministicFailuresRemainExplicitAndCannotPretendReceiptExists() {
        DurableInputAcceptanceUnitOfWork.Result inputConflict =
                new DurableInputAcceptanceUnitOfWork.Result(
                        DurableInputFactStore.PersistStatus.CONFLICT,
                        new MeetingFinalizationStore.Conflict(
                                MeetingFinalizationStore.ConflictReason.FACT_INCONSISTENT));
        assertEquals(DurableInputFactStore.PersistStatus.CONFLICT, inputConflict.inputStatus());
        assertEquals(MeetingFinalizationStore.Status.CONFLICT,
                inputConflict.finalization().status());
        assertFalse(inputConflict.receiptAvailable());

        DurableInputAcceptanceUnitOfWork.Result missingMeeting =
                new DurableInputAcceptanceUnitOfWork.Result(
                        DurableInputFactStore.PersistStatus.REPLAYED,
                        new MeetingFinalizationStore.NotFound());
        assertFalse(missingMeeting.receiptAvailable());

        DurableInputAcceptanceUnitOfWork.Result finalizationConflict =
                new DurableInputAcceptanceUnitOfWork.Result(
                        DurableInputFactStore.PersistStatus.REPLAYED,
                        new MeetingFinalizationStore.Conflict(
                                MeetingFinalizationStore.ConflictReason.INVALID_INPUT_STATE));
        assertFalse(finalizationConflict.receiptAvailable());
    }

    @Test
    void firstFactWriteCannotBeReturnedWhenFinalizationDidNotCommit() {
        assertThrows(IllegalArgumentException.class,
                () -> new DurableInputAcceptanceUnitOfWork.Result(
                        DurableInputFactStore.PersistStatus.APPLIED,
                        new MeetingFinalizationStore.NotFound()));
        assertThrows(IllegalArgumentException.class,
                () -> new DurableInputAcceptanceUnitOfWork.Result(
                        DurableInputFactStore.PersistStatus.APPLIED,
                        new MeetingFinalizationStore.Conflict(
                                MeetingFinalizationStore.ConflictReason.INVALID_INPUT_STATE)));
    }

    @Test
    void inputConflictCannotBeCombinedWithAcceptedFinalization() {
        assertThrows(IllegalArgumentException.class,
                () -> new DurableInputAcceptanceUnitOfWork.Result(
                        DurableInputFactStore.PersistStatus.CONFLICT,
                        acceptedApplied()));
        assertThrows(IllegalArgumentException.class,
                () -> new DurableInputAcceptanceUnitOfWork.Result(
                        DurableInputFactStore.PersistStatus.CONFLICT,
                        acceptedReplayed()));
    }

    @Test
    void diagnosticsRedactAllSensitiveValues() {
        DurableInputAcceptanceUnitOfWork.AcceptRequest request =
                new DurableInputAcceptanceUnitOfWork.AcceptRequest(
                        MEETING_ID, fact(MEETING_ID));
        DurableInputAcceptanceUnitOfWork.Result result =
                new DurableInputAcceptanceUnitOfWork.Result(
                        DurableInputFactStore.PersistStatus.REPLAYED,
                        acceptedReplayed());

        String requestText = request.toString();
        String resultText = result.toString();
        assertFalse(requestText.contains(MEETING_ID));
        assertFalse(requestText.contains(INPUT_BINDING));
        assertFalse(requestText.contains(DIGEST));
        assertFalse(resultText.contains(MEETING_ID));
        assertFalse(resultText.contains(RECEIPT));
        assertEquals("DurableInputAcceptanceUnitOfWork.AcceptRequest[redacted]", requestText);
        assertEquals(
                "DurableInputAcceptanceUnitOfWork.Result[inputStatus=REPLAYED, "
                        + "finalizationStatus=REPLAYED]",
                resultText);
    }

    private static MeetingFinalizationStore.Applied acceptedApplied() {
        return new MeetingFinalizationStore.Applied(FinalizationFact.accepted(RECEIPT));
    }

    private static MeetingFinalizationStore.Replayed acceptedReplayed() {
        return new MeetingFinalizationStore.Replayed(FinalizationFact.accepted(RECEIPT));
    }

    private static FinalizationInputFact fact(String meetingId) {
        return new FinalizationInputFact(
                meetingId,
                INPUT_BINDING,
                17,
                4_200,
                "zh-CN",
                List.of("en-US"),
                ACCEPTED_AT,
                DIGEST);
    }
}
