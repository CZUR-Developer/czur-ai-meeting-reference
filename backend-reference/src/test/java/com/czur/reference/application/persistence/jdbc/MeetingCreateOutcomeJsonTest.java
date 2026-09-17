package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.port.MeetingCreateFailure;
import com.czur.reference.application.port.MeetingCreateOutcome;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork.ErrorCode;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingOwnership;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 会议创建成功 v1/v2 兼容和失败 v3 严格格式测试。 */
class MeetingCreateOutcomeJsonTest {
    @Test
    void successEncodingIsByteIdenticalToExistingVersionTwoAndRoundTrips() {
        MeetingCommandResult result = createdResult();
        String legacyEncoderBytes = IdempotencyResultJson.encode(result);

        String outcomeBytes = MeetingCreateOutcomeJson.encode(
                MeetingCreateOutcome.success(result));
        MeetingCreateOutcome.Success decoded = assertInstanceOf(
                MeetingCreateOutcome.Success.class,
                MeetingCreateOutcomeJson.decode(outcomeBytes));

        assertEquals(legacyEncoderBytes, outcomeBytes,
                "成功 Outcome 不得增加 wrapper 或改变既有 v2 编码字节");
        assertEquals("{\"schemaVersion\":2,\"operation\":\"CREATED\",\"meeting\":{"
                + "\"meetingId\":\"meeting\",\"deploymentScope\":\"deployment\","
                + "\"clientSessionId\":\"session\",\"subjectScope\":\"subject\","
                + "\"grantId\":\"grant\","
                + "\"grantExpiresAt\":\"2026-09-17T11:10:00.123456Z\","
                + "\"usageReservationId\":\"reservation\",\"profileId\":\"profile\","
                + "\"profileRevision\":7,\"state\":\"CREATED\","
                + "\"createdAt\":\"2026-09-17T11:00:00.123456Z\","
                + "\"updatedAt\":\"2026-09-17T11:00:00.123456Z\"}}", outcomeBytes,
                "冻结历史 v2 成功正文的完整字节顺序");
        assertEquals(result, decoded.result());
    }

    @Test
    void historicalUnversionedSuccessRemainsReadableWithoutInventingOwnership() {
        String v1 = "{\"operation\":\"CREATED\",\"meeting\":{"
                + "\"meetingId\":\"legacy\",\"deploymentScope\":\"deployment\","
                + "\"clientSessionId\":\"session\",\"profileId\":\"profile\","
                + "\"profileRevision\":1,\"state\":\"CREATED\","
                + "\"createdAt\":\"2026-09-17T11:00:00Z\","
                + "\"updatedAt\":\"2026-09-17T11:00:00Z\"}}";

        MeetingCreateOutcome.Success decoded = assertInstanceOf(
                MeetingCreateOutcome.Success.class,
                MeetingCreateOutcomeJson.decode(v1));

        assertEquals(MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE,
                decoded.result().meeting().subjectScope());
        assertNull(decoded.result().meeting().grantId());
    }

    @Test
    void failureUsesFixedVersionThreeFieldsAndRoundTrips() {
        MeetingCreateOutcome failure = MeetingCreateOutcome.failure(
                new MeetingCreateFailure(ErrorCode.INTERNAL, false));

        String encoded = MeetingCreateOutcomeJson.encode(failure);
        MeetingCreateOutcome.Failure decoded = assertInstanceOf(
                MeetingCreateOutcome.Failure.class,
                MeetingCreateOutcomeJson.decode(encoded));

        assertEquals("{\"schemaVersion\":3,\"operation\":\"MEETING_CREATE\","
                + "\"outcome\":\"FAILURE\","
                + "\"errorCode\":\"INTERNAL\",\"retryable\":false}", encoded);
        assertEquals(new MeetingCreateFailure(ErrorCode.INTERNAL, false), decoded.failure());
    }

    @Test
    void malformedOrNonPersistableFailureFailsClosedWithoutLeakingInput() {
        String attacker = "private-subject-token";
        String prefix = "{\"schemaVersion\":3,\"operation\":\"MEETING_CREATE\","
                + "\"outcome\":\"FAILURE\","
                + "\"errorCode\":\"";
        for (String invalid : new String[]{
                prefix + "STORAGE_UNAVAILABLE\",\"retryable\":false}",
                prefix + "INTERNAL\",\"retryable\":true}",
                prefix + "INTERNAL\",\"retryable\":false,\"extra\":\""
                        + attacker + "\"}",
                "{\"schemaVersion\":3,\"operation\":\"MEETING_STOP\","
                        + "\"outcome\":\"FAILURE\",\"errorCode\":\"INTERNAL\","
                        + "\"retryable\":false}",
                "{\"schemaVersion\":3,\"outcome\":\"FAILURE\","
                        + "\"errorCode\":\"INTERNAL\",\"retryable\":false}",
                prefix + "INTERNAL\",\"errorCode\":\"INTERNAL\","
                        + "\"retryable\":false}",
                "{\"schemaVersion\":4,\"operation\":\"MEETING_CREATE\","
                        + "\"outcome\":\"FAILURE\","
                        + "\"errorCode\":\"INTERNAL\",\"retryable\":false}",
                "{\"schemaVersion\":3,\"operation\":\"MEETING_CREATE\","
                        + "\"outcome\":\"FAILURE\","
                        + "\"errorCode\":\"INTERNAL\"}"
        }) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> MeetingCreateOutcomeJson.decode(invalid));
            assertEquals("会议创建 Outcome 格式非法", error.getMessage());
            assertFalse(error.getMessage().contains(attacker));
            assertNull(error.getCause());
        }
    }

    @Test
    void failureRejectsTrailingCommaAndNonCanonicalSchemaVersionNumbers() {
        // 独立 Review 发现的两个输入：对象尾逗号和带前导零的整数。
        assertMalformed("{\"schemaVersion\":3,\"operation\":\"MEETING_CREATE\","
                + "\"outcome\":\"FAILURE\",\"errorCode\":\"INTERNAL\","
                + "\"retryable\":false,}");
        assertMalformed(failureJson("03"));

        // 相邻语法边界：JSON 子解析器不得接受双逗号、其它前导零、符号、
        // 小数或指数形式作为 schemaVersion 整数。
        assertMalformed("{\"schemaVersion\":3,,\"operation\":\"MEETING_CREATE\","
                + "\"outcome\":\"FAILURE\",\"errorCode\":\"INTERNAL\","
                + "\"retryable\":false}");
        for (String invalidVersion : new String[]{
                "00", "+3", "-3", "3.0", "3e0", "3E+0"
        }) {
            assertMalformed(failureJson(invalidVersion));
        }
    }

    private static String failureJson(String schemaVersion) {
        return "{\"schemaVersion\":" + schemaVersion
                + ",\"operation\":\"MEETING_CREATE\""
                + ",\"outcome\":\"FAILURE\""
                + ",\"errorCode\":\"INTERNAL\",\"retryable\":false}";
    }

    private static void assertMalformed(String invalid) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> MeetingCreateOutcomeJson.decode(invalid));
        assertEquals("会议创建 Outcome 格式非法", error.getMessage());
        assertNull(error.getCause());
    }

    private static MeetingCommandResult createdResult() {
        Instant now = Instant.parse("2026-09-17T11:00:00.123456Z");
        return new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED,
                MeetingAggregate.MeetingSnapshot.owned(
                        "meeting", "deployment", "session", "subject",
                        "grant", now.plusSeconds(600), "reservation",
                        "profile", 7, MeetingState.CREATED, now, now));
    }
}
