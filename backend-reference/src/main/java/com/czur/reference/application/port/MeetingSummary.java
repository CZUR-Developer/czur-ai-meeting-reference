package com.czur.reference.application.port;

import com.czur.reference.domain.meeting.MeetingState;

import java.time.Instant;
import java.util.Objects;

/**
 * 会议列表使用的最小摘要。
 *
 * <p>摘要不携带主体、Grant、Reservation、幂等材料、结果 URL 或会议正文。
 * 标题和摘要正文也不属于本阶段端口；需要展示名称时由未来的公开 DTO 在
 * 明确的隐私策略下另行定义，避免把用户正文带入事实查询契约。</p>
 */
public record MeetingSummary(
        String meetingId,
        String title,
        MeetingState state,
        InputState inputState,
        ManifestStatus manifestState,
        Instant createdAt,
        Instant updatedAt) {

    public MeetingSummary {
        requireText("meetingId", meetingId);
        if (title != null && title.isBlank()) {
            throw new IllegalArgumentException("title must be null or non-blank");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(inputState, "inputState");
        Objects.requireNonNull(manifestState, "manifestState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    /** 摘要只输出稳定标识和状态，不输出任何正文或归属事实。 */
    @Override
    public String toString() {
        return "MeetingSummary[meetingId=" + meetingId
                + ", state=" + state
                + ", inputState=" + inputState
                + ", manifestState=" + manifestState
                + ", title=redacted]";
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
