package com.czur.aimeeting.reference.meeting

import com.czur.aimeeting.client.MeetingResult
import java.util.Locale

/** 把已类型化的 READY 产物转成 PDF 章节，App 不解释协议 JSON。 */
object LocalPdfContent {
    enum class Kind { HEADING, TEXT, IMAGE }
    data class Entry(private val kind: Kind, private val text: String, private val jpeg: ByteArray?) {
        fun kind() = kind
        fun text() = text
        fun jpeg() = jpeg
    }

    @JvmStatic fun from(result: MeetingResult): List<Entry> {
        val entries = mutableListOf<Entry>()
        // 该工具只处理已读回正文的 READY 产物；插件会先显式 readContent。
        val content = result.content()!!
        if (content.artifactTypes().contains("transcript.final")) {
            entries += heading("字幕")
            if (content.transcriptSegments().isNotEmpty()) {
                val speakers = mutableListOf<String>()
                for (segment in content.transcriptSegments()) {
                    entries += text(segment.text())
                    val id = segment.sessionSpeakerId()
                    if (!id.isNullOrBlank() && !speakers.contains(id.trim())) speakers += id.trim()
                }
                if (speakers.isNotEmpty()) {
                    entries += heading("本场发言人")
                    for (id in speakers) entries += text("本场发言人 ID：$id")
                }
            } else entries += text(content.transcriptText())
        }
        if (content.artifactTypes().contains("translation.final")) {
            entries += heading("译文")
            for (item in content.translations()) entries += text("${item.sourceLanguage()} → ${item.targetLanguage()}：${item.text()}")
        }
        content.summary()?.let { summary ->
            entries += heading("结构化纪要")
            entries += text(summary.title())
            entries += text(summary.overview())
            appendItems(entries, "关键要点", summary.keyPoints())
            appendItems(entries, "待办事项", summary.actionItems())
        }
        var hasFrames = false
        for (frame in content.presentationFrames()) {
            if (!hasFrames) { entries += heading("演示画面"); hasFrames = true }
            val seconds = frame.offsetMs() / 1000L
            entries += Entry(Kind.IMAGE, String.format(Locale.ROOT, "会议 %02d:%02d", seconds / 60, seconds % 60), frame.jpeg())
        }
        if (entries.isEmpty()) entries += text("本场没有可用的最终产物")
        return entries.toList()
    }

    private fun appendItems(entries: MutableList<Entry>, label: String, items: List<String>) {
        if (items.isEmpty()) return
        entries += text(label)
        for (item in items) entries += text("• $item")
    }
    private fun heading(value: String) = Entry(Kind.HEADING, value, null)
    private fun text(value: String) = Entry(Kind.TEXT, value, null)
}
