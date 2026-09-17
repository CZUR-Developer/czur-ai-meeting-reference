package com.czur.reference.application.identity.referencedev;

import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定凭据 Provider 的验证顺序、边界、并发和日志脱敏测试。 */
class ReferenceDevIdentityProviderTest {
    private static final String TOKEN = "local-reference-token-0123456789-abcdef";
    private static final Instant ISSUED_AT = Instant.parse("2026-09-17T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-17T01:00:00Z");

    @Test
    void validBearerReturnsConfiguredStableIdentityAndDoesNotCloseSecret() {
        ReferenceDevIdentityProvider provider = providerAt(ISSUED_AT);
        SecretCredential credential = secret(TOKEN);
        try {
            IdentityProviderResult<VerifiedIdentity> first = provider.verifyCredential(request(
                    credential, "ai-meeting-reference", "android-reference-app"));
            IdentityProviderResult<VerifiedIdentity> second = verify(provider, TOKEN);

            assertTrue(first.isSuccess());
            assertFalse(credential.isDestroyed(), "Provider 不能关闭调用方持有的 Secret");
            VerifiedIdentity identity = first.valueOrThrow();
            assertEquals("stable-subject", identity.subject().subjectScope());
            assertEquals("organization-a", identity.subject().organizationScope());
            assertEquals(second.valueOrThrow().subject(), identity.subject());
            assertEquals(ISSUED_AT, identity.issuedAt());
            assertEquals(EXPIRES_AT, identity.expiresAt());
            assertEquals("reference-credential-v1", identity.credentialId());
            assertTrue(identity.hasPermission("meeting:create"));
            assertTrue(identity.hasPermission("meeting:stop"));
        } finally {
            credential.close();
        }
    }

    @Test
    void rejectsWrongDifferentLengthPrefixAndSuffixTokensWithOneStableError() {
        ReferenceDevIdentityProvider provider = providerAt(ISSUED_AT.plusSeconds(1));
        for (String invalid : List.of(
                "local-reference-token-0123456789-abcdeg",
                "short-but-definitely-wrong",
                "prefix-" + TOKEN,
                TOKEN + "-suffix")) {
            assertError(verify(provider, invalid), IdentityProviderError.Code.INVALID_CREDENTIAL);
        }

        SecretCredential destroyed = secret(TOKEN);
        destroyed.close();
        assertError(provider.verifyCredential(request(
                        destroyed, "ai-meeting-reference", "android-reference-app")),
                IdentityProviderError.Code.INVALID_CREDENTIAL);
    }

    @Test
    void validatesAudienceAndClientOnlyAfterTokenMatches() {
        ReferenceDevIdentityProvider provider = providerAt(ISSUED_AT.plusSeconds(1));

        assertError(verify(provider, TOKEN, "other-audience", "android-reference-app"),
                IdentityProviderError.Code.INVALID_CREDENTIAL);
        assertError(verify(provider, TOKEN, "ai-meeting-reference", "other-client"),
                IdentityProviderError.Code.INVALID_CREDENTIAL);
        assertError(verify(provider, "wrong-token-value-with-enough-bytes-0000",
                        "other-audience", "other-client"),
                IdentityProviderError.Code.INVALID_CREDENTIAL);

        try (SecretCredential credential = secret(TOKEN)) {
            IdentityVerificationRequest deviceAssertion = new IdentityVerificationRequest(
                    IdentityVerificationRequest.CredentialType.DEVICE_ASSERTION,
                    credential, "ai-meeting-reference", "android-reference-app");
            assertError(provider.verifyCredential(deviceAssertion),
                    IdentityProviderError.Code.INVALID_REQUEST);
        }
        assertError(provider.verifyCredential(null), IdentityProviderError.Code.INVALID_REQUEST);
    }

    @Test
    void usesInjectedClockForNotActiveAndClosedExpiryBoundary() {
        assertError(verify(providerAt(ISSUED_AT.minusNanos(1)), TOKEN),
                IdentityProviderError.Code.INVALID_CREDENTIAL);
        assertTrue(verify(providerAt(ISSUED_AT), TOKEN).isSuccess());
        assertTrue(verify(providerAt(EXPIRES_AT.minusNanos(1)), TOKEN).isSuccess());
        assertError(verify(providerAt(EXPIRES_AT), TOKEN),
                IdentityProviderError.Code.CREDENTIAL_EXPIRED);
        assertError(verify(providerAt(EXPIRES_AT.plusSeconds(1)), TOKEN),
                IdentityProviderError.Code.CREDENTIAL_EXPIRED);

        // 即使配置已经过期，错误 Token 也不能通过错误码探测到时间窗。
        assertError(verify(providerAt(EXPIRES_AT.plusSeconds(1)),
                        "wrong-token-value-with-enough-bytes-0000"),
                IdentityProviderError.Code.INVALID_CREDENTIAL);
    }

    @Test
    void disabledProviderReturnsStableErrorWithoutConsumingSecret() {
        ReferenceDevIdentityConfig disabled = ReferenceDevIdentityConfig.fromEnvironment(Map.of());
        ReferenceDevIdentityProvider provider = new ReferenceDevIdentityProvider(
                disabled, Clock.fixed(ISSUED_AT, ZoneOffset.UTC));
        SecretCredential credential = secret(TOKEN);
        try {
            assertError(provider.verifyCredential(request(
                            credential, "ai-meeting-reference", "android-reference-app")),
                    IdentityProviderError.Code.IDENTITY_DISABLED);
            assertError(provider.verifyCredential(null),
                    IdentityProviderError.Code.IDENTITY_DISABLED);
            assertFalse(credential.isDestroyed());
            assertEquals(TOKEN.length(),
                    credential.useBytes(value -> value.length).intValue());
        } finally {
            credential.close();
        }
    }

    @Test
    void concurrentVerificationUsesIndependentDigestInstances() throws Exception {
        ReferenceDevIdentityProvider provider = providerAt(ISSUED_AT.plusSeconds(1));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<IdentityProviderResult<VerifiedIdentity>>> futures = new ArrayList<>();
            List<Boolean> expectedSuccess = new ArrayList<>();
            for (int index = 0; index < 128; index++) {
                boolean valid = index % 2 == 0;
                expectedSuccess.add(valid);
                String candidate = valid
                        ? TOKEN
                        : "wrong-token-value-with-enough-bytes-0000";
                futures.add(executor.submit(() -> verify(provider, candidate)));
            }
            for (int index = 0; index < futures.size(); index++) {
                Future<IdentityProviderResult<VerifiedIdentity>> future = futures.get(index);
                IdentityProviderResult<VerifiedIdentity> result = future.get();
                if (expectedSuccess.get(index)) {
                    assertTrue(result.isSuccess());
                    assertEquals("stable-subject", result.valueOrThrow().subject().subjectScope());
                } else {
                    assertError(result, IdentityProviderError.Code.INVALID_CREDENTIAL);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void logsNeverContainCredentialDigestSubjectOrRequestContents() throws Exception {
        Logger logger = Logger.getLogger(ReferenceDevIdentityProvider.class.getName());
        Level oldLevel = logger.getLevel();
        boolean oldUseParentHandlers = logger.getUseParentHandlers();
        List<String> messages = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String message = record.getMessage();
                Object[] parameters = record.getParameters();
                messages.add(parameters == null
                        ? message
                        : java.text.MessageFormat.format(message, parameters));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(capture);
        try {
            ReferenceDevIdentityProvider provider = providerAt(ISSUED_AT.plusSeconds(1));
            assertTrue(verify(provider, TOKEN).isSuccess());
            assertError(verify(provider, "wrong-token-value-with-enough-bytes-0000"),
                    IdentityProviderError.Code.INVALID_CREDENTIAL);
            assertError(verify(provider, TOKEN, "other-audience", "other-client"),
                    IdentityProviderError.Code.INVALID_CREDENTIAL);
        } finally {
            logger.removeHandler(capture);
            logger.setUseParentHandlers(oldUseParentHandlers);
            logger.setLevel(oldLevel);
        }

        String allLogs = String.join("\n", messages);
        byte[] digestBytes = MessageDigest.getInstance("SHA-256")
                .digest(TOKEN.getBytes(StandardCharsets.UTF_8));
        String digest = HexFormat.of().formatHex(digestBytes);
        String digestBase64 = Base64.getEncoder().encodeToString(digestBytes);
        for (String forbidden : List.of(
                TOKEN,
                digest,
                digestBase64,
                "stable-subject",
                "organization-a",
                "ai-meeting-reference",
                "android-reference-app",
                "other-audience",
                "other-client",
                "reference-credential-v1",
                "IdentityVerificationRequest[")) {
            assertFalse(allLogs.contains(forbidden), "日志泄露了禁止字段：" + forbidden);
        }
        assertTrue(allLogs.contains("TOKEN_MISMATCH"));
        assertTrue(allLogs.contains("AUDIENCE_MISMATCH"));
    }

    private static ReferenceDevIdentityProvider providerAt(Instant now) {
        ReferenceDevIdentityConfig config = ReferenceDevIdentityConfig.fromEnvironment(
                ReferenceDevIdentityConfigTest.validEnvironment(TOKEN));
        return new ReferenceDevIdentityProvider(config, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static IdentityProviderResult<VerifiedIdentity> verify(
            ReferenceDevIdentityProvider provider, String token) {
        return verify(provider, token, "ai-meeting-reference", "android-reference-app");
    }

    private static IdentityProviderResult<VerifiedIdentity> verify(
            ReferenceDevIdentityProvider provider,
            String token,
            String audience,
            String clientId) {
        try (SecretCredential credential = secret(token)) {
            return provider.verifyCredential(request(credential, audience, clientId));
        }
    }

    private static IdentityVerificationRequest request(
            SecretCredential credential, String audience, String clientId) {
        return new IdentityVerificationRequest(
                IdentityVerificationRequest.CredentialType.BEARER_TOKEN,
                credential,
                audience,
                clientId);
    }

    private static SecretCredential secret(String value) {
        return SecretCredential.copyOf(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertError(
            IdentityProviderResult<VerifiedIdentity> result,
            IdentityProviderError.Code expected) {
        assertFalse(result.isSuccess());
        assertEquals(expected, result.errorOrThrow().code());
        assertFalse(result.errorOrThrow().retryable());
    }
}
