package com.czur.reference.application.wss;

import com.czur.aimeeting.contract.realtime.v1.ClientEnvelope;
import com.czur.reference.application.finalization.MeetingInputReconciler;
import com.czur.reference.application.port.DurableAudioInputPort;
import com.czur.reference.application.port.RealtimeCredentialVerifier;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.realtime.RealtimeAudioIngress;
import com.czur.reference.application.realtime.RealtimeTranscriptSessions;
import com.czur.reference.domain.meeting.MeetingIdPolicy;
import com.czur.reference.protocol.realtime.RealtimeAudioWireMapper;
import com.czur.reference.protocol.realtime.RealtimeWireCodec;
import com.czur.reference.protocol.realtime.RealtimeTranscriptEventMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 一条物理 WSS 连接的传输适配器；不持有数据库或业务状态的第二份事实源。
 *
 * <p>握手取得并持有一份短生命周期 session 凭据，每条完整二进制消息前都向
 * {@link RealtimeCredentialVerifier} 重验会议、逻辑流、期限及当前状态。仅可信
 * V013 音频合同可建立 codec；解码后再将 envelope 的会议/流与握手路径核对。
 * 同连接只尝试一次 stream_start，Start/绑定交给 {@link RealtimeAudioIngress}。
 * 只有其 {@link RealtimeAudioIngress.DurableAckCursor} 可以产生 ACK；未知提交、
 * 旧 epoch、拒绝及存储故障都关闭连接，不回退到内存游标。</p>
 *
 * <p>本切片支持 stream_start、audio_frame 和 input_complete；Stop 仍走认证
 * REST，事件补拉留给后续切片。Mock 字幕由应用层从 V015 帧恢复并
 * 持久发布；本层只把已存事件编码成 ACK 之后的第二条消息。关闭连接必须调用
 * {@link #close()} 销毁凭据与本连接 Mock 会话；
 * 对象按连接串行处理，同步方法也防止库回调并发时重复绑定。</p>
 */
public final class RealtimeWssConnection implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(RealtimeWssConnection.class.getName());
    private final String meetingId;
    private final SecretCredential credential;
    private final RealtimeCredentialVerifier verifier;
    private final RealtimeAudioIngress ingress;
    private final RealtimeTranscriptSessions transcriptSessions;
    private final MeetingInputReconciler inputReconciler;
    private final Clock clock;
    private final Instant initialExpiresAt;
    private final RealtimeCredentialVerifier.AudioContract audioContract;
    private final RealtimeWireCodec codec;
    private final DurableAudioInputPort.WriterConnectionId connectionId;
    private DurableAudioInputPort.WriterBinding binding;
    private RealtimeTranscriptSessions.Session transcriptSession;
    private boolean startAttempted;
    private boolean closed;

    private RealtimeWssConnection(String meetingId, SecretCredential credential,
            RealtimeCredentialVerifier verifier, RealtimeAudioIngress ingress,
            RealtimeTranscriptSessions transcriptSessions,
            MeetingInputReconciler inputReconciler, Clock clock,
            RealtimeCredentialVerifier.AudioContract audioContract, Instant initialExpiresAt) {
        this.meetingId = meetingId;
        this.credential = credential;
        this.verifier = verifier;
        this.ingress = ingress;
        this.transcriptSessions = transcriptSessions;
        this.inputReconciler = inputReconciler;
        this.clock = clock;
        this.initialExpiresAt = Objects.requireNonNull(initialExpiresAt, "initialExpiresAt");
        this.audioContract = audioContract;
        this.codec = RealtimeAudioWireMapper.trustedCodec(audioContract);
        this.connectionId = DurableAudioInputPort.WriterConnectionId.newConnection();
    }

    /**
     * 握手时核验一次持久凭据，并接管 credential 生命周期。任意拒绝或异常均销毁
     * 凭据且抛出不含会议、Token 或数据库正文的固定异常；调用方不得再次使用它。
     */
    public static RealtimeWssConnection open(String meetingId, SecretCredential credential,
            RealtimeCredentialVerifier verifier, RealtimeAudioIngress ingress, Clock clock) {
        return open(meetingId, credential, verifier, ingress, null, clock);
    }

    /** 正式装配传入 Mock 运行时；旧定向传输测试可只验证音频 ACK。 */
    public static RealtimeWssConnection open(String meetingId, SecretCredential credential,
            RealtimeCredentialVerifier verifier, RealtimeAudioIngress ingress,
            RealtimeTranscriptSessions transcriptSessions, Clock clock) {
        return open(meetingId, credential, verifier, ingress, transcriptSessions, null, clock);
    }

    /** 正式装配复用 REST Stop 的同一收口编排器；旧传输定向测试可省略。 */
    public static RealtimeWssConnection open(String meetingId, SecretCredential credential,
            RealtimeCredentialVerifier verifier, RealtimeAudioIngress ingress,
            RealtimeTranscriptSessions transcriptSessions,
            MeetingInputReconciler inputReconciler, Clock clock) {
        Objects.requireNonNull(credential, "credential");
        try {
            MeetingIdPolicy.requireValid(meetingId);
            Objects.requireNonNull(verifier, "verifier");
            Objects.requireNonNull(ingress, "ingress");
            Objects.requireNonNull(clock, "clock");
            var result = verifier.verify(new RealtimeCredentialVerifier.Request(
                    meetingId, meetingId, credential));
            if (!(result instanceof RealtimeCredentialVerifier.Granted granted)
                    || !clock.instant().isBefore(granted.expiresAt())) {
                throw new SessionRejected();
            }
            return new RealtimeWssConnection(meetingId, credential, verifier, ingress,
                    transcriptSessions, inputReconciler, clock,
                    granted.audioContract(), granted.expiresAt());
        } catch (RuntimeException failure) {
            credential.close();
            throw new SessionRejected();
        }
    }

    /**
     * 处理一条已经完成 WebSocket 聚合的二进制消息。调用方须先按
     * {@link RealtimeWireCodec#MAX_ENVELOPE_BYTES} 限制聚合消息大小。每次均重新
     * 验证，再按当前可信合同解码；拒绝时返回固定 close code，不返回旧 ACK。
     */
    public synchronized Result receive(byte[] wire) {
        if (closed) return new Terminate(1008);
        try {
            RealtimeCredentialVerifier.Granted grant = currentGrant();
            if (grant == null) return terminate(1008);
            ClientEnvelope envelope = codec.decodeClient(wire);
            if (!meetingId.equals(envelope.getMeetingId())
                    || !meetingId.equals(envelope.getStreamId())) {
                return terminate(1008);
            }
            if (grant.access() != RealtimeCredentialVerifier.AccessMode.WRITE) {
                return terminate(1008);
            }
            return switch (envelope.getBodyCase()) {
                case STREAM_START -> begin(grant);
                case AUDIO_FRAME -> append(grant, envelope);
                case INPUT_COMPLETE -> complete(grant, envelope);
                default -> terminate(1003);
            };
        } catch (IllegalArgumentException invalid) {
            return terminate(1007);
        } catch (RuntimeException unavailable) {
            return terminate(1011);
        }
    }

    private RealtimeCredentialVerifier.Granted currentGrant() {
        var result = verifier.verify(new RealtimeCredentialVerifier.Request(
                meetingId, meetingId, credential));
        if (!(result instanceof RealtimeCredentialVerifier.Granted grant)
                || !clock.instant().isBefore(grant.expiresAt())
                || !audioContract.equals(grant.audioContract())) {
            return null;
        }
        return grant;
    }

    private Result begin(RealtimeCredentialVerifier.Granted grant) {
        if (startAttempted) return terminate(1008);
        startAttempted = true;
        if (!clock.instant().isBefore(grant.expiresAt())) return terminate(1008);
        var result = ingress.start(
                new RealtimeAudioIngress.VerifiedWriteSession(meetingId, grant), connectionId);
        if (result instanceof RealtimeAudioIngress.Ready ready) {
            binding = ready.binding();
            if (transcriptSessions != null) transcriptSession = transcriptSessions.open(meetingId);
            return new NoReply();
        }
        return terminate(result instanceof RealtimeAudioIngress.BindRejected
                || result instanceof RealtimeAudioIngress.StartFailed ? 1008 : 1011);
    }

    private Result append(RealtimeCredentialVerifier.Granted grant, ClientEnvelope envelope) {
        if (binding == null) return terminate(1008);
        if (!clock.instant().isBefore(grant.expiresAt())) return terminate(1008);
        var frame = RealtimeAudioWireMapper.audioFrame(envelope);
        var result = ingress.append(
                new RealtimeAudioIngress.VerifiedWriteSession(meetingId, grant), binding, frame);
        if (result instanceof RealtimeAudioIngress.DurableAckCursor cursor) {
            var transcript = transcriptSession == null ? null : transcriptSession.acceptStored(frame);
            // 只有末帧和字幕持久发布均成功才进入收口；重复帧的事件已在数据库中，
            // acceptStored 返回 null 仍可幂等重试。普通帧不扫描整场音频。
            if (cursor.atCompletionEndpoint() && transcriptSession != null) {
                triggerInputClosure();
            }
            Instant emittedAt = Objects.requireNonNull(clock.instant(), "clock.instant()");
            byte[] ack = codec.encodeServer(
                    RealtimeAudioWireMapper.durableAck(meetingId, cursor, emittedAt));
            if (transcript == null) return new SendBinary(ack);
            byte[] event = codec.encodeServer(RealtimeTranscriptEventMapper.fromStored(transcript));
            return new SendPair(ack, event);
        }
        return terminate(result instanceof RealtimeAudioIngress.AppendRejected ? 1008 : 1011);
    }

    private Result complete(RealtimeCredentialVerifier.Granted grant, ClientEnvelope envelope) {
        if (binding == null) return terminate(1008);
        if (!clock.instant().isBefore(grant.expiresAt())) return terminate(1008);
        var declaration = RealtimeAudioWireMapper.inputCompletion(envelope);
        DurableAudioInputPort.WriteResult result = ingress.complete(
                new RealtimeAudioIngress.VerifiedWriteSession(meetingId, grant),
                binding, declaration);
        if (result instanceof DurableAudioInputPort.Stored) {
            // 声明可先于末帧；本次触发可能返回待音频，不能据此发送 ACK/receipt。
            triggerInputClosure();
            return new NoReply();
        }
        return terminate(result instanceof DurableAudioInputPort.Rejected ? 1008 : 1011);
    }

    private void triggerInputClosure() {
        if (inputReconciler == null) return;
        MeetingInputReconciler.Result result = inputReconciler.reconcile(meetingId);
        if (result instanceof MeetingInputReconciler.Failure failure) {
            LOGGER.log(Level.WARNING, "meeting WSS 收口未完成 reason={0}", failure.reason());
        } else if (result instanceof MeetingInputReconciler.Pending pending) {
            LOGGER.log(Level.FINE, "meeting WSS 收口待事实 reason={0}", pending.reason());
        } else {
            LOGGER.log(Level.FINE, "meeting WSS 收口已接管");
        }
    }

    private Terminate terminate(int code) {
        close();
        return new Terminate(code);
    }

    /** 空闲连接也以握手时的到期时间为硬上限；先销毁凭据，再由监听器关闭 socket。 */
    public synchronized boolean closeIfInitiallyExpired() {
        if (closed || clock.instant().isBefore(initialExpiresAt)) return false;
        close();
        return true;
    }

    /** 幂等销毁所持 session 凭据；不关闭共享端口、数据库或监听器。 */
    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            try {
                if (transcriptSession != null) transcriptSession.close();
            } finally {
                credential.close();
            }
        }
    }

    /** 握手拒绝的固定异常；网络层统一映射，绝不附带原始请求或数据库正文。 */
    public static final class SessionRejected extends RuntimeException {
        private SessionRejected() { super("realtime session rejected"); }
    }

    /** 一条入站消息仅有三种网络结果：静默成功、持久 ACK 或关闭。 */
    public sealed interface Result permits NoReply, SendBinary, SendPair, Terminate { }

    public record NoReply() implements Result { }

    public record SendBinary(byte[] bytes) implements Result {
        public SendBinary { bytes = Arrays.copyOf(bytes, bytes.length); }
        @Override public byte[] bytes() { return Arrays.copyOf(bytes, bytes.length); }
    }

    /** 同一入站帧至多两条固定顺序输出：持久 ACK，然后已落库字幕。 */
    public record SendPair(byte[] first, byte[] second) implements Result {
        public SendPair {
            first = Arrays.copyOf(first, first.length);
            second = Arrays.copyOf(second, second.length);
        }
        @Override public byte[] first() { return Arrays.copyOf(first, first.length); }
        @Override public byte[] second() { return Arrays.copyOf(second, second.length); }
    }

    /** 使用 WebSocket 标准 close code；不带可能泄露事实的原因正文。 */
    public record Terminate(int code) implements Result { }
}
