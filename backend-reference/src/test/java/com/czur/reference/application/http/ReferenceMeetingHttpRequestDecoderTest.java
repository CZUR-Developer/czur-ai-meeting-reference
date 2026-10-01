package com.czur.reference.application.http;

import com.czur.reference.application.command.AuthenticatedMeetingStopApplicationService;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingRequestCanonicalizer;
import com.czur.reference.application.port.DeleteRequest;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 Stop/Delete 请求解码边界测试；不接数据库或公网身份服务。 */
class ReferenceMeetingHttpRequestDecoderTest {
    private static final String STOP_KEY = "stop-key-1234567";
    private static final String DELETE_KEY = "delete-key-123456";
    private static final String TOKEN = "secret.private+token==";
    private static final ReferenceMeetingHttpRequestDecoder DECODER =
            new ReferenceMeetingHttpRequestDecoder("trusted-audience", "trusted-client");
    private static final HttpErrorMapper ERRORS = new HttpErrorMapper(() -> "fixed-diag");

    @Test
    void stopUsesPathHeaderAndTrustedSettingsAndCredentialIsClosable() {
        HttpRequest request = new HttpRequest("POST", "/v1/meetings/mtg_stop:stop",
                Map.of("Authorization", "Bearer " + TOKEN, "Idempotency-Key", STOP_KEY), "");
        var decoded = DECODER.decodeStop(request, "mtg_stop", STOP_KEY);
        assertEquals("mtg_stop", decoded.intent().meetingId());
        assertEquals(STOP_KEY, decoded.intent().idempotencyKey());
        assertEquals("trusted-audience", decoded.verificationRequest().audience());
        assertEquals("trusted-client", decoded.verificationRequest().clientId());
        assertEquals(TOKEN, decoded.verificationRequest().credential().useBytes(
                bytes -> new String(bytes, StandardCharsets.US_ASCII)));
        assertFalse(decoded.toString().contains(TOKEN));
        assertFalse(decoded.toString().contains(STOP_KEY));
        decoded.verificationRequest().credential().close();
        assertTrue(decoded.verificationRequest().credential().isDestroyed());
    }

    @Test
    void authenticatedStopServiceClosesDecodedCredentialOnIdentityFailure() {
        var decoded = DECODER.decodeStop(new HttpRequest("POST", "/v1/meetings/mtg_stop:stop",
                Map.of("Authorization", "Bearer " + TOKEN, "Idempotency-Key", STOP_KEY), ""),
                "mtg_stop", STOP_KEY);
        var service = new AuthenticatedMeetingStopApplicationService("deployment",
                request -> IdentityProviderResult.failure(new IdentityProviderError(
                        IdentityProviderError.Code.INVALID_CREDENTIAL, false)),
                request -> { throw new AssertionError("stop port must not run"); }, new NoQuery());
        service.stop(decoded.verificationRequest(), decoded.intent());
        assertTrue(decoded.verificationRequest().credential().isDestroyed());
    }

    @Test
    void badStopBearerMapsToFixed401WithoutExposingToken() {
        for (String authorization : new String[]{"Basic secret", "Bearer  a b",
                "Bearer secret@invalid", "Bearer\tsecret"}) {
            HttpRequest request = new HttpRequest("POST", "/v1/meetings/mtg_stop:stop",
                    Map.of("Authorization", authorization, "Idempotency-Key", STOP_KEY), "");
            var failure = assertThrows(ReferenceMeetingHttpRequestDecoder.AuthenticationFailure.class,
                    () -> DECODER.decodeStop(request, "mtg_stop", STOP_KEY));
            assertEquals("invalid Stop authentication", failure.getMessage());
            HttpResponse response = ERRORS.exception(failure);
            assertEquals(401, response.status());
            assertFalse(response.body().contains(authorization));
        }
        HttpRequest missing = new HttpRequest("POST", "/v1/meetings/mtg_stop:stop",
                Map.of("Idempotency-Key", STOP_KEY), "");
        assertEquals(401, ERRORS.exception(assertThrows(
                ReferenceMeetingHttpRequestDecoder.AuthenticationFailure.class,
                () -> DECODER.decodeStop(missing, "mtg_stop", STOP_KEY))).status());
    }

    @Test
    void stopPathAndIdempotencyMustMatchBeforeCredentialCreation() {
        HttpRequest request = new HttpRequest("POST", "/v1/meetings/mtg_stop:stop",
                Map.of("Authorization", "Bearer " + TOKEN, "Idempotency-Key", STOP_KEY), "");
        for (String pathId : new String[]{"mtg_other", "", "mtg_stop:stop"}) {
            assertEquals(400, ERRORS.exception(assertThrows(IllegalArgumentException.class,
                    () -> DECODER.decodeStop(request, pathId, STOP_KEY))).status());
        }
        assertEquals(400, ERRORS.exception(assertThrows(IllegalArgumentException.class,
                () -> DECODER.decodeStop(request, "mtg_stop", "another-stop-key-123456"))).status());
        HttpRequest query = new HttpRequest("POST", "/v1/meetings/mtg_stop:stop",
                request.headers(), Map.of("meetingId", "mtg_other"), "");
        assertEquals(400, ERRORS.exception(assertThrows(IllegalArgumentException.class,
                () -> DECODER.decodeStop(query, "mtg_stop", STOP_KEY))).status());
    }

    @Test
    void deleteDigestHasVersionedFixedVectorAndExcludesKeyAndSubject() {
        MeetingCommandContext first = context("owner-one");
        MeetingCommandContext second = context("owner-two");
        DeleteRequest a = DECODER.decodeDelete(delete("mtg_delete", DELETE_KEY), first);
        DeleteRequest replay = DECODER.decodeDelete(delete("mtg_delete", DELETE_KEY), first);
        DeleteRequest newKey = DECODER.decodeDelete(
                delete("mtg_delete", "another-delete-key-123456"), second);
        DeleteRequest otherMeeting = DECODER.decodeDelete(delete("mtg_other", DELETE_KEY), first);

        assertEquals(first, a.context());
        assertEquals("mtg_delete", a.meetingId());
        assertEquals(DELETE_KEY, a.idempotencyKey());
        assertEquals("26d8b8b7020207643b73feeaa4fea9aaf5d35cd6376b7e64d7c912d95e344f40",
                MeetingRequestCanonicalizer.deleteDigest("mtg_delete"));
        assertEquals(a.canonicalRequestDigest(), replay.canonicalRequestDigest());
        assertEquals(a.canonicalRequestDigest(), newKey.canonicalRequestDigest());
        assertNotEquals(a.canonicalRequestDigest(), otherMeeting.canonicalRequestDigest());
        assertFalse(a.toString().contains(DELETE_KEY));
        assertFalse(a.toString().contains(a.canonicalRequestDigest()));
    }

    @Test
    void deleteRejectsBodyQueryAndMissingKeyAndNetworkStillHasNoContext() {
        MeetingCommandContext context = context("owner-one");
        for (HttpRequest request : new HttpRequest[]{
                new HttpRequest("DELETE", "/v1/meetings/mtg_delete",
                        Map.of("Idempotency-Key", DELETE_KEY),
                        "{\"meetingId\":\"mtg_other\"}"),
                new HttpRequest("DELETE", "/v1/meetings/mtg_delete",
                        Map.of("Idempotency-Key", DELETE_KEY), Map.of("x", "1"), ""),
                new HttpRequest("DELETE", "/v1/meetings/mtg_delete", Map.of(), ""),
                new HttpRequest("POST", "/v1/meetings/mtg_delete",
                        Map.of("Idempotency-Key", DELETE_KEY), "")}) {
            assertEquals(400, ERRORS.exception(assertThrows(IllegalArgumentException.class,
                    () -> DECODER.decodeDelete(request, context))).status());
        }
        MeetingHttpAdapter adapter = new MeetingHttpAdapter(
                new NoQuery(), request -> { throw new AssertionError("no delete port call"); },
                DECODER, ERRORS, new AuthenticatedMeetingStopApplicationService(
                        "deployment", request -> { throw new AssertionError("no identity"); },
                        request -> { throw new AssertionError("no stop"); }, new NoQuery()));
        assertEquals(401, adapter.handle(delete("mtg_delete", DELETE_KEY), null).status());
        // 解码器只消费可信 context，不检查权限；02-03 必须在端口调用前完成检查。
        assertFalse(context.identity().hasPermission("meeting:delete"));
    }

    @Test
    void adapterRejectsStopBodyBeforeRealDecoderCreatesCredential() {
        MeetingHttpAdapter adapter = new MeetingHttpAdapter(new NoQuery(),
                request -> { throw new AssertionError("no delete"); }, DECODER, ERRORS,
                new AuthenticatedMeetingStopApplicationService("deployment",
                        request -> { throw new AssertionError("no identity"); },
                        request -> { throw new AssertionError("no stop"); }, new NoQuery()));
        HttpRequest rejected = new HttpRequest("POST", "/v1/meetings/mtg_stop:stop",
                Map.of("Authorization", "Bearer " + TOKEN,
                        "Idempotency-Key", STOP_KEY, "Content-Type", "application/json"),
                "{\"meetingId\":\"mtg_other\"}");
        HttpResponse response = adapter.handle(rejected, null);
        assertEquals(400, response.status());
        assertFalse(response.body().contains(TOKEN));
        assertFalse(response.body().contains("mtg_other"));

        HttpRequest badBearer = new HttpRequest("POST", "/v1/meetings/mtg_stop:stop",
                Map.of("Authorization", "Basic " + TOKEN, "Idempotency-Key", STOP_KEY), "");
        assertEquals(401, adapter.handle(badBearer, null).status());
    }

    private static HttpRequest delete(String meetingId, String key) {
        return new HttpRequest("DELETE", "/v1/meetings/" + meetingId,
                Map.of("Idempotency-Key", key), "");
    }

    private static MeetingCommandContext context(String subject) {
        return new MeetingCommandContext("deployment", new VerifiedIdentity(
                new Subject(subject), Set.of(), Set.of(),
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-12-01T00:00:00Z"), "credential-reference"));
    }

    private static final class NoQuery implements MeetingQueryPort {
        @Override public Result get(GetRequest request) {
            throw new AssertionError("no query");
        }
        @Override public ListResult list(ListRequest request) {
            throw new AssertionError("no list");
        }
    }
}
