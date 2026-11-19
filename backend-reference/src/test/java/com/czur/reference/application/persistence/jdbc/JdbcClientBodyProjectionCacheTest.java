package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.EventLog;
import com.czur.reference.application.port.model.CanonicalTranscriptEvent;
import com.czur.reference.application.port.model.CanonicalTranslationEvent;
import com.czur.reference.protocol.realtime.RealtimeTranscriptEventMapper;
import com.czur.reference.protocol.realtime.RealtimeTranslationEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JdbcClientBodyProjectionCacheTest {
    @Test void immutableCandidateDoesNotChangeCommittedStateOnRollback() {
        var original = empty().with(transcript(1, "a", "zh-CN", "原文😀", true), 1);
        var cache = new JdbcClientBodyProjectionCache();
        cache.committed("mtg_one", 1, original);
        var candidate = original.with(transcript(2, "a", "zh-CN", "未提交的修订", false), 2);
        assertEquals("原文😀", projection(original).text());
        assertEquals("", projection(candidate).text());
        assertSame(original, cache.find("mtg_one", 1));
        assertNull(cache.find("mtg_one", 2));
        assertThrows(UnsupportedOperationException.class, () -> original.latest().clear());
    }

    @Test void exactDatabaseSequenceAndMeetingIdentityAreRequired() {
        var state = empty().with(transcript(1, "a", "zh-CN", "文字", true), 1);
        var cache = new JdbcClientBodyProjectionCache();
        cache.committed("mtg_one", 7, state);
        assertNull(cache.find("mtg_one", 6));
        assertNull(cache.find("mtg_one", 8));
        assertNull(cache.find("mtg_other", 7));
        assertSame(state, cache.find("mtg_one", 7));
        cache.committed("mtg_one", 6, empty());
        assertSame(state, cache.find("mtg_one", 7));
    }

    @Test void entryAndWeightBoundsEvictWithoutChangingAnyProjection() {
        var state = empty().with(transcript(1, "a", "zh-CN", "文字", true), 1);
        var cache = new JdbcClientBodyProjectionCache(2, 1024 * 1024);
        cache.committed("mtg_a", 1, state);
        cache.committed("mtg_b", 1, state);
        assertSame(state, cache.find("mtg_a", 1));
        cache.committed("mtg_c", 1, state);
        assertNull(cache.find("mtg_b", 1));
        assertSame(state, cache.find("mtg_a", 1));
        var tiny = new JdbcClientBodyProjectionCache(2, state.estimatedWeight());
        tiny.committed("mtg_a", 1, state);
        var large = state.with(transcript(2, "b", "en-US", "large".repeat(100), true), 2);
        tiny.committed("mtg_a", 2, large);
        assertNull(tiny.find("mtg_a", 1));
        assertNull(tiny.find("mtg_a", 2));
        assertEquals("文字", projection(state).text());
    }

    @Test void latestFinalOrderingUnicodeAndTranslationsShareTheSameSerializer() {
        var state = empty().with(transcript(1, "b", "zh-CN", "旧\"文\\😀", false), 1)
                .with(transcript(2, "a", "en-US", "first", true), 2)
                .with(transcript(3, "b", "zh-CN", "最终😀", true), 3);
        var translated = RealtimeTranslationEventMapper.event("mtg_cache", "evt_t", 4,
                Instant.EPOCH, new CanonicalTranslationEvent("t", "b", 1,
                        CanonicalTranscriptEvent.Operation.UPSERT, "zh-CN", "ja-JP", "翻訳😀", true, List.of()));
        state = state.with(translated, 4);
        var projected = projection(state);
        assertEquals("first\n最终😀", projected.text());
        assertEquals("mul", projected.language());
        assertEquals("翻訳😀", projected.translations().getFirst().getText());
        long exact = JdbcClientMeetingResultFinalizer.transcriptArtifactBody(projected).toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + JdbcClientMeetingResultFinalizer.translationArtifactBody(projected).toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertEquals(exact, JdbcClientMeetingResultFinalizer.serializedClientBodyBytes(projected));
    }

    @Test void partialCanIncreaseSerializedBodyAndMustNotBypassTheBudget() {
        var state = empty().with(transcript(1, "a", "en-US", "a", true), 1)
                .with(transcript(2, "b", "long-language-".repeat(200), "b", true), 2);
        long before = JdbcClientMeetingResultFinalizer.serializedClientBodyBytes(projection(state));
        var candidate = state.with(transcript(3, "a", "en-US", "partial", false), 3);
        long after = JdbcClientMeetingResultFinalizer.serializedClientBodyBytes(projection(candidate));
        assertTrue(after > before, "移除一种语言后顶层语言由mul变成长标签，partial也必须完整核对预算");
    }

    @Test void summariesAreReadSeparatelyFromTheCachedEventState() {
        var state = empty().with(transcript(1, "a", "zh-CN", "原文", true), 1);
        var json = new ObjectMapper();
        var shortSummary = json.createObjectNode().put("language", "zh-CN").put("title", "标题");
        var longSummary = shortSummary.deepCopy().put("overview", "大纲".repeat(200));
        assertTrue(JdbcClientMeetingResultFinalizer.serializedClientBodyBytes(
                JdbcClientMeetingResultFinalizer.project(state, longSummary))
                > JdbcClientMeetingResultFinalizer.serializedClientBodyBytes(
                JdbcClientMeetingResultFinalizer.project(state, shortSummary)));
    }

    private static JdbcClientMeetingResultFinalizer.ClientEvents empty() {
        return new JdbcClientMeetingResultFinalizer.ClientEvents(Map.of(), Map.of());
    }
    private static JdbcClientMeetingResultFinalizer.Projection projection(
            JdbcClientMeetingResultFinalizer.ClientEvents events) {
        return JdbcClientMeetingResultFinalizer.project(events, null);
    }
    private static EventLog transcript(long sequence, String id, String language, String text, boolean complete) {
        return RealtimeTranscriptEventMapper.event("mtg_cache", "evt_" + sequence, sequence, Instant.EPOCH,
                new CanonicalTranscriptEvent(id, (int) sequence, CanonicalTranscriptEvent.Operation.UPSERT,
                        0, 1000, language, text, complete, null, 0, List.of(), List.of()));
    }
}
