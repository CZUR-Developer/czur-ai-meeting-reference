package com.czur.reference.application.finalization;

import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.UsageActual;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.time.Instant;
import java.util.Objects;

/**
 * Admission 阶段冻结、供最终收口 Worker 独立恢复的 Entitlement commit 计划。
 *
 * <p>首次构造必须接收 Admission 在数据库行锁下观察到的、已经绑定本会议且仍为
 * {@link UsageReservation.State#RESERVED} 的 Reservation。其 {@code version} 被
 * 逐值保存为 {@code expectedVersion}；Worker 重试只从本计划恢复请求，不得再读取
 * Reservation 的“当前版本”。</p>
 *
 * <p>计划同时冻结最终用量的 input provenance，因此它既能恢复
 * {@link CommitUsageRequest}，也能恢复与 V009 相同的 {@link FinalUsageFact}。
 * 不保存 receipt、lease token、凭据、正文、URL 或本地路径。</p>
 */
public final class FinalizationCommitPlan {
    private static final String INVALID_PLAN = "Finalization commit plan is invalid";

    private final String meetingId;
    private final String deploymentScope;
    private final String subjectScope;
    private final FinalizationOperationIdentity identity;
    private final String reservationId;
    private final long expectedVersion;
    private final UsageActual actual;
    private final String sourceInputBinding;
    private final String sourceInputDigest;
    private final Instant sourceAcceptedAt;

    private FinalizationCommitPlan(
            String meetingId,
            String deploymentScope,
            String subjectScope,
            FinalizationOperationIdentity identity,
            String reservationId,
            long expectedVersion,
            UsageActual actual,
            String sourceInputBinding,
            String sourceInputDigest,
            Instant sourceAcceptedAt) {
        this.meetingId = meetingId;
        this.deploymentScope = deploymentScope;
        this.subjectScope = subjectScope;
        this.identity = identity;
        this.reservationId = reservationId;
        this.expectedVersion = expectedVersion;
        this.actual = actual;
        this.sourceInputBinding = sourceInputBinding;
        this.sourceInputDigest = sourceInputDigest;
        this.sourceAcceptedAt = sourceAcceptedAt;
    }

    /**
     * 从 Admission 锁定事实冻结提交计划。
     *
     * @param deploymentScope Admission 已核对的 Meeting deployment scope
     * @param subjectScope Admission 已核对的 Meeting subject scope
     * @param finalUsage 已从 durable input 形成的最终用量事实
     * @param lockedReservation 同一事务行锁下读取的当前 Reservation 快照
     */
    public static FinalizationCommitPlan freeze(
            String deploymentScope,
            String subjectScope,
            FinalUsageFact finalUsage,
            UsageReservation lockedReservation) {
        try {
            Objects.requireNonNull(finalUsage, "finalUsage");
            Objects.requireNonNull(lockedReservation, "lockedReservation");
            validateLockedReservation(
                    deploymentScope, subjectScope, finalUsage, lockedReservation);
            FinalizationOperationIdentity identity = FinalizationOperationIdentity.commit(
                    deploymentScope,
                    subjectScope,
                    finalUsage.meetingId(),
                    lockedReservation.reservationId(),
                    lockedReservation.version(),
                    finalUsage.actual(),
                    finalUsage.sourceInputBinding(),
                    finalUsage.sourceInputDigest(),
                    finalUsage.sourceAcceptedAt());
            return new FinalizationCommitPlan(
                    finalUsage.meetingId(),
                    deploymentScope,
                    subjectScope,
                    identity,
                    lockedReservation.reservationId(),
                    lockedReservation.version(),
                    finalUsage.actual(),
                    finalUsage.sourceInputBinding(),
                    finalUsage.sourceInputDigest(),
                    finalUsage.sourceAcceptedAt());
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 从 checkpoint 恢复计划，并重新计算 operation identity 与 request digest。
     * JSON 中任一材料被单独修改都会被关闭式拒绝。
     */
    public static FinalizationCommitPlan restore(
            String meetingId,
            String deploymentScope,
            String subjectScope,
            String operationScope,
            String idempotencyKey,
            String requestDigest,
            String reservationId,
            long expectedVersion,
            UsageUnit usageUnit,
            long usageAmount,
            String sourceInputBinding,
            String sourceInputDigest,
            Instant sourceAcceptedAt) {
        try {
            MeetingIdPolicy.requireValid(meetingId);
            FinalizationOperationIdentity.requireText(deploymentScope);
            FinalizationOperationIdentity.requireText(subjectScope);
            FinalizationOperationIdentity.requireText(reservationId);
            FinalizationOperationIdentity.requireText(sourceInputBinding);
            FinalizationOperationIdentity.requireText(sourceInputDigest);
            if (expectedVersion <= 0 || usageUnit != UsageUnit.MEETING_DURATION_MS) {
                throw invalid();
            }
            UsageActual actual = new UsageActual(usageUnit, usageAmount);
            FinalizationOperationIdentity identity =
                    FinalizationOperationIdentity.restoreCommit(
                            deploymentScope,
                            subjectScope,
                            operationScope,
                            idempotencyKey,
                            requestDigest,
                            meetingId,
                            reservationId,
                            expectedVersion,
                            actual,
                            sourceInputBinding,
                            sourceInputDigest,
                            sourceAcceptedAt);
            // 复用 FinalUsageFact 的恢复入口，保证来源时间和首版单位约束一致。
            FinalUsageFact.restore(
                    meetingId, actual, sourceInputBinding,
                    sourceInputDigest, sourceAcceptedAt);
            return new FinalizationCommitPlan(
                    meetingId,
                    deploymentScope,
                    subjectScope,
                    identity,
                    reservationId,
                    expectedVersion,
                    actual,
                    sourceInputBinding,
                    sourceInputDigest,
                    sourceAcceptedAt);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /** 只依赖冻结字段恢复权益端口请求。 */
    public CommitUsageRequest commitUsageRequest() {
        return new CommitUsageRequest(
                deploymentScope,
                subjectScope,
                identity.operationScope(),
                identity.idempotencyKey(),
                identity.requestDigest(),
                reservationId,
                expectedVersion,
                actual);
    }

    /** 只依赖冻结 source provenance 恢复最终用量事实。 */
    public FinalUsageFact finalUsageFact() {
        return FinalUsageFact.restore(
                meetingId, actual, sourceInputBinding,
                sourceInputDigest, sourceAcceptedAt);
    }

    public String meetingId() {
        return meetingId;
    }

    public String deploymentScope() {
        return deploymentScope;
    }

    public String subjectScope() {
        return subjectScope;
    }

    public FinalizationOperationIdentity identity() {
        return identity;
    }

    public String reservationId() {
        return reservationId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public UsageActual actual() {
        return actual;
    }

    public String sourceInputBinding() {
        return sourceInputBinding;
    }

    public String sourceInputDigest() {
        return sourceInputDigest;
    }

    public Instant sourceAcceptedAt() {
        return sourceAcceptedAt;
    }

    private static void validateLockedReservation(
            String deploymentScope,
            String subjectScope,
            FinalUsageFact usage,
            UsageReservation reservation) {
        FinalizationOperationIdentity.requireText(deploymentScope);
        FinalizationOperationIdentity.requireText(subjectScope);
        if (reservation.state() != UsageReservation.State.RESERVED
                || !reservation.isBound()
                || !deploymentScope.equals(reservation.deploymentScope())
                || !subjectScope.equals(reservation.subjectScope())
                || !usage.meetingId().equals(reservation.expectedMeetingId())
                || !usage.meetingId().equals(reservation.boundMeetingId())
                || usage.actual().unit() != reservation.reservedUsage().unit()) {
            throw invalid();
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FinalizationCommitPlan that)) {
            return false;
        }
        return expectedVersion == that.expectedVersion
                && meetingId.equals(that.meetingId)
                && deploymentScope.equals(that.deploymentScope)
                && subjectScope.equals(that.subjectScope)
                && identity.equals(that.identity)
                && reservationId.equals(that.reservationId)
                && actual.equals(that.actual)
                && sourceInputBinding.equals(that.sourceInputBinding)
                && sourceInputDigest.equals(that.sourceInputDigest)
                && sourceAcceptedAt.equals(that.sourceAcceptedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                meetingId, deploymentScope, subjectScope, identity,
                reservationId, expectedVersion, actual, sourceInputBinding,
                sourceInputDigest, sourceAcceptedAt);
    }

    /** 诊断只显示版本与用量数值，不输出任何 scope、key、Reservation 或摘要。 */
    @Override
    public String toString() {
        return "FinalizationCommitPlan[meetingId=redacted, scopes=redacted"
                + ", identity=redacted, reservationId=redacted"
                + ", expectedVersion=" + expectedVersion
                + ", usageUnit=" + actual.unit()
                + ", usageAmount=" + actual.amount()
                + ", sourceProvenance=redacted]";
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID_PLAN);
    }
}
