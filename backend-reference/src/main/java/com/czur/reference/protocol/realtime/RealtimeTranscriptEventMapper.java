package com.czur.reference.protocol.realtime;

import com.czur.aimeeting.contract.realtime.v1.ServerEnvelope;
import com.czur.aimeeting.contract.realtime.v1.SpeakerRef;
import com.czur.aimeeting.contract.realtime.v1.TranscriptUpsert;
import com.czur.aimeeting.contract.realtime.v1.UpsertOperation;
import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.model.CanonicalTranscriptEvent;
import com.czur.reference.application.port.model.SpeakerReference;
import com.google.protobuf.util.JsonFormat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 单一受控持久事件类型 {@code transcriptUpsert} 的 Proto/规范 JSON 映射。
 *
 * <p>meeting_event 的 canonical_payload 保存完整 ServerEnvelope，不是裸字幕正文。
 * 读取时拒绝未知类型、非规范 JSON、错误 oneof 及外层列与正文不一致；不猜测旧
 * 任意 JSON 的语义。此类不分配事件序号、写库或决定何时向 WSS 发送。</p>
 */
public final class RealtimeTranscriptEventMapper {
    public static final String EVENT_TYPE = "transcriptUpsert";
    private static final JsonFormat.Printer PRINTER =
            JsonFormat.printer().omittingInsignificantWhitespace();

    private RealtimeTranscriptEventMapper() { }

    /** 从规范化 Driver 字幕构造完整、不可变的持久事件事实。 */
    public static EventLog event(String meetingId, String eventId, long sequence,
            Instant emittedAt, CanonicalTranscriptEvent transcript) {
        Objects.requireNonNull(transcript, "transcript");
        Instant at = Objects.requireNonNull(emittedAt, "emittedAt")
                .truncatedTo(ChronoUnit.MICROS);
        ServerEnvelope envelope = ServerEnvelope.newBuilder()
                .setSpecVersion(RealtimeWireCodec.SPEC_VERSION)
                .setMeetingId(meetingId).setStreamId(meetingId)
                .setEventId(eventId).setEventSequence(sequence)
                .setEmittedAt(at.toString())
                .setTranscriptUpsert(body(transcript)).build();
        return new EventLog(envelope.getSpecVersion(), meetingId, meetingId,
                eventId, sequence, at, EVENT_TYPE, canonical(envelope));
    }

    /** 只接受完整、规范、与 EventLog 列字段完全一致的 transcriptUpsert。 */
    public static ServerEnvelope fromStored(EventLog stored) {
        Objects.requireNonNull(stored, "stored");
        if (!EVENT_TYPE.equals(stored.eventType())) throw invalid();
        try {
            ServerEnvelope.Builder builder = ServerEnvelope.newBuilder();
            JsonFormat.parser().merge(stored.canonicalPayloadJson(), builder);
            ServerEnvelope envelope = builder.build();
            if (!stored.canonicalPayloadJson().equals(canonical(envelope))
                    || !RealtimeWireCodec.SPEC_VERSION.equals(envelope.getSpecVersion())
                    || !stored.specVersion().equals(envelope.getSpecVersion())
                    || !stored.meetingId().equals(envelope.getMeetingId())
                    || !stored.streamId().equals(envelope.getStreamId())
                    || !stored.eventId().equals(envelope.getEventId())
                    || stored.eventSequence() != envelope.getEventSequence()
                    || !stored.emittedAt().equals(Instant.parse(envelope.getEmittedAt()))
                    || envelope.getBodyCase() != ServerEnvelope.BodyCase.TRANSCRIPT_UPSERT) {
                throw invalid();
            }
            TranscriptUpsert body = envelope.getTranscriptUpsert();
            if (body.getSegmentId().isBlank() || body.getRevision() <= 0
                    || body.getStartMs() < 0 || body.getEndMs() < body.getStartMs()
                    || body.getLanguage().isBlank()
                    || body.getOperation() == UpsertOperation.UPSERT_OPERATION_UNKNOWN
                    || body.getOperation() == UpsertOperation.UNRECOGNIZED
                    || !Float.isFinite(body.getConfidence())
                    || body.getConfidence() < 0 || body.getConfidence() > 1) {
                throw invalid();
            }
            return envelope;
        } catch (RuntimeException | com.google.protobuf.InvalidProtocolBufferException failure) {
            throw invalid();
        }
    }

    /** 旧赢家必须对应同一帧生成的同一规范字幕，不可因稳定 ID 忽略正文冲突。 */
    public static boolean sameTranscript(EventLog stored, CanonicalTranscriptEvent expected) {
        return fromStored(stored).getTranscriptUpsert().equals(body(expected));
    }

    private static TranscriptUpsert body(CanonicalTranscriptEvent event) {
        TranscriptUpsert.Builder body = TranscriptUpsert.newBuilder()
                .setSegmentId(event.segmentId()).setRevision(event.revision())
                .setOperation(event.operation() == CanonicalTranscriptEvent.Operation.UPSERT
                        ? UpsertOperation.UPSERT_OPERATION_UPSERT
                        : UpsertOperation.UPSERT_OPERATION_REPLACE)
                .setStartMs(event.startMs()).setEndMs(event.endMs())
                .setLanguage(event.language()).setText(event.text())
                .setFinal(event.isFinal()).setConfidence((float) event.confidence())
                .addAllQualityFlags(event.qualityFlags())
                .addAllSupersedesSegmentIds(event.supersedesSegmentIds());
        SpeakerReference speaker = event.speaker();
        if (speaker != null) {
            body.setSpeaker(SpeakerRef.newBuilder()
                    .setSessionSpeakerId(speaker.sessionSpeakerId())
                    .setPersonId(speaker.personId() == null ? "" : speaker.personId())
                    .setDisplayName(speaker.displayName() == null ? "" : speaker.displayName()));
        }
        return body.build();
    }

    private static String canonical(ServerEnvelope envelope) {
        try {
            return PRINTER.print(envelope);
        } catch (com.google.protobuf.InvalidProtocolBufferException failure) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid transcript event fact");
    }
}
