package com.czur.reference.application.finalization;

import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一场会议最终收口所需的、可跨进程恢复的冻结执行事实。
 *
 * <p>该事实把会议标识、会议开始时间、显式可空标题、完整 Product Profile 快照和
 * 完整 Capability Plan 固定在一个不可变值中。Worker 恢复时只能使用这里保存的值，
 * 不得重新查询当前 Profile、Provider 能力、数据库派生配置或当前时钟。它也刻意不
 * 保存 finalization receipt、访问 Token、会议/模型正文、对象存储凭据或预签名
 * URL；这些值属于其它安全边界，不是执行计划身份的一部分。</p>
 *
 * <p>构造时会验证 Capability Plan 对 Profile 的能力集合完整覆盖且每项只出现一次，
 * {@code requiredByProfile} 与 Profile 一致，并验证 workflow/artifact 映射没有漂移
 * 或重复项。{@code USE_FALLBACK} 仅用于读取旧冻结事实；当前 ProductProfile 会
 * 拒绝 fallback 配置，Planner 也不再生成该动作。其它动作的映射必须来自能力自身。
 * action/availability 以及 action/Profile policy 还必须是当前 Planner 能产生的
 * 组合：required 能力不能被关闭，optional 能力只有使用 {@code FAIL_MEETING} policy
 * 时才能产生 {@code FAIL_MEETING}。{@code ENABLE} 不得携带
 * warning，其它动作必须携带非空 warning；可用但未请求的 {@code DISABLE} 固定使用
 * {@code NOT_REQUESTED}。此外，fallback 目标以及 {@code ENABLE} 能力的全部依赖必须在
 * 同一 Plan 中可用。验证后决策按 capability 排序，使等价计划产生稳定的 canonical 表示。</p>
 *
 * <p>{@code startedAt} 必须位于四位年份协议值域，且纳秒部分能够被数据库
 * 时间列无损保存（即微秒精度）。实现拒绝而不是截断
 * 亚微秒输入，避免首次执行和数据库恢复后的事实发生漂移。标题和 Plan 决策中的
 * 全部动态文本同样必须是严格合法 UTF-8，防止不同非法 UTF-16 输入在编码时被替换成
 * 相同 canonical 字节和摘要。</p>
 */
public final class FinalizationExecutionFact {
    private static final String INVALID_FACT = "Finalization execution fact is invalid";
    private static final Instant MIN_PROTOCOL_INSTANT =
            Instant.parse("0000-01-01T00:00:00Z");
    private static final Instant MAX_PROTOCOL_INSTANT =
            Instant.parse("9999-12-31T23:59:59.999999Z");

    private final String meetingId;
    private final Instant startedAt;
    private final String title;
    private final ProductProfileSnapshot productProfile;
    private final CapabilityPlan capabilityPlan;

    /**
     * 使用已经冻结的 Profile 快照和能力计划恢复执行事实。
     *
     * <p>这是 JSON/持久化适配器的恢复入口。它执行与首次冻结完全相同的关闭式校验，
     * 因而损坏、缺项或映射漂移的数据不能进入 Worker。</p>
     *
     * @param meetingId 会议稳定标识；满足共享领域策略的严格 UTF-8 与 1024 字节上限
     * @param startedAt 会议开始时间；必须为数据库微秒精度
     * @param title 显式可空标题；非空时最多 256 个 Unicode code point
     * @param productProfile 创建会议时捕获的完整 Profile 快照
     * @param capabilityPlan 创建会议时确定的完整能力计划
     * @throws IllegalArgumentException 任一字段或 Profile/Plan 对应关系非法
     */
    public FinalizationExecutionFact(
            String meetingId,
            Instant startedAt,
            String title,
            ProductProfileSnapshot productProfile,
            CapabilityPlan capabilityPlan) {
        try {
            MeetingIdPolicy.requireValid(meetingId);
            requirePersistentInstant(startedAt);
            if (title != null) {
                // utf8Length 使用 REPORT 模式；不能让未配对 surrogate 在摘要编码时
                // 被替换为同一个字节序列。标题长度仍按公开契约的 code point 计算。
                MeetingIdPolicy.utf8Length(title);
                if (title.codePointCount(0, title.length()) > 256) {
                    throw invalid();
                }
            }
            Objects.requireNonNull(productProfile, "productProfile");
            Objects.requireNonNull(capabilityPlan, "capabilityPlan");

            List<CapabilityDecision> sortedDecisions = new ArrayList<>(
                    capabilityPlan.decisions());
            sortedDecisions.sort(Comparator.comparing(CapabilityDecision::capability));
            CapabilityPlan canonicalPlan = new CapabilityPlan(
                    capabilityPlan.planRevision(), sortedDecisions);
            validatePlan(productProfile, canonicalPlan);

            this.meetingId = meetingId;
            this.startedAt = startedAt;
            this.title = title;
            this.productProfile = productProfile;
            this.capabilityPlan = canonicalPlan;
        } catch (RuntimeException failure) {
            // 领域异常可能包含 capability；不得把值或 cause 带出执行事实边界。
            throw invalid();
        }
    }

    /**
     * 从创建会议时实际使用的领域 Profile 和 Plan 冻结执行事实。
     *
     * <p>该方法是首次写入路径的便利入口；它不读取任何外部状态，也不生成时间。
     * {@code startedAt} 必须由上游已经确定的会议事实显式传入。</p>
     *
     * @return 可直接 canonical 编码并在重启后恢复的执行事实
     */
    public static FinalizationExecutionFact freeze(
            String meetingId,
            Instant startedAt,
            String title,
            ProductProfile profile,
            CapabilityPlan capabilityPlan) {
        try {
            return new FinalizationExecutionFact(
                    meetingId,
                    startedAt,
                    title,
                    ProductProfileSnapshot.from(profile),
                    capabilityPlan);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /** 返回会议标识。调用方不得将其写入异常或诊断日志。 */
    public String meetingId() {
        return meetingId;
    }

    /** 返回已验证可由数据库微秒精度无损保存的开始时间。 */
    public Instant startedAt() {
        return startedAt;
    }

    /** 返回显式可空标题。调用方不得将非空值写入异常或诊断日志。 */
    public String title() {
        return title;
    }

    /** 返回完整、不可变的 Product Profile 快照。 */
    public ProductProfileSnapshot productProfile() {
        return productProfile;
    }

    /** 返回完整且按 capability 排序的 Capability Plan。 */
    public CapabilityPlan capabilityPlan() {
        return capabilityPlan;
    }

    /**
     * 从冻结快照恢复等价领域 Profile，不读取当前配置。
     */
    public ProductProfile restoreProductProfile() {
        return productProfile.toProductProfile();
    }

    /**
     * 诊断只显示时间、版本、规模和标题存在性；敏感标识及映射全部脱敏。
     */
    @Override
    public String toString() {
        return "FinalizationExecutionFact[meetingId=redacted"
                + ", startedAt=" + startedAt
                + ", titlePresent=" + (title != null)
                + ", profileRevision=" + productProfile.revision()
                + ", capabilityCount=" + productProfile.allCapabilities().size()
                + ", planRevision=" + capabilityPlan.planRevision()
                + ", decisionCount=" + capabilityPlan.decisions().size() + ']';
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FinalizationExecutionFact that)) {
            return false;
        }
        return meetingId.equals(that.meetingId)
                && startedAt.equals(that.startedAt)
                && Objects.equals(title, that.title)
                && productProfile.equals(that.productProfile)
                && capabilityPlan.equals(that.capabilityPlan);
    }

    @Override
    public int hashCode() {
        return Objects.hash(meetingId, startedAt, title, productProfile, capabilityPlan);
    }

    private static void validatePlan(
            ProductProfileSnapshot profile,
            CapabilityPlan plan) {
        Set<String> expected = profile.allCapabilities();
        Map<String, CapabilityDecision> decisionsByCapability = new HashMap<>();
        for (CapabilityDecision decision : plan.decisions()) {
            if (decisionsByCapability.putIfAbsent(decision.capability(), decision) != null) {
                throw invalid();
            }
            if (!expected.contains(decision.capability())) {
                throw invalid();
            }
            boolean required = profile.requiredCapabilities().contains(decision.capability());
            if (decision.requiredByProfile() != required) {
                throw invalid();
            }
            requireStrictUtf8(decision.capability());
            requireStrictUtf8(decision.fallbackCapability());
            requireStrictUtf8(decision.warningCode());
            requireStrictUtf8(decision.affectedWorkflows());
            requireStrictUtf8(decision.producedArtifacts());
            validateActionAvailability(decision);
            validateActionPolicy(profile, decision, required);
            validateWarning(decision);
            requireUnique(decision.affectedWorkflows());
            requireUnique(decision.producedArtifacts());

            String mappingCapability = decision.capability();
            if (decision.action() == CapabilityDecision.Action.USE_FALLBACK) {
                String configuredFallback = profile.fallbackCapabilities()
                        .get(decision.capability());
                if (profile.missingPolicyOf(decision.capability())
                        != ProductProfile.MissingCapabilityPolicy.USE_FALLBACK
                        || configuredFallback == null
                        || !configuredFallback.equals(decision.fallbackCapability())) {
                    throw invalid();
                }
                mappingCapability = configuredFallback;
            } else if (decision.fallbackCapability() != null) {
                throw invalid();
            }

            List<String> expectedWorkflows = profile.affectedWorkflows()
                    .getOrDefault(mappingCapability, List.of());
            List<String> expectedArtifacts = profile.producedArtifacts()
                    .getOrDefault(mappingCapability, List.of());
            if (!expectedWorkflows.equals(decision.affectedWorkflows())
                    || !expectedArtifacts.equals(decision.producedArtifacts())) {
                throw invalid();
            }
        }
        if (!decisionsByCapability.keySet().equals(expected)) {
            throw invalid();
        }

        // 与 CapabilityPlanner.isUsable 保持一致：ENABLE 和 USE_FALLBACK 才能
        // 满足依赖。每个节点都在上面的循环中完成 action/policy/mapping 校验，
        // 因而这里对直接边逐项检查即可关闭式覆盖整个无环依赖/fallback 链。
        for (CapabilityDecision decision : plan.decisions()) {
            if (decision.action() == CapabilityDecision.Action.ENABLE) {
                for (String dependency : profile.dependencies()
                        .getOrDefault(decision.capability(), Set.of())) {
                    if (!isUsable(decisionsByCapability.get(dependency))) {
                        throw invalid();
                    }
                }
            } else if (decision.action() == CapabilityDecision.Action.USE_FALLBACK
                    && !isUsable(decisionsByCapability.get(decision.fallbackCapability()))) {
                throw invalid();
            }
        }
        validateAcyclicExecutionGraph(profile, decisionsByCapability);
    }

    private static void validateActionPolicy(
            ProductProfileSnapshot profile,
            CapabilityDecision decision,
            boolean required) {
        if (required && decision.action() == CapabilityDecision.Action.DISABLE) {
            // Planner 对缺失的 required 能力始终 FAIL_MEETING；可用时 ENABLE，
            // 只有显式且可用的 fallback 才会 USE_FALLBACK。
            throw invalid();
        }
        if (!required
                && decision.action() == CapabilityDecision.Action.FAIL_MEETING
                && profile.missingPolicyOf(decision.capability())
                != ProductProfile.MissingCapabilityPolicy.FAIL_MEETING) {
            throw invalid();
        }
    }

    private static void validateActionAvailability(CapabilityDecision decision) {
        switch (decision.action()) {
            case ENABLE -> {
                if (decision.availability() != CapabilityDecision.Availability.AVAILABLE) {
                    throw invalid();
                }
            }
            case USE_FALLBACK -> {
                if (decision.availability() != CapabilityDecision.Availability.DEGRADED) {
                    throw invalid();
                }
            }
            case FAIL_MEETING -> {
                if (decision.availability() != CapabilityDecision.Availability.UNAVAILABLE) {
                    throw invalid();
                }
            }
            case DISABLE -> {
                // Planner 的未请求 optional 可以仍然是 AVAILABLE；只有 DEGRADED
                // 表示正在使用 fallback，与 DISABLE 动作矛盾。
                if (decision.availability() == CapabilityDecision.Availability.DEGRADED) {
                    throw invalid();
                }
            }
        }
    }

    private static void validateWarning(CapabilityDecision decision) {
        if (decision.action() == CapabilityDecision.Action.ENABLE) {
            if (decision.warningCode() != null) {
                throw invalid();
            }
            return;
        }
        if (decision.warningCode() == null) {
            throw invalid();
        }
        if (decision.action() == CapabilityDecision.Action.DISABLE
                && decision.availability() == CapabilityDecision.Availability.AVAILABLE
                && !"NOT_REQUESTED".equals(decision.warningCode())) {
            throw invalid();
        }
    }

    private static void requireUnique(List<String> values) {
        if (new HashSet<>(values).size() != values.size()) {
            throw invalid();
        }
    }

    private static boolean isUsable(CapabilityDecision decision) {
        return decision != null
                && (decision.action() == CapabilityDecision.Action.ENABLE
                || decision.action() == CapabilityDecision.Action.USE_FALLBACK);
    }

    private static void validateAcyclicExecutionGraph(
            ProductProfileSnapshot profile,
            Map<String, CapabilityDecision> decisionsByCapability) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String capability : decisionsByCapability.keySet()) {
            visitExecutionGraph(
                    capability, profile, decisionsByCapability, visiting, visited);
        }
    }

    private static void visitExecutionGraph(
            String capability,
            ProductProfileSnapshot profile,
            Map<String, CapabilityDecision> decisionsByCapability,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(capability)) {
            return;
        }
        if (!visiting.add(capability)) {
            throw invalid();
        }
        CapabilityDecision decision = decisionsByCapability.get(capability);
        if (decision.action() == CapabilityDecision.Action.ENABLE) {
            for (String dependency : profile.dependencies()
                    .getOrDefault(capability, Set.of())) {
                visitExecutionGraph(
                        dependency, profile, decisionsByCapability, visiting, visited);
            }
        } else if (decision.action() == CapabilityDecision.Action.USE_FALLBACK) {
            visitExecutionGraph(
                    decision.fallbackCapability(),
                    profile,
                    decisionsByCapability,
                    visiting,
                    visited);
        }
        visiting.remove(capability);
        visited.add(capability);
    }

    private static void requireStrictUtf8(String value) {
        if (value != null) {
            MeetingIdPolicy.utf8Length(value);
        }
    }

    private static void requireStrictUtf8(Iterable<String> values) {
        for (String value : values) {
            requireStrictUtf8(value);
        }
    }

    private static void requirePersistentInstant(Instant value) {
        Objects.requireNonNull(value, "startedAt");
        if (value.isBefore(MIN_PROTOCOL_INSTANT)
                || value.isAfter(MAX_PROTOCOL_INSTANT)
                || value.getNano() % 1_000 != 0) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID_FACT);
    }
}
