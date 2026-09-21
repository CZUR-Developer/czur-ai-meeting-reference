package com.czur.reference.application.port;

import com.czur.reference.domain.meeting.MeetingState;

import java.util.Objects;

/**
 * Manifest 生成开始与最终版本发布的原子工作单元。
 *
 * <p>该端口只描述应用层需要的业务结果，不暴露 {@link ManifestFactStore}、JDBC
 * 连接或数据库表。实现必须把 meeting 行锁、Manifest revision、会议状态、
 * {@code manifestState}、聚合版本和更新时间放在同一事务中提交；任一写入失败
 * 都不能留下“已追加 revision 但会议仍在生成”或相反的半事实。</p>
 *
 * <p>首次 {@link #begin(BeginRequest)} 仅接受
 * {@code FINALIZING + INPUT_ACCEPTED + NOT_STARTED + revision=0}，并把
 * {@code manifestState} 原子推进到 {@code GENERATING}。同一个请求在已经处于
 * {@code FINALIZING + INPUT_ACCEPTED + GENERATING + revision=0} 时返回
 * {@link BeginReplayed}；其它无法证明的状态组合都返回固定冲突，不猜测 Worker
 * 是否曾经成功。</p>
 *
 * <p>{@link #publish(PublishRequest)} 的重放判定键是
 * {@code meetingId + targetRevision + canonical Manifest JSON}。相同 revision 和
 * canonical 正文已存在时返回 {@link PublishReplayed}，正文不同返回
 * {@link ConflictReason#CONTENT_CONFLICT}。首次发布必须从已 begin 的
 * {@code FINALIZING + INPUT_ACCEPTED + GENERATING + revision=0} 追加 revision 1；
 * 后续 revision 只能是 latest+1，且保持原来的 READY、READY_WITH_WARNINGS 或
 * FAILED 完成状态。receipt、identity、Profile 或输入状态无法与锁读事实一致时，
 * 返回 {@link ConflictReason#SNAPSHOT_INCONSISTENT}。</p>
 */
public interface ManifestPublicationUnitOfWork {
    /** 在同一事务中把尚未开始的 Manifest 生成推进到 GENERATING。 */
    BeginResult begin(BeginRequest request);

    /** 在同一事务中追加不可变 Manifest revision 并推进最终完成状态。 */
    PublishResult publish(PublishRequest request);

    /**
     * begin 所需的会议身份、Profile 和输入接管快照。
     *
     * <p>这些字段是 Worker 在进入数据库事务前已经持有的预期，而不是新的
     * 权威来源。JDBC 实现会在 meeting 行锁内逐字段比较；receipt 只用于比较，
     * 不会进入日志或结果的 {@code toString()}。</p>
     */
    record BeginRequest(
            String meetingId,
            ExpectedMeetingFacts expectedFacts) {
        public BeginRequest {
            requireText("meetingId", meetingId);
            Objects.requireNonNull(expectedFacts, "expectedFacts");
        }

        @Override
        public String toString() {
            return "BeginRequest[meetingId=redacted, expectedFacts=redacted]";
        }
    }

    /** publish 与 begin 共用同一份锁读前置事实。 */
    record PublishRequest(
            String meetingId,
            ExpectedMeetingFacts expectedFacts,
            MeetingManifest manifest) {
        public PublishRequest {
            requireText("meetingId", meetingId);
            Objects.requireNonNull(expectedFacts, "expectedFacts");
            Objects.requireNonNull(manifest, "manifest");
            if (!meetingId.equals(manifest.meetingId())) {
                throw new IllegalArgumentException("meetingId must match manifest.meetingId");
            }
            if (manifest.status() == ManifestStatus.DELETION_PENDING
                    || manifest.status() == ManifestStatus.DELETED) {
                throw new IllegalArgumentException(
                        "publication cannot use deletion manifest status");
            }
        }

        @Override
        public String toString() {
            return "PublishRequest[meetingId=redacted, manifestRevision="
                    + manifest.manifestRevision() + "]";
        }
    }

    /**
     * 会议行的身份/Profile/输入完整快照。其值不能从当前时钟或请求正文推断。
     */
    record ExpectedMeetingFacts(
            String deploymentScope,
            String subjectScope,
            String clientSessionId,
            String profileId,
            int profileRevision,
            FinalizationFact finalization) {
        public ExpectedMeetingFacts {
            requireText("deploymentScope", deploymentScope);
            requireText("subjectScope", subjectScope);
            requireText("clientSessionId", clientSessionId);
            requireText("profileId", profileId);
            if (profileRevision < 1) {
                throw new IllegalArgumentException("profileRevision must be positive");
            }
            Objects.requireNonNull(finalization, "finalization");
            if (!finalization.isAccepted()) {
                throw new IllegalArgumentException(
                        "Manifest publication requires INPUT_ACCEPTED facts");
            }
        }

        @Override
        public String toString() {
            return "ExpectedMeetingFacts[identity=redacted, profile=redacted, input=redacted]";
        }
    }

    /** 首次 begin 已经在本次事务中提交。 */
    record BeginApplied(int manifestRevision) implements BeginResult {
        public BeginApplied {
            if (manifestRevision != 0) {
                throw new IllegalArgumentException("begin must preserve revision zero");
            }
        }

        @Override
        public Status status() {
            return Status.APPLIED;
        }
    }

    /** begin 的相同前置事实已经由之前的事务提交。 */
    record BeginReplayed(int manifestRevision) implements BeginResult {
        public BeginReplayed {
            if (manifestRevision != 0) {
                throw new IllegalArgumentException("begin replay must preserve revision zero");
            }
        }

        @Override
        public Status status() {
            return Status.REPLAYED;
        }
    }

    /** 首次发布已提交；状态由 Manifest status 决定。 */
    record PublishApplied(
            int manifestRevision,
            MeetingState state,
            ManifestStatus manifestState) implements PublishResult {
        public PublishApplied {
            if (manifestRevision < 1) {
                throw new IllegalArgumentException("published revision must be positive");
            }
            requireCompletionState(state, manifestState);
        }

        @Override
        public Status status() {
            return Status.APPLIED;
        }
    }

    /** 相同 meeting/revision/canonical 正文已提交；不再次推进版本。 */
    record PublishReplayed(
            int manifestRevision,
            MeetingState state,
            ManifestStatus manifestState) implements PublishResult {
        public PublishReplayed {
            if (manifestRevision < 1) {
                throw new IllegalArgumentException("replayed revision must be positive");
            }
            requireCompletionState(state, manifestState);
        }

        @Override
        public Status status() {
            return Status.REPLAYED;
        }
    }

    record NotFound() implements BeginResult, PublishResult {
        @Override
        public Status status() {
            return Status.NOT_FOUND;
        }
    }

    record Conflict(ConflictReason reason) implements BeginResult, PublishResult {
        public Conflict {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public Status status() {
            return Status.CONFLICT;
        }
    }

    record Unavailable(UnavailableReason reason) implements BeginResult, PublishResult {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
        }

        public boolean retryable() {
            return reason == UnavailableReason.TRANSIENT_STORAGE_FAILURE
                    || reason == UnavailableReason.OUTCOME_UNKNOWN;
        }

        @Override
        public Status status() {
            return Status.UNAVAILABLE;
        }
    }

    sealed interface BeginResult permits BeginApplied, BeginReplayed,
            NotFound, Conflict, Unavailable {
        Status status();
    }

    sealed interface PublishResult permits PublishApplied, PublishReplayed,
            NotFound, Conflict, Unavailable {
        Status status();
    }

    enum Status {
        APPLIED,
        REPLAYED,
        NOT_FOUND,
        CONFLICT,
        UNAVAILABLE
    }

    enum ConflictReason {
        INVALID_MEETING_STATE,
        INVALID_INPUT_STATE,
        INVALID_MANIFEST_STATE,
        REVISION_CONFLICT,
        CONTENT_CONFLICT,
        IDENTITY_MISMATCH,
        PROFILE_MISMATCH,
        INPUT_MISMATCH,
        SNAPSHOT_INCONSISTENT
    }

    enum UnavailableReason {
        TRANSIENT_STORAGE_FAILURE,
        STORAGE_FAILURE,
        OUTCOME_UNKNOWN
    }

    private static void requireCompletionState(MeetingState state, ManifestStatus manifestState) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(manifestState, "manifestState");
        if (switch (manifestState) {
            case READY -> state != MeetingState.READY;
            case READY_WITH_WARNINGS -> state != MeetingState.READY_WITH_WARNINGS;
            case FAILED -> state != MeetingState.FAILED;
            default -> true;
        }) {
            throw new IllegalArgumentException("manifest completion state mismatch");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
