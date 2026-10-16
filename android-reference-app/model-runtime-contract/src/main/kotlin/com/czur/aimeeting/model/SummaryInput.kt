package com.czur.aimeeting.model

/** 最终字幕齐备后交给总结 Adapter 的标准输入，不含音频或长期密钥。 */
data class SummaryInput(private val language: String, private val transcript: String) {
    init { require(language.isNotBlank() && transcript.isNotBlank()) { "纪要需要语言和最终字幕" } }
    fun language(): String = language
    fun transcript(): String = transcript
}
