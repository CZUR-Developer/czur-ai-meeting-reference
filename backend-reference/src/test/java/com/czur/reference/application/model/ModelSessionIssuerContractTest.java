package com.czur.reference.application.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelSessionIssuerContractTest {
    private static final Instant EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void acceptsWsAndWssEndpointsAndDefensivelyCopiesInitParams() {
        var mutable = new java.util.HashMap<String, String>();
        mutable.put("taskId", "task-1");
        var session = new ModelSessionIssuer.Session(
                "tingwu-realtime", "wss://join.example.invalid/meeting",
                EXPIRES_AT, mutable);
        mutable.put("taskId", "changed");
        assertEquals("task-1", session.initParams().get("taskId"));
        assertThrows(UnsupportedOperationException.class,
                () -> session.initParams().put("x", "y"));

        assertNotNull(new ModelSessionIssuer.Session(
                "sample-json-ws", "ws://127.0.0.1/model", EXPIRES_AT, Map.of()));
    }

    @Test
    void rejectsBlankAdapterInvalidSchemeMissingHostAndNullMaterials() {
        assertThrows(IllegalArgumentException.class, () -> new ModelSessionIssuer.Session(
                " ", "wss://join.example.invalid/meeting", EXPIRES_AT, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ModelSessionIssuer.Session(
                "adapter", "https://join.example.invalid/meeting", EXPIRES_AT, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ModelSessionIssuer.Session(
                "adapter", "wss:///meeting", EXPIRES_AT, Map.of()));
        assertThrows(NullPointerException.class, () -> new ModelSessionIssuer.Session(
                "adapter", "wss://join.example.invalid/meeting", null, Map.of()));
        assertThrows(NullPointerException.class, () -> new ModelSessionIssuer.Session(
                "adapter", "wss://join.example.invalid/meeting", EXPIRES_AT, null));
    }

    @Test
    void requestRejectsBlankSourceBlankTargetAndBlankMeetingId() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelSessionIssuer.Request(" ", List.of("en-US"), false));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelSessionIssuer.Request("zh-CN", List.of(" "), false));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelSessionIssuer.Request("zh-CN", List.of(), false, " "));
        assertDoesNotThrow(
                () -> new ModelSessionIssuer.Request("zh-CN", List.of(), false, null));
    }
}
