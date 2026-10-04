package com.czur.reference.protocol.realtime;

import com.czur.aimeeting.contract.realtime.v1.ServerEnvelope;
import com.czur.aimeeting.contract.realtime.v1.TranslationUpsert;
import com.czur.aimeeting.contract.realtime.v1.UpsertOperation;
import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.model.CanonicalTranscriptEvent;
import com.czur.reference.application.port.model.CanonicalTranslationEvent;
import com.google.protobuf.util.JsonFormat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** 标准译文的完整 ServerEnvelope 映射；验证存储列与规范 JSON 同源。 */
public final class RealtimeTranslationEventMapper {
    public static final String EVENT_TYPE = "translationUpsert";
    private static final JsonFormat.Printer PRINTER =
            JsonFormat.printer().omittingInsignificantWhitespace();

    private RealtimeTranslationEventMapper() { }

    /** 使用后端分配的事件身份封装一次已归一化的译文修订。 */
    public static EventLog event(String meetingId, String eventId, long sequence,
            Instant emittedAt, CanonicalTranslationEvent translation) {
        Instant at = Objects.requireNonNull(emittedAt, "emittedAt")
                .truncatedTo(ChronoUnit.MICROS);
        ServerEnvelope envelope = ServerEnvelope.newBuilder()
                .setSpecVersion(RealtimeWireCodec.SPEC_VERSION)
                .setMeetingId(meetingId).setStreamId(meetingId)
                .setEventId(eventId).setEventSequence(sequence)
                .setEmittedAt(at.toString()).setTranslationUpsert(body(translation)).build();
        return new EventLog(envelope.getSpecVersion(), meetingId, meetingId,
                eventId, sequence, at, EVENT_TYPE, canonical(envelope));
    }

    /** 只接受字段完整、类型正确且与事件日志列一致的规范译文事件。 */
    public static ServerEnvelope fromStored(EventLog stored) {
        Objects.requireNonNull(stored, "stored");
        if (!EVENT_TYPE.equals(stored.eventType())) throw invalid();
        try {
            ServerEnvelope.Builder builder = ServerEnvelope.newBuilder();
            JsonFormat.parser().merge(stored.canonicalPayloadJson(), builder);
            ServerEnvelope envelope = builder.build();
            TranslationUpsert item = envelope.getTranslationUpsert();
            if (!stored.canonicalPayloadJson().equals(canonical(envelope))
                    || !RealtimeWireCodec.SPEC_VERSION.equals(envelope.getSpecVersion())
                    || !stored.specVersion().equals(envelope.getSpecVersion())
                    || !stored.meetingId().equals(envelope.getMeetingId())
                    || !stored.streamId().equals(envelope.getStreamId())
                    || !stored.eventId().equals(envelope.getEventId())
                    || stored.eventSequence() != envelope.getEventSequence()
                    || !stored.emittedAt().equals(Instant.parse(envelope.getEmittedAt()))
                    || envelope.getBodyCase() != ServerEnvelope.BodyCase.TRANSLATION_UPSERT
                    || item.getTranslationId().isBlank() || item.getSourceSegmentId().isBlank()
                    || item.getRevision() <= 0 || item.getSourceLanguage().isBlank()
                    || item.getTargetLanguage().isBlank()
                    || item.getSourceLanguage().equals(item.getTargetLanguage())
                    || item.getOperation() == UpsertOperation.UPSERT_OPERATION_UNKNOWN
                    || item.getOperation() == UpsertOperation.UNRECOGNIZED) throw invalid();
            return envelope;
        } catch (RuntimeException | com.google.protobuf.InvalidProtocolBufferException failure) {
            throw invalid();
        }
    }

    /** 同一客户端事件 ID 的重放必须保留完整译文正文和修订事实。 */
    public static boolean sameTranslation(EventLog stored, CanonicalTranslationEvent expected) {
        return fromStored(stored).getTranslationUpsert().equals(body(expected));
    }

    private static TranslationUpsert body(CanonicalTranslationEvent event) {
        Objects.requireNonNull(event, "event");
        return TranslationUpsert.newBuilder()
                .setTranslationId(event.translationId())
                .setSourceSegmentId(event.sourceSegmentId())
                .setRevision(event.revision())
                .setOperation(event.operation() == CanonicalTranscriptEvent.Operation.UPSERT
                        ? UpsertOperation.UPSERT_OPERATION_UPSERT
                        : UpsertOperation.UPSERT_OPERATION_REPLACE)
                .setSourceLanguage(event.sourceLanguage())
                .setTargetLanguage(event.targetLanguage())
                .setText(event.text()).setFinal(event.isFinal())
                .addAllSupersedesTranslationIds(event.supersedesTranslationIds()).build();
    }

    private static String canonical(ServerEnvelope envelope) {
        try { return PRINTER.print(envelope); }
        catch (com.google.protobuf.InvalidProtocolBufferException failure) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid translation event fact");
    }
}
