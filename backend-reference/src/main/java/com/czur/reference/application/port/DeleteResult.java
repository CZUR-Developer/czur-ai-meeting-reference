package com.czur.reference.application.port;

import java.util.Objects;

/**
 * 删除会议的稳定结果联合类型。
 *
 * <p>删除的首次成功结果是 {@link Accepted}，其会议和 Manifest 状态必须同时为
 * {@code DELETION_PENDING}；重复同一幂等请求返回 {@link Replayed}。端口不
 * 假定数据库、对象存储或备份已经同步物理删除，最终 {@code DELETED} 由后续
 * 清理 Worker 写入事实源并通过普通查询观察。冲突、访问拒绝和存储不可用
 * 都是显式结果，不能用“删除成功”掩盖。</p>
 */
public sealed interface DeleteResult
        permits DeleteResult.Accepted,
        DeleteResult.Replayed,
        DeleteResult.NotFound,
        DeleteResult.Conflict,
        DeleteResult.AccessDenied,
        DeleteResult.Unavailable {

    Status status();

    /** 首次受理删除，会议进入 DELETION_PENDING。 */
    record Accepted(MeetingStatus meeting) implements DeleteResult {
        public Accepted {
            Objects.requireNonNull(meeting, "meeting");
            if (!isMatchingDeletionState(meeting,
                    com.czur.reference.domain.meeting.MeetingState.DELETION_PENDING,
                    ManifestStatus.DELETION_PENDING)) {
                throw new IllegalArgumentException(
                        "accepted deletion requires matching DELETION_PENDING states");
            }
        }

        @Override
        public Status status() {
            return Status.DELETION_PENDING;
        }

        @Override
        public String toString() {
            return "DeleteResult.Accepted[meeting=redacted]";
        }
    }

    /**
     * 相同幂等键和摘要已经受理过删除，复用第一次稳定结果。
     *
     * <p>重放发生在清理 Worker 已经完成时，事实源可能已经是
     * {@code DELETED}；这仍然是同一删除请求的稳定重放，而不是一次新的受理。
     * 因此这里仅允许 Meeting/Manifest 同为 {@code DELETION_PENDING}，或同为
     * {@code DELETED}；交叉组合和其它中间状态都会关闭式拒绝。</p>
     */
    record Replayed(MeetingStatus meeting) implements DeleteResult {
        public Replayed {
            Objects.requireNonNull(meeting, "meeting");
            boolean pending = isMatchingDeletionState(meeting,
                    com.czur.reference.domain.meeting.MeetingState.DELETION_PENDING,
                    ManifestStatus.DELETION_PENDING);
            boolean deleted = isMatchingDeletionState(meeting,
                    com.czur.reference.domain.meeting.MeetingState.DELETED,
                    ManifestStatus.DELETED);
            if (!pending && !deleted) {
                throw new IllegalArgumentException(
                        "replayed deletion requires matching pending or deleted states");
            }
        }

        @Override
        public Status status() {
            return Status.REPLAYED;
        }

        @Override
        public String toString() {
            return "DeleteResult.Replayed[meeting=redacted]";
        }
    }

    record NotFound() implements DeleteResult {
        @Override
        public Status status() {
            return Status.NOT_FOUND;
        }

        @Override
        public String toString() {
            return "DeleteResult.NotFound";
        }
    }

    /** 相同删除 scope/key 已绑定不同规范请求摘要。 */
    record Conflict(ConflictReason reason) implements DeleteResult {
        public Conflict {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public Status status() {
            return Status.CONFLICT;
        }

        @Override
        public String toString() {
            return "DeleteResult.Conflict[reason=" + reason + ']';
        }
    }

    /** 部署、主体或历史无主归属不匹配；不返回具体拒绝原因。 */
    record AccessDenied() implements DeleteResult {
        @Override
        public Status status() {
            return Status.ACCESS_DENIED;
        }

        @Override
        public String toString() {
            return "DeleteResult.AccessDenied";
        }
    }

    /** 事实源不可用；reason 与 retryable 必须匹配。 */
    record Unavailable(UnavailableReason reason, boolean retryable) implements DeleteResult {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            if ((reason == UnavailableReason.TRANSIENT_STORAGE_FAILURE) != retryable) {
                throw new IllegalArgumentException(
                        "retryable must match delete unavailable reason");
            }
        }

        @Override
        public Status status() {
            return Status.UNAVAILABLE;
        }

        @Override
        public String toString() {
            return "DeleteResult.Unavailable[reason=" + reason
                    + ", retryable=" + retryable + ']';
        }
    }

    enum Status {
        DELETION_PENDING,
        REPLAYED,
        NOT_FOUND,
        CONFLICT,
        ACCESS_DENIED,
        UNAVAILABLE
    }

    enum ConflictReason {
        REQUEST_DIGEST_MISMATCH,
        SNAPSHOT_INCONSISTENT
    }

    enum UnavailableReason {
        TRANSIENT_STORAGE_FAILURE,
        STORAGE_FAILURE
    }

    private static boolean isMatchingDeletionState(
            MeetingStatus meeting,
            com.czur.reference.domain.meeting.MeetingState meetingState,
            ManifestStatus manifestState) {
        return meeting.state() == meetingState && meeting.manifestState() == manifestState;
    }
}
