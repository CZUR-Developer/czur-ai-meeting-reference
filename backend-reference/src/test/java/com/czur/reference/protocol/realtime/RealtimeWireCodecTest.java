package com.czur.reference.protocol.realtime;

import com.czur.aimeeting.contract.realtime.v1.AudioEncoding;
import com.czur.aimeeting.contract.realtime.v1.AudioDiscontinuity;
import com.czur.aimeeting.contract.realtime.v1.AudioDiscontinuityReason;
import com.czur.aimeeting.contract.realtime.v1.AudioProfile;
import com.czur.aimeeting.contract.realtime.v1.Cancel;
import com.czur.aimeeting.contract.realtime.v1.ClientEnvelope;
import com.czur.aimeeting.contract.realtime.v1.Heartbeat;
import com.czur.aimeeting.contract.realtime.v1.InputComplete;
import com.czur.aimeeting.contract.realtime.v1.MeetingAudioFrame;
import com.czur.aimeeting.contract.realtime.v1.MeetingState;
import com.czur.aimeeting.contract.realtime.v1.ResumeRequest;
import com.czur.aimeeting.contract.realtime.v1.ServerEnvelope;
import com.czur.aimeeting.contract.realtime.v1.SessionStateEvent;
import com.czur.aimeeting.contract.realtime.v1.StreamStart;
import com.czur.aimeeting.contract.realtime.v1.TranscriptUpsert;
import com.czur.reference.protocol.realtime.RealtimeWireCodec.AudioContract;
import com.czur.reference.protocol.realtime.RealtimeWireCodec.Code;
import com.czur.reference.protocol.realtime.RealtimeWireCodec.CodecException;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T16-02B 只验证协议字节边界；不以此代替鉴权、持久 ACK 或 WSS 集成门禁。 */
class RealtimeWireCodecTest {
    private static final AudioContract PROFILE = new AudioContract(16_000, 1, 20, 640);
    private static final RealtimeWireCodec CODEC = new RealtimeWireCodec(PROFILE);
    private static final String PCM20_SHA =
            "9e132485d5107211de325a45e7917cbe3e4b5b9cde3e4ee91d7d2102317759ee";

    @Test
    void fixedCompletePcmFixtureRoundTripsAsRealProtobuf() throws Exception {
        ClientEnvelope source = clientFixture("audio-frame-pcm20.valid.json");
        byte[] binary = source.toByteArray();
        ClientEnvelope decoded = CODEC.decodeClient(binary);

        assertEquals(640, decoded.getAudioFrame().getAudio().size());
        assertEquals(PCM20_SHA, sha256(decoded.getAudioFrame().getAudio().toByteArray()));
        assertEquals(source, decoded);
        assertArrayEquals(binary, decoded.toByteArray());
        // 锁住真正的 Protobuf 二进制字节；JSON Fixture 仅负责可读性。
        assertEquals("7b98fed025945c054af2aae0c7cfc28a519f352c035b9caf5a5d7f02fbad512d",
                sha256(binary));
    }

    @Test
    void startUsesTrustedProfileAndShortFinalFrameKeepsActualPcmDuration() {
        ClientEnvelope start = start();
        assertEquals(start, CODEC.decodeClient(start.toByteArray()));

        ClientEnvelope shortFrame = frame(1, new byte[32]);
        assertEquals(shortFrame, CODEC.decodeClient(shortFrame.toByteArray()));
        ClientEnvelope nearNominalFinalFrame = frame(19, new byte[608]);
        assertEquals(nearNominalFinalFrame,
                CODEC.decodeClient(nearNominalFinalFrame.toByteArray()));
        assertEquals(0, shortFrame.getAudioFrame().getStartPositionMs());
        assertEquals(0, start.getStreamStart().getResumeAfterEventSequence());
    }

    @Test
    void publishedServerEventFixturesUseSameProtobufBodyForRestAndWss() throws Exception {
        int durable = 0;
        for (String name : List.of("realtime-events.jsonl", "capability-scenarios.jsonl")) {
            for (String line : Files.readAllLines(publicFixture(name), StandardCharsets.UTF_8)) {
                ServerEnvelope source = serverJson(line);
                byte[] wire = CODEC.encodeServer(source);
                assertEquals(source, CODEC.decodeServer(wire));
                assertArrayEquals(wire, CODEC.encodeServer(CODEC.decodeServer(wire)));
                assertTrue(source.getEventSequence() > 0);
                durable++;
            }
        }
        assertEquals(12, durable);

        int transientCount = 0;
        for (String line : Files.readAllLines(publicFixture("realtime-wss-transient.jsonl"),
                StandardCharsets.UTF_8)) {
            ServerEnvelope source = serverJson(line);
            assertEquals(source, CODEC.decodeServer(CODEC.encodeServer(source)));
            assertEquals(0, source.getEventSequence());
            transientCount++;
        }
        assertEquals(2, transientCount);
    }

    @Test
    void rejectsEmptyMalformedOversizedAndUnknownVersionBeforeBusiness() {
        expect(Code.EMPTY_MESSAGE, (byte[]) null);
        expect(Code.EMPTY_MESSAGE, new byte[0]);
        expect(Code.MALFORMED_PROTO, new byte[]{(byte) 0xff});
        expect(Code.MESSAGE_TOO_LARGE, new byte[RealtimeWireCodec.MAX_ENVELOPE_BYTES + 1]);
        expect(Code.UNSUPPORTED_VERSION,
                start().toBuilder().setSpecVersion("9.0").build().toByteArray());
    }

    @Test
    void rejectsMissingFieldsUnsupportedCancelAndWrongOneofWithoutLeakingBody() {
        expect(Code.INVALID_FIELD,
                start().toBuilder().clearClientMessageId().build().toByteArray());
        expect(Code.INVALID_ENVELOPE,
                start().toBuilder().setStreamId("other").build().toByteArray());
        expect(Code.INVALID_BODY, start().toBuilder().clearStreamStart().build().toByteArray());
        expect(Code.UNSUPPORTED_BODY, start().toBuilder()
                .setCancel(Cancel.newBuilder().setRequestId("cancel").build())
                .build().toByteArray());

        byte[] first = start().toByteArray();
        byte[] second = frame(1, new byte[32]).toByteArray();
        expect(Code.INVALID_BODY, concat(first, second)); // 不同 oneof tag，不能 last-wins
        expect(Code.INVALID_BODY, concat(first, first)); // 同一 oneof tag 重复也拒绝

        UnknownFieldSet unknownBody = unknownMessageField(17);
        expect(Code.INVALID_BODY, start().toBuilder().clearStreamStart()
                .setUnknownFields(unknownBody).build().toByteArray());
    }

    @Test
    void acceptsUnknownOrdinaryOptionalFieldAndZeroCursorsButNotUnknownKeyEnum() {
        ClientEnvelope withOptional = start().toBuilder()
                .setUnknownFields(unknownVarint(7)).build();
        ClientEnvelope decoded = CODEC.decodeClient(withOptional.toByteArray());
        assertTrue(decoded.getUnknownFields().hasField(7));
        assertArrayEquals(withOptional.toByteArray(), decoded.toByteArray());

        expect(Code.INVALID_FIELD, frame(1, new byte[32]).toBuilder()
                .setAudioFrame(frame(1, new byte[32]).getAudioFrame().toBuilder()
                        .addFlagsValue(77)).build().toByteArray());
    }

    @Test
    void rejectsWrongProfileMissingPresenceAndUnsupportedUintRanges() {
        expect(Code.INVALID_FIELD, start().toBuilder()
                .setStreamStart(StreamStart.getDefaultInstance()).build().toByteArray());
        expect(Code.AUDIO_PROFILE_MISMATCH, start().toBuilder()
                .setStreamStart(start().getStreamStart().toBuilder()
                        .setAudioProfile(AudioProfile.newBuilder()
                                .setEncoding(AudioEncoding.AUDIO_ENCODING_PCM_S16LE)
                                .setSampleRateHz(48_000).setChannelCount(1)
                                .setFrameDurationMs(20))).build().toByteArray());
        expect(Code.INVALID_FIELD, frame(1, new byte[32]).toBuilder()
                .setAudioFrame(frame(1, new byte[32]).getAudioFrame().toBuilder()
                        .setStreamSequence(-1L)).build().toByteArray());
        expect(Code.INVALID_FIELD, frame(1, new byte[32]).toBuilder()
                .setAudioFrame(frame(1, new byte[32]).getAudioFrame().toBuilder()
                        .setFormatRevision(-1)).build().toByteArray());
    }

    @Test
    void checksExactPcmLengthMeetingLimitAndOriginalAudioChecksum() throws Exception {
        expect(Code.AUDIO_FRAME_INVALID,
                clientFixture("audio-frame-duration.invalid.json").toByteArray());
        expect(Code.AUDIO_FRAME_INVALID, frame(1, new byte[31]).toByteArray());
        expect(Code.AUDIO_FRAME_INVALID, frame(21, new byte[672]).toByteArray());
        CodecException limited = assertThrows(CodecException.class,
                () -> new RealtimeWireCodec(new AudioContract(16_000, 1, 20, 639))
                        .decodeClient(clientFixture("audio-frame-pcm20.valid.json")
                                .toByteArray()));
        assertEquals(Code.AUDIO_FRAME_INVALID, limited.code());

        ClientEnvelope wrongChecksum = clientFixture("audio-frame-pcm20.valid.json")
                .toBuilder().setAudioFrame(clientFixture("audio-frame-pcm20.valid.json")
                        .getAudioFrame().toBuilder().setChecksum("0".repeat(64))).build();
        expect(Code.CHECKSUM_MISMATCH, wrongChecksum.toByteArray());
        expect(Code.INVALID_FIELD, wrongChecksum.toBuilder()
                .setAudioFrame(wrongChecksum.getAudioFrame().toBuilder()
                        .setChecksum("sha256:" + PCM20_SHA)).build().toByteArray());
    }

    @Test
    void serverDistinguishesDurableAndTransientAndRejectsUnknownPositiveEnum() {
        ServerEnvelope heartbeat = ServerEnvelope.newBuilder()
                .setSpecVersion("0.1").setMeetingId("mtg").setStreamId("mtg")
                .setEmittedAt("2026-10-02T00:00:00Z")
                .setHeartbeat(Heartbeat.newBuilder().setNonce("n")
                        .setSentAt("2026-10-02T00:00:00Z")).build();
        assertEquals(heartbeat, CODEC.decodeServer(CODEC.encodeServer(heartbeat)));
        expectServer(Code.INVALID_ENVELOPE, heartbeat.toBuilder()
                .setEventId("evt").setEventSequence(1).build());

        ServerEnvelope event = heartbeat.toBuilder().clearHeartbeat()
                .setEventId("evt").setEventSequence(1)
                .setTranscriptUpsert(TranscriptUpsert.newBuilder()
                        .setSegmentId("seg").setRevision(1)
                        .setOperationValue(77).setLanguage("zh-CN")
                        .setStartMs(0).setEndMs(20)).build();
        expectServer(Code.INVALID_FIELD, event);
        expectServer(Code.INVALID_ENVELOPE, event.toBuilder().setEventSequence(0)
                .setTranscriptUpsert(event.getTranscriptUpsert().toBuilder().setOperationValue(1))
                .build());
    }

    @Test
    void serverRetainsOrdinaryOptionalFieldsAndRejectsRepeatedKnownBodies() {
        ServerEnvelope heartbeat = ServerEnvelope.newBuilder()
                .setSpecVersion("0.1").setMeetingId("mtg").setStreamId("mtg")
                .setEmittedAt("2026-10-02T00:00:00Z")
                .setHeartbeat(Heartbeat.newBuilder().setNonce("n")
                        .setSentAt("2026-10-02T00:00:00Z"))
                .setUnknownFields(unknownVarint(7)).build();
        byte[] binary = CODEC.encodeServer(heartbeat);
        ServerEnvelope decoded = CODEC.decodeServer(binary);
        assertTrue(decoded.getUnknownFields().hasField(7));
        assertArrayEquals(binary, CODEC.encodeServer(decoded));

        ServerEnvelope event = heartbeat.toBuilder().clearHeartbeat()
                .setUnknownFields(UnknownFieldSet.getDefaultInstance())
                .setEventId("evt").setEventSequence(1)
                .setSessionState(SessionStateEvent.newBuilder()
                        .setState(MeetingState.MEETING_STATE_RUNNING)).build();
        expectServerDecode(Code.INVALID_BODY, concat(binary, binary));
        expectServerDecode(Code.INVALID_BODY, concat(binary, CODEC.encodeServer(event)));
    }

    @Test
    void remainingClientBodiesHavePositiveAndNegativeWireRules() {
        ClientEnvelope complete = start().toBuilder().setInputComplete(InputComplete.newBuilder()
                .setLastStreamSequence(1).setLastAudioPositionMs(20)
                .setRequestId("complete-1")).build();
        assertEquals(complete, CODEC.decodeClient(complete.toByteArray()));
        expect(Code.INVALID_FIELD, complete.toBuilder().setInputComplete(
                complete.getInputComplete().toBuilder().clearRequestId()).build().toByteArray());
        expect(Code.INVALID_FIELD, complete.toBuilder().setInputComplete(
                complete.getInputComplete().toBuilder().setRecordingSizeBytes(640))
                .build().toByteArray());
        expect(Code.INVALID_FIELD, complete.toBuilder().setInputComplete(
                complete.getInputComplete().toBuilder().setRecordingChecksum(PCM20_SHA))
                .build().toByteArray());

        ClientEnvelope resume = start().toBuilder()
                .setResumeRequest(ResumeRequest.newBuilder().setAfterEventSequence(0)).build();
        assertEquals(resume, CODEC.decodeClient(resume.toByteArray()));
        expect(Code.INVALID_FIELD, resume.toBuilder().setResumeRequest(
                ResumeRequest.newBuilder().setAfterEventSequence(-1L)).build().toByteArray());

        ClientEnvelope heartbeat = start().toBuilder().setHeartbeat(Heartbeat.newBuilder()
                .setNonce("ping").setSentAt("2026-10-02T00:00:00Z")).build();
        assertEquals(heartbeat, CODEC.decodeClient(heartbeat.toByteArray()));
        expect(Code.INVALID_FIELD, heartbeat.toBuilder().setHeartbeat(
                heartbeat.getHeartbeat().toBuilder().clearSentAt()).build().toByteArray());
        expect(Code.INVALID_FIELD, heartbeat.toBuilder().setHeartbeat(
                heartbeat.getHeartbeat().toBuilder().setSentAt("bad-time"))
                .build().toByteArray());

        ClientEnvelope discontinuity = start().toBuilder().setAudioDiscontinuity(
                AudioDiscontinuity.newBuilder().setAfterStreamSequence(0)
                        .setResumeAudioPositionMs(0).setSourceGeneration(0)
                        .setReason(AudioDiscontinuityReason
                                .AUDIO_DISCONTINUITY_REASON_SOURCE_CHANGED)).build();
        assertEquals(discontinuity, CODEC.decodeClient(discontinuity.toByteArray()));
        expect(Code.INVALID_FIELD, discontinuity.toBuilder().setAudioDiscontinuity(
                discontinuity.getAudioDiscontinuity().toBuilder().setReasonValue(77))
                .build().toByteArray());
        expect(Code.INVALID_FIELD, discontinuity.toBuilder().setAudioDiscontinuity(
                discontinuity.getAudioDiscontinuity().toBuilder().setGapDurationMs(-1L))
                .build().toByteArray());
    }

    private static ClientEnvelope start() {
        return ClientEnvelope.newBuilder().setSpecVersion("0.1")
                .setMeetingId("mtg").setStreamId("mtg").setClientMessageId("msg_start")
                .setStreamStart(StreamStart.newBuilder().setAudioProfile(AudioProfile.newBuilder()
                        .setEncoding(AudioEncoding.AUDIO_ENCODING_PCM_S16LE)
                        .setSampleRateHz(16_000).setChannelCount(1).setFrameDurationMs(20)))
                .build();
    }

    private static ClientEnvelope frame(int durationMs, byte[] audio) {
        return ClientEnvelope.newBuilder().setSpecVersion("0.1")
                .setMeetingId("mtg").setStreamId("mtg").setClientMessageId("msg_frame")
                .setAudioFrame(MeetingAudioFrame.newBuilder().setStreamSequence(1)
                        .setStartPositionMs(0).setDurationMs(durationMs)
                        .setAudio(ByteString.copyFrom(audio))).build();
    }

    private static UnknownFieldSet unknownVarint(int number) {
        return UnknownFieldSet.newBuilder().addField(number,
                UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build();
    }

    private static UnknownFieldSet unknownMessageField(int number) {
        return UnknownFieldSet.newBuilder().addField(number,
                UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.EMPTY).build())
                .build();
    }

    private static ClientEnvelope clientFixture(String name) throws Exception {
        try (InputStream input = RealtimeWireCodecTest.class.getResourceAsStream(
                "/realtime/" + name)) {
            if (input == null) {
                throw new IOException("missing test fixture: " + name);
            }
            ClientEnvelope.Builder builder = ClientEnvelope.newBuilder();
            JsonFormat.parser().merge(new String(input.readAllBytes(), StandardCharsets.UTF_8),
                    builder);
            return builder.build();
        }
    }

    private static ServerEnvelope serverJson(String json) throws Exception {
        ServerEnvelope.Builder builder = ServerEnvelope.newBuilder();
        JsonFormat.parser().merge(json, builder);
        return builder.build();
    }

    private static Path publicFixture(String name) throws IOException {
        for (Path candidate : List.of(Path.of("contracts", "fixtures", name),
                Path.of("..", "contracts", "fixtures", name))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IOException("missing public fixture: " + name);
    }

    private static void expect(Code code, byte[] wire) {
        CodecException failure = assertThrows(CodecException.class,
                () -> CODEC.decodeClient(wire));
        assertEquals(code, failure.code());
        assertEquals(code.name(), failure.getMessage());
        assertFalse(failure.getMessage().contains("mtg"));
    }

    private static void expect(Code code, ClientEnvelope envelope) {
        expect(code, envelope.toByteArray());
    }

    private static void expectServer(Code code, ServerEnvelope envelope) {
        CodecException failure = assertThrows(CodecException.class,
                () -> CODEC.encodeServer(envelope));
        assertEquals(code, failure.code());
    }

    private static void expectServerDecode(Code code, byte[] wire) {
        CodecException failure = assertThrows(CodecException.class,
                () -> CODEC.decodeServer(wire));
        assertEquals(code, failure.code());
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
