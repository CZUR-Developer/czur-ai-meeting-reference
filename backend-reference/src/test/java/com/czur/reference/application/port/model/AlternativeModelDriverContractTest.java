package com.czur.reference.application.port.model;

import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityIds;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 使用与 Mock 完全相同的 TCK 验证第二个无网络固定文本 Driver。 */
class AlternativeModelDriverContractTest extends ModelDriverContractTestKit {
    private static final Set<String> CAPABILITIES = Set.of(
            CapabilityIds.TRANSCRIPT_REALTIME,
            CapabilityIds.TRANSCRIPT_FINAL);

    @Override
    protected CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor("alternative-fixed-text", "1.0.0", 1, CAPABILITIES);
    }

    @Override
    protected ModelDriver createDriver(
            CapabilityDescriptor descriptor, ModelEventSink sink) {
        return new AlternativeTextModelDriver(
                Clock.fixed(NOW, ZoneOffset.UTC),
                4,
                64);
    }

    @Override
    protected ModelDriver createBoundedDriver(
            CapabilityDescriptor descriptor, ModelEventSink sink) {
        return new AlternativeTextModelDriver(
                Clock.fixed(NOW, ZoneOffset.UTC),
                1,
                1);
    }

    @Override
    protected ModelDriver createPendingDriver(
            CapabilityDescriptor descriptor, ModelEventSink sink, Runnable beforeFinalDispatchHook) {
        return new AlternativeTextModelDriver(
                Clock.fixed(NOW, ZoneOffset.UTC),
                4,
                64,
                beforeFinalDispatchHook);
    }

    @Override
    protected boolean supportsPendingDispatchHook() {
        return true;
    }

    @org.junit.jupiter.api.Test
    void alternativeDriverHasIndependentIdentityAndSemanticOutput() {
        RecordingSink sink = new RecordingSink();
        ModelDriver driver = createDriver(descriptor(), sink);
        assertEquals("alternative-fixed-text", driver.describe().providerId());
        assertEquals(CAPABILITIES, driver.describe().capabilities());

        ModelDriverSession session = driver.openSession(
                new RealtimeSessionSpec("meeting-alt", "stream-alt", "zh-CN",
                        AudioProfile.pcm16kMono(100), CAPABILITIES, 1, 60_000, "settings-alt"),
                sink, "open-alt", context("open-alt")).valueOrThrow();
        driver.start(session, context("start-alt"));
        driver.acceptAudio(session,
                new MeetingAudioFrame(1, 0, 100, new byte[]{1, 2}, null, 1, 1, 0, Set.of()),
                context("frame-alt"));
        driver.completeInput(session, 1, "complete-alt", context("complete-alt"));

        assertEquals("alternative partial 1", sink.transcripts.getFirst().text());
        assertEquals("alternative final alternative-segment-1", sink.transcripts.get(1).text());
        assertNull(sink.transcripts.getFirst().speaker());
    }

    private static ModelOperationContext context(String operationId) {
        return ModelOperationContext.withoutCancellation(
                operationId, ModelDriverContractTestKit.NOW.plusSeconds(30));
    }
}
