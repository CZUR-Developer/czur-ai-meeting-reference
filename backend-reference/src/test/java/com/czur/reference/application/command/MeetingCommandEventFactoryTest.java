package com.czur.reference.application.command;

import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals("meeting-921658ae03a034b2744bd3437b7e9a9a6e076c13e036f2e2a53c713e01aeb8d4",
                created.eventId());
        assertEquals("meeting-97c7db07ea2a920a38f70cc4a9002a3855321302b77c79c7b7266b34ef897d7f",
                stopped.eventId());
        org.junit.jupiter.api.Assertions.assertNotEquals(created.eventId(), stopped.eventId());
    }

    @Test
    void deletionEventHasStableIdentityMinimalPayloadAndStatusTime() {
        MeetingStatus status = deletionStatus(
                MeetingState.DELETION_PENDING, ManifestStatus.DELETION_PENDING);

        var first = MeetingCommandEventFactory.meetingDeletionRequested(
                "deployment-event", status);
        var replayConstruction = MeetingCommandEventFactory.meetingDeletionRequested(
                "deployment-event", status);

        assertEquals(first, replayConstruction);
        assertEquals(
                "meeting-1de6a90f78eafbf5ad6c402cea679d516ca45ade67627f037803528ccad1f185",
                first.eventId());
        assertEquals("MeetingDeletionRequested", first.eventType());
        assertEquals("Meeting", first.aggregateType());
        assertEquals("meeting-event", first.aggregateId());
        assertEquals("{\"meetingId\":\"meeting-event\","
                        + "\"deploymentScope\":\"deployment-event\","
                        + "\"state\":\"DELETION_PENDING\","
                        + "\"updatedAt\":\"2026-09-17T10:30:00Z\"}",
                first.payload());
        assertEquals(NOW, first.occurredAt());
        assertEquals(NOW, first.availableAt());
    }

    @Test
    void deletionEventExcludesPrivateCommandAndMeetingFacts() {
        String receipt = "private-finalization-receipt";
        MeetingStatus status = new MeetingStatus(
                "meeting-event",
                MeetingState.DELETION_PENDING,
                FinalizationFact.accepted(receipt),
                ManifestStatus.DELETION_PENDING,
                NOW);

        var event = MeetingCommandEventFactory.meetingDeletionRequested(
                "deployment-event", status);
        String diagnostic = event.toString();

        for (String secret : new String[]{
                receipt,
                "private-subject",
                "private-idempotency-key",
                "private-request-digest",
                "private-meeting-body",
                "private-object-storage-path"
        }) {
            assertFalse(event.payload().contains(secret));
            assertFalse(diagnostic.contains(secret));
        }
    }

    @Test
    void deletionEventRejectsBlankDeploymentAndMismatchedDeletionStates() {
        MeetingStatus pending = deletionStatus(
                MeetingState.DELETION_PENDING, ManifestStatus.DELETION_PENDING);
        assertThrows(IllegalArgumentException.class,
                () -> MeetingCommandEventFactory.meetingDeletionRequested(" ", pending));

        for (MeetingStatus invalid : new MeetingStatus[]{
                deletionStatus(MeetingState.DELETED, ManifestStatus.DELETED),
                deletionStatus(MeetingState.DELETION_PENDING, ManifestStatus.DELETED),
                deletionStatus(MeetingState.DELETED, ManifestStatus.DELETION_PENDING)
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> MeetingCommandEventFactory.meetingDeletionRequested(
                            "deployment-event", invalid));
        }
    }

    @Test
    void allEventShapesRejectInvalidUnicodeWithoutChangingValidWireBytes() {
        String invalidDeployment = "private-deployment-\uD800";
        String invalidMeetingId = "private-meeting-\uDC00";
        MeetingAggregate.MeetingSnapshot invalidLegacyDeployment =
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        "meeting-event", invalidDeployment, "session-event", "profile", 1,
                        MeetingState.CREATED, NOW, NOW);

        assertInvalidUnicode(
                () -> MeetingCommandEventFactory.meetingCreated(
                        invalidLegacyDeployment, NOW),
                invalidDeployment);
        assertInvalidUnicode(
                () -> MeetingCommandEventFactory.meetingStopRequested(
                        invalidLegacyDeployment, NOW),
                invalidDeployment);
        assertInvalidUnicode(
                () -> MeetingCommandEventFactory.meetingDeletionRequested(
                        invalidDeployment,
                        deletionStatus(
                                MeetingState.DELETION_PENDING,
                                ManifestStatus.DELETION_PENDING)),
                invalidDeployment);

        MeetingStatus invalidIdStatus = new MeetingStatus(
                invalidMeetingId,
                MeetingState.DELETION_PENDING,
                FinalizationFact.accepted("private-finalization-receipt"),
                ManifestStatus.DELETION_PENDING,
                NOW);
        assertInvalidUnicode(
                () -> MeetingCommandEventFactory.meetingDeletionRequested(
                        "deployment-event", invalidIdStatus),
                invalidMeetingId);

        // 合法 supplementary Unicode 继续使用既有 UTF-8/JSON 字节，不被误拒绝。
        MeetingStatus validUnicode = new MeetingStatus(
                "meeting-😀",
                MeetingState.DELETION_PENDING,
                FinalizationFact.accepted("receipt"),
                ManifestStatus.DELETION_PENDING,
                NOW);
        assertEquals("{\"meetingId\":\"meeting-😀\","
                        + "\"deploymentScope\":\"deployment-🚀\","
                        + "\"state\":\"DELETION_PENDING\","
                        + "\"updatedAt\":\"2026-09-17T10:30:00Z\"}",
                MeetingCommandEventFactory.meetingDeletionRequested(
                        "deployment-🚀", validUnicode).payload());
    }

    private static MeetingStatus deletionStatus(
            MeetingState state,
            ManifestStatus manifestStatus) {
        return new MeetingStatus(
                "meeting-event",
                state,
                FinalizationFact.accepted("private-finalization-receipt"),
                manifestStatus,
                NOW);
    }

    private static void assertInvalidUnicode(
            org.junit.jupiter.api.function.Executable invocation,
            String sensitiveInput) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, invocation);
        assertFalse(error.getMessage().contains(sensitiveInput));
        org.junit.jupiter.api.Assertions.assertNull(error.getCause());
    }
}
