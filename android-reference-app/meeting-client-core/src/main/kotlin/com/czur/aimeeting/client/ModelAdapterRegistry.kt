package com.czur.aimeeting.client

import com.czur.aimeeting.client.provider.BailianSummaryAdapter
import com.czur.aimeeting.client.provider.SampleJsonModelAdapter
import com.czur.aimeeting.client.provider.SampleJsonTranslationAdapter
import com.czur.aimeeting.client.provider.TingwuModelAdapter
import com.czur.aimeeting.model.ModelAdapter
import com.czur.aimeeting.model.SummaryAdapter
import com.czur.aimeeting.model.TranslationAdapter
import java.util.function.Supplier

/** 构建期模型注册表，后端仅提供 ID 与材料，伙伴通过注册解析器接入，不修改页面或 SDK。 */
class ModelAdapterRegistry {
    private val entries = LinkedHashMap<String, Entry>()
    private val summaryAdapters = LinkedHashMap<String, Supplier<SummaryAdapter>>()
    private val translationAdapters = LinkedHashMap<String, Supplier<TranslationAdapter>>()
    init {
        register(TingwuModelAdapter.ID, ::TingwuModelAdapter, setOf(MeetingCapabilityState.TRANSCRIPT,
            MeetingCapabilityState.TRANSLATION, MeetingCapabilityState.SPEAKER))
        register(SampleJsonModelAdapter.ID, ::SampleJsonModelAdapter, setOf(MeetingCapabilityState.TRANSCRIPT))
        registerSummary(BailianSummaryAdapter.ID, ::BailianSummaryAdapter)
        registerTranslation(SampleJsonTranslationAdapter.ID, ::SampleJsonTranslationAdapter)
    }
    /** 同 ID 后注册覆盖前者，不影响在途会议；工厂须每次返回独立状态，本声明不替代 Server 授权。 */
    fun register(adapterId: String, factory: ModelAdapterFactory, capabilities: Set<String>): ModelAdapterRegistry {
        entries[adapterId] = Entry(factory, java.util.Set.copyOf(capabilities))
        return this
    }
    /** 会后文本工厂，不持有部署模型地址或短期材料。 */
    fun registerSummary(adapterId: String, factory: Supplier<SummaryAdapter>): ModelAdapterRegistry {
        summaryAdapters[adapterId] = factory
        return this
    }
    /** 可选逐段文本翻译工厂；材料由本场 modelSession.translationSession 选择，不替代授权。 */
    fun registerTranslation(adapterId: String, factory: Supplier<TranslationAdapter>): ModelAdapterRegistry {
        translationAdapters[adapterId] = factory
        return this
    }
    fun hasTranslation(adapterId: String): Boolean = translationAdapters.containsKey(adapterId)
    fun createTranslation(adapterId: String): TranslationAdapter = (translationAdapters[adapterId]
        ?: throw IllegalArgumentException("参考 App 未注册独立翻译 Adapter: $adapterId")).get()
    fun create(adapterId: String): ModelAdapter = entry(adapterId).factory.create()
    fun realtimeCapabilities(adapterId: String): Set<String> = entry(adapterId).capabilities
    /**
     * 本机可执行能力，用作部署能力的求交一端。纪要正文由服务端按语言列表生成，执行位置
     * 在部署侧，所以 `summary.server` 恒定可用，不要求本机注册文本总结 Adapter；设备直连
     * 兼容期的 `summary.structured` 仍只在确有 Adapter 时声明。纯服务端部署据此不再被
     * 本机能力交集挡住纪要。
     */
    fun setupCapabilities(): Set<String> {
        val ready = entries.values.flatMap { it.capabilities }.filter(READY_MEETING_CAPABILITIES::contains).toMutableSet()
        ready.add(MeetingCapabilityState.SUMMARY_SERVER)
        if (summaryAdapters.isNotEmpty()) ready.add(MeetingCapabilityState.SUMMARY)
        if (translationAdapters.isNotEmpty()) ready.add(MeetingCapabilityState.TRANSLATION)
        return java.util.Set.copyOf(ready)
    }
    fun createSummary(adapterId: String): SummaryAdapter = (summaryAdapters[adapterId]
        ?: throw IllegalArgumentException("参考 App 未注册文本总结 Adapter: $adapterId")).get()
    private fun entry(adapterId: String): Entry = entries[adapterId]
        ?: throw IllegalArgumentException("参考 App 未注册模型 Adapter: $adapterId")
    private data class Entry(val factory: ModelAdapterFactory, val capabilities: Set<String>)
    companion object {
        private val READY_MEETING_CAPABILITIES = setOf(MeetingCapabilityState.TRANSCRIPT,
            MeetingCapabilityState.TRANSLATION, MeetingCapabilityState.SPEAKER)
    }
}
