package com.czur.reference.application.port.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 已由设备应用规范化后的会议音频帧。
 *
 * <p>sequence 必须从 1 开始连续递增；Driver 按序去重并拒绝乱序输入。音频
 * 字节会在构造和读取时复制，调用方无法在 Driver 处理期间修改共享内容。
 * 该对象不携带用户身份、模型密钥或供应商字段。</p>
 */
public record MeetingAudioFrame(
        long streamSequence,
        long startPositionMs,
        int durationMs,
        byte[] audio,
        String checksum,
        long sourceGeneration,
        int formatRevision,
        long gapDurationMsBefore,
        Set<AudioFrameFlag> flags) {

    public MeetingAudioFrame {
        if (streamSequence <= 0) {
            throw new IllegalArgumentException("streamSequence must be positive");
        }
        if (startPositionMs < 0) {
            throw new IllegalArgumentException("startPositionMs must not be negative");
        }
        if (durationMs <= 0) {
            throw new IllegalArgumentException("durationMs must be positive");
        }
        Objects.requireNonNull(audio, "audio");
        if (audio.length == 0) {
            throw new IllegalArgumentException("audio must not be empty");
        }
        if (checksum != null && checksum.isBlank()) {
            throw new IllegalArgumentException("checksum must not be blank");
        }
        if (sourceGeneration < 0) {
            throw new IllegalArgumentException("sourceGeneration must not be negative");
        }
        if (formatRevision < 0) {
            throw new IllegalArgumentException("formatRevision must not be negative");
        }
        if (gapDurationMsBefore < 0) {
            throw new IllegalArgumentException("gapDurationMsBefore must not be negative");
        }
        Objects.requireNonNull(flags, "flags");
        EnumSet<AudioFrameFlag> copiedFlags = flags.isEmpty()
                ? EnumSet.noneOf(AudioFrameFlag.class)
                : EnumSet.copyOf(flags);
        if (copiedFlags.contains(null)) {
            throw new IllegalArgumentException("flags must not contain null");
        }
        flags = Collections.unmodifiableSet(copiedFlags);
        audio = Arrays.copyOf(audio, audio.length);
    }

    /** 读取音频副本，避免调用方修改 Driver 内部数据。 */
    @Override
    public byte[] audio() {
        return Arrays.copyOf(audio, audio.length);
    }

    /** 系统音频来源/格式变化等诊断标记。 */
    public enum AudioFrameFlag {
        UNKNOWN,
        AFTER_GAP,
        SOURCE_CHANGED,
        PLATFORM_RESTARTED
    }
}
