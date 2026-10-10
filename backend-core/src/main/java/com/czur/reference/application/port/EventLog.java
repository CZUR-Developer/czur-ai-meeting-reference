package com.czur.reference.application.port;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        String specVersion,
        String meetingId,
        String streamId,
        String eventId,
        long eventSequence,
        Instant emittedAt,
        String eventType,
        String canonicalPayloadJson) {

    public EventLog {
        requireText("specVersion", specVersion);
        requireText("meetingId", meetingId);
        requireText("streamId", streamId);
        requireText("eventId", eventId);
        if (eventSequence <= 0) {
            throw new IllegalArgumentException("eventSequence must be positive");
        }
        Objects.requireNonNull(emittedAt, "emittedAt");
        // PostgreSQL timestamptz 和参考 JDBC 事实源都以微秒为持久化精度。统一在
        // 端口对象构造时截断，确保内存实现、JDBC 首次写入和同 eventId 重放
        // 比较观察到同一个时间事实，而不是由各适配器各自静默舍入。
        emittedAt = emittedAt.truncatedTo(ChronoUnit.MICROS);
        requireText("eventType", eventType);
        requireText("canonicalPayloadJson", canonicalPayloadJson);
    }

    /** 事件日志诊断只输出可定位元数据，不输出字幕、总结或模型响应正文。 */
    @Override
    public String toString() {
        return "EventLog[specVersion=" + specVersion
                + ", meetingId=" + meetingId
                + ", streamId=" + streamId
                + ", eventId=" + eventId
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
