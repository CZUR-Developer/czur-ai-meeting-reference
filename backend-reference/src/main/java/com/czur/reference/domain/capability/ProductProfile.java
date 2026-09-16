package com.czur.reference.domain.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一个产品档位对能力的承诺和缺失处理规则。
 *
 * <p>{@code requiredCapabilities} 是创建会议时必须自动纳入的能力；
 * {@code optionalCapabilities} 只能在客户端请求并且本场授权允许时纳入。
 * 依赖和回退关系也属于 Profile 的版本化配置，不能由客户端临时指定，
 * 这样同一档位的业务流程才会保持稳定。</p>
 */
public final class ProductProfile {
    /** Provider 缺失能力时的产品策略。 */
    public enum MissingCapabilityPolicy {
        /** 该能力不可用时关闭对应功能，但不影响不相关流程。 */
        DISABLE,
        /** 尝试使用 Profile 明确声明的 fallbackCapability。 */
        USE_FALLBACK,
        /** 该能力不可用时让会议创建/运行进入失败。 */
        FAIL_MEETING
    }

    private final String id;
    private final int revision;
    private final Set<String> requiredCapabilities;
    private final Set<String> optionalCapabilities;
    private final Map<String, Set<String>> dependencies;
    private final Map<String, MissingCapabilityPolicy> missingPolicies;
    private final Map<String, String> fallbackCapabilities;
    private final Map<String, List<String>> affectedWorkflows;
    private final Map<String, List<String>> producedArtifacts;

    /**
     * 完整构造函数。所有集合和映射都会被复制，调用方后续修改不会影响 Profile。
     */
    public ProductProfile(
            String id,
            int revision,
            Set<String> requiredCapabilities,
            Set<String> optionalCapabilities,
            Map<String, Set<String>> dependencies,
            Map<String, MissingCapabilityPolicy> missingPolicies,
            Map<String, String> fallbackCapabilities) {
        this(id, revision, requiredCapabilities, optionalCapabilities, dependencies,
                missingPolicies, fallbackCapabilities, Map.of(), Map.of());
    }

    /**
     * 包含工作流和产物映射的完整构造函数。
     *
     * <p>映射键必须是 Profile 已声明能力；映射值使用部署内部的稳定工作流
     * 标识和公共 Artifact 标识。Planner 只复制这些声明，不根据模型名称
     * 推断流程或产物。</p>
     */
    public ProductProfile(
            String id,
            int revision,
            Set<String> requiredCapabilities,
            Set<String> optionalCapabilities,
            Map<String, Set<String>> dependencies,
            Map<String, MissingCapabilityPolicy> missingPolicies,
            Map<String, String> fallbackCapabilities,
            Map<String, List<String>> affectedWorkflows,
            Map<String, List<String>> producedArtifacts) {
        this.id = requireText("id", id);
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        this.revision = revision;
        this.requiredCapabilities = immutableCapabilitySet(requiredCapabilities, "requiredCapabilities");
        this.optionalCapabilities = immutableCapabilitySet(optionalCapabilities, "optionalCapabilities");
        Set<String> overlap = new HashSet<>(this.requiredCapabilities);
        overlap.retainAll(this.optionalCapabilities);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("required and optional capabilities overlap: " + overlap);
        }
        Set<String> known = new HashSet<>(this.requiredCapabilities);
        known.addAll(this.optionalCapabilities);
        this.dependencies = immutableDependencies(dependencies, known);
        this.missingPolicies = immutablePolicies(missingPolicies, known);
        this.fallbackCapabilities = immutableFallbacks(fallbackCapabilities, known);
        this.affectedWorkflows = immutableStringLists(affectedWorkflows, known, "affectedWorkflows");
        this.producedArtifacts = immutableStringLists(producedArtifacts, known, "producedArtifacts");
        validateDependencyCycles(this.dependencies);
        validateFallbackCycles(this.fallbackCapabilities);
    }

    /** 使用默认版本 1 创建 Profile。 */
    public ProductProfile(
            String id,
            Set<String> requiredCapabilities,
            Set<String> optionalCapabilities,
            Map<String, Set<String>> dependencies,
            Map<String, MissingCapabilityPolicy> missingPolicies,
            Map<String, String> fallbackCapabilities) {
        this(id, 1, requiredCapabilities, optionalCapabilities, dependencies,
                missingPolicies, fallbackCapabilities);
    }

    /** 创建没有依赖、且可选能力默认缺失即关闭的最小 Profile。 */
    public ProductProfile(String id, Set<String> requiredCapabilities, Set<String> optionalCapabilities) {
        this(id, 1, requiredCapabilities, optionalCapabilities,
                Map.of(), Map.of(), Map.of());
    }

    public String id() {
        return id;
    }

    public int revision() {
        return revision;
    }

    public Set<String> requiredCapabilities() {
        return requiredCapabilities;
    }

    public Set<String> optionalCapabilities() {
        return optionalCapabilities;
    }

    public Set<String> allCapabilities() {
        TreeSet<String> all = new TreeSet<>(requiredCapabilities);
        all.addAll(optionalCapabilities);
        return Collections.unmodifiableSet(all);
    }

    public Map<String, Set<String>> dependencies() {
        return dependencies;
    }

    public Set<String> dependenciesOf(String capability) {
        return dependencies.getOrDefault(capability, Set.of());
    }

    public Map<String, MissingCapabilityPolicy> missingPolicies() {
        return missingPolicies;
    }

    public MissingCapabilityPolicy missingPolicyOf(String capability) {
        if (requiredCapabilities.contains(capability)) {
            return missingPolicies.getOrDefault(capability, MissingCapabilityPolicy.FAIL_MEETING);
        }
        return missingPolicies.getOrDefault(capability, MissingCapabilityPolicy.DISABLE);
    }

    public Map<String, String> fallbackCapabilities() {
        return fallbackCapabilities;
    }

    public String fallbackOf(String capability) {
        return fallbackCapabilities.get(capability);
    }

    public Map<String, List<String>> affectedWorkflows() {
        return affectedWorkflows;
    }

    public List<String> affectedWorkflowsOf(String capability) {
        return affectedWorkflows.getOrDefault(capability, List.of());
    }

    public Map<String, List<String>> producedArtifacts() {
        return producedArtifacts;
    }

    public List<String> producedArtifactsOf(String capability) {
        return producedArtifacts.getOrDefault(capability, List.of());
    }

    private static Set<String> immutableCapabilitySet(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            sorted.add(requireText(name + " capability", value));
        }
        return Collections.unmodifiableSet(sorted);
    }

    private static Map<String, Set<String>> immutableDependencies(
            Map<String, Set<String>> values, Set<String> known) {
        Objects.requireNonNull(values, "dependencies");
        Map<String, Set<String>> copy = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : values.entrySet()) {
            String capability = requireText("dependency capability", entry.getKey());
            requireKnown(capability, known, "dependency capability");
            Set<String> deps = immutableCapabilitySet(entry.getValue(), "dependencies of " + capability);
            for (String dependency : deps) {
                requireKnown(dependency, known, "dependency target");
                if (capability.equals(dependency)) {
                    throw new IllegalArgumentException("capability cannot depend on itself: " + capability);
                }
            }
            copy.put(capability, deps);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, MissingCapabilityPolicy> immutablePolicies(
            Map<String, MissingCapabilityPolicy> values, Set<String> known) {
        Objects.requireNonNull(values, "missingPolicies");
        Map<String, MissingCapabilityPolicy> copy = new HashMap<>();
        for (Map.Entry<String, MissingCapabilityPolicy> entry : values.entrySet()) {
            String capability = requireText("policy capability", entry.getKey());
            requireKnown(capability, known, "policy capability");
            copy.put(capability, Objects.requireNonNull(entry.getValue(), "missing policy"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, String> immutableFallbacks(Map<String, String> values, Set<String> known) {
        Objects.requireNonNull(values, "fallbackCapabilities");
        Map<String, String> copy = new HashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String capability = requireText("fallback capability", entry.getKey());
            String fallback = requireText("fallback target", entry.getValue());
            requireKnown(capability, known, "fallback capability");
            requireKnown(fallback, known, "fallback target");
            if (capability.equals(fallback)) {
                throw new IllegalArgumentException("capability cannot fallback to itself: " + capability);
            }
            copy.put(capability, fallback);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, List<String>> immutableStringLists(
            Map<String, List<String>> values, Set<String> known, String name) {
        Objects.requireNonNull(values, name);
        Map<String, List<String>> copy = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            String capability = requireText(name + " capability", entry.getKey());
            requireKnown(capability, known, name + " capability");
            Objects.requireNonNull(entry.getValue(), name + " values");
            ArrayList<String> items = new ArrayList<>();
            for (String item : entry.getValue()) {
                items.add(requireText(name + " item", item));
            }
            copy.put(capability, Collections.unmodifiableList(items));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static void validateDependencyCycles(Map<String, Set<String>> graph) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String node : graph.keySet()) {
            detectCycle(node, graph, visiting, visited);
        }
    }

    private static void detectCycle(
            String node,
            Map<String, Set<String>> graph,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(node)) {
            return;
        }
        if (!visiting.add(node)) {
            throw new CapabilityPlanningException(
                    CapabilityPlanningException.ReasonCode.CYCLIC_DEPENDENCY,
                    "cyclic capability dependency: " + node);
        }
        for (String dependency : graph.getOrDefault(node, Set.of())) {
            detectCycle(dependency, graph, visiting, visited);
        }
        visiting.remove(node);
        visited.add(node);
    }

    private static void validateFallbackCycles(Map<String, String> fallbacks) {
        for (String start : fallbacks.keySet()) {
            Set<String> seen = new HashSet<>();
            String current = start;
            while (current != null) {
                if (!seen.add(current)) {
                    throw new CapabilityPlanningException(
                            CapabilityPlanningException.ReasonCode.CYCLIC_DEPENDENCY,
                            "cyclic capability fallback: " + start);
                }
                current = fallbacks.get(current);
            }
        }
    }

    private static void requireKnown(String capability, Set<String> known, String name) {
        if (!known.contains(capability)) {
            throw new IllegalArgumentException(name + " is not declared by profile: " + capability);
        }
    }

    private static String requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
