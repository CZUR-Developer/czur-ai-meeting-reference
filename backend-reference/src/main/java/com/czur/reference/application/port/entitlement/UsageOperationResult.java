package com.czur.reference.application.port.entitlement;

import java.util.Objects;
import java.time.Instant;

/**
 * reserve、commit 或 cancel 的可持久化稳定结果。
 *
 * <p>结果重复保存部署、主体和操作 scope，防止适配器只凭 reservationId 或
 * 幂等键跨主体重放。相同摘要重试必须原样返回首次保存的本对象；“本次是首次
 * 执行还是重放”仅属于日志/指标元数据，不能改变稳定业务结果。</p>
 *
 * <p>{@code grantExpiresAt} 只在 reserve 结果中保存首次授权 Grant 的截止时间。
 * 这是崩溃恢复输入，不能用当前请求的新 Grant 或 Reservation 截止时间替代；
 * commit/cancel 结果必须为 {@code null}。</p>
 */
public record UsageOperationResult(
        String deploymentScope,
        String subjectScope,
        String operationScope,
        String idempotencyKey,
        String requestDigest,
        Instant grantExpiresAt,
        UsageReservation reservation) {

    public UsageOperationResult {
        ReserveUsageRequest.requireText("deploymentScope", deploymentScope);
        ReserveUsageRequest.requireText("subjectScope", subjectScope);
        ReserveUsageRequest.requireText("operationScope", operationScope);
        ReserveUsageRequest.requireText("idempotencyKey", idempotencyKey);
        ReserveUsageRequest.requireText("requestDigest", requestDigest);
        if (UsageOperationScopes.RESERVE.equals(operationScope)) {
            Objects.requireNonNull(grantExpiresAt, "grantExpiresAt");
        } else if (grantExpiresAt != null) {
            throw new IllegalArgumentException("commit/cancel result grantExpiresAt must be null");
        }
        Objects.requireNonNull(reservation, "reservation");
        if (!deploymentScope.equals(reservation.deploymentScope())
                || !subjectScope.equals(reservation.subjectScope())) {
            throw new IllegalArgumentException("result scope does not match reservation scope");
        }
        validateOperationState(operationScope, reservation.state());
    }

    /**
     * 兼容编译期入口：只能构造 commit/cancel 结果；reserve 必须显式传入 Grant
     * 过期时间，避免调用方不知不觉丢失崩溃恢复事实。
     */
    public UsageOperationResult(
            String deploymentScope,
            String subjectScope,
            String operationScope,
            String idempotencyKey,
            String requestDigest,
            UsageReservation reservation) {
        this(deploymentScope, subjectScope, operationScope, idempotencyKey,
                requestDigest, null, reservation);
    }

    /** 创建带有首次 Grant 截止时间的 reserve 稳定结果。 */
    public static UsageOperationResult reserve(
            String deploymentScope,
            String subjectScope,
            String idempotencyKey,
            String requestDigest,
            Instant grantExpiresAt,
            UsageReservation reservation) {
        ReserveUsageRequest.requireText("operationScope", UsageOperationScopes.RESERVE);
        Objects.requireNonNull(grantExpiresAt, "grantExpiresAt");
        Objects.requireNonNull(reservation, "reservation");
        if (grantExpiresAt.isBefore(reservation.expiresAt())) {
            throw new IllegalArgumentException(
                    "grantExpiresAt must not be before reservation expiry");
        }
        return new UsageOperationResult(deploymentScope, subjectScope,
                UsageOperationScopes.RESERVE, idempotencyKey, requestDigest,
                grantExpiresAt, reservation);
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
