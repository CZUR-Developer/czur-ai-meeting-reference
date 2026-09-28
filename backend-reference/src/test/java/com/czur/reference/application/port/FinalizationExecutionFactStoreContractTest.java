package com.czur.reference.application.port;

import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.capability.ProductProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link FinalizationExecutionFactStore} 纯 Java 结果不变量和脱敏门禁。 */
class FinalizationExecutionFactStoreContractTest {
    @Test
    void persistResultRequiresStatusAndWinnerFact() {
        FinalizationExecutionFact fact = fact();

        for (FinalizationExecutionFactStore.PersistStatus status
                : FinalizationExecutionFactStore.PersistStatus.values()) {
            var result = new FinalizationExecutionFactStore.PersistResult(status, fact);
            assertEquals(status, result.status());
            assertEquals(fact, result.fact());
        }
        assertThrows(NullPointerException.class,
                () -> new FinalizationExecutionFactStore.PersistResult(null, fact));
        assertThrows(NullPointerException.class,
                () -> new FinalizationExecutionFactStore.PersistResult(
                        FinalizationExecutionFactStore.PersistStatus.APPLIED, null));
    }

    @Test
    void persistResultDiagnosticTextDoesNotExposeExecutionFact() {
        String diagnostic = new FinalizationExecutionFactStore.PersistResult(
                FinalizationExecutionFactStore.PersistStatus.CONFLICT, fact()).toString();

        assertEquals("FinalizationExecutionFactStore.PersistResult[status=CONFLICT"
                + ", fact=redacted]", diagnostic);
        for (String secret : List.of(
                "mtg_execution_store_contract", "private-title", "profile-private",
                "asr.private", "summary.private")) {
            assertFalse(diagnostic.contains(secret), secret);
        }
    }

    private static FinalizationExecutionFact fact() {
        ProductProfile profile = new ProductProfile(
                "profile-private",
                7,
                Set.of("asr.private"),
                Set.of("summary.private"),
                Map.of(),
                Map.of("summary.private", ProductProfile.MissingCapabilityPolicy.DISABLE),
                Map.of(),
                Map.of("asr.private", List.of("realtime-caption")),
                Map.of("asr.private", List.of("transcript.final")));
        CapabilityPlan plan = new CapabilityPlan(11, List.of(
                new CapabilityDecision(
                        "asr.private",
                        CapabilityDecision.Availability.AVAILABLE,
                        true,
                        CapabilityDecision.Action.ENABLE,
                        null,
                        List.of("realtime-caption"),
                        List.of("transcript.final"),
                        null,
                        11),
                new CapabilityDecision(
                        "summary.private",
                        CapabilityDecision.Availability.UNAVAILABLE,
                        false,
                        CapabilityDecision.Action.DISABLE,
                        null,
                        List.of(),
                        List.of(),
                        "PROVIDER_CAPABILITY_MISSING",
                        11)));
        return FinalizationExecutionFact.freeze(
                "mtg_execution_store_contract",
                Instant.parse("2026-09-28T08:00:00.123456Z"),
                "private-title",
                profile,
                plan);
    }
}
