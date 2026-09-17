package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingOwnership;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotencyResultJsonTest {
    @Test
    void versionTwoRoundTripPreservesCompleteOwnedMeetingCommandResult() {
        Instant createdAt = Instant.parse("2026-09-16T03:00:00Z");
        MeetingAggregate.MeetingSnapshot snapshot = MeetingAggregate.MeetingSnapshot.owned(
                "meeting-\"中文",
                "partner\\scope",
                "client-session",
                "subject-stable",
                "grant-1",
                createdAt.plusSeconds(600),
                "reservation-1",
                "meeting-minutes/v1",
                7,
                MeetingState.READY,
                createdAt,
                createdAt.plusSeconds(12));
        MeetingCommandResult expected = new MeetingCommandResult(
                MeetingCommandResult.Operation.STOP_ACCEPTED, snapshot);

        String encoded = IdempotencyResultJson.encode(expected);
        MeetingCommandResult actual = IdempotencyResultJson.decode(encoded);

        assertEquals(expected, actual);
        // 结果正文必须是 JSON 对象，而不是日志或 Java 序列化内容。
        assertEquals('{', encoded.charAt(0));
        assertEquals('}', encoded.charAt(encoded.length() - 1));
        org.junit.jupiter.api.Assertions.assertTrue(encoded.contains("\"schemaVersion\":2"));
    }

    @Test
    void readsHistoricalUnversionedResultAsLegacyWithoutInventingOwnership() {
        String json = "{\"operation\":\"CREATED\",\"meeting\":{"
                + "\"meetingId\":\"legacy-meeting\",\"deploymentScope\":\"deployment\","
                + "\"clientSessionId\":\"session\",\"profileId\":\"profile\","
                + "\"profileRevision\":1,\"state\":\"CREATED\","
                + "\"createdAt\":\"2026-09-16T03:00:00Z\","
                + "\"updatedAt\":\"2026-09-16T03:00:00Z\"}}";

        MeetingAggregate.MeetingSnapshot snapshot = IdempotencyResultJson.decode(json).meeting();

        assertEquals(MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE, snapshot.subjectScope());
        org.junit.jupiter.api.Assertions.assertNull(snapshot.grantId());
        org.junit.jupiter.api.Assertions.assertNull(snapshot.grantExpiresAt());
        org.junit.jupiter.api.Assertions.assertNull(snapshot.usageReservationId());
    }

    @Test
    void versionTwoLegacyResultKeepsExplicitNullOwnershipReferences() {
        Instant now = Instant.parse("2026-09-16T03:00:00.123456789Z");
        var legacy = MeetingAggregate.MeetingSnapshot.legacyUnowned(
                "legacy", "deployment", "session", "profile", 1,
                MeetingState.CREATED, now, now);

        String encoded = IdempotencyResultJson.encode(new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED, legacy));
        var decoded = IdempotencyResultJson.decode(encoded).meeting();

        assertEquals(legacy, decoded);
        org.junit.jupiter.api.Assertions.assertTrue(encoded.contains("\"grantId\":null"));
        org.junit.jupiter.api.Assertions.assertTrue(encoded.contains("\"grantExpiresAt\":null"));
        org.junit.jupiter.api.Assertions.assertTrue(encoded.contains("\"usageReservationId\":null"));
    }

    @Test
    void unknownFieldIsRejectedInsteadOfSilentlyReplayed() {
        String json = "{\"operation\":\"CREATED\",\"meeting\":{"
                + "\"meetingId\":\"m\",\"deploymentScope\":\"d\","
                + "\"clientSessionId\":\"c\",\"profileId\":\"p\","
                + "\"profileRevision\":1,\"state\":\"CREATED\","
                + "\"createdAt\":\"2026-09-16T03:00:00Z\","
                + "\"updatedAt\":\"2026-09-16T03:00:00Z\",\"extra\":\"x\"}}";

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> IdempotencyResultJson.decode(json));
        assertEquals("幂等响应格式非法", failure.getMessage());
        org.junit.jupiter.api.Assertions.assertNull(failure.getCause());
        org.junit.jupiter.api.Assertions.assertFalse(failure.getMessage().contains("extra"));
    }

    @Test
    void malformedDynamicValuesNeverAppearInDecoderFailure() {
        String attackerText = "private-subject-and-token";
        String json = "{\"schemaVersion\":2,\"operation\":\"CREATED\",\"meeting\":{"
                + "\"meetingId\":\"m\",\"deploymentScope\":\"d\","
                + "\"clientSessionId\":\"c\",\"subjectScope\":\"s\","
                + "\"grantId\":\"g\",\"grantExpiresAt\":\"2026-09-16T04:00:00Z\","
                + "\"usageReservationId\":\"r\",\"profileId\":\"p\","
                + "\"profileRevision\":1,\"state\":\"" + attackerText + "\","
                + "\"createdAt\":\"2026-09-16T03:00:00Z\","
                + "\"updatedAt\":\"2026-09-16T03:00:00Z\"}}";

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> IdempotencyResultJson.decode(json));

        assertEquals("幂等响应格式非法", failure.getMessage());
        org.junit.jupiter.api.Assertions.assertFalse(failure.getMessage().contains(attackerText));
        org.junit.jupiter.api.Assertions.assertNull(failure.getCause());
    }
}
