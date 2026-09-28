package com.czur.reference.domain.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 根据 Provider、产品档位和本场会议授权计算能力计划。
 *
 * <p>编排器只处理稳定的能力标识，不识别模型厂商，也不调用模型、存储或
 * 鉴权服务。Provider 的能力是“事实”，ProductProfile 的 required/optional
 * 是“产品承诺”，授权集合是“本场可用范围”；三者的交集再受部署限制约束。
 * 计算结果会固定到会议，调用方不应在各个业务步骤中重新判断 Provider 名称。
 * 该类不保存进程内版本状态；初始版本或由已持久化计划递增后的版本由应用层
 * 显式传入，因此幂等重放和服务重启不会改变结果。</p>
 */
public final class CapabilityPlanner {
    private static final Logger LOGGER = Logger.getLogger(CapabilityPlanner.class.getName());

    /**
     * 计算本场能力计划。
     *
     * <p>required 能力不依赖客户端请求；optional 能力必须同时被请求且
     * 获得本场授权。为便于 App 展示能力矩阵，结果还会为未请求的 optional
     * 能力生成一条 {@code DISABLE} 记录；availability 仍按 Provider、授权和
     * 部署限制如实计算。被启用能力的依赖会自动纳入计划。</p>
     *
     * @throws CapabilityPlanningException 当 requestedCapabilities 超出 Profile
     *                                     定义范围时抛出 CAPABILITY_NOT_ALLOWED
     */
    public CapabilityPlan plan(
            CapabilityDescriptor provider,
            ProductProfile profile,
            Set<String> requestedCapabilities,
            Set<String> grantedCapabilities,
            DeploymentLimits deploymentLimits) {
        return plan(provider, profile, requestedCapabilities, grantedCapabilities, deploymentLimits, 1L);
    }

    /**
     * 使用调用方提供的稳定版本计算能力计划。
     *
     * <p>Planner 是纯函数，不使用进程内计数器。首次创建会议传入 1；运行中
     * 重新计算时必须读取会议中已持久化的版本并加一后传入，避免服务重启后
     * planRevision 回退或同一次幂等重放得到不同版本。</p>
     */
    public CapabilityPlan plan(
            CapabilityDescriptor provider,
            ProductProfile profile,
            Set<String> requestedCapabilities,
            Set<String> grantedCapabilities,
            DeploymentLimits deploymentLimits,
            long planRevision) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(profile, "profile");
        if (planRevision <= 0) {
            throw new IllegalArgumentException("planRevision must be positive");
        }
        Set<String> requested = immutableInput(requestedCapabilities, "requestedCapabilities");
        Set<String> granted = immutableInput(grantedCapabilities, "grantedCapabilities");
        DeploymentLimits limits = deploymentLimits == null ? DeploymentLimits.NONE : deploymentLimits;
        validateRequested(profile, requested);
        LOGGER.log(Level.FINE,
                "开始计算能力计划 profileRevision={0} planRevision={1} requestedCount={2}",
                new Object[]{profile.revision(), planRevision, requested.size()});

        PlanningContext context = new PlanningContext(provider, profile, requested, granted, limits, planRevision);
        for (String capability : profile.requiredCapabilities()) {
            context.evaluate(capability, true);
        }
        for (String capability : profile.optionalCapabilities()) {
            if (requested.contains(capability)) {
                context.evaluate(capability, true);
            }
        }

        List<CapabilityDecision> decisions = new ArrayList<>();
        for (String capability : context.selectedCapabilities()) {
            decisions.add(context.decision(capability));
        }
        // 记录未被选择的 optional 能力，保证每个 Profile 能力在本场有固定状态。
        for (String capability : profile.optionalCapabilities()) {
            if (!context.selectedCapabilities().contains(capability)) {
                decisions.add(context.notRequestedDecision(capability));
            }
        }
        decisions.sort((left, right) -> left.capability().compareTo(right.capability()));
        for (CapabilityDecision decision : decisions) {
            LOGGER.log(Level.FINER,
                    "能力决策 availability={0} action={1} planRevision={2}",
                    new Object[]{decision.availability(), decision.action(),
                            decision.planRevision()});
        }
        CapabilityPlan plan = new CapabilityPlan(planRevision, decisions);
        if (plan.failed()) {
            LOGGER.log(Level.WARNING,
                    "能力计划包含必选能力失败 profileRevision={0} planRevision={1}",
                    new Object[]{profile.revision(), planRevision});
        } else {
            LOGGER.log(Level.INFO,
                    "能力计划已生成 profileRevision={0} planRevision={1} decisions={2}",
                    new Object[]{profile.revision(), planRevision, decisions.size()});
        }
        return plan;
    }

    /**
     * 基于已经持久化的旧计划计算下一版本。
     *
     * <p>该便捷入口保证版本严格加一；生产应用层仍须把旧计划和新计划与
     * capability_changed 事件放在同一一致性边界中持久化。</p>
     */
    public CapabilityPlan replan(
            CapabilityPlan previousPlan,
            CapabilityDescriptor provider,
            ProductProfile profile,
            Set<String> requestedCapabilities,
            Set<String> grantedCapabilities,
            DeploymentLimits deploymentLimits) {
        Objects.requireNonNull(previousPlan, "previousPlan");
        long revision;
        try {
            revision = Math.addExact(previousPlan.planRevision(), 1L);
        } catch (ArithmeticException exhausted) {
            throw new IllegalStateException("capability plan revision exhausted", exhausted);
        }
        return plan(provider, profile, requestedCapabilities, grantedCapabilities, deploymentLimits, revision);
    }

    private static void validateRequested(ProductProfile profile, Set<String> requested) {
        Set<String> allowed = new HashSet<>(profile.requiredCapabilities());
        allowed.addAll(profile.optionalCapabilities());
        for (String capability : requested) {
            if (!allowed.contains(capability)) {
                LOGGER.log(Level.WARNING, "会议请求了 Product Profile 未声明的能力");
                throw new CapabilityPlanningException(
                        CapabilityPlanningException.ReasonCode.CAPABILITY_NOT_ALLOWED,
                        "requested capability is not allowed by profile: " + capability);
            }
        }
    }

    private static Set<String> immutableInput(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        TreeSet<String> copy = new TreeSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not contain blank capability");
            }
            copy.add(value);
        }
        return Collections.unmodifiableSet(copy);
    }

    private static final class PlanningContext {
        private final CapabilityDescriptor provider;
        private final ProductProfile profile;
        private final Set<String> requested;
        private final Set<String> granted;
        private final DeploymentLimits limits;
        private final long revision;
        private final Map<String, CapabilityDecision> decisions = new HashMap<>();
        private final Set<String> selected = new HashSet<>();
        private final Set<String> evaluating = new HashSet<>();

        private PlanningContext(
                CapabilityDescriptor provider,
                ProductProfile profile,
                Set<String> requested,
                Set<String> granted,
                DeploymentLimits limits,
                long revision) {
            this.provider = provider;
            this.profile = profile;
            this.requested = requested;
            this.granted = granted;
            this.limits = limits;
            this.revision = revision;
        }

        private Set<String> selectedCapabilities() {
            return selected;
        }

        private CapabilityDecision decision(String capability) {
            CapabilityDecision decision = decisions.get(capability);
            if (decision == null) {
                throw new IllegalStateException("capability decision was not computed: " + capability);
            }
            return decision;
        }

        private CapabilityDecision notRequestedDecision(String capability) {
            CapabilityDecision.Availability availability = granted.contains(capability)
                    && limits.permits(capability)
                    && provider.supports(capability)
                    && nativeDependencyClosureAvailable(capability, new HashSet<>())
                    ? CapabilityDecision.Availability.AVAILABLE
                    : CapabilityDecision.Availability.UNAVAILABLE;
            return new CapabilityDecision(
                    capability,
                    availability,
                    false,
                    CapabilityDecision.Action.DISABLE,
                    null,
                    profile.affectedWorkflowsOf(capability),
                    profile.producedArtifactsOf(capability),
                    "NOT_REQUESTED",
                    revision);
        }

        private CapabilityDecision evaluate(String capability, boolean select) {
            boolean alreadySelected = selected.contains(capability);
            if (select) {
                selected.add(capability);
            }
            CapabilityDecision existing = decisions.get(capability);
            if (existing != null) {
                return existing;
            }
            if (!evaluating.add(capability)) {
                // ProductProfile 构造时已经做过校验，此分支是对未来动态 Profile
                // 实现的防线，避免依赖闭包递归耗尽栈空间。
                throw new CapabilityPlanningException(
                        CapabilityPlanningException.ReasonCode.CYCLIC_DEPENDENCY,
                        "cyclic capability dependency while planning: " + capability);
            }

            boolean required = profile.requiredCapabilities().contains(capability);
            boolean requestedOrRequired = required || requested.contains(capability)
                    || select || alreadySelected;
            CapabilityDecision result;
            if (!requestedOrRequired) {
                result = notRequestedDecision(capability);
                decisions.put(capability, result);
                evaluating.remove(capability);
                return result;
            }

            String missingReason = directMissingReason(capability);
            List<String> failedDependencies = new ArrayList<>();
            if (missingReason == null) {
                for (String dependency : profile.dependenciesOf(capability)) {
                    CapabilityDecision dependencyDecision = evaluate(dependency, true);
                    if (!isUsable(dependencyDecision)) {
                        failedDependencies.add(dependency);
                    }
                }
                if (!failedDependencies.isEmpty()) {
                    missingReason = "DEPENDENCY_UNAVAILABLE:" + String.join(",", failedDependencies);
                }
            }

            if (missingReason == null) {
                result = new CapabilityDecision(
                        capability,
                        CapabilityDecision.Availability.AVAILABLE,
                        required,
                        CapabilityDecision.Action.ENABLE,
                        null,
                        profile.affectedWorkflowsOf(capability),
                        profile.producedArtifactsOf(capability),
                        null,
                        revision);
            } else {
                result = resolveMissing(capability, required, missingReason);
            }
            decisions.put(capability, result);
            evaluating.remove(capability);
            return result;
        }

        private String directMissingReason(String capability) {
            if (!granted.contains(capability)) {
                return "NOT_GRANTED";
            }
            if (!limits.permits(capability)) {
                return "DEPLOYMENT_LIMIT";
            }
            if (!provider.supports(capability)) {
                return "PROVIDER_CAPABILITY_MISSING";
            }
            return null;
        }

        private CapabilityDecision resolveMissing(String capability, boolean required, String reason) {
            ProductProfile.MissingCapabilityPolicy policy = profile.missingPolicyOf(capability);
            String fallback = profile.fallbackOf(capability);
            if (policy == ProductProfile.MissingCapabilityPolicy.USE_FALLBACK) {
                if (fallback == null) {
                    reason = "FALLBACK_NOT_CONFIGURED";
                } else if (!hasExecutionMapping(fallback)) {
                    reason = "FALLBACK_MAPPING_MISSING:" + fallback;
                } else {
                    CapabilityDecision fallbackDecision = evaluate(fallback, true);
                    if (isUsable(fallbackDecision)) {
                        LOGGER.log(Level.INFO,
                                "能力计划使用已声明的降级实现 planRevision={0}", revision);
                        return new CapabilityDecision(
                                capability,
                                CapabilityDecision.Availability.DEGRADED,
                                required,
                                CapabilityDecision.Action.USE_FALLBACK,
                                fallback,
                                profile.affectedWorkflowsOf(fallback),
                                profile.producedArtifactsOf(fallback),
                                reason,
                                revision);
                    }
                    reason = "FALLBACK_UNAVAILABLE:" + fallback;
                }
            }

            if (required || policy == ProductProfile.MissingCapabilityPolicy.FAIL_MEETING) {
                return new CapabilityDecision(
                        capability,
                        CapabilityDecision.Availability.UNAVAILABLE,
                        required,
                        CapabilityDecision.Action.FAIL_MEETING,
                        null,
                        profile.affectedWorkflowsOf(capability),
                        profile.producedArtifactsOf(capability),
                        reason,
                        revision);
            }
            return new CapabilityDecision(
                    capability,
                    CapabilityDecision.Availability.UNAVAILABLE,
                    false,
                    CapabilityDecision.Action.DISABLE,
                    null,
                    profile.affectedWorkflowsOf(capability),
                    profile.producedArtifactsOf(capability),
                    reason,
                    revision);
        }

        private boolean hasExecutionMapping(String capability) {
            return !profile.affectedWorkflowsOf(capability).isEmpty()
                    && !profile.producedArtifactsOf(capability).isEmpty();
        }

        private boolean nativeDependencyClosureAvailable(String capability, Set<String> visiting) {
            if (!visiting.add(capability)) {
                throw new CapabilityPlanningException(
                        CapabilityPlanningException.ReasonCode.CYCLIC_DEPENDENCY,
                        "cyclic capability dependency while checking availability: " + capability);
            }
            for (String dependency : profile.dependenciesOf(capability)) {
                if (!granted.contains(dependency)
                        || !limits.permits(dependency)
                        || !provider.supports(dependency)
                        || !nativeDependencyClosureAvailable(dependency, visiting)) {
                    visiting.remove(capability);
                    return false;
                }
            }
            visiting.remove(capability);
            return true;
        }

        private static boolean isUsable(CapabilityDecision decision) {
            return decision.action() == CapabilityDecision.Action.ENABLE
                    || decision.action() == CapabilityDecision.Action.USE_FALLBACK;
        }
    }
}
