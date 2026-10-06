package com.czur.aimeeting.client;

import static org.junit.Assert.*;
import com.czur.aimeeting.client.provider.SampleJsonModelAdapter;
import com.czur.aimeeting.client.provider.TingwuModelAdapter;
import com.czur.aimeeting.model.ModelAdapter;
import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSignal;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranscriptListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** 模型插件的正常接入检查：工厂注册、能力和旧 Adapter 默认方法兼容。 */
public final class ModelAdapterRegistryTest {
    @Test public void defaultsCreateIndependentAdapters() {
        ModelAdapterRegistry registry = new ModelAdapterRegistry();
        assertEquals(TingwuModelAdapter.ID, registry.create(TingwuModelAdapter.ID).id());
        assertNotSame(registry.create(TingwuModelAdapter.ID), registry.create(TingwuModelAdapter.ID));
        assertNotSame(registry.create(SampleJsonModelAdapter.ID), registry.create(SampleJsonModelAdapter.ID));
        assertTrue(registry.setupCapabilities().contains(MeetingCapabilityState.SUMMARY));
    }

    @Test public void partnerRegistersItsFactoryAndCapabilities() {
        ModelAdapterRegistry registry = new ModelAdapterRegistry();
        registry.register("partner", LegacyAdapter::new, Set.of(MeetingCapabilityState.TRANSCRIPT));
        assertEquals("partner", registry.create("partner").id());
        assertNotSame(registry.create("partner"), registry.create("partner"));
        assertEquals(Set.of(MeetingCapabilityState.TRANSCRIPT), registry.realtimeCapabilities("partner"));
    }

    @Test public void lifecycleOverloadKeepsOldPartnerAdapterWorking() {
        List<TranscriptEvent> events = new ArrayList<>();
        List<String> begins = new ArrayList<>();
        ModelAdapter adapter = new LegacyAdapter();
        assertEquals(ModelSignal.NONE, adapter.onServerMessage("text", events::add,
                event -> { }, event -> begins.add(event.segmentId())));
        assertEquals("text", events.get(0).text());
        assertTrue(begins.isEmpty());
    }

    private static final class LegacyAdapter implements ModelAdapter {
        @Override public String id() { return "partner"; }
        @Override public String startMessage(ModelSessionGrant grant) { return "start"; }
        @Override public ModelSignal onServerMessage(String message, TranscriptListener listener) {
            listener.onTranscript(new TranscriptEvent("one", 1, 0, 100, "en-US", message, false));
            return ModelSignal.NONE;
        }
    }
}
