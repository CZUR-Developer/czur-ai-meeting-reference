package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingCommandContext;

import java.util.Objects;

/**
 * 认证删除会议的应用输入。
 *
 * <p>删除只表示撤销访问并发起异步清理，不要求端口同步物理删除所有
 * Artifact。幂等键和规范摘要用于服务端重放，但不能通过
 * {@link #toString()}、日志或错误结果暴露；客户端也不能提交或覆盖部署范围。</p>
 */
public record DeleteRequest(
        MeetingCommandContext context,
        String meetingId,
        String idempotencyKey,
        String canonicalRequestDigest) {

    public DeleteRequest {
        Objects.requireNonNull(context, "context");
        requireText("meetingId", meetingId);
        requireText("idempotencyKey", idempotencyKey);
        requireText("canonicalRequestDigest", canonicalRequestDigest);
    }

    /** 只保留会议标识和已认证事实，隐藏幂等键、摘要及主体信息。 */
    @Override
    public String toString() {
        return "DeleteRequest[meetingId=" + meetingId + ", authenticated=true]";
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
