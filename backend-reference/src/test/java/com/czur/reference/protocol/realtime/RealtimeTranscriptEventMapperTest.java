package com.czur.reference.protocol.realtime;

import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.model.CanonicalTranscriptEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeTranscriptEventMapperTest {
    private static final CanonicalTranscriptEvent TRANSCRIPT = new CanonicalTranscriptEvent(
            "mock-segment-1", 1, CanonicalTranscriptEvent.Operation.UPSERT,
            0, 100, "zh-CN", "模拟实时字幕（修订 1）", false,
            null, 0.5, List.of(), List.of());

    @Test
    void completeServerEnvelopeRoundTripsAndRejectsOuterMismatchOrUnknownType() {
        EventLog event = RealtimeTranscriptEventMapper.event("mtg_mapper", "evt_mapper_1",
                1, Instant.parse("2030-01-01T00:00:00.123456789Z"), TRANSCRIPT);
        var decoded = RealtimeTranscriptEventMapper.fromStored(event);
        assertEquals("mtg_mapper", decoded.getMeetingId());
        assertEquals(1, decoded.getEventSequence());
        assertEquals("模拟实时字幕（修订 1）", decoded.getTranscriptUpsert().getText());
        assertTrue(RealtimeTranscriptEventMapper.sameTranscript(event, TRANSCRIPT));
        assertFalse(RealtimeTranscriptEventMapper.sameTranscript(event,
                new CanonicalTranscriptEvent("mock-segment-1", 2,
                        CanonicalTranscriptEvent.Operation.UPSERT, 0, 100,
                        "zh-CN", "other", false, null, 0.5, List.of(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> RealtimeTranscriptEventMapper.fromStored(
                new EventLog(event.specVersion(), event.meetingId(), event.streamId(),
                        event.eventId(), event.eventSequence(), event.emittedAt(), "unknown",
                        event.canonicalPayloadJson())));
        assertThrows(IllegalArgumentException.class, () -> RealtimeTranscriptEventMapper.fromStored(
                new EventLog(event.specVersion(), event.meetingId(), event.streamId(),
                        event.eventId(), 2, event.emittedAt(), event.eventType(),
                        event.canonicalPayloadJson())));
        assertThrows(IllegalArgumentException.class, () -> RealtimeTranscriptEventMapper.fromStored(
                new EventLog(event.specVersion(), event.meetingId(), event.streamId(),
                        event.eventId(), event.eventSequence(), event.emittedAt(), event.eventType(),
                        event.canonicalPayloadJson().replace("\"transcriptUpsert\"",
                                "\"unknownBody\""))));
    }
}
