package com.czur.reference.application.port;

/**
 * 服务端对会议输入接管进度的公开状态。
 *
 * <p>该状态与 {@code MeetingState} 分开保存，但接管边界必须原子推进：
 * {@code STOPPING + INPUT_COMPLETE_PENDING} 只能一起变为
 * {@code FINALIZING + INPUT_ACCEPTED}，不能公布中间组合。输入已经被持久化接管
 * 仍不等于最终产物已经 {@code READY}。其中
 * {@link #INPUT_COMPLETE_SENT} 不属于服务端状态，它只存在于 Android App
 * 的本地恢复日志，因此这里刻意不提供这个枚举值。</p>
 */
public enum InputState {
    /** 会议仍接受输入，App 不能清理本地恢复数据。 */
    INPUT_OPEN,
    /** 已记录输入完成意图，但后端尚未完成持久化接管。 */
    INPUT_COMPLETE_PENDING,
    /** 后端已经持久化接管输入，App 可以依据 receipt 清理恢复数据。 */
    INPUT_ACCEPTED,
    /** 输入被取消，不再等待后端接管。 */
    INPUT_CANCELLED
}
