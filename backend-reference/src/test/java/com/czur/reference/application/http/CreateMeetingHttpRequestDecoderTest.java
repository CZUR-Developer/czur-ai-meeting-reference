package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingRequestCanonicalizer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Create 入站解码的公开字段、严格 JSON 和凭据生命周期门禁。 */
class CreateMeetingHttpRequestDecoderTest {
    private static final String KEY = "create-key-123456";
    private static final String TOKEN = "opaque.private+token==";
    private static final CreateMeetingHttpRequestDecoder DECODER =
            new CreateMeetingHttpRequestDecoder("meeting-api", "trusted-client");
    private static final String BODY = """
            {
              "clientSessionId":"client-session-1",
              "profile":{"id":"basic","revision":2},
              "client":{"appId":"partner-app","appVersion":"1.2.3","contractVersion":"0.1","deviceId":"device-1"},
              "title":"讨论会",
              "timezone":"Asia/Shanghai",
              "languages":{"source":"zh-CN","targets":["en-US"]},
              "audioProfile":{"encoding":"PCM_S16LE","sampleRateHz":16000,"channelCount":1,"frameDurationMs":40},
              "requestedCapabilities":["transcript.realtime","translation.realtime"],
              "artifactPolicy":{"retainAudio":true,"presentationCapture":false}
            }
            """;

    @Test
    void completeRequestPreservesAllFieldsAndUsesTrustedIdentitySettings() {
        try (var decoded = DECODER.decode(request(BODY))) {
            var snapshot = decoded.requestFact().requestSnapshot();
            assertEquals("client-session-1", snapshot.clientSessionId());
            assertEquals("basic", snapshot.profile().id());
            assertEquals(2, snapshot.profile().revision());
            assertEquals("partner-app", snapshot.client().appId());
            assertEquals("1.2.3", snapshot.client().appVersion());
            assertEquals("0.1", snapshot.client().contractVersion());
            assertEquals("device-1", snapshot.client().deviceId());
            assertEquals("讨论会", snapshot.title());
            assertEquals("Asia/Shanghai", snapshot.timezone());
            assertEquals("zh-CN", snapshot.languages().source());
            assertEquals(java.util.List.of("en-US"), snapshot.languages().targets());
            assertEquals(40, snapshot.audioProfile().frameDurationMs());
            assertEquals(2, snapshot.requestedCapabilities().size());
            assertTrue(snapshot.artifactPolicy().retainAudio());
            assertFalse(snapshot.artifactPolicy().presentationCapture());
            assertEquals(KEY, snapshot.idempotencyKey());
            assertEquals(MeetingRequestCanonicalizer.digest(snapshot),
                    decoded.requestFact().canonicalRequestDigest());
            assertEquals("meeting-api", decoded.verificationRequest().audience());
            assertEquals("trusted-client", decoded.verificationRequest().clientId());
            assertEquals(TOKEN, decoded.verificationRequest().credential().useBytes(
                    bytes -> new String(bytes, StandardCharsets.US_ASCII)));
            assertFalse(decoded.toString().contains(TOKEN));
            assertFalse(decoded.toString().contains("device-1"));
            assertFalse(decoded.toString().contains(KEY));
        }
    }

    @Test
    void closeDestroysCredentialAndOmittedTitleIsAccepted() {
        var decoded = DECODER.decode(request(BODY.replace("  \"title\":\"讨论会\",\n", "")));
        assertEquals(null, decoded.requestFact().requestSnapshot().title());
        decoded.close();
        decoded.close();
        assertTrue(decoded.verificationRequest().credential().isDestroyed());
    }

    @Test
    void rejectsMalformedOrIncompleteJsonWithFixedSafeError() {
        reject(BODY.replace("\"title\":\"讨论会\"", "\"title\":null"));
        reject(BODY.replace("\"clientSessionId\":\"client-session-1\",", ""));
        reject(BODY.replace("\"clientSessionId\":\"client-session-1\"",
                "\"clientSessionId\":\"client-session-1\",\"unexpected\":true"));
        reject(BODY.replace("\"profile\":{\"id\":\"basic\",\"revision\":2}",
                "\"profile\":{\"id\":\"basic\",\"revision\":2,\"unexpected\":1}"));
        reject(BODY.replace("\"revision\":2", "\"revision\":2,\"revision\":3"));
        reject(BODY.replace("\"revision\":2", "\"revision\":2147483648"));
        reject(BODY.replace("\"revision\":2", "\"revision\":2.0"));
        reject(BODY.replace("\"retainAudio\":true", "\"retainAudio\":\"true\""));
        reject(BODY.replace("\"requestedCapabilities\":[\"transcript.realtime\",\"translation.realtime\"]",
                "\"requestedCapabilities\":[\"transcript.realtime\",\"transcript.realtime\"]"));
        reject(BODY.replace("\"targets\":[\"en-US\"]", "\"targets\":[\"en-US\",\"en-US\"]"));
        reject(BODY.replace("\"title\":\"讨论会\"", "\"title\":\"\\uD800\""));
        reject(BODY + " {} ");
        reject(BODY.replace("\"frameDurationMs\":40", "\"frameDurationMs\":5"));
        reject(BODY.replace("\"encoding\":\"PCM_S16LE\"", "\"encoding\":\"UNKNOWN\""));
    }

    @Test
    void rejectsMalformedHeadersAndTransportBounds() {
        rejectAuthentication(new HttpRequest("POST", "/v1/meetings",
                Map.of("Authorization", "Basic abc", "Content-Type", "application/json",
                        "Idempotency-Key", KEY), BODY));
        try (var decoded = DECODER.decode(new HttpRequest("POST", "/v1/meetings",
                Map.of("Authorization", "Bearer  extra", "Content-Type", "application/json",
                        "Idempotency-Key", KEY), BODY))) {
            assertEquals("extra", decoded.verificationRequest().credential().useBytes(
                    bytes -> new String(bytes, StandardCharsets.US_ASCII)));
        }
        rejectAuthentication(new HttpRequest("POST", "/v1/meetings",
                Map.of("Authorization", "Bearer\textra", "Content-Type", "application/json",
                        "Idempotency-Key", KEY), BODY));
        rejectAuthentication(new HttpRequest("POST", "/v1/meetings",
                Map.of("Authorization", "Bearer  ex tra", "Content-Type", "application/json",
                        "Idempotency-Key", KEY), BODY));
        rejectAuthentication(new HttpRequest("POST", "/v1/meetings",
                Map.of("Content-Type", "application/json", "Idempotency-Key", KEY), BODY));
        rejectAuthentication(new HttpRequest("POST", "/v1/meetings",
                Map.of("Authorization", "Bearer a@b", "Content-Type", "text/plain",
                        "Idempotency-Key", "short"), "not JSON"));
        for (String malformed : java.util.List.of("Bearer a@b", "Bearer a=b", "Bearer ===",
                "Bearer a,b", "Bearer a%b", "Bearer a\"b")) {
            rejectAuthentication(new HttpRequest("POST", "/v1/meetings",
                    Map.of("Authorization", malformed, "Content-Type", "application/json",
                            "Idempotency-Key", KEY), BODY));
        }
        rejectAuthentication(new HttpRequest("POST", "/v1/meetings",
                Map.of("Authorization", "Bearer " + "a".repeat(8193),
                        "Content-Type", "application/json", "Idempotency-Key", KEY), BODY));
        try (var decoded = DECODER.decode(new HttpRequest("POST", "/v1/meetings",
                Map.of("Authorization", "Bearer " + "a".repeat(8192),
                        "Content-Type", "application/json", "Idempotency-Key", KEY), BODY))) {
            int tokenLength = decoded.verificationRequest().credential().useBytes(
                    bytes -> bytes.length);
            assertEquals(8192, tokenLength);
        }
        reject(new HttpRequest("POST", "/v1/meetings",
                Map.of("Authorization", "Bearer " + TOKEN, "Content-Type", "text/plain",
                        "Idempotency-Key", KEY), BODY));
        reject(new HttpRequest("POST", "/v1/meetings",
                Map.of("Authorization", "Bearer " + TOKEN, "Content-Type", "application/json;\fcharset=utf-8",
                        "Idempotency-Key", KEY), BODY));
        reject(new HttpRequest("POST", "/v1/meetings",
                Map.of("Authorization", "Bearer " + TOKEN, "Content-Type", "application/json",
                        "Idempotency-Key", "short"), BODY));
        reject(new HttpRequest("POST", "/v1/meetings",
                Map.of("Authorization", "Bearer " + TOKEN, "Content-Type", "application/json",
                        "Idempotency-Key", KEY, "X-Request-Id", "x".repeat(129)), BODY));
        reject(new HttpRequest("POST", "/v1/meetings",
                Map.of("Authorization", "Bearer " + TOKEN, "Content-Type", "application/json",
                        "Idempotency-Key", KEY + "\u0000"), BODY));
        assertThrows(IllegalArgumentException.class, () -> new HttpRequest("POST", "/v1/meetings",
                Map.of("Authorization", "Bearer " + TOKEN, "authorization", "Bearer other"), BODY));
        assertThrows(IllegalArgumentException.class, () -> request("a".repeat(HttpRequest.MAX_BODY_BYTES + 1)));
        assertThrows(IllegalArgumentException.class, () -> new HttpRequest("POST", "/v1/meetings",
                Map.of("Authorization", "Bearer " + "a".repeat(HttpRequest.MAX_HEADER_BYTES)), BODY));
    }

    @Test
    void idempotencyLengthCountsCodePointsAndIsNotReadFromBody() {
        String unicodeKey = "🔑".repeat(16);
        try (var decoded = DECODER.decode(new HttpRequest("POST", "/v1/meetings", Map.of(
                "Authorization", "Bearer " + TOKEN,
                "Idempotency-Key", unicodeKey,
                "Content-Type", "application/json"), BODY))) {
            assertEquals(unicodeKey, decoded.requestFact().requestSnapshot().idempotencyKey());
        }
        reject(new HttpRequest("POST", "/v1/meetings", Map.of(
                "Authorization", "Bearer " + TOKEN,
                "Idempotency-Key", "🔑".repeat(15),
                "Content-Type", "application/json"), BODY));
        reject(BODY.replace("\"clientSessionId\":\"client-session-1\"",
                "\"clientSessionId\":\"client-session-1\",\"idempotencyKey\":\"" + KEY + "\""));
    }

    private static HttpRequest request(String body) {
        return new HttpRequest("POST", "/v1/meetings", Map.of(
                "Authorization", "Bearer " + TOKEN,
                "Idempotency-Key", KEY,
                "Content-Type", "application/json; charset=UTF-8"), body);
    }

    private static void reject(String body) { reject(request(body)); }

    private static void reject(HttpRequest request) {
        var failure = assertThrows(CreateMeetingHttpRequestDecoder.DecodeFailure.class,
                () -> DECODER.decode(request));
        assertEquals(CreateMeetingHttpRequestDecoder.DecodeFailure.Kind.CONTRACT, failure.kind());
        assertEquals("invalid Create HTTP request", failure.getMessage());
        assertFalse(failure.toString().contains(TOKEN));
        assertFalse(failure.toString().contains("device-1"));
    }

    private static void rejectAuthentication(HttpRequest request) {
        var failure = assertThrows(CreateMeetingHttpRequestDecoder.DecodeFailure.class,
                () -> DECODER.decode(request));
        assertEquals(CreateMeetingHttpRequestDecoder.DecodeFailure.Kind.AUTHENTICATION,
                failure.kind());
        assertEquals("invalid Create authentication", failure.getMessage());
        assertFalse(failure.toString().contains(TOKEN));
    }
}
