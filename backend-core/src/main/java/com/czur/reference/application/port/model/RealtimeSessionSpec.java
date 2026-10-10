package com.czur.reference.application.port.model;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 打开模型实时会话所需的公共参数。
 *
 * <p>spec 不携带伙伴 Token、供应商 Task ID 或客户端原始能力请求。应用层必须
 * 先通过 CapabilityPlanner 固化本场计划，再把计划中的 enabledCapabilities
 * 和 planRevision 传给 Driver。这样“客户端请求但未获授权”的能力不会越过
 * Orchestrator 直接开启模型功能。</p>
 */
public record RealtimeSessionSpec(
        String meetingId,
        String streamId,
        String sourceLanguage,
        AudioProfile audioProfile,
        Set<String> enabledCapabilities,
        long planRevision,
        long maxDurationMs,
        String settingsRevision) {

    public RealtimeSessionSpec {
        requireText("meetingId", meetingId);
        requireText("streamId", streamId);
        requireText("sourceLanguage", sourceLanguage);
        Objects.requireNonNull(audioProfile, "audioProfile");
        Objects.requireNonNull(enabledCapabilities, "enabledCapabilities");
        TreeSet<String> copied = new TreeSet<>();
        for (String capability : enabledCapabilities) {
            requireText("enabled capability", capability);
            copied.add(capability);
        }
        enabledCapabilities = Collections.unmodifiableSet(copied);
        if (planRevision <= 0) {
            throw new IllegalArgumentException("planRevision must be positive");
        }
        if (maxDurationMs <= 0) {
            throw new IllegalArgumentException("maxDurationMs must be positive");
        }
        requireText("settingsRevision", settingsRevision);
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
