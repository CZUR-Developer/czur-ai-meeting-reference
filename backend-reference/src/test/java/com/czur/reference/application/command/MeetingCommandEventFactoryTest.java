package com.czur.reference.application.command;

import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** legacy 与 owned 命令路径共享事件格式的回归门禁。 */
class MeetingCommandEventFactoryTest {
    private static final Instant NOW = Instant.parse("2026-09-17T10:30:00Z");

    @Test
    void legacyAndOwnedCreatedEventsKeepTheSameStableWireShape() {
        MeetingAggregate.MeetingSnapshot legacy = MeetingAggregate.MeetingSnapshot.legacyUnowned(
                "meeting-event", "deployment-event", "session-event", "profile", 1,
                MeetingState.CREATED, NOW, NOW);
        MeetingAggregate.MeetingSnapshot owned = MeetingAggregate.MeetingSnapshot.owned(
                "meeting-event", "deployment-event", "session-event", "subject",
                "grant", NOW.plusSeconds(600), "reservation", "profile", 1,
                MeetingState.CREATED, NOW, NOW);

        var legacyEvent = MeetingCommandEventFactory.meetingCreated(legacy, NOW);
        var ownedEvent = MeetingCommandEventFactory.meetingCreated(owned, NOW);

        assertEquals(legacyEvent, ownedEvent);
        assertEquals("MeetingCreated", legacyEvent.eventType());
        assertEquals("Meeting", legacyEvent.aggregateType());
        assertEquals("meeting-event", legacyEvent.aggregateId());
        assertEquals("{\"meetingId\":\"meeting-event\","
                        + "\"deploymentScope\":\"deployment-event\","
                        + "\"state\":\"CREATED\","
                        + "\"updatedAt\":\"2026-09-17T10:30:00Z\"}",
                legacyEvent.payload());
    }

    @Test
    void createdAndStopEventsUseDifferentStableIds() {
        MeetingAggregate.MeetingSnapshot meeting = MeetingAggregate.MeetingSnapshot.legacyUnowned(
                "meeting-event", "deployment-event", "session-event", "profile", 1,
                MeetingState.CREATED, NOW, NOW);

        var created = MeetingCommandEventFactory.meetingCreated(meeting, NOW);
        var stopped = MeetingCommandEventFactory.meetingStopRequested(meeting, NOW);

        assertEquals("MeetingStopRequested", stopped.eventType());
        org.junit.jupiter.api.Assertions.assertNotEquals(created.eventId(), stopped.eventId());
    }
}
