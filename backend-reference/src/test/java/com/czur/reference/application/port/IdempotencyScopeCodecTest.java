package com.czur.reference.application.port;

import com.czur.reference.domain.meeting.MeetingOwnership;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        String stop = IdempotencyScopeCodec.meetingStop("deployment-a", "subject-a");
        String delete = IdempotencyScopeCodec.meetingDelete("deployment-a", "subject-a");

        assertNotEquals(baseline,
                IdempotencyScopeCodec.meetingCreate("deployment-b", "subject-a"));
        assertNotEquals(baseline,
                IdempotencyScopeCodec.meetingCreate("deployment-a", "subject-b"));
        assertNotEquals(baseline, stop);
        assertNotEquals(baseline, delete);
        assertNotEquals(stop, delete);
        assertEquals(new IdempotencyScopeCodec.Components(
                        "deployment-a", "subject-a",
                        IdempotencyScopeCodec.MEETING_DELETE_OPERATION),
                IdempotencyScopeCodec.decode(delete));
    }

    @Test
    void deleteScopeAndFailuresDoNotExposeSensitivePlaintext() {
        String deployment = "private-deployment-scope";
        String subject = "private-subject-scope";
        String encoded = IdempotencyScopeCodec.meetingDelete(deployment, subject);

        assertFalse(encoded.contains(deployment));
        assertFalse(encoded.contains(subject));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> IdempotencyScopeCodec.decode(encoded + "private-idempotency-key"));
        assertFalse(error.getMessage().contains(deployment));
        assertFalse(error.getMessage().contains(subject));
        assertFalse(
                error.getMessage().contains("private-idempotency-key"));
    }

    @Test
    void allCommandScopesRejectUnpairedSurrogatesInsteadOfColliding() {
        String invalidDeploymentOne = "private-deployment-\uD800";
        String invalidDeploymentTwo = "private-deployment-\uD801";
        String invalidSubjectOne = "private-subject-\uDC00";
        String invalidSubjectTwo = "private-subject-\uDC01";
        List<BiFunction<String, String, String>> commandScopes = List.of(
                IdempotencyScopeCodec::meetingCreate,
                IdempotencyScopeCodec::meetingStop,
                IdempotencyScopeCodec::meetingDelete);

        for (BiFunction<String, String, String> commandScope : commandScopes) {
            assertInvalidUnicode(
                    () -> commandScope.apply(invalidDeploymentOne, "valid-subject"),
                    invalidDeploymentOne);
            assertInvalidUnicode(
                    () -> commandScope.apply(invalidDeploymentTwo, "valid-subject"),
                    invalidDeploymentTwo);
            assertInvalidUnicode(
                    () -> commandScope.apply("valid-deployment", invalidSubjectOne),
                    invalidSubjectOne);
            assertInvalidUnicode(
                    () -> commandScope.apply("valid-deployment", invalidSubjectTwo),
                    invalidSubjectTwo);
        }
    }

    @Test
    void validSupplementaryUnicodeKeepsTheFrozenUtf8Bytes() {
        String encoded = IdempotencyScopeCodec.meetingDelete("部署😀", "用户🚀");

        assertEquals("AIMS|1|14:6YOo572y8J-YgA"
                + "14:55So5oi38J-agA"
                + "23:bWVldGluZy5kZWxldGUudjE", encoded);
        assertEquals(new IdempotencyScopeCodec.Components(
                        "部署😀", "用户🚀", IdempotencyScopeCodec.MEETING_DELETE_OPERATION),
                IdempotencyScopeCodec.decode(encoded));
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

    private static void assertInvalidUnicode(
            org.junit.jupiter.api.function.Executable invocation,
            String sensitiveInput) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, invocation);
        assertFalse(error.getMessage().contains(sensitiveInput));
        assertNull(error.getCause());
    }
}
