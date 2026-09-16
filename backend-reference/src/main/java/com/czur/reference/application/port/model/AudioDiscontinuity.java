package com.czur.reference.application.port.model;

import java.util.Objects;

/**
 * 系统音频切源、丢帧或 Broker 重启的明确通知。
 *
 * <p>Driver 必须返回确定的继续、重开或失败动作；不能把缺口静默当作正常
 * 网络重试，也不能重置公共 streamSequence。</p>
 */
public record AudioDiscontinuity(
        long afterStreamSequence,
        long resumeAudioPositionMs,
        long sourceGeneration,
        int formatRevision,
        Reason reason,
        long gapDurationMs) {

    public AudioDiscontinuity {
        if (afterStreamSequence < 0 || resumeAudioPositionMs < 0 || sourceGeneration < 0
                || formatRevision < 0 || gapDurationMs < 0) {
            throw new IllegalArgumentException("discontinuity numbers must not be negative");
        }
        Objects.requireNonNull(reason, "reason");
    }

    /** 与公开实时协议保持一致的缺口原因。 */
    public enum Reason {
        UNKNOWN,
        DROPPED_FRAMES,
        SOURCE_CHANGED,
        FORMAT_CHANGED,
        BROKER_RESTARTED
    }
}
