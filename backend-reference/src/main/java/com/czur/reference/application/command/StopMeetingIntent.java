package com.czur.reference.application.command;

/**
 * 客户端提交的“停止会议”业务意图。
 *
 * <p>停止摘要只由协议版本和 {@code meetingId} 决定；幂等键用于定位请求记录，
 * 但不是业务意图本身，因此不进入摘要。请求时间、主体和部署范围由服务端上下文
 * 提供，也不得添加到该客户端对象。</p>
 */
public record StopMeetingIntent(
        String meetingId,
        String idempotencyKey) {

    public StopMeetingIntent {
        requireText("meetingId", meetingId);
        requireText("idempotencyKey", idempotencyKey);
    }

    /** 返回不包含幂等键的安全诊断摘要。 */
    @Override
    public String toString() {
        return "StopMeetingIntent[meetingId=" + meetingId + ']';
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
