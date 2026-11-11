package com.czur.aimeeting.reference.meeting

import com.czur.aimeeting.client.ModelAdapterRegistry
import com.czur.aimeeting.client.MeetingCapabilityState
import com.czur.aimeeting.client.provider.SampleJsonModelAdapter
import com.czur.aimeeting.client.provider.TingwuModelAdapter
import com.czur.aimeeting.client.provider.BailianSummaryAdapter
import org.junit.Assert.*
import org.junit.Test

class ModelAdapterRegistryTest {
    @Test fun selectsAdapterByBackendGrantId() {
        val registry = ModelAdapterRegistry()
        assertEquals(TingwuModelAdapter.ID, registry.create(TingwuModelAdapter.ID).id())
        assertEquals(SampleJsonModelAdapter.ID, registry.create(SampleJsonModelAdapter.ID).id())
        assertNotSame(registry.create(SampleJsonModelAdapter.ID), registry.create(SampleJsonModelAdapter.ID))
        assertTrue(registry.setupCapabilities().contains(MeetingCapabilityState.TRANSCRIPT))
        assertTrue(registry.setupCapabilities().contains(MeetingCapabilityState.TRANSLATION))
        assertTrue(registry.setupCapabilities().contains(MeetingCapabilityState.SUMMARY))
        assertTrue("服务端纪要不需要本机注册文本总结工厂",
            registry.setupCapabilities().contains(MeetingCapabilityState.SUMMARY_SERVER))
        assertEquals(BailianSummaryAdapter.ID, registry.createSummary(BailianSummaryAdapter.ID).id())
        assertTrue(registry.realtimeCapabilities(TingwuModelAdapter.ID).contains(MeetingCapabilityState.TRANSLATION))
        assertFalse(registry.realtimeCapabilities(SampleJsonModelAdapter.ID).contains(MeetingCapabilityState.TRANSLATION))
    }
}
