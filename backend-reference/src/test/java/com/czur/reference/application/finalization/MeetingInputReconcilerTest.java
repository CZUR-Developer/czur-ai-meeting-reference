package com.czur.reference.application.finalization;

import com.czur.reference.application.port.AcceptedInputFactLookup;
import com.czur.reference.application.port.CompletedAudioReadinessPort;
import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.FinalizationAdmissionUnitOfWork;
import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.MeetingInputClosureContextPort;
import com.czur.reference.application.port.WorkflowStep;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeetingInputReconcilerTest {
    private static final String ID = "mtg_reconcile";
    private static final String DIGEST = "sha256-v1:" + "a".repeat(64);
    private static final Instant STOP_AT = Instant.parse("2026-10-02T08:00:01.123456Z");
    private static final CompletedAudioReadinessPort.Ready READY =
            new CompletedAudioReadinessPort.Ready(ID, 2, 400, DIGEST);

    @Test
    void stopNotSubmittedDoesNotInspectAudioOrIssueReceipt() {
        FakeAdmission admission = new FakeAdmission();
        AtomicInteger audioCalls = new AtomicInteger();
        MeetingInputReconciler reconciler = new MeetingInputReconciler(
                ignored -> new MeetingInputClosureContextPort.WaitingForStop(),
                ignored -> new AcceptedInputFactLookup.Missing(),
                ignored -> { audioCalls.incrementAndGet(); return READY; },
                admission, clock(STOP_AT));

        assertEquals(MeetingInputReconciler.PendingReason.STOP_NOT_REQUESTED,
                assertInstanceOf(MeetingInputReconciler.Pending.class,
                        reconciler.reconcile(ID)).reason());
        assertEquals(0, audioCalls.get());
        assertEquals(0, admission.admitCalls);
    }

    @Test
    void completionBeforeLastFrameRemainsPendingWithoutReceipt() {
        FakeAdmission admission = new FakeAdmission();
        MeetingInputReconciler reconciler = new MeetingInputReconciler(
                ignored -> context(InputState.INPUT_COMPLETE_PENDING),
                ignored -> new AcceptedInputFactLookup.Missing(),
                ignored -> new CompletedAudioReadinessPort.Pending(),
                admission, clock(STOP_AT));

        assertEquals(MeetingInputReconciler.PendingReason.AUDIO_NOT_READY,
                assertInstanceOf(MeetingInputReconciler.Pending.class,
                        reconciler.reconcile(ID)).reason());
        assertEquals(0, admission.admitCalls);
    }

    @Test
    void firstAdmissionUsesTrustedAsrOnlyFactsAndMicrosecondClockAfterStop() {
        FakeAdmission admission = new FakeAdmission();
        Instant now = Instant.parse("2026-10-02T08:00:02.123456789Z");
        MeetingInputReconciler reconciler = reconciler(
                context(InputState.INPUT_COMPLETE_PENDING),
                ignored -> new AcceptedInputFactLookup.Missing(),
                ignored -> READY, admission, clock(now));

        assertInstanceOf(MeetingInputReconciler.Accepted.class, reconciler.reconcile(ID));
        FinalizationInputFact input = admission.admitted.inputFact();
        assertEquals(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS), input.acceptedAt());
        assertEquals(ID, input.inputBinding());
        assertEquals("zh-CN", input.sourceLanguage());
        assertEquals(List.of(), input.targetLanguages());
        assertEquals(DIGEST, input.contentDigest());
        assertEquals(1, admission.admitCalls);
        assertEquals(0, admission.recoverCalls);
        assertFalse(admission.admitted.toString().contains("zh-CN"));
    }

    @Test
    void clockBehindStopUsesStopLowerBound() {
        FakeAdmission admission = new FakeAdmission();
        MeetingInputReconciler reconciler = reconciler(
                context(InputState.INPUT_COMPLETE_PENDING),
                ignored -> new AcceptedInputFactLookup.Missing(),
                ignored -> READY, admission, clock(STOP_AT.minusSeconds(5)));

        assertInstanceOf(MeetingInputReconciler.Accepted.class, reconciler.reconcile(ID));
        assertEquals(STOP_AT, admission.admitted.inputFact().acceptedAt());
    }

    @Test
    void acceptedWinnerReplaysItsOriginalTimeWithoutAudioOrNewClock() {
        FakeAdmission admission = new FakeAdmission();
        FinalizationInputFact winner = input(STOP_AT.plusSeconds(1));
        AtomicInteger audioCalls = new AtomicInteger();
        MeetingInputReconciler reconciler = reconciler(
                context(InputState.INPUT_ACCEPTED),
                ignored -> new AcceptedInputFactLookup.Found(winner),
                ignored -> { audioCalls.incrementAndGet(); return READY; },
                admission, clock(STOP_AT.plusSeconds(200)));

        assertInstanceOf(MeetingInputReconciler.Accepted.class, reconciler.reconcile(ID));
        assertEquals(winner, admission.recovered.inputFact());
        assertEquals(0, admission.admitCalls);
        assertEquals(0, audioCalls.get());
    }

    @Test
    void concurrentAdmissionConflictRecoversWinnerNotNewTimestamp() {
        FakeAdmission admission = new FakeAdmission();
        admission.admitResult = new FinalizationAdmissionUnitOfWork.Failure(
                FinalizationAdmissionUnitOfWork.ErrorCode.INPUT_FACT_CONFLICT);
        FinalizationInputFact winner = input(STOP_AT.plusSeconds(1));
        AtomicInteger reads = new AtomicInteger();
        MeetingInputReconciler reconciler = reconciler(
                context(InputState.INPUT_COMPLETE_PENDING),
                ignored -> reads.incrementAndGet() == 1
                        ? new AcceptedInputFactLookup.Missing()
                        : new AcceptedInputFactLookup.Found(winner),
                ignored -> READY, admission, clock(STOP_AT.plusSeconds(2)));

        assertInstanceOf(MeetingInputReconciler.Accepted.class, reconciler.reconcile(ID));
        assertEquals(STOP_AT.plusSeconds(2), admission.admitted.inputFact().acceptedAt());
        assertEquals(winner, admission.recovered.inputFact());
        assertEquals(1, admission.admitCalls);
        assertEquals(1, admission.recoverCalls);
    }

    @Test
    void unknownCommitRecoversOriginalRequestAndReturnsConfirmedReceipt() {
        FakeAdmission admission = new FakeAdmission();
        admission.admitResult = new FinalizationAdmissionUnitOfWork.Failure(
                FinalizationAdmissionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN);
        AtomicInteger winnerReads = new AtomicInteger();
        MeetingInputReconciler reconciler = reconciler(
                context(InputState.INPUT_COMPLETE_PENDING),
                ignored -> { winnerReads.incrementAndGet();
                    return new AcceptedInputFactLookup.Missing(); },
                ignored -> READY, admission, clock(STOP_AT.plusSeconds(1)));

        MeetingInputReconciler.Accepted accepted = assertInstanceOf(
                MeetingInputReconciler.Accepted.class, reconciler.reconcile(ID));
        assertEquals("receipt", accepted.admission().finalization().finalizationReceipt());
        assertSame(admission.admitted, admission.recovered);
        assertEquals(1, admission.recoverCalls);
        assertEquals(1, winnerReads.get());
    }

    @Test
    void unknownCommitExplicitlyNotFoundHasNoReceiptAndCanStartLaterAttempt() {
        FakeAdmission admission = new FakeAdmission();
        admission.admitResult = new FinalizationAdmissionUnitOfWork.Failure(
                FinalizationAdmissionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN);
        admission.recoverResult = new FinalizationAdmissionUnitOfWork.Failure(
                FinalizationAdmissionUnitOfWork.ErrorCode.ADMISSION_NOT_FOUND);
        MeetingInputReconciler reconciler = reconciler(
                context(InputState.INPUT_COMPLETE_PENDING),
                ignored -> new AcceptedInputFactLookup.Missing(),
                ignored -> READY, admission, clock(STOP_AT.plusSeconds(1)));

        MeetingInputReconciler.Failure failure = assertInstanceOf(
                MeetingInputReconciler.Failure.class, reconciler.reconcile(ID));
        assertEquals(MeetingInputReconciler.FailureReason.ADMISSION_NOT_FOUND,
                failure.reason());
        assertTrue(failure.retryable());
        assertSame(admission.admitted, admission.recovered);
        assertEquals(1, admission.recoverCalls);
    }

    @Test
    void badWinnerOrUnavailableAudioDoesNotEnterAdmission() {
        FakeAdmission admission = new FakeAdmission();
        FinalizationInputFact wrongLanguage = new FinalizationInputFact(
                ID, ID, 2, 400, "en-US", List.of(), STOP_AT.plusSeconds(1), DIGEST);
        var badWinner = reconciler(context(InputState.INPUT_ACCEPTED),
                ignored -> new AcceptedInputFactLookup.Found(wrongLanguage),
                ignored -> READY, admission, clock(STOP_AT));
        assertEquals(MeetingInputReconciler.FailureReason.INCONSISTENT,
                assertInstanceOf(MeetingInputReconciler.Failure.class,
                        badWinner.reconcile(ID)).reason());

        var unavailable = reconciler(context(InputState.INPUT_COMPLETE_PENDING),
                ignored -> new AcceptedInputFactLookup.Missing(),
                ignored -> new CompletedAudioReadinessPort.Unavailable(),
                admission, clock(STOP_AT));
        assertEquals(MeetingInputReconciler.FailureReason.UNAVAILABLE,
                assertInstanceOf(MeetingInputReconciler.Failure.class,
                        unavailable.reconcile(ID)).reason());
        assertEquals(0, admission.admitCalls);
        assertEquals(0, admission.recoverCalls);
    }

    private static MeetingInputClosureContextPort.Result context(InputState state) {
        return new MeetingInputClosureContextPort.Found(
                new MeetingInputClosureContextPort.Context(
                        "deployment", "subject", ID, state, STOP_AT, "zh-CN"));
    }

    private static FinalizationInputFact input(Instant acceptedAt) {
        return new FinalizationInputFact(ID, ID, 2, 400, "zh-CN", List.of(),
                acceptedAt, DIGEST);
    }

    private static Clock clock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static MeetingInputReconciler reconciler(
            MeetingInputClosureContextPort.Result context,
            AcceptedInputFactLookup lookup,
            CompletedAudioReadinessPort readiness,
            FakeAdmission admission, Clock clock) {
        return new MeetingInputReconciler(ignored -> context, lookup, readiness,
                admission, clock);
    }

    private static final class FakeAdmission implements FinalizationAdmissionUnitOfWork {
        private Result admitResult;
        private Result recoverResult;
        private int admitCalls;
        private int recoverCalls;
        private AdmissionRequest admitted;
        private AdmissionRequest recovered;

        @Override public Result admit(AdmissionRequest request) {
            admitted = request;
            admitCalls++;
            return admitResult == null ? success(request) : admitResult;
        }

        @Override public Result recover(AdmissionRequest request) {
            recovered = request;
            recoverCalls++;
            return recoverResult == null ? success(request, SuccessCode.REPLAYED)
                    : recoverResult;
        }

        private static Result success(AdmissionRequest request) {
            return success(request, SuccessCode.ADMITTED);
        }

        private static Result success(AdmissionRequest request, SuccessCode code) {
            return new Success(code,
                    FinalizationFact.accepted("receipt"),
                    FinalUsageFact.fromInput(request.inputFact()),
                    new WorkflowStep.Key(request.meetingId(), "finalization", "stable"));
        }
    }
}
