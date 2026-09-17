package com.czur.reference.domain.meeting;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final String subjectScope;
    private final String grantId;
    private final Instant grantExpiresAt;
    private final String usageReservationId;
    private final String profileId;
    private final int profileRevision;
    private final Instant createdAt;
    private final MeetingStateMachine stateMachine;
    private Instant updatedAt;

    /**
     * 旧未认证命令的源码兼容构造器；新认证路径必须使用 {@link #owned}。
     */
    @Deprecated(forRemoval = false)
    public MeetingAggregate(
            String meetingId,
            String deploymentScope,
            String clientSessionId,
            String profileId,
            int profileRevision,
            Instant createdAt) {
        this(meetingId, deploymentScope, clientSessionId,
                MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE,
                null, null, null, profileId, profileRevision, createdAt);
    }

    private MeetingAggregate(
            String meetingId,
            String deploymentScope,
            String clientSessionId,
            String subjectScope,
            String grantId,
            Instant grantExpiresAt,
            String usageReservationId,
            String profileId,
            int profileRevision,
            Instant createdAt) {
        this.meetingId = requiredId("meetingId", meetingId);
        this.deploymentScope = requiredId("deploymentScope", deploymentScope);
        this.clientSessionId = requiredId("clientSessionId", clientSessionId);
        this.subjectScope = requiredId("subjectScope", subjectScope);
        validateOwnership(subjectScope, grantId, grantExpiresAt, usageReservationId);
        this.grantId = grantId;
        this.grantExpiresAt = canonicalTimestamp(grantExpiresAt);
        this.usageReservationId = usageReservationId;
        this.profileId = requiredId("profileId", profileId);
        if (profileRevision <= 0) {
            throw new IllegalArgumentException("profileRevision must be positive");
        }
        this.profileRevision = profileRevision;
        this.createdAt = canonicalTimestamp(Objects.requireNonNull(createdAt, "createdAt"));
        if (this.grantExpiresAt != null && !this.grantExpiresAt.isAfter(this.createdAt)) {
            throw new IllegalArgumentException("grantExpiresAt must be after createdAt");
        }
        this.updatedAt = this.createdAt;
        this.stateMachine = new MeetingStateMachine();
    }

    /**
     * 创建具有真实主体、Grant 和主用量预占引用的新会议。
     *
     * <p>这是后续认证创建链唯一应使用的领域入口。四个归属事实必须一次性
     * 提供，避免产生“有主体但没有预占”或“有 Grant 但无法确定所有者”的半
     * 快照。该方法只创建领域对象；Reservation 的数据库锁定与原子 bind 属于
     * P2-T12 第 5B 步，不在本方法中伪装完成。</p>
     */
    public static MeetingAggregate owned(
            String meetingId,
            String deploymentScope,
            String clientSessionId,
            String subjectScope,
            String grantId,
            Instant grantExpiresAt,
            String usageReservationId,
            String profileId,
            int profileRevision,
            Instant createdAt) {
        if (MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(subjectScope)) {
            throw new IllegalArgumentException("owned meeting cannot use LEGACY_UNOWNED subject");
        }
        return new MeetingAggregate(meetingId, deploymentScope, clientSessionId, subjectScope,
                grantId, grantExpiresAt, usageReservationId, profileId, profileRevision, createdAt);
    }

    /**
     * 显式创建未认证兼容会议。
     *
     * <p>该入口仅用于现有命令链和旧固件兼容，不得用于 reference-dev 或未来
     * 生产认证装配。兼容会议永远属于
     * {@link MeetingOwnership#LEGACY_UNOWNED_SUBJECT_SCOPE}，
     * 且不携带 Grant/Reservation 引用。</p>
     */
    public static MeetingAggregate legacyUnowned(
            String meetingId,
            String deploymentScope,
            String clientSessionId,
            String profileId,
            int profileRevision,
            Instant createdAt) {
        return new MeetingAggregate(meetingId, deploymentScope, clientSessionId,
                MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE, null, null, null, profileId,
                profileRevision, createdAt);
    }

    /**
     * 兼容部署范围字段加入前的调用方；旧聚合统一属于 {@code default} 范围。
     */
    @Deprecated(forRemoval = false)
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
                snapshot.subjectScope(),
                snapshot.grantId(),
                snapshot.grantExpiresAt(),
                snapshot.usageReservationId(),
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

    /** 返回稳定会议所有者；历史兼容会议返回 {@code LEGACY_UNOWNED}。 */
    public String subjectScope() {
        return subjectScope;
    }

    /** 返回首次创建授权的稳定标识；历史兼容会议返回 {@code null}。 */
    public String grantId() {
        return grantId;
    }

    /** 返回首次授权截止时间；它不会在会议创建后撤销会议归属。 */
    public Instant grantExpiresAt() {
        return grantExpiresAt;
    }

    /** 返回会议绑定的主用量预占；历史兼容会议返回 {@code null}。 */
    public String usageReservationId() {
        return usageReservationId;
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
        Instant canonicalEventTime = canonicalTimestamp(Objects.requireNonNull(eventTime, "eventTime"));
        if (canonicalEventTime.isBefore(updatedAt)) {
            throw new IllegalArgumentException("eventTime cannot be before updatedAt");
        }
        MeetingState nextState = stateMachine.apply(event);
        updatedAt = canonicalEventTime;
        return nextState;
    }

    public MeetingSnapshot snapshot() {
        return new MeetingSnapshot(
                meetingId,
                deploymentScope,
                clientSessionId,
                subjectScope,
                grantId,
                grantExpiresAt,
                usageReservationId,
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

    private static void validateOwnership(
            String subjectScope,
            String grantId,
            Instant grantExpiresAt,
            String usageReservationId) {
        boolean legacy = MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(subjectScope);
        boolean allReferencesMissing = grantId == null
                && grantExpiresAt == null
                && usageReservationId == null;
        boolean allReferencesPresent = grantId != null
                && grantExpiresAt != null
                && usageReservationId != null;
        if (legacy && !allReferencesMissing) {
            throw new IllegalArgumentException(
                    "LEGACY_UNOWNED meeting cannot contain entitlement references");
        }
        if (!legacy && !allReferencesPresent) {
            throw new IllegalArgumentException(
                    "owned meeting requires grantId, grantExpiresAt and usageReservationId");
        }
        if (allReferencesPresent) {
            requiredId("grantId", grantId);
            requiredId("usageReservationId", usageReservationId);
        }
    }

    /**
     * PostgreSQL {@code timestamptz} 以微秒保存。领域快照在进入事实源前统一截断，
     * 避免纳秒输入经过 JDBC 往返后因精度变化破坏相等性与幂等重放。
     */
    private static Instant canonicalTimestamp(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 可持久化的不可变会议快照。
     *
     * <p>快照只允许两种完整形态：历史兼容形态固定为
     * {@code LEGACY_UNOWNED + 三个 null 权益引用}；新归属形态必须同时包含真实
     * subject、Grant、Grant 截止时间和主 Reservation。任何半快照都会在构造时
     * 被拒绝，持久化适配器不得自行补猜缺失事实。</p>
     */
    public record MeetingSnapshot(
            String meetingId,
            String deploymentScope,
            String clientSessionId,
            String subjectScope,
            String grantId,
            Instant grantExpiresAt,
            String usageReservationId,
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
            subjectScope = requiredId("subjectScope", subjectScope);
            validateOwnership(subjectScope, grantId, grantExpiresAt, usageReservationId);
            grantExpiresAt = canonicalTimestamp(grantExpiresAt);
            Objects.requireNonNull(clientSessionId, "clientSessionId");
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(state, "state");
            createdAt = canonicalTimestamp(Objects.requireNonNull(createdAt, "createdAt"));
            updatedAt = canonicalTimestamp(Objects.requireNonNull(updatedAt, "updatedAt"));
            if (profileRevision <= 0) {
                throw new IllegalArgumentException("profileRevision must be positive");
            }
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt cannot be before createdAt");
            }
            if (grantExpiresAt != null && !grantExpiresAt.isAfter(createdAt)) {
                throw new IllegalArgumentException("grantExpiresAt must be after createdAt");
            }
        }

        /**
         * 显式构造历史兼容快照。旧 JSON 和 V001/V002 数据只能通过该语义恢复，
         * 不得把当前用户或当前 Grant 填充进历史记录。
         */
        public static MeetingSnapshot legacyUnowned(
                String meetingId,
                String deploymentScope,
                String clientSessionId,
                String profileId,
                int profileRevision,
                MeetingState state,
                Instant createdAt,
                Instant updatedAt) {
            return new MeetingSnapshot(meetingId, deploymentScope, clientSessionId,
                    MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE,
                    null, null, null, profileId,
                    profileRevision, state, createdAt, updatedAt);
        }

        /** 构造字段完整的新归属快照，主要供持久化适配器恢复数据库事实。 */
        public static MeetingSnapshot owned(
                String meetingId,
                String deploymentScope,
                String clientSessionId,
                String subjectScope,
                String grantId,
                Instant grantExpiresAt,
                String usageReservationId,
                String profileId,
                int profileRevision,
                MeetingState state,
                Instant createdAt,
                Instant updatedAt) {
            if (MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(subjectScope)) {
                throw new IllegalArgumentException("owned snapshot cannot use LEGACY_UNOWNED subject");
            }
            return new MeetingSnapshot(meetingId, deploymentScope, clientSessionId, subjectScope,
                    grantId, grantExpiresAt, usageReservationId, profileId, profileRevision,
                    state, createdAt, updatedAt);
        }

        /**
         * 兼容 P2-T12 之前的源码调用；该入口始终生成不可认领的历史兼容快照。
         * 新认证代码必须调用 {@link #owned(String, String, String, String, String,
         * Instant, String, String, int, MeetingState, Instant, Instant)}。
         */
        @Deprecated(forRemoval = false)
        public MeetingSnapshot(
                String meetingId,
                String deploymentScope,
                String clientSessionId,
                String profileId,
                int profileRevision,
                MeetingState state,
                Instant createdAt,
                Instant updatedAt) {
            this(meetingId, deploymentScope, clientSessionId,
                    MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE,
                    null, null, null, profileId, profileRevision, state, createdAt, updatedAt);
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
            this(meetingId, "default", clientSessionId,
                    MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE,
                    null, null, null, profileId, profileRevision, state, createdAt, updatedAt);
        }
    }
}
