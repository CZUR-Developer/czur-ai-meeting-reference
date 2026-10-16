package com.czur.aimeeting.client.display

import com.czur.aimeeting.model.TranscriptEvent
import com.czur.aimeeting.model.TranslationEvent

/**
 * 会中不可变快照，页面／浮窗消费同一份。sourceSide 为会前语义 A/B，swapped 只交换呈现；
 * 真实语言留在事件，80%／100% 透明度由 UI 按 final 呈现，本类型无 Android 依赖。
 */
data class MeetingDisplaySnapshot @JvmOverloads constructor(private val bilingual: Boolean,
    private val languageA: String, private val languageB: String, private val swapped: Boolean,
    private var rows: List<Row>, private val latestTranscriptSegmentId: String? = null) {
    init { rows = java.util.List.copyOf(rows) }
    fun bilingual(): Boolean = bilingual
    fun languageA(): String = languageA
    fun languageB(): String = languageB
    fun swapped(): Boolean = swapped
    fun rows(): List<Row> = rows
    fun latestTranscriptSegmentId(): String? = latestTranscriptSegmentId
    enum class Side { UNRESOLVED, A, B }
    /** 原文／译文可尚未到达，一源片段对应一行。 */
    data class Row(private val segmentId: String, private val transcript: TranscriptEvent?,
        private val translation: TranslationEvent?, private val sourceSide: Side, private val basis: DisplayRouter.Basis) {
        fun segmentId(): String = segmentId
        fun transcript(): TranscriptEvent? = transcript
        fun translation(): TranslationEvent? = translation
        fun sourceSide(): Side = sourceSide
        fun basis(): DisplayRouter.Basis = basis
    }
}
