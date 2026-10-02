package com.czur.reference.application.realtime;

import com.czur.reference.application.port.DurableAudioInputPort;
import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.application.port.RealtimeCredentialVerifier;
import com.czur.reference.application.port.model.MeetingAudioFrame;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.util.Objects;

/**
 * 已验证实时写会话的窄音频接入编排：v2 Start、绑定当前写者、持久帧游标。
 *
 * <p>WSS 必须先对同一 meetingId/streamId 完成当次 Token 核验，再构造
 * {@link VerifiedWriteSession}；它不是可长期缓存的授权。WSS 仍负责每条物理连接
 * 只允许一次 stream_start、每次写入前重新核验 Token/状态与到期时间、以及连接
 * 关闭和重连。本类不解码 Protobuf、不发送 ACK、不产生字幕或业务事件。</p>
 *
 * <p>仅 {@link DurableAckCursor} 表示已经由 V015 {@code Stored} 确认的正序持久
 * 音频游标，可供网络层编码 {@code durable=true} ACK。绑定成功、Start 成功、
 * 未知提交、旧 epoch 栅栏和存储不可用都不是可 ACK 事实。绑定结果未知时 WSS
 * 必须关闭该连接并以新连接 ID 重绑；追加结果未知也不得发 ACK，须关闭连接，
 * 重连后按持久游标补发未确认帧。</p>
 */
public final class RealtimeAudioIngress {
    private final RealtimeMeetingStartCoordinator start;
    private final DurableAudioInputPort audio;

    public RealtimeAudioIngress(RealtimeMeetingStartCoordinator start,
            DurableAudioInputPort audio) {
        this.start = Objects.requireNonNull(start, "start");
        this.audio = Objects.requireNonNull(audio, "audio");
    }

    /**
     * 先确认完整 v2 Start，再为本物理连接绑定新的 writer epoch。
     * 重复 stream_start 必须由网络层在调用本方法前拒绝，不可复用旧绑定。
     */
    public StartResult start(VerifiedWriteSession session,
            DurableAudioInputPort.WriterConnectionId connectionId) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(connectionId, "connectionId");
        MeetingStartExecutionUnitOfWork.Result started = start.start(session.meetingId());
        if (started instanceof MeetingStartExecutionUnitOfWork.Failure failed) {
            return new StartFailed(failed.code());
        }
        MeetingStartExecutionUnitOfWork.Success success =
                (MeetingStartExecutionUnitOfWork.Success) started;
        if (!session.meetingId().equals(success.meeting().meetingId())) {
            return new StartFailed(MeetingStartExecutionUnitOfWork.ErrorCode.FACT_INCONSISTENT);
        }
        DurableAudioInputPort.BindResult bound = audio.bindWriter(
                session.meetingId(), connectionId);
        if (bound instanceof DurableAudioInputPort.Bound value) {
            if (!session.meetingId().equals(value.binding().meetingId())
                    || !connectionId.equals(value.binding().connectionId())) {
                return new BindRejected(DurableAudioInputPort.Rejection.FACT_INCONSISTENT);
            }
            return new Ready(value.binding(), value.cursor(), success.code());
        }
        if (bound instanceof DurableAudioInputPort.Rejected rejected) {
            return new BindRejected(rejected.reason());
        }
        if (bound instanceof DurableAudioInputPort.Unavailable unavailable) {
            return new BindUnavailable(unavailable.retryable());
        }
        return new BindUnknown();
    }

    /**
     * 追加领域帧，且仅在持久端确认 {@code Stored} 和正序游标时给出可 ACK 结果。
     * 不同会议的 session/binding 组合在触达持久端之前关闭式拒绝。
     */
    public AppendResult append(VerifiedWriteSession session,
            DurableAudioInputPort.WriterBinding binding, MeetingAudioFrame frame) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(frame, "frame");
        if (!session.meetingId().equals(binding.meetingId())) {
            return new AppendRejected(DurableAudioInputPort.Rejection.INVALID_INPUT);
        }
        DurableAudioInputPort.WriteResult written = audio.append(binding, frame);
        if (written instanceof DurableAudioInputPort.Stored stored) {
            DurableAudioInputPort.Cursor cursor = stored.cursor();
            if (cursor.acceptedStreamSequence() == 0
                    || cursor.acceptedStreamSequence() < frame.streamSequence()) {
                return new AppendRejected(DurableAudioInputPort.Rejection.FACT_INCONSISTENT);
            }
            return new DurableAckCursor(cursor.acceptedStreamSequence(),
                    cursor.acceptedAudioPositionMs(), stored.replayed());
        }
        if (written instanceof DurableAudioInputPort.Rejected rejected) {
            return new AppendRejected(rejected.reason());
        }
        if (written instanceof DurableAudioInputPort.Unavailable unavailable) {
            return new AppendUnavailable(unavailable.retryable());
        }
        return new AppendUnknown();
    }

    /**
     * 仅由同会议、同逻辑流的实时凭据核验成功结果构造，不能由客户端自报。
     * 这个值不携带 Token，且不代替 WSS 每帧前的重新核验与到期检查。
     */
    public record VerifiedWriteSession(
            String meetingId, RealtimeCredentialVerifier.Granted grant) {
        public VerifiedWriteSession {
            MeetingIdPolicy.requireValid(meetingId);
            Objects.requireNonNull(grant, "grant");
            if (grant.access() != RealtimeCredentialVerifier.AccessMode.WRITE) {
                throw new IllegalArgumentException("realtime write access required");
            }
        }

        @Override public String toString() { return "VerifiedWriteSession[redacted]"; }
    }

    /** Start/绑定结果均不是音频帧持久 ACK。 */
    public sealed interface StartResult permits Ready, StartFailed, BindRejected,
            BindUnavailable, BindUnknown { }

    /** 新写者已确认绑定；保留原游标供重连补发裁决，不能直接据此发帧 ACK。 */
    public record Ready(DurableAudioInputPort.WriterBinding binding,
                        DurableAudioInputPort.Cursor cursor,
                        MeetingStartExecutionUnitOfWork.SuccessCode startCode)
            implements StartResult {
        public Ready {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(cursor, "cursor");
            Objects.requireNonNull(startCode, "startCode");
        }
    }

    public record StartFailed(MeetingStartExecutionUnitOfWork.ErrorCode code)
            implements StartResult {
        public StartFailed { Objects.requireNonNull(code, "code"); }
    }

    public record BindRejected(DurableAudioInputPort.Rejection reason)
            implements StartResult {
        public BindRejected { Objects.requireNonNull(reason, "reason"); }
    }

    public record BindUnavailable(boolean retryable) implements StartResult { }

    /** 绑定提交可能成功；当前连接必须关闭，不得用同一 ID 重绑或发 ACK。 */
    public record BindUnknown() implements StartResult { }

    /** 只有 DurableAckCursor 可以编码持久 ACK；其它结果均不允许发送 ACK。 */
    public sealed interface AppendResult permits DurableAckCursor, AppendRejected,
            AppendUnavailable, AppendUnknown { }

    public record DurableAckCursor(long acceptedStreamSequence,
                                   long acceptedAudioPositionMs, boolean replayed)
            implements AppendResult {
        public DurableAckCursor {
            if (acceptedStreamSequence <= 0 || acceptedAudioPositionMs < 0) {
                throw new IllegalArgumentException("invalid durable audio cursor");
            }
        }
    }

    /** 包括旧 epoch 的 FENCED；网络层不得把拒绝映射成旧 ACK。 */
    public record AppendRejected(DurableAudioInputPort.Rejection reason)
            implements AppendResult {
        public AppendRejected { Objects.requireNonNull(reason, "reason"); }
    }

    public record AppendUnavailable(boolean retryable) implements AppendResult { }

    /** 帧提交未知，不能从内存候选推导 ACK；WSS 关闭并重连补发。 */
    public record AppendUnknown() implements AppendResult { }
}
