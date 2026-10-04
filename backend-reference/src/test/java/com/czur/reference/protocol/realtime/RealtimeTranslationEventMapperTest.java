package com.czur.reference.protocol.realtime;

import com.czur.reference.application.port.model.CanonicalTranscriptEvent;
import com.czur.reference.application.port.model.CanonicalTranslationEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeTranslationEventMapperTest {
    @Test void storesCanonicalTranslationEnvelopeAndComparesReplayBody() {
        var translation = new CanonicalTranslationEvent("tr-1", "seg-1", 2,
                CanonicalTranscriptEvent.Operation.UPSERT, "zh-CN", "en-US",
                "Hello", true, List.of());
        var stored = RealtimeTranslationEventMapper.event("mtg_translation", "evt_tr_1",
                3, Instant.parse("2026-10-04T00:00:00Z"), translation);
        var decoded = RealtimeTranslationEventMapper.fromStored(stored);
        assertEquals("translationUpsert", stored.eventType());
        assertEquals("seg-1", decoded.getTranslationUpsert().getSourceSegmentId());
        assertEquals("Hello", decoded.getTranslationUpsert().getText());
        assertTrue(RealtimeTranslationEventMapper.sameTranslation(stored, translation));
        assertFalse(RealtimeTranslationEventMapper.sameTranslation(stored,
                new CanonicalTranslationEvent("tr-1", "seg-1", 2,
                        CanonicalTranscriptEvent.Operation.UPSERT, "zh-CN", "en-US",
                        "Changed", true, List.of())));
        assertThrows(IllegalArgumentException.class, () ->
                RealtimeTranscriptEventMapper.fromStored(stored));
    }
}
