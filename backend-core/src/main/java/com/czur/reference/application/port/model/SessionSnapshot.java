package com.czur.reference.application.port.model;

import java.util.Objects;

/**
 * <p>只读会话诊断快照；用于测试、指标和运维，不作为会议事实源。
 * 不包含音频、Token、模型正文或供应商返回内容。</p>
 */
public record SessionSnapshot(
        ModelDriverSession session,
        ModelDriverSessionState state,
        long acceptedStreamSequence,
        long acceptedAudioPositionMs,
        int emittedEventCount) {
    public SessionSnapshot {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(state, "state");
        if (acceptedStreamSequence < 0 || acceptedAudioPositionMs < 0 || emittedEventCount < 0) {
            throw new IllegalArgumentException("snapshot values must not be negative");
        }
    }
}
