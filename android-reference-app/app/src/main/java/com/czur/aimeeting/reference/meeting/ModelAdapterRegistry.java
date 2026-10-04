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
    /** 会前可交付流程；译文解析已具备，但翻译会议的 Stop/结果与分栏尚未完成。 */
    private static final Set<String> READY_MEETING_CAPABILITIES =
            Set.of(MeetingCapabilityState.TRANSCRIPT);
    private final Map<String, Entry> entries = Map.of(
            TingwuModelAdapter.ID, new Entry(TingwuModelAdapter::new,
                    Set.of(MeetingCapabilityState.TRANSCRIPT,
                            MeetingCapabilityState.TRANSLATION)),
            SampleJsonModelAdapter.ID, new Entry(SampleJsonModelAdapter::new,
                    Set.of(MeetingCapabilityState.TRANSCRIPT)));

    public ModelAdapter create(String adapterId) {
        return entry(adapterId).factory.get();
    }

    /** 随 Adapter 一起登记的可归一化实时能力；会后产物由后端能力决定。 */
    public Set<String> realtimeCapabilities(String adapterId) {
        return entry(adapterId).capabilities;
    }

    /**
     * 会前仅开放已具备完整会议收口的模式。听悟 Adapter 已能回调译文，但后端 Stop
     * 和按方向分栏尚未接通，不能因此把互译入口作为可交付功能提前开放。
     */
    public Set<String> setupCapabilities() {
        return entries.values().stream().flatMap(entry -> entry.capabilities.stream())
                .filter(READY_MEETING_CAPABILITIES::contains)
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
