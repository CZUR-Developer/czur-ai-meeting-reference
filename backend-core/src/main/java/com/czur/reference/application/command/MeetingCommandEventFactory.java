package com.czur.reference.application.command;

import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.application.port.OutboxStore;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;

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
    private static final String MEETING_DELETION_REQUESTED = "MeetingDeletionRequested";
    private static final String MEETING_DELETED = "MeetingDeleted";

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

    /**
     * 为首次删除受理构造稳定事件；事务适配器只能在状态更新成功后追加一次。
     *
     * <p>载荷只暴露下游执行清理所需的会议 ID、部署范围、删除状态和权威
     * 更新时间。主体、幂等键、请求摘要、finalization receipt、会议正文和
     * 对象存储位置都不属于该事件。发生时间与首次可用时间统一使用删除状态
     * 的 {@link MeetingStatus#updatedAt()}，因此相同删除事实可稳定重建。</p>
     *
     * @param deploymentScope 会议行中已经完成归属校验的部署范围
     * @param meeting 同一事务准备持久化的删除待处理状态
     * @return 尚未追加到 Outbox 的稳定 PENDING 事件
     */
    public static OutboxStore.OutboxEvent meetingDeletionRequested(
            String deploymentScope,
            MeetingStatus meeting) {
        requireText("deploymentScope", deploymentScope);
        Objects.requireNonNull(meeting, "meeting");
        if (meeting.state() != MeetingState.DELETION_PENDING
                || meeting.manifestState() != ManifestStatus.DELETION_PENDING) {
            throw new IllegalArgumentException(
                    "deletion event requires matching DELETION_PENDING states");
        }
        String eventId = "meeting-" + digest(MEETING_DELETION_REQUESTED, meeting.meetingId());
        String payload = "{\"meetingId\":" + quote(meeting.meetingId())
                + ",\"deploymentScope\":" + quote(deploymentScope)
                + ",\"state\":" + quote(meeting.state().name())
                + ",\"updatedAt\":" + quote(meeting.updatedAt().toString()) + "}";
        return OutboxStore.OutboxEvent.pending(
                eventId,
                "Meeting",
                meeting.meetingId(),
                MEETING_DELETION_REQUESTED,
                payload,
                meeting.updatedAt(),
                meeting.updatedAt());
    }

    /**
     * 为删除终态构造稳定的唯一生命周期事件。事件正文只携带公开终态和时间，
     * 不携带产物、Manifest 正文、lease 或存储绑定；同一会议重放始终命中同一
     * eventId。
     */
    public static OutboxStore.OutboxEvent meetingDeleted(
            String deploymentScope,
            MeetingStatus meeting) {
        requireText("deploymentScope", deploymentScope);
        Objects.requireNonNull(meeting, "meeting");
        if (meeting.state() != MeetingState.DELETED
                || meeting.manifestState() != ManifestStatus.DELETED) {
            throw new IllegalArgumentException(
                    "deleted event requires matching DELETED states");
        }
        String eventId = "meeting-" + digest(MEETING_DELETED, meeting.meetingId());
        String payload = "{\"meetingId\":" + quote(meeting.meetingId())
                + ",\"deploymentScope\":" + quote(deploymentScope)
                + ",\"state\":\"DELETED\",\"updatedAt\":"
                + quote(meeting.updatedAt().toString()) + "}";
        return OutboxStore.OutboxEvent.pending(
                eventId,
                "Meeting",
                meeting.meetingId(),
                MEETING_DELETED,
                payload,
                meeting.updatedAt(),
                meeting.updatedAt());
    }

    /** 终态 JDBC 工作单元使用的轻量构造入口，避免凭空重建 receipt。 */
    public static OutboxStore.OutboxEvent meetingDeleted(
            String deploymentScope, String meetingId, Instant deletedAt) {
        requireText("deploymentScope", deploymentScope);
        requireText("meetingId", meetingId);
        Objects.requireNonNull(deletedAt, "deletedAt");
        String eventId = "meeting-" + digest(MEETING_DELETED, meetingId);
        String payload = "{\"meetingId\":" + quote(meetingId)
                + ",\"deploymentScope\":" + quote(deploymentScope)
                + ",\"state\":\"DELETED\",\"updatedAt\":"
                + quote(deletedAt.toString()) + "}";
        return OutboxStore.OutboxEvent.pending(
                eventId, "Meeting", meetingId, MEETING_DELETED, payload,
                deletedAt, deletedAt);
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
        Objects.requireNonNull(value, "value");
        requireValidUnicode(value);
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

    /**
     * Java 字符串允许包含未配对 surrogate，而 UTF-8/JSON 事件协议不允许。
     * 必须在共用转义入口关闭式拒绝，不能让编码器宽松替换后造成不同会议 ID
     * 或部署范围具有相同的线格式。异常使用固定文本，不拼接原始值。
     */
    private static void requireValidUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw new IllegalArgumentException("event JSON contains invalid Unicode");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("event JSON contains invalid Unicode");
            }
        }
    }

    private static String digest(String... values) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                requireValidUnicode(value);
                sha256.update(value.getBytes(StandardCharsets.UTF_8));
                sha256.update((byte) 0);
            }
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
