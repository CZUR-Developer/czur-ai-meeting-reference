package com.czur.aimeeting.model

/** 归一化实时字幕监听器，可能在非 UI 线程调用。 */
fun interface TranscriptListener {
    /** 同一 segmentId 的更高 revision 替换旧文字，isFinal 为 true 后成为最终文字。 */
    fun onTranscript(event: TranscriptEvent)
}
