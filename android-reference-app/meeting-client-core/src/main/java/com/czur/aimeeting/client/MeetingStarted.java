package com.czur.aimeeting.client;

import java.util.Set;

/** 采音/模型启动前确定的本场单调毫秒起点和 Server 时长上限；提醒与自动停止仍由 App 实现。 */
public record MeetingStarted(String meetingId, long startedAtMs, long maxMeetingDurationMs,
        Set<String> availableCapabilities) {
    public MeetingStarted { availableCapabilities = Set.copyOf(availableCapabilities); }
}
