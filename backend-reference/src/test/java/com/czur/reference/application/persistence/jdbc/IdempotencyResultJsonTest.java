package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotencyResultJsonTest {
    @Test
    void roundTripPreservesMeetingCommandResult() {
        Instant createdAt = Instant.parse("2026-09-16T03:00:00Z");
        MeetingAggregate.MeetingSnapshot snapshot = new MeetingAggregate.MeetingSnapshot(
                "meeting-\"中文",
                "partner\\scope",
                "client-session",
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
    }

    @Test
    void unknownFieldIsRejectedInsteadOfSilentlyReplayed() {
        String json = "{\"operation\":\"CREATED\",\"meeting\":{"
                + "\"meetingId\":\"m\",\"deploymentScope\":\"d\","
                + "\"clientSessionId\":\"c\",\"profileId\":\"p\","
                + "\"profileRevision\":1,\"state\":\"CREATED\","
                + "\"createdAt\":\"2026-09-16T03:00:00Z\","
                + "\"updatedAt\":\"2026-09-16T03:00:00Z\",\"extra\":\"x\"}}";

        assertThrows(IllegalArgumentException.class, () -> IdempotencyResultJson.decode(json));
    }
}
