package com.czur.reference.application.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BailianSummarySessionIssuerTest {
    @Test void issuesTemporaryKeyFromLocalHttpServiceWithoutReturningPermanentKey()
            throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC);
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/tokens", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().toString());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("{\"token\":\"st-local-only\",\"expires_at\":"
                    + clock.instant().plusSeconds(300).getEpochSecond() + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var config = new BailianSummarySessionConfig(
                    base.resolve("/api/v1/tokens"), base.resolve("/api/v1/apps"),
                    "app-local", "permanent-local-only", 300);
            var session = new BailianSummarySessionIssuer(config, clock).issue();
            assertEquals("POST", method.get());
            assertEquals("/api/v1/tokens?expire_in_seconds=300", path.get());
            assertEquals("Bearer permanent-local-only", authorization.get());
            assertEquals("bailian-summary", session.adapterId());
            assertEquals(base + "/api/v1/apps/app-local/completion", session.endpoint());
            assertEquals("st-local-only", session.token());
            assertFalse(config.toString().contains("permanent-local-only"));
            assertFalse(session.toString().contains("st-local-only"));
        } finally {
            server.stop(0);
        }
    }

    @Test void disabledDeploymentNeedsNoKeyAndTtlMustMatchProviderRange() {
        assertEquals(null, BailianSummarySessionConfig.fromEnvironment(Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new BailianSummarySessionConfig(URI.create("https://example.test/api/v1/tokens"),
                        URI.create("https://example.test/api/v1/apps"), "app-test", "key", 1801));
    }
}
