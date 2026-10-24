package com.czur.reference.application.model;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 注册表只按 ID 选择发行器；不建连接、不读真实凭据。 */
class ModelSessionIssuerRegistryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final ModelSessionIssuerRegistry.Context context =
            new ModelSessionIssuerRegistry.Context(Map.of("AI_MEETING_MY_ENDPOINT", "wss://example.invalid"), CLOCK);

    @Test
    void registersAndCreatesByAdapterId() {
        ModelSessionIssuerRegistry registry = new ModelSessionIssuerRegistry()
                .register("my-asr", ctx -> (request) -> new ModelSessionIssuer.Session(
                        "my-asr", "wss://example.invalid", CLOCK.instant(), Map.of()));

        assertTrue(registry.contains("my-asr"));
        assertEquals(Set.of("my-asr"), registry.adapterIds());
        assertEquals("my-asr", registry.create("my-asr", context).issue(null).adapterId());
    }

    @Test
    void unknownAdapterExplainsHowToRegister() {
        ModelSessionIssuerRegistry registry = new ModelSessionIssuerRegistry();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> registry.create("my-asr", context));
        assertTrue(failure.getMessage().contains("未注册的模型 Adapter: my-asr"));
        assertTrue(failure.getMessage().contains("ModelSessionIssuerRegistry.register"));
        assertFalse(registry.contains("my-asr"));
    }

    @Test
    void duplicateRegistrationIsConfigurationError() {
        ModelSessionIssuerRegistry registry = new ModelSessionIssuerRegistry()
                .register("my-asr", ctx -> (request) -> null);
        assertThrows(IllegalArgumentException.class,
                () -> registry.register("my-asr", ctx -> (request) -> null));
    }

    @Test
    void blankAdapterIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelSessionIssuerRegistry().register(" ", ctx -> (request) -> null));
    }
}
