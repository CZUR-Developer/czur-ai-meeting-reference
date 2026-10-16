package com.czur.aimeeting.model

/**
 * 译文的一次修订。sourceSegmentId 绑定同场原文，translationId 在 partial／final 间稳定。
 * App 分别更新显示，并按本场 clientSequence 提交最终产物；原始回包不进入后端。
 */
data class TranslationEvent(
    private val translationId: String,
    private val sourceSegmentId: String,
    private val revision: Int,
    private val sourceLanguage: String,
    private val targetLanguage: String,
    private val text: String,
    private val isFinal: Boolean,
) {
    fun translationId(): String = translationId
    fun sourceSegmentId(): String = sourceSegmentId
    fun revision(): Int = revision
    fun sourceLanguage(): String = sourceLanguage
    fun targetLanguage(): String = targetLanguage
    fun text(): String = text
    fun isFinal(): Boolean = isFinal
}
