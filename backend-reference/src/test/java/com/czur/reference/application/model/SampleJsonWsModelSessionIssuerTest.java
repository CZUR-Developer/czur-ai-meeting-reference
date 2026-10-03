package com.czur.reference.application.model;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 不启动网络，锁定第二格式演示签发材料的公开形状。 */
class SampleJsonWsModelSessionIssuerTest {
    @Test
    void issuesConfiguredWebSocketEndpointAndDistinctAdapterId() {
        var now = Instant.parse("2026-10-03T00:00:00Z");
        var issuer = new SampleJsonWsModelSessionIssuer(
                URI.create("ws://127.0.0.1:19091/model"), Clock.fixed(now, ZoneOffset.UTC));
        var session = issuer.issue();
        assertEquals("sample-json-ws", session.adapterId());
        assertEquals("ws://127.0.0.1:19091/model", session.endpoint());
        assertEquals(now.plusSeconds(300), session.expiresAt());
        assertEquals(Map.of(), session.initParams());
        assertFalse(session.toString().contains("127.0.0.1"));
    }
}
