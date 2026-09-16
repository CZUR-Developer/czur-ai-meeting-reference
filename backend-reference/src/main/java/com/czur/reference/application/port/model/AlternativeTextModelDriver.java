package com.czur.reference.application.port.model;

import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityIds;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 仅使用 JDK 的第二个固定文本 Driver。
 *
 * <p>该实现拥有独立 Provider ID、能力集合、会话注册表和生命周期状态机，不
 * 依赖或包装其它 Driver。它把每个已接收帧输出为独立稳定分段，
 * completeInput 时逐段输出更高 revision 的 final，因此与 Mock 的滚动单分段
 * 语义不同，但两者都只输出公共 {@link CanonicalTranscriptEvent}。</p>
 *
 * <p>固定文本只用于契约测试和替换证明。日志只记录会话 ID、序号、计数和
 * 稳定原因，不输出音频字节、字幕正文、凭据或供应商响应。</p>
 */
public final class AlternativeTextModelDriver implements ModelDriver {
    private static final Logger LOGGER = Logger.getLogger(AlternativeTextModelDriver.class.getName());
    private static final Set<String> CAPABILITIES = Set.of(
            CapabilityIds.TRANSCRIPT_REALTIME,
            CapabilityIds.TRANSCRIPT_FINAL);

    private final CapabilityDescriptor descriptor = new CapabilityDescriptor(
            "alternative-fixed-text", "1.0.0", 1, CAPABILITIES);
    private final Clock clock;
    private final int maxConcurrentSessions;
    private final int maxInFlightCallbacks;
    private final Runnable beforeFinalDispatchHook;
    private final AtomicLong sessionSequence = new AtomicLong();
    private final AtomicLong diagnosticSequence = new AtomicLong();
    private final Object registryLock = new Object();
    private final Map<String, SessionRecord> sessions = new HashMap<>();
    private final Map<String, OpenRecord> opensByIdempotency = new HashMap<>();
    private volatile boolean acceptingNewSessions = true;

    /** 使用 UTC 系统时钟和默认资源上限创建固定文本 Driver。 */
    public AlternativeTextModelDriver() {
        this(Clock.systemUTC(), 4, 64);
    }

    /**
     * 使用可控时钟和资源上限创建 Driver。
     *
     * @param clock 检查调用 deadline 使用的时钟；不能为空
     * @param maxConcurrentSessions 同时存活的会话上限，必须为正数
     * @param maxInFlightCallbacks 单个会话尚未完成的回调上限，必须为正数
     */
    public AlternativeTextModelDriver(
            Clock clock, int maxConcurrentSessions, int maxInFlightCallbacks) {
        this(clock, maxConcurrentSessions, maxInFlightCallbacks, () -> { });
    }

    /** 供同包契约测试稳定覆盖 pending 创建与 final 派发之间的窗口。 */
    AlternativeTextModelDriver(
            Clock clock,
            int maxConcurrentSessions,
            int maxInFlightCallbacks,
            Runnable beforeFinalDispatchHook) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.beforeFinalDispatchHook = Objects.requireNonNull(
                beforeFinalDispatchHook, "beforeFinalDispatchHook");
        if (maxConcurrentSessions <= 0 || maxInFlightCallbacks <= 0) {
            throw new IllegalArgumentException("resource limits must be positive");
        }
        this.maxConcurrentSessions = maxConcurrentSessions;
        this.maxInFlightCallbacks = maxInFlightCallbacks;
    }

    @Override
    public CapabilityDescriptor describe() {
        return descriptor;
    }

    @Override
    public ModelDriverResult<Void> validateSettings(ModelOperationContext context) {
        ModelDriverResult<Void> guard = checkContext(context);
        if (!guard.isSuccess()) {
            return guard;
        }
        synchronized (registryLock) {
            if (!acceptingNewSessions) {
                return failure(ModelDriverError.Code.PROVIDER_UNAVAILABLE, false);
            }
        }
        return ModelDriverResult.success();
    }

    @Override
    public ModelDriverResult<ModelDriverSession> openSession(
            RealtimeSessionSpec spec,
            ModelEventSink sink,
            String idempotencyKey,
            ModelOperationContext context) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(sink, "sink");
        requireText("idempotencyKey", idempotencyKey);
        ModelDriverResult<Void> guard = checkContext(context);
        if (!guard.isSuccess()) {
            return failureFrom(guard);
        }
        synchronized (registryLock) {
            OpenRecord previous = opensByIdempotency.get(idempotencyKey);
            if (previous != null) {
                if (!previous.spec().equals(spec) || previous.sink() != sink) {
                    return failure(ModelDriverError.Code.CONFLICT, false);
                }
                return ModelDriverResult.success(previous.session());
            }
            if (!descriptor.capabilities().containsAll(spec.enabledCapabilities())) {
                return failure(ModelDriverError.Code.UNSUPPORTED, false);
            }
            int activeSessions = activeSessionCountLocked();
            if (!acceptingNewSessions || activeSessions >= maxConcurrentSessions) {
                return failure(ModelDriverError.Code.PROVIDER_UNAVAILABLE, true);
            }
            String sessionId = "alternative-session-" + sessionSequence.incrementAndGet();
            ModelDriverSession session = new ModelDriverSession(sessionId, spec);
            SessionRecord record = new SessionRecord(session, sink);
            sessions.put(sessionId, record);
            opensByIdempotency.put(idempotencyKey, new OpenRecord(spec, session, sink));
            LOGGER.log(Level.INFO, "固定文本 Driver 会话已打开 sessionId={0} streamId={1}",
                    new Object[]{sessionId, spec.streamId()});
            return ModelDriverResult.success(session);
        }
    }

    @Override
    public ModelDriverResult<Void> start(ModelDriverSession session, ModelOperationContext context) {
        SessionRecord record = findSession(session);
        if (record == null) {
            return failure(ModelDriverError.Code.INVALID_ARGUMENT, false);
        }
        ModelDriverResult<Void> guard = checkContext(record, context);
        if (!guard.isSuccess()) {
            return guard;
        }
        synchronized (record) {
            return switch (record.state) {
                case READY -> {
                    record.state = ModelDriverSessionState.STREAMING;
                    yield ModelDriverResult.success();
                }
                case STREAMING -> ModelDriverResult.success();
                case DRAINING, COMPLETED, FAILED, CLOSED ->
                        failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
                default -> failure(ModelDriverError.Code.NOT_READY, false);
            };
        }
    }

    @Override
    public ModelDriverResult<AudioAcceptance> acceptAudio(
            ModelDriverSession session,
            MeetingAudioFrame frame,
            ModelOperationContext context) {
        Objects.requireNonNull(frame, "frame");
        SessionRecord record = findSession(session);
        if (record == null) {
            return failure(ModelDriverError.Code.INVALID_ARGUMENT, false);
        }
        ModelDriverResult<Void> guard = checkContext(record, context);
        if (!guard.isSuccess()) {
            return failureFrom(guard);
        }
        CanonicalTranscriptEvent partial;
        AudioAcceptance acceptance;
        synchronized (record) {
            if (record.state != ModelDriverSessionState.STREAMING) {
                return failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
            }
            if (frame.streamSequence() <= record.lastAcceptedSequence) {
                return ModelDriverResult.success(new AudioAcceptance(
                        AudioAcceptance.Status.DUPLICATE,
                        record.lastAcceptedSequence,
                        record.lastAudioPositionMs,
                        maxInFlightCallbacks,
                        0));
            }
            if (frame.streamSequence() != record.lastAcceptedSequence + 1
                    || frame.startPositionMs() < record.lastAudioPositionMs) {
                return failure(ModelDriverError.Code.OUT_OF_ORDER, false);
            }
            if (record.inFlightCallbacks >= maxInFlightCallbacks) {
                return ModelDriverResult.success(new AudioAcceptance(
                        AudioAcceptance.Status.BACKPRESSURED,
                        record.lastAcceptedSequence,
                        record.lastAudioPositionMs,
                        maxInFlightCallbacks,
                        25));
            }
            record.lastAcceptedSequence = frame.streamSequence();
            record.lastAudioPositionMs = frame.startPositionMs() + frame.durationMs();
            partial = new CanonicalTranscriptEvent(
                    "alternative-segment-" + frame.streamSequence(),
                    1,
                    CanonicalTranscriptEvent.Operation.UPSERT,
                    frame.startPositionMs(),
                    record.lastAudioPositionMs,
                    session.spec().sourceLanguage(),
                    "alternative partial " + frame.streamSequence(),
                    false,
                    null,
                    0.6,
                    List.of("fixture"),
                    List.of());
            record.partials.add(partial);
            record.inFlightCallbacks++;
            acceptance = new AudioAcceptance(
                    AudioAcceptance.Status.ACCEPTED,
                    record.lastAcceptedSequence,
                    record.lastAudioPositionMs,
                    maxInFlightCallbacks - record.inFlightCallbacks,
                    0);
        }
        try {
            ModelDriverError callbackError = dispatchTranscript(record, partial);
            if (callbackError != null) {
                return ModelDriverResult.failure(callbackError);
            }
        } finally {
            synchronized (record) {
                record.inFlightCallbacks--;
            }
        }
        return ModelDriverResult.success(acceptance);
    }

    @Override
    public ModelDriverResult<DiscontinuityAction> notifyDiscontinuity(
            ModelDriverSession session,
            AudioDiscontinuity discontinuity,
            ModelOperationContext context) {
        Objects.requireNonNull(discontinuity, "discontinuity");
        SessionRecord record = findSession(session);
        if (record == null) {
            return failure(ModelDriverError.Code.INVALID_ARGUMENT, false);
        }
        ModelDriverResult<Void> guard = checkContext(record, context);
        if (!guard.isSuccess()) {
            return failureFrom(guard);
        }
        synchronized (record) {
            if (record.state != ModelDriverSessionState.STREAMING) {
                return failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
            }
            if (discontinuity.afterStreamSequence() > record.lastAcceptedSequence) {
                return failure(ModelDriverError.Code.OUT_OF_ORDER, false);
            }
            record.lastAudioPositionMs = Math.max(
                    record.lastAudioPositionMs, discontinuity.resumeAudioPositionMs());
            return ModelDriverResult.success(DiscontinuityAction.CONTINUED);
        }
    }

    @Override
    public ModelDriverResult<InputCompletion> completeInput(
            ModelDriverSession session,
            long lastSequence,
            String idempotencyKey,
            ModelOperationContext context) {
        requireText("idempotencyKey", idempotencyKey);
        if (lastSequence < 0) {
            return failure(ModelDriverError.Code.INVALID_ARGUMENT, false);
        }
        SessionRecord record = findSession(session);
        if (record == null) {
            return failure(ModelDriverError.Code.INVALID_ARGUMENT, false);
        }
        ModelDriverResult<Void> guard = checkContext(record, context);
        if (!guard.isSuccess()) {
            return failureFrom(guard);
        }
        PendingCompletion pending;
        boolean pendingCreated = false;
        synchronized (record) {
            CompletionRecord previous = record.completionsByKey.get(idempotencyKey);
            if (previous != null) {
                if (previous.lastSequence() != lastSequence) {
                    return failure(ModelDriverError.Code.CONFLICT, false);
                }
                return ModelDriverResult.success(previous.completion());
            }
            if (!record.completionsByKey.isEmpty()) {
                return failure(ModelDriverError.Code.CONFLICT, false);
            }
            if (record.pendingCompletion != null) {
                pending = record.pendingCompletion;
                if (!pending.idempotencyKey.equals(idempotencyKey)
                        || pending.lastSequence != lastSequence) {
                    return failure(ModelDriverError.Code.CONFLICT, false);
                }
                if (record.state != ModelDriverSessionState.DRAINING
                        && record.state != ModelDriverSessionState.COMPLETED
                        && record.state != ModelDriverSessionState.CLOSED) {
                    return failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
                }
            } else {
                if (record.state != ModelDriverSessionState.STREAMING) {
                    return failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
                }
                if (lastSequence != record.lastAcceptedSequence) {
                    return failure(ModelDriverError.Code.INPUT_SEQUENCE_MISMATCH, false);
                }
                if (record.inFlightCallbacks > 0) {
                    return failure(ModelDriverError.Code.BACKPRESSURED, true);
                }
                record.state = ModelDriverSessionState.DRAINING;
                List<CanonicalTranscriptEvent> finals = new ArrayList<>();
                for (CanonicalTranscriptEvent partial : record.partials) {
                    finals.add(new CanonicalTranscriptEvent(
                            partial.segmentId(),
                            partial.revision() + 1,
                            CanonicalTranscriptEvent.Operation.REPLACE,
                            partial.startMs(),
                            partial.endMs(),
                            partial.language(),
                            "alternative final " + partial.segmentId(),
                            true,
                            null,
                            0.95,
                            partial.qualityFlags(),
                            List.of()));
                }
                InputCompletion completion = new InputCompletion(
                        record.lastAcceptedSequence,
                        record.lastAudioPositionMs,
                        finals.size());
                pending = new PendingCompletion(idempotencyKey, lastSequence, completion, finals);
                record.pendingCompletion = pending;
                pendingCreated = true;
            }
        }
        if (pendingCreated) {
            beforeFinalDispatchHook.run();
        }
        boolean dispatchFinals = false;
        synchronized (record) {
            if (!pending.finalDelivered) {
                if (pending.finalDispatching) {
                    return failure(ModelDriverError.Code.BACKPRESSURED, true);
                }
                pending.finalDispatching = true;
                dispatchFinals = true;
            }
        }
        if (dispatchFinals) {
            ModelDriverError callbackError = null;
            for (CanonicalTranscriptEvent event : pending.finalEvents) {
                callbackError = dispatchTranscript(record, event);
                if (callbackError != null) {
                    break;
                }
            }
            synchronized (record) {
                pending.finalDispatching = false;
                if (callbackError != null) {
                    return ModelDriverResult.failure(callbackError);
                }
                pending.finalDelivered = true;
                if (pending.finalDeliverySuppressed) {
                    return failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
                }
                if (record.state != ModelDriverSessionState.DRAINING
                        || record.pendingCompletion != pending) {
                    return failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
                }
            }
        }
        synchronized (record) {
            if (record.pendingCompletion != pending || pending.finalDeliverySuppressed) {
                return failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
            }
            if (pending.terminalDelivered) {
                return ModelDriverResult.success(pending.completion);
            }
            if (pending.terminalDispatching) {
                return failure(ModelDriverError.Code.BACKPRESSURED, true);
            }
            if (record.state == ModelDriverSessionState.DRAINING) {
                record.state = ModelDriverSessionState.COMPLETED;
            } else if (record.state != ModelDriverSessionState.COMPLETED
                    && record.state != ModelDriverSessionState.CLOSED) {
                return failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
            }
            pending.terminalDispatching = true;
        }
        ModelDriverError terminalError = dispatchTerminal(
                record, new ModelTerminal(ModelTerminal.Status.COMPLETED, null));
        synchronized (record) {
            pending.terminalDispatching = false;
            if (terminalError != null) {
                return ModelDriverResult.failure(terminalError);
            }
            pending.terminalDelivered = true;
            record.completionsByKey.put(
                    idempotencyKey, new CompletionRecord(lastSequence, pending.completion));
            record.pendingCompletion = null;
            return ModelDriverResult.success(pending.completion);
        }
    }

    @Override
    public ModelDriverResult<Void> abort(
            ModelDriverSession session,
            String reason,
            String idempotencyKey,
            ModelOperationContext context) {
        requireText("reason", reason);
        requireText("idempotencyKey", idempotencyKey);
        SessionRecord record = findSession(session);
        if (record == null) {
            return failure(ModelDriverError.Code.INVALID_ARGUMENT, false);
        }
        ModelDriverResult<Void> guard = checkContext(record, context);
        if (!guard.isSuccess()) {
            return guard;
        }
        AbortRecord abort;
        synchronized (record) {
            AbortRecord previous = record.abortsByKey.get(idempotencyKey);
            if (previous != null) {
                if (!previous.reason.equals(reason)) {
                    return failure(ModelDriverError.Code.CONFLICT, false);
                }
                if (previous.terminalDelivered) {
                    return ModelDriverResult.success();
                }
                if (previous.terminalDispatching) {
                    return failure(ModelDriverError.Code.BACKPRESSURED, true);
                }
                abort = previous;
                abort.terminalDispatching = true;
            } else {
                if (record.state == ModelDriverSessionState.CLOSED) {
                    return ModelDriverResult.success();
                }
                if (record.state == ModelDriverSessionState.COMPLETED
                        || record.state == ModelDriverSessionState.FAILED) {
                    return failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
                }
                abort = new AbortRecord(reason, new ModelTerminal(
                        ModelTerminal.Status.CANCELLED,
                        error(ModelDriverError.Code.CANCELLED, false)));
                abort.terminalDispatching = true;
                record.abortsByKey.put(idempotencyKey, abort);
                record.state = ModelDriverSessionState.FAILED;
            }
        }
        ModelDriverError terminalError = dispatchTerminal(record, abort.terminal);
        synchronized (record) {
            abort.terminalDispatching = false;
            if (terminalError != null) {
                return ModelDriverResult.failure(terminalError);
            }
            abort.terminalDelivered = true;
            return ModelDriverResult.success();
        }
    }

    @Override
    public ModelDriverResult<Void> close(ModelDriverSession session, ModelOperationContext context) {
        SessionRecord record = findSession(session);
        if (record == null) {
            return failure(ModelDriverError.Code.INVALID_ARGUMENT, false);
        }
        synchronized (record) {
            if (record.state == ModelDriverSessionState.CLOSED) {
                return ModelDriverResult.success();
            }
            if (record.pendingCompletion != null && !record.pendingCompletion.finalDelivered) {
                record.pendingCompletion.finalDeliverySuppressed = true;
            }
            record.state = ModelDriverSessionState.CLOSED;
            return ModelDriverResult.success();
        }
    }

    @Override
    public void shutdown(Instant deadline) {
        Objects.requireNonNull(deadline, "deadline");
        acceptingNewSessions = false;
        synchronized (registryLock) {
            for (SessionRecord record : sessions.values()) {
                synchronized (record) {
                    record.state = ModelDriverSessionState.CLOSED;
                }
            }
        }
    }

    private SessionRecord findSession(ModelDriverSession session) {
        if (session == null) {
            return null;
        }
        synchronized (registryLock) {
            SessionRecord record = sessions.get(session.sessionId());
            return record != null && record.session == session ? record : null;
        }
    }

    private int activeSessionCountLocked() {
        int active = 0;
        for (SessionRecord record : sessions.values()) {
            synchronized (record) {
                if (record.state != ModelDriverSessionState.CLOSED) {
                    active++;
                }
            }
        }
        return active;
    }

    private ModelDriverResult<Void> checkContext(ModelOperationContext context) {
        if (context == null) {
            return failure(ModelDriverError.Code.INVALID_ARGUMENT, false);
        }
        if (context.cancelled()) {
            return failure(ModelDriverError.Code.CANCELLED, false);
        }
        if (context.expired(clock.instant())) {
            return failure(ModelDriverError.Code.TIMEOUT, true);
        }
        return ModelDriverResult.success();
    }

    private ModelDriverResult<Void> checkContext(
            SessionRecord record, ModelOperationContext context) {
        ModelDriverResult<Void> result = checkContext(context);
        if (result.isSuccess() || result.errorOrThrow().code() != ModelDriverError.Code.TIMEOUT) {
            return result;
        }
        boolean transitioned = false;
        synchronized (record) {
            if (record.state != ModelDriverSessionState.CLOSED
                    && record.state != ModelDriverSessionState.COMPLETED) {
                record.state = ModelDriverSessionState.FAILED;
                transitioned = true;
            }
        }
        if (transitioned) {
            dispatchTerminal(record, new ModelTerminal(ModelTerminal.Status.FAILED, result.errorOrThrow()));
        }
        return result;
    }

    private ModelDriverError dispatchTranscript(
            SessionRecord record, CanonicalTranscriptEvent event) {
        try {
            record.sink.onTranscript(event);
            return null;
        } catch (RuntimeException callbackFailure) {
            ModelDriverError callbackError = error(ModelDriverError.Code.CALLBACK_FAILED, false);
            boolean transitioned = false;
            synchronized (record) {
                if (record.state == ModelDriverSessionState.STREAMING
                        || record.state == ModelDriverSessionState.DRAINING) {
                    record.state = ModelDriverSessionState.FAILED;
                    transitioned = true;
                }
            }
            if (transitioned) {
                dispatchTerminal(record, new ModelTerminal(ModelTerminal.Status.FAILED, callbackError));
            }
            return callbackError;
        }
    }

    private ModelDriverError dispatchTerminal(SessionRecord record, ModelTerminal terminal) {
        try {
            record.sink.onTerminal(terminal);
            return null;
        } catch (RuntimeException callbackFailure) {
            return error(ModelDriverError.Code.CALLBACK_FAILED, true);
        }
    }

    private <T> ModelDriverResult<T> failureFrom(ModelDriverResult<?> result) {
        return ModelDriverResult.failure(result.errorOrThrow());
    }

    private <T> ModelDriverResult<T> failure(ModelDriverError.Code code, boolean retryable) {
        return ModelDriverResult.failure(error(code, retryable));
    }

    private ModelDriverError error(ModelDriverError.Code code, boolean retryable) {
        return ModelDriverError.of(
                code, retryable, "alternative-diag-" + diagnosticSequence.incrementAndGet());
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record OpenRecord(
            RealtimeSessionSpec spec, ModelDriverSession session, ModelEventSink sink) {
    }

    private record CompletionRecord(long lastSequence, InputCompletion completion) {
    }

    private static final class PendingCompletion {
        private final String idempotencyKey;
        private final long lastSequence;
        private final InputCompletion completion;
        private final List<CanonicalTranscriptEvent> finalEvents;
        private boolean finalDispatching;
        private boolean finalDeliverySuppressed;
        private boolean finalDelivered;
        private boolean terminalDispatching;
        private boolean terminalDelivered;

        private PendingCompletion(
                String idempotencyKey,
                long lastSequence,
                InputCompletion completion,
                List<CanonicalTranscriptEvent> finalEvents) {
            this.idempotencyKey = idempotencyKey;
            this.lastSequence = lastSequence;
            this.completion = completion;
            this.finalEvents = List.copyOf(finalEvents);
        }
    }

    private static final class AbortRecord {
        private final String reason;
        private final ModelTerminal terminal;
        private boolean terminalDispatching;
        private boolean terminalDelivered;

        private AbortRecord(String reason, ModelTerminal terminal) {
            this.reason = reason;
            this.terminal = terminal;
        }
    }

    private static final class SessionRecord {
        private final ModelDriverSession session;
        private final ModelEventSink sink;
        private final List<CanonicalTranscriptEvent> partials = new ArrayList<>();
        private final Map<String, CompletionRecord> completionsByKey = new HashMap<>();
        private final Map<String, AbortRecord> abortsByKey = new HashMap<>();
        private PendingCompletion pendingCompletion;
        private ModelDriverSessionState state = ModelDriverSessionState.READY;
        private long lastAcceptedSequence;
        private long lastAudioPositionMs;
        private int inFlightCallbacks;

        private SessionRecord(ModelDriverSession session, ModelEventSink sink) {
            this.session = session;
            this.sink = sink;
        }
    }
}
