package com.czur.reference.domain.capability;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Provider 在注册阶段声明的能力事实。
 *
 * <p>Descriptor 只描述当前 Provider 已实现并通过契约测试的能力，不表示
 * 某个用户已经购买了这些能力，也不负责描述产品档位。能力由稳定的字符串
 * 标识，例如 {@code transcript.realtime}、{@code summary.structured}。
 * 该对象是不可变的，适合在插件注册表和应用层之间传递。</p>
 */
public record CapabilityDescriptor(
        String providerId,
        String providerVersion,
        int spiVersion,
        Set<String> capabilities) {

    /**
     * 使用最小注册信息创建 Descriptor；版本信息用于参考实现的本地测试。
     */
    public CapabilityDescriptor(Set<String> capabilities) {
        this("provider", "dev", 1, capabilities);
    }

    /**
     * 使用 Provider 标识和能力集合创建 Descriptor。
     */
    public CapabilityDescriptor(String providerId, Set<String> capabilities) {
        this(providerId, "dev", 1, capabilities);
    }

    public CapabilityDescriptor {
        requireText("providerId", providerId);
        requireText("providerVersion", providerVersion);
        if (spiVersion <= 0) {
            throw new IllegalArgumentException("spiVersion must be positive");
        }
        capabilities = immutableCapabilities(capabilities);
    }

    /** 返回 Provider 是否明确声明了指定能力。 */
    public boolean supports(String capability) {
        return capability != null && capabilities.contains(capability);
    }

    private static Set<String> immutableCapabilities(Set<String> values) {
        Objects.requireNonNull(values, "capabilities");
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            requireText("capability", value);
            sorted.add(value);
        }
        return Collections.unmodifiableSet(sorted);
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
