package com.czur.reference.application.port.entitlement;

import java.util.Objects;

/**
 * 携带 Reservation 清理 lease 的取消请求。
 *
 * <p>普通 {@link CancelReservationRequest} 供会议创建失败等业务路径使用，
 * 它故意不包含清理 Worker 的 lease。过期 Reservation 清理必须使用本类型，
 * 让持久化适配器能够在同一事务中校验 owner、token、版本和截止时间；否则
 * Worker A 在 lease 过期后被 Worker B 重领时，A 的迟到 cancel 可能误伤 B。</p>
 *
 * <p>{@code cancel} 中的幂等摘要只描述稳定业务事实，不应包含 lease token、
 * owner 或 lease 截止时间。lease 轮换后，相同成功取消可以安全重放；在首次
 * 成功前，当前 lease 必须仍与 {@code lease} 完整匹配。</p>
 */
public record LeaseAwareCancelReservationRequest(
        CancelReservationRequest cancel,
        ExpiredReservationStore.LeaseToken lease) {

    public LeaseAwareCancelReservationRequest {
        Objects.requireNonNull(cancel, "cancel");
        Objects.requireNonNull(lease, "lease");
        if (!cancel.deploymentScope().equals(lease.deploymentScope())
                || !cancel.subjectScope().equals(lease.subjectScope())
                || !cancel.reservationId().equals(lease.reservationId())) {
            throw new IllegalArgumentException(
                    "cancel and lease reservation scope must match");
        }
    }
}
