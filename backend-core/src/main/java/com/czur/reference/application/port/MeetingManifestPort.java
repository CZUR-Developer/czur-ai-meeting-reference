package com.czur.reference.application.port;

import java.util.Objects;

/**
 * 会议 Manifest 事实的只读端口。
 *
 * <p>端口以互斥结果区分“最终产物仍在生成”和“完整 Manifest 已经形成”：
 * {@link Pending} 只携带现有会议状态投影，传输适配器应映射为 HTTP 202；
 * {@link Found} 只携带可直接按 Manifest Schema 输出的完整结果，传输适配器应
 * 映射为 HTTP 200。生成中的状态不得通过空 Manifest 或虚构的产物、语言和时间
 * 字段表达。</p>
 */
public interface MeetingManifestPort {
    /** 查询指定会议在统一 genTime 上下文中的 Manifest 状态。 */
    Result get(Request request);

    /**
     * Manifest 查询输入；genTime 作为结果 URL 的统一有效期上下文，单位为
     * 毫秒。现有结果 URL 约定 {@code -1} 表示永不过期，非负值表示生成时间；
     * 其它负值一律拒绝。
     *
     * <p>参考 Web 沿用现有结果 URL，不在此端口要求 Web 再次登录。服务端仍须
     * 校验 opaque meetingId 与 genTime 的有效期、撤销和 Manifest 访问边界；
     * 需要用户身份的管理查询和删除继续使用其它认证端口。</p>
     */
    record Request(String meetingId, long genTime) {
        public Request {
            MeetingManifest.requireMeetingId(meetingId);
            if (genTime < -1) {
                throw new IllegalArgumentException(
                        "genTime must be -1 or non-negative milliseconds");
            }
        }

        /** 查询诊断不得输出可被直接用于结果访问的会议标识。 */
        @Override
        public String toString() {
            return "MeetingManifestPort.Request[meetingId=redacted, genTime="
                    + genTime + ']';
        }
    }

    sealed interface Result permits Pending, Found, NotFound, Conflict, AccessDenied, Unavailable {
        Status status();
    }

    /**
     * 最终清单尚未形成时的权威状态投影。
     *
     * <p>该结果只接受 {@link ManifestStatus#NOT_STARTED} 或
     * {@link ManifestStatus#GENERATING}。它不会携带部分 Manifest，避免调用方把
     * 尚未冻结的 Profile、语言、时间范围或 Artifact 当成最终事实。</p>
     */
    record Pending(MeetingStatus meetingStatus) implements Result {
        public Pending {
            Objects.requireNonNull(meetingStatus, "meetingStatus");
            if (meetingStatus.manifestState() != ManifestStatus.NOT_STARTED
                    && meetingStatus.manifestState() != ManifestStatus.GENERATING) {
                throw new IllegalArgumentException(
                        "pending result requires NOT_STARTED or GENERATING manifest state");
            }
        }

        /** 委托读取会议标识；不会复制或改写状态快照。 */
        public String meetingId() {
            return meetingStatus.meetingId();
        }

        /** 委托读取仍在生成阶段的 Manifest 状态。 */
        public ManifestStatus manifestState() {
            return meetingStatus.manifestState();
        }

        @Override
        public Status status() {
            return Status.PENDING;
        }

        /** 诊断只显示公开状态，不输出会议标识或 finalization receipt。 */
        @Override
        public String toString() {
            return "MeetingManifestPort.Pending[manifestState=" + manifestState() + ']';
        }
    }

    /** 已经形成完整、可按 Manifest Schema 输出的最终清单。 */
    record Found(MeetingManifest manifest) implements Result {
        public Found {
            Objects.requireNonNull(manifest, "manifest");
        }

        /** 兼容状态查询调用方读取 Manifest 的状态投影。 */
        public ManifestStatus manifestState() {
            return manifest.status();
        }

        /** 结果 URL 对应的会议标识。 */
        public String meetingId() {
            return manifest.meetingId();
        }

        @Override
        public Status status() {
            return Status.FOUND;
        }

        @Override
        public String toString() {
            return "MeetingManifestPort.Found[manifestState=" + manifest.status() + ']';
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
        PENDING,
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

}
