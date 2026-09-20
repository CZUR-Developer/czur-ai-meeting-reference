package com.czur.reference.domain.meeting;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeetingIdPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");

    @Test
    void acceptsExactly1024Utf8BytesAndRejectsTheNextByte() {
        String maximumAscii = "m".repeat(MeetingIdPolicy.MAX_UTF8_BYTES);
        String maximumMultibyte = "会".repeat(341) + "a";

        assertEquals(maximumAscii, MeetingIdPolicy.requireValid(maximumAscii));
        assertEquals(MeetingIdPolicy.MAX_UTF8_BYTES,
                MeetingIdPolicy.utf8Length(maximumAscii));
        assertEquals(MeetingIdPolicy.MAX_UTF8_BYTES,
                MeetingIdPolicy.utf8Length(maximumMultibyte));
        assertThrows(IllegalArgumentException.class,
                () -> MeetingIdPolicy.requireValid(maximumAscii + "x"));
        assertThrows(IllegalArgumentException.class,
                () -> MeetingIdPolicy.requireValid(maximumMultibyte + "会"));
    }

    @Test
    void aggregateAndSnapshotShareTheSameBoundary() {
        String maximum = "m".repeat(MeetingIdPolicy.MAX_UTF8_BYTES);
        assertEquals(maximum,
                MeetingAggregate.legacyUnowned(
                        maximum, "deployment", "session", "profile", 1, NOW)
                        .meetingId());
        assertEquals(maximum,
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        maximum, "deployment", "session", "profile", 1,
                        MeetingState.CREATED, NOW, NOW)
                        .meetingId());

        String oversized = maximum + "x";
        assertThrows(IllegalArgumentException.class,
                () -> MeetingAggregate.legacyUnowned(
                        oversized, "deployment", "session", "profile", 1, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        oversized, "deployment", "session", "profile", 1,
                        MeetingState.CREATED, NOW, NOW));
    }

    @Test
    void rejectsBlankAndMalformedUtf16InsteadOfUsingReplacementBytes() {
        assertThrows(IllegalArgumentException.class,
                () -> MeetingIdPolicy.requireValid(" "));
        assertThrows(IllegalArgumentException.class,
                () -> MeetingIdPolicy.requireValid("invalid-\ud800"));
    }
}
