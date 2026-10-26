package com.czur.reference.application.command;

import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Create 成功响应事实的纯 Java 值对象契约。 */
class CreateMeetingResponseFactTest {
    private static final Instant EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void freezesAllResponseFactsAndCollections() {
        Set<String> granted = new HashSet<>(Set.of("summary.structured", "transcript.final"));
        List<CapabilityDecision> decisions = new ArrayList<>(List.of(decision(
                "summary.structured", CapabilityDecision.Availability.AVAILABLE)));
        CreateMeetingResponseFact fact = valid(granted, decisions);

        granted.add("speaker.diarization.session");
        decisions.clear();

        assertEquals(Set.of("summary.structured", "transcript.final"),
                fact.grantedCapabilities());
        assertEquals(1, fact.capabilityDecisions().size());
        assertThrows(UnsupportedOperationException.class,
                () -> fact.grantedCapabilities().add("translation.realtime"));
        assertThrows(UnsupportedOperationException.class,
                () -> fact.capabilityDecisions().clear());
        assertEquals(MeetingState.CREATED, fact.state());
        assertEquals("mtg_response_fact_1", fact.meetingId());
    }

    @Test
    void canonicalizesCapabilityDecisionOrder() {
        CreateMeetingResponseFact fact = valid(Set.of("summary.structured"), List.of(
                decision("transcript.final", CapabilityDecision.Availability.AVAILABLE),
                decision("summary.structured", CapabilityDecision.Availability.DEGRADED)));

        assertEquals(List.of("summary.structured", "transcript.final"),
                fact.capabilityDecisions().stream()
                        .map(CapabilityDecision::capability)
                        .toList());
    }

    @Test
    void requiresCreatedStateAndRejectsInvalidResponseValues() {
        assertThrows(IllegalArgumentException.class, () -> new CreateMeetingResponseFact(
                "mtg_response_fact_1", MeetingState.AUTHORIZED, profile(), Set.of(),
                List.of(), AudioProfile.pcm16kMono(100), realtime(), limits()));
        assertThrows(IllegalArgumentException.class, () -> new CreateMeetingResponseFact(
                "not-a-meeting-id", MeetingState.CREATED, profile(), Set.of(),
                List.of(), AudioProfile.pcm16kMono(100), realtime(), limits()));
        assertThrows(IllegalArgumentException.class, () -> new CreateMeetingResponseFact(
                "mtg_response_fact_1", MeetingState.CREATED, profile(), Set.of(" "),
                List.of(), AudioProfile.pcm16kMono(100), realtime(), limits()));
        assertThrows(IllegalArgumentException.class, () -> new CreateMeetingResponseFact(
                "mtg_response_fact_1", MeetingState.CREATED, profile(), Set.of(),
                List.of(decision("summary.structured", CapabilityDecision.Availability.AVAILABLE),
                        decision("summary.structured", CapabilityDecision.Availability.DEGRADED)),
                AudioProfile.pcm16kMono(100), realtime(), limits()));
        assertThrows(IllegalArgumentException.class, () -> new CreateMeetingResponseFact(
                "mtg_response_fact_1", MeetingState.CREATED, profile(), Set.of(),
                List.of(), new AudioProfile(AudioProfile.AudioEncoding.PCM_S16LE,
                        48_000, 1, 100), realtime(), limits()));
    }

    @Test
    void realtimeDescriptorDoesNotGuessTokenOrProtocol() {
        assertThrows(IllegalArgumentException.class, () -> new CreateMeetingResponseFact.RealtimeSession(
                URI.create("wss://example.test/realtime"), "wrong.protocol", "token", EXPIRES_AT));
        assertThrows(IllegalArgumentException.class, () -> new CreateMeetingResponseFact.RealtimeSession(
                URI.create("wss://example.test/realtime"),
                CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, "", EXPIRES_AT));
        assertThrows(IllegalArgumentException.class, () -> new CreateMeetingResponseFact.RealtimeSession(
                URI.create("/relative"),
                CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, "token", EXPIRES_AT));
    }

    @Test
    void validatesLimitsAndRedactsSensitiveResponseContents() {
        assertThrows(IllegalArgumentException.class,
                () -> new CreateMeetingResponseFact.Limits(0, 1, 1, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CreateMeetingResponseFact.Limits(1, 0, 1, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CreateMeetingResponseFact.Limits(1, 1, 0, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CreateMeetingResponseFact.Limits(1, 1, 1, -1L, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CreateMeetingResponseFact.Limits(1, 1, 1, null, 0));

        String diagnostic = valid(Set.of("summary.structured"), List.of(
                decision("summary.structured", CapabilityDecision.Availability.AVAILABLE))).toString();
        assertFalse(diagnostic.contains("mtg_response_fact_1"));
        assertFalse(diagnostic.contains("https://example.test/realtime"));
        assertFalse(diagnostic.contains("secret-session-token"));
        assertFalse(diagnostic.contains("standard"));
        assertEquals("CreateMeetingResponseFact[state=CREATED, grantedCapabilityCount=1, "
                        + "capabilityDecisionCount=1, realtimePresent=true, limitsPresent=true]",
                diagnostic);
    }

    private static CreateMeetingResponseFact valid(
            Set<String> granted, List<CapabilityDecision> decisions) {
        return new CreateMeetingResponseFact(
                "mtg_response_fact_1", MeetingState.CREATED, profile(), granted, decisions,
                AudioProfile.pcm16kMono(100), realtime(), limits());
    }

    private static CreateMeetingRequestSnapshot.ProfileRef profile() {
        return new CreateMeetingRequestSnapshot.ProfileRef("standard", 1);
    }

    private static CreateMeetingResponseFact.RealtimeSession realtime() {
        return new CreateMeetingResponseFact.RealtimeSession(
                URI.create("https://example.test/realtime"),
                CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                "secret-session-token", EXPIRES_AT);
    }

    private static CreateMeetingResponseFact.Limits limits() {
        return new CreateMeetingResponseFact.Limits(3_600_000, 64_000, 200, 30_000L, 120_000);
    }

    private static CapabilityDecision decision(
            String capability, CapabilityDecision.Availability availability) {
        return new CapabilityDecision(
                capability, availability, false, CapabilityDecision.Action.ENABLE, null,
                List.of("workflow." + capability), List.of("artifact." + capability),
                null, 1L);
    }
}
