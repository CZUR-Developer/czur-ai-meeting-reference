package com.czur.reference.application.port.entitlement;

/**
 * 开会资格和标准用量结算端口。
 *
 * <p>端口不暴露价格、订单和支付规则。{@link #authorizeStart} 必须是只读判断，
 * 不登记 usage operation；其余方法是持久变更操作，必须按请求中显式的
 * 部署、主体、操作和幂等键隔离，并对相同摘要稳定重放、不同摘要返回冲突。
 * 弃场结算没有客户端命令，幂等键由会议标识确定，但仍要通过同一预占账本提交。</p>
 */
public interface EntitlementProvider {
    /** 读取当前策略并签发绑定主体和 Profile 的短期 Grant，不占用额度。 */
    EntitlementProviderResult<MeetingGrant> authorizeStart(AuthorizeStartRequest request);

    /** 为预期会议持久预占用量；并发相同请求只能形成一条 Reservation。 */
    EntitlementProviderResult<UsageOperationResult> reserveUsage(ReserveUsageRequest request);

    /**
     * 使用已持久化的最终累积用量事实提交已绑定 Reservation。
     *
     * <p>请求携带 {@code meetingId} 时，实现必须锁定预占并校验它确实绑定到该
     * 会议，再以锁定行的当前 version 提交；冻结时记录的 {@code expectedVersion}
     * 缺失或陈旧不能让会议永久卡在待收口。预占已过期不构成拒绝理由：过期只阻止
     * 新的 bind。未携带会议时按 {@code expectedVersion} 保持乐观并发语义。</p>
     */
    EntitlementProviderResult<UsageOperationResult> commitUsage(CommitUsageRequest request);

    /**
     * 以服务端推导的零或时长用量提交一次弃场会议的主预占。
     *
     * <p>与 {@link #commitUsage} 相同，实现必须锁定预占并校验其绑定会议；差异只在
     * 于幂等身份不来自客户端命令：同一场会议固定使用
     * {@code meeting.abandon.v1:<meetingId>}。相同请求重放返回首次成功结果，
     * 预占已是 {@code COMMITTED} 时也视为可重放成功，绝不重复扣减。</p>
     */
    EntitlementProviderResult<UsageOperationResult> settleAbandoned(
            SettleAbandonedRequest request);

    /** 条件取消尚未绑定的 Reservation；已绑定记录必须明确拒绝。 */
    EntitlementProviderResult<UsageOperationResult> cancelReservation(
            CancelReservationRequest request);
}
