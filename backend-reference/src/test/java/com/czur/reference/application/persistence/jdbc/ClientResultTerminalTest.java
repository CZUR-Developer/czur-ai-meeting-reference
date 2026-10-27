package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.meeting.MeetingEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冻结用量超时收口按首次 Create 能力计划选择终态与 Manifest 告警。 */
class ClientResultTerminalTest {
    @Test
    void enabledCapabilityWithoutWarningStaysReady() {
        JdbcClientMeetingResultFinalizer.ResultTerminal terminal =
                JdbcClientMeetingResultFinalizer.terminalFor(List.of(decision(
                        "transcript.realtime", CapabilityDecision.Action.ENABLE,
                        CapabilityDecision.Availability.AVAILABLE, null)));
        assertEquals(MeetingEvent.READY, terminal.event());
        assertEquals(ManifestStatus.READY, terminal.manifestStatus());
        assertTrue(terminal.warnings().isEmpty());
    }

    @Test
    void capabilityWarningSelectsReadyWithWarnings() {
        JdbcClientMeetingResultFinalizer.ResultTerminal terminal =
                JdbcClientMeetingResultFinalizer.terminalFor(List.of(decision(
                        "summary.structured", CapabilityDecision.Action.DISABLE,
                        CapabilityDecision.Availability.UNAVAILABLE, "NOT_GRANTED")));
        assertEquals(MeetingEvent.READY_WITH_WARNINGS, terminal.event());
        assertEquals(ManifestStatus.READY_WITH_WARNINGS, terminal.manifestStatus());
        assertEquals("NOT_GRANTED", terminal.warnings().getFirst().code());
        assertEquals("summary.structured", terminal.warnings().getFirst().capability());
        assertEquals("ai_meeting.capability.NOT_GRANTED",
                terminal.warnings().getFirst().messageKey());
    }

    @Test
    void notRequestedOptionalCapabilityIsNotAWarning() {
        JdbcClientMeetingResultFinalizer.ResultTerminal terminal =
                JdbcClientMeetingResultFinalizer.terminalFor(List.of(decision(
                        "summary.structured", CapabilityDecision.Action.DISABLE,
                        CapabilityDecision.Availability.AVAILABLE, "NOT_REQUESTED")));
        assertEquals(MeetingEvent.READY, terminal.event());
        assertEquals(ManifestStatus.READY, terminal.manifestStatus());
        assertTrue(terminal.warnings().isEmpty());
    }

    private static CapabilityDecision decision(String capability,
            CapabilityDecision.Action action, CapabilityDecision.Availability availability,
            String warningCode) {
        return new CapabilityDecision(capability, availability, false, action, null,
                List.of(), List.of(), warningCode, 1);
    }
}
