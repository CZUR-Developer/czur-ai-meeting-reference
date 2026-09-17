package com.czur.reference.application.port;

import com.czur.reference.domain.meeting.MeetingOwnership;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 版本化 opaque 幂等范围的跨字符集、隔离和 legacy 防碰撞契约。 */
class IdempotencyScopeCodecTest {

    @Test
    void roundTripUsesVersionedLengthPrefixedCanonicalEncoding() {
        String encoded = IdempotencyScopeCodec.encode(
                "部署:partner|一", "主体:用户|二", "meeting.custom:v1");

        assertTrue(encoded.startsWith("AIMS|1|"));
        assertEquals(new IdempotencyScopeCodec.Components(
                        "部署:partner|一", "主体:用户|二", "meeting.custom:v1"),
                IdempotencyScopeCodec.decode(encoded));
        assertEquals(encoded, IdempotencyScopeCodec.encode(
                "部署:partner|一", "主体:用户|二", "meeting.custom:v1"));
    }

    @Test
    void deploymentSubjectAndOperationAreOrthogonalScopeDimensions() {
        String baseline = IdempotencyScopeCodec.meetingCreate("deployment-a", "subject-a");

        assertNotEquals(baseline,
                IdempotencyScopeCodec.meetingCreate("deployment-b", "subject-a"));
        assertNotEquals(baseline,
                IdempotencyScopeCodec.meetingCreate("deployment-a", "subject-b"));
        assertNotEquals(baseline,
                IdempotencyScopeCodec.meetingStop("deployment-a", "subject-a"));
    }

    @Test
    void legacyNamespaceAndReservedSubjectCannotBecomeOwnedScope() {
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyScopeCodec.decode("meeting.create"));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyScopeCodec.decode("meeting.stop"));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyScopeCodec.meetingCreate(
                        "deployment", MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE));

        assertThrows(IllegalArgumentException.class,
                () -> new IdempotencyScopeCodec.Components(
                        "deployment", MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE,
                        IdempotencyScopeCodec.MEETING_CREATE_OPERATION));
    }

    @Test
    void rejectsUnknownVersionTruncationAndTrailingData() {
        String valid = IdempotencyScopeCodec.meetingCreate("deployment", "subject");

        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyScopeCodec.decode(valid.replace("AIMS|1|", "AIMS|2|")));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyScopeCodec.decode(valid.substring(0, valid.length() - 1)));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyScopeCodec.decode(valid + "x"));
    }
}
