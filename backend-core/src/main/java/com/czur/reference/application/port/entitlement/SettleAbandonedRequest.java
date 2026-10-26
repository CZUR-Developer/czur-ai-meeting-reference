package com.czur.reference.application.port.entitlement;

import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 服务端弃场收口的最终用量提交请求。
 *
 * <p>弃场由服务端判定，客户端没有对应的显式命令，因此部署、主体、预占和会议
 * 都必须由调用方从已持久化事实复制，不能取当前请求上下文或账户默认值。
 * {@code actual} 是服务端推导出的零或时长用量；它不来自客户端上报。</p>
 *
 * <p>幂等键只由会议标识确定：同一场会议无论重放多少次都落到同一个
 * {@code usage.commit.v1} 操作，不会因为窗口重算或进程重启生成第二条结算事实。
 * 密钥长度受数据库冻结列约束，超长标识在构造时即拒绝。</p>
 */
public record SettleAbandonedRequest(
        String deploymentScope,
        String subjectScope,
        String reservationId,
        String meetingId,
        UsageActual actual) {

    /** 弃场结算的稳定键前缀；后缀是会议标识本身。 */
    public static final String IDEMPOTENCY_KEY_PREFIX = "meeting.abandon.v1:";
    private static final int MAX_IDEMPOTENCY_KEY_UTF8_BYTES = 256;

    public SettleAbandonedRequest {
        requireText("deploymentScope", deploymentScope);
        requireText("subjectScope", subjectScope);
        requireText("reservationId", reservationId);
        MeetingIdPolicy.requireValid(meetingId);
        Objects.requireNonNull(actual, "actual");
        int keyBytes = (IDEMPOTENCY_KEY_PREFIX + meetingId)
                .getBytes(StandardCharsets.UTF_8).length;
        if (keyBytes > MAX_IDEMPOTENCY_KEY_UTF8_BYTES) {
            throw new IllegalArgumentException(
                    "abandon idempotency key must not exceed 256 UTF-8 bytes");
        }
    }

    /** 同一场会议唯一的弃场结算键；重放必须复用它，不能按轮次生成新键。 */
    public String idempotencyKey() {
        return IDEMPOTENCY_KEY_PREFIX + meetingId;
    }

    /**
     * 只公开可诊断的 scope 是否具备与用量数值；预占、会议和幂等键都不进入日志。
     */
    @Override
    public String toString() {
        return "SettleAbandonedRequest[deploymentScope=redacted, subjectScope=redacted"
                + ", reservationId=redacted, meetingId=redacted"
                + ", usageUnit=" + actual.unit()
                + ", usageAmount=" + actual.amount() + ']';
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
