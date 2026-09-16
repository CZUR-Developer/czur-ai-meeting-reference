package com.czur.reference.application.port.model;

import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityIds;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model Driver 的可复用契约测试工具包。
 *
 * <p>新增 Driver 的测试只需实现 {@link #descriptor()} 和
 * {@link #createDriver(CapabilityDescriptor, ModelEventSink)}，即可复用同一组
 * 生命周期、能力、幂等、并发、错误和回调测试。测试只依赖公开
 * {@link ModelDriver} 端口，不读取某个实现的内部快照或 Provider 字段。</p>
 *
 * <p>本工具包是 test scope 的协议/生命周期门禁，不是模型质量评测，也不能
 * 替代真实会议的端到端测试。</p>
 */
public abstract class ModelDriverContractTestKit {
    /** 契约测试使用的固定时间，Driver 工厂可据此构造可控时钟。 */
    protected static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    /** 返回被测 Driver 的注册能力描述。 */
    protected abstract CapabilityDescriptor descriptor();

    /**
     * 创建被测 Driver。
     *
     * @param descriptor 本测试类登记的能力描述
     * @param sink 本场固定事件接收器
     */
    protected abstract ModelDriver createDriver(
            CapabilityDescriptor descriptor, ModelEventSink sink);

    /**
     * 创建较小回调队列的 Driver；需要背压测试的实现应覆盖此入口。
     * 默认复用普通工厂，仍会执行其余所有公共契约。
     */
    protected ModelDriver createBoundedDriver(
            CapabilityDescriptor descriptor, ModelEventSink sink) {
        return createDriver(descriptor, sink);
    }

    /** 需要覆盖 pending 创建窗口的实现应将 hook 注入自己的测试构造函数。 */
    protected ModelDriver createPendingDriver(
            CapabilityDescriptor descriptor, ModelEventSink sink, Runnable beforeFinalDispatchHook) {
        return createDriver(descriptor, sink);
    }

    /** Driver 能注入 pending 创建 hook 时返回 true；普通 Driver 可保留默认值。 */
    protected boolean supportsPendingDispatchHook() {
        return false;
    }

    @Test
    void descriptorAndSettingsValidationUseStableErrors() {
        RecordingSink sink = new RecordingSink();
        ModelDriver driver = createDriver(descriptor(), sink);

        assertEquals(descriptor(), driver.describe());
        assertTrue(driver.describe().supports(CapabilityIds.TRANSCRIPT_REALTIME));
        assertTrue(driver.validateSettings(context("validate")).isSuccess());

        ModelDriverResult<Void> nullContext = driver.validateSettings(null);
        assertFalse(nullContext.isSuccess());
        assertEquals(ModelDriverError.Code.INVALID_ARGUMENT, nullContext.errorOrThrow().code());

        ModelDriverResult<Void> cancelled = driver.validateSettings(
                new ModelOperationContext("cancelled", NOW.plusSeconds(30), () -> true));
        assertFalse(cancelled.isSuccess());
        assertEquals(ModelDriverError.Code.CANCELLED, cancelled.errorOrThrow().code());

        ModelDriverResult<Void> expired = driver.validateSettings(
                ModelOperationContext.withoutCancellation("expired", NOW));
        assertFalse(expired.isSuccess());
        assertEquals(ModelDriverError.Code.TIMEOUT, expired.errorOrThrow().code());
    }

    @Test
    void capabilitySubsetAndUnsupportedCapabilityAreEnforced() {
        RecordingSink sink = new RecordingSink();
        ModelDriver driver = createDriver(descriptor(), sink);
        ModelDriverSession accepted = driver.openSession(
                spec("stream-capability", descriptor().capabilities()),
                sink, "open-capability", context("open-capability")).valueOrThrow();
        assertEquals(descriptor().capabilities(), accepted.spec().enabledCapabilities());

        ModelDriverResult<ModelDriverSession> unsupported = driver.openSession(
                spec("stream-unsupported", Set.of(
                        CapabilityIds.TRANSCRIPT_REALTIME, "capability.not-registered")),
                sink, "open-unsupported", context("open-unsupported"));
        assertFalse(unsupported.isSuccess());
        assertEquals(ModelDriverError.Code.UNSUPPORTED, unsupported.errorOrThrow().code());
    }

    @Test
    void lifecycleProducesCanonicalRevisionedEventsAndExplicitDiscontinuity() {
        RecordingSink sink = new RecordingSink();
        ModelDriver driver = createDriver(descriptor(), sink);
        ModelDriverSession session = open(driver, sink, "open-lifecycle", "stream-lifecycle");
        assertTrue(driver.start(session, context("start")).isSuccess());
        assertTrue(driver.start(session, context("start-replay")).isSuccess());

        assertEquals(AudioAcceptance.Status.ACCEPTED,
                driver.acceptAudio(session, frame(1, 0), context("frame-1"))
                        .valueOrThrow().status());

        assertEquals(ModelDriver.DiscontinuityAction.CONTINUED,
                driver.notifyDiscontinuity(session,
                        new AudioDiscontinuity(1, 250, 2, 1,
                                AudioDiscontinuity.Reason.SOURCE_CHANGED, 150),
                        context("gap")).valueOrThrow());

        // 缺口通知推进音频时间轴；下一个帧仍使用连续公共序号和不回退的时间轴。
        assertEquals(AudioAcceptance.Status.ACCEPTED,
                driver.acceptAudio(session, frame(2, 250), context("frame-2"))
                        .valueOrThrow().status());
        assertEquals(AudioAcceptance.Status.DUPLICATE,
                driver.acceptAudio(session, frame(2, 250), context("frame-2-replay"))
                        .valueOrThrow().status());

        InputCompletion completion = driver.completeInput(
                session, 2, "complete-lifecycle", context("complete")).valueOrThrow();
        assertTrue(completion.finalEventCount() > 0);
        assertEquals(2 + completion.finalEventCount(), sink.transcripts.size());
        assertEquals(1, sink.terminals.size());
        assertEquals(ModelTerminal.Status.COMPLETED, sink.terminals.getFirst().status());

        List<CanonicalTranscriptEvent> partials = sink.transcripts.stream()
                .filter(event -> !event.isFinal()).toList();
        List<CanonicalTranscriptEvent> finals = sink.transcripts.stream()
                .filter(CanonicalTranscriptEvent::isFinal).toList();
        assertEquals(completion.finalEventCount(), finals.size());
        Map<String, Integer> partialRevisions = new HashMap<>();
        for (CanonicalTranscriptEvent event : partials) {
            assertTrue(event.startMs() <= event.endMs());
            assertTrue(event.text() != null && !event.text().isBlank());
            Integer previous = partialRevisions.put(event.segmentId(), event.revision());
            if (previous != null) {
                assertTrue(previous < event.revision());
            }
        }
        for (CanonicalTranscriptEvent finalEvent : finals) {
            assertTrue(finalEvent.startMs() <= finalEvent.endMs());
            assertTrue(finalEvent.text() != null && !finalEvent.text().isBlank());
            assertTrue(partialRevisions.containsKey(finalEvent.segmentId()));
            assertTrue(partialRevisions.get(finalEvent.segmentId()) < finalEvent.revision());
        }

        assertTrue(driver.close(session, context("close")).isSuccess());
        assertTrue(driver.close(session, context("close-replay")).isSuccess());
    }

    @Test
    void openAndSessionHandlesAreIdentityBoundAndIdempotent() {
        RecordingSink sink = new RecordingSink();
        ModelDriver driver = createDriver(descriptor(), sink);
        RealtimeSessionSpec spec = spec("stream-idempotency", descriptor().capabilities());
        ModelDriverSession first = driver.openSession(
                spec, sink, "open-idempotency", context("open")).valueOrThrow();
        ModelDriverSession replay = driver.openSession(
                spec, sink, "open-idempotency", context("open-replay")).valueOrThrow();
        assertSame(first, replay);

        ModelDriverResult<ModelDriverSession> sinkConflict = driver.openSession(
                spec, new RecordingSink(), "open-idempotency", context("open-sink-conflict"));
        assertFalse(sinkConflict.isSuccess());
        assertEquals(ModelDriverError.Code.CONFLICT, sinkConflict.errorOrThrow().code());

        ModelDriverSession forged = new ModelDriverSession(first.sessionId(), first.spec());
        ModelDriverResult<Void> forgedResult = driver.start(forged, context("start-forged"));
        assertFalse(forgedResult.isSuccess());
        assertEquals(ModelDriverError.Code.INVALID_ARGUMENT, forgedResult.errorOrThrow().code());
    }

    @Test
    void sequenceMismatchAndAbortHaveDeterministicIdempotency() {
        RecordingSink sink = new RecordingSink();
        ModelDriver driver = createDriver(descriptor(), sink);
        ModelDriverSession session = open(driver, sink, "open-abort", "stream-abort");
        driver.start(session, context("start"));
        driver.acceptAudio(session, frame(1, 0), context("frame-1"));

        ModelDriverResult<AudioAcceptance> outOfOrder = driver.acceptAudio(
                session, frame(3, 100), context("frame-gap"));
        assertFalse(outOfOrder.isSuccess());
        assertEquals(ModelDriverError.Code.OUT_OF_ORDER, outOfOrder.errorOrThrow().code());

        ModelDriverResult<InputCompletion> mismatch = driver.completeInput(
                session, 0, "complete-bad", context("complete-bad"));
        assertFalse(mismatch.isSuccess());
        assertEquals(ModelDriverError.Code.INPUT_SEQUENCE_MISMATCH, mismatch.errorOrThrow().code());

        assertTrue(driver.abort(session, "USER_CANCEL", "cancel-1", context("cancel")).isSuccess());
        assertTrue(driver.cancel(session, "USER_CANCEL", "cancel-1", context("cancel-replay")).isSuccess());
        assertEquals(1, sink.terminals.size());
        assertEquals(ModelTerminal.Status.CANCELLED, sink.terminals.getFirst().status());

        ModelDriverResult<Void> reasonConflict = driver.abort(
                session, "SYSTEM_CANCEL", "cancel-1", context("cancel-conflict"));
        assertFalse(reasonConflict.isSuccess());
        assertEquals(ModelDriverError.Code.CONFLICT, reasonConflict.errorOrThrow().code());
    }

    @Test
    void concurrentCompletionDoesNotDuplicateFinal() throws Exception {
        CountDownLatch finalEntered = new CountDownLatch(1);
        CountDownLatch releaseFinal = new CountDownLatch(1);
        RecordingSink sink = new RecordingSink() {
            @Override
            public void onTranscript(CanonicalTranscriptEvent event) {
                if (event.isFinal()) {
                    finalEntered.countDown();
                    await(releaseFinal);
                }
                super.onTranscript(event);
            }
        };
        ModelDriver driver = createDriver(descriptor(), sink);
        ModelDriverSession session = open(driver, sink, "open-concurrent", "stream-concurrent");
        driver.start(session, context("start"));
        driver.acceptAudio(session, frame(1, 0), context("frame-1"));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ModelDriverResult<InputCompletion>> first = executor.submit(() ->
                    driver.completeInput(session, 1, "complete-concurrent", context("complete-first")));
            await(finalEntered);

            ModelDriverResult<InputCompletion> replay = driver.completeInput(
                    session, 1, "complete-concurrent", context("complete-replay"));
            assertFalse(replay.isSuccess());
            assertEquals(ModelDriverError.Code.BACKPRESSURED, replay.errorOrThrow().code());
            assertEquals(1, sink.transcripts.size());

            releaseFinal.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).isSuccess());
            assertTrue(sink.transcripts.size() >= 2,
                    "已开始的 final 回调至少应交付一份，不得静默丢失");
            assertTrue(sink.transcripts.stream().anyMatch(CanonicalTranscriptEvent::isFinal));
            assertEquals(1, sink.terminals.size());
        } finally {
            releaseFinal.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void boundedCallbacksReturnBackpressureWithoutMovingCursor() throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        RecordingSink sink = new RecordingSink() {
            @Override
            public void onTranscript(CanonicalTranscriptEvent event) {
                super.onTranscript(event);
                callbackEntered.countDown();
                await(releaseCallback);
            }
        };
        ModelDriver driver = createBoundedDriver(descriptor(), sink);
        ModelDriverSession session = open(driver, sink, "open-bounded", "stream-bounded");
        driver.start(session, context("start"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ModelDriverResult<AudioAcceptance>> first = executor.submit(() ->
                    driver.acceptAudio(session, frame(1, 0), context("frame-1")));
            await(callbackEntered);

            AudioAcceptance backpressure = driver.acceptAudio(
                    session, frame(2, 100), context("frame-2")).valueOrThrow();
            assertEquals(AudioAcceptance.Status.BACKPRESSURED, backpressure.status());
            assertEquals(1, backpressure.acceptedStreamSequence());
            assertTrue(backpressure.retryAfterMs() > 0);

            releaseCallback.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(1, first.get(5, TimeUnit.SECONDS).valueOrThrow().acceptedStreamSequence());
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void callbackFailureAndTerminalRetryUseStableErrors() {
        RecordingSink failingPartial = new RecordingSink() {
            @Override
            public void onTranscript(CanonicalTranscriptEvent event) {
                throw new IllegalStateException("fixture callback failure");
            }
        };
        ModelDriver driver = createDriver(descriptor(), failingPartial);
        ModelDriverSession session = open(driver, failingPartial, "open-callback", "stream-callback");
        driver.start(session, context("start"));
        ModelDriverResult<AudioAcceptance> failed = driver.acceptAudio(
                session, frame(1, 0), context("frame-1"));
        assertFalse(failed.isSuccess());
        assertEquals(ModelDriverError.Code.CALLBACK_FAILED, failed.errorOrThrow().code());
        assertEquals(1, failingPartial.terminals.size());
        assertEquals(ModelTerminal.Status.FAILED, failingPartial.terminals.getFirst().status());

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
        driver = createDriver(descriptor(), failOnce);
        session = open(driver, failOnce, "open-terminal-retry", "stream-terminal-retry");
        driver.start(session, context("start"));
        driver.acceptAudio(session, frame(1, 0), context("frame-1"));
        ModelDriverResult<InputCompletion> first = driver.completeInput(
                session, 1, "complete-terminal-retry", context("complete"));
        assertFalse(first.isSuccess());
        assertEquals(ModelDriverError.Code.CALLBACK_FAILED, first.errorOrThrow().code());
        int eventCount = failOnce.transcripts.size();
        ModelDriverResult<InputCompletion> retry = driver.completeInput(
                session, 1, "complete-terminal-retry", context("complete-retry"));
        assertTrue(retry.isSuccess());
        assertEquals(eventCount, failOnce.transcripts.size());
        assertEquals(1, failOnce.terminals.size());
    }

    @Test
    void closeAndAbortLinearizeAgainstFinalCallbackWindow() throws Exception {
        assertFinalRace(false);
        assertFinalRace(true);
    }

    @Test
    void closeWinsWhenPendingCompletionHasNotStartedFinalDispatch() throws Exception {
        if (!supportsPendingDispatchHook()) {
            return;
        }
        assertPendingRace(false);
        assertPendingRace(true);
    }

    private void assertPendingRace(boolean abort) throws Exception {
        CountDownLatch pendingCreated = new CountDownLatch(1);
        CountDownLatch releaseBeforeDispatch = new CountDownLatch(1);
        RecordingSink sink = new RecordingSink();
        ModelDriver driver = createPendingDriver(
                descriptor(), sink, () -> {
                    pendingCreated.countDown();
                    await(releaseBeforeDispatch);
                });
        ModelDriverSession session = open(driver, sink, "open-pending-close", "stream-pending-close");
        driver.start(session, context("start"));
        driver.acceptAudio(session, frame(1, 0), context("frame-1"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ModelDriverResult<InputCompletion>> completing = executor.submit(() ->
                    driver.completeInput(session, 1, "complete-pending-close", context("complete")));
            await(pendingCreated);
            if (abort) {
                assertTrue(driver.abort(session, "USER_CANCEL", "cancel-pending", context("cancel")).isSuccess());
            } else {
                assertTrue(driver.close(session, context("close")).isSuccess());
            }
            releaseBeforeDispatch.countDown();
            ModelDriverResult<InputCompletion> result = completing.get(5, TimeUnit.SECONDS);
            assertFalse(result.isSuccess());
            assertEquals(ModelDriverError.Code.ALREADY_TERMINAL, result.errorOrThrow().code());
            assertTrue(sink.transcripts.size() >= 2,
                    "已开始的 final 回调至少应交付一份，不得静默丢失");
            assertTrue(sink.transcripts.stream().anyMatch(CanonicalTranscriptEvent::isFinal));
            if (abort) {
                assertEquals(1, sink.terminals.size());
                assertEquals(ModelTerminal.Status.CANCELLED, sink.terminals.getFirst().status());
            } else {
                assertTrue(sink.terminals.isEmpty());
            }
        } finally {
            releaseBeforeDispatch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shutdownClosesExistingSessionsAndRejectsNewOpen() {
        RecordingSink sink = new RecordingSink();
        ModelDriver driver = createDriver(descriptor(), sink);
        ModelDriverSession session = open(driver, sink, "open-shutdown", "stream-shutdown");
        driver.shutdown(NOW.plusSeconds(1));
        ModelDriverResult<ModelDriverSession> afterShutdown = driver.openSession(
                spec("stream-after-shutdown", descriptor().capabilities()),
                sink, "open-after-shutdown", context("open-after-shutdown"));
        assertFalse(afterShutdown.isSuccess());
        assertEquals(ModelDriverError.Code.PROVIDER_UNAVAILABLE,
                afterShutdown.errorOrThrow().code());
        ModelDriverResult<Void> afterClose = driver.close(session, context("close-after-shutdown"));
        assertTrue(afterClose.isSuccess());
    }

    private void assertFinalRace(boolean abort) throws Exception {
        CountDownLatch finalEntered = new CountDownLatch(1);
        CountDownLatch releaseFinal = new CountDownLatch(1);
        RecordingSink sink = new RecordingSink() {
            @Override
            public void onTranscript(CanonicalTranscriptEvent event) {
                if (event.isFinal()) {
                    finalEntered.countDown();
                    await(releaseFinal);
                }
                super.onTranscript(event);
            }
        };
        ModelDriver driver = createDriver(descriptor(), sink);
        ModelDriverSession session = open(driver, sink,
                abort ? "open-race-abort" : "open-race-close",
                abort ? "stream-race-abort" : "stream-race-close");
        driver.start(session, context("start"));
        driver.acceptAudio(session, frame(1, 0), context("frame-1"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ModelDriverResult<InputCompletion>> completing = executor.submit(() ->
                    driver.completeInput(session, 1,
                            abort ? "complete-race-abort" : "complete-race-close",
                            context("complete")));
            await(finalEntered);
            if (abort) {
                assertTrue(driver.abort(session, "USER_CANCEL", "cancel-race", context("cancel")).isSuccess());
            } else {
                assertTrue(driver.close(session, context("close")).isSuccess());
            }
            releaseFinal.countDown();
            ModelDriverResult<InputCompletion> result = completing.get(5, TimeUnit.SECONDS);
            assertFalse(result.isSuccess());
            assertEquals(ModelDriverError.Code.ALREADY_TERMINAL, result.errorOrThrow().code());
            assertTrue(sink.transcripts.size() >= 2,
                    "已开始的 final 回调至少应交付一份，不得静默丢失");
            assertTrue(sink.transcripts.stream().anyMatch(CanonicalTranscriptEvent::isFinal));
            assertTrue(sink.terminals.stream().noneMatch(t -> t.status() == ModelTerminal.Status.COMPLETED));
        } finally {
            releaseFinal.countDown();
            executor.shutdownNow();
        }
    }

    private ModelDriverSession open(
            ModelDriver driver, ModelEventSink sink, String key, String streamId) {
        return driver.openSession(
                spec(streamId, descriptor().capabilities()), sink, key, context("open")).valueOrThrow();
    }

    private static RealtimeSessionSpec spec(String streamId, Set<String> capabilities) {
        return new RealtimeSessionSpec(
                "meeting-tck",
                streamId,
                "zh-CN",
                AudioProfile.pcm16kMono(100),
                capabilities,
                1,
                3_600_000,
                "settings-tck");
    }

    private static MeetingAudioFrame frame(long sequence, long startMs) {
        return new MeetingAudioFrame(
                sequence, startMs, 100, new byte[]{1, 2, 3, 4}, null,
                1, 1, 0, Set.of());
    }

    private static ModelOperationContext context(String operationId) {
        return ModelOperationContext.withoutCancellation(operationId, NOW.plusSeconds(30));
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

    /** 测试固定 sink，仅保存规范化事件和终态，不保存音频或正文之外的敏感数据。 */
    protected static class RecordingSink implements ModelEventSink {
        protected final List<CanonicalTranscriptEvent> transcripts = new ArrayList<>();
        protected final List<ModelTerminal> terminals = new ArrayList<>();

        @Override
        public synchronized void onTranscript(CanonicalTranscriptEvent event) {
            transcripts.add(event);
        }

        @Override
        public synchronized void onTerminal(ModelTerminal terminal) {
            terminals.add(terminal);
        }
    }
}
