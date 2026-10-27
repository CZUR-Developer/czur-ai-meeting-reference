package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 删除首次受理幂等正文的稳定字节、JSON 重排和关闭式失败门禁。 */
class MeetingDeletionResultJsonTest {
    private static final Instant UPDATED_AT =
            Instant.parse("2026-09-20T09:10:11.123456Z");
    private static final String RECEIPT = "private-finalization-receipt";
    private static final String ERROR = "会议删除幂等结果格式非法";

    @Test
    void acceptedSnapshotUsesFixedBytesAndRoundTrips() {
        MeetingStatus meeting = acceptedMeeting();

        String encoded = MeetingDeletionResultJson.encode(meeting);

        assertEquals("{\"schemaVersion\":1,\"operation\":\"MEETING_DELETE\","
                + "\"outcome\":\"ACCEPTED\",\"meetingId\":\"meeting-delete\","
                + "\"state\":\"DELETION_PENDING\","
                + "\"inputState\":\"INPUT_ACCEPTED\","
                + "\"finalizationReceipt\":\"private-finalization-receipt\","
                + "\"manifestState\":\"DELETION_PENDING\","
                + "\"updatedAt\":\"2026-09-20T09:10:11.123456Z\"}", encoded);
        assertEquals(meeting, MeetingDeletionResultJson.decode(encoded));
    }

    @Test
    void decoderAcceptsJsonbFieldReorderingAndNullableReceipt() {
        String reordered = "{\n"
                + " \"updatedAt\": \"2026-09-20T09:10:11.123456Z\","
                + " \"manifestState\": \"DELETION_PENDING\","
                + " \"finalizationReceipt\": null,"
                + " \"inputState\": \"INPUT_CANCELLED\","
                + " \"state\": \"DELETION_PENDING\","
                + " \"meetingId\": \"meeting-delete\","
                + " \"outcome\": \"ACCEPTED\","
                + " \"operation\": \"MEETING_DELETE\","
                + " \"schemaVersion\": 1\n} ";

        MeetingStatus decoded = MeetingDeletionResultJson.decode(reordered);

        assertEquals(cancelledMeeting(), decoded);
        assertEquals(reorderedMeetingCanonicalBytes(),
                MeetingDeletionResultJson.encode(decoded));
    }

    @Test
    void decoderRejectsUnknownMissingDuplicateAndMalformedJsonWithoutDisclosure() {
        String valid = MeetingDeletionResultJson.encode(acceptedMeeting());
        String attackerField = "private-attacker-field";
        for (String invalid : new String[]{
                valid.substring(0, valid.length() - 1)
                        + ",\"" + attackerField + "\":\"x\"}",
                valid.replace(",\"outcome\":\"ACCEPTED\"", ""),
                valid.substring(0, valid.length() - 1)
                        + ",\"state\":\"DELETION_PENDING\"}",
                valid.substring(0, valid.length() - 1) + ",}",
                valid + " trailing",
                valid.substring(0, valid.length() - 1),
                valid.replace("\"meeting-delete\"", "\"bad\\uD800\""),
                valid.replace("\"meeting-delete\"", "\"bad\\uZZZZ\""),
                valid.replace("\"meeting-delete\"", "\"bad\u0001value\"")
        }) {
            assertMalformed(invalid, attackerField);
        }
    }

    @Test
    void decoderRejectsWrongTypesConstantsEnumsTimesAndStatePairs() {
        String valid = MeetingDeletionResultJson.encode(acceptedMeeting());
        for (String invalid : new String[]{
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":01"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                valid.replace("\"MEETING_DELETE\"", "\"MEETING_STOP\""),
                valid.replace("\"ACCEPTED\"", "\"REPLAYED\""),
                valid.replace("\"meeting-delete\"", "null"),
                valid.replace("\"DELETION_PENDING\"", "\"UNKNOWN_STATE\""),
                valid.replace("\"INPUT_ACCEPTED\"", "\"INPUT_UNKNOWN\""),
                valid.replaceFirst("\"DELETION_PENDING\"", "\"DELETED\""),
                replaceLast(valid, "\"DELETION_PENDING\"", "\"DELETED\""),
                valid.replace("\"private-finalization-receipt\"", "null"),
                valid.replace("\"private-finalization-receipt\"", "false"),
                valid.replace("\"private-finalization-receipt\"", "\"   \""),
                valid.replace("2026-09-20T09:10:11.123456Z", "not-an-instant")
        }) {
            assertMalformed(invalid, RECEIPT);
        }

        String cancelled = MeetingDeletionResultJson.encode(cancelledMeeting());
        assertMalformed(cancelled.replace("\"finalizationReceipt\":null",
                "\"finalizationReceipt\":\"" + RECEIPT + "\""), RECEIPT);
    }

    @Test
    void encoderOnlyAcceptsTheFirstPendingPendingFact() {
        for (MeetingStatus invalid : new MeetingStatus[]{
                status(MeetingState.DELETED, ManifestStatus.DELETED),
                status(MeetingState.DELETION_PENDING, ManifestStatus.DELETED),
                status(MeetingState.DELETED, ManifestStatus.DELETION_PENDING)
        }) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> MeetingDeletionResultJson.encode(invalid));
            assertFalse(error.getMessage().contains(RECEIPT));
        }
    }

    private static MeetingStatus acceptedMeeting() {
        return new MeetingStatus(
                "meeting-delete",
                MeetingState.DELETION_PENDING,
                FinalizationFact.accepted(RECEIPT),
                ManifestStatus.DELETION_PENDING,
                UPDATED_AT);
    }

    private static MeetingStatus cancelledMeeting() {
        return new MeetingStatus(
                "meeting-delete",
                MeetingState.DELETION_PENDING,
                FinalizationFact.pending(InputState.INPUT_CANCELLED),
                ManifestStatus.DELETION_PENDING,
                UPDATED_AT);
    }

    private static MeetingStatus status(
            MeetingState state,
            ManifestStatus manifestStatus) {
        return new MeetingStatus(
                "meeting-delete",
                state,
                FinalizationFact.accepted(RECEIPT),
                manifestStatus,
                UPDATED_AT);
    }

    private static String reorderedMeetingCanonicalBytes() {
        return "{\"schemaVersion\":1,\"operation\":\"MEETING_DELETE\","
                + "\"outcome\":\"ACCEPTED\",\"meetingId\":\"meeting-delete\","
                + "\"state\":\"DELETION_PENDING\","
                + "\"inputState\":\"INPUT_CANCELLED\","
                + "\"finalizationReceipt\":null,"
                + "\"manifestState\":\"DELETION_PENDING\","
                + "\"updatedAt\":\"2026-09-20T09:10:11.123456Z\"}";
    }

    private static String replaceLast(String value, String target, String replacement) {
        int index = value.lastIndexOf(target);
        return value.substring(0, index) + replacement + value.substring(index + target.length());
    }

    private static void assertMalformed(String invalid, String... secrets) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> MeetingDeletionResultJson.decode(invalid));
        assertEquals(ERROR, error.getMessage());
        assertNull(error.getCause());
        for (String secret : secrets) {
            assertFalse(error.getMessage().contains(secret));
        }
    }
}
