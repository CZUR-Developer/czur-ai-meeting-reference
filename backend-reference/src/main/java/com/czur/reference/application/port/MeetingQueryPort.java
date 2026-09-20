package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingCommandContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 会议状态和列表摘要的只读端口。
 *
 * <p>实现只能读取持久化会议事实，不得从 Worker 内存状态、模型连接或旧
 * Gateway DTO 合成结果。查询前必须复用 {@code MeetingAccessGuard}：真实 owned
 * 会议可以返回 {@link Found}，不存在的 meetingId 返回 {@link NotFound}；历史
 * {@code LEGACY_UNOWNED} 或跨主体/跨部署访问统一返回 {@link AccessDenied}，
 * 不得通过不同结果泄露外域行的归属类型或内部健康状态。</p>
 */
public interface MeetingQueryPort {
    /** 查询单场会议的权威状态。 */
    Result get(GetRequest request);

    /** 查询当前主体可访问的会议摘要列表。 */
    ListResult list(ListRequest request);

    /** 单场状态查询输入；不接受客户端提交的部署范围。 */
    record GetRequest(MeetingCommandContext context, String meetingId) {
        public GetRequest {
            Objects.requireNonNull(context, "context");
            requireText("meetingId", meetingId);
        }

        @Override
        public String toString() {
            return "MeetingQueryPort.GetRequest[meetingId=" + meetingId
                    + ", authenticated=true]";
        }
    }

    /** 列表查询使用服务端生成的 opaque page token。 */
    record ListRequest(MeetingCommandContext context, String pageToken, int pageSize) {
        public ListRequest {
            Objects.requireNonNull(context, "context");
            if (pageToken != null && pageToken.isBlank()) {
                throw new IllegalArgumentException("pageToken must not be blank");
            }
            if (pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("pageSize must be between 1 and 100");
            }
        }

        @Override
        public String toString() {
            return "MeetingQueryPort.ListRequest[pageSize=" + pageSize
                    + ", pageTokenPresent=" + (pageToken != null)
                    + ", authenticated=true]";
        }
    }

    /** 单场查询结果；调用方必须处理访问拒绝和持久化不可用。 */
    sealed interface Result permits Found, NotFound, Conflict, AccessDenied, Unavailable {
        Status status();
    }

    record Found(MeetingStatus meeting) implements Result {
        public Found {
            Objects.requireNonNull(meeting, "meeting");
        }

        @Override
        public Status status() {
            return Status.FOUND;
        }

        @Override
        public String toString() {
            return "MeetingQueryPort.Found[meeting=redacted]";
        }
    }

    record NotFound() implements Result {
        @Override
        public Status status() {
            return Status.NOT_FOUND;
        }

        @Override
        public String toString() {
            return "MeetingQueryPort.NotFound";
        }
    }

    /** 读请求无法与稳定快照建立一致解释时返回冲突。 */
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
            return "MeetingQueryPort.Conflict[reason=" + reason + ']';
        }
    }

    record AccessDenied() implements Result {
        @Override
        public Status status() {
            return Status.ACCESS_DENIED;
        }

        @Override
        public String toString() {
            return "MeetingQueryPort.AccessDenied";
        }
    }

    record Unavailable(UnavailableReason reason, boolean retryable) implements Result {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            if ((reason == UnavailableReason.TRANSIENT_STORAGE_FAILURE) != retryable) {
                throw new IllegalArgumentException(
                        "retryable must match meeting query unavailable reason");
            }
        }

        @Override
        public Status status() {
            return Status.UNAVAILABLE;
        }

        @Override
        public String toString() {
            return "MeetingQueryPort.Unavailable[reason=" + reason
                    + ", retryable=" + retryable + ']';
        }
    }

    /** 会议列表结果；列表本身不携带会议正文或归属字段。 */
    sealed interface ListResult permits ListPage, ListNotFound, ListConflict,
            ListAccessDenied, ListUnavailable {
        ListStatus status();
    }

    record ListPage(List<MeetingSummary> items, String nextPageToken) implements ListResult {
        public ListPage {
            Objects.requireNonNull(items, "items");
            List<MeetingSummary> copy = new ArrayList<>(items.size());
            for (MeetingSummary item : items) {
                copy.add(Objects.requireNonNull(item, "items item"));
            }
            items = Collections.unmodifiableList(copy);
            if (nextPageToken != null && nextPageToken.isBlank()) {
                throw new IllegalArgumentException("nextPageToken must not be blank");
            }
        }

        @Override
        public ListStatus status() {
            return ListStatus.FOUND;
        }

        @Override
        public String toString() {
            return "MeetingQueryPort.ListPage[itemCount=" + items.size()
                    + ", nextPageTokenPresent=" + (nextPageToken != null) + ']';
        }
    }

    record ListNotFound() implements ListResult {
        @Override
        public ListStatus status() {
            return ListStatus.NOT_FOUND;
        }

        @Override
        public String toString() {
            return "MeetingQueryPort.ListNotFound";
        }
    }

    record ListConflict(ListConflictReason reason) implements ListResult {
        public ListConflict {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public ListStatus status() {
            return ListStatus.CONFLICT;
        }

        @Override
        public String toString() {
            return "MeetingQueryPort.ListConflict[reason=" + reason + ']';
        }
    }

    record ListAccessDenied() implements ListResult {
        @Override
        public ListStatus status() {
            return ListStatus.ACCESS_DENIED;
        }

        @Override
        public String toString() {
            return "MeetingQueryPort.ListAccessDenied";
        }
    }

    record ListUnavailable(UnavailableReason reason, boolean retryable) implements ListResult {
        public ListUnavailable {
            Objects.requireNonNull(reason, "reason");
            if ((reason == UnavailableReason.TRANSIENT_STORAGE_FAILURE) != retryable) {
                throw new IllegalArgumentException(
                        "retryable must match meeting list unavailable reason");
            }
        }

        @Override
        public ListStatus status() {
            return ListStatus.UNAVAILABLE;
        }

        @Override
        public String toString() {
            return "MeetingQueryPort.ListUnavailable[reason=" + reason
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

    enum ListStatus {
        FOUND,
        NOT_FOUND,
        CONFLICT,
        ACCESS_DENIED,
        UNAVAILABLE
    }

    enum ConflictReason {
        SNAPSHOT_INCONSISTENT,
        INVALID_PAGE_TOKEN
    }

    enum ListConflictReason {
        INVALID_PAGE_TOKEN,
        SNAPSHOT_INCONSISTENT
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
