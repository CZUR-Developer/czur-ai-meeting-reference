package com.czur.reference.application.port.model;

import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityIds;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Set;

/** 使用公共 TCK 验证参考 Mock Driver。 */
class MockModelDriverContractTest extends ModelDriverContractTestKit {
    private static final Set<String> CAPABILITIES = Set.of(
            CapabilityIds.TRANSCRIPT_REALTIME,
            CapabilityIds.TRANSCRIPT_FINAL,
            CapabilityIds.SPEAKER_DIARIZATION_SESSION);

    @Override
    protected CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor("tck-mock", "1.0.0", 1, CAPABILITIES);
    }

    @Override
    protected ModelDriver createDriver(
            CapabilityDescriptor descriptor, ModelEventSink sink) {
        return new MockModelDriver(
                descriptor,
                Clock.fixed(NOW, ZoneOffset.UTC),
                4,
                64);
    }

    @Override
    protected ModelDriver createBoundedDriver(
            CapabilityDescriptor descriptor, ModelEventSink sink) {
        return new MockModelDriver(
                descriptor,
                Clock.fixed(NOW, ZoneOffset.UTC),
                1,
                1);
    }

    @Override
    protected ModelDriver createPendingDriver(
            CapabilityDescriptor descriptor, ModelEventSink sink, Runnable beforeFinalDispatchHook) {
        return new MockModelDriver(
                descriptor,
                Clock.fixed(NOW, ZoneOffset.UTC),
                4,
                64,
                beforeFinalDispatchHook);
    }

    @Override
    protected boolean supportsPendingDispatchHook() {
        return true;
    }
}
