package com.czur.aimeeting.model

/** 标准纪要，不含模型原始回包或访问材料；列表在构造时冻结。 */
class SummaryResult(
    private val language: String,
    private val title: String,
    private val overview: String,
    keyPoints: List<String>,
    actionItems: List<String>,
) {
    private val keyPoints = java.util.List.copyOf(keyPoints)
    private val actionItems = java.util.List.copyOf(actionItems)
    init { require(language.isNotBlank() && title.isNotBlank() && overview.isNotBlank()) { "纪要缺少必要文字" } }
    fun language(): String = language
    fun title(): String = title
    fun overview(): String = overview
    fun keyPoints(): List<String> = keyPoints
    fun actionItems(): List<String> = actionItems

    override fun equals(other: Any?): Boolean = other is SummaryResult && language == other.language &&
        title == other.title && overview == other.overview && keyPoints == other.keyPoints && actionItems == other.actionItems
    override fun hashCode(): Int = ((((language.hashCode() * 31 + title.hashCode()) * 31 + overview.hashCode()) * 31 +
        keyPoints.hashCode()) * 31 + actionItems.hashCode())
    override fun toString(): String = "SummaryResult[language=$language, title=$title, overview=$overview, " +
        "keyPoints=$keyPoints, actionItems=$actionItems]"
}
