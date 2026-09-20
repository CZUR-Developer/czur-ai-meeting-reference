package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingCommandContext;

import java.util.Objects;

/**
 * 会议规范事件日志只读端口。
 *
 * <p>端口同时服务实时断线补拉和 REST 历史读取，但不持有会议状态机，也不
 * 调用模型、存储或权益 Provider。适配器必须先使用
 * {@code MeetingAccessGuard} 校验部署和主体归属；历史
 * {@code LEGACY_UNOWNED} 会议与跨主体访问均返回同一个
 * {@link AccessDenied}，不能用事件页差异泄露会议存在性。</p>
 */
public interface MeetingEventLogPort {
    /** 按会议和序号读取一页规范事件。 */
    Result read(Request request);

    /** 事件分页输入；幂等键、摘要和正文不属于读请求。 */
    record Request(MeetingCommandContext context, String meetingId,
                   long afterSequence, int limit) {
        public Request {
            Objects.requireNonNull(context, "context");
            requireText("meetingId", meetingId);
            if (afterSequence < 0) {
                throw new IllegalArgumentException("afterSequence must not be negative");
            }
            if (limit < 1 || limit > 1000) {
                throw new IllegalArgumentException("limit must be between 1 and 1000");
            }
        }

        /** 隐藏认证上下文，避免把主体和部署范围写入诊断日志。 */
        @Override
        public String toString() {
            return "MeetingEventLogPort.Request[meetingId=" + meetingId
                    + ", afterSequence=" + afterSequence
                    + ", limit=" + limit
                    + ", authenticated=true]";
        }
    }

    /** 适配器必须显式处理的事件读取结果。 */
    sealed interface Result permits Page, NotFound, Conflict, AccessDenied, Unavailable {
        Status status();
    }

    record Page(MeetingEventPage page) implements Result {
        public Page {
            Objects.requireNonNull(page, "page");
        }

        @Override
        public Status status() {
            return Status.FOUND;
        }

        @Override
        public String toString() {
            return "MeetingEventLogPort.Page[page=redacted]";
        }
    }

    /** 会议不存在；不要把查询异常的正文带给客户端。 */
    record NotFound() implements Result {
        @Override
        public Status status() {
            return Status.NOT_FOUND;
        }

        @Override
        public String toString() {
            return "MeetingEventLogPort.NotFound";
        }
    }

    /** 事件游标不再可重放或请求与已存在事件事实冲突。 */
    record Conflict(ConflictReason reason) implements Result {
        public Conflict {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public Status status() {
            return Status.CONFLICT;
        }

        @Override
        public String toString() {
            return "MeetingEventLogPort.Conflict[reason=" + reason + ']';
        }
    }

    /** 跨部署、跨主体或历史无主会议统一拒绝，不暴露存在性细节。 */
    record AccessDenied() implements Result {
        @Override
        public Status status() {
            return Status.ACCESS_DENIED;
        }

        @Override
        public String toString() {
            return "MeetingEventLogPort.AccessDenied";
        }
    }

    /** 持久化事实源不可用；只携带稳定原因和可重试标记。 */
    record Unavailable(UnavailableReason reason, boolean retryable) implements Result {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            if ((reason == UnavailableReason.TRANSIENT_STORAGE_FAILURE) != retryable) {
                throw new IllegalArgumentException(
                        "retryable must match event log unavailable reason");
            }
        }

        @Override
        public Status status() {
            return Status.UNAVAILABLE;
        }

        @Override
        public String toString() {
            return "MeetingEventLogPort.Unavailable[reason=" + reason
                    + ", retryable=" + retryable + ']';
        }
    }

    enum Status {
        FOUND,
        NOT_FOUND,
        CONFLICT,
        ACCESS_DENIED,
        UNAVAILABLE
    }

    enum ConflictReason {
        EVENT_REPLAY_WINDOW_EXPIRED,
        INVALID_CURSOR
    }

    enum UnavailableReason {
        TRANSIENT_STORAGE_FAILURE,
        STORAGE_FAILURE
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
