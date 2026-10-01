package com.czur.reference.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 P2 Fixture 的会议级持久事件与瞬时 WSS 消息边界；不声称服务端已实现 WSS。
 */
class RealtimeFixtureContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> DURABLE_BODIES = Set.of(
            "sessionState", "transcriptUpsert", "translationUpsert", "speakerUpdate",
            "capabilityChanged", "finalization", "error", "audioContinuity");
    private static final Set<String> TRANSIENT_BODIES = Set.of("audioAck", "heartbeat");

    @Test
    void durableAndTransientFixturesStaySeparated() throws Exception {
        Map<String, Long> meetingSequences = new HashMap<>();
        Set<String> durableCoverage = new HashSet<>();
        int durableCount = 0;
        for (String name : List.of("realtime-events.jsonl", "capability-scenarios.jsonl")) {
            for (JsonNode event : rows(name)) {
                assertEquals(event.path("meetingId").asText(), event.path("streamId").asText());
                assertTrue(event.hasNonNull("eventId"));
                checkMeetingSequence(event, meetingSequences);
                durableCoverage.add(onlyBody(event, DURABLE_BODIES));
                assertFalse(event.has("audioAck"));
                assertFalse(event.has("heartbeat"));
                durableCount++;
            }
        }
        assertEquals(12, durableCount);
        assertEquals(DURABLE_BODIES, durableCoverage);

        List<JsonNode> transientRows = rows("realtime-wss-transient.jsonl");
        assertEquals(2, transientRows.size());
        Set<String> transientCoverage = new HashSet<>();
        for (JsonNode event : transientRows) {
            assertEquals(event.path("meetingId").asText(), event.path("streamId").asText());
            assertFalse(event.has("eventId"));
            assertEquals("0", event.path("eventSequence").asText());
            for (String durableBody : DURABLE_BODIES) {
                assertFalse(event.has(durableBody));
            }
            String body = onlyBody(event, TRANSIENT_BODIES);
            if ("audioAck".equals(body)) {
                assertTrue(event.path("audioAck").path("durable").asBoolean());
            }
            transientCoverage.add(body);
        }
        assertEquals(TRANSIENT_BODIES, transientCoverage);
    }

    @Test
    void sequenceCannotResetWhenASecondGenericStreamSharesTheMeeting() throws Exception {
        List<JsonNode> events = rows("meeting-global-reset.invalid.jsonl");
        assertEquals(2, events.size());
        assertEquals(events.get(0).path("meetingId").asText(), events.get(1).path("meetingId").asText());
        assertNotEquals(events.get(0).path("streamId").asText(), events.get(1).path("streamId").asText());

        Map<String, Long> meetingSequences = new HashMap<>();
        checkMeetingSequence(events.get(0), meetingSequences);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> checkMeetingSequence(events.get(1), meetingSequences));
        assertEquals("eventSequence expected 2, got 1", failure.getMessage());
    }

    @Test
    void checksumCoversOnlyOriginalAudioBytes() throws Exception {
        JsonNode valid = JSON.readTree(fixture("audio-frame-checksum.valid.json").toFile());
        JsonNode invalid = JSON.readTree(fixture("audio-frame-checksum.invalid.json").toFile());
        byte[] audio = Base64.getDecoder().decode(valid.path("audio").asText());
        assertArrayEquals(new byte[]{0, 1, 2, (byte) 0xff}, audio);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(audio));
        assertEquals("3d1f57c984978ef98a18378c8166c1cb8ede02c03eeb6aee7e2f121dfeee3e56", digest);
        assertEquals(digest, valid.path("checksum").asText());
        assertEquals(valid.path("audio").asText(), invalid.path("audio").asText());
        assertNotEquals(digest, invalid.path("checksum").asText());
    }

    private static void checkMeetingSequence(JsonNode event, Map<String, Long> sequences) {
        String meetingId = event.path("meetingId").asText();
        long next = sequences.getOrDefault(meetingId, 0L) + 1;
        String actual = event.path("eventSequence").asText();
        if (!Long.toString(next).equals(actual)) {
            throw new IllegalArgumentException("eventSequence expected " + next + ", got " + actual);
        }
        sequences.put(meetingId, next);
    }

    private static String onlyBody(JsonNode event, Set<String> allowed) {
        List<String> present = new ArrayList<>();
        for (String body : allowed) {
            if (event.has(body)) {
                present.add(body);
            }
        }
        assertEquals(1, present.size(), "event must contain exactly one allowed body");
        return present.getFirst();
    }

    private static List<JsonNode> rows(String name) throws IOException {
        List<JsonNode> result = new ArrayList<>();
        for (String line : Files.readAllLines(fixture(name), StandardCharsets.UTF_8)) {
            result.add(JSON.readTree(line));
        }
        return result;
    }

    private static Path fixture(String name) throws IOException {
        for (Path candidate : List.of(
                Path.of("contracts", "fixtures", name),
                Path.of("..", "contracts", "fixtures", name))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IOException("fixture not found: " + name);
    }
}
