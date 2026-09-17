package com.czur.reference.application.port.entitlement;

import java.util.Objects;

/**
 * reserve、commit 或 cancel 的可持久化稳定结果。
 *
 * <p>结果重复保存部署、主体和操作 scope，防止适配器只凭 reservationId 或
 * 幂等键跨主体重放。相同摘要重试必须原样返回首次保存的本对象；“本次是首次
 * 执行还是重放”仅属于日志/指标元数据，不能改变稳定业务结果。</p>
 */
public record UsageOperationResult(
        String deploymentScope,
        String subjectScope,
        String operationScope,
        String idempotencyKey,
        String requestDigest,
        UsageReservation reservation) {

    public UsageOperationResult {
        ReserveUsageRequest.requireText("deploymentScope", deploymentScope);
        ReserveUsageRequest.requireText("subjectScope", subjectScope);
        ReserveUsageRequest.requireText("operationScope", operationScope);
        ReserveUsageRequest.requireText("idempotencyKey", idempotencyKey);
        ReserveUsageRequest.requireText("requestDigest", requestDigest);
        Objects.requireNonNull(reservation, "reservation");
        if (!deploymentScope.equals(reservation.deploymentScope())
                || !subjectScope.equals(reservation.subjectScope())) {
            throw new IllegalArgumentException("result scope does not match reservation scope");
        }
        validateOperationState(operationScope, reservation.state());
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
