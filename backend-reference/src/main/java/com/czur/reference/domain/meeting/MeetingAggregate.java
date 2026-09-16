package com.czur.reference.domain.meeting;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain aggregate for one meeting.
 *
 * <p>The aggregate owns identity, the accepted product profile snapshot and
 * lifecycle transitions. Persistence, authentication and provider calls stay
 * outside this class.</p>
 */
public final class MeetingAggregate {
    private final String meetingId;
    private final String deploymentScope;
    private final String clientSessionId;
    private final String profileId;
    private final int profileRevision;
    private final Instant createdAt;
    private final MeetingStateMachine stateMachine;
    private Instant updatedAt;

    public MeetingAggregate(
            String meetingId,
            String deploymentScope,
            String clientSessionId,
            String profileId,
            int profileRevision,
            Instant createdAt) {
        this.meetingId = requiredId("meetingId", meetingId);
        this.deploymentScope = requiredId("deploymentScope", deploymentScope);
        this.clientSessionId = requiredId("clientSessionId", clientSessionId);
        this.profileId = requiredId("profileId", profileId);
        if (profileRevision <= 0) {
            throw new IllegalArgumentException("profileRevision must be positive");
        }
        this.profileRevision = profileRevision;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = createdAt;
        this.stateMachine = new MeetingStateMachine();
    }

    /**
     * 兼容部署范围字段加入前的调用方；旧聚合统一属于 {@code default} 范围。
     */
    public MeetingAggregate(
            String meetingId,
            String clientSessionId,
            String profileId,
            int profileRevision,
            Instant createdAt) {
        this(meetingId, "default", clientSessionId, profileId, profileRevision, createdAt);
    }

    /**
     * 从持久化快照恢复聚合。
     *
     * <p>恢复入口只接受由本领域生成的不可变快照，应用层不得自行修改
     * 状态字段。该方法让持久化适配器保持简单，同时仍由聚合负责后续
     * 状态转换和时间不变量校验。</p>
     */
    public static MeetingAggregate fromSnapshot(MeetingSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        MeetingAggregate aggregate = new MeetingAggregate(
                snapshot.meetingId(),
                snapshot.deploymentScope(),
                snapshot.clientSessionId(),
                snapshot.profileId(),
                snapshot.profileRevision(),
                snapshot.createdAt());
        aggregate.stateMachine.restore(snapshot.state());
        aggregate.updatedAt = snapshot.updatedAt();
        return aggregate;
    }

    public String meetingId() {
        return meetingId;
    }

    public String clientSessionId() {
        return clientSessionId;
    }

    /**
     * 返回会议所属的部署范围。
     *
     * <p>该值来自创建命令的部署配置，是持久化隔离键的一部分；同一范围内
     * 的 {@code clientSessionId} 只能成功创建一场会议，不同范围可以复用同一
     * 客户端会话标识。</p>
     */
    public String deploymentScope() {
        return deploymentScope;
    }

    public String profileId() {
        return profileId;
    }

    public int profileRevision() {
        return profileRevision;
    }

    public MeetingState state() {
        return stateMachine.state();
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * Applies a lifecycle event and records the event time.
     *
     * <p>Equal timestamps are accepted for idempotent retries. Older event
     * times are rejected so the aggregate never moves its update clock
     * backwards.</p>
     */
    public MeetingState apply(MeetingEvent event, Instant eventTime) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(eventTime, "eventTime");
        if (eventTime.isBefore(updatedAt)) {
            throw new IllegalArgumentException("eventTime cannot be before updatedAt");
        }
        MeetingState nextState = stateMachine.apply(event);
        updatedAt = eventTime;
        return nextState;
    }

    public MeetingSnapshot snapshot() {
        return new MeetingSnapshot(
                meetingId,
                deploymentScope,
                clientSessionId,
                profileId,
                profileRevision,
                state(),
                createdAt,
                updatedAt);
    }

    private static String requiredId(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Immutable view suitable for persistence or transport mapping. */
    public record MeetingSnapshot(
            String meetingId,
            String deploymentScope,
            String clientSessionId,
            String profileId,
            int profileRevision,
            MeetingState state,
            Instant createdAt,
            Instant updatedAt) {
        public MeetingSnapshot {
            Objects.requireNonNull(meetingId, "meetingId");
            if (deploymentScope == null || deploymentScope.isBlank()) {
                throw new IllegalArgumentException("deploymentScope must not be blank");
            }
            Objects.requireNonNull(clientSessionId, "clientSessionId");
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (profileRevision <= 0) {
                throw new IllegalArgumentException("profileRevision must be positive");
            }
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt cannot be before createdAt");
            }
        }

        /**
         * 兼容旧持久化适配器构造快照的入口；历史数据映射到 {@code default} 范围。
         */
        public MeetingSnapshot(
                String meetingId,
                String clientSessionId,
                String profileId,
                int profileRevision,
                MeetingState state,
                Instant createdAt,
                Instant updatedAt) {
            this(meetingId, "default", clientSessionId, profileId, profileRevision, state,
                    createdAt, updatedAt);
        }
    }
}
