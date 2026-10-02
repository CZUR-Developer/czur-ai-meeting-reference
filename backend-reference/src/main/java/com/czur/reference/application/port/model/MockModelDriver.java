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
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 无网络、无计费副作用的参考 Model Driver。
 *
 * <p>Mock 以每个连续音频帧修订同一个稳定分段，并在 completeInput 时产生
 * 更高 revision 的 final 事件。它只用于验证端口生命周期、序号、幂等和回调
 * 语义；返回的文本是固定测试数据，不能代表真实模型质量。</p>
 *
 * <p>实现使用每会话锁保护状态，回调在释放锁后执行，避免 Orchestrator 回调
 * 反向调用 Driver 造成死锁。日志仅输出会话 ID、序号和计数，不输出音频字节、
 * 字幕正文、凭据或供应商响应。</p>
 */
public final class MockModelDriver implements ModelDriver {
    private static final Logger LOGGER = Logger.getLogger(MockModelDriver.class.getName());

    private final CapabilityDescriptor descriptor;
    private final Clock clock;
    private final int maxConcurrentSessions;
    private final int maxInFlightCallbacks;
    /** 仅用于让契约测试稳定重现 pending 创建与 final 派发之间的竞态。 */
    private final Runnable beforeFinalDispatchHook;
    private final AtomicLong sessionSequence = new AtomicLong();
    private final AtomicLong diagnosticSequence = new AtomicLong();
    private final Object registryLock = new Object();
    private final Map<String, SessionRecord> sessions = new HashMap<>();
    private final Map<String, OpenRecord> opensByIdempotency = new HashMap<>();
    private volatile boolean acceptingNewSessions = true;

    /** 使用 UTC 系统时钟和每会话 64 个在途回调上限创建 Mock。 */
    public MockModelDriver(CapabilityDescriptor descriptor) {
        this(descriptor, Clock.systemUTC(), 4, 64);
    }

    /**
     * 使用可控时钟和资源上限创建 Mock，便于测试 timeout、并发和背压。
     *
     * @param descriptor 已登记并通过契约测试的能力描述
     * @param clock Driver 检查 deadline 使用的时钟
     * @param maxConcurrentSessions 同时打开的会话上限
     * @param maxInFlightCallbacks 单会话尚未完成的事件回调上限
     */
    public MockModelDriver(
            CapabilityDescriptor descriptor,
            Clock clock,
            int maxConcurrentSessions,
            int maxInFlightCallbacks) {
        this(descriptor, clock, maxConcurrentSessions, maxInFlightCallbacks, () -> { });
    }

    /**
     * 供同包契约测试注入 final 派发前的暂停点；生产调用应使用四参数构造函数。
     */
    MockModelDriver(
            CapabilityDescriptor descriptor,
            Clock clock,
            int maxConcurrentSessions,
            int maxInFlightCallbacks,
            Runnable beforeFinalDispatchHook) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
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
        LOGGER.log(Level.FINE, "Mock Driver 配置校验通过 provider={0}", descriptor.providerId());
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
                    LOGGER.log(Level.WARNING, "Model 会话幂等键参数冲突 keyHash={0}", hashForLog(idempotencyKey));
                    return failure(ModelDriverError.Code.CONFLICT, false);
                }
                LOGGER.log(Level.FINE, "Model 会话打开幂等重放 sessionId={0}", previous.session().sessionId());
                return ModelDriverResult.success(previous.session());
            }
            if (!descriptor.capabilities().containsAll(spec.enabledCapabilities())) {
                LOGGER.log(Level.WARNING,
                        "Model 会话包含 Driver 未注册能力 provider={0} planRevision={1}",
                        new Object[]{descriptor.providerId(), spec.planRevision()});
                return failure(ModelDriverError.Code.UNSUPPORTED, false);
            }
            int activeSessions = activeSessionCountLocked();
            if (!acceptingNewSessions || activeSessions >= maxConcurrentSessions) {
                LOGGER.log(Level.WARNING, "Model 会话达到资源上限 active={0}", activeSessions);
                return failure(ModelDriverError.Code.PROVIDER_UNAVAILABLE, true);
            }
            String sessionId = "mock-session-" + sessionSequence.incrementAndGet();
            ModelDriverSession session = new ModelDriverSession(sessionId, spec);
            SessionRecord record = new SessionRecord(session, sink);
            sessions.put(sessionId, record);
            opensByIdempotency.put(idempotencyKey, new OpenRecord(spec, session, sink));
            LOGGER.log(Level.INFO, "Model 会话已打开 sessionId={0} streamId={1}",
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
                    LOGGER.log(Level.INFO, "Model 会话开始接收音频 sessionId={0}", session.sessionId());
                    yield ModelDriverResult.success();
                }
                case STREAMING -> {
                    LOGGER.log(Level.FINE, "Model start 幂等重放 sessionId={0}", session.sessionId());
                    yield ModelDriverResult.success();
                }
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
                LOGGER.log(Level.WARNING, "Model 输入被拒绝 state={0} sessionId={1}",
                        new Object[]{record.state, session.sessionId()});
                return failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
            }
            if (frame.streamSequence() <= record.lastAcceptedSequence) {
                LOGGER.log(Level.FINE, "Model 收到重复音频帧 sessionId={0} sequence={1}",
                        new Object[]{session.sessionId(), frame.streamSequence()});
                return ModelDriverResult.success(new AudioAcceptance(
                        AudioAcceptance.Status.DUPLICATE,
                        record.lastAcceptedSequence,
                        record.lastAudioPositionMs,
                        maxInFlightCallbacks,
                        0));
            }
            if (frame.streamSequence() != record.lastAcceptedSequence + 1
                    || frame.startPositionMs() < record.lastAudioPositionMs) {
                LOGGER.log(Level.WARNING, "Model 音频帧顺序错误 sessionId={0} sequence={1} expected={2}",
                        new Object[]{session.sessionId(), frame.streamSequence(), record.lastAcceptedSequence + 1});
                return failure(ModelDriverError.Code.OUT_OF_ORDER, false);
            }
            if (record.inFlightCallbacks >= maxInFlightCallbacks) {
                LOGGER.log(Level.WARNING, "Model 回调队列达到上限 sessionId={0} inFlight={1}",
                        new Object[]{session.sessionId(), record.inFlightCallbacks});
                return ModelDriverResult.success(new AudioAcceptance(
                        AudioAcceptance.Status.BACKPRESSURED,
                        record.lastAcceptedSequence,
                        record.lastAudioPositionMs,
                        maxInFlightCallbacks,
                        20));
            }
            record.lastAcceptedSequence = frame.streamSequence();
            record.lastAudioPositionMs = frame.startPositionMs() + frame.durationMs();
            int revision = record.currentPartial == null ? 1 : record.currentPartial.revision() + 1;
            long segmentStartMs = record.currentPartial == null
                    ? frame.startPositionMs()
                    : record.currentPartial.startMs();
            partial = new CanonicalTranscriptEvent(
                    "mock-segment-1",
                    revision,
                    CanonicalTranscriptEvent.Operation.UPSERT,
                    segmentStartMs,
                    record.lastAudioPositionMs,
                    session.spec().sourceLanguage(),
                    "模拟实时字幕（修订 " + revision + "）",
                    false,
                    speakerReference(session),
                    0.5,
                    List.of(),
                    List.of());
            record.currentPartial = partial;
            record.inFlightCallbacks++;
            record.emittedEventCount++;
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
        LOGGER.log(Level.FINER, "Model 音频帧已接收 sessionId={0} sequence={1}",
                new Object[]{session.sessionId(), frame.streamSequence()});
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
            // 允许显式确认缺口；旧通知不回退游标，下一个合法帧仍连续递增。
            record.lastAudioPositionMs = Math.max(
                    record.lastAudioPositionMs, discontinuity.resumeAudioPositionMs());
            LOGGER.log(Level.INFO, "Model 音频连续性已确认 sessionId={0} afterSequence={1} reason={2}",
                    new Object[]{session.sessionId(), discontinuity.afterStreamSequence(), discontinuity.reason()});
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
                LOGGER.log(Level.FINE, "Model completeInput 幂等重放 sessionId={0}", session.sessionId());
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
                    LOGGER.log(Level.WARNING,
                            "Model completeInput 序号不一致 sessionId={0} expected={1} actual={2}",
                            new Object[]{session.sessionId(), record.lastAcceptedSequence, lastSequence});
                    return failure(ModelDriverError.Code.INPUT_SEQUENCE_MISMATCH, false);
                }
                if (record.inFlightCallbacks > 0) {
                    LOGGER.log(Level.FINE, "Model completeInput 等待在途回调 sessionId={0} inFlight={1}",
                            new Object[]{session.sessionId(), record.inFlightCallbacks});
                    return failure(ModelDriverError.Code.BACKPRESSURED, true);
                }
                record.state = ModelDriverSessionState.DRAINING;
                List<CanonicalTranscriptEvent> finals = new ArrayList<>();
                if (record.currentPartial != null) {
                    CanonicalTranscriptEvent partial = record.currentPartial;
                    finals.add(new CanonicalTranscriptEvent(
                            partial.segmentId(),
                            partial.revision() + 1,
                            partial.operation(),
                            partial.startMs(),
                            partial.endMs(),
                            partial.language(),
                            "mock final",
                            true,
                            partial.speaker(),
                            0.9,
                            partial.qualityFlags(),
                            partial.supersedesSegmentIds()));
                }
                InputCompletion completion = new InputCompletion(
                        record.lastAcceptedSequence, record.lastAudioPositionMs, finals.size());
                pending = new PendingCompletion(idempotencyKey, lastSequence, completion, finals);
                record.pendingCompletion = pending;
                pendingCreated = true;
            }
        }
        if (pendingCreated) {
            // 该暂停点只存在于参考 Mock，用于确定性覆盖 close 抢在 finalDispatching
            // 设置前的窗口；真实 Driver 不依赖此回调。
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
                // Sink 已成功返回，先记录 final 已交付，再根据 close/abort 的线性化
                // 结果决定是否还允许补发 COMPLETED terminal。
                pending.finalDelivered = true;
                record.emittedEventCount += pending.finalEvents.size();
                if (pending.finalDeliverySuppressed) {
                    // close 已在线性化点抢占完成；final 虽已由 Sink 接管，但不能
                    // 因后续重试再补发 COMPLETED terminal。
                    return failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
                }
                // final 回调期间 abort/close 可以抢先终止。完成路径不得覆盖其终态，
                // 也不得再发送 COMPLETED terminal。
                if (record.state != ModelDriverSessionState.DRAINING
                        || record.pendingCompletion != pending) {
                    return failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
                }
            }
        }
        synchronized (record) {
            if (record.pendingCompletion != pending) {
                return failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
            }
            if (pending.finalDeliverySuppressed) {
                return failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
            }
            if (pending.terminalDelivered) {
                return ModelDriverResult.success(pending.completion);
            }
            if (pending.terminalDispatching) {
                return failure(ModelDriverError.Code.BACKPRESSURED, true);
            }
            if (record.state == ModelDriverSessionState.DRAINING) {
                // 这是完成与取消/关闭的线性化点。之后 abort 不能再覆盖 COMPLETED；
                // 即使 terminal 回调暂时失败，也只重试 terminal，不重复 final。
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
            LOGGER.log(Level.INFO, "Model 输入已排空 sessionId={0} finalCount={1}",
                    new Object[]{session.sessionId(), pending.finalEvents.size()});
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
                if (record.state == ModelDriverSessionState.COMPLETED) {
                    return failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
                }
                if (record.state == ModelDriverSessionState.FAILED) {
                    return failure(ModelDriverError.Code.ALREADY_TERMINAL, false);
                }
                ModelTerminal terminal = new ModelTerminal(
                        ModelTerminal.Status.CANCELLED,
                        error(ModelDriverError.Code.CANCELLED, false));
                abort = new AbortRecord(reason, terminal);
                abort.terminalDispatching = true;
                record.abortsByKey.put(idempotencyKey, abort);
                record.state = ModelDriverSessionState.FAILED;
                LOGGER.log(Level.INFO, "Model 会话已取消 sessionId={0}", session.sessionId());
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
        if (context == null || context.cancelled() || context.expired(clock.instant())) {
            // close 是兜底资源释放，不因业务调用已超时或取消而跳过；只记录状态。
            LOGGER.log(Level.FINE, "Model close 在取消或超时后继续释放资源 sessionId={0}", session.sessionId());
        }
        synchronized (record) {
            if (record.state == ModelDriverSessionState.CLOSED) {
                return ModelDriverResult.success();
            }
            if (record.pendingCompletion != null
                    && !record.pendingCompletion.finalDelivered) {
                // close 可能在完成线程开始或结束 final 交付前抢占。记录该线性化结果，
                // 避免稍后的同幂等键重试再次发送 final 或 terminal。
                record.pendingCompletion.finalDeliverySuppressed = true;
            }
            record.state = ModelDriverSessionState.CLOSED;
            LOGGER.log(Level.INFO, "Model 会话资源已关闭 sessionId={0}", session.sessionId());
            return ModelDriverResult.success();
        }
    }

    /** 返回不含敏感数据的会话快照，供测试和指标使用。 */
    public ModelDriverResult<SessionSnapshot> snapshot(ModelDriverSession session) {
        SessionRecord record = findSession(session);
        if (record == null) {
            return failure(ModelDriverError.Code.INVALID_ARGUMENT, false);
        }
        synchronized (record) {
            return ModelDriverResult.success(new SessionSnapshot(
                    record.session, record.state, record.lastAcceptedSequence,
                    record.lastAudioPositionMs, record.emittedEventCount));
        }
    }

    @Override
    public void shutdown(Instant deadline) {
        Objects.requireNonNull(deadline, "deadline");
        acceptingNewSessions = false;
        int closedSessions = 0;
        synchronized (registryLock) {
            for (SessionRecord record : sessions.values()) {
                synchronized (record) {
                    if (record.state != ModelDriverSessionState.CLOSED) {
                        record.state = ModelDriverSessionState.CLOSED;
                        closedSessions++;
                    }
                }
            }
        }
        LOGGER.log(Level.INFO, "Mock Driver 已关闭 sessions={0} deadline={1}",
                new Object[]{closedSessions, deadline});
    }

    private SessionRecord findSession(ModelDriverSession session) {
        if (session == null) {
            return null;
        }
        synchronized (registryLock) {
            SessionRecord record = sessions.get(session.sessionId());
            // ModelDriverSession 是对外可见的值对象，调用方可以复制 sessionId/spec。
            // 只有 Driver 自己曾返回的那个对象才是有效句柄，不能用值相等放行伪造句柄。
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

    private ModelDriverResult<Void> checkContext(SessionRecord record, ModelOperationContext context) {
        ModelDriverResult<Void> result = checkContext(context);
        if (result.isSuccess()
                || result.errorOrThrow().code() != ModelDriverError.Code.TIMEOUT) {
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
        LOGGER.log(Level.WARNING, "Model 调用超时 sessionId={0}", record.session.sessionId());
        if (transitioned) {
            dispatchTerminal(record, new ModelTerminal(ModelTerminal.Status.FAILED, result.errorOrThrow()));
        }
        return result;
    }

    private ModelDriverError dispatchTranscript(SessionRecord record, CanonicalTranscriptEvent event) {
        try {
            record.sink.onTranscript(event);
            return null;
        } catch (RuntimeException callbackFailure) {
            LOGGER.log(Level.WARNING, "Model 事件回调失败 sessionId={0}", record.session.sessionId());
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
            LOGGER.log(Level.WARNING, "Model 终态回调失败 sessionId={0}", record.session.sessionId());
            return error(ModelDriverError.Code.CALLBACK_FAILED, true);
        }
    }

    private SpeakerReference speakerReference(ModelDriverSession session) {
        return descriptor.supports(CapabilityIds.SPEAKER_DIARIZATION_SESSION)
                && session.spec().enabledCapabilities().contains(
                        CapabilityIds.SPEAKER_DIARIZATION_SESSION)
                ? new SpeakerReference("mock-speaker-1", null, null)
                : null;
    }

    private <T> ModelDriverResult<T> failureFrom(ModelDriverResult<?> result) {
        return ModelDriverResult.failure(result.errorOrThrow());
    }

    private <T> ModelDriverResult<T> failure(ModelDriverError.Code code, boolean retryable) {
        return ModelDriverResult.failure(error(code, retryable));
    }

    private ModelDriverError error(ModelDriverError.Code code, boolean retryable) {
        return ModelDriverError.of(code, retryable, "mock-diag-" + diagnosticSequence.incrementAndGet());
    }

    private static String hashForLog(String value) {
        return Integer.toHexString(value.hashCode());
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record OpenRecord(RealtimeSessionSpec spec, ModelDriverSession session, ModelEventSink sink) {
    }

    private record CompletionRecord(long lastSequence, InputCompletion completion) {
    }

    /** Mock 只有一个滚动分段，因此 finalEvents 首版最多包含一个事件。 */
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
        private final Map<String, CompletionRecord> completionsByKey = new HashMap<>();
        private final Map<String, AbortRecord> abortsByKey = new HashMap<>();
        private PendingCompletion pendingCompletion;
        private ModelDriverSessionState state = ModelDriverSessionState.READY;
        private long lastAcceptedSequence;
        private long lastAudioPositionMs;
        private int emittedEventCount;
        private int inFlightCallbacks;
        private CanonicalTranscriptEvent currentPartial;

        private SessionRecord(ModelDriverSession session, ModelEventSink sink) {
            this.session = session;
            this.sink = sink;
        }
    }
}
