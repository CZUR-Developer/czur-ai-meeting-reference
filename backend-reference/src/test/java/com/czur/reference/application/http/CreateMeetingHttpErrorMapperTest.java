package com.czur.reference.application.http;

import com.czur.reference.application.command.AuthenticatedCompleteMeetingCreateResult;
import com.czur.reference.application.command.CompleteMeetingResponseAssembler;
import com.czur.reference.application.command.CreateMeetingRequestSnapshot;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.port.CompleteMeetingCreationUnitOfWork;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.domain.meeting.MeetingState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 完整 Create 的 HTTP 状态、公开投影与固定错误 envelope 门禁。 */
class CreateMeetingHttpErrorMapperTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpErrorMapper MAPPER = new HttpErrorMapper(() -> "opaque-diag");

    @Test
    void firstAndReplayedSuccessUseSame201PublicBody() throws Exception {
        var fact = responseFact();
        HttpResponse first = MAPPER.create(new AuthenticatedCompleteMeetingCreateResult.Success(
                AuthenticatedCompleteMeetingCreateResult.SuccessCode.CREATED, fact));
        HttpResponse replay = MAPPER.create(new AuthenticatedCompleteMeetingCreateResult.Success(
                AuthenticatedCompleteMeetingCreateResult.SuccessCode.REPLAYED, fact));
        assertEquals(201, first.status());
        assertEquals(first.body(), replay.body());
        assertEquals(CreateMeetingPublicResponseJson.encode(fact), first.body());
        assertEquals("mtg_create_http", JSON.readTree(first.body()).path("meetingId").asText());
        assertFalse(first.body().contains("schemaVersion"));
        assertFalse(first.body().contains("planRevision"));
        assertFalse(first.toString().contains("private-realtime-token"));
    }

    @Test
    void identityAndDecodeFailuresKeepAuthenticationDistinctFromBadContract() throws Exception {
        Map<IdentityProviderError.Code, Integer> statuses = Map.of(
                IdentityProviderError.Code.INVALID_REQUEST, 400,
                IdentityProviderError.Code.INVALID_CREDENTIAL, 401,
                IdentityProviderError.Code.CREDENTIAL_EXPIRED, 401,
                IdentityProviderError.Code.IDENTITY_DISABLED, 401,
                IdentityProviderError.Code.PERMISSION_DENIED, 403,
                IdentityProviderError.Code.PROVIDER_UNAVAILABLE, 503,
                IdentityProviderError.Code.INTERNAL, 500);
        for (var entry : statuses.entrySet()) {
            for (boolean retryable : List.of(false, true)) {
                HttpResponse mapped = MAPPER.create(new AuthenticatedCompleteMeetingCreateResult
                        .IdentityFailure(new IdentityProviderError(entry.getKey(), retryable)));
                assertEquals(retryable, assertError(mapped, entry.getValue())
                        .path("retryable").asBoolean());
            }
        }
        var decoder = new CreateMeetingHttpRequestDecoder("audience", "client");
        CreateMeetingHttpRequestDecoder.DecodeFailure missingBearer;
        try {
            decoder.decode(new HttpRequest("POST", "/v1/meetings",
                    Map.of("Content-Type", "text/plain"), "not json"));
            throw new AssertionError("missing Bearer must fail");
        } catch (CreateMeetingHttpRequestDecoder.DecodeFailure failure) {
            missingBearer = failure;
        }
        assertError(MAPPER.exception(missingBearer), 401);
        assertError(MAPPER.exception(new IllegalArgumentException("private-body-token")), 400);
    }

    @Test
    void entitlementCodesHaveClosedStatusMatrixAndRespectRetryableBit() throws Exception {
        Map<EntitlementProviderError.Code, Integer> statuses = Map.ofEntries(
                Map.entry(EntitlementProviderError.Code.INVALID_REQUEST, 400),
                Map.entry(EntitlementProviderError.Code.PERMISSION_DENIED, 403),
                Map.entry(EntitlementProviderError.Code.ENTITLEMENT_DENIED, 422),
                Map.entry(EntitlementProviderError.Code.CAPABILITY_NOT_GRANTED, 422),
                Map.entry(EntitlementProviderError.Code.GRANT_EXPIRED, 422),
                Map.entry(EntitlementProviderError.Code.RESERVATION_EXPIRED, 409),
                Map.entry(EntitlementProviderError.Code.RESERVATION_NOT_FOUND, 409),
                Map.entry(EntitlementProviderError.Code.RESERVATION_SCOPE_MISMATCH, 409),
                Map.entry(EntitlementProviderError.Code.RESERVATION_STATE_CONFLICT, 409),
                Map.entry(EntitlementProviderError.Code.VERSION_CONFLICT, 409),
                Map.entry(EntitlementProviderError.Code.IDEMPOTENCY_CONFLICT, 409),
                Map.entry(EntitlementProviderError.Code.PROVIDER_UNAVAILABLE, 503),
                Map.entry(EntitlementProviderError.Code.INTERNAL, 500));
        assertEquals(Set.of(EntitlementProviderError.Code.values()), statuses.keySet());
        for (var entry : statuses.entrySet()) {
            for (boolean retryable : List.of(false, true)) {
                var result = new AuthenticatedCompleteMeetingCreateResult.EntitlementFailure(
                        new EntitlementProviderError(entry.getKey(), retryable));
                HttpResponse mapped = MAPPER.create(result);
                JsonNode error = assertError(mapped, entry.getValue());
                assertEquals(retryable, error.path("retryable").asBoolean());
            }
        }
    }

    @Test
    void preparationAndCommitFailuresUseStableEnumOnly() throws Exception {
        Map<CompleteMeetingResponseAssembler.FailureCode, Integer> preparation = Map.of(
                CompleteMeetingResponseAssembler.FailureCode.WINNER_INCONSISTENT, 500,
                CompleteMeetingResponseAssembler.FailureCode.PROFILE_MISMATCH, 500,
                CompleteMeetingResponseAssembler.FailureCode.CAPABILITY_UNAVAILABLE, 422,
                CompleteMeetingResponseAssembler.FailureCode.AUDIO_UNSUPPORTED, 422,
                CompleteMeetingResponseAssembler.FailureCode.SESSION_UNAVAILABLE, 503);
        assertEquals(Set.of(CompleteMeetingResponseAssembler.FailureCode.values()),
                preparation.keySet());
        for (var entry : preparation.entrySet()) {
            JsonNode error = assertError(MAPPER.create(new AuthenticatedCompleteMeetingCreateResult
                    .PreparationFailure(entry.getKey())), entry.getValue());
            assertFalse(error.path("retryable").asBoolean());
            if (entry.getKey() == CompleteMeetingResponseAssembler.FailureCode.CAPABILITY_UNAVAILABLE) {
                assertEquals("CONTRACT", error.path("domain").asText());
                assertEquals("CREATE_CAPABILITY_UNAVAILABLE", error.path("code").asText());
            }
        }

        Map<CompleteMeetingCreationUnitOfWork.ErrorCode, Integer> commit = Map.of(
                CompleteMeetingCreationUnitOfWork.ErrorCode.IDEMPOTENCY_CONFLICT, 409,
                CompleteMeetingCreationUnitOfWork.ErrorCode.RESERVATION_NOT_FOUND, 409,
                CompleteMeetingCreationUnitOfWork.ErrorCode.RESERVATION_EXPIRED, 409,
                CompleteMeetingCreationUnitOfWork.ErrorCode.RESERVATION_STATE_CONFLICT, 409,
                CompleteMeetingCreationUnitOfWork.ErrorCode.VERSION_CONFLICT, 409,
                CompleteMeetingCreationUnitOfWork.ErrorCode.MEETING_CONFLICT, 409,
                CompleteMeetingCreationUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE, 503,
                CompleteMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, 503,
                CompleteMeetingCreationUnitOfWork.ErrorCode.INTERNAL, 500);
        assertEquals(Set.of(CompleteMeetingCreationUnitOfWork.ErrorCode.values()), commit.keySet());
        for (var entry : commit.entrySet()) {
            for (boolean retryable : List.of(false, true)) {
                JsonNode error = assertError(MAPPER.create(new AuthenticatedCompleteMeetingCreateResult
                        .CreationFailure(entry.getKey(), retryable)), entry.getValue());
                assertEquals(retryable, error.path("retryable").asBoolean());
            }
        }
    }

    @Test
    void factFailureWithoutRetryableFlagDoesNotInventRetryAdvice() throws Exception {
        assertError(MAPPER.create(new AuthenticatedCompleteMeetingCreateResult.Conflict()), 409);
        for (var stage : AuthenticatedCompleteMeetingCreateResult.Stage.values()) {
            JsonNode inconsistent = assertError(MAPPER.create(
                    new AuthenticatedCompleteMeetingCreateResult.Inconsistent(stage)), 500);
            JsonNode unavailable = assertError(MAPPER.create(
                    new AuthenticatedCompleteMeetingCreateResult.Unavailable(stage)), 503);
            assertFalse(inconsistent.path("retryable").asBoolean());
            assertFalse(unavailable.path("retryable").asBoolean());
            assertTrue(unavailable.path("retryAfterMs").isNull());
            assertEquals("INTERNAL", unavailable.path("domain").asText());
            assertEquals("CREATE_DEPENDENCY_UNAVAILABLE", unavailable.path("code").asText());
        }
    }

    private static JsonNode assertError(HttpResponse response, int expectedStatus) throws Exception {
        assertEquals(expectedStatus, response.status());
        JsonNode document = JSON.readTree(response.body());
        JsonNode error = document.path("error");
        assertEquals("0.1", document.path("specVersion").asText());
        assertEquals("opaque-diag", error.path("diagnosticId").asText());
        assertFalse(response.body().contains("private-body-token"));
        assertFalse(response.body().contains("private-realtime-token"));
        return error;
    }

    private static CreateMeetingResponseFact responseFact() {
        return new CreateMeetingResponseFact("mtg_create_http", MeetingState.CREATED,
                new CreateMeetingRequestSnapshot.ProfileRef("basic", 1), Set.of(), List.of(),
                AudioProfile.pcm16kMono(40),
                new CreateMeetingResponseFact.RealtimeSession(
                        URI.create("wss://example.test/realtime"),
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                        "private-realtime-token", Instant.parse("2030-01-01T00:01:00Z")),
                new CreateMeetingResponseFact.Limits(120_000, 1_280, 128, null, 120_000));
    }
}
