package com.czur.reference.application.port;

import java.util.Objects;

/**
 * canonical 会议事件日志的持久化原语。
 *
 * <p>该端口只负责保存和读取已完成协议校验的 {@link EventLog}，不负责
 * 主体鉴权、会议访问控制、实时推送或 Outbox 发布。应用层应先通过
 * {@link MeetingEventLogPort} 完成访问判定，再调用此原语。公开适配器的
 * {@code append} 是独立短事务；只有 connection-scoped 实现嵌入已有工作单元
 * 时，才能把事件和会议快照放在同一数据库事务中，不能把独立调用误称为原子。
 * </p>
 *
 * <p>事件序号由上层 Orchestrator 分配，适配器在同一会议行锁下要求新事件
 * 恰好是当前最大序号加一。相同 {@code eventId} 且整个 envelope 完全一致时
 * 返回 {@link AppendStatus#REPLAYED}；任意字段不同都返回
 * {@link AppendStatus#CONFLICT}，原记录永远不会被覆盖。</p>
 */
public interface EventLogStore {

    /** 追加一条事件；重复请求不会覆盖已经持久化的事件。 */
    AppendResult append(EventLog event);

    /**
     * 按会议、逻辑流和事件序号读取一页事件。
     *
     * <p>结果按 {@code eventSequence} 严格递增；没有新事件时返回空页并保留
     * 调用方提供的 {@code afterSequence} 作为已知游标。</p>
     */
    MeetingEventPage readPage(String meetingId, String streamId, long afterSequence, int limit);

    /** 追加结果；CONFLICT 时 event 是数据库中保留的原记录。 */
    record AppendResult(AppendStatus status, EventLog event) {
        public AppendResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(event, "event");
        }

        @Override
        public String toString() {
            return "EventLogStore.AppendResult[status=" + status + ", event=redacted]";
        }
    }

    enum AppendStatus {
        APPENDED,
        REPLAYED,
        CONFLICT
    }

    /** 统一校验分页输入，避免内存和 JDBC 适配器产生不同边界。 */
    static void validatePage(String meetingId, String streamId, long afterSequence, int limit) {
        requireText("meetingId", meetingId);
        requireText("streamId", streamId);
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must not be negative");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
    }

    static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
