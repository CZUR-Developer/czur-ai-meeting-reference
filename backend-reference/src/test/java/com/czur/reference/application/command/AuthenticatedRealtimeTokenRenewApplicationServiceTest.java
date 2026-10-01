package com.czur.reference.application.command;

import com.czur.reference.application.port.RenewRealtimeTokenUnitOfWork;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 续期认证编排的凭据生命周期与失败关闭式契约；不访问网络或数据库。 */
class AuthenticatedRealtimeTokenRenewApplicationServiceTest {
    private static final String MEETING = "mtg_renew_service";
    private static final String KEY = "renew-service-key-0001";

    @Test
    void verifiedOwnerIsPassedToPersistenceAndCredentialIsAlwaysDestroyed() {
        AtomicReference<RenewRealtimeTokenUnitOfWork.Request> seen = new AtomicReference<>();
        var service = service(request -> IdentityProviderResult.success(identity()), request -> {
            seen.set(request);
            return new RenewRealtimeTokenUnitOfWork.Failure(
                    RenewRealtimeTokenUnitOfWork.ErrorCode.STATE_CONFLICT);
        });
        SecretCredential credential = credential();
        var result = service.renew(verification(credential), MEETING, KEY);
        var renewal = assertInstanceOf(
                AuthenticatedRealtimeTokenRenewApplicationService.Renewal.class, result);
        assertEquals(RenewRealtimeTokenUnitOfWork.ErrorCode.STATE_CONFLICT,
                assertInstanceOf(RenewRealtimeTokenUnitOfWork.Failure.class,
                        renewal.result()).code());
        assertEquals("deployment", seen.get().context().deploymentScope());
        assertEquals("subject", seen.get().context().identity().subject().subjectScope());
        assertEquals(KEY, seen.get().idempotencyKey());
        assertTrue(credential.isDestroyed());
    }

    @Test
    void deniedOrThrowingIdentityCannotCallPersistenceAndDestroysCredential() {
        AtomicInteger calls = new AtomicInteger();
        RenewRealtimeTokenUnitOfWork renewal = request -> {
            calls.incrementAndGet();
            throw new AssertionError("unverified identity reached persistence");
        };
        SecretCredential denied = credential();
        var deniedResult = service(request -> IdentityProviderResult.failure(
                new IdentityProviderError(
                        IdentityProviderError.Code.INVALID_CREDENTIAL, false)), renewal)
                .renew(verification(denied), MEETING, KEY);
        assertInstanceOf(AuthenticatedRealtimeTokenRenewApplicationService.IdentityFailure.class,
                deniedResult);
        assertTrue(denied.isDestroyed());

        SecretCredential thrown = credential();
        var thrownResult = service(request -> {
            throw new IllegalStateException("provider secret must not escape");
        }, renewal).renew(verification(thrown), MEETING, KEY);
        assertInstanceOf(AuthenticatedRealtimeTokenRenewApplicationService.InternalFailure.class,
                thrownResult);
        assertTrue(thrown.isDestroyed());
        assertEquals(0, calls.get());
    }

    @Test
    void throwingPersistenceBecomesInternalFailureAndDestroysCredential() {
        SecretCredential credential = credential();
        var result = service(request -> IdentityProviderResult.success(identity()), request -> {
            throw new IllegalArgumentException("database secret must not escape");
        }).renew(verification(credential), MEETING, KEY);
        assertInstanceOf(AuthenticatedRealtimeTokenRenewApplicationService.InternalFailure.class,
                result);
        assertTrue(credential.isDestroyed());
    }

    private static AuthenticatedRealtimeTokenRenewApplicationService service(
            IdentityProvider identity, RenewRealtimeTokenUnitOfWork renewal) {
        return new AuthenticatedRealtimeTokenRenewApplicationService(
                "deployment", identity, renewal);
    }

    private static VerifiedIdentity identity() {
        return new VerifiedIdentity(new Subject("subject"), Set.of(), Set.of(),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2027-01-01T00:00:00Z"), null);
    }

    private static SecretCredential credential() {
        return SecretCredential.copyOf("user-bearer-secret".getBytes(StandardCharsets.UTF_8));
    }

    private static IdentityVerificationRequest verification(SecretCredential credential) {
        return new IdentityVerificationRequest(
                IdentityVerificationRequest.CredentialType.BEARER_TOKEN,
                credential, "audience", "client");
    }
}
