package com.czur.reference.application.identity.referencedev;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** reference-dev 身份配置的开关、fail-fast 和 Secret 脱敏测试。 */
class ReferenceDevIdentityConfigTest {
    private static final String TOKEN_VALUE = "local-reference-token-0123456789-abcdef";

    @Test
    void defaultsToDisabledWithoutReadingIdentityFields() {
        ReferenceDevIdentityConfig missing = ReferenceDevIdentityConfig.fromEnvironment(Map.of());
        ReferenceDevIdentityConfig explicit = ReferenceDevIdentityConfig.fromEnvironment(Map.of(
                ReferenceDevIdentityConfig.ENABLED, "false",
                ReferenceDevIdentityConfig.BEARER_TOKEN, "short"));

        assertFalse(missing.enabled());
        assertFalse(explicit.enabled());
        assertFalse(ReferenceDevIdentityConfig.class.isRecord());
        assertEquals(
                "ReferenceDevIdentityConfig{enabled=false, credential=REDACTED, identity=REDACTED}",
                missing.toString());
        assertFalse(missing.toString().contains("short"));
        assertThrows(IllegalStateException.class, missing::copyCredentialDigest);
    }

    @Test
    void enabledConfigStoresOnlyDigestAndStableIdentityClaims() throws Exception {
        Map<String, String> environment = validEnvironment(TOKEN_VALUE);
        ReferenceDevIdentityConfig config = ReferenceDevIdentityConfig.fromEnvironment(environment);

        byte[] raw = TOKEN_VALUE.getBytes(StandardCharsets.UTF_8);
        byte[] expectedDigest = MessageDigest.getInstance("SHA-256").digest(raw);
        byte[] firstCopy = config.copyCredentialDigest();
        assertTrue(config.enabled());
        assertArrayEquals(expectedDigest, firstCopy);
        assertNotEquals(new String(raw, StandardCharsets.UTF_8),
                new String(firstCopy, StandardCharsets.ISO_8859_1));
        Arrays.fill(firstCopy, (byte) 0);
        assertArrayEquals(expectedDigest, config.copyCredentialDigest(),
                "调用方修改摘要副本不能影响配置内部摘要");
        assertEquals("stable-subject", config.subject().subjectScope());
        assertEquals("organization-a", config.subject().organizationScope());
        assertEquals(Set.of("REVIEWER", "USER"), config.roles());
        assertEquals(Set.of("meeting:create", "meeting:stop"), config.permissions());
        assertEquals("ai-meeting-reference", config.audience());
        assertEquals("android-reference-app", config.clientId());
        assertEquals(Instant.parse("2026-09-17T00:00:00Z"), config.issuedAt());
        assertEquals(Instant.parse("2026-09-17T01:00:00Z"), config.expiresAt());
        assertEquals("reference-credential-v1", config.credentialId());

        String diagnostic = config.toString();
        assertFalse(diagnostic.contains(TOKEN_VALUE));
        assertFalse(diagnostic.contains("stable-subject"));
        assertFalse(diagnostic.contains(toHex(expectedDigest)));
    }

    @Test
    void tokenLengthUsesUtf8BytesAndEnforcesClosedBounds() {
        assertTrue(ReferenceDevIdentityConfig.fromEnvironment(
                validEnvironment("a".repeat(32))).enabled());
        assertTrue(ReferenceDevIdentityConfig.fromEnvironment(
                validEnvironment("a".repeat(4_096))).enabled());
        assertTrue(ReferenceDevIdentityConfig.fromEnvironment(
                validEnvironment("中".repeat(11))).enabled(),
                "11 个中文字符是 33 个 UTF-8 字节，达到最小边界");

        assertSafeFailure(validEnvironment("a".repeat(31)),
                ReferenceDevIdentityConfig.BEARER_TOKEN);
        assertSafeFailure(validEnvironment("a".repeat(4_097)),
                ReferenceDevIdentityConfig.BEARER_TOKEN);
        assertSafeFailure(validEnvironment("中".repeat(10)),
                ReferenceDevIdentityConfig.BEARER_TOKEN);
    }

    @Test
    void enabledConfigurationFailsFastWithoutLeakingValues() {
        Map<String, String> invalidFlag = validEnvironment(TOKEN_VALUE);
        invalidFlag.put(ReferenceDevIdentityConfig.ENABLED, "yes");
        assertSafeFailure(invalidFlag, ReferenceDevIdentityConfig.ENABLED);
        for (String nonLiteral : Set.of("TRUE", "False", "1")) {
            Map<String, String> invalidCase = validEnvironment(TOKEN_VALUE);
            invalidCase.put(ReferenceDevIdentityConfig.ENABLED, nonLiteral);
            assertSafeFailure(invalidCase, ReferenceDevIdentityConfig.ENABLED);
        }

        for (String required : Set.of(
                ReferenceDevIdentityConfig.BEARER_TOKEN,
                ReferenceDevIdentityConfig.SUBJECT_SCOPE,
                ReferenceDevIdentityConfig.ROLES,
                ReferenceDevIdentityConfig.PERMISSIONS,
                ReferenceDevIdentityConfig.AUDIENCE,
                ReferenceDevIdentityConfig.CLIENT_ID,
                ReferenceDevIdentityConfig.ISSUED_AT,
                ReferenceDevIdentityConfig.EXPIRES_AT,
                ReferenceDevIdentityConfig.CREDENTIAL_ID)) {
            Map<String, String> missing = validEnvironment(TOKEN_VALUE);
            missing.remove(required);
            assertSafeFailure(missing, required);
        }

        Map<String, String> blankOrganization = validEnvironment(TOKEN_VALUE);
        blankOrganization.put(ReferenceDevIdentityConfig.ORGANIZATION_SCOPE, "  ");
        assertSafeFailure(blankOrganization, ReferenceDevIdentityConfig.ORGANIZATION_SCOPE);

        Map<String, String> emptyRole = validEnvironment(TOKEN_VALUE);
        emptyRole.put(ReferenceDevIdentityConfig.ROLES, "USER,,REVIEWER");
        assertSafeFailure(emptyRole, ReferenceDevIdentityConfig.ROLES);

        Map<String, String> duplicatePermission = validEnvironment(TOKEN_VALUE);
        duplicatePermission.put(
                ReferenceDevIdentityConfig.PERMISSIONS, "meeting:create,meeting:create");
        assertSafeFailure(duplicatePermission, ReferenceDevIdentityConfig.PERMISSIONS);

        Map<String, String> badIssuedAt = validEnvironment(TOKEN_VALUE);
        badIssuedAt.put(ReferenceDevIdentityConfig.ISSUED_AT, "not-an-instant");
        assertSafeFailure(badIssuedAt, ReferenceDevIdentityConfig.ISSUED_AT);

        Map<String, String> reversedWindow = validEnvironment(TOKEN_VALUE);
        reversedWindow.put(ReferenceDevIdentityConfig.EXPIRES_AT, "2026-09-16T23:59:59Z");
        assertSafeFailure(reversedWindow, ReferenceDevIdentityConfig.EXPIRES_AT);
    }

    static Map<String, String> validEnvironment(String token) {
        Map<String, String> values = new HashMap<>();
        values.put(ReferenceDevIdentityConfig.ENABLED, "true");
        values.put(ReferenceDevIdentityConfig.BEARER_TOKEN, token);
        values.put(ReferenceDevIdentityConfig.SUBJECT_SCOPE, "stable-subject");
        values.put(ReferenceDevIdentityConfig.ORGANIZATION_SCOPE, "organization-a");
        values.put(ReferenceDevIdentityConfig.ROLES, "USER,REVIEWER");
        values.put(ReferenceDevIdentityConfig.PERMISSIONS, "meeting:create,meeting:stop");
        values.put(ReferenceDevIdentityConfig.AUDIENCE, "ai-meeting-reference");
        values.put(ReferenceDevIdentityConfig.CLIENT_ID, "android-reference-app");
        values.put(ReferenceDevIdentityConfig.ISSUED_AT, "2026-09-17T00:00:00Z");
        values.put(ReferenceDevIdentityConfig.EXPIRES_AT, "2026-09-17T01:00:00Z");
        values.put(ReferenceDevIdentityConfig.CREDENTIAL_ID, "reference-credential-v1");
        return values;
    }

    private static void assertSafeFailure(Map<String, String> environment, String field) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ReferenceDevIdentityConfig.fromEnvironment(environment));
        assertTrue(failure.getMessage().contains(field));
        for (String sensitive : Set.of(
                TOKEN_VALUE,
                "stable-subject",
                "organization-a",
                "reference-credential-v1")) {
            assertFalse(failure.getMessage().contains(sensitive));
        }
    }

    private static String toHex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }
}
