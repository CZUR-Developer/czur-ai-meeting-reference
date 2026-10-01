package com.czur.reference.application.http;

import com.czur.reference.application.command.AuthenticatedRealtimeTokenRenewApplicationService;
import com.czur.reference.application.port.RenewRealtimeTokenUnitOfWork;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 续期 HTTP 只按稳定身份/事务错误码映射，不推断异常或泄漏 Grant。 */
class RealtimeTokenRenewHttpErrorMapperTest {
    private static final HttpErrorMapper MAPPER = new HttpErrorMapper(() -> "opaque-diagnostic");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void persistedSuccessIsReturnedByteForByteWithoutCaching() {
        String json = "{\"meetingId\":\"mtg_test\",\"streamId\":\"mtg_test\","
                + "\"sessionToken\":\"opaque-private-token\","
                + "\"expiresAt\":\"2026-10-01T10:01:00Z\"}";
        var response = MAPPER.renew(new AuthenticatedRealtimeTokenRenewApplicationService.Renewal(
                new RenewRealtimeTokenUnitOfWork.Success(
                        RenewRealtimeTokenUnitOfWork.SuccessCode.REPLAYED, json)));
        assertEquals(200, response.status());
        assertTrue(json.equals(response.body()),
                "persisted Grant must be emitted byte-for-byte without logging its Token");
        assertEquals("no-store", response.headers().get("cache-control"));
        assertFalse(response.toString().contains("opaque-private-token"));
    }

    @Test
    void everyStorageFailureHasStableHttpStatusAndRetryability() throws Exception {
        Map<RenewRealtimeTokenUnitOfWork.ErrorCode, Integer> statuses = Map.of(
                RenewRealtimeTokenUnitOfWork.ErrorCode.NOT_FOUND, 404,
                RenewRealtimeTokenUnitOfWork.ErrorCode.ACCESS_DENIED, 403,
                RenewRealtimeTokenUnitOfWork.ErrorCode.STATE_CONFLICT, 409,
                RenewRealtimeTokenUnitOfWork.ErrorCode.FACT_INCONSISTENT, 500,
                RenewRealtimeTokenUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE, 503,
                RenewRealtimeTokenUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, 503,
                RenewRealtimeTokenUnitOfWork.ErrorCode.INTERNAL, 500);
        assertEquals(Map.copyOf(statuses).keySet(),
                java.util.Set.of(RenewRealtimeTokenUnitOfWork.ErrorCode.values()));
        for (var entry : statuses.entrySet()) {
            var response = MAPPER.renew(
                    new AuthenticatedRealtimeTokenRenewApplicationService.Renewal(
                            new RenewRealtimeTokenUnitOfWork.Failure(entry.getKey())));
            assertEquals(entry.getValue(), response.status());
            var error = JSON.readTree(response.body()).path("error");
            assertEquals(entry.getKey().retryable(), error.path("retryable").booleanValue());
            assertFalse(response.body().contains("sessionToken"));
        }
    }

    @Test
    void identityFailuresAndUnexpectedServiceFailureUseFixedEnvelope() throws Exception {
        Map<IdentityProviderError.Code, Integer> statuses = Map.of(
                IdentityProviderError.Code.INVALID_REQUEST, 400,
                IdentityProviderError.Code.INVALID_CREDENTIAL, 401,
                IdentityProviderError.Code.CREDENTIAL_EXPIRED, 401,
                IdentityProviderError.Code.IDENTITY_DISABLED, 401,
                IdentityProviderError.Code.PERMISSION_DENIED, 403,
                IdentityProviderError.Code.PROVIDER_UNAVAILABLE, 503,
                IdentityProviderError.Code.INTERNAL, 500);
        for (var entry : statuses.entrySet()) {
            var response = MAPPER.renew(
                    new AuthenticatedRealtimeTokenRenewApplicationService.IdentityFailure(
                            new IdentityProviderError(entry.getKey(), false)));
            assertEquals(entry.getValue(), response.status());
            assertFalse(response.body().contains("opaque-private-token"));
        }
        var internal = MAPPER.renew(
                new AuthenticatedRealtimeTokenRenewApplicationService.InternalFailure());
        assertEquals(500, internal.status());
        assertTrue(internal.body().contains("INTERNAL_ERROR"));
    }
}
