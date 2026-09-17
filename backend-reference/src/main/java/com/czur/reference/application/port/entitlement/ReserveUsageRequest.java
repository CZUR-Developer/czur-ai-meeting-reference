package com.czur.reference.application.port.entitlement;

import java.time.Instant;
import java.util.Objects;

/**
 * 创建会议前执行持久预占的幂等请求。
 *
 * <p>部署、主体、操作、幂等键和 canonical 请求摘要全部显式存在，后续 JDBC
 * 实现必须以四元组 {@code deploymentScope + subjectScope + operationScope +
 * idempotencyKey} 唯一定位操作。{@code expectedMeetingId} 是服务端已生成的会议
 * 标识，但不得加入外部创建请求摘要。</p>
 */
public record ReserveUsageRequest(
        String deploymentScope,
        String subjectScope,
        String operationScope,
        String idempotencyKey,
        String requestDigest,
        MeetingGrant grant,
        UsageEstimate estimate,
        String expectedMeetingId,
        Instant reservationExpiresAt) {

    public ReserveUsageRequest {
        requireOperationIdentity(deploymentScope, subjectScope, operationScope,
                idempotencyKey, requestDigest, UsageOperationScopes.RESERVE);
        Objects.requireNonNull(grant, "grant");
        Objects.requireNonNull(estimate, "estimate");
        requireText("expectedMeetingId", expectedMeetingId);
        Objects.requireNonNull(reservationExpiresAt, "reservationExpiresAt");
        if (!deploymentScope.equals(grant.deploymentScope())
                || !subjectScope.equals(grant.subjectScope())) {
            throw new IllegalArgumentException("grant scope does not match reserve scope");
        }
        if (reservationExpiresAt.isAfter(grant.expiresAt())) {
            throw new IllegalArgumentException("reservation cannot outlive grant");
        }
    }

    static void requireOperationIdentity(
            String deploymentScope,
            String subjectScope,
            String operationScope,
            String idempotencyKey,
            String requestDigest,
            String expectedOperationScope) {
        requireText("deploymentScope", deploymentScope);
        requireText("subjectScope", subjectScope);
        requireText("operationScope", operationScope);
        UsageOperationScopes.require(expectedOperationScope, operationScope);
        requireText("idempotencyKey", idempotencyKey);
        requireText("requestDigest", requestDigest);
    }

    static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
