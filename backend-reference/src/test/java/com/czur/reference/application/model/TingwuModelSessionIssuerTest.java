package com.czur.reference.application.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
            "example-app", "example-access-id", "example-secret",
            Duration.ofMinutes(5));

    @Test void signsRealtimeCreateTaskAndReturnsJoinUrlWithoutRamCredentials() throws Exception {
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        var issuer = new TingwuModelSessionIssuer(CONFIG, request -> {
            sent.set(request);
            return new TingwuModelSessionIssuer.ProviderResponse(200,
                    "{\"Data\":{\"TaskId\":\"task-123\",\"MeetingJoinUrl\":"
                            + "\"wss://join.example.invalid/meeting?temporary=grant\"}}");
        }, CLOCK);

        var legacyRequest = new ModelSessionIssuer.Request("zh-CN", List.of(), false);
        assertEquals(null, legacyRequest.meetingId());
        var session = issuer.issue(legacyRequest);
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
        assertFalse(input.has("LanguageHints"));
        var parameters = new ObjectMapper().readTree(body).path("Parameters");
        var transcription = parameters.path("Transcription");
        assertTrue(transcription.path("RealtimeDiarizationEnabled").asBoolean());
        assertTrue(transcription.path("DiarizationEnabled").asBoolean());
        assertTrue(transcription.path("Diarization").has("SpeakerCount"));
        assertEquals(0, transcription.path("Diarization").path("SpeakerCount").asInt());
        assertFalse(parameters.has("TranslationEnabled"));
        assertFalse(parameters.has("Translation"));
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
        assertThrows(TingwuModelSessionIssuer.SessionIssueException.class,
                () -> issuer.issue(new ModelSessionIssuer.Request("zh-CN", List.of(), false)));
    }

    @Test void englishMeetingUsesEnglishInCreateTask() throws Exception {
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        var issuer = new TingwuModelSessionIssuer(CONFIG, request -> {
            sent.set(request);
            return new TingwuModelSessionIssuer.ProviderResponse(200,
                    "{\"Data\":{\"TaskId\":\"task-en\",\"MeetingJoinUrl\":"
                            + "\"wss://join.example.invalid/meeting\"}}");
        }, CLOCK);

        issuer.issue(new ModelSessionIssuer.Request("en-US", List.of(), false));
        var body = new ObjectMapper().readTree(readBody(sent.get()));
        assertEquals("en", body.path("Input").path("SourceLanguage").asText());
    }

    @Test void allProductLanguagesMapInSubtitleAndBilingualRequests() throws Exception {
        var standard = List.of("zh-CN", "en-US", "ja-JP", "ko-KR", "de-DE", "fr-FR", "ru-RU");
        var provider = List.of("cn", "en", "ja", "ko", "de", "fr", "ru");
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        var issuer = new TingwuModelSessionIssuer(CONFIG, request -> {
            sent.set(request);
            return new TingwuModelSessionIssuer.ProviderResponse(200,
                    "{\"Data\":{\"TaskId\":\"task-language\",\"MeetingJoinUrl\":"
                            + "\"wss://join.example.invalid/meeting\"}}");
        }, CLOCK);
        ObjectMapper json = new ObjectMapper();
        for (int index = 0; index < standard.size(); index++) {
            issuer.issue(new ModelSessionIssuer.Request(standard.get(index), List.of(), false));
            var subtitle = json.readTree(readBody(sent.get()));
            assertEquals(provider.get(index), subtitle.path("Input").path("SourceLanguage").asText());
            assertFalse(subtitle.path("Parameters").has("TranslationEnabled"));
            // 逐个轮转配对，使每种语言都覆盖源、目标以及双向翻译两侧，沿用旧产品请求规则。
            int other = (index + 1) % standard.size();
            issuer.issue(new ModelSessionIssuer.Request(standard.get(index),
                    List.of(standard.get(other)), true));
            var bilingual = json.readTree(readBody(sent.get()));
            var expected = json.valueToTree(List.of(provider.get(index), provider.get(other)));
            assertEquals("multilingual", bilingual.path("Input").path("SourceLanguage").asText());
            assertEquals(expected, bilingual.path("Input").path("LanguageHints"));
            assertEquals(expected, bilingual.path("Parameters").path("Translation").path("TargetLanguages"));
            assertTrue(bilingual.path("Parameters").path("TranslationEnabled").asBoolean());
        }
    }

    @Test void enabledTranslationUsesOneMultilingualTaskWithBothDirections() throws Exception {
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        var issuer = new TingwuModelSessionIssuer(CONFIG, request -> {
            sent.set(request);
            return new TingwuModelSessionIssuer.ProviderResponse(200,
                    "{\"Data\":{\"TaskId\":\"task-bilingual\",\"MeetingJoinUrl\":"
                            + "\"wss://join.example.invalid/meeting\"}}");
        }, CLOCK);

        var session = issuer.issue(new ModelSessionIssuer.Request("zh-CN", List.of("en-US"), true));
        var body = new ObjectMapper().readTree(readBody(sent.get()));
        assertEquals("multilingual", body.path("Input").path("SourceLanguage").asText());
        assertEquals(List.of("cn", "en"), new ObjectMapper().convertValue(
                body.path("Input").path("LanguageHints"),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
        assertTrue(body.path("Parameters").path("TranslationEnabled").asBoolean());
        var transcription = body.path("Parameters").path("Transcription");
        assertTrue(transcription.path("RealtimeDiarizationEnabled").asBoolean());
        assertTrue(transcription.path("DiarizationEnabled").asBoolean());
        assertTrue(transcription.path("Diarization").has("SpeakerCount"));
        assertEquals(0, transcription.path("Diarization").path("SpeakerCount").asInt());
        assertEquals(List.of("cn", "en"), new ObjectMapper().convertValue(
                body.path("Parameters").path("Translation").path("TargetLanguages"),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
        assertEquals(2, body.path("Parameters").path("Translation").path("OutputLevel").asInt());
        assertEquals(Map.of("taskId", "task-bilingual", "translationEnabled", "true"),
                session.initParams());
    }

    @Test void requestedTargetWithoutEnabledCapabilityRemainsSingleLanguage() throws Exception {
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        var issuer = new TingwuModelSessionIssuer(CONFIG, request -> {
            sent.set(request);
            return new TingwuModelSessionIssuer.ProviderResponse(200,
                    "{\"Data\":{\"TaskId\":\"task-asr\",\"MeetingJoinUrl\":"
                            + "\"wss://join.example.invalid/meeting\"}}");
        }, CLOCK);

        issuer.issue(new ModelSessionIssuer.Request("zh-CN", List.of("en-US"), false));
        var body = new ObjectMapper().readTree(readBody(sent.get()));
        assertEquals("cn", body.path("Input").path("SourceLanguage").asText());
        assertFalse(body.path("Parameters").has("TranslationEnabled"));
    }

    @Test void trustedMeetingIdKeepsDefaultTingwuMaterialsAndTaskBodyUnchanged() throws Exception {
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        var issuer = new TingwuModelSessionIssuer(CONFIG, request -> {
            sent.set(request);
            return new TingwuModelSessionIssuer.ProviderResponse(200,
                    "{\"Data\":{\"TaskId\":\"task-owned\",\"MeetingJoinUrl\":"
                            + "\"wss://join.example.invalid/meeting\"}}");
        }, CLOCK);
        var request = new ModelSessionIssuer.Request("zh-CN", List.of(), false,
                "mtg_owned_meeting");

        var session = issuer.issue(request);
        assertEquals("mtg_owned_meeting", request.meetingId());
        assertEquals("tingwu-realtime", session.adapterId());
        assertEquals(Map.of("taskId", "task-owned"), session.initParams());
        var body = new ObjectMapper().readTree(readBody(sent.get()));
        assertEquals("cn", body.path("Input").path("SourceLanguage").asText());
        assertFalse(body.path("Parameters").has("TranslationEnabled"));
        assertFalse(body.toString().contains("mtg_owned_meeting"));
        assertFalse(request.toString().contains("mtg_owned_meeting"));
    }

    @Test void independentTextTranslationKeepsBilingualAsrWithoutNativeTranslations() throws Exception {
        var sent = new AtomicReference<HttpRequest>();
        var issuer = new TingwuModelSessionIssuer(CONFIG, request -> {
            sent.set(request);
            return new TingwuModelSessionIssuer.ProviderResponse(200,
                    "{\"Data\":{\"TaskId\":\"task-independent\",\"MeetingJoinUrl\":\"wss://join.example.invalid/meeting\"}}");
        }, CLOCK);
        issuer.issue(new ModelSessionIssuer.Request("zh-CN", List.of("ja-JP"), false,
                "mtg_owned", true));
        var body = new ObjectMapper().readTree(readBody(sent.get()));
        assertEquals("multilingual", body.path("Input").path("SourceLanguage").asText());
        assertEquals(new ObjectMapper().readTree("[\"cn\",\"ja\"]"), body.path("Input").path("LanguageHints"));
        assertFalse(body.path("Parameters").has("TranslationEnabled"));
        assertFalse(body.path("Parameters").has("Translation"));
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
