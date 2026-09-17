package com.czur.reference.application.port.identity;

import com.czur.reference.domain.meeting.MeetingOwnership;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 身份纯 Java 边界的安全和稳定性契约测试。 */
class IdentityPortContractTest {
    private static final Instant ISSUED_AT = Instant.parse("2026-09-17T00:00:00Z");
    private static final Instant EXPIRES_AT = ISSUED_AT.plusSeconds(600);

    @Test
    void subjectIsStableAndDoesNotIncludeDynamicAuthorizationClaims() {
        Subject subject = new Subject("subject-42", "organization-a");
        VerifiedIdentity first = new VerifiedIdentity(subject, Set.of("USER"),
                Set.of("meeting:create"), ISSUED_AT, EXPIRES_AT, "credential-reference-1");
        VerifiedIdentity refreshed = new VerifiedIdentity(subject, Set.of("USER", "REVIEWER"),
                Set.of("meeting:create", "meeting:stop"), ISSUED_AT.plusSeconds(1),
                EXPIRES_AT.plusSeconds(600), "credential-reference-2");

        assertEquals(first.subject(), refreshed.subject());
        assertNotEquals(first.roles(), refreshed.roles());
        assertNotEquals(first.permissions(), refreshed.permissions());
        assertTrue(first.hasPermission("meeting:create"));
        assertFalse(first.hasPermission("meeting:stop"));
    }

    @Test
    void subjectRejectsReservedLegacyOwnershipMarker() {
        assertThrows(IllegalArgumentException.class,
                () -> new Subject(MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE));
    }

    @Test
    void verifiedIdentityCopiesClaimsAndUsesClosedExpiryBoundary() {
        var roles = new java.util.HashSet<>(Set.of("USER"));
        var permissions = new java.util.HashSet<>(Set.of("meeting:create"));
        VerifiedIdentity identity = new VerifiedIdentity(new Subject("subject-42"), roles,
                permissions, ISSUED_AT, EXPIRES_AT, null);
        roles.add("ADMIN");
        permissions.clear();

        assertEquals(Set.of("USER"), identity.roles());
        assertEquals(Set.of("meeting:create"), identity.permissions());
        assertFalse(identity.isExpiredAt(EXPIRES_AT.minusNanos(1)));
        assertTrue(identity.isExpiredAt(EXPIRES_AT));
        assertThrows(UnsupportedOperationException.class, () -> identity.roles().add("ADMIN"));
    }

    @Test
    void verifiedIdentityDiagnosticTextDoesNotExposeIdentityOrAuthorizationClaims() {
        VerifiedIdentity identity = new VerifiedIdentity(
                new Subject("subject-private", "organization-private"),
                Set.of("ROLE_PRIVATE"), Set.of("permission:private"),
                ISSUED_AT, EXPIRES_AT, "credential-private");

        assertFalse(identity.toString().contains("subject-private"));
        assertFalse(identity.toString().contains("organization-private"));
        assertFalse(identity.toString().contains("ROLE_PRIVATE"));
        assertFalse(identity.toString().contains("permission:private"));
        assertFalse(identity.toString().contains("credential-private"));
        assertFalse(identity.subject().toString().contains("subject-private"));
        assertFalse(identity.subject().toString().contains("organization-private"));
    }

    @Test
    void secretCredentialDefensivelyCopiesRedactsAndDestroysTemporaryBytes() {
        byte[] source = "never-log-this-token".getBytes(StandardCharsets.UTF_8);
        byte[] expected = source.clone();
        AtomicReference<byte[]> callbackView = new AtomicReference<>();
        SecretCredential credential = SecretCredential.copyOf(source);
        Arrays.fill(source, (byte) 'x');

        byte[] observed = credential.useBytes(bytes -> {
            callbackView.set(bytes);
            return bytes.clone();
        });

        assertArrayEquals(expected, observed);
        assertTrue(allZero(callbackView.get()), "回调临时副本在返回后必须被清零");
        assertEquals("SecretCredential[REDACTED]", credential.toString());
        assertFalse(credential.toString().contains("never-log"));

        credential.close();
        credential.close();
        assertTrue(credential.isDestroyed());
        assertThrows(IllegalStateException.class, () -> credential.useBytes(bytes -> bytes.length));
    }

    @Test
    void verificationRequestIsNotRecordAndNeverPrintsCredential() {
        try (SecretCredential credential = SecretCredential.copyOf(
                "private-bearer".getBytes(StandardCharsets.UTF_8))) {
            IdentityVerificationRequest request = new IdentityVerificationRequest(
                    IdentityVerificationRequest.CredentialType.BEARER_TOKEN,
                    credential, "ai-meeting", "android-reference-app");

            assertFalse(IdentityVerificationRequest.class.isRecord());
            assertTrue(request.toString().contains("credential=REDACTED"));
            assertFalse(request.toString().contains("private-bearer"));
            assertEquals(credential, request.credential());
        }
    }

    @Test
    void identityResultsExposeOnlyStableErrors() {
        IdentityProviderResult<VerifiedIdentity> failure = IdentityProviderResult.failure(
                new IdentityProviderError(
                        IdentityProviderError.Code.INVALID_CREDENTIAL, false));

        assertFalse(failure.isSuccess());
        assertEquals(IdentityProviderError.Code.INVALID_CREDENTIAL,
                failure.errorOrThrow().code());
        assertEquals("ai_meeting.identity.invalid_credential",
                failure.errorOrThrow().messageKey());
        assertThrows(IllegalStateException.class, failure::valueOrThrow);
    }

    private static boolean allZero(byte[] value) {
        for (byte item : value) {
            if (item != 0) {
                return false;
            }
        }
        return true;
    }
}
