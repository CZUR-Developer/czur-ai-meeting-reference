package com.czur.aimeeting.client;

import java.util.Set;

/**
 * 输入接管与 READY 分开；availableCapabilities 是本场实际启用能力，不是厂商能力推断。
 * canStop 支持既有 Stop 202 后由用户再次确认的入口；savedEventCount 只计成功保存的原文事件。
 * active 为会议还未终结，FINALIZING 期间不接受重复 Stop。取消 close 不生成完成或结果通知。
 */
public record MeetingSessionState(Phase phase, String meetingId, Set<String> availableCapabilities,
        int savedEventCount, boolean active, boolean canStop, String message) {
    public MeetingSessionState { availableCapabilities = Set.copyOf(availableCapabilities); }
    /** STARTING/CLOSED 保留阶段名称；当前 Coordinator 不单独发出这两个阶段。 */
    public enum Phase { CREATING, STARTING, RUNNING, STOPPING, STOP_PENDING, FINALIZING, READY, ENDED, FAILED, CLOSED }
}
