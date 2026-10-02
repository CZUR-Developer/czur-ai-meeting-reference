package com.czur.reference.protocol.realtime;

import com.czur.aimeeting.contract.realtime.v1.AudioAck;
import com.czur.aimeeting.contract.realtime.v1.AudioFrameFlag;
import com.czur.aimeeting.contract.realtime.v1.ClientEnvelope;
import com.czur.aimeeting.contract.realtime.v1.ServerEnvelope;
import com.czur.reference.application.port.RealtimeCredentialVerifier;
import com.czur.reference.application.port.model.MeetingAudioFrame;
import com.czur.reference.application.realtime.RealtimeAudioIngress;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;

/**
 * 实时音频的传输边界映射，不核验凭据、不保存帧，也不决定是否可发送 ACK。
 *
 * <p>客户端 envelope 必须先经过本场可信音频合同创建的
 * {@link RealtimeWireCodec#decodeClient(byte[])}；本类仅把已校验的音频字段复制到
 * 应用值对象。服务端 ACK 只能从 {@link RealtimeAudioIngress.DurableAckCursor}
 * 构造，且是零事件序号、无事件 ID 的瞬时消息。调用方再用同一个 codec 编码，
 * 并在每条输入前重新完成凭据与状态核验。此类不持有连接或音频字节。</p>
 */
public final class RealtimeAudioWireMapper {
    private RealtimeAudioWireMapper() { }

    /** 从持久 Create 授权中的可信合同创建协议校验器，客户端 Profile 不参与选择。 */
    public static RealtimeWireCodec trustedCodec(
            RealtimeCredentialVerifier.AudioContract trusted) {
        Objects.requireNonNull(trusted, "trusted");
        var profile = trusted.audioProfile();
        return new RealtimeWireCodec(new RealtimeWireCodec.AudioContract(
                profile.sampleRateHz(), profile.channelCount(), profile.frameDurationMs(),
                trusted.maxAudioFrameBytes()));
    }

    /**
     * 将已经过 codec 校验的 AUDIO_FRAME 转成防御性复制的应用帧；其它 body 和
     * 不支持的标记关闭式拒绝。空的可选 checksum 映射为应用层的 null。
     */
    public static MeetingAudioFrame audioFrame(ClientEnvelope validated) {
        Objects.requireNonNull(validated, "validated");
        if (validated.getBodyCase() != ClientEnvelope.BodyCase.AUDIO_FRAME) {
            throw new IllegalArgumentException("audio frame body required");
        }
        var source = validated.getAudioFrame();
        EnumSet<MeetingAudioFrame.AudioFrameFlag> flags =
                EnumSet.noneOf(MeetingAudioFrame.AudioFrameFlag.class);
        for (AudioFrameFlag flag : source.getFlagsList()) {
            flags.add(switch (flag) {
                case AUDIO_FRAME_FLAG_AFTER_GAP -> MeetingAudioFrame.AudioFrameFlag.AFTER_GAP;
                case AUDIO_FRAME_FLAG_SOURCE_CHANGED ->
                        MeetingAudioFrame.AudioFrameFlag.SOURCE_CHANGED;
                case AUDIO_FRAME_FLAG_PLATFORM_RESTARTED ->
                        MeetingAudioFrame.AudioFrameFlag.PLATFORM_RESTARTED;
                case AUDIO_FRAME_FLAG_UNKNOWN, UNRECOGNIZED ->
                        throw new IllegalArgumentException("unsupported audio frame flag");
            });
        }
        return new MeetingAudioFrame(source.getStreamSequence(), source.getStartPositionMs(),
                source.getDurationMs(), source.getAudio().toByteArray(),
                source.getChecksum().isEmpty() ? null : source.getChecksum(),
                source.getSourceGeneration(), source.getFormatRevision(),
                source.getGapDurationMsBefore(), flags);
    }

    /**
     * 将 V015 已确认的最高连续帧游标编码为瞬时持久 ACK；这里不调用 Store，
     * 也不把 ACK 写入会议事件日志。单帧小步仅建议一个在途帧，不施加重试等待。
     */
    public static ServerEnvelope durableAck(String meetingId,
            RealtimeAudioIngress.DurableAckCursor cursor, Instant emittedAt) {
        if (meetingId == null || meetingId.isBlank()) {
            throw new IllegalArgumentException("meetingId is required");
        }
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(emittedAt, "emittedAt");
        return ServerEnvelope.newBuilder()
                .setSpecVersion(RealtimeWireCodec.SPEC_VERSION)
                .setMeetingId(meetingId).setStreamId(meetingId)
                .setEmittedAt(emittedAt.toString())
                .setAudioAck(AudioAck.newBuilder()
                        .setAcceptedStreamSequence(cursor.acceptedStreamSequence())
                        .setAcceptedAudioPositionMs(cursor.acceptedAudioPositionMs())
                        .setSuggestedMaxInFlightFrames(1)
                        .setRetryAfterMs(0).setDurable(true))
                .build();
    }
}
