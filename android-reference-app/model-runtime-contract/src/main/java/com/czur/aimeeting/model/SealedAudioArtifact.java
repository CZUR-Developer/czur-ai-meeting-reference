package com.czur.aimeeting.model;

import java.io.File;
import java.util.Objects;

/**
 * 唯一本场标准 PCM 流停止并完成封口后的本地产物事实，不是正在录制的文件。
 * 运行时须先排完完整尾帧、编码 EOS、容器停止及文件同步，再交付此不可变描述。
 * 本类型不打开音源、不验证媒体可播放性，也不上传或删除文件；持有者须保持文件不变，
 * 直到部署方确认同场上传及资源登记。文件位置仅供本地发布器使用，不进入公共 HTTP 或日志。
 */
public final class SealedAudioArtifact {
    public static final int SAMPLE_RATE_HZ = 16_000;
    public static final int CHANNEL_COUNT = 1;
    public static final int BITS_PER_SAMPLE = 16;
    public static final int PCM_BYTES_PER_MS = 32;

    /** 明确区分 Opus/OGG 和未压缩 PCM16；不得把编码失败的文件声明为 Opus。 */
    public enum Format {
        OPUS_OGG("audio/ogg"),
        PCM16("application/octet-stream");
        private final String contentType;
        Format(String contentType) { this.contentType = contentType; }
        public String contentType() { return contentType; }
    }

    private final String meetingId;
    private final File file;
    private final Format format;
    private final long encodedBytes;
    private final long capturedPcmBytes;

    /**
     * encodedBytes 为封口后文件的真实字节数；capturedPcmBytes 只累计实交 PCM，
     * 不含模型重发和编码 padding。0 表示已确认零帧，而不是缺录音或封口失败。
     * PCM16 文件无容器或 padding，其文件长度必须与实交 PCM 字节数相同。
     */
    public SealedAudioArtifact(String meetingId, File file, Format format,
            long encodedBytes, long capturedPcmBytes) {
        this.meetingId = Objects.requireNonNull(meetingId, "meetingId");
        if (meetingId.isBlank()) throw new IllegalArgumentException("meetingId must not be blank");
        this.file = Objects.requireNonNull(file, "file").getAbsoluteFile();
        this.format = Objects.requireNonNull(format, "format");
        if (encodedBytes < 0 || capturedPcmBytes < 0 || capturedPcmBytes % 2 != 0)
            throw new IllegalArgumentException("audio byte counts must be non-negative PCM16 facts");
        if (format == Format.PCM16 && encodedBytes != capturedPcmBytes)
            throw new IllegalArgumentException("PCM16 file length must equal captured PCM bytes");
        this.encodedBytes = encodedBytes;
        this.capturedPcmBytes = capturedPcmBytes;
    }

    public String meetingId() { return meetingId; }
    public File file() { return file; }
    public Format format() { return format; }
    public String contentType() { return format.contentType(); }
    public long encodedBytes() { return encodedBytes; }
    public long capturedPcmBytes() { return capturedPcmBytes; }
    /** 与 frozenAudioDurationMs 同口径；编码 padding 不增加采音用量。 */
    public long durationMs() { return capturedPcmBytes / PCM_BYTES_PER_MS; }
    public int sampleRateHz() { return SAMPLE_RATE_HZ; }
    public int channelCount() { return CHANNEL_COUNT; }
    public int bitsPerSample() { return BITS_PER_SAMPLE; }

    @Override public String toString() {
        return "SealedAudioArtifact[meetingId=redacted, file=redacted, format=" + format
                + ", encodedBytes=" + encodedBytes + ", capturedPcmBytes=" + capturedPcmBytes + "]";
    }
}
