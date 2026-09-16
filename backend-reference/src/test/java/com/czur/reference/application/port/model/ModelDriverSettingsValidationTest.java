package com.czur.reference.application.port.model;

import com.czur.reference.domain.capability.CapabilityDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证每个 Driver 必须显式执行的配置和调用上下文门禁。 */
class ModelDriverSettingsValidationTest {
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private MockModelDriver driver;

    @BeforeEach
    void setUp() {
        driver = new MockModelDriver(
                new CapabilityDescriptor("settings-mock", Set.of()),
                Clock.fixed(NOW, ZoneOffset.UTC),
                1,
                1);
    }

    @Test
    void validContextPassesExplicitDriverValidation() {
        ModelDriverResult<Void> result = driver.validateSettings(
                ModelOperationContext.withoutCancellation("validate", NOW.plusSeconds(10)));

        assertTrue(result.isSuccess());
        assertTrue(result instanceof ModelDriverResult.VoidSuccess);
    }

    @Test
    void nullContextIsRejectedWithoutFailOpen() {
        ModelDriverResult<Void> result = driver.validateSettings(null);

        assertFalse(result.isSuccess());
        assertEquals(ModelDriverError.Code.INVALID_ARGUMENT, result.errorOrThrow().code());
    }

    @Test
    void cancelledContextIsRejectedWithoutFailOpen() {
        ModelDriverResult<Void> result = driver.validateSettings(
                new ModelOperationContext("cancelled", NOW.plusSeconds(10), () -> true));

        assertFalse(result.isSuccess());
        assertEquals(ModelDriverError.Code.CANCELLED, result.errorOrThrow().code());
    }

    @Test
    void expiredContextIsRejectedWithoutFailOpen() {
        ModelDriverResult<Void> result = driver.validateSettings(
                ModelOperationContext.withoutCancellation("expired", NOW));

        assertFalse(result.isSuccess());
        assertEquals(ModelDriverError.Code.TIMEOUT, result.errorOrThrow().code());
    }
}
