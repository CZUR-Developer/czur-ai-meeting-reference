package com.czur.reference.application.command;

import com.czur.reference.application.port.OutboxStore;
import com.czur.reference.domain.meeting.MeetingAggregate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 生成会议命令对应的稳定 Outbox 事件。
 *
 * <p>legacy 命令链与 owned 创建工作单元必须调用同一个工厂，确保同一会议和
 * 事件类型得到完全相同的 eventId、事件类型、canonical JSON 字段顺序和时间
 * 语义。这里不追加事件、不打开事务，也不包含主体、Grant、Reservation、Token、
 * 音频或会议正文；持久化仍由调用方当前工作单元负责。</p>
 */
public final class MeetingCommandEventFactory {
    private static final String MEETING_CREATED = "MeetingCreated";
    private static final String MEETING_STOP_REQUESTED = "MeetingStopRequested";

    private MeetingCommandEventFactory() {
    }

    /** 为首次会议创建构造稳定事件；legacy 与 owned 路径共享此入口。 */
    public static OutboxStore.OutboxEvent meetingCreated(
            MeetingAggregate.MeetingSnapshot meeting, Instant occurredAt) {
        return commandEvent(meeting, MEETING_CREATED, occurredAt);
    }

    /** 为首次停止意图构造稳定事件；重复 STOP 不应再次追加。 */
    public static OutboxStore.OutboxEvent meetingStopRequested(
            MeetingAggregate.MeetingSnapshot meeting, Instant occurredAt) {
        return commandEvent(meeting, MEETING_STOP_REQUESTED, occurredAt);
    }

    private static OutboxStore.OutboxEvent commandEvent(
            MeetingAggregate.MeetingSnapshot meeting,
            String eventType,
            Instant occurredAt) {
        Objects.requireNonNull(meeting, "meeting");
        Objects.requireNonNull(occurredAt, "occurredAt");
        String eventId = "meeting-" + digest(eventType, meeting.meetingId());
        String payload = "{\"meetingId\":" + quote(meeting.meetingId())
                + ",\"deploymentScope\":" + quote(meeting.deploymentScope())
                + ",\"state\":" + quote(meeting.state().name())
                + ",\"updatedAt\":" + quote(meeting.updatedAt().toString()) + "}";
        return OutboxStore.OutboxEvent.pending(
                eventId, "Meeting", meeting.meetingId(), eventType, payload,
                occurredAt, occurredAt);
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (current < 0x20) {
                        result.append(String.format("\\u%04x", (int) current));
                    } else {
                        result.append(current);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private static String digest(String... values) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                sha256.update(value.getBytes(StandardCharsets.UTF_8));
                sha256.update((byte) 0);
            }
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
