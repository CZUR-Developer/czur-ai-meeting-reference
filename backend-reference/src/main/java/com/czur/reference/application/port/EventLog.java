package com.czur.reference.application.port;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条可重放的规范会议事件日志记录。
 *
 * <p>事件正文使用已经通过协议校验的 canonical JSON 保存为不透明文本；本层
 * 不解析模型厂商 JSON，也不在 {@link #toString()} 中输出正文。事件序号在同一
 * 会议内单调递增，REST 补拉和实时传输都应复用这份记录，而不是建立第二套
 * 事件模型。</p>
 */
public record EventLog(
        String eventId,
        String meetingId,
        long eventSequence,
        Instant emittedAt,
        String eventType,
        String canonicalPayloadJson) {

    public EventLog {
        requireText("eventId", eventId);
        requireText("meetingId", meetingId);
        if (eventSequence <= 0) {
            throw new IllegalArgumentException("eventSequence must be positive");
        }
        Objects.requireNonNull(emittedAt, "emittedAt");
        requireText("eventType", eventType);
        requireText("canonicalPayloadJson", canonicalPayloadJson);
    }

    /** 事件日志诊断只输出可定位元数据，不输出字幕、总结或模型响应正文。 */
    @Override
    public String toString() {
        return "EventLog[eventId=" + eventId
                + ", meetingId=" + meetingId
                + ", eventSequence=" + eventSequence
                + ", eventType=" + eventType
                + ", payload=redacted]";
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
