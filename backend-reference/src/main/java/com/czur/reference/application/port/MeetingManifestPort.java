package com.czur.reference.application.port;

import java.time.Instant;
import java.util.Objects;

/**
 * 会议 Manifest 状态事实的只读端口。
 *
 * <p>P2-T13 只冻结状态和最终收口事实，不在此端口携带 Artifact 路径、永久
 * URL、对象存储 Key 或会议正文。完整 Manifest 与 Artifact 内容由后续契约适配器
 * 提供；本端口先保证 App/Web 能安全判断是否可以继续查询或等待。</p>
 */
public interface MeetingManifestPort {
    /** 查询指定会议在统一 genTime 上下文中的 Manifest 状态。 */
    Result get(Request request);

    /**
     * Manifest 查询输入；genTime 作为结果 URL 的统一有效期上下文。
     *
     * <p>参考 Web 沿用现有结果 URL，不在此端口要求 Web 再次登录。服务端仍须
     * 校验 opaque meetingId 与 genTime 的有效期、撤销和 Manifest 访问边界；
     * 需要用户身份的管理查询和删除继续使用其它认证端口。</p>
     */
    record Request(String meetingId, Instant genTime) {
        public Request {
            requireText("meetingId", meetingId);
            Objects.requireNonNull(genTime, "genTime");
        }

        @Override
        public String toString() {
            return "MeetingManifestPort.Request[meetingId=" + meetingId
                    + ", genTimePresent=true]";
        }
    }

    sealed interface Result permits Found, NotFound, Conflict, AccessDenied, Unavailable {
        Status status();
    }

    record Found(ManifestStatus manifestState, FinalizationFact finalization) implements Result {
        public Found {
            Objects.requireNonNull(manifestState, "manifestState");
            Objects.requireNonNull(finalization, "finalization");
        }

        @Override
        public Status status() {
            return Status.FOUND;
        }

        @Override
        public String toString() {
            return "MeetingManifestPort.Found[manifestState=" + manifestState
                    + ", finalization=redacted]";
        }
    }

    record NotFound() implements Result {
        @Override
        public Status status() {
            return Status.NOT_FOUND;
        }

        @Override
        public String toString() {
            return "MeetingManifestPort.NotFound";
        }
    }

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
            return "MeetingManifestPort.Conflict[reason=" + reason + ']';
        }
    }

    record AccessDenied() implements Result {
        @Override
        public Status status() {
            return Status.ACCESS_DENIED;
        }

        @Override
        public String toString() {
            return "MeetingManifestPort.AccessDenied";
        }
    }

    record Unavailable(UnavailableReason reason, boolean retryable) implements Result {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            if ((reason == UnavailableReason.TRANSIENT_STORAGE_FAILURE) != retryable) {
                throw new IllegalArgumentException(
                        "retryable must match manifest unavailable reason");
            }
        }

        @Override
        public Status status() {
            return Status.UNAVAILABLE;
        }

        @Override
        public String toString() {
            return "MeetingManifestPort.Unavailable[reason=" + reason
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
        GENERATION_CONTEXT_MISMATCH,
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
