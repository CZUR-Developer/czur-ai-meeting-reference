package com.czur.aimeeting.model

/**
 * 一段已确认原文的独立翻译输入。ID 和 revision 沿用已归一化的本场原文，目标语言由
 * Coordinator 按会前两种语言及实际源语种选择，交换显示栏不改变翻译语义。
 * 不包含 PCM、DOA、登录材料或会话状态；更高原文修订形成一次新的完整输入。
 */
class TranslationInput(
    private val sourceSegmentId: String,
    private val sourceRevision: Int,
    private val sourceLanguage: String,
    private val targetLanguage: String,
    private val text: String,
) {
    fun sourceSegmentId(): String = sourceSegmentId
    fun sourceRevision(): Int = sourceRevision
    fun sourceLanguage(): String = sourceLanguage
    fun targetLanguage(): String = targetLanguage
    fun text(): String = text

    /**
     * 将模型文字变为同一原文对应的标准 final 译文；重试保持 ID/revision，不制造新片段。
     * 模型检测到更准确源语种时可以传入，但不得用展示栏名称改写真实源语种。
     */
    @JvmOverloads fun result(text: String, sourceLanguage: String = this.sourceLanguage): TranslationEvent =
        TranslationEvent("text-translation:$sourceSegmentId:$targetLanguage", sourceSegmentId, sourceRevision,
            sourceLanguage, targetLanguage, text, true)
}
