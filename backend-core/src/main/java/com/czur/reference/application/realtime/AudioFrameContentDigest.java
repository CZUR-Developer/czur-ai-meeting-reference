package com.czur.reference.application.realtime;

import com.czur.reference.application.port.model.MeetingAudioFrame;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 持久音频帧 v1 内容摘要的稳定字节域。
 *
 * <p>SHA-256 输入依次是 ASCII {@code ai-meeting/audio-frame/v1\0}、以下大端
 * 固定宽度字段：sequence(i64)、startMs(i64)、durationMs(i32)、
 * sourceGeneration(i64)、formatRevision(i32)、gapBeforeMs(i64)、flags 位图(i32)、
 * audio 长度(i32)、原始 audio 字节。flags 位 1/2/3 分别代表 AFTER_GAP、
 * SOURCE_CHANGED、PLATFORM_RESTARTED；UNKNOWN 无效。
 * 线上可选 checksum 只校验 audio 原始字节，不纳入内容身份：同一帧带或不带
 * 该冗余字段均视为相同内容。版本前缀使后续格式不能静默改变已有摘要。</p>
 *
 * <p>本类不决定写入权限、帧大小或 ACK；这些由持久端在事务中核对。不会记录
 * 原始音频或摘要。所有方法无可变共享状态，可跨线程复用。</p>
 */
public final class AudioFrameContentDigest {
    public static final int VERSION = 1;
    private static final byte[] PREFIX = "ai-meeting/audio-frame/v1\0"
            .getBytes(StandardCharsets.US_ASCII);

    private AudioFrameContentDigest() { }

    /** 完整帧内容 SHA-256；拒绝不规范的可选原始音频 checksum 和未知 flag。 */
    public static byte[] sha256(MeetingAudioFrame frame) {
        Objects.requireNonNull(frame, "frame");
        byte[] audio = frame.audio();
        String checksum = frame.checksum();
        if (checksum != null) {
            if (!checksum.matches("[0-9a-f]{64}")
                    || !MessageDigest.isEqual(
                            HexFormat.of().parseHex(checksum), digest(audio))) {
                throw new IllegalArgumentException("audio checksum mismatch");
            }
        }
        int flags = 0;
        for (MeetingAudioFrame.AudioFrameFlag flag : frame.flags()) {
            flags |= switch (flag) {
                case AFTER_GAP -> 1 << 1;
                case SOURCE_CHANGED -> 1 << 2;
                case PLATFORM_RESTARTED -> 1 << 3;
                case UNKNOWN -> throw new IllegalArgumentException("unknown audio flag");
            };
        }
        MessageDigest hash = newDigest();
        hash.update(PREFIX);
        hash.update(ByteBuffer.allocate(Long.BYTES * 4 + Integer.BYTES * 4)
                .putLong(frame.streamSequence())
                .putLong(frame.startPositionMs())
                .putInt(frame.durationMs())
                .putLong(frame.sourceGeneration())
                .putInt(frame.formatRevision())
                .putLong(frame.gapDurationMsBefore())
                .putInt(flags)
                .putInt(audio.length)
                .array());
        hash.update(audio);
        return hash.digest();
    }

    /** 便于固定向量与持久事实诊断；不得作为会议或业务事件 ID。 */
    public static String sha256Hex(MeetingAudioFrame frame) {
        return HexFormat.of().formatHex(sha256(frame));
    }

    private static byte[] digest(byte[] bytes) {
        return newDigest().digest(bytes);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 unavailable", missing);
        }
    }
}
