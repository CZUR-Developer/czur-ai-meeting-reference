package com.czur.reference.application.model;

import com.czur.reference.application.http.HttpErrorMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 只锁定真实装配接缝与线上响应形状；不假造翻译质量或重建会议流程。 */
class TextTranslationModelSessionIssuerTest {
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");
    private static final ModelSessionIssuer.Session ASR = new ModelSessionIssuer.Session(
            "asr-only", "wss://asr.invalid/session", NOW.plusSeconds(300), Map.of());
    private static final TranslationSessionIssuer.Session TEXT = new TranslationSessionIssuer.Session(
            "partner-translation", "https://translate.invalid/text", NOW.plusSeconds(300),
            "short-lived-only", Map.of("tokenHeader", "x-api-key"));

    @Test void composesOnlyWhenGrantedAndKeepsSameMeetingAndLanguages() throws Exception {
        AtomicReference<ModelSessionIssuer.Request> asrRequest = new AtomicReference<>();
        AtomicReference<ModelSessionIssuer.Request> textRequest = new AtomicReference<>();
        var issuer = new TextTranslationModelSessionIssuer(request -> {
            asrRequest.set(request); return ASR;
        }, request -> { textRequest.set(request); return TEXT; });
        var request = new ModelSessionIssuer.Request("ja-JP", List.of("de-DE"), true, "mtg_owned");
        var session = issuer.issue(request);
        assertEquals(request, textRequest.get());
        assertEquals(new ModelSessionIssuer.Request("ja-JP", List.of("de-DE"), false, "mtg_owned", true),
                asrRequest.get());
        assertEquals(TEXT, session.translationSession());
        var json = new ObjectMapper().readTree(new HttpErrorMapper().modelSession("mtg_owned", session).body());
        assertEquals("asr-only", json.path("adapterId").asText());
        assertEquals("partner-translation", json.path("translationSession").path("adapterId").asText());
        assertEquals("x-api-key", json.path("translationSession").path("initParams").path("tokenHeader").asText());
        assertFalse(session.toString().contains("short-lived-only"));
        assertFalse(TEXT.toString().contains("short-lived-only"));
    }

    @Test void asrOnlyKeepsOriginalResponseAndNeverIssuesTextMaterials() {
        AtomicInteger textCalls = new AtomicInteger();
        var issuer = new TextTranslationModelSessionIssuer(request -> ASR,
                request -> { textCalls.incrementAndGet(); return TEXT; });
        assertSame(ASR, issuer.issue(new ModelSessionIssuer.Request("zh-CN", List.of(), false)));
        assertEquals(0, textCalls.get());
        assertFalse(new HttpErrorMapper().modelSession("mtg_owned", ASR).body().contains("translationSession"));
        assertNull(issuer.issueTranslation(new ModelSessionIssuer.Request("zh-CN", List.of(), false)));
    }

    @Test void translationRenewalNeverCreatesAnotherAsrTask() {
        AtomicInteger asrCalls = new AtomicInteger();
        AtomicInteger textCalls = new AtomicInteger();
        var issuer = new TextTranslationModelSessionIssuer(request -> {
            asrCalls.incrementAndGet(); return ASR;
        }, request -> { textCalls.incrementAndGet(); return TEXT; });
        var request = new ModelSessionIssuer.Request("en-US", List.of("zh-CN"), true, "mtg_owned");
        assertEquals(TEXT, issuer.issueTranslation(request));
        assertEquals(0, asrCalls.get());
        assertEquals(1, textCalls.get());
    }

    @Test void sampleNeedsNoVendorKeyAndAbsenceKeepsNativeTranslation() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertNull(SampleJsonTranslationSessionIssuer.fromEnvironment(Map.of(), clock));
        var sample = SampleJsonTranslationSessionIssuer.fromEnvironment(Map.of(
                SampleJsonTranslationSessionIssuer.ENDPOINT, "http://127.0.0.1:19092/translate"), clock);
        var session = sample.issue(new ModelSessionIssuer.Request("zh-CN", List.of("en-US"), true));
        assertEquals("sample-json-translation", session.adapterId());
        assertEquals(NOW.plusSeconds(300), session.expiresAt());
        assertNull(session.token());
        assertThrows(IllegalArgumentException.class, () -> new SampleJsonTranslationSessionIssuer(
                URI.create("file:///tmp/not-a-model"), clock));
    }

    @Test void summaryPluginCanProvideSignedUrlAndAdditionalParameters() throws Exception {
        SummarySessionIssuer.Session summary = new SummarySessionIssuer.Session() {
            public String adapterId() { return "partner-summary"; }
            public String endpoint() { return "https://summary.invalid/signed"; }
            public Instant expiresAt() { return NOW.plusSeconds(300); }
            public String token() { return null; }
            public Map<String, String> initParams() { return Map.of("apiVersion", "v2"); }
        };
        var json = new ObjectMapper().readTree(new HttpErrorMapper().summarySession("mtg_owned", summary).body());
        assertEquals("partner-summary", json.path("adapterId").asText());
        assertEquals("v2", json.path("initParams").path("apiVersion").asText());
        assertFalse(json.has("token"));
    }
}
