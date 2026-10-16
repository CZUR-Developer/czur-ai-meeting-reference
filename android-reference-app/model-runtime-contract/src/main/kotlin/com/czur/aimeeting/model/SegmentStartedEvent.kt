package com.czur.aimeeting.model

/**
 * 模型确认句开始的事实，不含原始消息或显示侧。segmentId 与后续字幕稳定 ID 相同，
 * startMs 为本场音频相对毫秒。显示层此时绑定方向，缺少事件时可在首条文字兜底。
 */
data class SegmentStartedEvent(private val segmentId: String, private val startMs: Long) {
    fun segmentId(): String = segmentId
    fun startMs(): Long = startMs
}
