package com.czur.aimeeting.client;

import com.czur.aimeeting.model.ModelAdapter;
import com.czur.aimeeting.model.SummaryAdapter;
import com.czur.aimeeting.client.provider.BailianSummaryAdapter;
import com.czur.aimeeting.client.provider.SampleJsonModelAdapter;
import com.czur.aimeeting.client.provider.TingwuModelAdapter;
import java.util.Map;
import java.util.LinkedHashMap;
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
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Supplier<SummaryAdapter>> summaryAdapters = new LinkedHashMap<>();

    /** 默认内置听悟、第二格式样例和百炼总结；实例状态不放在注册表中。 */
    public ModelAdapterRegistry() {
        register(TingwuModelAdapter.ID, TingwuModelAdapter::new,
                Set.of(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION,
                        MeetingCapabilityState.SPEAKER));
        register(SampleJsonModelAdapter.ID, SampleJsonModelAdapter::new,
                Set.of(MeetingCapabilityState.TRANSCRIPT));
        registerSummary(BailianSummaryAdapter.ID, BailianSummaryAdapter::new);
    }

    /**
     * 合作伙伴在会议开始前注册自己的消息格式。factory 每次调用应返回新 Adapter，
     * 不能返回上场使用过的实例；可用能力是解析能力，不替代 Server 的本场授权。
     * 相同 ID 的后注册工厂替换先前工厂，便于伙伴替换默认工具，不影响已开始的会议。
     */
    public ModelAdapterRegistry register(String adapterId, ModelAdapterFactory factory,
            Set<String> capabilities) {
        entries.put(adapterId, new Entry(factory, Set.copyOf(capabilities)));
        return this;
    }

    /** 注册会后文本模型工厂；模型地址和短期凭证仍由 Server 提供。 */
    public ModelAdapterRegistry registerSummary(String adapterId, Supplier<SummaryAdapter> factory) {
        summaryAdapters.put(adapterId, factory);
        return this;
    }

    public ModelAdapter create(String adapterId) {
        return entry(adapterId).factory.create();
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

    private record Entry(ModelAdapterFactory factory, Set<String> capabilities) {}
}
