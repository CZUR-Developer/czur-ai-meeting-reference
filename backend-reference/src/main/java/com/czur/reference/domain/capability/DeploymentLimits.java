package com.czur.reference.domain.capability;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 部署侧对 Provider 能力的静态限制。
 *
 * <p>例如同一部署没有配置跨会议声纹服务时，可以把
 * {@code speaker.identity.cross_meeting} 放入 disabledCapabilities。该类
 * 不保存并发数、密钥等运行时配置；它只负责能力是否被部署明确禁用。</p>
 */
public record DeploymentLimits(Set<String> disabledCapabilities) {
    /** 不额外限制能力的部署配置。 */
    public static final DeploymentLimits NONE = new DeploymentLimits(Set.of());

    public DeploymentLimits {
        Objects.requireNonNull(disabledCapabilities, "disabledCapabilities");
        TreeSet<String> copy = new TreeSet<>();
        for (String capability : disabledCapabilities) {
            if (capability == null || capability.isBlank()) {
                throw new IllegalArgumentException("disabled capability must not be blank");
            }
            copy.add(capability);
        }
        disabledCapabilities = Collections.unmodifiableSet(copy);
    }

    /** 返回该能力是否未被部署配置禁用。 */
    public boolean permits(String capability) {
        return capability != null && !disabledCapabilities.contains(capability);
    }
}
