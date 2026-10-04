package com.czur.reference.application.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** 仅后端持有永久 Key，按次向百炼换取 Android 文本应用所用的短期 Key。 */
public final class BailianSummarySessionIssuer {
    public static final String ADAPTER_ID = "bailian-summary";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final BailianSummarySessionConfig config;
    private final HttpClient http;
    private final Clock clock;

    public BailianSummarySessionIssuer(BailianSummarySessionConfig config, Clock clock) {
        this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), clock);
    }

    BailianSummarySessionIssuer(BailianSummarySessionConfig config, HttpClient http, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Session issue() {
        HttpRequest request = HttpRequest.newBuilder(config.tokenRequestUrl())
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + config.permanentApiKey())
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new SessionIssueException();
            JsonNode body = JSON.readTree(response.body());
            String token = body.path("token").asText("");
            long expiresAt = body.path("expires_at").asLong(0);
            if (token.isBlank() || expiresAt <= clock.instant().getEpochSecond()) {
                throw new SessionIssueException();
            }
            return new Session(ADAPTER_ID, config.completionUrl().toString(),
                    Instant.ofEpochSecond(expiresAt), token);
        } catch (IOException | RuntimeException failure) {
            throw new SessionIssueException();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new SessionIssueException();
        }
    }

    /** 响应对象的诊断文本不包含 endpoint 或短期 Key。 */
    public record Session(String adapterId, String endpoint, Instant expiresAt, String token) {
        @Override public String toString() {
            return "BailianSummarySessionIssuer.Session[adapterId=" + adapterId
                    + ", materials=REDACTED]";
        }
    }

    public static final class SessionIssueException extends RuntimeException {
        public SessionIssueException() { super("summary session unavailable"); }
    }
}
