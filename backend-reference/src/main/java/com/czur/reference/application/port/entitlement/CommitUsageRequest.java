package com.czur.reference.application.port.entitlement;

import java.util.Objects;

/**
 * 以已持久化最终用量事实提交预占的幂等请求。
 *
 * <p>调用方必须在每次重试时复用同一 requestDigest 和实际用量。范围字段不能
 * 从全局 reservationId 推断；存储实现还必须同时校验范围和 expectedVersion。</p>
 */
public record CommitUsageRequest(
        String deploymentScope,
        String subjectScope,
        String operationScope,
        String idempotencyKey,
        String requestDigest,
        String reservationId,
        long expectedVersion,
        UsageActual actual) {

    public CommitUsageRequest {
        ReserveUsageRequest.requireOperationIdentity(deploymentScope, subjectScope, operationScope,
                idempotencyKey, requestDigest, UsageOperationScopes.COMMIT);
        ReserveUsageRequest.requireText("reservationId", reservationId);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        Objects.requireNonNull(actual, "actual");
    }
}
