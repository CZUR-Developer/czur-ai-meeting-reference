package com.czur.reference.protocol.realtime;

import com.czur.aimeeting.contract.realtime.v1.AudioFrameFlag;
import com.czur.aimeeting.contract.realtime.v1.ClientEnvelope;
import com.czur.aimeeting.contract.realtime.v1.MeetingAudioFrame;
import com.czur.aimeeting.contract.realtime.v1.StreamStart;
import com.czur.reference.application.port.RealtimeCredentialVerifier;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.application.realtime.RealtimeAudioIngress;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 纯协议映射门禁；不以构造 ACK 代替真实 V015 提交证明。 */
class RealtimeAudioWireMapperTest {
    private static final String MEETING = "mtg_wire_audio_mapper";
    private static final RealtimeWireCodec CODEC = RealtimeAudioWireMapper.trustedCodec(
            new RealtimeCredentialVerifier.AudioContract(AudioProfile.pcm16kMono(20), 640));

    @Test
    void validatedAudioFrameCopiesPcmMetadataAndOptionalChecksum() {
        byte[] pcm = new byte[640];
        Arrays.fill(pcm, (byte) 7);
        ClientEnvelope input = frame(pcm).toBuilder()
                .setAudioFrame(frame(pcm).getAudioFrame().toBuilder()
                        .setSourceGeneration(3).setFormatRevision(2)
                        .setGapDurationMsBefore(10)
                        .addFlags(AudioFrameFlag.AUDIO_FRAME_FLAG_AFTER_GAP)
                        .addFlags(AudioFrameFlag.AUDIO_FRAME_FLAG_SOURCE_CHANGED))
                .build();
        var domain = RealtimeAudioWireMapper.audioFrame(CODEC.decodeClient(input.toByteArray()));

        assertEquals(1, domain.streamSequence());
        assertEquals(0, domain.startPositionMs());
        assertEquals(20, domain.durationMs());
        assertArrayEquals(pcm, domain.audio());
        assertEquals(null, domain.checksum());
        assertEquals(3, domain.sourceGeneration());
        assertEquals(2, domain.formatRevision());
        assertEquals(10, domain.gapDurationMsBefore());
        assertEquals(Set.of(com.czur.reference.application.port.model.MeetingAudioFrame
                        .AudioFrameFlag.AFTER_GAP,
                com.czur.reference.application.port.model.MeetingAudioFrame
                        .AudioFrameFlag.SOURCE_CHANGED), domain.flags());
        byte[] exposed = domain.audio();
        exposed[0] = 99;
        assertEquals(7, domain.audio()[0]);
    }

    @Test
    void onlyDurableCursorMapsToTransientAckShape() {
        var cursor = new RealtimeAudioIngress.DurableAckCursor(1, 20, false);
        var emittedAt = Instant.parse("2030-01-01T00:00:20Z");
        var ack = RealtimeAudioWireMapper.durableAck(MEETING, cursor, emittedAt);
        var decoded = CODEC.decodeServer(CODEC.encodeServer(ack));

        assertEquals(MEETING, decoded.getMeetingId());
        assertEquals(MEETING, decoded.getStreamId());
        assertEquals("", decoded.getEventId());
        assertEquals(0, decoded.getEventSequence());
        assertEquals(emittedAt.toString(), decoded.getEmittedAt());
        assertTrue(decoded.getAudioAck().getDurable());
        assertEquals(1, decoded.getAudioAck().getAcceptedStreamSequence());
        assertEquals(20, decoded.getAudioAck().getAcceptedAudioPositionMs());
        assertEquals(1, decoded.getAudioAck().getSuggestedMaxInFlightFrames());
        assertEquals(0, decoded.getAudioAck().getRetryAfterMs());
        assertFalse(cursor.replayed());
    }

    @Test
    void wrongBodyAndUnsupportedFlagCannotBecomeApplicationAudio() {
        assertThrows(IllegalArgumentException.class,
                () -> RealtimeAudioWireMapper.audioFrame(ClientEnvelope.newBuilder()
                        .setSpecVersion(RealtimeWireCodec.SPEC_VERSION)
                        .setMeetingId(MEETING).setStreamId(MEETING)
                        .setClientMessageId("start-1")
                        .setStreamStart(StreamStart.getDefaultInstance()).build()));
        byte[] pcm = new byte[640];
        ClientEnvelope unsupported = frame(pcm).toBuilder()
                .setAudioFrame(frame(pcm).getAudioFrame().toBuilder()
                        .addFlags(AudioFrameFlag.AUDIO_FRAME_FLAG_UNKNOWN))
                .build();
        assertThrows(RealtimeWireCodec.CodecException.class,
                () -> CODEC.decodeClient(unsupported.toByteArray()));
        assertThrows(IllegalArgumentException.class,
                () -> RealtimeAudioWireMapper.audioFrame(unsupported));
    }

    private static ClientEnvelope frame(byte[] pcm) {
        return ClientEnvelope.newBuilder()
                .setSpecVersion(RealtimeWireCodec.SPEC_VERSION)
                .setMeetingId(MEETING).setStreamId(MEETING)
                .setClientMessageId("frame-1")
                .setAudioFrame(MeetingAudioFrame.newBuilder()
                        .setStreamSequence(1).setStartPositionMs(0)
                        .setDurationMs(20).setAudio(ByteString.copyFrom(pcm)))
                .build();
    }
}
