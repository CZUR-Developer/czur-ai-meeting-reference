package com.czur.aimeeting.client

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 断电残档的时长读取：只认持久前缀，末页被截断或 CRC 坏都不虚增时长。 */
class OggOpusDurationReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun ninetySecondsWithoutEos() {
        assertEquals(90000L, read(headers(), audio(2, 90000, 0)))
    }

    @Test fun twelveMinutesWithEos() {
        assertEquals(720000L, read(headers(), audio(2, 720000, 4)))
    }

    @Test fun initialZeroGranuleKeepsFollowingDurableAudioWithoutEos() {
        assertEquals(90000L, read(headers(), page(2, 0, 0, SERIAL, byteArrayOf(1)), audio(3, 90000, 0)))
    }

    @Test fun deviceEncoderZeroThen960GranulesHaveRecoverableDuration() {
        assertEquals(90000L, read(headers(), page(2, 0, 0, SERIAL, byteArrayOf(1)),
            page(3, 0, 960, SERIAL, byteArrayOf(1)), page(4, 4, 90000L * 48 + PRE_SKIP, SERIAL, byteArrayOf(1))))
    }

    @Test fun truncatedTailKeepsLastCompletePage() {
        val tail = audio(3, 2000, 4)
        for (length in intArrayOf(0, 3, 26, 27, 28, tail.size - 1)) {
            assertEquals(1000L, read(headers(), audio(2, 1000, 0), tail.copyOf(length)))
        }
    }

    @Test fun corruptedCrcDoesNotIncreaseDuration() {
        val tail = audio(3, 2000, 4)
        tail[tail.size - 1] = (tail[tail.size - 1].toInt() xor 1).toByte()
        assertEquals(1000L, read(headers(), audio(2, 1000, 0), tail))
    }

    @Test fun sequenceGapDoesNotIncreaseDuration() {
        assertEquals(1000L, read(headers(), audio(2, 1000, 0), audio(4, 2000, 4)))
    }

    @Test fun headerOnlyAndEmptyFileHaveZeroDuration() {
        assertEquals(0L, read(ByteArray(0)))
        assertEquals(0L, read(opusHead()))
        assertEquals(0L, read(headers()))
    }

    private fun read(vararg chunks: ByteArray): Long {
        val file: File = temporary.newFile()
        file.writeBytes(join(*chunks))
        return OggOpusDurationReader.readDurationMs(file)
    }

    private fun headers(): ByteArray = join(opusHead(), tags())

    private fun opusHead(): ByteArray = page(0, 2, 0, SERIAL, headPacket())

    private fun headPacket(): ByteArray {
        val packet = ByteArray(19)
        System.arraycopy("OpusHead".toByteArray(StandardCharsets.US_ASCII), 0, packet, 0, 8)
        packet[8] = 1
        packet[9] = 1
        put(packet, 10, PRE_SKIP.toLong(), 2)
        put(packet, 12, 16000, 4)
        return packet
    }

    private fun tags(): ByteArray {
        val packet = ByteArray(16)
        System.arraycopy("OpusTags".toByteArray(StandardCharsets.US_ASCII), 0, packet, 0, 8)
        return page(1, 0, 0, SERIAL, packet)
    }

    private fun audio(sequence: Int, durationMs: Long, flags: Int): ByteArray =
        page(sequence, flags, durationMs * 48L + PRE_SKIP, SERIAL, byteArrayOf(1))

    private fun page(sequence: Int, flags: Int, granule: Long, serial: Int, vararg segments: ByteArray): ByteArray {
        val body = join(*segments)
        val page = ByteArray(27 + segments.size + body.size)
        System.arraycopy("OggS".toByteArray(StandardCharsets.US_ASCII), 0, page, 0, 4)
        page[5] = flags.toByte()
        put(page, 6, granule, 8)
        put(page, 14, serial.toLong(), 4)
        put(page, 18, sequence.toLong(), 4)
        page[26] = segments.size.toByte()
        for (index in segments.indices) page[27 + index] = segments[index].size.toByte()
        System.arraycopy(body, 0, page, 27 + segments.size, body.size)
        var crc = 0
        for (value in page) {
            crc = crc xor ((value.toInt() and 0xff) shl 24)
            repeat(8) { crc = (crc shl 1) xor (if (crc < 0) 0x04c11db7 else 0) }
        }
        put(page, 22, crc.toLong() and 0xffffffffL, 4)
        return page
    }

    private fun join(vararg chunks: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        for (chunk in chunks) output.write(chunk)
        return output.toByteArray()
    }

    private fun put(bytes: ByteArray, offset: Int, value: Long, length: Int) {
        for (index in 0 until length) bytes[offset + index] = (value ushr (8 * index)).toByte()
    }

    private companion object {
        const val SERIAL = 7
        const val PRE_SKIP = 312
    }
}
