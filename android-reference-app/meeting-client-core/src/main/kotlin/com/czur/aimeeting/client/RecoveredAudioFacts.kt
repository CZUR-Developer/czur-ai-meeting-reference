package com.czur.aimeeting.client

import com.czur.aimeeting.model.SealedAudioArtifact
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 从进程异常退出后留下的同源录音文件重建续办事实。
 *
 * 只读取文件的持久前缀：Opus 按页读取，缺少 EOS 或末页被截断时停在最后完整页；
 * PCM 按字节数换算。capturedPcmBytes 由可读时长换算，是恢复估算，不冒充正常
 * 停止时由计数门给出的原值；无法读出时长时明确失败，不发布伪音频。
 */
internal object RecoveredAudioFacts {
    @Throws(IOException::class)
    fun sealedArtifact(meetingId: String, file: File): SealedAudioArtifact {
        require(meetingId.isNotBlank()) { "缺少会议编号" }
        if (!file.isFile) throw IOException("原场录音文件不存在")
        val encodedBytes = file.length()
        val pcm = file.extension.equals("pcm", ignoreCase = true)
        val durationMs = if (pcm) {
            encodedBytes / SealedAudioArtifact.PCM_BYTES_PER_MS
        } else {
            OggOpusDurationReader.readDurationMs(file)
        }
        if (durationMs <= 0L) throw IOException("原场录音时长不可用")
        val capturedPcmBytes = if (pcm) encodedBytes else durationMs * SealedAudioArtifact.PCM_BYTES_PER_MS
        val format = if (pcm) SealedAudioArtifact.Format.PCM16 else SealedAudioArtifact.Format.OPUS_OGG
        return SealedAudioArtifact(meetingId, file, format, encodedBytes, capturedPcmBytes)
    }
}

/** Reads the durable prefix of a single Ogg/Opus recording, including one without EOS. */
internal object OggOpusDurationReader {
    private const val MAX_PAGE_SIZE = 27 + 255 + 255 * 255
    private val CRC_TABLE = IntArray(256).also { table ->
        for (index in 0 until 256) {
            var value = index shl 24
            for (bit in 0 until 8) {
                value = if (value and 0x80000000.toInt() != 0) (value shl 1) xor 0x04c11db7
                else value shl 1
            }
            table[index] = value
        }
    }

    @Throws(IOException::class)
    fun readDurationMs(file: File): Long {
        BufferedInputStream(FileInputStream(file)).use { input ->
            val page = ByteArray(MAX_PAGE_SIZE)
            var size = readPage(input, page)
            // Our encoder writes a standalone, mapping-family-0 OpusHead BOS page.
            if (size < 0 || (page[5].toInt() and 0xff) != 2 || readInt(page, 18) != 0 ||
                readLong(page, 6) != 0L || (page[26].toInt() and 0xff) != 1 ||
                (page[27].toInt() and 0xff) < 19 || (page[27].toInt() and 0xff) == 255 ||
                !matches(page, 28, "OpusHead") || (page[36].toInt() and 0xff) > 15 ||
                (page[37].toInt() and 0xff) < 1 || (page[37].toInt() and 0xff) > 2 || page[46].toInt() != 0
            ) {
                return 0
            }
            val serial = readInt(page, 14)
            var sequence = 0
            val preSkip = (page[38].toInt() and 0xff) or ((page[39].toInt() and 0xff) shl 8)
            var lastGranule = 0L
            var tagsPending = true
            var tagsStarted = false
            var continued = false
            while (true) {
                size = readPage(input, page)
                if (size < 0) break
                val flags = page[5].toInt() and 0xff
                val segments = page[26].toInt() and 0xff
                if ((flags and 5.inv()) != 0 || readInt(page, 14) != serial ||
                    readInt(page, 18) != sequence + 1 || segments == 0 ||
                    ((flags and 1) != 0) != continued
                ) {
                    break
                }
                val body = 27 + segments
                if (!tagsStarted) {
                    if ((page[27].toInt() and 0xff) < 16 || !matches(page, body, "OpusTags")) break
                    tagsStarted = true
                }
                var audioCompleted = false
                for (index in 0 until segments) {
                    val length = page[27 + index].toInt() and 0xff
                    if (length < 255) {
                        if (tagsPending) tagsPending = false else audioCompleted = true
                    }
                }
                continued = (page[26 + segments].toInt() and 0xff) == 255
                val granule = readLong(page, 6)
                if (audioCompleted) {
                    // The device encoder starts at zero, before pre-skip has elapsed.
                    if (granule < lastGranule) break
                    lastGranule = granule
                } else if (granule != -1L && granule != 0L) {
                    break
                }
                sequence++
                if ((flags and 4) != 0) break
            }
            // Opus granules always use 48 kHz, NOT the encoder's 16 kHz input rate.
            return if (lastGranule > preSkip) (lastGranule - preSkip) / 48L else 0
        }
    }

    private fun readPage(input: InputStream, page: ByteArray): Int {
        if (!readFully(input, page, 0, 27) || !matches(page, 0, "OggS") || page[4].toInt() != 0) return -1
        val segments = page[26].toInt() and 0xff
        if (!readFully(input, page, 27, segments)) return -1
        var bodyLength = 0
        for (index in 0 until segments) bodyLength += page[27 + index].toInt() and 0xff
        val size = 27 + segments + bodyLength
        if (!readFully(input, page, 27 + segments, bodyLength)) return -1
        val expectedCrc = readInt(page, 22)
        var crc = 0
        for (index in 0 until size) {
            val value = if (index in 22..25) 0 else page[index].toInt() and 0xff
            crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor value) and 0xff]
        }
        return if (crc == expectedCrc) size else -1
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Boolean {
        var position = offset
        val end = offset + length
        while (position < end) {
            val read = input.read(buffer, position, end - position)
            if (read < 0) return false
            position += read
        }
        return true
    }

    private fun matches(bytes: ByteArray, offset: Int, signature: String): Boolean {
        for (index in signature.indices) {
            if (bytes[offset + index] != signature[index].code.toByte()) return false
        }
        return true
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int = readLittleEndian(bytes, offset, 4).toInt()

    private fun readLong(bytes: ByteArray, offset: Int): Long = readLittleEndian(bytes, offset, 8)

    private fun readLittleEndian(bytes: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        for (index in 0 until length) {
            value = value or ((bytes[offset + index].toLong() and 0xffL) shl (8 * index))
        }
        return value
    }
}
