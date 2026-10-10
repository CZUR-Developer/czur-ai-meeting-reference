package com.czur.reference.application.finalization;

import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 最终收口执行事实中冻结的完整 {@link ProductProfile} 配置。
 *
 * <p>执行事实不能只保存 Profile 的 id/revision 后再读取“当前配置”，否则配置发布、
 * 回滚或服务重启会让同一场会议得到不同的依赖、缺失策略和执行映射。本快照因此保存
 * 构造 {@code ProductProfile} 所需的全部字段：required/optional 能力、依赖、缺失
 * 策略、fallback、受影响工作流和产物映射。快照只包含编排配置，不包含 receipt、
 * Token、模型输出正文、对象存储地址或预签名 URL。</p>
 *
 * <p>所有集合均在构造时深复制，并按 capability key 排序。列表值保留 Profile 中的
 * 声明顺序，因为 workflow/artifact 顺序可能是版本化配置的一部分；同一映射中的
 * 重复项会关闭式拒绝，避免恢复后重复执行同一工作流或生成同一类产物。构造会复用
 * 领域 {@code ProductProfile} 的完整不变量校验；任何失败都转换为固定、无正文的
 * 异常，避免 capability 标识或映射内容进入异常链。所有参与 canonical 编码的动态
 * 文本还必须能以严格 UTF-8 编码；未配对 surrogate 不允许被替换字符悄悄归一成
 * 相同摘要。</p>
 */
public final class ProductProfileSnapshot {
    private static final String INVALID_SNAPSHOT = "Product profile snapshot is invalid";

    private final String id;
    private final int revision;
    private final Set<String> requiredCapabilities;
    private final Set<String> optionalCapabilities;
    private final Map<String, Set<String>> dependencies;
    private final Map<String, ProductProfile.MissingCapabilityPolicy> missingPolicies;
    private final Map<String, String> fallbackCapabilities;
    private final Map<String, List<String>> affectedWorkflows;
    private final Map<String, List<String>> producedArtifacts;

    /**
     * 从完整的冻结值创建快照。
     *
     * <p>该入口主要供持久化协议解码使用。调用方必须提供全部九项配置；空映射应显式
     * 传入空映射，不能用 {@code null} 表示。构造成功后，调用方继续修改原集合不会
     * 改变快照。</p>
     *
     * @throws IllegalArgumentException 任一配置不满足 {@link ProductProfile} 不变量
     */
    public ProductProfileSnapshot(
            String id,
            int revision,
            Set<String> requiredCapabilities,
            Set<String> optionalCapabilities,
            Map<String, Set<String>> dependencies,
            Map<String, ProductProfile.MissingCapabilityPolicy> missingPolicies,
            Map<String, String> fallbackCapabilities,
            Map<String, List<String>> affectedWorkflows,
            Map<String, List<String>> producedArtifacts) {
        try {
            ProductProfile validated = new ProductProfile(
                    id,
                    revision,
                    requiredCapabilities,
                    optionalCapabilities,
                    dependencies,
                    missingPolicies,
                    fallbackCapabilities,
                    affectedWorkflows,
                    producedArtifacts);
            requireStrictUtf8(validated.id());
            requireStrictUtf8(validated.requiredCapabilities());
            requireStrictUtf8(validated.optionalCapabilities());
            requireStrictUtf8SetMap(validated.dependencies());
            requireStrictUtf8Keys(validated.missingPolicies());
            requireStrictUtf8TextMap(validated.fallbackCapabilities());
            requireStrictUtf8ListMap(validated.affectedWorkflows());
            requireStrictUtf8ListMap(validated.producedArtifacts());
            this.id = validated.id();
            this.revision = validated.revision();
            this.requiredCapabilities = immutableSet(validated.requiredCapabilities());
            this.optionalCapabilities = immutableSet(validated.optionalCapabilities());
            this.dependencies = immutableSetMap(validated.dependencies());
            this.missingPolicies = immutableMap(validated.missingPolicies());
            this.fallbackCapabilities = immutableMap(validated.fallbackCapabilities());
            requireUniqueLists(validated.affectedWorkflows());
            requireUniqueLists(validated.producedArtifacts());
            this.affectedWorkflows = immutableListMap(validated.affectedWorkflows());
            this.producedArtifacts = immutableListMap(validated.producedArtifacts());
        } catch (RuntimeException failure) {
            // ProductProfile 的诊断可能包含 capability 标识；执行事实边界不能透传。
            throw new IllegalArgumentException(INVALID_SNAPSHOT);
        }
    }

    /**
     * 捕获一个已经通过领域校验的 Product Profile。
     *
     * @param profile 创建会议时实际使用的完整 Profile，不得为空
     * @return 与当前配置解耦、可持久化重放的不可变快照
     */
    public static ProductProfileSnapshot from(ProductProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return new ProductProfileSnapshot(
                profile.id(),
                profile.revision(),
                profile.requiredCapabilities(),
                profile.optionalCapabilities(),
                profile.dependencies(),
                profile.missingPolicies(),
                profile.fallbackCapabilities(),
                profile.affectedWorkflows(),
                profile.producedArtifacts());
    }

    /**
     * 只从本快照恢复等价领域对象，不读取配置中心、数据库、注册表或当前时钟。
     *
     * @return 包含冻结配置的全新 {@link ProductProfile}
     * @throws IllegalStateException 快照内部状态无法恢复；正常构造的快照不会发生
     */
    public ProductProfile toProductProfile() {
        try {
            return new ProductProfile(
                    id,
                    revision,
                    requiredCapabilities,
                    optionalCapabilities,
                    dependencies,
                    missingPolicies,
                    fallbackCapabilities,
                    affectedWorkflows,
                    producedArtifacts);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Product profile snapshot cannot be restored");
        }
    }

    /** 返回冻结的 Profile 标识。调用方不得把该值写入诊断日志。 */
    public String id() {
        return id;
    }

    /** 返回冻结的 Profile 版本。 */
    public int revision() {
        return revision;
    }

    /** 返回不可变、按字典序排列的 required 能力集合。 */
    public Set<String> requiredCapabilities() {
        return requiredCapabilities;
    }

    /** 返回不可变、按字典序排列的 optional 能力集合。 */
    public Set<String> optionalCapabilities() {
        return optionalCapabilities;
    }

    /** 返回不可变、按 key 排列的依赖配置。 */
    public Map<String, Set<String>> dependencies() {
        return dependencies;
    }

    /** 返回 Profile 中显式声明的缺失策略配置。 */
    public Map<String, ProductProfile.MissingCapabilityPolicy> missingPolicies() {
        return missingPolicies;
    }

    /**
     * 返回能力的有效缺失策略，包括领域 Profile 对未显式登记能力使用的默认策略。
     */
    public ProductProfile.MissingCapabilityPolicy missingPolicyOf(String capability) {
        if (requiredCapabilities.contains(capability)) {
            return missingPolicies.getOrDefault(
                    capability, ProductProfile.MissingCapabilityPolicy.FAIL_MEETING);
        }
        return missingPolicies.getOrDefault(
                capability, ProductProfile.MissingCapabilityPolicy.DISABLE);
    }

    /** 返回 Profile 中显式声明的 fallback 配置。 */
    public Map<String, String> fallbackCapabilities() {
        return fallbackCapabilities;
    }

    /** 返回不可变、按 key 排列的 workflow 映射。 */
    public Map<String, List<String>> affectedWorkflows() {
        return affectedWorkflows;
    }

    /** 返回不可变、按 key 排列的 artifact 映射。 */
    public Map<String, List<String>> producedArtifacts() {
        return producedArtifacts;
    }

    /** 返回 required 与 optional 的只读并集。 */
    public Set<String> allCapabilities() {
        TreeSet<String> all = new TreeSet<>(requiredCapabilities);
        all.addAll(optionalCapabilities);
        return Collections.unmodifiableSet(all);
    }

    /**
     * 诊断只公开版本和规模，不公开 Profile id、能力标识或任何映射正文。
     */
    @Override
    public String toString() {
        return "ProductProfileSnapshot[id=redacted"
                + ", revision=" + revision
                + ", requiredCount=" + requiredCapabilities.size()
                + ", optionalCount=" + optionalCapabilities.size()
                + ", dependencyEntryCount=" + dependencies.size()
                + ", missingPolicyEntryCount=" + missingPolicies.size()
                + ", fallbackEntryCount=" + fallbackCapabilities.size()
                + ", workflowEntryCount=" + affectedWorkflows.size()
                + ", artifactEntryCount=" + producedArtifacts.size() + ']';
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProductProfileSnapshot that)) {
            return false;
        }
        return revision == that.revision
                && id.equals(that.id)
                && requiredCapabilities.equals(that.requiredCapabilities)
                && optionalCapabilities.equals(that.optionalCapabilities)
                && dependencies.equals(that.dependencies)
                && missingPolicies.equals(that.missingPolicies)
                && fallbackCapabilities.equals(that.fallbackCapabilities)
                && affectedWorkflows.equals(that.affectedWorkflows)
                && producedArtifacts.equals(that.producedArtifacts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                id,
                revision,
                requiredCapabilities,
                optionalCapabilities,
                dependencies,
                missingPolicies,
                fallbackCapabilities,
                affectedWorkflows,
                producedArtifacts);
    }

    private static Set<String> immutableSet(Set<String> values) {
        return Collections.unmodifiableSet(new TreeSet<>(values));
    }

    private static Map<String, Set<String>> immutableSetMap(Map<String, Set<String>> values) {
        TreeMap<String, Set<String>> copy = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : values.entrySet()) {
            copy.put(entry.getKey(), immutableSet(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static <V> Map<String, V> immutableMap(Map<String, V> values) {
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }

    private static Map<String, List<String>> immutableListMap(Map<String, List<String>> values) {
        TreeMap<String, List<String>> copy = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(
                    new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static void requireUniqueLists(Map<String, List<String>> values) {
        for (List<String> items : values.values()) {
            if (new java.util.HashSet<>(items).size() != items.size()) {
                throw new IllegalArgumentException(INVALID_SNAPSHOT);
            }
        }
    }

    private static void requireStrictUtf8(String value) {
        MeetingIdPolicy.utf8Length(value);
    }

    private static void requireStrictUtf8(Iterable<String> values) {
        for (String value : values) {
            requireStrictUtf8(value);
        }
    }

    private static void requireStrictUtf8Keys(Map<String, ?> values) {
        requireStrictUtf8(values.keySet());
    }

    private static void requireStrictUtf8SetMap(Map<String, Set<String>> values) {
        requireStrictUtf8Keys(values);
        for (Set<String> items : values.values()) {
            requireStrictUtf8(items);
        }
    }

    private static void requireStrictUtf8TextMap(Map<String, String> values) {
        requireStrictUtf8Keys(values);
        requireStrictUtf8(values.values());
    }

    private static void requireStrictUtf8ListMap(Map<String, List<String>> values) {
        requireStrictUtf8Keys(values);
        for (List<String> items : values.values()) {
            requireStrictUtf8(items);
        }
    }
}
