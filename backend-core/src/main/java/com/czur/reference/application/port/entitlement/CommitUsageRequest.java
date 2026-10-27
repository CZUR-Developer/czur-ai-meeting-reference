package com.czur.reference.application.port.entitlement;

import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.util.Objects;

/**
 * 以已持久化最终用量事实提交预占的幂等请求。
 *
 * <p>调用方必须在每次重试时复用同一 requestDigest 和实际用量。范围字段不能
 * 从全局 reservationId 推断；存储实现还必须同时校验范围和预占归属。</p>
 *
 * <p>{@code meetingId} 是可选的服务端归属证明，来自会议持久事实而不是客户端
 * 请求。提供时，实现必须锁定预占并校验 {@code expectedMeetingId} 与
 * {@code boundMeetingId} 都是该会议，并以锁定行的当前 version 提交，不能因为
 * 冻结时记录的 {@code expectedVersion} 缺失或陈旧而永久拒绝。过期只阻止新的
 * bind，不阻止已绑定预占的最终提交。旧调用方不提供会议时保留
 * {@code expectedVersion} 乐观并发语义。</p>
 */
public record CommitUsageRequest(
        String deploymentScope,
        String subjectScope,
        String operationScope,
        String idempotencyKey,
        String requestDigest,
        String reservationId,
        long expectedVersion,
        UsageActual actual,
        String meetingId) {

    /** 兼容不携带归属证明的旧调用；新收口路径应使用九参构造。 */
    public CommitUsageRequest(
            String deploymentScope,
            String subjectScope,
            String operationScope,
            String idempotencyKey,
            String requestDigest,
            String reservationId,
            long expectedVersion,
            UsageActual actual) {
        this(deploymentScope, subjectScope, operationScope, idempotencyKey, requestDigest,
                reservationId, expectedVersion, actual, null);
    }

    public CommitUsageRequest {
        ReserveUsageRequest.requireOperationIdentity(deploymentScope, subjectScope, operationScope,
                idempotencyKey, requestDigest, UsageOperationScopes.COMMIT);
        ReserveUsageRequest.requireText("reservationId", reservationId);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        Objects.requireNonNull(actual, "actual");
        if (meetingId != null) {
            MeetingIdPolicy.requireValid(meetingId);
        }
    }

    /**
     * 只公开可诊断的版本与用量数值；所有 scope、operation key、digest、
     * Reservation 与会议 identity 均不得进入日志或断言失败正文。
     */
    @Override
    public String toString() {
        return "CommitUsageRequest[deploymentScope=redacted, subjectScope=redacted"
                + ", operationScope=redacted, idempotencyKey=redacted"
                + ", requestDigestPresent=true, reservationId=redacted"
                + ", expectedVersion=" + expectedVersion
                + ", meetingIdPresent=" + (meetingId != null)
                + ", usageUnit=" + actual.unit()
                + ", usageAmount=" + actual.amount() + ']';
    }
}
