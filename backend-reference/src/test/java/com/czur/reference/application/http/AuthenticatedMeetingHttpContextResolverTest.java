package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingPermissions;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不启动网络，只验证读取/删除上下文的认证、权限和凭据生命周期。 */
class AuthenticatedMeetingHttpContextResolverTest {
    private static final String TOKEN = "secret.private+token==";
    private static final HttpErrorMapper ERRORS = new HttpErrorMapper(() -> "identity-diag");

    @Test
    void missingOrMalformedBearerIs401WithoutCallingProvider() {
        RecordingProvider provider = new RecordingProvider();
        var resolver = resolver(provider);
        assertEquals(401, rejected(resolver.forRead(read(Map.of()))).status());
        for (String bad : new String[]{"Basic abc", "Bearer  a b", "Bearer a@b"}) {
            assertEquals(401, rejected(resolver.forRead(read(Map.of(
                    "Authorization", bad)))).status());
        }
        assertEquals(0, provider.calls);
    }

    @Test
    void invalidTokenReturns401AndClosesCredential() {
        RecordingProvider provider = new RecordingProvider();
        provider.result = IdentityProviderResult.failure(new IdentityProviderError(
                IdentityProviderError.Code.INVALID_CREDENTIAL, false));
        var result = resolver(provider).forRead(read(auth()));
        assertEquals(401, rejected(result).status());
        assertEquals(1, provider.calls);
        assertEquals("trusted-audience", provider.request.audience());
        assertEquals("trusted-client", provider.request.clientId());
        assertEquals(TOKEN, provider.seenToken);
        assertTrue(provider.request.credential().isDestroyed());
        assertFalse(result.toString().contains(TOKEN));
    }

    @Test
    void trustedDeploymentAndVerifiedSubjectCannotBeOverriddenByRequest() {
        RecordingProvider provider = new RecordingProvider();
        provider.result = IdentityProviderResult.success(identity(Set.of()));
        HttpRequest request = new HttpRequest("GET", "/v1/meetings",
                Map.of("Authorization", "Bearer " + TOKEN,
                        "X-Deployment-Scope", "untrusted-deployment"),
                Map.of("deploymentScope", "client-value"), "");
        var result = assertInstanceOf(AuthenticatedMeetingHttpContextResolver.Ready.class,
                resolver(provider).forRead(request));
        assertEquals("trusted-deployment", result.context().deploymentScope());
        assertEquals("stable-subject", result.context().identity().subject().subjectScope());
        assertTrue(provider.request.credential().isDestroyed());
        assertFalse(result.toString().contains("stable-subject"));
        assertFalse(result.toString().contains(TOKEN));
    }

    @Test
    void deleteChecksPermissionBeforeReturningContext() {
        RecordingProvider denied = new RecordingProvider();
        denied.result = IdentityProviderResult.success(identity(Set.of()));
        assertEquals(403, rejected(resolver(denied).forDelete(delete(auth()))).status());
        assertTrue(denied.request.credential().isDestroyed());

        RecordingProvider permitted = new RecordingProvider();
        permitted.result = IdentityProviderResult.success(
                identity(Set.of(MeetingPermissions.DELETE)));
        var ready = assertInstanceOf(AuthenticatedMeetingHttpContextResolver.Ready.class,
                resolver(permitted).forDelete(delete(auth())));
        assertTrue(ready.context().identity().hasPermission(MeetingPermissions.DELETE));
        assertTrue(permitted.request.credential().isDestroyed());
        assertEquals(400, rejected(resolver(permitted).forDelete(read(auth()))).status());
        assertEquals(1, permitted.calls);
    }

    @Test
    void providerUnavailableInternalAndThrownExceptionsAreStableAndCloseCredential() {
        for (var scenario : new Object[][]{
                {IdentityProviderError.Code.PROVIDER_UNAVAILABLE, 503},
                {IdentityProviderError.Code.INTERNAL, 500},
                {IdentityProviderError.Code.PERMISSION_DENIED, 403},
                {IdentityProviderError.Code.INVALID_REQUEST, 400}}) {
            RecordingProvider provider = new RecordingProvider();
            provider.result = IdentityProviderResult.failure(new IdentityProviderError(
                    (IdentityProviderError.Code) scenario[0], false));
            assertEquals((int) scenario[1],
                    rejected(resolver(provider).forRead(read(auth()))).status());
            assertTrue(provider.request.credential().isDestroyed());
        }
        RecordingProvider throwing = new RecordingProvider();
        throwing.failure = new IllegalStateException("PRIVATE_PROVIDER_DETAIL");
        HttpResponse response = rejected(resolver(throwing).forRead(read(auth())));
        assertEquals(500, response.status());
        assertFalse(response.body().contains("PRIVATE_PROVIDER_DETAIL"));
        assertTrue(throwing.request.credential().isDestroyed());

        RecordingProvider nullResult = new RecordingProvider();
        assertEquals(500, rejected(resolver(nullResult).forRead(read(auth()))).status());
        assertTrue(nullResult.request.credential().isDestroyed());
    }

    @Test
    void errorLogsNeverIncludeTokenBodyOrFullUrl() {
        RecordingProvider provider = new RecordingProvider();
        provider.result = IdentityProviderResult.success(identity(Set.of()));
        String url = "/v1/meetings/mtg_private?secret=PRIVATE_URL_SENTINEL";
        String body = "PRIVATE_BODY_SENTINEL";
        HttpRequest request = new HttpRequest("DELETE", "/v1/meetings/mtg_private",
                Map.of("Authorization", "Bearer " + TOKEN),
                Map.of("secret", "PRIVATE_URL_SENTINEL"), body);
        CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        SimpleFormatter formatter = new SimpleFormatter();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) {
                messages.add(formatter.formatMessage(record));
            }
            @Override public void flush() {
            }
            @Override public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(capture);
        HttpResponse response;
        try {
            response = rejected(resolver(provider).forDelete(request));
        } finally {
            root.removeHandler(capture);
        }
        assertEquals(403, response.status());
        assertNotNull(provider.request);
        assertTrue(provider.request.credential().isDestroyed());
        String logs = String.join("\n", messages);
        assertFalse(messages.isEmpty());
        for (String secret : new String[]{TOKEN, body, url, "PRIVATE_URL_SENTINEL"}) {
            assertFalse(logs.contains(secret));
            assertFalse(response.body().contains(secret));
        }
    }

    @Test
    void missingTrustedConfigurationFailsAtConstruction() {
        RecordingProvider provider = new RecordingProvider();
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticatedMeetingHttpContextResolver(
                        " ", "audience", "client", provider, ERRORS));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticatedMeetingHttpContextResolver(
                        "scope", "", "client", provider, ERRORS));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticatedMeetingHttpContextResolver(
                        "scope", "audience", null, provider, ERRORS));
    }

    private static AuthenticatedMeetingHttpContextResolver resolver(IdentityProvider provider) {
        return new AuthenticatedMeetingHttpContextResolver("trusted-deployment",
                "trusted-audience", "trusted-client", provider, ERRORS);
    }

    private static Map<String, String> auth() {
        return Map.of("Authorization", "Bearer " + TOKEN);
    }

    private static HttpRequest read(Map<String, String> headers) {
        return new HttpRequest("GET", "/v1/meetings", headers, "");
    }

    private static HttpRequest delete(Map<String, String> headers) {
        return new HttpRequest("DELETE", "/v1/meetings/mtg_one", headers, "");
    }

    private static HttpResponse rejected(AuthenticatedMeetingHttpContextResolver.Resolution result) {
        return assertInstanceOf(AuthenticatedMeetingHttpContextResolver.Rejected.class,
                result).response();
    }

    private static VerifiedIdentity identity(Set<String> permissions) {
        return new VerifiedIdentity(new Subject("stable-subject"), Set.of(), permissions,
                Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-02T00:00:00Z"), "credential-id");
    }

    private static final class RecordingProvider implements IdentityProvider {
        private IdentityProviderResult<VerifiedIdentity> result;
        private RuntimeException failure;
        private IdentityVerificationRequest request;
        private String seenToken;
        private int calls;

        @Override
        public IdentityProviderResult<VerifiedIdentity> verifyCredential(
                IdentityVerificationRequest verification) {
            calls++;
            request = verification;
            seenToken = verification.credential().useBytes(
                    bytes -> new String(bytes, StandardCharsets.US_ASCII));
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
