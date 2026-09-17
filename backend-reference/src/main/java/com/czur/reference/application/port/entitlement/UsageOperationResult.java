package com.czur.reference.application.port.entitlement;

import java.time.Instant;
import java.util.Objects;

/**
 * reserve、commit 或 cancel 的可持久化稳定结果。
 *
 * <p>结果重复保存部署、主体和操作 scope，防止适配器只凭 reservationId 或
 * 幂等键跨主体重放。相同摘要重试必须原样返回首次保存的本对象；“本次是首次
 * 执行还是重放”仅属于日志/指标元数据，不能改变稳定业务结果。</p>
 *
 * <p>reserve 结果同时保存首次 Grant 的截止时间和授权 Profile 快照。两者都是
 * 崩溃恢复输入，不能用重试请求中的新 Grant、当前策略或 Reservation 截止时间
 * 替代。commit/cancel 结果必须同时不携带这两个 reserve 专属字段。</p>
 *
 * <p>早期 schemaVersion=1 的历史 reserve JSON 没有授权快照。此类结果仍可
 * 稳定重放，便于升级后识别既有幂等事实，但不能据此创建带归属的新会议；调用方
 * 必须先检查 {@link #supportsOwnedMeetingCreation()}，并通过
 * {@link #requireAuthorizedProfileSnapshot()} 获取后续创建输入。</p>
 */
public final class UsageOperationResult {
    private final String deploymentScope;
    private final String subjectScope;
    private final String operationScope;
    private final String idempotencyKey;
    private final String requestDigest;
    private final Instant grantExpiresAt;
    private final AuthorizedProfileSnapshot authorizedProfileSnapshot;
    private final UsageReservation reservation;

    private UsageOperationResult(
            String deploymentScope,
            String subjectScope,
            String operationScope,
            String idempotencyKey,
            String requestDigest,
            Instant grantExpiresAt,
            AuthorizedProfileSnapshot authorizedProfileSnapshot,
            UsageReservation reservation,
            boolean allowLegacyReserveWithoutSnapshot) {
        ReserveUsageRequest.requireText("deploymentScope", deploymentScope);
        ReserveUsageRequest.requireText("subjectScope", subjectScope);
        ReserveUsageRequest.requireText("operationScope", operationScope);
        ReserveUsageRequest.requireText("idempotencyKey", idempotencyKey);
        ReserveUsageRequest.requireText("requestDigest", requestDigest);
        Objects.requireNonNull(reservation, "reservation");

        if (UsageOperationScopes.RESERVE.equals(operationScope)) {
            if (grantExpiresAt == null) {
                throw new IllegalArgumentException("reserve result grantExpiresAt is required");
            }
            if (grantExpiresAt.isBefore(reservation.expiresAt())) {
                throw new IllegalArgumentException(
                        "grantExpiresAt must not be before reservation expiry");
            }
            if (authorizedProfileSnapshot == null && !allowLegacyReserveWithoutSnapshot) {
                throw new IllegalArgumentException(
                        "new reserve result authorizedProfileSnapshot is required");
            }
        } else if (grantExpiresAt != null || authorizedProfileSnapshot != null) {
            throw new IllegalArgumentException(
                    "commit/cancel result reserve-only fields must be null");
        }
        if (!deploymentScope.equals(reservation.deploymentScope())
                || !subjectScope.equals(reservation.subjectScope())) {
            throw new IllegalArgumentException("result scope does not match reservation scope");
        }
        validateOperationState(operationScope, reservation.state());

        this.deploymentScope = deploymentScope;
        this.subjectScope = subjectScope;
        this.operationScope = operationScope;
        this.idempotencyKey = idempotencyKey;
        this.requestDigest = requestDigest;
        this.grantExpiresAt = grantExpiresAt;
        this.authorizedProfileSnapshot = authorizedProfileSnapshot;
        this.reservation = reservation;
    }

    /** 创建能够继续执行新会议归属写入的 reserve 稳定结果。 */
    public static UsageOperationResult reserve(
            String deploymentScope,
            String subjectScope,
            String idempotencyKey,
            String requestDigest,
            Instant grantExpiresAt,
            AuthorizedProfileSnapshot authorizedProfileSnapshot,
            UsageReservation reservation) {
        return new UsageOperationResult(
                deploymentScope,
                subjectScope,
                UsageOperationScopes.RESERVE,
                idempotencyKey,
                requestDigest,
                grantExpiresAt,
                Objects.requireNonNull(
                        authorizedProfileSnapshot, "authorizedProfileSnapshot"),
                reservation,
                false);
    }

    /**
     * 只供持久化适配器恢复 schemaVersion=1 历史 reserve 结果。
     *
     * <p>显式命名用于阻止普通 Provider 把缺少授权快照的新结果误当成可创建会议
     * 的结果。返回对象的 {@link #supportsOwnedMeetingCreation()} 恒为 false。</p>
     */
    public static UsageOperationResult restoreLegacyV1Reserve(
            String deploymentScope,
            String subjectScope,
            String idempotencyKey,
            String requestDigest,
            Instant grantExpiresAt,
            UsageReservation reservation) {
        return new UsageOperationResult(
                deploymentScope,
                subjectScope,
                UsageOperationScopes.RESERVE,
                idempotencyKey,
                requestDigest,
                grantExpiresAt,
                null,
                reservation,
                true);
    }

    /** 创建 commit 或 cancel 的稳定终态结果；reserve 必须使用专用 factory。 */
    public static UsageOperationResult terminal(
            String deploymentScope,
            String subjectScope,
            String operationScope,
            String idempotencyKey,
            String requestDigest,
            UsageReservation reservation) {
        if (UsageOperationScopes.RESERVE.equals(operationScope)) {
            throw new IllegalArgumentException("reserve result must use reserve factory");
        }
        return new UsageOperationResult(
                deploymentScope,
                subjectScope,
                operationScope,
                idempotencyKey,
                requestDigest,
                null,
                null,
                reservation,
                false);
    }

    /**
     * 当前稳定结果是否包含创建新归属会议所需的全部授权事实。
     *
     * <p>只有 schemaVersion=2 及以后生成的 reserve 结果满足该条件；终态结果和
     * 从历史 v1 JSON 恢复的 reserve 都返回 false。</p>
     */
    public boolean supportsOwnedMeetingCreation() {
        return UsageOperationScopes.RESERVE.equals(operationScope)
                && authorizedProfileSnapshot != null;
    }

    /**
     * 返回首次授权快照，缺失时拒绝继续创建会议。
     *
     * @throws IllegalStateException 当前结果为历史 v1 reserve 或非 reserve 结果
     */
    public AuthorizedProfileSnapshot requireAuthorizedProfileSnapshot() {
        if (!supportsOwnedMeetingCreation()) {
            throw new IllegalStateException(
                    "usage operation result does not support owned meeting creation");
        }
        return authorizedProfileSnapshot;
    }

    public String deploymentScope() {
        return deploymentScope;
    }

    public String subjectScope() {
        return subjectScope;
    }

    public String operationScope() {
        return operationScope;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String requestDigest() {
        return requestDigest;
    }

    public Instant grantExpiresAt() {
        return grantExpiresAt;
    }

    /** 返回授权快照；仅为历史数据识别保留 nullable 语义。 */
    public AuthorizedProfileSnapshot authorizedProfileSnapshot() {
        return authorizedProfileSnapshot;
    }

    public UsageReservation reservation() {
        return reservation;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UsageOperationResult that)) {
            return false;
        }
        return deploymentScope.equals(that.deploymentScope)
                && subjectScope.equals(that.subjectScope)
                && operationScope.equals(that.operationScope)
                && idempotencyKey.equals(that.idempotencyKey)
                && requestDigest.equals(that.requestDigest)
                && Objects.equals(grantExpiresAt, that.grantExpiresAt)
                && Objects.equals(authorizedProfileSnapshot, that.authorizedProfileSnapshot)
                && reservation.equals(that.reservation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                deploymentScope,
                subjectScope,
                operationScope,
                idempotencyKey,
                requestDigest,
                grantExpiresAt,
                authorizedProfileSnapshot,
                reservation);
    }

    /** 避免调试输出意外包含主体、幂等摘要或 Reservation 正文。 */
    @Override
    public String toString() {
        return "UsageOperationResult[operationScope=" + operationScope
                + ", supportsOwnedMeetingCreation=" + supportsOwnedMeetingCreation() + ']';
    }

    private static void validateOperationState(String operationScope, UsageReservation.State state) {
        if (UsageOperationScopes.RESERVE.equals(operationScope)
                && state == UsageReservation.State.RESERVED) {
            return;
        }
        if (UsageOperationScopes.COMMIT.equals(operationScope)
                && state == UsageReservation.State.COMMITTED) {
            return;
        }
        if (UsageOperationScopes.CANCEL.equals(operationScope)
                && state == UsageReservation.State.CANCELLED) {
            return;
        }
        throw new IllegalArgumentException("operationScope does not match reservation state");
    }
}
