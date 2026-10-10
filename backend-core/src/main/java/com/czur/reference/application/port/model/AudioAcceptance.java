package com.czur.reference.application.port.model;

import java.util.Objects;

/** Driver 对单个音频帧的接收结果。 */
public record AudioAcceptance(
        Status status,
        long acceptedStreamSequence,
        long acceptedAudioPositionMs,
        int suggestedMaxInFlightFrames,
        int retryAfterMs) {

    public AudioAcceptance {
        Objects.requireNonNull(status, "status");
        if (acceptedStreamSequence < 0 || acceptedAudioPositionMs < 0) {
            throw new IllegalArgumentException("accepted positions must not be negative");
        }
        if (suggestedMaxInFlightFrames < 0 || retryAfterMs < 0) {
            throw new IllegalArgumentException("backpressure values must not be negative");
        }
    }

    /** 与公共 audio_ack 语义一致的接收状态。 */
    public enum Status {
        ACCEPTED,
        DUPLICATE,
        OUT_OF_ORDER,
        BACKPRESSURED,
        REJECTED,
        SESSION_TERMINAL
    }
}
