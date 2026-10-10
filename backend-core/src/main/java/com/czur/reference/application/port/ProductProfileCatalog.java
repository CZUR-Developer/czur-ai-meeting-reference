package com.czur.reference.application.port;

import com.czur.reference.domain.capability.ProductProfile;

import java.util.Optional;

/**
 * 按稳定 ID 和版本读取服务端可信 Product Profile 的端口。
 *
 * <p>首次权益预占已保存授权快照而完整 Create 尚未提交时，重试仍必须能读取
 * 对应版本的产品能力和降级规则；不能把当前最新版 Profile 冒充旧版。实现应
 * 保留仍可能用于未完成会议的版本，返回的 ProductProfile 必须精确匹配请求
 * ID/revision。缺版时应用层关闭式拒绝，不伪造赢家不一致；已提交的 V013
 * 响应仍在本端口之前直接重放。</p>
 */
@FunctionalInterface
public interface ProductProfileCatalog {
    /**
     * 查找指定不可变版本；不能按 ID 隐式升级到最新版。
     *
     * @return 匹配版本的可信 Profile，缺失时为空；不得返回客户端提交的定义
     * @throws RuntimeException 实现无法可靠读取时可以抛出；应用层须单点映射为
     *                          Profile 不可用，不能当作允许创建
     */
    Optional<ProductProfile> find(String profileId, int revision);
}
