package com.czur.aimeeting.client

/** 最终译文关联，真实语种不因显示侧变化。 */
data class FinalTranslation(
    private val translationId: String,
    private val sourceSegmentId: String,
    private val sourceLanguage: String,
    private val targetLanguage: String,
    private val text: String,
) {
    fun translationId(): String = translationId
    fun sourceSegmentId(): String = sourceSegmentId
    fun sourceLanguage(): String = sourceLanguage
    fun targetLanguage(): String = targetLanguage
    fun text(): String = text
}
