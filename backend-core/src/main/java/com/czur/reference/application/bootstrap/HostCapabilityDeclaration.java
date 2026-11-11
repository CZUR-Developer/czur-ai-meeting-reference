package com.czur.reference.application.bootstrap;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 宿主在代码里声明的四组会议能力，用来替代 {@code AI_MEETING_*_CAPABILITIES} 环境变量。
 *
 * <p>能力是“服务端代码能不能做”的声明，属于装配后的代码事实；环境变量只保留
 * 数据库、上游地址、密钥、时长／预算等运行参数。宿主传入本对象后，四个同名
 * 环境变量一律不再决定能力，只在取值不一致时输出一条告警，供部署迁移期间比对。</p>
 *
 * <p>四组集合语义与公共核心原有的四个配置键一致：{@code requiredCapabilities}
 * 是每次 Create 都自动纳入的能力；{@code optionalCapabilities} 只能被客户端
 * 请求且本场授权允许时纳入；{@code entitlementSupportedCapabilities} 是权益
 * 可授予范围；{@code modelCapabilities} 是本部署 Provider 注册的能力事实。
 * 所有集合在构造时排序并复制，调用方后续修改原集合不会影响本对象。</p>
 */
public final class HostCapabilityDeclaration {
    /** 能力标识沿用公共协议：小写字母开头，只允许小写字母、数字、下划线、点和横线。 */
    private static final Pattern CAPABILITY_NAME = Pattern.compile("[a-z][a-z0-9_.-]*");

    private final Set<String> requiredCapabilities;
    private final Set<String> optionalCapabilities;
    private final Set<String> entitlementSupportedCapabilities;
    private final Set<String> modelCapabilities;

    private HostCapabilityDeclaration(Set<String> requiredCapabilities,
            Set<String> optionalCapabilities, Set<String> entitlementSupportedCapabilities,
            Set<String> modelCapabilities) {
        this.requiredCapabilities = immutableCapabilities(requiredCapabilities,
                "requiredCapabilities");
        this.optionalCapabilities = immutableCapabilities(optionalCapabilities,
                "optionalCapabilities");
        this.entitlementSupportedCapabilities = immutableCapabilities(
                entitlementSupportedCapabilities, "entitlementSupportedCapabilities");
        this.modelCapabilities = immutableCapabilities(modelCapabilities, "modelCapabilities");
        overlap(this.requiredCapabilities, this.optionalCapabilities);
        // 权益和 Provider 都必须至少覆盖必选能力；缺一项时公共核心会在
        // 构造 Profile／Descriptor 时失败，这里提前给出更明确的装配错误。
        containsAll(this.entitlementSupportedCapabilities, this.requiredCapabilities,
                "entitlementSupportedCapabilities");
        containsAll(this.modelCapabilities, this.requiredCapabilities, "modelCapabilities");
    }

    /**
     * 用四组能力创建声明；集合元素必须已经是规范化能力标识。
     *
     * @throws IllegalArgumentException 能力标识非法，或必需／可选重叠，
     *                                  或权益／Provider 未覆盖必选能力
     */
    public static HostCapabilityDeclaration of(Set<String> requiredCapabilities,
            Set<String> optionalCapabilities, Set<String> entitlementSupportedCapabilities,
            Set<String> modelCapabilities) {
        return new HostCapabilityDeclaration(requiredCapabilities, optionalCapabilities,
                entitlementSupportedCapabilities, modelCapabilities);
    }

    public Set<String> requiredCapabilities() {
        return requiredCapabilities;
    }

    public Set<String> optionalCapabilities() {
        return optionalCapabilities;
    }

    public Set<String> entitlementSupportedCapabilities() {
        return entitlementSupportedCapabilities;
    }

    public Set<String> modelCapabilities() {
        return modelCapabilities;
    }

    /** 诊断文本只报数量，不展开能力清单，避免启动日志随部署无限增长。 */
    @Override
    public String toString() {
        return "HostCapabilityDeclaration[required=" + requiredCapabilities.size()
                + ", optional=" + optionalCapabilities.size()
                + ", entitlementSupported=" + entitlementSupportedCapabilities.size()
                + ", model=" + modelCapabilities.size() + "]";
    }

    private static Set<String> immutableCapabilities(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || !value.equals(value.trim())
                    || !CAPABILITY_NAME.matcher(value).matches()) {
                // 只报集合名，不回显动态配置内容。
                throw new IllegalArgumentException("宿主能力声明 " + name + " 非法");
            }
            sorted.add(value);
        }
        return Collections.unmodifiableSet(sorted);
    }

    private static void overlap(Set<String> required, Set<String> optional) {
        Set<String> shared = new TreeSet<>(required);
        shared.retainAll(optional);
        if (!shared.isEmpty()) {
            throw new IllegalArgumentException(
                    "宿主能力声明的必需能力与可选能力重叠，共 " + shared.size() + " 项");
        }
    }

    private static void containsAll(Set<String> container, Set<String> required, String name) {
        if (!container.containsAll(required)) {
            // 只报数量，不展开具体能力名。
            int missing = required.size() - (int) required.stream()
                    .filter(container::contains).count();
            throw new IllegalArgumentException(
                    "宿主能力声明 " + name + " 未覆盖必选能力，缺 " + missing + " 项");
        }
    }
}
