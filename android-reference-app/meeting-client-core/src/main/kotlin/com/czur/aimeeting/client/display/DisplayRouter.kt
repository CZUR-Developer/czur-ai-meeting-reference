package com.czur.aimeeting.client.display

/**
 * 单场双语分栏：明确方向优先，模糊／缺失才按模型语种、原文字形降级。
 * 首次定侧按 segmentId 锁定，partial／final 不搬栏；未定不锁以便后续事实补齐。
 * 交换只改呈现。角度由音频 SDK 提供，不把 -1／361／NaN 当有效方向。
 */
class DisplayRouter(languageA: String?, languageB: String?) {
    enum class Basis { SEGMENT_DIRECTION, STABLE_DIRECTION, MODEL_LANGUAGE, TEXT_LANGUAGE, LANGUAGE_UNRESOLVED }
    /** side0 暂未定栏，1 为 A，2 为 B；只锁 1/2。 */
    data class Route(private val side: Int, private val basis: Basis) {
        fun side(): Int = side
        fun basis(): Basis = basis
        fun languageFallback(): Boolean = basis == Basis.MODEL_LANGUAGE || basis == Basis.TEXT_LANGUAGE ||
            basis == Basis.LANGUAGE_UNRESOLVED
    }
    private val languageA = BilingualLanguageResolver.normalize(languageA)
    private val languageB = BilingualLanguageResolver.normalize(languageB)
    private val locked = HashMap<String, Route>()
    private val segmentDirections = HashMap<String, Int>()
    private val segmentAngles = HashMap<String, Float>()
    private var stableAngle = Float.NaN

    fun updateStableAngle(angle: Float) { stableAngle = if (angle in 0f..360f) angle else Float.NaN }
    /** 片段方向在首次显示前提供，优先于稳定角。 */
    fun setSegmentDirection(segmentId: String, side: Int) {
        if ((side == 1 || side == 2) && !locked.containsKey(segmentId)) segmentDirections[segmentId] = side
    }
    /** 包括无方向在内的句开始快照，不被下一句覆盖。 */
    fun onSegmentStarted(segmentId: String) { segmentAngles.putIfAbsent(segmentId, stableAngle) }
    fun isLanguageBased(segmentId: String): Boolean {
        val angle = segmentAngles.getOrDefault(segmentId, stableAngle)
        return angle >= 0f && angle < 180f || angle > 260f && angle < 280f
    }
    fun hasConfiguredLanguage(language: String?): Boolean = languageSide(language) != 0
    /** 等待期结束／停止时按原二选一，数字同分选 A，不改模型真实语种。 */
    fun forceLocalFallback(segmentId: String, text: String?): Route {
        locked[segmentId]?.let { return it }
        val resolution = BilingualLanguageResolver.resolve(text, languageA, languageB)
        val route = Route(if (resolution.language() == languageA) 1 else 2, Basis.TEXT_LANGUAGE)
        locked[segmentId] = route
        return route
    }
    fun route(segmentId: String, modelLanguage: String?, sourceText: String?): Route {
        locked[segmentId]?.let { return it }
        val segmentSide = segmentDirections.remove(segmentId)
        onSegmentStarted(segmentId)
        val angle = segmentAngles.getValue(segmentId)
        val decided = when {
            segmentSide != null -> Route(segmentSide, Basis.SEGMENT_DIRECTION)
            angle in 180f..260f -> Route(1, Basis.STABLE_DIRECTION)
            angle in 280f..360f -> Route(2, Basis.STABLE_DIRECTION)
            else -> {
                val byModel = languageSide(modelLanguage)
                val byText = textSide(sourceText)
                when {
                    byModel != 0 -> Route(byModel, Basis.MODEL_LANGUAGE)
                    byText != 0 -> Route(byText, Basis.TEXT_LANGUAGE)
                    else -> Route(0, Basis.LANGUAGE_UNRESOLVED)
                }
            }
        }
        if (decided.side() != 0) locked[segmentId] = decided
        return decided
    }
    fun existingRoute(segmentId: String): Route? = locked[segmentId]
    private fun languageSide(language: String?): Int = when (BilingualLanguageResolver.normalize(language)) {
        "", "und", "mul" -> 0
        languageA -> 1
        languageB -> 2
        else -> 0
    }
    private fun textSide(text: String?): Int {
        val resolution = BilingualLanguageResolver.resolve(text, languageA, languageB)
        return if (resolution.scoreA() == 0L && resolution.scoreB() == 0L) 0
            else if (resolution.language() == languageA) 1 else 2
    }
}
