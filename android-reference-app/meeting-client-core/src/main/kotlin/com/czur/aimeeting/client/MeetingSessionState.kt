package com.czur.aimeeting.client

/** 输入接管与结果 READY 分开，能力来自本场决策；canStop 支持 Stop202 再确认，事件数只计保存原文。 */
data class MeetingSessionState(
    private val phase: Phase,
    private val meetingId: String?,
    private var availableCapabilities: Set<String>,
    private val savedEventCount: Int,
    private val active: Boolean,
    private val canStop: Boolean,
    private val notice: MeetingNotice? = null,
    private val errorCode: MeetingErrorCode? = null,
) {
    init {
        availableCapabilities = java.util.Set.copyOf(availableCapabilities)
    }
    fun phase(): Phase = phase
    fun meetingId(): String? = meetingId
    fun availableCapabilities(): Set<String> = availableCapabilities
    fun savedEventCount(): Int = savedEventCount
    fun active(): Boolean = active
    fun canStop(): Boolean = canStop
    /** 非错误提示事件；宿主自行决定展示方式。 */
    fun notice(): MeetingNotice? = notice
    /** 本场失败原因码；宿主按码映射自己的话术。 */
    fun errorCode(): MeetingErrorCode? = errorCode
    /** STARTING／CLOSED 保留名称，协调器当前不单独发出这两个阶段。 */
    enum class Phase { CREATING, STARTING, RUNNING, STOPPING, STOP_PENDING, FINALIZING, READY, ENDED, FAILED, CLOSED }
}
