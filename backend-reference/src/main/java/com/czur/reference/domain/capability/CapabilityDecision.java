package com.czur.reference.domain.capability;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 本场会议针对单项能力的不可变决策。
 *
 * <p>{@link Action#ENABLE} 表示按 Provider 原生能力执行；
 * {@link Action#USE_FALLBACK} 表示使用 Profile 中明确登记的降级能力；
 * {@link Action#DISABLE} 表示关闭对应功能；{@link Action#FAIL_MEETING}
 * 表示该能力是本档位不可缺少的，会议不能继续。所有决策使用同一个
 * {@code planRevision}，便于 Android App 和 Web 按版本幂等更新。</p>
 */
public record CapabilityDecision(
        String capability,
        Availability availability,
        boolean requiredByProfile,
        Action action,
        String fallbackCapability,
        List<String> affectedWorkflows,
        List<String> producedArtifacts,
        String warningCode,
        long planRevision) {

    public CapabilityDecision {
        requireText("capability", capability);
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(action, "action");
        if (fallbackCapability != null && fallbackCapability.isBlank()) {
            throw new IllegalArgumentException("fallbackCapability must not be blank");
        }
        affectedWorkflows = immutableList(affectedWorkflows, "affectedWorkflows");
        producedArtifacts = immutableList(producedArtifacts, "producedArtifacts");
        if (warningCode != null && warningCode.isBlank()) {
            throw new IllegalArgumentException("warningCode must not be blank");
        }
        if (planRevision <= 0) {
            throw new IllegalArgumentException("planRevision must be positive");
        }
    }

    /** 能力在当前 Provider/部署下的事实状态。 */
    public enum Availability {
        AVAILABLE,
        DEGRADED,
        UNAVAILABLE
    }

    /** 编排器对能力采取的动作。 */
    public enum Action {
        ENABLE,
        DISABLE,
        USE_FALLBACK,
        FAIL_MEETING
    }

    private static List<String> immutableList(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        for (String value : values) {
            requireText(name + " item", value);
        }
        return Collections.unmodifiableList(List.copyOf(values));
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
