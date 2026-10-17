package com.czur.aimeeting.model

/**
 * 模型确认句开始的事实，不含原始消息或显示侧。segmentId 与后续字幕稳定 ID 相同，
 * Adapter 输出 startMs 为本次模型任务收到音频的相对毫秒；SDK 加一次连接首帧偏移后，
 * 向 App／会议 core 交付本场完整录音相对毫秒。显示层此时绑定方向，缺事件时首条文字兜底。
 */
data class SegmentStartedEvent(private val segmentId: String, private val startMs: Long) {
    fun segmentId(): String = segmentId
    fun startMs(): Long = startMs
}
