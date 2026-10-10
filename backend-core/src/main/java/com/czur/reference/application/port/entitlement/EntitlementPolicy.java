package com.czur.reference.application.port.entitlement;

/**
 * 可替换的开会资格与首次预占策略，独立于会议数据库账本。
 *
 * <p>部署方可读取自己的账号、会员和额度服务，决定允许的能力、时长与有效期。
 * 本接口只返回授权或候选预占快照，不保存会议、不扣费，也不登记用量操作。
 * {@link EntitlementProvider} 的持久化实现负责在同一会议数据库中保存预占，
 * 供建会工作单元绑定；后续提交、取消及相同请求重放沿用该持久化实现。
 * 因此替换资格判断不会把预占记录搬到与会议事务无关的外部数据库。</p>
 *
 * <p>实现由部署装配创建并共享，必须支持多个请求线程并发调用，不持有请求凭据。
 * 失败返回 {@link EntitlementProviderResult} 的稳定错误；日志不得输出主体、
 * 凭据、请求正文或完整授权对象。装配层不关闭策略，外部客户端资源由部署方管理。</p>
 */
public interface EntitlementPolicy {
    /**
     * 判断当前主体是否可以开始本场会议，并生成对应授权。
     *
     * @param request 已验证主体及本场能力、Profile、预估用量；部署范围来自服务端
     * @return 本次授权或稳定拒绝结果；本方法不预占额度、不产生持久变更
     */
    EntitlementProviderResult<MeetingGrant> authorizeStart(AuthorizeStartRequest request);

    /**
     * 为首次预占生成快照，交给调用方的会议数据库事务保存。
     *
     * <p>持久化实现先查相同操作的记录，只有首次请求才调用此方法。策略应使用
     * 服务端时钟与ID生成器，生成与请求主体、部署和预期会议一致的版本零快照。
     * 不在此处写数据库或调用扣费接口；已有赢家的重放不重新生成ID或截止时间。</p>
     *
     * @param request 本次授权及预期会议的首次预占输入
     * @return 尚未绑定的预占候选或稳定拒绝结果
     */
    EntitlementProviderResult<UsageReservation> prepareReservation(ReserveUsageRequest request);
}
