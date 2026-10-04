package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.model.ModelAdapter;
import com.czur.aimeeting.reference.sample.SampleJsonModelAdapter;
import com.czur.aimeeting.reference.tingwu.TingwuModelAdapter;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 本参考 APK 的构建期模型协议注册表。后端只下发 adapterId 和会话材料；
 * 伙伴接入不同格式时增加公开 Adapter 并在这里登记，不修改会议页面或 SDK。
 */
public final class ModelAdapterRegistry {
    private final Map<String, Entry> entries = Map.of(
            TingwuModelAdapter.ID, new Entry(TingwuModelAdapter::new,
                    Set.of(MeetingCapabilityState.TRANSCRIPT)),
            SampleJsonModelAdapter.ID, new Entry(SampleJsonModelAdapter::new,
                    Set.of(MeetingCapabilityState.TRANSCRIPT)));

    public ModelAdapter create(String adapterId) {
        return entry(adapterId).factory.get();
    }

    /** 随 Adapter 一起登记的可归一化实时能力；会后产物由后端能力决定。 */
    public Set<String> realtimeCapabilities(String adapterId) {
        return entry(adapterId).capabilities;
    }

    /** 会前只展示 App 已注册 Adapter 能处理的模式；本场仍以 Create 决策为准。 */
    public Set<String> setupCapabilities() {
        return entries.values().stream().flatMap(entry -> entry.capabilities.stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Entry entry(String adapterId) {
        Entry entry = entries.get(adapterId);
        if (entry == null) {
            throw new IllegalArgumentException("参考 App 未注册模型 Adapter: " + adapterId);
        }
        return entry;
    }

    private record Entry(Supplier<ModelAdapter> factory, Set<String> capabilities) {}
}
