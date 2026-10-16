package com.czur.aimeeting.model

import java.io.File

/**
 * 同场唯一 PCM 流停止、尾帧排完并完成编码 EOS／容器停止／文件同步后的本地产物事实。
 * 不打开音源、不上传／删除、不证明可播放；持有者保持文件不变直至同场发布确认。
 * 文件路径只供本地发布器使用，不进入公共 HTTP 或日志。
 */
class SealedAudioArtifact(
    private val meetingId: String,
    file: File,
    private val format: Format,
    private val encodedBytes: Long,
    private val capturedPcmBytes: Long,
) {
    private val file = file.absoluteFile
    init {
        require(meetingId.isNotBlank()) { "meetingId must not be blank" }
        require(encodedBytes >= 0 && capturedPcmBytes >= 0 && capturedPcmBytes % 2 == 0L) {
            "audio byte counts must be non-negative PCM16 facts"
        }
        require(format != Format.PCM16 || encodedBytes == capturedPcmBytes) {
            "PCM16 file length must equal captured PCM bytes"
        }
    }

    /** 编码失败不能将文件声明成 Opus。 */
    enum class Format(private val contentType: String) {
        OPUS_OGG("audio/ogg"), PCM16("application/octet-stream");
        fun contentType(): String = contentType
    }

    fun meetingId(): String = meetingId
    fun file(): File = file
    fun format(): Format = format
    fun contentType(): String = format.contentType()
    fun encodedBytes(): Long = encodedBytes
    /** 实交 PCM 字节，不含模型重发／编码 padding，0 为确认零帧。 */
    fun capturedPcmBytes(): Long = capturedPcmBytes
    /** 与 frozenAudioDurationMs 口径一致。 */
    fun durationMs(): Long = capturedPcmBytes / PCM_BYTES_PER_MS
    fun sampleRateHz(): Int = SAMPLE_RATE_HZ
    fun channelCount(): Int = CHANNEL_COUNT
    fun bitsPerSample(): Int = BITS_PER_SAMPLE
    override fun toString(): String = "SealedAudioArtifact[meetingId=redacted, file=redacted, format=$format, " +
        "encodedBytes=$encodedBytes, capturedPcmBytes=$capturedPcmBytes]"

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val PCM_BYTES_PER_MS = 32
    }
}
