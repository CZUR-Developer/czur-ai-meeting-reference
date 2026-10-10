package com.czur.reference.application.port.entitlement;

/**
 * 条件取消未绑定预占的幂等请求。
 *
 * <p>{@code reasonCode} 必须是稳定机器码，不能把动态异常正文写入数据库或
 * 日志。实现只能在范围、预期会议、版本匹配且尚未绑定时执行取消。</p>
 */
public record CancelReservationRequest(
        String deploymentScope,
        String subjectScope,
        String operationScope,
        String idempotencyKey,
        String requestDigest,
        String reservationId,
        String expectedMeetingId,
        long expectedVersion,
        String reasonCode) {

    public CancelReservationRequest {
        ReserveUsageRequest.requireOperationIdentity(deploymentScope, subjectScope, operationScope,
                idempotencyKey, requestDigest, UsageOperationScopes.CANCEL);
        ReserveUsageRequest.requireText("reservationId", reservationId);
        ReserveUsageRequest.requireText("expectedMeetingId", expectedMeetingId);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        requireReasonCode(reasonCode);
    }

    static void requireReasonCode(String reasonCode) {
        ReserveUsageRequest.requireText("reasonCode", reasonCode);
        for (int index = 0; index < reasonCode.length(); index++) {
            char character = reasonCode.charAt(index);
            if (!(character == '_' || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9'))) {
                throw new IllegalArgumentException("reasonCode must be an uppercase stable code");
            }
        }
    }
}
