package com.czur.reference.protocol.json;

import com.czur.reference.application.command.CreateMeetingRequestFact;
import com.czur.reference.application.command.CreateMeetingRequestSnapshot;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.command.MeetingRequestCanonicalizer;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Create 事实独立 JSON schema 的纯 Java 往返和损坏输入门禁。 */
class CreateMeetingFactJsonTest {
    private static final String REQUEST_SECRET = "client-session-secret";
    private static final String RESPONSE_TOKEN = "secret-session-token";

    @Test
    void requestRoundTripUsesStableCanonicalOrderAndUtf8Bytes() {
        CreateMeetingRequestFact fact = requestFact();
        String canonical = CreateMeetingRequestFactJson.encode(fact);

        assertEquals(canonical,
                CreateMeetingRequestFactJson.encode(CreateMeetingRequestFactJson.decode(canonical)));
        assertEquals(canonical,
                CreateMeetingRequestFactJson.encode(CreateMeetingRequestFactJson.decode(
                        CreateMeetingRequestFactJson.encodeUtf8(fact))));
        assertEquals("[\"en-US\",\"zh-CN\"]",
                canonical.substring(canonical.indexOf("\"targets\":"),
                        canonical.indexOf("]", canonical.indexOf("\"targets\":")) + 1)
                        .replaceAll(".*(\\[.*\\])", "$1"));
        assertArrayEquals(canonical.getBytes(StandardCharsets.UTF_8),
                CreateMeetingRequestFactJson.encodeUtf8(fact));
    }

    @Test
    void responseRoundTripPreservesFullDecisionAndSensitiveFactWithoutLoggingIt() {
        CreateMeetingResponseFact fact = responseFact();
        String canonical = CreateMeetingResponseFactJson.encode(fact);
        CreateMeetingResponseFact decoded = CreateMeetingResponseFactJson.decode(canonical);

        assertEquals(canonical, CreateMeetingResponseFactJson.encode(decoded));
        assertEquals(fact.meetingId(), decoded.meetingId());
        assertEquals(fact.realtime().sessionToken(), decoded.realtime().sessionToken());
        assertEquals(fact.capabilityDecisions(), decoded.capabilityDecisions());
        assertEquals(canonical,
                CreateMeetingResponseFactJson.encode(CreateMeetingResponseFactJson.decode(
                        CreateMeetingResponseFactJson.encodeUtf8(fact))));
    }

    @Test
    void requestRejectsUnknownDuplicateTrailingWrongTypeAndOverflow() {
        String canonical = CreateMeetingRequestFactJson.encode(requestFact());
        assertMalformed(canonical.substring(0, canonical.length() - 1)
                + ",\"unknown\":true}");
        assertMalformed(canonical.replaceFirst("\\\"schemaVersion\\\":1",
                "\\\"schemaVersion\\\":1,\\\"schemaVersion\\\":1"));
        assertMalformed(canonical.substring(0, canonical.length() - 1) + ",}");
        assertMalformed(canonical.replaceFirst("\\\"schemaVersion\\\":1",
                "\\\"schemaVersion\\\":\\\"1\\\""));
        assertMalformed(canonical.replaceFirst("\\\"schemaVersion\\\":1",
                "\\\"schemaVersion\\\":999999999999999999999999"));
        assertMalformed(canonical.replace("\"retainAudio\":false",
                "\"retainAudio\":falseX"));
    }

    @Test
    void codecsRejectInvalidUtf8AndUnpairedSurrogateWithoutEchoingInput() {
        IllegalArgumentException badUtf8 = assertThrows(IllegalArgumentException.class,
                () -> CreateMeetingRequestFactJson.decode(new byte[] {(byte) 0xc3, 0x28}));
        assertFalse(badUtf8.getMessage().contains("c3"));

        String canonical = CreateMeetingRequestFactJson.encode(requestFact());
        String malformedSurrogate = canonical.replace(REQUEST_SECRET, "\\uD800");
        IllegalArgumentException badSurrogate = assertThrows(IllegalArgumentException.class,
                () -> CreateMeetingRequestFactJson.decode(malformedSurrogate));
        assertFalse(badSurrogate.getMessage().contains(REQUEST_SECRET));
    }

    @Test
    void acceptsAValidEscapedSurrogatePairAndCanonicalizesIt() {
        String canonical = CreateMeetingRequestFactJson.encode(requestFact("music \uD834\uDD1E"));
        String escaped = canonical.replace("music \uD834\uDD1E", "music \\uD834\\uDD1E");
        assertEquals(canonical, CreateMeetingRequestFactJson.encode(
                CreateMeetingRequestFactJson.decode(escaped)));
    }

    @Test
    void responseRejectsUnknownDuplicateTrailingWrongTypeAndOverflowAndRedactsToken() {
        String canonical = CreateMeetingResponseFactJson.encode(responseFact());
        assertMalformedResponse(canonical.substring(0, canonical.length() - 1)
                + ",\"unknown\":true}");
        assertMalformedResponse(canonical.replaceFirst("\\\"schemaVersion\\\":1",
                "\\\"schemaVersion\\\":1,\\\"schemaVersion\\\":1"));
        assertMalformedResponse(canonical.substring(0, canonical.length() - 1) + ",}");
        assertMalformedResponse(canonical.replaceFirst("\\\"schemaVersion\\\":1",
                "\\\"schemaVersion\\\":\\\"1\\\""));
        assertMalformedResponse(canonical.replaceFirst("\\\"maxMeetingDurationMs\\\":3600000",
                "\\\"maxMeetingDurationMs\\\":999999999999999999999999"));
        assertMalformedResponse(canonical.replace("\"requiredByProfile\":true",
                "\"requiredByProfile\":trueX"));

        String malformedToken = canonical.replace(RESPONSE_TOKEN, RESPONSE_TOKEN + "\\uD800");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CreateMeetingResponseFactJson.decode(malformedToken));
        assertFalse(error.getMessage().contains(RESPONSE_TOKEN));
    }

    private static void assertMalformed(String json) {
        assertThrows(IllegalArgumentException.class,
                () -> CreateMeetingRequestFactJson.decode(json));
    }

    private static void assertMalformedResponse(String json) {
        assertThrows(IllegalArgumentException.class,
                () -> CreateMeetingResponseFactJson.decode(json));
    }

    private static CreateMeetingRequestFact requestFact() {
        return requestFact("private title");
    }

    private static CreateMeetingRequestFact requestFact(String title) {
        CreateMeetingRequestSnapshot snapshot = new CreateMeetingRequestSnapshot(
                REQUEST_SECRET,
                new CreateMeetingRequestSnapshot.ProfileRef("standard", 1),
                new CreateMeetingRequestSnapshot.ClientDescriptor(
                        "reference-app", "1.0", "0.1.0", "device-secret"),
                title,
                "Asia/Shanghai",
                new CreateMeetingRequestSnapshot.LanguageSelection(
                        "zh-CN", List.of("zh-CN", "en-US")),
                AudioProfile.pcm16kMono(100),
                Set.of("summary.structured", "transcript.realtime"),
                new CreateMeetingRequestSnapshot.ArtifactPolicy(false, true),
                "idempotency-secret-1234");
        return new CreateMeetingRequestFact(snapshot,
                MeetingRequestCanonicalizer.digest(snapshot),
                MeetingRequestCanonicalizer.CREATE_SNAPSHOT_SCHEMA_VERSION);
    }

    private static CreateMeetingResponseFact responseFact() {
        CapabilityDecision decision = new CapabilityDecision(
                "summary.structured", CapabilityDecision.Availability.DEGRADED, true,
                CapabilityDecision.Action.USE_FALLBACK, "transcript.final",
                List.of("workflow.summary"), List.of("artifact.summary"), "provider.slow", 7L);
        return new CreateMeetingResponseFact(
                "mtg_fact_json_1", MeetingState.CREATED,
                new CreateMeetingRequestSnapshot.ProfileRef("standard", 3),
                Set.of("summary.structured", "transcript.final"), List.of(decision),
                AudioProfile.pcm16kMono(100),
                new CreateMeetingResponseFact.RealtimeSession(
                        URI.create("https://example.test/realtime"),
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                        RESPONSE_TOKEN, Instant.parse("2030-01-01T00:00:00Z")),
                new CreateMeetingResponseFact.Limits(3_600_000, 64_000, 200, 30_000L));
    }
}
