package com.czur.aimeeting.reference.meeting

import com.czur.aimeeting.client.display.DisplayRouter
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot

/**
 * 页面与窗口共用的无状态呈现工具。输入已由 SDK 配对、分栏；本工具只投影实际左右，
 * 不判断方向角、语种、修订顺序，不维护第二份会议状态。
 */
object DisplaySnapshotPresentation {
    data class SubtitleRow(private val segmentId: String, private val text: String, private val alpha: Float) {
        fun segmentId() = segmentId
        fun text() = text
        fun alpha() = alpha
    }
    /** 原译始终同侧；side 是交换后的物理侧，UNRESOLVED 不猜测发言侧。 */
    data class SpeakerColumnRow(
        private val segmentId: String, private val side: MeetingDisplaySnapshot.Side,
        private val source: String, private val translated: String,
        private val sourceAlpha: Float, private val translatedAlpha: Float,
        private val languageFallback: Boolean
    ) {
        fun segmentId() = segmentId
        fun side() = side
        fun source() = source
        fun translated() = translated
        fun sourceAlpha() = sourceAlpha
        fun translatedAlpha() = translatedAlpha
        fun languageFallback() = languageFallback
    }

    @JvmStatic fun speakerColumn(row: MeetingDisplaySnapshot.Row, swapped: Boolean): SpeakerColumnRow {
        var side = row.sourceSide()
        if (swapped && side != MeetingDisplaySnapshot.Side.UNRESOLVED) {
            side = if (side == MeetingDisplaySnapshot.Side.A) MeetingDisplaySnapshot.Side.B else MeetingDisplaySnapshot.Side.A
        }
        val fallback = row.basis() == DisplayRouter.Basis.MODEL_LANGUAGE ||
            row.basis() == DisplayRouter.Basis.TEXT_LANGUAGE || row.basis() == DisplayRouter.Basis.LANGUAGE_UNRESOLVED
        val source = row.transcript()
        val translated = row.translation()
        return SpeakerColumnRow(row.segmentId(), side, source?.text().orEmpty(), translated?.text().orEmpty(),
            if (source == null || source.isFinal()) 1f else .8f,
            if (translated == null || translated.isFinal()) 1f else .8f, fallback)
    }

    @JvmStatic fun leftLabel(snapshot: MeetingDisplaySnapshot) = languageLabel(if (snapshot.swapped()) snapshot.languageB() else snapshot.languageA())
    @JvmStatic fun rightLabel(snapshot: MeetingDisplaySnapshot) = languageLabel(if (snapshot.swapped()) snapshot.languageA() else snapshot.languageB())
    private fun languageLabel(language: String) = when {
        language.startsWith("zh") -> "中文"
        language.startsWith("en") -> "English"
        else -> language
    }

    /** 译文先到时字幕页跳过该行，互译页仍可呈现译文。 */
    @JvmStatic fun subtitle(row: MeetingDisplaySnapshot.Row): SubtitleRow? = row.transcript()?.let {
        SubtitleRow(row.segmentId(), it.text(), if (it.isFinal()) 1f else .8f)
    }

    /** 采用 SDK 指定的最近原文段，迟到修订不改变应用层事件顺序。 */
    @JvmStatic fun latestSubtitle(snapshot: MeetingDisplaySnapshot): SubtitleRow? {
        for (row in snapshot.rows()) {
            if (row.segmentId() == snapshot.latestTranscriptSegmentId()) return subtitle(row)
        }
        return SubtitleRow("", "等待字幕…", .8f)
    }
}
