package com.czur.reference.application.realtime;

import com.czur.reference.application.port.DurableAudioInputPort;
import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.model.AudioAcceptance;
import com.czur.reference.application.port.model.CanonicalTranscriptEvent;
import com.czur.reference.application.port.model.MeetingAudioFrame;
import com.czur.reference.application.port.model.MockModelDriver;
import com.czur.reference.application.port.model.ModelDriverSession;
import com.czur.reference.application.port.model.ModelEventSink;
import com.czur.reference.application.port.model.ModelOperationContext;
import com.czur.reference.domain.capability.CapabilityDescriptor;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * ASR-only Mock 的连接内临时状态；V015 帧和 meeting_event 才是跨连接事实。
 *
 * <p>每次 stream_start 新建 MockModelDriver 会话并从 V015 按序重放到当次持久
 * 游标，重放回调按稳定事件 ID 幂等入库。此后只处理已由 V015 Stored 确认的新
 * 帧；事件发布失败即丢弃整个会话，不在 FAILED Driver 上重试同帧，也不把模型
 * 的内存接收游标当 durable ACK。无处理游标、队列或新表。</p>
 */
public final class RealtimeMockTranscriptRuntime implements RealtimeTranscriptSessions {
    private final RealtimeMockSessionSpecLookup specs;
    private final DurableAudioInputPort audio;
    private final RealtimeTranscriptEventPublisher events;
    private final CapabilityDescriptor descriptor;
    private final Clock clock;

    public RealtimeMockTranscriptRuntime(RealtimeMockSessionSpecLookup specs,
            DurableAudioInputPort audio, RealtimeTranscriptEventPublisher events,
            CapabilityDescriptor descriptor, Clock clock) {
        this.specs = Objects.requireNonNull(specs, "specs");
        this.audio = Objects.requireNonNull(audio, "audio");
        this.events = Objects.requireNonNull(events, "events");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 新物理连接成功完成持久 Start/绑定后调用；失败时不保留会话。 */
    @Override
    public Session open(String meetingId) {
        var spec = specs.load(meetingId);
        Session session = new Session(meetingId, new MockModelDriver(descriptor, clock, 1, 64));
        try {
            session.start(spec);
            session.replay();
            return session;
        } catch (RuntimeException failure) {
            session.close();
            throw new IllegalStateException("mock transcript session unavailable");
        }
    }

    public final class Session implements RealtimeTranscriptSessions.Session {
        private final String meetingId;
        private final MockModelDriver driver;
        private ModelDriverSession modelSession;
        private long processedSequence;
        private long activeFrameSequence;
        private RealtimeTranscriptEventPublisher.Published lastPublished;
        private boolean closed;

        private Session(String meetingId, MockModelDriver driver) {
            this.meetingId = meetingId;
            this.driver = driver;
        }

        private void start(com.czur.reference.application.port.model.RealtimeSessionSpec spec) {
            ModelEventSink sink = this::publish;
            var opened = driver.openSession(spec, sink, "mock-connection-" + UUID.randomUUID(),
                    context("open"));
            if (!opened.isSuccess()) throw unavailable();
            modelSession = opened.valueOrThrow();
            if (!driver.start(modelSession, context("start")).isSuccess()) throw unavailable();
        }

        private void replay() {
            long target = -1;
            while (true) {
                var result = audio.replay(meetingId, processedSequence, 1000);
                if (!(result instanceof DurableAudioInputPort.FramePage page)) {
                    throw unavailable();
                }
                if (target < 0) target = page.acceptedStreamSequence();
                if (processedSequence == target) return;
                if (page.frames().isEmpty()) throw unavailable();
                for (MeetingAudioFrame frame : page.frames()) {
                    if (frame.streamSequence() > target) return;
                    accept(frame);
                }
            }
        }

        /** V015 Stored 后增量处理；新落库事件返回给连接层，重放/重复不再推送。 */
        public synchronized EventLog acceptStored(MeetingAudioFrame frame) {
            if (closed || modelSession == null) throw unavailable();
            if (frame.streamSequence() <= processedSequence) return null;
            accept(frame);
            return lastPublished != null && lastPublished.newlyAppended()
                    ? lastPublished.event() : null;
        }

        private void accept(MeetingAudioFrame frame) {
            if (frame.streamSequence() != processedSequence + 1) throw unavailable();
            activeFrameSequence = frame.streamSequence();
            lastPublished = null;
            var result = driver.acceptAudio(modelSession, frame, context("frame"));
            if (!result.isSuccess()
                    || result.valueOrThrow().status() != AudioAcceptance.Status.ACCEPTED
                    || lastPublished == null) {
                throw unavailable();
            }
            processedSequence = frame.streamSequence();
        }

        private void publish(CanonicalTranscriptEvent transcript) {
            if (activeFrameSequence <= 0) throw unavailable();
            lastPublished = events.publish(meetingId, transcript);
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                if (modelSession != null) driver.close(modelSession, context("close"));
            }
        }
    }

    private ModelOperationContext context(String operation) {
        return ModelOperationContext.withoutCancellation("mock-" + operation,
                clock.instant().plusSeconds(30));
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("mock transcript processing unavailable");
    }
}
