package com.czur.reference.application.port.entitlement;

import java.time.Instant;
import java.util.Objects;

/**
 * 可持久化的用量预占不可变快照和状态机。
 *
 * <p>合法路径只有 {@code RESERVED -> COMMITTED} 或 {@code RESERVED ->
 * CANCELLED}。预占先绑定稳定的 expectedMeetingId；会议创建事务再通过
 * {@link #bind(String, Instant, long)} 原子写入 boundMeetingId。取消只允许未绑定
 * 记录，提交只允许已绑定记录，因此孤儿清理和最终收口不会互相误伤。</p>
 *
 * <p>每次状态变化递增 version。持久化适配器必须使用相同 expectedVersion
 * 做行锁或 compare-and-set，不能只在内存校验后无条件更新。</p>
 */
public record UsageReservation(
        String reservationId,
        String deploymentScope,
        String subjectScope,
        String grantId,
        String expectedMeetingId,
        String boundMeetingId,
        Instant boundAt,
        UsageEstimate reservedUsage,
        UsageActual actualUsage,
        Instant createdAt,
        Instant expiresAt,
        State state,
        long version,
        Instant committedAt,
        Instant cancelledAt,
        String cancellationReasonCode) {

    /** 预占状态固定为一个可变中间态和两个互斥终态。 */
    public enum State {
        RESERVED,
        COMMITTED,
        CANCELLED
    }

    public UsageReservation {
        ReserveUsageRequest.requireText("reservationId", reservationId);
        ReserveUsageRequest.requireText("deploymentScope", deploymentScope);
        ReserveUsageRequest.requireText("subjectScope", subjectScope);
        ReserveUsageRequest.requireText("grantId", grantId);
        ReserveUsageRequest.requireText("expectedMeetingId", expectedMeetingId);
        if ((boundMeetingId == null) != (boundAt == null)) {
            throw new IllegalArgumentException("boundMeetingId and boundAt must appear together");
        }
        if (boundMeetingId != null) {
            ReserveUsageRequest.requireText("boundMeetingId", boundMeetingId);
            if (!expectedMeetingId.equals(boundMeetingId)) {
                throw new IllegalArgumentException("boundMeetingId must match expectedMeetingId");
            }
        }
        Objects.requireNonNull(reservedUsage, "reservedUsage");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (boundAt != null && boundAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("boundAt must not be before createdAt");
        }
        if (boundAt != null && !boundAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("boundAt must be before expiresAt");
        }
        Objects.requireNonNull(state, "state");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        validateState(state, boundMeetingId, boundAt, actualUsage, reservedUsage,
                createdAt, committedAt, cancelledAt, cancellationReasonCode, version);
    }

    /** 创建版本为零、尚未绑定会议的初始预占。 */
    public static UsageReservation reserved(
            String reservationId,
            MeetingGrant grant,
            String expectedMeetingId,
            UsageEstimate estimate,
            Instant createdAt,
            Instant expiresAt) {
        Objects.requireNonNull(grant, "grant");
        Objects.requireNonNull(estimate, "estimate");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (expiresAt.isAfter(grant.expiresAt())) {
            throw new IllegalArgumentException("reservation cannot outlive grant");
        }
        if (estimate.unit() == UsageUnit.MEETING_DURATION_MS
                && estimate.amount() > grant.maxDurationMs()) {
            throw new IllegalArgumentException("estimated duration exceeds grant limit");
        }
        return new UsageReservation(reservationId, grant.deploymentScope(), grant.subjectScope(),
                grant.grantId(), expectedMeetingId, null, null, estimate, null, createdAt,
                expiresAt, State.RESERVED, 0, null, null, null);
    }

    public boolean isBound() {
        return boundMeetingId != null;
    }

    /** 截止时刻视为过期；过期只阻止新 bind，不阻止已绑定记录最终提交。 */
    public boolean isExpiredAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return !now.isBefore(expiresAt);
    }

    /**
     * 把预占绑定到预先声明的会议。
     *
     * <p>该方法只表达领域校验；数据库实现必须把 bind 与 Meeting、会议幂等和
     * 创建 Outbox 放在同一事务中提交。</p>
     */
    public UsageReservation bind(String meetingId, Instant now, long expectedVersion) {
        ReserveUsageRequest.requireText("meetingId", meetingId);
        Objects.requireNonNull(now, "now");
        requireReserved(expectedVersion);
        if (now.isBefore(createdAt)) {
            throw new IllegalArgumentException("boundAt must not be before createdAt");
        }
        if (isBound()) {
            throw transition(UsageReservationTransitionException.ReasonCode.ALREADY_BOUND);
        }
        if (!expectedMeetingId.equals(meetingId)) {
            throw transition(UsageReservationTransitionException.ReasonCode.EXPECTED_MEETING_MISMATCH);
        }
        if (isExpiredAt(now)) {
            throw transition(UsageReservationTransitionException.ReasonCode.RESERVATION_EXPIRED);
        }
        return new UsageReservation(reservationId, deploymentScope, subjectScope, grantId,
                expectedMeetingId, meetingId, now, reservedUsage, null, createdAt, expiresAt,
                State.RESERVED, version + 1, null, null, null);
    }

    /** 提交已绑定会议的最终累积用量；即使此时已过 expiresAt 仍然允许。 */
    public UsageReservation commit(UsageActual actual, Instant now, long expectedVersion) {
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(now, "now");
        requireReserved(expectedVersion);
        if (!isBound()) {
            throw transition(UsageReservationTransitionException.ReasonCode.BIND_REQUIRED);
        }
        if (actual.unit() != reservedUsage.unit()) {
            throw new IllegalArgumentException("actual usage unit must match reserved usage unit");
        }
        if (now.isBefore(boundAt)) {
            throw new IllegalArgumentException("committedAt must not be before boundAt");
        }
        return new UsageReservation(reservationId, deploymentScope, subjectScope, grantId,
                expectedMeetingId, boundMeetingId, boundAt, reservedUsage, actual, createdAt,
                expiresAt, State.COMMITTED, version + 1, now, null, null);
    }

    /** 取消尚未绑定会议的预占；已绑定记录只能由最终收口提交。 */
    public UsageReservation cancel(String reasonCode, Instant now, long expectedVersion) {
        CancelReservationRequest.requireReasonCode(reasonCode);
        Objects.requireNonNull(now, "now");
        requireReserved(expectedVersion);
        if (now.isBefore(createdAt)) {
            throw new IllegalArgumentException("cancelledAt must not be before createdAt");
        }
        if (isBound()) {
            throw transition(UsageReservationTransitionException.ReasonCode.ALREADY_BOUND);
        }
        return new UsageReservation(reservationId, deploymentScope, subjectScope, grantId,
                expectedMeetingId, null, null, reservedUsage, null, createdAt, expiresAt,
                State.CANCELLED, version + 1, null, now, reasonCode);
    }

    private void requireReserved(long expectedVersion) {
        if (version != expectedVersion) {
            throw transition(UsageReservationTransitionException.ReasonCode.VERSION_CONFLICT);
        }
        if (state != State.RESERVED) {
            throw transition(UsageReservationTransitionException.ReasonCode.TERMINAL_STATE);
        }
    }

    private static void validateState(
            State state,
            String boundMeetingId,
            Instant boundAt,
            UsageActual actualUsage,
            UsageEstimate reservedUsage,
            Instant createdAt,
            Instant committedAt,
            Instant cancelledAt,
            String cancellationReasonCode,
            long version) {
        switch (state) {
            case RESERVED -> {
                if (actualUsage != null || committedAt != null || cancelledAt != null
                        || cancellationReasonCode != null) {
                    throw new IllegalArgumentException("RESERVED cannot contain terminal facts");
                }
                if (boundMeetingId != null && version < 1) {
                    throw new IllegalArgumentException("RESERVED version does not match binding state");
                }
            }
            case COMMITTED -> {
                if (boundMeetingId == null || actualUsage == null || committedAt == null
                        || cancelledAt != null || cancellationReasonCode != null) {
                    throw new IllegalArgumentException("COMMITTED facts are incomplete or conflicting");
                }
                if (actualUsage.unit() != reservedUsage.unit()) {
                    throw new IllegalArgumentException("actual usage unit must match reservation");
                }
                if (version < 2 || committedAt.isBefore(boundAt)) {
                    throw new IllegalArgumentException("COMMITTED version or time order is invalid");
                }
            }
            case CANCELLED -> {
                if (boundMeetingId != null || actualUsage != null || committedAt != null
                        || cancelledAt == null || cancellationReasonCode == null) {
                    throw new IllegalArgumentException("CANCELLED facts are incomplete or conflicting");
                }
                CancelReservationRequest.requireReasonCode(cancellationReasonCode);
                if (version < 1 || cancelledAt.isBefore(createdAt)) {
                    throw new IllegalArgumentException("CANCELLED version or time order is invalid");
                }
            }
        }
    }

    private static UsageReservationTransitionException transition(
            UsageReservationTransitionException.ReasonCode code) {
        return new UsageReservationTransitionException(code);
    }
}
