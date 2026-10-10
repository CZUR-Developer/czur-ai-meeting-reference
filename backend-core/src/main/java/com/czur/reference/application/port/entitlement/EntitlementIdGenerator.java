package com.czur.reference.application.port.entitlement;

/**
 * 为权益生命周期生成不透明标识的端口。
 *
 * <p>生产适配器应使用密码学安全的随机源或等价的不可预测 ID 服务。实现不得
 * 从部署、主体、幂等键、请求摘要或当前时间派生标识，也不得把这些业务字段
 * 编码到标识中。三个方法分开定义，避免调用方把 Grant、Reservation 和 lease
 * token 混用；返回值仍然只是无业务语义的不透明字符串。</p>
 *
 * <p>该端口不负责保存唯一性。持久化适配器仍必须使用数据库唯一约束处理
 * 极低概率的碰撞，并把碰撞映射为可重试的内部失败，不能覆盖已有事实。</p>
 */
public interface EntitlementIdGenerator {
    /** 为一次只读授权判断生成新的 Grant 标识。 */
    String newGrantId();

    /** 为一次首次用量预占生成新的 Reservation 标识。 */
    String newReservationId();

    /** 为一次到期 Reservation 领取生成新的 lease token。 */
    String newLeaseToken();
}
