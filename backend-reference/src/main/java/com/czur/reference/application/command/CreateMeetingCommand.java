package com.czur.reference.application.command;

import java.time.Instant;
import java.util.Objects;

/**
 * 应用层的“创建会议”命令。
 *
 * <p>命令只描述调用方提交的业务意图，不携带模型、存储或认证实现。
 * {@code idempotencyKey} 必须在客户端重试期间保持不变；服务端据此把
 * 网络重试与新的创建请求区分开来。命令对象是不可变的，适合在 HTTP、
 * 消息队列或测试适配器之间传递。</p>
 */
public record CreateMeetingCommand(
        String meetingId,
        String deploymentScope,
        String clientSessionId,
        String profileId,
        int profileRevision,
        Instant requestedAt,
        String idempotencyKey) {

    /**
     * 兼容 P2-T06 之前的调用方。
     *
     * <p>旧调用没有部署范围概念，统一归入 {@code default}。新代码应优先
     * 使用包含 {@code deploymentScope} 的构造函数，并从部署配置或认证主体
     * 显式传入稳定范围标识，不能把终端用户输入直接当作范围。</p>
     */
    public CreateMeetingCommand(
            String meetingId,
            String clientSessionId,
            String profileId,
            int profileRevision,
            Instant requestedAt,
            String idempotencyKey) {
        this(meetingId, "default", clientSessionId, profileId, profileRevision, requestedAt,
                idempotencyKey);
    }

    public CreateMeetingCommand {
        requireText("meetingId", meetingId);
        requireText("deploymentScope", deploymentScope);
        requireText("clientSessionId", clientSessionId);
        requireText("profileId", profileId);
        requireText("idempotencyKey", idempotencyKey);
        if (profileRevision <= 0) {
            throw new IllegalArgumentException("profileRevision must be positive");
        }
        Objects.requireNonNull(requestedAt, "requestedAt");
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
