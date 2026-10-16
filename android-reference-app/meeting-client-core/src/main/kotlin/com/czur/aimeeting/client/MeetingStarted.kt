package com.czur.aimeeting.client

/** 采音／模型启动前确定的单调毫秒起点与服务器时长上限；提醒和自动停止由 App 实现。 */
data class MeetingStarted(
    private val meetingId: String,
    private val startedAtMs: Long,
    private val maxMeetingDurationMs: Long,
    private var availableCapabilities: Set<String>,
) {
    init {
        availableCapabilities = java.util.Set.copyOf(availableCapabilities)
    }
    fun meetingId(): String = meetingId
    fun startedAtMs(): Long = startedAtMs
    fun maxMeetingDurationMs(): Long = maxMeetingDurationMs
    fun availableCapabilities(): Set<String> = availableCapabilities
}
