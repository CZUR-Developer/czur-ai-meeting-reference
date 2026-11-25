package com.czur.aimeeting.client

import com.czur.aimeeting.model.SummaryInput
import com.czur.aimeeting.model.TranscriptEvent
import java.io.File
import java.util.logging.Logger
import org.json.JSONObject

/**
 * 本场最终字幕的本地追加日志，供原场现场保留与诊断使用。
 *
 * 纪要正文已改由服务端生成，Coordinator 不再从本日志重建总结输入；`summaryInput`
 * 仅为既有测试／旧调用保留，不参与正常停止或原场重跑。
 */
internal class TranscriptJournal(private val file: File) {
    fun file(): File = file

    @Synchronized
    fun append(event: TranscriptEvent) {
        if (!event.isFinal() || event.text().isBlank()) return
        try {
            val line = JSONObject()
                .put("segmentId", event.segmentId())
                .put("revision", event.revision())
                .put("startMs", event.startMs())
                .put("language", event.language())
                .put("text", event.text())
                .toString()
            file.appendText(line + "\n")
        } catch (failure: Exception) {
            // 日志失败不能打断字幕与停止；续办会按缺原文处理。
            LOG.warning("字幕日志写入失败 class=${failure.javaClass.simpleName}")
        }
    }

    /** 读回最高修订并按时序拼接；没有可用原文时返回 null，不伪造空纪要。 */
    @Synchronized
    fun summaryInput(language: String): SummaryInput? {
        if (!file.isFile) return null
        val latest = HashMap<String, JSONObject>()
        try {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try {
                    val item = JSONObject(line)
                    val segmentId = item.optString("segmentId")
                    if (segmentId.isBlank()) return@forEachLine
                    val revision = item.optInt("revision", 0)
                    val previous = latest[segmentId]
                    if (previous == null || revision > previous.optInt("revision", 0)) latest[segmentId] = item
                } catch (_: Exception) {
                    // 断电残行或历史坏行跳过，不影响其余原文。
                }
            }
        } catch (failure: Exception) {
            LOG.warning("字幕日志读取失败 class=${failure.javaClass.simpleName}")
            return null
        }
        val ordered = latest.values.sortedWith(
            compareBy<JSONObject> { it.optLong("startMs", 0L) }.thenBy { it.optString("segmentId") })
        val text = ordered.map { it.optString("text") }.filter { it.isNotBlank() }.joinToString("\n")
        if (text.isBlank()) return null
        return SummaryInput(language, text)
    }

    @Synchronized
    fun delete() {
        try {
            if (file.exists() && !file.delete()) LOG.warning("字幕日志删除失败")
        } catch (failure: Exception) {
            LOG.warning("字幕日志删除失败 class=${failure.javaClass.simpleName}")
        }
    }

    companion object {
        private val LOG = Logger.getLogger(TranscriptJournal::class.java.name)
    }
}
