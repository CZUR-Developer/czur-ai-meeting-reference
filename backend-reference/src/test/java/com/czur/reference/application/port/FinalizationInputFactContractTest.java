package com.czur.reference.application.port;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定 durable input fact 的纯 Java 值契约，不触发数据库或收口流程。 */
class FinalizationInputFactContractTest {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-21T01:02:03Z");
    private static final String MEETING_ID = "mtg_input_fact";
    private static final String INPUT_BINDING = "input-binding-sensitive";
    private static final String CONTENT_DIGEST = "sha256-content-digest-sensitive";

    @Test
    void factCopiesTargetLanguagesAndExposesAnUnmodifiableList() {
        List<String> targets = new ArrayList<>(List.of("en-US", "ja-JP"));
        FinalizationInputFact fact = new FinalizationInputFact(
                MEETING_ID,
                INPUT_BINDING,
                42,
                12_345,
                "zh-CN",
                targets,
                ACCEPTED_AT,
                CONTENT_DIGEST);

        targets.add("de-DE");

        assertEquals(List.of("en-US", "ja-JP"), fact.targetLanguages());
        assertThrows(UnsupportedOperationException.class,
                () -> fact.targetLanguages().add("de-DE"));
        assertEquals(42, fact.lastAcceptedSequence());
        assertEquals(12_345, fact.audioDurationMs());
        assertEquals(ACCEPTED_AT, fact.acceptedAt());
    }

    @Test
    void rejectsMissingTextNegativeCountersAndInvalidTargetLanguages() {
        assertThrows(IllegalArgumentException.class, () -> fact(null, "binding", 0, 0,
                "zh-CN", List.of(), ACCEPTED_AT, CONTENT_DIGEST));
        assertThrows(IllegalArgumentException.class, () -> fact(MEETING_ID, " ", 0, 0,
                "zh-CN", List.of(), ACCEPTED_AT, CONTENT_DIGEST));
        assertThrows(IllegalArgumentException.class, () -> fact(MEETING_ID, INPUT_BINDING, -1, 0,
                "zh-CN", List.of(), ACCEPTED_AT, CONTENT_DIGEST));
        assertThrows(IllegalArgumentException.class, () -> fact(MEETING_ID, INPUT_BINDING, 0, -1,
                "zh-CN", List.of(), ACCEPTED_AT, CONTENT_DIGEST));
        assertThrows(IllegalArgumentException.class, () -> fact(MEETING_ID, INPUT_BINDING, 0, 0,
                "", List.of(), ACCEPTED_AT, CONTENT_DIGEST));
        assertThrows(NullPointerException.class, () -> fact(MEETING_ID, INPUT_BINDING, 0, 0,
                "zh-CN", null, ACCEPTED_AT, CONTENT_DIGEST));
        assertThrows(IllegalArgumentException.class, () -> fact(MEETING_ID, INPUT_BINDING, 0, 0,
                "zh-CN", List.of("en-US", "en-US"), ACCEPTED_AT, CONTENT_DIGEST));
        assertThrows(IllegalArgumentException.class, () -> fact(MEETING_ID, INPUT_BINDING, 0, 0,
                "zh-CN", List.of(" "), ACCEPTED_AT, CONTENT_DIGEST));
        assertThrows(NullPointerException.class, () -> fact(MEETING_ID, INPUT_BINDING, 0, 0,
                "zh-CN", List.of(), null, CONTENT_DIGEST));
        assertThrows(IllegalArgumentException.class, () -> fact(MEETING_ID, INPUT_BINDING, 0, 0,
                "zh-CN", List.of(), ACCEPTED_AT, ""));
    }

    @Test
    void toStringRedactsIdentifiersDigestAndReceiptLikeValues() {
        String text = new FinalizationInputFact(
                MEETING_ID,
                INPUT_BINDING,
                3,
                900,
                "zh-CN",
                List.of("en-US"),
                ACCEPTED_AT,
                CONTENT_DIGEST).toString();

        assertFalse(text.contains(MEETING_ID));
        assertFalse(text.contains(INPUT_BINDING));
        assertFalse(text.contains(CONTENT_DIGEST));
        assertFalse(text.contains("receipt-sensitive-value"));
        assertTrue(text.contains("meetingId=redacted"));
        assertTrue(text.contains("inputBinding=redacted"));
        assertTrue(text.contains("contentDigestPresent=true"));
    }

    @Test
    void equalValuesProduceEqualFactsWithoutSharingMutableInput() {
        FinalizationInputFact first = fact(MEETING_ID, INPUT_BINDING, 7, 800,
                "zh-CN", List.of("en-US"), ACCEPTED_AT, CONTENT_DIGEST);
        FinalizationInputFact second = fact(MEETING_ID, INPUT_BINDING, 7, 800,
                "zh-CN", List.of("en-US"), ACCEPTED_AT, CONTENT_DIGEST);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    private static FinalizationInputFact fact(
            String meetingId,
            String inputBinding,
            long lastAcceptedSequence,
            long audioDurationMs,
            String sourceLanguage,
            List<String> targetLanguages,
            Instant acceptedAt,
            String contentDigest) {
        return new FinalizationInputFact(
                meetingId,
                inputBinding,
                lastAcceptedSequence,
                audioDurationMs,
                sourceLanguage,
                targetLanguages,
                acceptedAt,
                contentDigest);
    }
}
