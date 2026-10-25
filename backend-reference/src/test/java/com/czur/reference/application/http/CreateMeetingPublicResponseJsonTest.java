package com.czur.reference.application.http;

import com.czur.reference.application.command.CreateMeetingRequestSnapshot;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.meeting.MeetingState;
import com.czur.reference.protocol.json.CreateMeetingResponseFactJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 公开 Create 投影与内部 V013 JSON 边界的纯 Java 契约门禁。 */
class CreateMeetingPublicResponseJsonTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void projectsOnlyOpenApiFieldsAndOmitsNullOptionalProperties() throws Exception {
        var fact = fact(null);
        String publicJson = CreateMeetingPublicResponseJson.encode(fact);
        JsonNode body = JSON.readTree(publicJson);

        assertEquals(List.of("meetingId", "state", "acceptedProfile",
                "grantedCapabilities", "capabilityDecisions", "acceptedAudioProfile",
                "realtime", "limits"), fields(body));
        assertEquals("mtg_public_projection", body.path("meetingId").asText());
        assertEquals("CREATED", body.path("state").asText());
        assertEquals(List.of("id", "revision"), fields(body.path("acceptedProfile")));
        assertEquals(List.of("transcript.realtime"), strings(body.path("grantedCapabilities")));
        assertEquals(2, body.path("capabilityDecisions").size());
        JsonNode disabled = body.path("capabilityDecisions").get(0);
        assertEquals(List.of("capability", "availability", "reasonCode",
                "requiredByProfile"), fields(disabled));
        assertEquals("summary.structured", disabled.path("capability").asText());
        assertEquals("NOT_REQUESTED", disabled.path("reasonCode").asText());
        JsonNode realtimeDecision = body.path("capabilityDecisions").get(1);
        assertEquals(List.of("capability", "availability", "requiredByProfile"),
                fields(realtimeDecision));
        assertEquals("transcript.realtime", realtimeDecision.path("capability").asText());
        assertEquals(List.of("encoding", "sampleRateHz", "channelCount", "frameDurationMs"),
                fields(body.path("acceptedAudioProfile")));
        assertEquals(List.of("url", "subprotocol", "sessionToken", "expiresAt"),
                fields(body.path("realtime")));
        assertEquals("opaque-test-token", body.path("realtime").path("sessionToken").asText());
        assertEquals(List.of("maxMeetingDurationMs", "maxAudioFrameBytes",
                "eventReplayWindow"), fields(body.path("limits")));
        assertFalse(publicJson.contains("schemaVersion"));
        assertFalse(publicJson.contains("warningCode"));
        assertFalse(publicJson.contains("action"));
        assertFalse(publicJson.contains("affectedWorkflows"));
        assertFalse(publicJson.contains("producedArtifacts"));
        assertFalse(publicJson.contains("planRevision"));
        assertFalse(new HttpResponse(201, java.util.Map.of(), publicJson).toString()
                .contains("opaque-test-token"));
    }

    @Test
    void firstAndStrictlyDecodedV013ReplayProduceIdenticalPublicBytes() throws Exception {
        var fact = fact(30_000L);
        String first = CreateMeetingPublicResponseJson.encode(fact);
        String replay = CreateMeetingPublicResponseJson.encode(
                CreateMeetingResponseFactJson.decode(
                        CreateMeetingResponseFactJson.encode(fact)));
        Path fixture = Path.of("..", "contracts", "fixtures",
                "create-meeting-response.v1.json");
        if (!Files.isRegularFile(fixture)) {
            fixture = Path.of("contracts", "fixtures", "create-meeting-response.v1.json");
        }
        String compactFixture = JSON.writeValueAsString(JSON.readTree(
                Files.readString(fixture, StandardCharsets.UTF_8)));
        // Python 的真实 OpenAPI schema 门禁验证同一 fixture；这里再把 Java
        // 投影的 UTF-8 正文逐字节锁定到它，避免两边各自通过却互不相干。
        assertEquals(compactFixture, first);
        assertEquals(first, replay);
        assertTrue(first.contains("\"maxReplayAudioMs\":30000"));
        assertFalse(first.equals(CreateMeetingResponseFactJson.encode(fact)));
    }

    @Test
    void legacyFallbackFieldStaysNullWithoutInternalExecutionFields() throws Exception {
        var original = fact(null);
        var enabled = new CapabilityDecision("translation.realtime",
                CapabilityDecision.Availability.AVAILABLE, false,
                CapabilityDecision.Action.ENABLE, null,
                List.of("workflow.translation"), List.of("artifact.translation"),
                null, 1L);
        var fact = new CreateMeetingResponseFact(original.meetingId(), original.state(),
                original.acceptedProfile(), original.grantedCapabilities(),
                List.of(enabled), original.acceptedAudioProfile(), original.realtime(),
                original.limits());
        JsonNode decision = JSON.readTree(CreateMeetingPublicResponseJson.encode(fact))
                .path("capabilityDecisions").get(0);
        assertEquals(List.of("capability", "availability", "requiredByProfile"),
                fields(decision));
        assertFalse(decision.has("fallbackCapability"));
        assertEquals("AVAILABLE", decision.path("availability").asText());
    }

    private static CreateMeetingResponseFact fact(Long maxReplayAudioMs) {
        var enabled = new CapabilityDecision("transcript.realtime",
                CapabilityDecision.Availability.AVAILABLE, true,
                CapabilityDecision.Action.ENABLE, null, List.of("workflow.realtime"),
                List.of("artifact.transcript"), null, 1L);
        var disabled = new CapabilityDecision("summary.structured",
                CapabilityDecision.Availability.UNAVAILABLE, false,
                CapabilityDecision.Action.DISABLE, null, List.of("workflow.summary"),
                List.of("artifact.summary"), "NOT_REQUESTED", 1L);
        return new CreateMeetingResponseFact("mtg_public_projection", MeetingState.CREATED,
                new CreateMeetingRequestSnapshot.ProfileRef("standard", 1),
                Set.of("transcript.realtime"), List.of(enabled, disabled),
                AudioProfile.pcm16kMono(100),
                new CreateMeetingResponseFact.RealtimeSession(
                        URI.create("wss://example.test/realtime"),
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                        "opaque-test-token", Instant.parse("2030-01-01T00:01:00Z")),
                new CreateMeetingResponseFact.Limits(120_000, 3_200, 128,
                        maxReplayAudioMs));
    }

    private static List<String> fields(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }
}
