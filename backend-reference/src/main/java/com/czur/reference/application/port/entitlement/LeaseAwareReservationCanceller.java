package com.czur.reference.application.port.entitlement;

/**
 * 过期且未绑定 Reservation 的 lease-aware 清理端口。
 *
 * <p>该端口与普通 {@link EntitlementProvider#cancelReservation(CancelReservationRequest)}
 * 分开，避免调用方无意中绕过 Worker lease 栅栏。实现必须在一个持久化事务中完成：
 * 幂等结果重放或当前 lease 校验、Reservation 的 {@code CANCELLED} 更新以及
 * {@code usage_operation} 写入。实现不得先调用普通 cancel，再另起事务写操作记录。</p>
 */
public interface LeaseAwareReservationCanceller {

    /**
     * 使用当前 Worker lease 取消一个未绑定 Reservation。
     *
     * <p>首次成功前，owner、token、leaseVersion、leaseUntil、业务版本和会议号
     * 必须全部匹配；截止时刻本身不属于有效 lease。相同幂等摘要在成功后可以
     * 稳定重放，即使重放请求携带的是已经过期的旧 lease。</p>
     */
    EntitlementProviderResult<UsageOperationResult> cancelReservationWithLease(
            LeaseAwareCancelReservationRequest request);
}
