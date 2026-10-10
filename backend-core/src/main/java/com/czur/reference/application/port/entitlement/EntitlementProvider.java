package com.czur.reference.application.port.entitlement;

/**
 * 开会资格和标准用量结算端口。
 *
 * <p>端口不暴露价格、订单和支付规则。{@link #authorizeStart} 必须是只读判断，
 * 不登记 usage operation；其余三个方法是持久变更操作，必须按请求中显式的
 * 部署、主体、操作和幂等键隔离，并对相同摘要稳定重放、不同摘要返回冲突。</p>
 */
public interface EntitlementProvider {
    /** 读取当前策略并签发绑定主体和 Profile 的短期 Grant，不占用额度。 */
    EntitlementProviderResult<MeetingGrant> authorizeStart(AuthorizeStartRequest request);

    /** 为预期会议持久预占用量；并发相同请求只能形成一条 Reservation。 */
    EntitlementProviderResult<UsageOperationResult> reserveUsage(ReserveUsageRequest request);

    /** 使用已持久化的最终累积用量事实提交已绑定 Reservation。 */
    EntitlementProviderResult<UsageOperationResult> commitUsage(CommitUsageRequest request);

    /** 条件取消尚未绑定的 Reservation；已绑定记录必须明确拒绝。 */
    EntitlementProviderResult<UsageOperationResult> cancelReservation(
            CancelReservationRequest request);
}
