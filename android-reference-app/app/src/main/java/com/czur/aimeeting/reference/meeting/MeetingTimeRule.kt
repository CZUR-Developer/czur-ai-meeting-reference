package com.czur.aimeeting.reference.meeting

import java.util.Locale

/**
 * 一场会议的时长与无转写阈值。输入单调时钟，便于原测试直接跳到阈值。
 * 这里只给出提示和停止原因，实际停止仍走 Service 的唯一收口流程。
 */
class MeetingTimeRule(private val startedAtMs: Long, private val maxDurationMs: Long) {
    enum class StopCause { NONE, TIME_LIMIT, NO_TRANSCRIPT }

    data class Snapshot(
        private val elapsedMs: Long,
        private val remainingMs: Long,
        private val limitWarning: Boolean,
        private val noTranscriptWarning: Boolean,
        private val noTranscriptRemainingMs: Long,
        private val stopCause: StopCause
    ) {
        fun elapsedMs() = elapsedMs
        fun remainingMs() = remainingMs
        fun limitWarning() = limitWarning
        fun noTranscriptWarning() = noTranscriptWarning
        fun noTranscriptRemainingMs() = noTranscriptRemainingMs
        fun stopCause() = stopCause
    }

    private var lastTranscriptAtMs = startedAtMs

    init { require(maxDurationMs > 0) { "会议时长上限必须大于零" } }

    /** 任意非空原文 partial/final 都刷新计时；译文不单独作为恢复信号。 */
    fun onTranscript(nowMs: Long, text: String?) {
        if (!text.isNullOrBlank()) onTranscriptActivity(nowMs)
    }

    /** SDK 确认原文活跃后通知，保持与开场起点一致的单调时基。 */
    fun onTranscriptActivity(nowMs: Long) { lastTranscriptAtMs = nowMs }

    fun at(nowMs: Long): Snapshot {
        val elapsed = maxOf(0L, nowMs - startedAtMs)
        val remaining = maxOf(0L, maxDurationMs - elapsed)
        val idle = maxOf(0L, nowMs - lastTranscriptAtMs)
        val stop = when {
            elapsed >= maxDurationMs -> StopCause.TIME_LIMIT
            idle >= NO_TEXT_STOP_MS -> StopCause.NO_TRANSCRIPT
            else -> StopCause.NONE
        }
        return Snapshot(elapsed, remaining,
            maxDurationMs > LIMIT_WARNING_MS && remaining <= LIMIT_WARNING_MS,
            idle >= NO_TEXT_WARNING_MS && idle < NO_TEXT_STOP_MS,
            maxOf(0L, NO_TEXT_STOP_MS - idle), stop)
    }

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val LIMIT_WARNING_MS = 20 * MINUTE_MS
        private const val NO_TEXT_WARNING_MS = 4 * MINUTE_MS
        private const val NO_TEXT_STOP_MS = 5 * MINUTE_MS

        /** 页面与悬浮窗共用的 HH:mm:ss 格式。 */
        @JvmStatic fun format(millis: Long): String {
            val seconds = maxOf(0L, millis) / 1000L
            return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600L,
                seconds / 60L % 60L, seconds % 60L)
        }
    }
}
