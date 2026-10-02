package com.czur.reference.application.realtime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * P2 单逻辑流完整音频输入的版本化摘要，供最终接管事实使用。
 *
 * <p>v1 的 SHA-256 字节域依次为 ASCII {@code ai-meeting/audio-stream/v1\0}；
 * 对每个从 1 连续递增的帧，写入大端 {@code sequence(i64)}、
 * {@code frameDigestVersion(i32)}、32 字节帧内容摘要；最后写入 ASCII
 * {@code ai-meeting/audio-stream/end/v1\0} 和大端完成终点
 * {@code lastStreamSequence(i64), lastAudioPositionMs(i64)}。返回值格式为
 * {@code sha256-v1:} 加小写 64 位十六进制。帧内容摘要必须按
 * {@link AudioFrameContentDigest} 从原始帧重新校验，不能仅信任数据库保存的
 * 32 字节或设备声明的单帧 checksum。</p>
 *
 * <p>本类只定义确定性字节域，不证明帧已经持久提交、完成声明已经落盘或录音
 * 原文件已上传。调用者必须在同一稳定读取视图中确认这些事实后，按顺序输入
 * 经校验的帧摘要。P2 只支持 v1 帧与流式音频；将来若把独立录音文件纳入输入
 * 身份，应定义新的摘要版本，而不能改变本版本的字节域。实例仅供单线程使用，
 * 成功结束后不可再次追加或结束。</p>
 */
public final class AudioStreamContentDigest {
    public static final int VERSION = 1;
    private static final byte[] PREFIX = "ai-meeting/audio-stream/v1\0"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] END = "ai-meeting/audio-stream/end/v1\0"
            .getBytes(StandardCharsets.US_ASCII);

    private final MessageDigest hash = newDigest();
    private long lastSequence;
    private boolean finished;

    public AudioStreamContentDigest() {
        hash.update(PREFIX);
    }

    /**
     * 加入一帧已经与持久原始字节复核的内容摘要。
     *
     * @param sequence 从 1 开始连续递增的持久流序号
     * @param frameDigestVersion 已校验帧摘要的编码版本，当前只能为 v1
     * @param frameDigest 32 字节 SHA-256，方法调用后不保留该数组
     * @throws IllegalArgumentException 版本、摘要长度或连续序号不符合 v1 约束
     * @throws IllegalStateException 已经成功结束，或序号已经无法继续递增
     */
    public void append(long sequence, int frameDigestVersion, byte[] frameDigest) {
        if (finished || lastSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("audio stream digest already finished");
        }
        if (sequence != lastSequence + 1 || frameDigestVersion != AudioFrameContentDigest.VERSION
                || Objects.requireNonNull(frameDigest, "frameDigest").length != 32) {
            throw new IllegalArgumentException("invalid ordered frame digest");
        }
        hash.update(ByteBuffer.allocate(Long.BYTES + Integer.BYTES)
                .putLong(sequence).putInt(frameDigestVersion).array());
        hash.update(frameDigest);
        lastSequence = sequence;
    }

    /**
     * 加入持久完成声明的终点并结束计算。
     *
     * @param lastStreamSequence 完成声明中的最后帧序号，必须等于已追加帧数
     * @param lastAudioPositionMs 完成声明中的音频时间轴终点，必须为正
     * @return 可保存到 {@code FinalizationInputFact.contentDigest} 的版本化文本
     * @throws IllegalArgumentException 未收到帧，或声明终点与已追加帧不一致
     * @throws IllegalStateException 实例已成功结束
     */
    public String finish(long lastStreamSequence, long lastAudioPositionMs) {
        if (finished) throw new IllegalStateException("audio stream digest already finished");
        if (lastSequence == 0 || lastStreamSequence != lastSequence
                || lastAudioPositionMs <= 0) {
            throw new IllegalArgumentException("incomplete audio stream endpoint");
        }
        hash.update(END);
        hash.update(ByteBuffer.allocate(Long.BYTES * 2)
                .putLong(lastStreamSequence).putLong(lastAudioPositionMs).array());
        finished = true;
        return "sha256-v1:" + HexFormat.of().formatHex(hash.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 unavailable", missing);
        }
    }
}
