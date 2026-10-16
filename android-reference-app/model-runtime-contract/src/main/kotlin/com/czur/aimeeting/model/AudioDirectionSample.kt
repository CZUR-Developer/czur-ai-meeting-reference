package com.czur.aimeeting.model

/**
 * SDK 从系统音频能力读取的声源到达方向（DOA）样本。角度是系统物理量，SDK 只做技术性平滑；
 * 左右栏、语种回退及片段锁侧由应用决定。回调可来自非 UI 线程，应与字幕排入同一 UI 队列。
 * 时间戳使用 Android elapsedRealtime 单调时钟毫秒，不能作为服务器时间。
 *
 * AVAILABLE 时角度为 0～360°；其他状态使用 Float.NaN，不能将旧角度伪装成本次观测。
 */
class AudioDirectionSample(
    private val angleDegrees: Float,
    private val stableAngleDegrees: Float,
    private val sampledAtElapsedRealtimeMs: Long,
    private val status: Status,
) {
    /** 区分暂未检测到声源与平台完全没有方向能力。 */
    enum class Status {
        /** 本次读到有效物理角度，原始和稳定角度均可用于本次决策。 */
        AVAILABLE,
        /** 支持方向能力，但本次没有有效声源；基础层不返回伪造角度。 */
        NO_DIRECTION,
        /** 当前音源没有方向能力；标准 MIC 和 Debug WAV 使用此状态。 */
        UNSUPPORTED,
    }

    /** 原始物理角度，不可用时为 Float.NaN。 */
    fun angleDegrees(): Float = angleDegrees
    /** 技术平滑后的角度，不含左右栏或语种业务含义。 */
    fun stableAngleDegrees(): Float = stableAngleDegrees
    /** 本次读取的单调时钟毫秒。 */
    fun sampledAtElapsedRealtimeMs(): Long = sampledAtElapsedRealtimeMs
    /** 本次能力及观测状态。 */
    fun status(): Status = status
}
