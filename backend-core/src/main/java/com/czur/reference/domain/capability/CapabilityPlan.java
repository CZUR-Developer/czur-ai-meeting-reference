package com.czur.reference.domain.capability;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** 一次会议能力计算的不可变结果。 */
public record CapabilityPlan(long planRevision, List<CapabilityDecision> decisions) {
    public CapabilityPlan {
        if (planRevision <= 0) {
            throw new IllegalArgumentException("planRevision must be positive");
        }
        Objects.requireNonNull(decisions, "decisions");
        decisions = List.copyOf(decisions);
        for (CapabilityDecision decision : decisions) {
            Objects.requireNonNull(decision, "decision");
            if (decision.planRevision() != planRevision) {
                throw new IllegalArgumentException("all decisions must use planRevision " + planRevision);
            }
        }
    }

    /** 本计划中是否存在必选能力失败。 */
    public boolean failed() {
        return decisions.stream().anyMatch(decision ->
                decision.action() == CapabilityDecision.Action.FAIL_MEETING);
    }

    /** 返回指定能力的决策；不存在时返回空。 */
    public CapabilityDecision decisionFor(String capability) {
        return decisions.stream()
                .filter(decision -> decision.capability().equals(capability))
                .findFirst()
                .orElse(null);
    }

    /** 返回本计划实际 ENABLE 的能力；已移除 fallback 决策。 */
    public Set<String> enabledCapabilities() {
        TreeSet<String> enabled = decisions.stream()
                .filter(decision -> decision.action() == CapabilityDecision.Action.ENABLE)
                .map(CapabilityDecision::capability)
                .collect(Collectors.toCollection(TreeSet::new));
        return Collections.unmodifiableSet(enabled);
    }
}
