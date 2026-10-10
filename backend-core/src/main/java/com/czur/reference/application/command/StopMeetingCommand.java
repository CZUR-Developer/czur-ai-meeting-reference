package com.czur.reference.application.command;

import java.time.Instant;
import java.util.Objects;

/**
 * 应用层的“停止会议”命令。
 *
 * <p>停止请求只负责记录停止意图并推动领域状态机；输入接管、最终收口
 * 和结果产物由后续 Worker 处理。重复提交同一幂等键必须返回第一次请求
 * 的结果，避免客户端超时重试造成重复副作用。</p>
 */
public record StopMeetingCommand(String meetingId, Instant requestedAt, String idempotencyKey) {
    public StopMeetingCommand {
        if (meetingId == null || meetingId.isBlank()) {
            throw new IllegalArgumentException("meetingId must not be blank");
        }
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}
