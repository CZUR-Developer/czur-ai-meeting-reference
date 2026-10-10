package com.czur.reference.application.entitlement;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@link FixedEntitlementPolicy} 的单部署、单 Profile 固定策略。
 *
 * <p>本策略不实现套餐、订单、余额或动态管理后台。该配置只冻结一个
 * 服务端部署范围、一个 Product Profile 版本、可申请能力集合和会议时长上限。
 * Grant 与 Reservation 的有效期均是上限，不代表真实计费周期。</p>
 */
public record FixedEntitlementPolicyConfig(
        String deploymentScope,
        String profileId,
        int profileRevision,
        Set<String> supportedCapabilities,
        long maxDurationMs,
        Duration grantTtl,
        Duration reservationTtl) {

    public FixedEntitlementPolicyConfig {
        requireText("deploymentScope", deploymentScope);
        requireText("profileId", profileId);
        if (profileRevision <= 0) {
            throw new IllegalArgumentException("profileRevision must be positive");
        }
        Objects.requireNonNull(supportedCapabilities, "supportedCapabilities");
        TreeSet<String> copy = new TreeSet<>();
        for (String capability : supportedCapabilities) {
            requireText("supported capability", capability);
            if (!copy.add(capability)) {
                throw new IllegalArgumentException("supportedCapabilities must not contain duplicates");
            }
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("supportedCapabilities must not be empty");
        }
        supportedCapabilities = Collections.unmodifiableSet(copy);
        if (maxDurationMs <= 0) {
            throw new IllegalArgumentException("maxDurationMs must be positive");
        }
        requirePositiveDuration("grantTtl", grantTtl);
        requirePositiveDuration("reservationTtl", reservationTtl);
        if (reservationTtl.compareTo(grantTtl) > 0) {
            throw new IllegalArgumentException("reservationTtl must not exceed grantTtl");
        }
    }

    private static void requirePositiveDuration(String name, Duration value) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
