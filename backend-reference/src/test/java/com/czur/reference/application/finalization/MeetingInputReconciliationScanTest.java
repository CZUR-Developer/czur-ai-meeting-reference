package com.czur.reference.application.finalization;

import com.czur.reference.application.port.AcceptedInputFactLookup;
import com.czur.reference.application.port.CompletedAudioReadinessPort;
import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.FinalizationAdmissionUnitOfWork;
import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.MeetingInputClosureContextPort;
import com.czur.reference.application.port.WorkflowStep;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MeetingInputReconciliationScanTest {
    @Test
    void pendingFirstPageCannotStarveLaterMeetingAndIsRetriedAfterWrap() {
        List<String> outstanding = new ArrayList<>(List.of("mtg_first", "mtg_second"));
        AtomicBoolean firstAudioReady = new AtomicBoolean();
        Instant now = Instant.parse("2026-10-02T08:00:02Z");
        FinalizationAdmissionUnitOfWork admission = new FinalizationAdmissionUnitOfWork() {
            @Override public Result admit(AdmissionRequest request) {
                outstanding.remove(request.meetingId());
                return success(request);
            }
            @Override public Result recover(AdmissionRequest request) {
                return success(request);
            }
            private Result success(AdmissionRequest request) {
                return new Success(SuccessCode.ADMITTED,
                        FinalizationFact.accepted("receipt"),
                        FinalUsageFact.fromInput(request.inputFact()),
                        new WorkflowStep.Key(request.meetingId(), "finalization", "stable"));
            }
        };
        MeetingInputReconciler reconciler = new MeetingInputReconciler(
                id -> new MeetingInputClosureContextPort.Found(
                        new MeetingInputClosureContextPort.Context("deployment", "subject",
                                id, InputState.INPUT_COMPLETE_PENDING, now, "zh-CN")),
                id -> new AcceptedInputFactLookup.Missing(),
                id -> id.equals("mtg_first") && !firstAudioReady.get()
                        ? new CompletedAudioReadinessPort.Pending()
                        : new CompletedAudioReadinessPort.Ready(id, 1, 100,
                                "sha256-v1:" + "a".repeat(64)),
                admission, Clock.fixed(now, ZoneOffset.UTC));
        var scan = new MeetingInputReconciliationScan((after, limit) -> outstanding.stream()
                .filter(id -> after == null || id.compareTo(after) > 0)
                .limit(limit).toList(), reconciler, 1);

        assertEquals(1, scan.runOnce().pending());
        assertEquals(1, scan.runOnce().accepted());
        assertEquals(List.of("mtg_first"), outstanding);
        assertEquals(0, scan.runOnce().candidates()); // 页尾，下一轮回到第一页。
        firstAudioReady.set(true);
        assertEquals(1, scan.runOnce().accepted());
        assertEquals(0, scan.runOnce().candidates()); // 成功收口后不再计入。
        assertEquals(List.of(), outstanding);
    }
}
