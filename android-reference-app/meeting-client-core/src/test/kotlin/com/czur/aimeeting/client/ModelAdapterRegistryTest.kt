package com.czur.aimeeting.client

import com.czur.aimeeting.client.provider.SampleJsonModelAdapter
import com.czur.aimeeting.client.provider.TingwuModelAdapter
import com.czur.aimeeting.model.*
import org.junit.Assert.*
import org.junit.Test

/** 模型工厂注册、能力及旧解析入口的默认方法转发。 */
class ModelAdapterRegistryTest {
    @Test fun defaultsCreateIndependentAdapters() {
        val registry = ModelAdapterRegistry()
        assertEquals(TingwuModelAdapter.ID, registry.create(TingwuModelAdapter.ID).id())
        assertTrue(registry.create(TingwuModelAdapter.ID).mayEmitDeferredSourceLanguage())
        assertFalse(registry.create(SampleJsonModelAdapter.ID).mayEmitDeferredSourceLanguage())
        assertNotSame(registry.create(TingwuModelAdapter.ID), registry.create(TingwuModelAdapter.ID))
        assertNotSame(registry.create(SampleJsonModelAdapter.ID), registry.create(SampleJsonModelAdapter.ID))
        assertTrue(registry.setupCapabilities().contains(MeetingCapabilityState.SUMMARY))
        assertTrue(registry.setupCapabilities().contains(MeetingCapabilityState.SPEAKER))
    }
    @Test fun partnerRegistersItsFactoryAndCapabilities() {
        val registry = ModelAdapterRegistry()
        registry.register("partner", ::LegacyAdapter, setOf(MeetingCapabilityState.TRANSCRIPT))
        assertEquals("partner", registry.create("partner").id())
        assertNotSame(registry.create("partner"), registry.create("partner"))
        assertEquals(setOf(MeetingCapabilityState.TRANSCRIPT), registry.realtimeCapabilities("partner"))
    }
    @Test fun lifecycleOverloadKeepsOldPartnerAdapterWorking() {
        val events = ArrayList<TranscriptEvent>()
        val begins = ArrayList<String>()
        val adapter: ModelAdapter = LegacyAdapter()
        assertFalse("旧伙伴实现默认不等待源语种", adapter.mayEmitDeferredSourceLanguage())
        assertEquals(ModelSignal.NONE, adapter.onServerMessage("text", { events.add(it) }, {}, { begins.add(it.segmentId()) }))
        assertEquals("text", events[0].text()); assertTrue(begins.isEmpty())
    }
    private class LegacyAdapter : ModelAdapter {
        override fun id(): String = "partner"
        override fun startMessage(grant: ModelSessionGrant): String = "start"
        override fun onServerMessage(message: String, listener: TranscriptListener): ModelSignal {
            listener.onTranscript(TranscriptEvent("one", 1, 0, 100, "en-US", message, false))
            return ModelSignal.NONE
        }
    }
}
