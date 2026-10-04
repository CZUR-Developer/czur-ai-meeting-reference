package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.model.ModelAdapter;
import com.czur.aimeeting.model.SummaryAdapter;
import com.czur.aimeeting.reference.bailian.BailianSummaryAdapter;
import com.czur.aimeeting.reference.sample.SampleJsonModelAdapter;
import com.czur.aimeeting.reference.tingwu.TingwuModelAdapter;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 本参考 APK 的构建期模型协议注册表。后端只下发 adapterId 和会话材料；
 * 伙伴接入不同格式时增加公开 Adapter 并在这里登记，不修改会议页面或 SDK。
 */
public final class ModelAdapterRegistry {
    /** 已接通会中显示、标准事件提交和 Stop/结果的会前模式；真实双向会议仍需验收。 */
    private static final Set<String> READY_MEETING_CAPABILITIES =
            Set.of(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION);
    private final Map<String, Entry> entries = Map.of(
            TingwuModelAdapter.ID, new Entry(TingwuModelAdapter::new,
                    Set.of(MeetingCapabilityState.TRANSCRIPT,
                            MeetingCapabilityState.TRANSLATION,
                            MeetingCapabilityState.SPEAKER)),
            SampleJsonModelAdapter.ID, new Entry(SampleJsonModelAdapter::new,
                    Set.of(MeetingCapabilityState.TRANSCRIPT)));
    private final Map<String, Supplier<SummaryAdapter>> summaryAdapters = Map.of(
            BailianSummaryAdapter.ID, BailianSummaryAdapter::new);

    public ModelAdapter create(String adapterId) {
        return entry(adapterId).factory.get();
    }

    /** 随 Adapter 一起登记的可归一化实时能力；会后产物由后端能力决定。 */
    public Set<String> realtimeCapabilities(String adapterId) {
        return entry(adapterId).capabilities;
    }

    /** 会前仅开放已接通的模式；当前只有听悟 Adapter 登记实时翻译能力。 */
    public Set<String> setupCapabilities() {
        Set<String> ready = entries.values().stream()
                .flatMap(entry -> entry.capabilities.stream())
                .filter(READY_MEETING_CAPABILITIES::contains)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        if (!summaryAdapters.isEmpty()) ready.add(MeetingCapabilityState.SUMMARY);
        return Set.copyOf(ready);
    }

    public SummaryAdapter createSummary(String adapterId) {
        Supplier<SummaryAdapter> factory = summaryAdapters.get(adapterId);
        if (factory == null) {
            throw new IllegalArgumentException("参考 App 未注册文本总结 Adapter: " + adapterId);
        }
        return factory.get();
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
