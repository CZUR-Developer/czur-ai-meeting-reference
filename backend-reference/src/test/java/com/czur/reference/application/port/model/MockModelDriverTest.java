package com.czur.reference.application.port.model;

import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Model Driver 端口和无网络 Mock 的生命周期契约测试。 */
class MockModelDriverTest {
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final Set<String> CAPABILITIES = Set.of(
            CapabilityIds.TRANSCRIPT_REALTIME,
            CapabilityIds.TRANSCRIPT_FINAL,
            CapabilityIds.SPEAKER_DIARIZATION_SESSION);

    private RecordingSink sink;
    private MockModelDriver driver;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        driver = new MockModelDriver(
                new CapabilityDescriptor("mock-asr", "1.0.0", 1, CAPABILITIES),
                Clock.fixed(NOW, ZoneOffset.UTC),
                4,
                64);
    }

    @Test
    void descriptorFeedsCapabilityPlannerWithoutVendorSpecificBranch() {
        ProductProfile profile = new ProductProfile(
                "realtime/v1",
                Set.of(CapabilityIds.TRANSCRIPT_REALTIME),
                Set.of(CapabilityIds.SPEAKER_DIARIZATION_SESSION));

        CapabilityPlan plan = new CapabilityPlanner().plan(
                driver.describe(),
                profile,
                Set.of(CapabilityIds.SPEAKER_DIARIZATION_SESSION),
                CAPABILITIES,
                DeploymentLimits.NONE);

        assertEquals(CapabilityDecision.Action.ENABLE,
                plan.decisionFor(CapabilityIds.TRANSCRIPT_REALTIME).action());
        assertEquals(CapabilityDecision.Action.ENABLE,
                plan.decisionFor(CapabilityIds.SPEAKER_DIARIZATION_SESSION).action());
    }

    @Test
    void requestedButNotGrantedCapabilityNeverEntersDriverSpec() {
        ProductProfile profile = new ProductProfile(
                "realtime/v1",
                Set.of(CapabilityIds.TRANSCRIPT_REALTIME),
                Set.of(CapabilityIds.SPEAKER_DIARIZATION_SESSION));
        CapabilityPlan plan = new CapabilityPlanner().plan(
                driver.describe(),
                profile,
                Set.of(CapabilityIds.SPEAKER_DIARIZATION_SESSION),
                Set.of(CapabilityIds.TRANSCRIPT_REALTIME),
                DeploymentLimits.NONE,
                7);
        RealtimeSessionSpec plannedSpec = spec(
                "stream-planned", plan.enabledCapabilities(), plan.planRevision());

        ModelDriverSession session = driver.openSession(
                plannedSpec, sink, "open-planned", context("open-planned")).valueOrThrow();

        assertEquals(Set.of(CapabilityIds.TRANSCRIPT_REALTIME),
                session.spec().enabledCapabilities());
        assertEquals(7, session.spec().planRevision());
        assertFalse(session.spec().enabledCapabilities().contains(
                CapabilityIds.SPEAKER_DIARIZATION_SESSION));
    }

    @Test
    void openRejectsCapabilityNotDeclaredByDriver() {
        RealtimeSessionSpec illegal = spec(
                "stream-illegal",
                Set.of(CapabilityIds.TRANSCRIPT_REALTIME, CapabilityIds.SUMMARY_STRUCTURED),
                1);

        ModelDriverResult<ModelDriverSession> result = driver.openSession(
                illegal, sink, "open-illegal", context("open-illegal"));

        assertFalse(result.isSuccess());
        assertEquals(ModelDriverError.Code.UNSUPPORTED, result.errorOrThrow().code());
    }

    @Test
    void sessionSpecRequiresPositivePersistedPlanRevision() {
        assertThrows(IllegalArgumentException.class, () -> spec(
                "stream-bad-plan", Set.of(CapabilityIds.TRANSCRIPT_REALTIME), 0));
    }

    @Test
    void opensIdempotentlyAndRejectsSameKeyWithDifferentSpec() {
        ModelDriverSession first = open("open-1", spec("stream-1"));
        ModelDriverSession replay = driver.openSession(
                spec("stream-1"), sink, "open-1", context("open-replay"))
                .valueOrThrow();

        ModelDriverResult<ModelDriverSession> conflict = driver.openSession(
                spec("stream-2"), sink, "open-1", context("open-conflict"));

        assertSame(first, replay);
        assertFalse(conflict.isSuccess());
        assertEquals(ModelDriverError.Code.CONFLICT, conflict.errorOrThrow().code());
        assertEquals(ModelDriverSessionState.READY, driver.snapshot(first).valueOrThrow().state());
    }

    @Test
    void forgedValueEqualSessionHandleIsRejected() {
        ModelDriverSession original = open("open-opaque", spec("stream-opaque"));
        ModelDriverSession forged = new ModelDriverSession(original.sessionId(), original.spec());

        ModelDriverResult<Void> result = driver.start(forged, context("start-forged"));

        assertFalse(result.isSuccess());
        assertEquals(ModelDriverError.Code.INVALID_ARGUMENT, result.errorOrThrow().code());
        assertEquals(ModelDriverSessionState.READY, driver.snapshot(original).valueOrThrow().state());
    }

    @Test
    void openReplayWithDifferentSinkIsRejectedUntilReattachContractExists() {
        ModelDriverSession first = open("open-sink", spec("stream-sink"));

        ModelDriverResult<ModelDriverSession> conflict = driver.openSession(
                spec("stream-sink"), new RecordingSink(), "open-sink", context("open-new-sink"));

        assertEquals(ModelDriverSessionState.READY, driver.snapshot(first).valueOrThrow().state());
        assertFalse(conflict.isSuccess());
        assertEquals(ModelDriverError.Code.CONFLICT, conflict.errorOrThrow().code());
    }

    @Test
    void normalLifecycleEmitsStablePartialAndFinalEventsExactlyOnce() {
        ModelDriverSession session = open("open-normal", spec("stream-normal"));
        assertTrue(driver.start(session, context("start")).isSuccess());
        assertTrue(driver.start(session, context("start-replay")).isSuccess());

        AudioAcceptance first = driver.acceptAudio(session, frame(1, 0), context("frame-1"))
                .valueOrThrow();
        AudioAcceptance second = driver.acceptAudio(session, frame(2, 100), context("frame-2"))
                .valueOrThrow();
        AudioAcceptance duplicate = driver.acceptAudio(session, frame(2, 100), context("frame-2-replay"))
                .valueOrThrow();

        assertEquals(AudioAcceptance.Status.ACCEPTED, first.status());
        assertEquals(AudioAcceptance.Status.ACCEPTED, second.status());
        assertEquals(AudioAcceptance.Status.DUPLICATE, duplicate.status());
        assertEquals(2, sink.transcripts.size());

        ModelDriverResult<AudioAcceptance> outOfOrder = driver.acceptAudio(
                session, frame(4, 300), context("frame-gap"));
        assertFalse(outOfOrder.isSuccess());
        assertEquals(ModelDriverError.Code.OUT_OF_ORDER, outOfOrder.errorOrThrow().code());

        InputCompletion completed = driver.completeInput(
                session, 2, "complete-1", context("complete")).valueOrThrow();
        assertEquals(1, completed.finalEventCount());
        assertEquals(3, sink.transcripts.size());
        assertEquals(ModelTerminal.Status.COMPLETED, sink.terminals.getFirst().status());
        assertEquals(ModelDriverSessionState.COMPLETED, driver.snapshot(session).valueOrThrow().state());

        CanonicalTranscriptEvent partial = sink.transcripts.getFirst();
        CanonicalTranscriptEvent secondPartial = sink.transcripts.get(1);
        CanonicalTranscriptEvent finalEvent = sink.transcripts.get(2);
        assertEquals(partial.segmentId(), finalEvent.segmentId());
        assertEquals(partial.segmentId(), secondPartial.segmentId());
        assertEquals(1, partial.revision());
        assertEquals(2, secondPartial.revision());
        assertEquals(3, finalEvent.revision());
        assertFalse(partial.isFinal());
        assertTrue(finalEvent.isFinal());
        assertEquals(0, finalEvent.startMs());
        assertEquals(200, finalEvent.endMs());
        assertEquals("mock-speaker-1", finalEvent.speaker().sessionSpeakerId());

        InputCompletion replay = driver.completeInput(
                session, 2, "complete-1", context("complete-replay")).valueOrThrow();
        assertEquals(completed, replay);
        assertEquals(3, sink.transcripts.size(), "幂等重放不能重复下发 final");
        assertEquals(1, sink.terminals.size(), "幂等重放不能重复下发终态");

        assertTrue(driver.close(session, context("close")).isSuccess());
        assertTrue(driver.close(session, context("close-replay")).isSuccess());
        assertEquals(ModelDriverSessionState.CLOSED, driver.snapshot(session).valueOrThrow().state());
    }

    @Test
    void completionRequiresHighestAcceptedSequenceAndStopsNewInput() {
        ModelDriverSession session = open("open-sequence", spec("stream-sequence"));
        driver.start(session, context("start"));
        driver.acceptAudio(session, frame(1, 0), context("frame-1"));

        ModelDriverResult<InputCompletion> mismatch = driver.completeInput(
                session, 0, "complete-bad", context("complete-bad"));
        assertFalse(mismatch.isSuccess());
        assertEquals(ModelDriverError.Code.INPUT_SEQUENCE_MISMATCH, mismatch.errorOrThrow().code());
        assertEquals(ModelDriverSessionState.STREAMING, driver.snapshot(session).valueOrThrow().state());

        driver.completeInput(session, 1, "complete-ok", context("complete-ok"));
        ModelDriverResult<AudioAcceptance> afterComplete = driver.acceptAudio(
                session, frame(2, 100), context("late-frame"));
        assertFalse(afterComplete.isSuccess());
        assertEquals(ModelDriverError.Code.ALREADY_TERMINAL, afterComplete.errorOrThrow().code());
    }

    @Test
    void completionIdempotencyKeyCannotBeReusedForDifferentLastSequence() {
        ModelDriverSession session = open("open-complete-conflict", spec("stream-complete-conflict"));
        driver.start(session, context("start"));
        driver.acceptAudio(session, frame(1, 0), context("frame-1"));
        driver.completeInput(session, 1, "complete-same", context("complete-ok"));

        ModelDriverResult<InputCompletion> conflict = driver.completeInput(
                session, 0, "complete-same", context("complete-conflict"));

        assertFalse(conflict.isSuccess());
        assertEquals(ModelDriverError.Code.CONFLICT, conflict.errorOrThrow().code());
    }

    @Test
    void cancellationIsIdempotentAndProducesOneTerminalEvent() {
        ModelDriverSession session = open("open-cancel", spec("stream-cancel"));
        driver.start(session, context("start"));
        driver.acceptAudio(session, frame(1, 0), context("frame-1"));

        assertTrue(driver.cancel(session, "USER_CANCEL", "cancel-1", context("cancel")).isSuccess());
        assertTrue(driver.abort(session, "USER_CANCEL", "cancel-1", context("cancel-replay")).isSuccess());
        assertEquals(ModelDriverSessionState.FAILED, driver.snapshot(session).valueOrThrow().state());
        assertEquals(1, sink.terminals.size());
        assertEquals(ModelTerminal.Status.CANCELLED, sink.terminals.getFirst().status());

        ModelDriverResult<Void> secondCommand = driver.abort(
                session, "OTHER_CANCEL", "cancel-2", context("cancel-new"));
        assertFalse(secondCommand.isSuccess());
        assertEquals(ModelDriverError.Code.ALREADY_TERMINAL, secondCommand.errorOrThrow().code());
        assertEquals(1, sink.terminals.size());
        assertTrue(driver.close(session, context("close")).isSuccess());
    }

    @Test
    void cancellationIdempotencyKeyCannotBeReusedForDifferentReason() {
        ModelDriverSession session = open("open-cancel-conflict", spec("stream-cancel-conflict"));
        driver.start(session, context("start"));
        driver.cancel(session, "USER_CANCEL", "cancel-same", context("cancel"));

        ModelDriverResult<Void> conflict = driver.cancel(
                session, "SYSTEM_CANCEL", "cancel-same", context("cancel-conflict"));

        assertFalse(conflict.isSuccess());
        assertEquals(ModelDriverError.Code.CONFLICT, conflict.errorOrThrow().code());
        assertEquals(1, sink.terminals.size());
    }

    @Test
    void discontinuityIsExplicitAndDoesNotResetTheStreamSequence() {
        ModelDriverSession session = open("open-gap", spec("stream-gap"));
        driver.start(session, context("start"));
        driver.acceptAudio(session, frame(1, 0), context("frame-1"));

        ModelDriver.DiscontinuityAction action = driver.notifyDiscontinuity(
                session,
                new AudioDiscontinuity(
                        1, 250, 2, 1, AudioDiscontinuity.Reason.SOURCE_CHANGED, 150),
                context("gap"))
                .valueOrThrow();
        AudioAcceptance next = driver.acceptAudio(session, frame(2, 250), context("frame-2"))
                .valueOrThrow();

        assertEquals(ModelDriver.DiscontinuityAction.CONTINUED, action);
        assertEquals(AudioAcceptance.Status.ACCEPTED, next.status());
        assertEquals(2, next.acceptedStreamSequence());
        assertEquals(350, next.acceptedAudioPositionMs());
    }

    @Test
    void partialCallbackFailureTerminatesSessionInsteadOfPretendingReplaySucceeded() {
        RecordingSink failingSink = new RecordingSink() {
            @Override
            public void onTranscript(CanonicalTranscriptEvent event) {
                throw new IllegalStateException("fixture callback failure");
            }
        };
        ModelDriverSession session = driver.openSession(
                spec("stream-partial-callback"), failingSink, "open-partial-callback", context("open"))
                .valueOrThrow();
        driver.start(session, context("start"));

        ModelDriverResult<AudioAcceptance> failed = driver.acceptAudio(
                session, frame(1, 0), context("frame-1"));
        ModelDriverResult<AudioAcceptance> retry = driver.acceptAudio(
                session, frame(1, 0), context("frame-1-retry"));

        assertFalse(failed.isSuccess());
        assertEquals(ModelDriverError.Code.CALLBACK_FAILED, failed.errorOrThrow().code());
        assertEquals(ModelDriverSessionState.FAILED, driver.snapshot(session).valueOrThrow().state());
        assertFalse(retry.isSuccess());
        assertEquals(ModelDriverError.Code.ALREADY_TERMINAL, retry.errorOrThrow().code());
        assertEquals(1, failingSink.terminals.size());
        assertEquals(ModelTerminal.Status.FAILED, failingSink.terminals.getFirst().status());
    }

    @Test
    void finalCallbackFailureDoesNotCreateReplayableSuccessfulCompletion() {
        RecordingSink failingFinalSink = new RecordingSink() {
            @Override
            public void onTranscript(CanonicalTranscriptEvent event) {
                if (event.isFinal()) {
                    throw new IllegalStateException("fixture final callback failure");
                }
                super.onTranscript(event);
            }
        };
        ModelDriverSession session = driver.openSession(
                spec("stream-final-callback"), failingFinalSink, "open-final-callback", context("open"))
                .valueOrThrow();
        driver.start(session, context("start"));
        driver.acceptAudio(session, frame(1, 0), context("frame-1"));

        ModelDriverResult<InputCompletion> failed = driver.completeInput(
                session, 1, "complete-failing", context("complete"));
        ModelDriverResult<InputCompletion> retry = driver.completeInput(
                session, 1, "complete-failing", context("complete-retry"));

        assertFalse(failed.isSuccess());
        assertEquals(ModelDriverError.Code.CALLBACK_FAILED, failed.errorOrThrow().code());
        assertEquals(ModelDriverSessionState.FAILED, driver.snapshot(session).valueOrThrow().state());
        assertFalse(retry.isSuccess());
        assertEquals(ModelDriverError.Code.ALREADY_TERMINAL, retry.errorOrThrow().code());
        assertEquals(1, failingFinalSink.terminals.size());
        assertEquals(ModelTerminal.Status.FAILED, failingFinalSink.terminals.getFirst().status());
    }

    @Test
    void completedTerminalCallbackFailureRetriesOnlyTerminal() {
        RecordingSink failOnce = new RecordingSink() {
            private int completedAttempts;

            @Override
            public void onTerminal(ModelTerminal terminal) {
                if (terminal.status() == ModelTerminal.Status.COMPLETED
                        && completedAttempts++ == 0) {
                    throw new IllegalStateException("fixture terminal callback failure");
                }
                super.onTerminal(terminal);
            }
        };
        ModelDriverSession session = driver.openSession(
                spec("stream-complete-terminal"), failOnce, "open-complete-terminal", context("open"))
                .valueOrThrow();
        driver.start(session, context("start"));
        driver.acceptAudio(session, frame(1, 0), context("frame-1"));

        ModelDriverResult<InputCompletion> first = driver.completeInput(
                session, 1, "complete-terminal", context("complete-first"));
        int transcriptCountAfterFailure = failOnce.transcripts.size();
        ModelDriverResult<InputCompletion> retry = driver.completeInput(
                session, 1, "complete-terminal", context("complete-retry"));

        assertFalse(first.isSuccess());
        assertEquals(ModelDriverError.Code.CALLBACK_FAILED, first.errorOrThrow().code());
        assertEquals(ModelDriverSessionState.COMPLETED, driver.snapshot(session).valueOrThrow().state());
        assertTrue(retry.isSuccess());
        assertEquals(transcriptCountAfterFailure, failOnce.transcripts.size(),
                "terminal 重试不得重复已交付的 final");
        assertEquals(2, failOnce.transcripts.size(), "Mock 每场最多输出一个 final");
        assertEquals(1, failOnce.terminals.size());
        assertEquals(ModelTerminal.Status.COMPLETED, failOnce.terminals.getFirst().status());
    }

    @Test
    void closeAfterCompletedTerminalFailureStillAllowsTerminalRetry() {
        RecordingSink failOnce = new RecordingSink() {
            private int completedAttempts;

            @Override
            public void onTerminal(ModelTerminal terminal) {
                if (terminal.status() == ModelTerminal.Status.COMPLETED
                        && completedAttempts++ == 0) {
                    throw new IllegalStateException("fixture terminal callback failure");
                }
                super.onTerminal(terminal);
            }
        };
        ModelDriverSession session = driver.openSession(
                spec("stream-complete-terminal-close"), failOnce,
                "open-complete-terminal-close", context("open")).valueOrThrow();
        driver.start(session, context("start"));
        driver.acceptAudio(session, frame(1, 0), context("frame-1"));

        ModelDriverResult<InputCompletion> first = driver.completeInput(
                session, 1, "complete-terminal-close", context("complete-first"));
        assertFalse(first.isSuccess());
        assertEquals(ModelDriverError.Code.CALLBACK_FAILED, first.errorOrThrow().code());
        assertTrue(driver.close(session, context("close")).isSuccess());

        ModelDriverResult<InputCompletion> retry = driver.completeInput(
                session, 1, "complete-terminal-close", context("complete-retry"));

        assertTrue(retry.isSuccess());
        assertEquals(2, failOnce.transcripts.size(), "close 后 terminal 重试不得重复 final");
        assertEquals(1, failOnce.terminals.size());
        assertEquals(ModelTerminal.Status.COMPLETED, failOnce.terminals.getFirst().status());
        assertEquals(ModelDriverSessionState.CLOSED, driver.snapshot(session).valueOrThrow().state());
    }

    @Test
    void closeBeforeFinalDispatchingSuppressesRetryWithoutDuplicateFinal() throws Exception {
        CountDownLatch pendingCreated = new CountDownLatch(1);
        CountDownLatch releaseBeforeDispatch = new CountDownLatch(1);
        MockModelDriver controlled = new MockModelDriver(
                new CapabilityDescriptor("close-race", CAPABILITIES),
                Clock.fixed(NOW, ZoneOffset.UTC),
                4,
                64,
                () -> {
                    pendingCreated.countDown();
                    await(releaseBeforeDispatch);
                });
        ModelDriverSession session = controlled.openSession(
                spec("stream-close-before-dispatch"), sink,
                "open-close-before-dispatch", context("open")).valueOrThrow();
        controlled.start(session, context("start"));
        controlled.acceptAudio(session, frame(1, 0), context("frame-1"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ModelDriverResult<InputCompletion>> completing = executor.submit(() ->
                    controlled.completeInput(
                            session, 1, "complete-close-before-dispatch", context("complete")));
            await(pendingCreated);

            assertTrue(controlled.close(session, context("close")).isSuccess());
            releaseBeforeDispatch.countDown();
            ModelDriverResult<InputCompletion> completion = completing.get(5, TimeUnit.SECONDS);

            assertFalse(completion.isSuccess());
            assertEquals(ModelDriverError.Code.ALREADY_TERMINAL, completion.errorOrThrow().code());
            assertEquals(2, sink.transcripts.size(), "final 首次交付仍只发送一次");
            assertEquals(0, sink.terminals.size(), "close 抢占后不得补发 COMPLETED terminal");

            ModelDriverResult<InputCompletion> retry = controlled.completeInput(
                    session, 1, "complete-close-before-dispatch", context("complete-retry"));
            assertFalse(retry.isSuccess());
            assertEquals(ModelDriverError.Code.ALREADY_TERMINAL, retry.errorOrThrow().code());
            assertEquals(2, sink.transcripts.size(), "close 抢占后重试不得重复发送 final");
            assertEquals(0, sink.terminals.size());
        } finally {
            releaseBeforeDispatch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCompletionReplayDoesNotDispatchFinalTwice() throws Exception {
        CountDownLatch finalEntered = new CountDownLatch(1);
        CountDownLatch releaseFinal = new CountDownLatch(1);
        RecordingSink blockingFinal = blockingFinalSink(finalEntered, releaseFinal);
        ModelDriverSession session = driver.openSession(
                spec("stream-concurrent-complete"), blockingFinal,
                "open-concurrent-complete", context("open")).valueOrThrow();
        driver.start(session, context("start"));
        driver.acceptAudio(session, frame(1, 0), context("frame-1"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ModelDriverResult<InputCompletion>> first = executor.submit(() ->
                    driver.completeInput(
                            session, 1, "complete-concurrent", context("complete-first")));
            await(finalEntered);

            ModelDriverResult<InputCompletion> replay = driver.completeInput(
                    session, 1, "complete-concurrent", context("complete-replay"));

            assertFalse(replay.isSuccess());
            assertEquals(ModelDriverError.Code.BACKPRESSURED, replay.errorOrThrow().code());
            assertEquals(1, blockingFinal.transcripts.size(),
                    "阻塞中的首个 final 尚未被 Sink 接管，并发重放不得发送第二份");
            releaseFinal.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(2, blockingFinal.transcripts.size());
            assertEquals(1, blockingFinal.terminals.size());
        } finally {
            releaseFinal.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cancelledTerminalCallbackFailureCanRetryWithoutCancellingTwice() {
        RecordingSink failOnce = new RecordingSink() {
            private int cancelledAttempts;

            @Override
            public void onTerminal(ModelTerminal terminal) {
                if (terminal.status() == ModelTerminal.Status.CANCELLED
                        && cancelledAttempts++ == 0) {
                    throw new IllegalStateException("fixture cancel terminal callback failure");
                }
                super.onTerminal(terminal);
            }
        };
        ModelDriverSession session = driver.openSession(
                spec("stream-cancel-terminal"), failOnce, "open-cancel-terminal", context("open"))
                .valueOrThrow();
        driver.start(session, context("start"));

        ModelDriverResult<Void> first = driver.abort(
                session, "USER_CANCEL", "cancel-terminal", context("cancel-first"));
        ModelDriverResult<Void> retry = driver.abort(
                session, "USER_CANCEL", "cancel-terminal", context("cancel-retry"));

        assertFalse(first.isSuccess());
        assertEquals(ModelDriverError.Code.CALLBACK_FAILED, first.errorOrThrow().code());
        assertEquals(ModelDriverSessionState.FAILED, driver.snapshot(session).valueOrThrow().state());
        assertTrue(retry.isSuccess());
        assertEquals(1, failOnce.terminals.size());
        assertEquals(ModelTerminal.Status.CANCELLED, failOnce.terminals.getFirst().status());
    }

    @Test
    void abortDuringFinalCallbackWinsWithoutCompletedTerminal() throws Exception {
        CountDownLatch finalEntered = new CountDownLatch(1);
        CountDownLatch releaseFinal = new CountDownLatch(1);
        RecordingSink blockingFinal = blockingFinalSink(finalEntered, releaseFinal);
        ModelDriverSession session = driver.openSession(
                spec("stream-race-abort"), blockingFinal, "open-race-abort", context("open"))
                .valueOrThrow();
        driver.start(session, context("start"));
        driver.acceptAudio(session, frame(1, 0), context("frame-1"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ModelDriverResult<InputCompletion>> completing = executor.submit(() ->
                    driver.completeInput(session, 1, "complete-race-abort", context("complete")));
            await(finalEntered);

            assertTrue(driver.abort(
                    session, "USER_CANCEL", "cancel-race", context("cancel")).isSuccess());
            releaseFinal.countDown();
            ModelDriverResult<InputCompletion> completion = completing.get(5, TimeUnit.SECONDS);

            assertFalse(completion.isSuccess());
            assertEquals(ModelDriverError.Code.ALREADY_TERMINAL, completion.errorOrThrow().code());
            assertEquals(ModelDriverSessionState.FAILED, driver.snapshot(session).valueOrThrow().state());
            assertTrue(blockingFinal.terminals.stream()
                    .noneMatch(terminal -> terminal.status() == ModelTerminal.Status.COMPLETED));
            assertTrue(blockingFinal.terminals.stream()
                    .anyMatch(terminal -> terminal.status() == ModelTerminal.Status.CANCELLED));

            ModelDriverResult<InputCompletion> retry = driver.completeInput(
                    session, 1, "complete-race-abort", context("complete-retry"));
            assertFalse(retry.isSuccess());
            assertEquals(ModelDriverError.Code.ALREADY_TERMINAL, retry.errorOrThrow().code());
            assertEquals(2, blockingFinal.transcripts.size(),
                    "close 抢占后重试不得重复发送 final");
        } finally {
            releaseFinal.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void closeDuringFinalCallbackWinsWithoutCompletedTerminal() throws Exception {
        CountDownLatch finalEntered = new CountDownLatch(1);
        CountDownLatch releaseFinal = new CountDownLatch(1);
        RecordingSink blockingFinal = blockingFinalSink(finalEntered, releaseFinal);
        ModelDriverSession session = driver.openSession(
                spec("stream-race-close"), blockingFinal, "open-race-close", context("open"))
                .valueOrThrow();
        driver.start(session, context("start"));
        driver.acceptAudio(session, frame(1, 0), context("frame-1"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ModelDriverResult<InputCompletion>> completing = executor.submit(() ->
                    driver.completeInput(session, 1, "complete-race-close", context("complete")));
            await(finalEntered);

            assertTrue(driver.close(session, context("close")).isSuccess());
            releaseFinal.countDown();
            ModelDriverResult<InputCompletion> completion = completing.get(5, TimeUnit.SECONDS);

            assertFalse(completion.isSuccess());
            assertEquals(ModelDriverError.Code.ALREADY_TERMINAL, completion.errorOrThrow().code());
            assertEquals(ModelDriverSessionState.CLOSED, driver.snapshot(session).valueOrThrow().state());
            assertTrue(blockingFinal.terminals.stream()
                    .noneMatch(terminal -> terminal.status() == ModelTerminal.Status.COMPLETED));

            ModelDriverResult<InputCompletion> retry = driver.completeInput(
                    session, 1, "complete-race-close", context("complete-retry"));
            assertFalse(retry.isSuccess());
            assertEquals(ModelDriverError.Code.ALREADY_TERMINAL, retry.errorOrThrow().code());
            assertEquals(2, blockingFinal.transcripts.size(),
                    "close 抢占后重试不得重复发送 final");
        } finally {
            releaseFinal.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void closeAfterCompletionLinearizationDoesNotTurnCompletionIntoFailure() throws Exception {
        CountDownLatch terminalEntered = new CountDownLatch(1);
        CountDownLatch releaseTerminal = new CountDownLatch(1);
        RecordingSink blockingTerminal = new RecordingSink() {
            @Override
            public void onTerminal(ModelTerminal terminal) {
                if (terminal.status() == ModelTerminal.Status.COMPLETED) {
                    terminalEntered.countDown();
                    await(releaseTerminal);
                }
                super.onTerminal(terminal);
            }
        };
        ModelDriverSession session = driver.openSession(
                spec("stream-race-terminal-close"), blockingTerminal,
                "open-race-terminal-close", context("open")).valueOrThrow();
        driver.start(session, context("start"));
        driver.acceptAudio(session, frame(1, 0), context("frame-1"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ModelDriverResult<InputCompletion>> completing = executor.submit(() ->
                    driver.completeInput(session, 1, "complete-terminal-close", context("complete")));
            await(terminalEntered);
            assertEquals(ModelDriverSessionState.COMPLETED, driver.snapshot(session).valueOrThrow().state());

            assertTrue(driver.close(session, context("close")).isSuccess());
            releaseTerminal.countDown();

            assertTrue(completing.get(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(ModelDriverSessionState.CLOSED, driver.snapshot(session).valueOrThrow().state());
            assertEquals(1, blockingTerminal.terminals.size());
        } finally {
            releaseTerminal.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void timeoutHasStableErrorAndTerminatesAnActiveSession() {
        ModelDriverResult<ModelDriverSession> openTimeout = driver.openSession(
                spec("stream-open-timeout"), sink, "open-timeout",
                ModelOperationContext.withoutCancellation("expired-open", NOW));
        assertFalse(openTimeout.isSuccess());
        assertEquals(ModelDriverError.Code.TIMEOUT, openTimeout.errorOrThrow().code());

        ModelDriverSession session = open("open-active", spec("stream-active"));
        driver.start(session, context("start"));
        ModelDriverResult<AudioAcceptance> timedOut = driver.acceptAudio(
                session,
                frame(1, 0),
                ModelOperationContext.withoutCancellation("expired-frame", NOW));

        assertFalse(timedOut.isSuccess());
        assertEquals(ModelDriverError.Code.TIMEOUT, timedOut.errorOrThrow().code());
        assertTrue(timedOut.errorOrThrow().retryable());
        assertEquals(ModelDriverSessionState.FAILED, driver.snapshot(session).valueOrThrow().state());
        assertEquals(ModelTerminal.Status.FAILED, sink.terminals.getFirst().status());
    }

    @Test
    void operationCancellationIsExplicitAndCloseStillReleasesResources() {
        ModelDriverSession session = open("open-operation-cancel", spec("stream-operation-cancel"));
        driver.start(session, context("start"));
        ModelOperationContext cancelled = new ModelOperationContext(
                "cancelled-call", NOW.plusSeconds(30), () -> true);

        ModelDriverResult<AudioAcceptance> rejected = driver.acceptAudio(
                session, frame(1, 0), cancelled);

        assertFalse(rejected.isSuccess());
        assertEquals(ModelDriverError.Code.CANCELLED, rejected.errorOrThrow().code());
        assertEquals(ModelDriverSessionState.STREAMING, driver.snapshot(session).valueOrThrow().state());
        assertTrue(driver.close(session, null).isSuccess());
        assertEquals(ModelDriverSessionState.CLOSED, driver.snapshot(session).valueOrThrow().state());
    }

    @Test
    void boundedBufferReturnsBackpressureWithoutLosingAcceptedCursor() throws Exception {
        MockModelDriver bounded = new MockModelDriver(
                new CapabilityDescriptor("bounded", CAPABILITIES),
                Clock.fixed(NOW, ZoneOffset.UTC),
                1,
                1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        RecordingSink boundedSink = new RecordingSink() {
            @Override
            public void onTranscript(CanonicalTranscriptEvent event) {
                super.onTranscript(event);
                callbackEntered.countDown();
                await(releaseCallback);
            }
        };
        ModelDriverSession session = bounded.openSession(
                spec("stream-bounded"), boundedSink, "open-bounded", context("open-bounded"))
                .valueOrThrow();
        bounded.start(session, context("start-bounded"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ModelDriverResult<AudioAcceptance>> first = executor.submit(() ->
                    bounded.acceptAudio(session, frame(1, 0), context("frame-1")));
            await(callbackEntered);

            AudioAcceptance backpressure = bounded.acceptAudio(
                    session, frame(2, 100), context("frame-2")).valueOrThrow();

            assertEquals(AudioAcceptance.Status.BACKPRESSURED, backpressure.status());
            assertEquals(1, backpressure.acceptedStreamSequence());
            assertTrue(backpressure.retryAfterMs() > 0);
            assertEquals(1, boundedSink.transcripts.size());
            releaseCallback.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).isSuccess());
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void closeReleasesConcurrencySlotAndShutdownRejectsNewSessions() {
        MockModelDriver single = new MockModelDriver(
                new CapabilityDescriptor("single", CAPABILITIES),
                Clock.fixed(NOW, ZoneOffset.UTC),
                1,
                4);
        ModelDriverSession first = single.openSession(
                spec("stream-one"), sink, "open-one", context("open-one")).valueOrThrow();
        ModelDriverResult<ModelDriverSession> limited = single.openSession(
                spec("stream-two"), sink, "open-two", context("open-two"));
        assertFalse(limited.isSuccess());
        assertEquals(ModelDriverError.Code.PROVIDER_UNAVAILABLE, limited.errorOrThrow().code());

        single.close(first, context("close-one"));
        ModelDriverSession second = single.openSession(
                spec("stream-two"), sink, "open-two", context("open-two-retry")).valueOrThrow();
        assertNotEquals(first.sessionId(), second.sessionId());

        single.shutdown(NOW.plusSeconds(1));
        assertEquals(ModelDriverSessionState.CLOSED, single.snapshot(second).valueOrThrow().state());
        ModelDriverResult<ModelDriverSession> afterShutdown = single.openSession(
                spec("stream-three"), sink, "open-three", context("open-three"));
        assertFalse(afterShutdown.isSuccess());
        assertEquals(ModelDriverError.Code.PROVIDER_UNAVAILABLE, afterShutdown.errorOrThrow().code());
    }

    @Test
    void audioBytesAreDefensivelyCopied() {
        byte[] input = new byte[]{1, 2, 3};
        MeetingAudioFrame frame = new MeetingAudioFrame(
                1, 0, 100, input, null, 1, 1, 0, Set.of());
        input[0] = 9;
        byte[] read = frame.audio();
        read[1] = 8;

        assertArrayEquals(new byte[]{1, 2, 3}, frame.audio());
    }

    private ModelDriverSession open(String key, RealtimeSessionSpec spec) {
        return driver.openSession(spec, sink, key, context("open")).valueOrThrow();
    }

    private static RealtimeSessionSpec spec(String streamId) {
        return spec(streamId, CAPABILITIES, 1);
    }

    private static RealtimeSessionSpec spec(
            String streamId, Set<String> enabledCapabilities, long planRevision) {
        return new RealtimeSessionSpec(
                "meeting-1",
                streamId,
                "zh-CN",
                AudioProfile.pcm16kMono(100),
                enabledCapabilities,
                planRevision,
                3_600_000,
                "settings-1");
    }

    private static MeetingAudioFrame frame(long sequence, long startMs) {
        return new MeetingAudioFrame(
                sequence,
                startMs,
                100,
                new byte[]{1, 2, 3, 4},
                null,
                1,
                1,
                0,
                Set.of());
    }

    private static ModelOperationContext context(String operationId) {
        return ModelOperationContext.withoutCancellation(operationId, NOW.plusSeconds(30));
    }

    private static RecordingSink blockingFinalSink(
            CountDownLatch finalEntered, CountDownLatch releaseFinal) {
        return new RecordingSink() {
            @Override
            public void onTranscript(CanonicalTranscriptEvent event) {
                if (event.isFinal()) {
                    finalEntered.countDown();
                    await(releaseFinal);
                }
                super.onTranscript(event);
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("fixture latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static class RecordingSink implements ModelEventSink {
        private final List<CanonicalTranscriptEvent> transcripts = new CopyOnWriteArrayList<>();
        private final List<ModelTerminal> terminals = new CopyOnWriteArrayList<>();

        @Override
        public void onTranscript(CanonicalTranscriptEvent event) {
            transcripts.add(event);
        }

        @Override
        public void onTerminal(ModelTerminal terminal) {
            terminals.add(terminal);
        }
    }
}
