package com.czur.aimeeting.client

import com.czur.aimeeting.model.SummaryInput
import com.czur.aimeeting.model.TranscriptEvent

/** 每段只保留最新 final，供结束后生成总结，不保存模型原始响应。 */
class FinalTranscriptCollector {
    private val latest = HashMap<String, TranscriptEvent>()
    @Synchronized fun clear() { latest.clear() }
    @Synchronized fun apply(event: TranscriptEvent) {
        if (!event.isFinal()) return
        val previous = latest[event.segmentId()]
        if (previous == null || event.revision() > previous.revision()) latest[event.segmentId()] = event
    }
    @Synchronized fun input(language: String): SummaryInput {
        val text = latest.values.sortedWith(compareBy(TranscriptEvent::startMs).thenBy(TranscriptEvent::segmentId))
            .map(TranscriptEvent::text).filter { it.isNotBlank() }.joinToString("\n")
        return SummaryInput(language, text)
    }
}
