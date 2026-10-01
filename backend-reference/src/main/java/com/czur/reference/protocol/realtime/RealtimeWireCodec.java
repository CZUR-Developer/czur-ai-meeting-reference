package com.czur.reference.protocol.realtime;

import com.czur.aimeeting.contract.realtime.v1.AudioDiscontinuity;
import com.czur.aimeeting.contract.realtime.v1.AudioDiscontinuityReason;
import com.czur.aimeeting.contract.realtime.v1.AudioEncoding;
import com.czur.aimeeting.contract.realtime.v1.AudioFrameFlag;
import com.czur.aimeeting.contract.realtime.v1.ClientEnvelope;
import com.czur.aimeeting.contract.realtime.v1.InputComplete;
import com.czur.aimeeting.contract.realtime.v1.MeetingAudioFrame;
import com.czur.aimeeting.contract.realtime.v1.ServerEnvelope;
import com.czur.aimeeting.contract.realtime.v1.StreamStart;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 实时 WebSocket 的单消息 Protobuf 字节边界，不持有会议状态或连接。
 *
 * <p>调用方必须先从已确认的 Create 事实取得本场 {@link AudioContract}，并在调用本类
 * 之前执行连接鉴权和 WebSocket 聚合帧大小限制。本类只接受一个完整二进制消息；
 * 解析前用独立 64 KiB 上限保护堆内存，解析后校验当前 v0.1 的业务字段。它不决定
 * 音频是否持久化，不签发 ACK，也不调用应用/Driver 端口。生成的 Protobuf 类型仅在
 * 此协议/传输边界出现；接入业务服务时须由传输适配器转换为应用命令。</p>
 *
 * <p>已知 oneof 同时出现两次会在 Protobuf 解析前被拒绝；普通未知可选 wire 字段
 * 按 Protobuf 规则保留。wire 格式无法判断一个未知 tag 将来属于 oneof 还是普通
 * 可选字段，因此未来新增 body 必须通过版本协商，不能依赖本类自动识别。</p>
 *
 * <p>本类无可变状态，可跨连接复用；异常和 DEBUG 级日志只携带稳定错误码，绝不
 * 附带 Token、音频字节、正文或客户端字符串。生产入口应统一记录经过脱敏的拒绝
 * 统计，不逐帧记录成功路径。</p>
 */
public final class RealtimeWireCodec {
    public static final int MAX_ENVELOPE_BYTES = 64 * 1024;
    public static final String SPEC_VERSION = "0.1";
    private static final Logger LOGGER = Logger.getLogger(RealtimeWireCodec.class.getName());
    private static final int PCM_S16LE_BYTES_PER_MS = 32; // 16000 Hz * mono * 2 bytes / 1000

    private final AudioContract audioContract;

    /**
     * @param audioContract 从本场已确认 Create 响应取得的音频 Profile 与帧字节上限；
     *                      不能根据客户端 stream_start 自行建立信任。
     */
    public RealtimeWireCodec(AudioContract audioContract) {
        this.audioContract = Objects.requireNonNull(audioContract, "audioContract");
    }

    /**
     * 解码一条客户端消息；返回值仅供协议/传输适配器使用，尚未获会议写权限。
     *
     * @param wire 一个完整的 WebSocket 二进制消息；不接受 null、空消息或超长消息
     * @return 已通过当前协议字段与音频字节校验的生成对象
     * @throws CodecException 字节损坏、版本/正文/关键字段不支持或音频不符合可信 Profile
     */
    public ClientEnvelope decodeClient(byte[] wire) {
        requireWireSize(wire);
        rejectRepeatedKnownBody(wire, true);
        final ClientEnvelope envelope;
        try {
            envelope = ClientEnvelope.parseFrom(wire);
        } catch (InvalidProtocolBufferException failure) {
            throw reject(Code.MALFORMED_PROTO);
        }
        requireClient(envelope);
        return envelope;
    }

    /**
     * 编码一条服务端消息；持久业务事件必须有会议全局正序号和 eventId，瞬时
     * ACK/心跳必须使用零序号且不得有 eventId。此方法不产生或持久化事件事实。
     *
     * @throws CodecException 字段不完整或编码后超出独立消息上限
     */
    public byte[] encodeServer(ServerEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        requireServer(envelope);
        byte[] wire = envelope.toByteArray();
        requireWireSize(wire);
        return wire;
    }

    /**
     * 解析服务端消息以校验当前 v0.1 固定 Fixture；参考后端正常入站不调用它。
     * 与 {@link #decodeClient(byte[])} 一样，未知普通可选字段由生成对象原样保留。
     */
    public ServerEnvelope decodeServer(byte[] wire) {
        requireWireSize(wire);
        rejectRepeatedKnownBody(wire, false);
        final ServerEnvelope envelope;
        try {
            envelope = ServerEnvelope.parseFrom(wire);
        } catch (InvalidProtocolBufferException failure) {
            throw reject(Code.MALFORMED_PROTO);
        }
        requireServer(envelope);
        return envelope;
    }

    private void requireClient(ClientEnvelope envelope) {
        requireVersion(envelope.getSpecVersion());
        requireText(envelope.getMeetingId());
        requireText(envelope.getStreamId());
        requireText(envelope.getClientMessageId());
        if (!envelope.getMeetingId().equals(envelope.getStreamId())) {
            throw reject(Code.INVALID_ENVELOPE); // P2 ASR-only 单逻辑流公开规则
        }
        switch (envelope.getBodyCase()) {
            case STREAM_START -> requireStreamStart(envelope.getStreamStart());
            case AUDIO_FRAME -> requireAudioFrame(envelope.getAudioFrame());
            case INPUT_COMPLETE -> requireInputComplete(envelope.getInputComplete());
            case RESUME_REQUEST -> requireNonNegative(envelope.getResumeRequest()
                    .getAfterEventSequence());
            case HEARTBEAT -> {
                requireText(envelope.getHeartbeat().getNonce());
                requireInstant(envelope.getHeartbeat().getSentAt());
            }
            case AUDIO_DISCONTINUITY -> requireDiscontinuity(
                    envelope.getAudioDiscontinuity());
            case CANCEL -> throw reject(Code.UNSUPPORTED_BODY); // P2 取消只走用户 Bearer REST
            case BODY_NOT_SET -> throw reject(Code.INVALID_BODY);
        }
    }

    private void requireStreamStart(StreamStart start) {
        if (!start.hasAudioProfile()) {
            throw reject(Code.INVALID_FIELD); // proto3 message presence 可检查
        }
        var profile = start.getAudioProfile();
        if (profile.getEncoding() != AudioEncoding.AUDIO_ENCODING_PCM_S16LE
                || profile.getSampleRateHz() != audioContract.sampleRateHz()
                || profile.getChannelCount() != audioContract.channelCount()
                || profile.getFrameDurationMs() != audioContract.frameDurationMs()) {
            throw reject(Code.AUDIO_PROFILE_MISMATCH);
        }
        requireNonNegative(start.getResumeAfterStreamSequence());
        requireNonNegative(start.getResumeAfterEventSequence());
    }

    private void requireAudioFrame(MeetingAudioFrame frame) {
        requirePositive(frame.getStreamSequence());
        requireNonNegative(frame.getStartPositionMs());
        requireNonNegative(frame.getSourceGeneration());
        requireNonNegative(frame.getGapDurationMsBefore());
        if (frame.getFormatRevision() < 0) { // uint32 超过 Java int 正数域会变负
            throw reject(Code.INVALID_FIELD);
        }
        for (AudioFrameFlag flag : frame.getFlagsList()) {
            if (flag == AudioFrameFlag.AUDIO_FRAME_FLAG_UNKNOWN
                    || flag == AudioFrameFlag.UNRECOGNIZED) {
                throw reject(Code.INVALID_FIELD);
            }
        }
        int duration = frame.getDurationMs();
        if (duration <= 0 || duration > audioContract.frameDurationMs()) {
            throw reject(Code.AUDIO_FRAME_INVALID);
        }
        long expectedBytes = (long) duration * PCM_S16LE_BYTES_PER_MS;
        int actualBytes = frame.getAudio().size();
        if (actualBytes != expectedBytes || actualBytes > audioContract.maxAudioFrameBytes()) {
            throw reject(Code.AUDIO_FRAME_INVALID);
        }
        if (!frame.getChecksum().isEmpty()) {
            requireDigest(frame.getChecksum());
            byte[] actual = sha256(frame.getAudio().toByteArray());
            if (!MessageDigest.isEqual(actual, HexFormat.of().parseHex(frame.getChecksum()))) {
                throw reject(Code.CHECKSUM_MISMATCH);
            }
        }
    }

    private static void requireInputComplete(InputComplete complete) {
        requirePositive(complete.getLastStreamSequence());
        requireNonNegative(complete.getLastAudioPositionMs());
        requireText(complete.getRequestId());
        requireNonNegative(complete.getRecordingSizeBytes());
        if (complete.getRecordingChecksum().isEmpty()) {
            if (complete.getRecordingSizeBytes() != 0) {
                throw reject(Code.INVALID_FIELD);
            }
        } else {
            requireDigest(complete.getRecordingChecksum());
            if (complete.getRecordingSizeBytes() == 0) {
                throw reject(Code.INVALID_FIELD);
            }
        }
        // 录音是否真正上传/持久接管由 T16-08 核验，声明本身不能产生 receipt。
    }

    private static void requireDiscontinuity(AudioDiscontinuity discontinuity) {
        requireNonNegative(discontinuity.getAfterStreamSequence());
        requireNonNegative(discontinuity.getResumeAudioPositionMs());
        requireNonNegative(discontinuity.getSourceGeneration());
        requireNonNegative(discontinuity.getGapDurationMs());
        if (discontinuity.getFormatRevision() < 0
                || discontinuity.getReasonValue() <= 0
                || discontinuity.getReason() == AudioDiscontinuityReason.UNRECOGNIZED) {
            throw reject(Code.INVALID_FIELD);
        }
    }

    private static void requireServer(ServerEnvelope envelope) {
        requireVersion(envelope.getSpecVersion());
        requireText(envelope.getMeetingId());
        requireText(envelope.getStreamId());
        requireInstant(envelope.getEmittedAt());
        if (!envelope.getMeetingId().equals(envelope.getStreamId())) {
            throw reject(Code.INVALID_ENVELOPE); // P2 参考单流
        }
        boolean transientBody = switch (envelope.getBodyCase()) {
            case AUDIO_ACK, HEARTBEAT -> true;
            case BODY_NOT_SET -> throw reject(Code.INVALID_BODY);
            default -> false;
        };
        if (transientBody) {
            if (!envelope.getEventId().isEmpty() || envelope.getEventSequence() != 0) {
                throw reject(Code.INVALID_ENVELOPE);
            }
            if (envelope.getBodyCase() == ServerEnvelope.BodyCase.AUDIO_ACK) {
                var ack = envelope.getAudioAck();
                requirePositive(ack.getAcceptedStreamSequence());
                requireNonNegative(ack.getAcceptedAudioPositionMs());
                if (!ack.getDurable() || ack.getSuggestedMaxInFlightFrames() < 0
                        || ack.getRetryAfterMs() < 0) {
                    throw reject(Code.INVALID_FIELD);
                }
            } else {
                requireText(envelope.getHeartbeat().getNonce());
                requireInstant(envelope.getHeartbeat().getSentAt());
            }
        } else {
            requireText(envelope.getEventId());
            if (envelope.getEventSequence() <= 0) {
                throw reject(Code.INVALID_ENVELOPE);
            }
            requireDurableBody(envelope);
        }
    }

    /** 当前固定事件 oneof 的最小字段完整性；事件事实与游标的事务规则留 T16-05/07。 */
    private static void requireDurableBody(ServerEnvelope envelope) {
        switch (envelope.getBodyCase()) {
            case SESSION_STATE -> {
                if (envelope.getSessionState().getStateValue() <= 0
                        || isUnrecognized(envelope.getSessionState().getState())) {
                    throw reject(Code.INVALID_FIELD);
                }
            }
            case TRANSCRIPT_UPSERT -> {
                var body = envelope.getTranscriptUpsert();
                requireText(body.getSegmentId());
                requirePositive(body.getRevision());
                requireText(body.getLanguage());
                requireNonNegative(body.getStartMs());
                requireNonNegative(body.getEndMs());
                if (body.getOperationValue() <= 0 || isUnrecognized(body.getOperation())
                        || body.getEndMs() < body.getStartMs()) {
                    throw reject(Code.INVALID_FIELD);
                }
            }
            case TRANSLATION_UPSERT -> {
                var body = envelope.getTranslationUpsert();
                requireText(body.getTranslationId());
                requireText(body.getSourceSegmentId());
                requireText(body.getSourceLanguage());
                requireText(body.getTargetLanguage());
                requirePositive(body.getRevision());
                if (body.getOperationValue() <= 0 || isUnrecognized(body.getOperation())) {
                    throw reject(Code.INVALID_FIELD);
                }
            }
            case SPEAKER_UPDATE -> {
                var body = envelope.getSpeakerUpdate();
                requireText(body.getSessionSpeakerId());
                requirePositive(body.getRevision());
                if (body.getKindValue() <= 0 || isUnrecognized(body.getKind())) {
                    throw reject(Code.INVALID_FIELD);
                }
            }
            case CAPABILITY_CHANGED -> {
                var body = envelope.getCapabilityChanged();
                requireText(body.getCapability());
                if (body.getAvailabilityValue() <= 0
                        || isUnrecognized(body.getAvailability())) {
                    throw reject(Code.INVALID_FIELD);
                }
            }
            case FINALIZATION -> {
                var body = envelope.getFinalization();
                if (body.getStageValue() <= 0 || isUnrecognized(body.getStage())
                        || body.getInputStateValue() <= 0
                        || isUnrecognized(body.getInputState())
                        || body.getProgressPercent() < 0 || body.getProgressPercent() > 100) {
                    throw reject(Code.INVALID_FIELD);
                }
            }
            case ERROR -> {
                var body = envelope.getError();
                requireText(body.getCode());
                requireText(body.getMessageKey());
                if (body.getDomainValue() <= 0 || isUnrecognized(body.getDomain())
                        || body.getSeverityValue() <= 0 || isUnrecognized(body.getSeverity())
                        || body.getUserActionValue() <= 0
                        || isUnrecognized(body.getUserAction())) {
                    throw reject(Code.INVALID_FIELD);
                }
            }
            case AUDIO_CONTINUITY -> {
                var body = envelope.getAudioContinuity();
                requireNonNegative(body.getAfterStreamSequence());
                requireNonNegative(body.getSourceGeneration());
                requireNonNegative(body.getGapDurationMs());
                if (body.getReasonValue() <= 0 || isUnrecognized(body.getReason())
                        || body.getActionValue() <= 0
                        || isUnrecognized(body.getAction())) {
                    throw reject(Code.INVALID_FIELD);
                }
            }
            default -> throw reject(Code.INVALID_BODY);
        }
    }

    /** 仅使用 Protobuf 自带的 tag reader 检查已知 oneof 重复，不另写二进制解析器。 */
    private static void rejectRepeatedKnownBody(byte[] wire, boolean client) {
        CodedInputStream input = CodedInputStream.newInstance(wire);
        int bodyCount = 0;
        try {
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                int field = WireFormat.getTagFieldNumber(tag);
                boolean knownBody = client ? field >= 10 && field <= 16
                        : field >= 10 && field <= 19;
                if (knownBody && ++bodyCount > 1) {
                    throw reject(Code.INVALID_BODY);
                }
                if (!input.skipField(tag)) {
                    throw reject(Code.MALFORMED_PROTO);
                }
            }
        } catch (IOException failure) {
            throw reject(Code.MALFORMED_PROTO);
        }
    }

    private static void requireWireSize(byte[] wire) {
        if (wire == null || wire.length == 0) {
            throw reject(Code.EMPTY_MESSAGE);
        }
        if (wire.length > MAX_ENVELOPE_BYTES) {
            throw reject(Code.MESSAGE_TOO_LARGE);
        }
    }

    private static void requireVersion(String value) {
        if (!SPEC_VERSION.equals(value)) {
            throw reject(Code.UNSUPPORTED_VERSION);
        }
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw reject(Code.INVALID_FIELD);
        }
    }

    private static void requireInstant(String value) {
        requireText(value);
        try {
            Instant.parse(value);
        } catch (DateTimeParseException failure) {
            throw reject(Code.INVALID_FIELD);
        }
    }

    private static void requirePositive(long value) {
        if (value <= 0) {
            throw reject(Code.INVALID_FIELD);
        }
    }

    private static void requireNonNegative(long value) {
        if (value < 0) { // Protobuf uint64 在 Java long 中溢出时为负
            throw reject(Code.INVALID_FIELD);
        }
    }

    private static void requireDigest(String value) {
        if (!value.matches("[0-9a-f]{64}")) {
            throw reject(Code.INVALID_FIELD);
        }
    }

    private static boolean isUnrecognized(Enum<?> value) {
        return "UNRECOGNIZED".equals(value.name());
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static CodecException reject(Code code) {
        LOGGER.log(Level.FINE, "实时协议消息拒绝 code={0}", code);
        return new CodecException(code);
    }

    /**
     * 可信 Create 结果中的 PCM S16LE 音频参数，不是客户端可写的协商请求。
     * 目前公开 v0.1 只支持 16 kHz、单声道和名义 20～200 ms 帧；实际末帧可更短。
     * 对象不可变，可安全跨线程复用。会议最大帧字节数由调用方显式传入，不在此硬编码。
     */
    public record AudioContract(int sampleRateHz, int channelCount,
                                int frameDurationMs, int maxAudioFrameBytes) {
        public AudioContract {
            if (sampleRateHz != 16_000 || channelCount != 1
                    || frameDurationMs < 20 || frameDurationMs > 200
                    || maxAudioFrameBytes <= 0) {
                throw new IllegalArgumentException("invalid trusted audio contract");
            }
        }
    }

    /** 仅协议字节边界使用的稳定错误分类；不携带原始输入。 */
    public enum Code {
        EMPTY_MESSAGE,
        MESSAGE_TOO_LARGE,
        MALFORMED_PROTO,
        UNSUPPORTED_VERSION,
        INVALID_ENVELOPE,
        INVALID_BODY,
        UNSUPPORTED_BODY,
        INVALID_FIELD,
        AUDIO_PROFILE_MISMATCH,
        AUDIO_FRAME_INVALID,
        CHECKSUM_MISMATCH
    }

    /**
     * 可向网络边界映射的拒绝原因。异常文本只有 {@link Code}，不透传解析异常或
     * 原始 payload；调用方不应把它解释为会议业务或权限结果。
     */
    public static final class CodecException extends IllegalArgumentException {
        private final Code code;

        private CodecException(Code code) {
            super(code.name());
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }
}
