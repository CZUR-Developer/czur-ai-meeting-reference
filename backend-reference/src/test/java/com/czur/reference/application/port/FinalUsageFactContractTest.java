package com.czur.reference.application.port;

import com.czur.reference.application.port.entitlement.UsageUnit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证最终用量事实只从输入事实固化、单位兼容且诊断输出脱敏。 */
class FinalUsageFactContractTest {
    private static final String MEETING_ID = "meeting-final-usage-sensitive";
    private static final String INPUT_BINDING = "input-binding-sensitive";
    private static final String DIGEST = "sha256-final-usage-sensitive";
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-21T03:04:05Z");

    @Test
    void fromInputCopiesStableSourceAndUsesStandardDurationUsage() {
        FinalizationInputFact input = inputFact(4_200, INPUT_BINDING, DIGEST);

        FinalUsageFact fact = FinalUsageFact.fromInput(input);

        assertEquals(MEETING_ID, fact.meetingId());
        assertEquals(UsageUnit.MEETING_DURATION_MS, fact.actual().unit());
        assertEquals(4_200, fact.actual().amount());
        assertEquals(INPUT_BINDING, fact.sourceInputBinding());
        assertEquals(DIGEST, fact.sourceInputDigest());
        assertEquals(ACCEPTED_AT, fact.sourceAcceptedAt());
    }

    @Test
    void repeatedConstructionFromPersistedInputDoesNotReadClockOrRecalculate() {
        FinalizationInputFact input = inputFact(9_001, INPUT_BINDING, DIGEST);

        FinalUsageFact first = FinalUsageFact.fromInput(input);
        FinalUsageFact retry = FinalUsageFact.fromInput(input);

        assertNotSame(first, retry);
        assertEquals(first, retry);
        assertEquals(first.hashCode(), retry.hashCode());
    }

    @Test
    void nullInputFailsClosed() {
        assertThrows(NullPointerException.class, () -> FinalUsageFact.fromInput(null));
    }

    @Test
    void sourceBindingAndDigestRemainPartOfTheImmutableFactIdentity() {
        FinalUsageFact original = FinalUsageFact.fromInput(
                inputFact(100, INPUT_BINDING, DIGEST));
        FinalUsageFact differentBinding = FinalUsageFact.fromInput(
                inputFact(100, "another-input-binding", DIGEST));
        FinalUsageFact differentDigest = FinalUsageFact.fromInput(
                inputFact(100, INPUT_BINDING, "sha256-another-digest"));

        assertFalse(original.equals(differentBinding));
        assertFalse(original.equals(differentDigest));
    }

    @Test
    void diagnosticsDoNotExposeMeetingBindingOrDigest() {
        FinalUsageFact fact = FinalUsageFact.fromInput(inputFact(12, INPUT_BINDING, DIGEST));

        String text = fact.toString();

        assertFalse(text.contains(MEETING_ID));
        assertFalse(text.contains(INPUT_BINDING));
        assertFalse(text.contains(DIGEST));
        assertEquals("FinalUsageFact[meetingId=redacted"
                        + ", actualUnit=MEETING_DURATION_MS"
                        + ", actualAmount=12"
                        + ", sourceInputBinding=redacted"
                        + ", sourceInputDigestPresent=true"
                        + ", sourceAcceptedAt=2026-09-21T03:04:05Z]",
                text);
    }

    private static FinalizationInputFact inputFact(
            long durationMs,
            String inputBinding,
            String digest) {
        return new FinalizationInputFact(
                MEETING_ID,
                inputBinding,
                17,
                durationMs,
                "zh-CN",
                List.of("en-US"),
                ACCEPTED_AT,
                digest);
    }
}
