package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import com.czur.aimeeting.reference.sample.SampleJsonModelAdapter;
import com.czur.aimeeting.reference.tingwu.TingwuModelAdapter;
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
    }
}
