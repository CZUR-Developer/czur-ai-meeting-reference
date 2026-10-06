package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.ModelAdapterRegistry;

import com.czur.aimeeting.client.MeetingCapabilityState;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.czur.aimeeting.client.provider.SampleJsonModelAdapter;
import com.czur.aimeeting.client.provider.TingwuModelAdapter;
import com.czur.aimeeting.client.provider.BailianSummaryAdapter;
import org.junit.Test;

/** 两种协议只在注册表分流，每场取得全新 Adapter 状态。 */
public final class ModelAdapterRegistryTest {
    @Test
    public void selectsAdapterByBackendGrantId() {
        ModelAdapterRegistry registry = new ModelAdapterRegistry();
        assertEquals(TingwuModelAdapter.ID, registry.create(TingwuModelAdapter.ID).id());
        assertEquals(SampleJsonModelAdapter.ID,
                registry.create(SampleJsonModelAdapter.ID).id());
        assertNotSame(registry.create(SampleJsonModelAdapter.ID),
                registry.create(SampleJsonModelAdapter.ID));
        assertTrue(registry.setupCapabilities().contains(MeetingCapabilityState.TRANSCRIPT));
        assertTrue(registry.setupCapabilities().contains(MeetingCapabilityState.TRANSLATION));
        assertTrue(registry.setupCapabilities().contains(MeetingCapabilityState.SUMMARY));
        assertEquals(BailianSummaryAdapter.ID,
                registry.createSummary(BailianSummaryAdapter.ID).id());
        assertTrue(registry.realtimeCapabilities(TingwuModelAdapter.ID)
                .contains(MeetingCapabilityState.TRANSLATION));
        assertFalse(registry.realtimeCapabilities(SampleJsonModelAdapter.ID)
                .contains(MeetingCapabilityState.TRANSLATION));
    }
}
