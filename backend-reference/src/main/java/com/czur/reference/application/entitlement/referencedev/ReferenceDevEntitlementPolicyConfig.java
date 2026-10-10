package com.czur.reference.application.entitlement.referencedev;

import java.time.Duration;
import java.util.Set;
import com.czur.reference.application.entitlement.FixedEntitlementPolicyConfig;

/**
 * {@link ReferenceDevEntitlementPolicy} 的单部署、单 Profile 演示策略。
 *
 * <p>reference-dev 不实现套餐、订单、余额或动态管理后台。该配置只冻结一个
 * 服务端部署范围、一个 Product Profile 版本、可申请能力集合和会议时长上限。
 * Grant 与 Reservation 的有效期均是上限，不代表真实计费周期。</p>
 */
public record ReferenceDevEntitlementPolicyConfig(
        String deploymentScope,
        String profileId,
        int profileRevision,
        Set<String> supportedCapabilities,
        long maxDurationMs,
        Duration grantTtl,
        Duration reservationTtl) {

    public ReferenceDevEntitlementPolicyConfig {
        // 旧参考名称只作兼容包装，固定策略的校验和不可变集合冻结只维护一份。
        var validated = new FixedEntitlementPolicyConfig(deploymentScope, profileId,
                profileRevision, supportedCapabilities, maxDurationMs, grantTtl, reservationTtl);
        supportedCapabilities = validated.supportedCapabilities();
    }
}
