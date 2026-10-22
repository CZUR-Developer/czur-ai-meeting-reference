package com.czur.aimeeting.sdk

import com.czur.aimeeting.model.SealedAudioArtifact
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.Arrays
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 真本机文件/同步加窄 codec 替身，不代签 Android MediaCodec 或 Opus 可播放性。 */
class FileAudioArtifactSinkTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun pcmIsLazyExactAndSealedOnceWithoutOverwritingAnEarlierFile() {
        val directory = files.newFolder()
        val codec = FakeCodec()
        val sink = sink(directory, FileAudioArtifactSink.Encoding.PCM16, codec)
        assertEquals(0, directory.list()!!.size)
        val frame = ByteArray(3200) { 3 }
        sink.write(frame)
        Arrays.fill(frame, 9.toByte())
        val sealed = sink.seal(3200)
        assertEquals(SealedAudioArtifact.Format.PCM16, sealed.format())
        assertEquals("application/octet-stream", sealed.contentType())
        assertEquals(100L, sealed.durationMs())
        val saved = Files.readAllBytes(sealed.file().toPath())
        for (value in saved) assertEquals(3, value.toInt())
        assertSame(sealed, sink.seal(3200))
        assertThrows(IOException::class.java) { sink.write(ByteArray(3200)) }
        sink.cancel()
        assertTrue(sealed.file().isFile)
        val second = sink(directory, FileAudioArtifactSink.Encoding.PCM16, codec, "test-meeting-2")
        val zero = second.seal(0)
        assertNotEquals(sealed.file(), zero.file())
        assertEquals(3200L, sealed.file().length())
        assertEquals(0L, zero.encodedBytes())
        assertEquals(0, codec.created)
    }
    @Test fun opusSubframePtsStartAtZeroAndPaddingDoesNotIncreaseCapturedDuration() {
        val codec = FakeCodec()
        val sink = sink(files.newFolder(), FileAudioArtifactSink.Encoding.OPUS_OGG, codec)
        val whole = ByteArray(3200) { (it / 640 + 1).toByte() }
        sink.write(whole)
        Arrays.fill(whole, 99.toByte())
        val remainder = ByteArray(160) { 6 }
        sink.write(remainder)
        Arrays.fill(remainder, 99.toByte())
        val sealed = sink.seal(3360)
        assertEquals(listOf(0L, 20_000L, 40_000L, 60_000L, 80_000L, 100_000L), codec.pts)
        for (frame in 0 until 5) for (value in codec.frames[frame]) assertEquals(frame + 1, value.toInt())
        for (i in 0 until 640) assertEquals(if (i < 160) 6 else 0, codec.frames[5][i].toInt())
        assertEquals(120_000L, codec.eosUs)
        assertEquals(1, codec.finished)
        assertEquals(3360L, sealed.capturedPcmBytes())
        assertEquals(105L, sealed.durationMs())
        assertEquals(SealedAudioArtifact.Format.OPUS_OGG, sealed.format())
        assertEquals("audio/ogg", sealed.contentType())
        assertEquals(6L, sealed.encodedBytes()) // 窄替身容器，不能声明六字节真实 Opus。
        assertSame(sealed, sink.seal(3360))
        assertEquals(1, codec.finished)
    }
    @Test fun unavailableOpusFallsBackOnlyWhenExplicitlyAllowed() {
        val codec = FakeCodec().apply { available = false }
        val directory = files.newFolder()
        val fallback = sink(directory, FileAudioArtifactSink.Encoding.OPUS_OR_PCM, codec)
        fallback.write(ByteArray(3200))
        assertEquals(SealedAudioArtifact.Format.PCM16, fallback.seal(3200).format())
        assertEquals(0, codec.created)
        val strict = sink(directory, FileAudioArtifactSink.Encoding.OPUS_OGG, codec)
        assertThrows(IOException::class.java) { strict.write(ByteArray(3200)) }
        assertThrows(IOException::class.java) { strict.seal(3200) }
        strict.cancel()
        assertEquals("不可用检查在创建文件前", 1, directory.list()!!.size)
    }
    @Test fun zeroCapturedFramesHaveExplicitSealedPcmNotMissingArtifact() {
        val codec = FakeCodec()
        val sink = sink(files.newFolder(), FileAudioArtifactSink.Encoding.OPUS_OR_PCM, codec)
        val zero = sink.seal(0)
        assertEquals(0L, zero.capturedPcmBytes())
        assertEquals(0L, zero.durationMs())
        assertEquals(0L, zero.encodedBytes())
        assertEquals(SealedAudioArtifact.Format.PCM16, zero.format())
        assertEquals(0, codec.created)
        // 构造期为确定按场文件名探测一次 Opus 能力；零帧封口仍不创建编码器。
        assertEquals(1, codec.availabilityReads)
    }
    @Test fun codecWriteFailureCannotFallBackOrBecomeSealedOnRetry() {
        val codec = FakeCodec().apply { failWrite = true }
        val directory = files.newFolder()
        val sink = sink(directory, FileAudioArtifactSink.Encoding.OPUS_OR_PCM, codec)
        val original = assertThrows(IOException::class.java) { sink.write(ByteArray(3200)) }
        assertSame(original, assertThrows(IOException::class.java) { sink.seal(3200) })
        assertSame(original, assertThrows(IOException::class.java) { sink.write(ByteArray(3200)) })
        sink.cancel(); sink.cancel()
        assertEquals(1, codec.created)
        assertEquals(0, codec.finished)
        assertEquals(1, codec.cancelled)
        assertEquals(1, directory.list()!!.size)
    }
    @Test fun eosOrContainerFailureIsNotSwallowedAsSuccessfulSeal() {
        val codec = FakeCodec().apply { failFinish = true }
        val directory = files.newFolder()
        val sink = sink(directory, FileAudioArtifactSink.Encoding.OPUS_OGG, codec)
        sink.write(ByteArray(3200))
        val original = assertThrows(IOException::class.java) { sink.seal(3200) }
        assertSame(original, assertThrows(IOException::class.java) { sink.seal(3200) })
        assertEquals(1, codec.finished)
        assertEquals(1, codec.cancelled)
        assertEquals(1, directory.list()!!.size)
    }
    @Test fun cancelledAndMismatchedCapturedInputCannotProduceSealedSuccess() {
        val codec = FakeCodec()
        val cancelled = sink(files.newFolder(), FileAudioArtifactSink.Encoding.PCM16, codec)
        cancelled.write(ByteArray(3200)); cancelled.cancel()
        assertThrows(IOException::class.java) { cancelled.seal(3200) }
        val mismatch = sink(files.newFolder(), FileAudioArtifactSink.Encoding.PCM16, codec)
        mismatch.write(ByteArray(3200))
        assertThrows(IOException::class.java) { mismatch.seal(6400) }
        assertThrows(IOException::class.java) { mismatch.seal(3200) }
        mismatch.cancel()
    }
    private fun sink(directory: File, encoding: FileAudioArtifactSink.Encoding, codec: FakeCodec,
        meetingId: String = "test-meeting") =
        FileAudioArtifactSink(meetingId, directory, encoding, codec)
    private class FakeCodec : FileAudioArtifactSink.EncoderFactory, FileAudioArtifactSink.Encoder {
        val frames = ArrayList<ByteArray>()
        val pts = ArrayList<Long>()
        var available = true
        var failWrite = false
        var failFinish = false
        var availabilityReads = 0
        var created = 0
        var finished = 0
        var cancelled = 0
        var eosUs = 0L
        var output: FileOutputStream? = null
        override fun available(): Boolean { ++availabilityReads; return available }
        override fun create(file: File): FileAudioArtifactSink.Encoder {
            ++created; output = FileOutputStream(file); return this
        }
        override fun encode(pcm20ms: ByteArray, presentationTimeUs: Long) {
            if (failWrite) throw IOException("fixture-codec-input-failure")
            frames.add(pcm20ms); pts.add(presentationTimeUs); output!!.write(1)
        }
        override fun finish(eosTimeUs: Long) {
            ++finished; eosUs = eosTimeUs
            if (failFinish) throw IOException("fixture-eos-or-muxer-stop-failure")
            output!!.close(); output = null
        }
        override fun cancel() {
            ++cancelled
            if (output != null) try { output!!.close(); output = null }
            catch (ignored: IOException) { throw AssertionError(ignored) }
        }
    }
}
