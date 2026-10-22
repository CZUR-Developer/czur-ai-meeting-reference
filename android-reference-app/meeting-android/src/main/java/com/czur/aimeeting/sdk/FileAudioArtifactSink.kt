package com.czur.aimeeting.sdk

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import com.czur.aimeeting.model.SealedAudioArtifact
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Arrays

/**
 * 本场保存队列的文件 sink，优先 16k mono/32kbps Opus/OGG；只有能力缺失才输出 PCM16。
 * 编码开始后的失败不静默回退或伪造 sealed。构造不打开文件，write/seal 时延迟初始化。
 * 文件名按 meetingId 确定，宿主可在采音起点写原场恢复现场；文件内容仍只在 write/seal
 * 时产生。不上传、登记或自动删除；交付后宿主保持文件不变。
 */
class FileAudioArtifactSink internal constructor(private val meetingId: String, directory: File,
    private val encoding: Encoding, private val encoders: EncoderFactory) : AudioArtifactSink {
    enum class Encoding { OPUS_OR_PCM, OPUS_OGG, PCM16 }
    /** 包内编码替身，仅用于验证子帧/PTS/EOS，不构成新的音源或上传插件层。 */
    internal interface Encoder {
        @Throws(IOException::class) fun encode(pcm20ms: ByteArray, presentationTimeUs: Long)
        @Throws(IOException::class) fun finish(eosTimeUs: Long)
        fun cancel()
    }
    internal interface EncoderFactory {
        @Throws(IOException::class) fun available(): Boolean
        @Throws(IOException::class) fun create(file: File): Encoder
    }
    private val directory = directory.absoluteFile
    private val pending = ByteArray(OPUS_FRAME_BYTES)
    private var pendingBytes = 0
    private var writtenPcmBytes = 0L
    private var queuedPcmBytes = 0L
    private var file: File? = null
    private var pcmOutput: FileOutputStream? = null
    private var encoder: Encoder? = null
    private var format: SealedAudioArtifact.Format? = null
    private var sealed: SealedAudioArtifact? = null
    private var failure: IOException? = null
    private var cancelled = false
    /** 本场录音的确定文件名；封口前也供恢复现场记录，不再依赖目录内随机名。 */
    private val plannedFile: File = File(directory, "$meetingId.${if (plannedOpus()) "opus" else "pcm"}")

    init { require(meetingId.isNotEmpty()) { "meetingId must not be empty" } }

    override fun file(): File? = plannedFile

    /** 构造期只询问能力，不打开编码器；失败按无 Opus 能力处理，由 open 决定明确错误。 */
    private fun plannedOpus(): Boolean {
        if (encoding == Encoding.PCM16) return false
        return try { encoders.available() } catch (_: IOException) { false }
    }

    @Throws(IOException::class)
    override fun write(pcm: ByteArray) {
        requireWritable()
        try {
            if (pcm.size % 2 != 0) throw IOException("非完整PCM16样本")
            if (pcm.isEmpty()) return
            open(false)
            val output = pcmOutput
            if (output != null) output.write(pcm) else {
                var offset = 0
                while (offset < pcm.size) {
                    val count = Math.min(OPUS_FRAME_BYTES - pendingBytes, pcm.size - offset)
                    System.arraycopy(pcm, offset, pending, pendingBytes, count)
                    pendingBytes += count
                    offset += count
                    if (pendingBytes == OPUS_FRAME_BYTES) feedPending()
                }
            }
            writtenPcmBytes += pcm.size
        } catch (problem: IOException) { throw remember(problem) }
        catch (problem: RuntimeException) { throw remember(problem) }
    }

    @Throws(IOException::class)
    private fun feedPending() {
        // 每20ms子帧使用自己的起点，不使用已累加100ms chunk的末端。
        encoder!!.encode(pending.copyOf(OPUS_FRAME_BYTES), timeUs(queuedPcmBytes))
        queuedPcmBytes += OPUS_FRAME_BYTES
        pendingBytes = 0
    }

    @Throws(IOException::class)
    private fun open(zeroFrames: Boolean) {
        if (file != null) return
        var opus = encoding != Encoding.PCM16
        // 零帧优先模式明确给空 PCM，避免没有 OGG track 却声称是有效容器。
        if (encoding == Encoding.OPUS_OR_PCM && zeroFrames) opus = false
        if (opus && !encoders.available()) {
            if (encoding == Encoding.OPUS_OGG) throw IOException("Opus/OGG能力不可用")
            opus = false
            Log.i(TAG, "同源录音使用明确PCM16输出：Opus/OGG不可用")
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建录音目录")
        // 文件名在构造时已确定，保证恢复现场与真实文件一致；零帧强制 PCM 仍写同一位置。
        val created = plannedFile
        file = created
        format = if (opus) SealedAudioArtifact.Format.OPUS_OGG else SealedAudioArtifact.Format.PCM16
        if (opus) encoder = encoders.create(created) else pcmOutput = FileOutputStream(created)
        Log.i(TAG, "同源录音文件已打开 format=$format")
    }

    @Throws(IOException::class)
    override fun seal(capturedPcmBytes: Long): SealedAudioArtifact {
        failure?.let { throw it }
        sealed?.let {
            if (it.capturedPcmBytes() != capturedPcmBytes) throw IOException("重复封口的用量不一致")
            return it
        }
        requireWritable()
        try {
            if (capturedPcmBytes != writtenPcmBytes) throw IOException("保存字节与实交PCM不一致")
            open(capturedPcmBytes == 0L)
            if (encoder != null) {
                if (pendingBytes > 0) {
                    Arrays.fill(pending, pendingBytes, OPUS_FRAME_BYTES, 0.toByte())
                    feedPending() // padding 不增加 written/captured 或计费时长。
                }
                encoder!!.finish(timeUs(queuedPcmBytes))
                encoder = null
            } else {
                pcmOutput!!.flush()
                pcmOutput!!.fd.sync()
                pcmOutput!!.close()
                pcmOutput = null
            }
            val saved = file!!
            // 容器关闭后再同步，不能只以 close 返回当作保存成功。
            FileOutputStream(saved, true).use { it.fd.sync() }
            val artifact = SealedAudioArtifact(meetingId, saved, format!!, saved.length(), capturedPcmBytes)
            sealed = artifact
            Log.i(TAG, "同源录音已封口 format=$format pcmBytes=$capturedPcmBytes")
            return artifact
        } catch (problem: IOException) {
            val remembered = remember(problem)
            release()
            throw remembered
        } catch (problem: RuntimeException) {
            val remembered = remember(problem)
            release()
            throw remembered
        }
    }

    @Throws(IOException::class)
    private fun requireWritable() {
        failure?.let { throw it }
        if (cancelled || sealed != null) throw IOException("录音保存已结束")
    }
    private fun remember(problem: Exception): IOException {
        if (failure == null) {
            failure = IOException("本场录音保存或封口失败", problem)
            Log.w(TAG, "同源录音失败 type=" + problem.javaClass.simpleName)
        }
        return failure!!
    }
    override fun cancel() {
        if (cancelled || sealed != null) return
        cancelled = true
        release()
    }
    private fun release() {
        encoder?.let { it.cancel(); encoder = null }
        pcmOutput?.let {
            try { it.close() } catch (ignored: IOException) { Log.w(TAG, "取消录音时文件释放未确认") }
            pcmOutput = null
        }
        // 失败文件保持原行为：不自动上传、不在没有同场 ACK 时删除。
    }

    /** ByteBuffer 编码与 EOS 有界等待；未排空或容器失败必须外传。 */
    private class AndroidOpusEncoder(file: File) : Encoder {
        private var codec: MediaCodec? = null
        private var muxer: MediaMuxer? = null
        private var muxerStarted = false
        private var track = -1
        private var outputEos = false
        init {
            try {
                val created = MediaCodec.createEncoderByType("audio/opus")
                codec = created
                created.configure(opusFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                created.start()
                muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            } catch (failure: IOException) {
                cancel(); throw IOException("Opus编码初始化失败", failure)
            } catch (failure: RuntimeException) {
                cancel(); throw IOException("Opus编码初始化失败", failure)
            }
        }

        @Throws(IOException::class)
        override fun encode(pcm20ms: ByteArray, presentationTimeUs: Long) {
            val input = inputBuffer()
            val buffer = codec!!.getInputBuffer(input)
            if (buffer == null || buffer.capacity() < pcm20ms.size) throw IOException("Opus输入缓冲无效")
            buffer.clear()
            buffer.put(pcm20ms)
            codec!!.queueInputBuffer(input, 0, pcm20ms.size, presentationTimeUs, 0)
            drain(false)
        }
        @Throws(IOException::class)
        private fun inputBuffer(): Int {
            for (attempt in 0 until INPUT_ATTEMPTS) {
                val index = codec!!.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) return index
                drain(false)
            }
            throw IOException("Opus输入队列未排空")
        }
        @Throws(IOException::class)
        override fun finish(eosTimeUs: Long) {
            try {
                val input = inputBuffer()
                codec!!.queueInputBuffer(input, 0, 0, eosTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
                if (!outputEos || !muxerStarted) throw IOException("Opus输出EOS或容器未确认")
                // stop/release 都成功才允许外层同步文件并形成 sealed。
                codec!!.stop(); codec!!.release(); codec = null
                muxer!!.stop(); muxer!!.release(); muxer = null
            } catch (failure: IOException) { throw IOException("Opus排空或容器封口失败", failure) }
            catch (failure: RuntimeException) { throw IOException("Opus排空或容器封口失败", failure) }
            finally { cancel() }
        }
        @Throws(IOException::class)
        private fun drain(awaitEos: Boolean) {
            val info = MediaCodec.BufferInfo()
            var idle = 0
            while (!outputEos) {
                // 普通子帧只排已经准备的输出，不每20ms再等待10ms；EOS才按原超时轮询。
                val output = codec!!.dequeueOutputBuffer(info, if (awaitEos) TIMEOUT_US else 0)
                when {
                    output == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!awaitEos) return
                        if (++idle >= EOS_IDLE_LIMIT) throw IOException("Opus输出EOS等待超时")
                    }
                    output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) throw IOException("Opus输出格式重复改变")
                        track = muxer!!.addTrack(codec!!.outputFormat)
                        muxer!!.start()
                        muxerStarted = true
                    }
                    output >= 0 -> {
                        try {
                            if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                val buffer = codec!!.getOutputBuffer(output)
                                if (!muxerStarted || buffer == null) throw IOException("Opus输出或track无效")
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer!!.writeSampleData(track, buffer, info)
                            }
                            outputEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        } finally { codec!!.releaseOutputBuffer(output, false) }
                    }
                    output != MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> throw IOException("Opus输出状态无效")
                }
            }
        }
        override fun cancel() {
            codec?.let {
                try { it.stop() } catch (ignored: RuntimeException) {}
                try { it.release() } catch (ignored: RuntimeException) {}
                codec = null
            }
            muxer?.let {
                try { if (muxerStarted) it.stop() } catch (ignored: RuntimeException) {}
                try { it.release() } catch (ignored: RuntimeException) {}
                muxer = null
            }
        }
        private companion object {
            const val INPUT_ATTEMPTS = 50
            const val EOS_IDLE_LIMIT = 100
            const val TIMEOUT_US = 10_000L
        }
    }

    companion object {
        private const val TAG = "AiMeetingSdk"
        private const val OPUS_FRAME_BYTES = 640 // 20 ms；100 ms标准帧拆为5帧。
        @JvmStatic fun factory(directory: File): AudioArtifactSink.Factory = factory(directory, Encoding.OPUS_OR_PCM)
        @JvmStatic fun factory(directory: File, encoding: Encoding): AudioArtifactSink.Factory =
            AudioArtifactSink.Factory { id -> FileAudioArtifactSink(id, directory, encoding, ANDROID_ENCODERS) }
        private fun timeUs(pcmBytes: Long): Long = pcmBytes * 1_000_000L / 32_000L
        private fun opusFormat(): MediaFormat = MediaFormat.createAudioFormat("audio/opus", 16_000, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        private val ANDROID_ENCODERS = object : EncoderFactory {
            override fun available(): Boolean = Build.VERSION.SDK_INT >= 29 &&
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(opusFormat()) != null
            override fun create(file: File): Encoder = AndroidOpusEncoder(file)
        }
    }
}
