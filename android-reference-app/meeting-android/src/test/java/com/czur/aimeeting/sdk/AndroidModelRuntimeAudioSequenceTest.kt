package com.czur.aimeeting.sdk

import com.czur.aimeeting.model.AudioDirectionSample
import com.czur.aimeeting.model.AudioInputMode
import com.czur.aimeeting.model.AudioInputLevel
import com.czur.aimeeting.model.ModelAdapter
import com.czur.aimeeting.model.ModelSessionGrant
import com.czur.aimeeting.model.ModelSessionListener
import com.czur.aimeeting.model.ModelSignal
import com.czur.aimeeting.model.SegmentStartedEvent
import com.czur.aimeeting.model.SegmentStartedListener
import com.czur.aimeeting.model.SealedAudioArtifact
import com.czur.aimeeting.model.TranscriptEvent
import com.czur.aimeeting.model.TranscriptListener
import com.czur.aimeeting.model.TranslationEvent
import com.czur.aimeeting.model.TranslationListener
import java.io.File
import java.io.IOException
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

/** 可控完整 PCM 帧与真实环回 WebSocket，证明开场、READY、尾帧及最终输出的正常顺序。 */
class AndroidModelRuntimeAudioSequenceTest {
    @Test fun retainedBeforeReadyUsesSameCountedFramesAndTailThenSealsOnlyOnce() {
        val recording = RecordingSink()
        Fixture(true, false, recording).use { fixture ->
            fixture.start(); fixture.awaitProbe()
            assertFalse(fixture.session.sealedAudioArtifact().isPresent)
            fixture.session.stopInput()
            val sealed = fixture.session.sealedAudioArtifact().orElseThrow()
            assertEquals(listOf("A", "B", "T"), recording.markers)
            assertEquals(9600L, sealed.capturedPcmBytes())
            assertEquals(300L, sealed.durationMs())
            assertEquals(1, recording.seals)
            fixture.sendReady(); fixture.awaitFinished(); fixture.session.stopInput(); fixture.session.close()
            fixture.audio.emit('L')
            assertSame(sealed, fixture.session.sealedAudioArtifact().orElseThrow())
            assertEquals(listOf("A", "B", "T"), recording.markers)
            assertEquals(listOf("start", "A", "B", "T", "eof"), fixture.received)
            assertEquals(1, recording.seals)
            assertFalse(recording.calledWhileSessionLocked)
            fixture.assertNormalCompletion()
        }
    }

    @Test fun retainedAfterReadyIncludesEveryFrameAndKeepsNormalModelFifo() {
        val recording = RecordingSink()
        Fixture(true, false, recording).use { fixture ->
            fixture.start(); fixture.awaitProbe(); fixture.sendReady(); fixture.awaitInitialFrames()
            fixture.audio.emit('C'); fixture.session.stopInput(); fixture.awaitFinished()
            assertEquals(listOf("A", "B", "C", "T"), recording.markers)
            assertEquals(400L, fixture.session.sealedAudioArtifact().orElseThrow().durationMs())
            assertEquals(listOf("start", "A", "B", "C", "T", "eof"), fixture.received)
            fixture.assertNormalCompletion()
        }
    }

    @Test fun modelEofPrecedesBlockedSealButFinishedWaitsForReadableArtifact() {
        val sealEntered = CountDownLatch(1)
        val allowSeal = CountDownLatch(1)
        val stopFailure = AtomicReference<Throwable>()
        val recording = RecordingSink()
        recording.beforeSeal = Runnable { sealEntered.countDown(); await(allowSeal) }
        Fixture(true, false, recording).use { fixture ->
            // 本例由测试同步送回 FINISHED，明确验证它与阻塞封口的交错；EOF仍走真实环回。
            fixture.autoFinishOnEof = false
            fixture.start(); fixture.awaitProbe(); fixture.sendReady(); fixture.awaitInitialFrames()
            val stop = Thread({
                try { fixture.session.stopInput() } catch (failure: Throwable) { stopFailure.set(failure) }
            }, "test-stop-with-blocked-artifact-seal")
            try {
                stop.start(); await(sealEntered); await(fixture.eofReceived)
                assertEquals(listOf("start", "A", "B", "T", "eof"), fixture.received)
                assertEquals(300L, fixture.session.frozenAudioDurationMs().asLong)
                assertFalse(fixture.session.sealedAudioArtifact().isPresent)
                fixture.session.handleServerMessage("final")
                assertEquals("FINISHED不能越过文件封口", 1L, fixture.finished.count)
                assertFalse(fixture.session.sealedAudioArtifact().isPresent)
                allowSeal.countDown(); stop.join(3000)
                assertFalse(stop.isAlive)
                assertNull(stopFailure.get())
                fixture.awaitFinished()
                assertEquals(300L, fixture.session.sealedAudioArtifact().orElseThrow().durationMs())
                assertEquals(1, recording.seals)
                assertEquals(0, recording.cancels)
                fixture.assertNormalCompletion()
            } finally { allowSeal.countDown(); stop.join(3000) }
        }
    }

    @Test fun modelFailureRetainsCapturedFramesAndCleanupTailBeforeError() {
        val recording = RecordingSink()
        Fixture(true, false, recording).use { fixture ->
            fixture.start(); fixture.awaitProbe()
            assertTrue(fixture.peer.get().send("model-failure"))
            await(fixture.finished)
            assertEquals("模型响应解析失败", fixture.error.get())
            val sealed = fixture.session.sealedAudioArtifact().orElseThrow()
            assertEquals(listOf("A", "B", "T"), recording.markers)
            assertEquals(9600L, sealed.capturedPcmBytes())
            assertEquals(300L, sealed.durationMs())
            assertEquals(300L, fixture.session.frozenAudioDurationMs().asLong)
            assertEquals(1, recording.seals)
            assertEquals(0, recording.cancels)
            assertEquals(1, fixture.audio.stops)
            fixture.session.close(); fixture.audio.emit('L')
            assertSame(sealed, fixture.session.sealedAudioArtifact().orElseThrow())
            assertEquals(listOf("A", "B", "T"), recording.markers)
            assertFalse(recording.calledWhileSessionLocked)
        }
    }

    @Test fun sealFailureRemainsExplicitWhileNormalModelEofAndUsageStillComplete() {
        val recording = RecordingSink().apply { failSeal = true }
        Fixture(true, true, recording).use { fixture ->
            fixture.start(); fixture.awaitProbe()
            val failure = assertThrows(IOException::class.java) { fixture.session.sealedAudioArtifact() }
            fixture.sendReady(); fixture.awaitFinished()
            assertSame(failure, assertThrows(IOException::class.java) { fixture.session.sealedAudioArtifact() })
            assertEquals(300L, fixture.session.frozenAudioDurationMs().asLong)
            assertEquals(listOf("A", "B", "T"), recording.markers)
            assertEquals(1, recording.seals)
            assertEquals(1, recording.cancels)
            assertFalse(recording.calledWhileSessionLocked)
            fixture.assertNormalCompletion()
        }
    }

    @Test fun fileWriteFailureKeepsCapturedUsageAndModelFlowButNeverClaimsSealedSuccess() {
        val recording = RecordingSink().apply { failWrite = true }
        Fixture(true, false, recording).use { fixture ->
            fixture.start(); fixture.awaitProbe(); fixture.session.stopInput()
            assertThrows(IOException::class.java) { fixture.session.sealedAudioArtifact() }
            fixture.sendReady(); fixture.awaitFinished()
            assertEquals(300L, fixture.session.frozenAudioDurationMs().asLong)
            assertEquals(0, recording.seals)
            assertEquals(1, recording.cancels)
            assertEquals(listOf("start", "A", "B", "T", "eof"), fixture.received)
            fixture.assertNormalCompletion()
        }
    }

    @Test fun oneSecondCountsCapturedFramesBeforeReadyAndFreezesAcrossStopAndClose() {
        Fixture(false, false).use { fixture ->
            fixture.start(); fixture.awaitProbe()
            assertFalse(fixture.session.frozenAudioDurationMs().isPresent)
            assertFalse(fixture.session.sealedAudioArtifact().isPresent)
            // 开场 A/B 加八帧共 32000 字节；尚未 READY 的缓冲帧也属于实采。
            for (marker in 'C'..'J') fixture.audio.emit(marker)
            assertEquals(3, fixture.levels.size)
            assertEquals(listOf(300L, 600L, 900L), listOf(
                fixture.levels[0].sampledAtElapsedRealtimeMs(), fixture.levels[1].sampledAtElapsedRealtimeMs(),
                fixture.levels[2].sampledAtElapsedRealtimeMs()))
            fixture.session.stopInput()
            assertEquals(1000L, fixture.session.frozenAudioDurationMs().asLong)
            assertEquals(3, fixture.levels.size)
            fixture.sendReady(); fixture.awaitFinished(); fixture.session.stopInput(); fixture.session.close()
            fixture.audio.emit('L') // 停流后的迟到回调不能改变冻结值。
            assertEquals(1000L, fixture.session.frozenAudioDurationMs().asLong)
            fixture.assertNormalCompletion()
        }
    }

    @Test fun delayedReadyBuffersFirstFramesAndDirectionBeforeModelConnects() {
        Fixture(false, false).use { fixture ->
            fixture.start(); fixture.awaitProbe()
            assertEquals(listOf("mode", "direction"), fixture.delivered)
            assertEquals(listOf("start"), fixture.received)
            fixture.sendReady(); fixture.audio.emit('C'); fixture.session.stopInput(); fixture.awaitFinished()
            assertEquals(listOf("start", "A", "B", "C", "eof"), fixture.received)
            fixture.assertNormalCompletion()
        }
    }

    @Test fun stopBeforeReadyKeepsInitialAndStopTailFramesUntilReady() {
        Fixture(true, false).use { fixture ->
            fixture.start(); fixture.awaitProbe(); fixture.session.stopInput()
            assertEquals(listOf("start"), fixture.received)
            assertEquals(1, fixture.audio.stops)
            assertTrue("第三帧是正常停止尾帧，只发模型／计用量，不生成电平", fixture.levels.isEmpty())
            fixture.sendReady(); fixture.awaitFinished()
            assertEquals(listOf("start", "A", "B", "T", "eof"), fixture.received)
            fixture.assertNormalCompletion()
        }
    }

    @Test fun stopAfterReadySendsCompleteTailBeforeOneEofAndFinalCallbacks() {
        Fixture(true, false).use { fixture ->
            fixture.start(); fixture.awaitProbe(); fixture.sendReady(); fixture.awaitInitialFrames()
            fixture.audio.emit('C')
            assertEquals(1, fixture.levels.size)
            assertEquals(90, fixture.levels[0].level()) // 0x4343 的同流 RMS。
            fixture.session.stopInput(); fixture.session.stopInput(); fixture.awaitFinished()
            assertEquals(listOf("start", "A", "B", "C", "T", "eof"), fixture.received)
            assertEquals(1, fixture.levels.size)
            fixture.assertNormalCompletion()
        }
    }

    @Test fun finiteSourceFinishingDuringStartStillConnectsAndFlushesBeforeEof() {
        Fixture(true, true).use { fixture ->
            fixture.start(); fixture.awaitProbe()
            assertEquals(1, fixture.audio.stops)
            assertEquals(listOf("start"), fixture.received)
            fixture.sendReady(); fixture.awaitFinished()
            assertEquals(listOf("start", "A", "B", "T", "eof"), fixture.received)
            fixture.assertNormalCompletion()
        }
    }

    private class Fixture(stopTail: Boolean, finiteDuringStart: Boolean,
        recording: RecordingSink? = null) : AutoCloseable {
        val server = MockWebServer()
        val audio = FakeAudio(stopTail, finiteDuringStart)
        val received: MutableList<String> = Collections.synchronizedList(ArrayList())
        val delivered: MutableList<String> = Collections.synchronizedList(ArrayList())
        val levels: MutableList<AudioInputLevel> = Collections.synchronizedList(ArrayList())
        val audioClock = AtomicLong(100)
        val peer = AtomicReference<WebSocket>()
        val error = AtomicReference<String>()
        val peerFailure = AtomicReference<Throwable>()
        val probe = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val initialFrames = CountDownLatch(2)
        val eofReceived = CountDownLatch(1)
        val finished = CountDownLatch(1)
        @Volatile var autoFinishOnEof = true
        val session: AndroidModelRuntime.WebSocketModelSession

        init {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { peer.set(webSocket) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                    if (text == "start") webSocket.send("probe")
                    if (text == "eof") {
                        eofReceived.countDown()
                        if (autoFinishOnEof) webSocket.send("final")
                    }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // 音源交付后覆写原数组，模型仍必须拿到完整的原始标记帧。
                    val actual = bytes.toByteArray()
                    val intact = actual.size == 3200 && actual.all { it == actual[0] }
                    if (!intact || actual[0] == 0.toByte()) {
                        peerFailure.compareAndSet(null, AssertionError("排队帧被复用或截断"))
                    }
                    received.add(actual[0].toInt().toChar().toString())
                    if (actual[0] == 'A'.code.toByte() || actual[0] == 'B'.code.toByte()) initialFrames.countDown()
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    peerFailure.compareAndSet(null, t)
                }
            }))
            server.start()
            val adapter = object : ModelAdapter {
                override fun id() = "sequence-test"
                override fun startMessage(grant: ModelSessionGrant) = "start"
                override fun stopMessage(grant: ModelSessionGrant) = "eof"
                override fun onServerMessage(text: String, listener: TranscriptListener): ModelSignal =
                    onServerMessage(text, listener, TranslationListener {}, SegmentStartedListener {})
                override fun onServerMessage(text: String, transcripts: TranscriptListener,
                    translations: TranslationListener, segments: SegmentStartedListener): ModelSignal {
                    if (text == "probe") { probe.countDown(); return ModelSignal.NONE }
                    if (text == "ready") { ready.countDown(); return ModelSignal.AUDIO_READY }
                    if (text == "model-failure") throw IllegalStateException("controlled-provider-failure")
                    if (text == "final") {
                        segments.onSegmentStarted(SegmentStartedEvent("segment", 0))
                        transcripts.onTranscript(TranscriptEvent("segment", 1, 0, 200, "zh-CN", "最后字幕", true))
                        translations.onTranslation(TranslationEvent("translation", "segment", 1,
                            "zh-CN", "en-US", "Final translation", true))
                        return ModelSignal.FINISHED
                    }
                    return ModelSignal.NONE
                }
            }
            session = AndroidModelRuntime.WebSocketModelSession(
                ModelSessionGrant("test-meeting", adapter.id(), server.url("/model").toString(),
                    "2030-01-01T00:01:00Z", null, emptyMap()),
                adapter, { delivered.add("transcript") }, object : ModelSessionListener {
                    override fun onAudioInputMode(mode: AudioInputMode) {
                        assertEquals(AudioInputMode.CZUR_BROKER, mode); delivered.add("mode")
                    }
                    override fun onAudioLevel(level: AudioInputLevel) { levels.add(level) }
                    override fun onAudioDirection(sample: AudioDirectionSample) {
                        assertEquals(AudioDirectionSample.Status.AVAILABLE, sample.status()); delivered.add("direction")
                    }
                    override fun onSegmentStarted(event: SegmentStartedEvent) { delivered.add("begin") }
                    override fun onTranslation(event: TranslationEvent) { delivered.add("translation") }
                    override fun onFinished() { delivered.add("finished"); finished.countDown() }
                    override fun onError(message: String) { error.set(message); finished.countDown() }
                }, audio, { audioClock.getAndAdd(100) }, recording)
            audio.session = session
            recording?.session = session
        }

        fun start() { session.start(); session.start(); assertEquals(1, audio.starts) }
        fun awaitProbe() = await(probe)
        fun awaitInitialFrames() = await(initialFrames)
        fun sendReady() { assertTrue(peer.get().send("ready")); await(ready) }
        fun awaitFinished() { await(finished); assertNull(error.get()); assertNull(peerFailure.get()) }
        fun assertNormalCompletion() {
            assertEquals(listOf("mode", "direction", "begin", "transcript", "translation", "finished"), delivered)
            assertEquals(1, audio.starts)
            assertEquals(1, audio.stops)
            assertEquals((received.size - 2) * 100L, session.frozenAudioDurationMs().asLong)
            assertFalse("采音/停流不得持有会话状态锁", audio.calledWhileSessionLocked)
            assertFalse("初始化回调中停止必须延后到开流返回", audio.stoppedWhileStarting)
        }
        override fun close() { session.close(); server.shutdown() }
    }

    private class RecordingSink : AudioArtifactSink {
        val markers = ArrayList<String>()
        lateinit var session: AndroidModelRuntime.WebSocketModelSession
        var seals = 0
        var cancels = 0
        var bytes = 0L
        var failSeal = false
        var failWrite = false
        var calledWhileSessionLocked = false
        var beforeSeal = Runnable {}
        override fun write(pcm: ByteArray) {
            calledWhileSessionLocked = calledWhileSessionLocked || Thread.holdsLock(session)
            if (failWrite) throw IOException("fixture-file-write-failed")
            for (value in pcm) assertEquals(pcm[0], value)
            markers.add(pcm[0].toInt().toChar().toString())
            bytes += pcm.size
        }
        override fun seal(capturedPcmBytes: Long): SealedAudioArtifact {
            calledWhileSessionLocked = calledWhileSessionLocked || Thread.holdsLock(session)
            beforeSeal.run(); seals++
            assertEquals(bytes, capturedPcmBytes)
            if (failSeal) throw IOException("fixture-seal-failed")
            return SealedAudioArtifact("test-meeting", File("fixture.pcm"), SealedAudioArtifact.Format.PCM16,
                bytes, capturedPcmBytes)
        }
        override fun cancel() { cancels++ }
    }

    private class FakeAudio(private val stopTail: Boolean, private val finiteDuringStart: Boolean) : PcmSource {
        private val active = AtomicBoolean()
        @Volatile lateinit var sink: MicPcmSource.FrameSink
        lateinit var session: AndroidModelRuntime.WebSocketModelSession
        var starts = 0
        var stops = 0
        var starting = false
        var calledWhileSessionLocked = false
        var stoppedWhileStarting = false
        override fun start(sink: MicPcmSource.FrameSink) {
            starts++
            calledWhileSessionLocked = calledWhileSessionLocked || Thread.holdsLock(session)
            this.sink = sink; starting = true; active.set(true)
            sink.onAudioInputMode(AudioInputMode.CZUR_BROKER)
            sink.onAudioDirection(AudioDirectionSample(45f, 45f, 100, AudioDirectionSample.Status.AVAILABLE))
            emit('A'); emit('B')
            if (finiteDuringStart) sink.onInputFinished()
            starting = false
        }
        fun emit(marker: Char) {
            val pcm = ByteArray(3200) { marker.code.toByte() }
            sink.onFrame(pcm); Arrays.fill(pcm, 0.toByte())
        }
        override fun stop() {
            if (!active.compareAndSet(true, false)) return
            stops++
            calledWhileSessionLocked = calledWhileSessionLocked || Thread.holdsLock(session)
            stoppedWhileStarting = stoppedWhileStarting || starting
            if (stopTail) emit('T')
        }
        override fun label() = "CONTROLLED_AUDIO"
    }

    companion object {
        private fun await(latch: CountDownLatch) {
            try { assertTrue("正常环回时序应在 5 秒内到达", latch.await(5, TimeUnit.SECONDS)) }
            catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt(); throw AssertionError(interrupted)
            }
        }
    }
}
