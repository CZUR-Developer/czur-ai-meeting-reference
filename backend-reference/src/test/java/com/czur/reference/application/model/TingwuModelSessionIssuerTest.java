package com.czur.reference.application.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不访问厂商，核对 CreateTask 请求形状及仅下发临时直连材料。 */
class TingwuModelSessionIssuerTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-03T02:00:00Z"), ZoneOffset.UTC);
    private static final TingwuModelSessionConfig CONFIG = new TingwuModelSessionConfig(
            URI.create("https://tingwu.cn-beijing.aliyuncs.com/openapi/tingwu/v2/tasks"),
            "example-app", "example-access-id", "example-secret", "cn",
            Duration.ofMinutes(5));

    @Test void signsRealtimeCreateTaskAndReturnsJoinUrlWithoutRamCredentials() throws Exception {
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        var issuer = new TingwuModelSessionIssuer(CONFIG, request -> {
            sent.set(request);
            return new TingwuModelSessionIssuer.ProviderResponse(200,
                    "{\"Data\":{\"TaskId\":\"task-123\",\"MeetingJoinUrl\":"
                            + "\"wss://join.example.invalid/meeting?temporary=grant\"}}");
        }, CLOCK);

        var session = issuer.issue();
        assertEquals("PUT", sent.get().method());
        assertEquals("type=realtime&RegionId=cn-beijing", sent.get().uri().getRawQuery());
        assertEquals("CreateTask", sent.get().headers().firstValue("X-Acs-Action").orElseThrow());
        assertTrue(sent.get().headers().firstValue("Authorization").orElseThrow()
                .startsWith("ACS3-HMAC-SHA256 Credential=example-access-id,"));
        assertEquals("2026-10-03T02:00:00Z",
                sent.get().headers().firstValue("X-Acs-Date").orElseThrow());
        String body = new String(sent.get().bodyPublisher().orElseThrow()
                .contentLength() > 0 ? readBody(sent.get()) : new byte[0]);
        var input = new ObjectMapper().readTree(body).path("Input");
        assertEquals("cn", input.path("SourceLanguage").asText());
        assertEquals(16000, input.path("SampleRate").asInt());
        assertEquals("pcm", input.path("Format").asText());
        assertEquals("tingwu-realtime", session.adapterId());
        assertEquals("wss://join.example.invalid/meeting?temporary=grant", session.endpoint());
        assertEquals(Map.of("taskId", "task-123"), session.initParams());
        assertEquals(Instant.parse("2026-10-03T02:05:00Z"), session.expiresAt());
        assertFalse(session.toString().contains("example-secret"));
        assertFalse(session.toString().contains("temporary=grant"));
        assertFalse(session.toString().contains("task-123"));
    }

    @Test void rejectsMissingProviderMaterialsWithoutReturningPartialGrant() {
        var issuer = new TingwuModelSessionIssuer(CONFIG,
                request -> new TingwuModelSessionIssuer.ProviderResponse(200,
                        "{\"Data\":{\"TaskId\":\"task-123\"}}"), CLOCK);
        assertThrows(TingwuModelSessionIssuer.SessionIssueException.class, issuer::issue);
    }

    @Test void remainsDisabledWithoutConfiguredCredentials() {
        assertEquals(null, TingwuModelSessionConfig.fromEnvironment(Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                TingwuModelSessionConfig.fromEnvironment(Map.of(
                        TingwuModelSessionConfig.ENABLED, "true")));
        var existingInjection = TingwuModelSessionConfig.fromEnvironment(Map.of(
                TingwuModelSessionConfig.ENABLED, "true",
                TingwuModelSessionConfig.TINGWU_APP_KEY, "existing-app",
                TingwuModelSessionConfig.ALIYUN_ACCESS_KEY_ID, "existing-id",
                TingwuModelSessionConfig.ALIYUN_ACCESS_KEY_SECRET, "existing-secret",
                TingwuModelSessionConfig.APP_KEY, ""));
        assertEquals("existing-app", existingInjection.appKey());
        assertEquals("existing-id", existingInjection.accessKeyId());
        assertFalse(existingInjection.toString().contains("existing-secret"));
    }

    private static byte[] readBody(HttpRequest request) throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            public void onNext(java.nio.ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }
            public void onError(Throwable failure) { throw new AssertionError(failure); }
            public void onComplete() {}
        });
        return output.toByteArray();
    }
}
