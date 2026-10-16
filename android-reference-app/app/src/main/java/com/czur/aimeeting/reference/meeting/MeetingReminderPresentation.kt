package com.czur.aimeeting.reference.meeting

/** 将单调时钟阈值转换为短时可见提醒，不改变会议自动停止规则。 */
class MeetingReminderPresentation {
    enum class Kind { NONE, TIME_LIMIT, NO_TRANSCRIPT }
    data class Notice(private val kind: Kind, private val title: String, private val message: String) {
        fun kind() = kind
        fun title() = title
        fun message() = message
        companion object { @JvmField val NONE = Notice(Kind.NONE, "", "") }
    }

    private var limitTriggered = false
    private var limitDismissed = false
    private var idleDismissed = false
    private var limitStartedAtMs = 0L
    private var visibleKind = Kind.NONE

    /** 确认只关闭当前提示；新转写后的下一次无转写区间仍会重新提醒。 */
    fun confirm() {
        if (visibleKind == Kind.TIME_LIMIT) limitDismissed = true
        if (visibleKind == Kind.NO_TRANSCRIPT) idleDismissed = true
        visibleKind = Kind.NONE
    }

    fun at(clock: MeetingTimeRule.Snapshot): Notice {
        if (clock.stopCause() != MeetingTimeRule.StopCause.NONE) {
            visibleKind = Kind.NONE
            return Notice.NONE
        }
        if (clock.limitWarning() && !limitTriggered) {
            limitTriggered = true
            limitStartedAtMs = clock.elapsedMs()
        }
        if (!clock.noTranscriptWarning()) idleDismissed = false
        if (clock.noTranscriptWarning() && !idleDismissed) {
            visibleKind = Kind.NO_TRANSCRIPT
            return Notice(Kind.NO_TRANSCRIPT, "长时间无转写",
                "连续 4 分钟未收到新转写，会议将在 " +
                    MeetingTimeRule.format(clock.noTranscriptRemainingMs()) +
                    " 后自动结束。\n收到新转写后此提醒会关闭。")
        }
        if (limitTriggered && !limitDismissed && clock.elapsedMs() - limitStartedAtMs < LIMIT_VISIBLE_MS) {
            visibleKind = Kind.TIME_LIMIT
            return Notice(Kind.TIME_LIMIT, "会议时长提醒", "本场会议剩余约 20 分钟，请留意会议结束时间。")
        }
        visibleKind = Kind.NONE
        return Notice.NONE
    }

    companion object { private const val LIMIT_VISIBLE_MS = 10_000L }
}
