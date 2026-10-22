package com.czur.aimeeting.sdk

import android.os.SystemClock
import com.czur.aimeeting.model.AudioInputErrorCode
import com.czur.aimeeting.model.AudioInputMode
import com.czur.aimeeting.model.ModelAdapter
import com.czur.aimeeting.model.ModelSessionGrant
import com.czur.aimeeting.model.ModelSessionListener
import com.czur.aimeeting.model.ModelSignal
import com.czur.aimeeting.model.SealedAudioArtifact
import com.czur.aimeeting.model.TranscriptEvent
import com.czur.aimeeting.model.TranscriptListener
import com.czur.aimeeting.model.TranslationEvent
import com.czur.aimeeting.model.TranslationListener
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** 用真实会话与可控音源复现并发窗口；仅网络与 Android 平台日志使用测试替身。 */
class AndroidModelRuntimeCallbackOrderTest {
    @Test fun modelErrorDuringArtifactSealWaitsForReadableSnapshot() {
        val sealEntered = CountDownLatch(1)
        val allowSeal = CountDownLatch(1)
        val delivered: MutableList<String> = Collections.synchronizedList(ArrayList())
        val failure = AtomicReference<Throwable>()
        val active = AtomicReference<AndroidModelRuntime.WebSocketModelSession>()
        val audio = FakeAudio {}.apply { emitStopTail = true }
        val recording = RecordingSink { sealEntered.countDown(); await(allowSeal) }
        val session = session(audio, { delivered.add("transcript") }, object : ModelSessionListener {
            override fun onFinished() { throw AssertionError("模型失败不应FINISHED") }
            override fun onError(message: String) {
                assertEquals(1000L, active.get().sealedAudioArtifact().orElseThrow().durationMs())
                delivered.add("error")
            }
        }, recording)
        active.set(session)
        session.onFrame(ByteArray(28_800))
        val stop = worker("test-stop-before-model-error", failure, session::stopInput)
        try {
            stop.start()
            await(sealEntered)
            session.handleServerMessage("model-failure")
            assertEquals(listOf("transcript"), delivered)
            assertFalse(session.sealedAudioArtifact().isPresent)
            allowSeal.countDown()
            stop.join(3000)
            assertFalse(stop.isAlive)
            assertNull(failure.get())
            assertEquals(listOf("transcript", "error"), delivered)
            assertEquals(1, recording.seals)
            assertEquals(0, recording.cancels)
            assertEquals(1, audio.stops)
        } finally {
            allowSeal.countDown(); stop.join(3000); session.close()
        }
    }

    @Test fun explicitCloseDuringModelFailureSealSuppressesSnapshotAndErrorNotification() {
        val sealEntered = CountDownLatch(1)
        val allowSeal = CountDownLatch(1)
        val delivered: MutableList<String> = Collections.synchronizedList(ArrayList())
        val failure = AtomicReference<Throwable>()
        val audio = FakeAudio {}
        val recording = RecordingSink { sealEntered.countDown(); await(allowSeal) }
        val session = session(audio, { delivered.add("transcript") }, object : ModelSessionListener {
            override fun onFinished() { delivered.add("finished") }
            override fun onError(message: String) { delivered.add("error") }
        }, recording)
        session.onFrame(ByteArray(3200))
        val websocket = worker("test-model-error-and-close", failure) { session.handleServerMessage("model-failure") }
        try {
            websocket.start(); await(sealEntered); session.close(); allowSeal.countDown(); websocket.join(3000)
            assertFalse(websocket.isAlive)
            assertNull(failure.get())
            assertEquals(listOf("transcript"), delivered)
            assertFalse(session.sealedAudioArtifact().isPresent)
            assertEquals(1, audio.stops)
            assertEquals(1, recording.seals)
        } finally {
            allowSeal.countDown(); websocket.join(3000); session.close()
        }
    }

    @Test fun finishedDuringArtifactSealWaitsForQueueDrainWithoutHoldingSessionLock() {
        val sealEntered = CountDownLatch(1)
        val allowSeal = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val audio = FakeAudio {}.apply { emitStopTail = true }
        val recording = RecordingSink { sealEntered.countDown(); await(allowSeal) }
        val session = session(audio, {}, object : ModelSessionListener {
            override fun onFinished() { finished.countDown() }
            override fun onError(message: String) { throw AssertionError(message) }
        }, recording)
        session.onFrame(ByteArray(28_800))
        val stop = worker("test-stop-and-seal", failure, session::stopInput)
        try {
            stop.start(); await(sealEntered)
            assertEquals(1000L, session.frozenAudioDurationMs().asLong)
            assertFalse(session.sealedAudioArtifact().isPresent)
            session.handleServerMessage("finished")
            assertEquals("FINISHED不能越过封口完成点", 1L, finished.count)
            allowSeal.countDown(); stop.join(3000)
            assertFalse(stop.isAlive)
            assertNull(failure.get())
            await(finished)
            assertEquals(1000L, session.sealedAudioArtifact().orElseThrow().durationMs())
            assertEquals(1, recording.seals)
            assertFalse(recording.calledWhileSessionLocked)
            assertEquals(1, audio.stops)
        } finally {
            allowSeal.countDown(); stop.join(3000); session.close()
        }
    }

    @Test fun inFlightTranscriptIsEnqueuedBeforeAudioFailureCompletesMeeting() {
        val transcriptEntered = CountDownLatch(1)
        val allowTranscriptAppend = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val stopInputReturned = CountDownLatch(1)
        val meetingCompleted = CountDownLatch(1)
        val saved: MutableList<String> = Collections.synchronizedList(ArrayList())
        val stopSent = AtomicBoolean()
        val audioFailed = AtomicBoolean()
        val completionQueued = AtomicBoolean()
        val network = Executors.newSingleThreadExecutor()
        val threadFailure = AtomicReference<Throwable>()
        val audio = FakeAudio { cleanupEntered.countDown(); await(allowCleanup) }.apply { emitStopTail = true }
        val recording = RecordingSink {}
        val complete = Runnable {
            if (completionQueued.compareAndSet(false, true)) network.execute {
                saved.add("stop"); meetingCompleted.countDown()
            }
        }
        val session = session(audio, {
            transcriptEntered.countDown(); await(allowTranscriptAppend)
            // 对应 MeetingService：标准事件回调返回前，先加入单线程网络提交队列。
            network.execute { saved.add("append") }
        }, object : ModelSessionListener {
            override fun onAudioError(code: AudioInputErrorCode, message: String) {
                assertEquals(AudioInputErrorCode.READ_FAILED, code)
                audioFailed.set(true)
                if (stopSent.get()) complete.run()
            }
            override fun onFinished() { throw AssertionError("失败场次不应 FINISHED") }
            override fun onError(message: String) { throw AssertionError(message) }
        }, recording)
        session.handleServerMessage("ready")
        val websocket = worker("test-ws", threadFailure) { session.handleServerMessage("transcript") }
        val capture = worker("test-pcm", threadFailure) { audio.sink.onCaptureError("受控音频读取错误") }
        try {
            websocket.start(); await(transcriptEntered); capture.start(); await(cleanupEntered)
            network.execute {
                // 对应用户 Stop 已进入网络队列，closed=true 使 stopInput 提前返回。
                session.stopInput(); stopSent.set(true)
                if (audioFailed.get()) complete.run()
                stopInputReturned.countDown()
            }
            await(stopInputReturned); allowCleanup.countDown(); capture.join(3000)
            assertFalse("音频错误通知不能阻塞音频清理线程", capture.isAlive)
            assertFalse("字幕尚未入队，音频错误不能抢先完成会议", audioFailed.get())
            assertFalse(completionQueued.get())
            assertTrue(saved.isEmpty())

            allowTranscriptAppend.countDown(); websocket.join(3000)
            assertFalse(websocket.isAlive)
            await(meetingCompleted)
            assertEquals(listOf("append", "stop"), saved)
            assertEquals(1, audio.stops)
            assertEquals(3200L, recording.bytes)
            assertEquals(0, recording.seals)
            assertEquals(1, recording.cancels)
            assertFalse(session.sealedAudioArtifact().isPresent)
            assertNull(threadFailure.get())
        } finally {
            allowCleanup.countDown(); allowTranscriptAppend.countDown()
            websocket.join(3000); capture.join(3000); network.shutdownNow(); session.close()
        }
    }

    @Test fun normalFinishedFollowsTranscriptAndTranslationAndRejectsLateError() {
        val delivered = ArrayList<String>()
        val audio = FakeAudio {}.apply { emitStopTail = true }
        val recording = RecordingSink {}
        val active = AtomicReference<AndroidModelRuntime.WebSocketModelSession>()
        val session = session(audio, { delivered.add("transcript") }, object : ModelSessionListener {
            override fun onTranslation(event: TranslationEvent) { delivered.add("translation") }
            override fun onFinished() {
                assertEquals(1000L, active.get().frozenAudioDurationMs().asLong)
                delivered.add("finished")
            }
            override fun onError(message: String) { delivered.add("error") }
        }, recording)
        active.set(session)
        session.onFrame(ByteArray(28_800)); session.handleServerMessage("ready")
        session.handleServerMessage("finished"); session.onCaptureError("迟到的错误")
        session.handleServerMessage("transcript")
        assertEquals(listOf("transcript", "translation", "finished"), delivered)
        assertEquals(1, audio.stops)
        assertEquals(1000L, session.frozenAudioDurationMs().asLong)
        assertEquals(32000L, recording.bytes)
        assertEquals(1, recording.seals)
        assertEquals(1000L, session.sealedAudioArtifact().orElseThrow().durationMs())
        assertFalse("音频 stop 必须位于会话状态锁外", audio.stoppedWhileSessionLocked)
    }

    @Test fun closeInsideTranscriptCallbackCancelsRemainingNotificationsWithoutLockingAudio() {
        val delivered = ArrayList<String>()
        val audio = FakeAudio {}.apply { emitStopTail = true }
        val recording = RecordingSink {}
        val active = AtomicReference<AndroidModelRuntime.WebSocketModelSession>()
        val session = session(audio, {
            delivered.add("transcript")
            // 重入时先接纳一个尚未派发的音源通知，再取消；close 必须清掉它。
            active.get().onAudioInputMode(AudioInputMode.CZUR_BROKER)
            active.get().close()
        }, object : ModelSessionListener {
            override fun onAudioInputMode(mode: AudioInputMode) { delivered.add("mode") }
            override fun onTranslation(event: TranslationEvent) { delivered.add("translation") }
            override fun onFinished() { delivered.add("finished") }
            override fun onError(message: String) { delivered.add("error") }
        }, recording)
        active.set(session)
        session.handleServerMessage("ready"); session.handleServerMessage("finished")
        session.onCaptureError("关闭之后的错误")
        assertEquals(listOf("transcript"), delivered)
        assertEquals(1, audio.stops)
        assertEquals(100L, session.frozenAudioDurationMs().asLong)
        assertEquals(3200L, recording.bytes)
        assertEquals(0, recording.seals)
        assertEquals(1, recording.cancels)
        assertFalse(session.sealedAudioArtifact().isPresent)
        assertFalse(recording.calledWhileSessionLocked)
        assertFalse("回调内 close 不得持会话状态锁执行音频 stop", audio.stoppedWhileSessionLocked)
    }

    @Test fun finishedDuringUserStopWaitsForCompleteCleanupTailBeforeNotification() {
        val cleanupEntered = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val delivered: MutableList<String> = Collections.synchronizedList(ArrayList())
        val failure = AtomicReference<Throwable>()
        val audio = FakeAudio { cleanupEntered.countDown(); await(allowCleanup) }.apply { emitStopTail = true }
        val recording = RecordingSink {}
        val session = session(audio, { delivered.add("transcript") }, object : ModelSessionListener {
            override fun onTranslation(event: TranslationEvent) { delivered.add("translation") }
            override fun onFinished() { delivered.add("finished") }
            override fun onError(message: String) { throw AssertionError(message) }
        }, recording)
        session.onFrame(ByteArray(28_800))
        val stop = worker("test-user-stop", failure, session::stopInput)
        try {
            stop.start(); await(cleanupEntered); session.handleServerMessage("finished")
            assertEquals(listOf("transcript", "translation"), delivered)
            assertFalse("采集尾帧未交付，尚不能冻结用量", session.frozenAudioDurationMs().isPresent)
            allowCleanup.countDown(); stop.join(3000)
            assertFalse(stop.isAlive)
            assertNull(failure.get())
            assertEquals(listOf("transcript", "translation", "finished"), delivered)
            assertEquals(1000L, session.frozenAudioDurationMs().asLong)
            assertEquals(1000L, session.sealedAudioArtifact().orElseThrow().durationMs())
            assertEquals(1, recording.seals)
            assertEquals(1, audio.stops)
            assertFalse(audio.stoppedWhileSessionLocked)
        } finally {
            allowCleanup.countDown(); stop.join(3000); session.close()
        }
    }

    private fun session(audio: FakeAudio, transcript: TranscriptListener,
        listener: ModelSessionListener, recording: RecordingSink? = null): AndroidModelRuntime.WebSocketModelSession {
        val adapter = object : ModelAdapter {
            override fun id() = "controlled-test"
            override fun startMessage(grant: ModelSessionGrant) = "start"
            override fun onServerMessage(text: String, listener: TranscriptListener): ModelSignal =
                onServerMessage(text, listener, TranslationListener {})
            override fun onServerMessage(text: String, listener: TranscriptListener,
                translations: TranslationListener): ModelSignal {
                if (text == "ready") return ModelSignal.AUDIO_READY
                listener.onTranscript(TranscriptEvent("segment", 1, 0, 100, "zh-CN", "受控字幕", true))
                if (text == "model-failure") throw IllegalStateException("controlled-provider-failure")
                if (text == "finished") {
                    translations.onTranslation(TranslationEvent("translation", "segment", 1,
                        "zh-CN", "en-US", "Controlled transcript", true))
                    return ModelSignal.FINISHED
                }
                return ModelSignal.NONE
            }
        }
        val session = AndroidModelRuntime.WebSocketModelSession(
            ModelSessionGrant("test-meeting", adapter.id(), "ws://localhost/unused", "2030-01-01T00:01:00Z", null, emptyMap()),
            adapter, transcript, listener, audio, SystemClock::elapsedRealtime, recording)
        audio.session = session
        recording?.session = session
        // 本组只控制回调/清理交错；真实开场采音及 READY 门由环回 WebSocket 测试覆盖。
        audio.start(session)
        return session
    }

    private class RecordingSink(private val beforeSeal: Runnable) : AudioArtifactSink {
        lateinit var session: AndroidModelRuntime.WebSocketModelSession
        var bytes = 0L
        var seals = 0
        var cancels = 0
        var calledWhileSessionLocked = false
        override fun write(pcm: ByteArray) {
            calledWhileSessionLocked = calledWhileSessionLocked || Thread.holdsLock(session)
            bytes += pcm.size
        }
        override fun seal(capturedPcmBytes: Long): SealedAudioArtifact {
            calledWhileSessionLocked = calledWhileSessionLocked || Thread.holdsLock(session)
            beforeSeal.run(); seals++
            assertEquals(bytes, capturedPcmBytes)
            return SealedAudioArtifact("test-meeting", File("fixture.pcm"), SealedAudioArtifact.Format.PCM16,
                capturedPcmBytes, capturedPcmBytes)
        }
        override fun cancel() { cancels++ }
    }

    private fun worker(name: String, failure: AtomicReference<Throwable>, action: Runnable): Thread = Thread({
        try { action.run() } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
    }, name)

    private fun await(latch: CountDownLatch) {
        try { assertTrue("受控交错应在 3 秒内到达", latch.await(3, TimeUnit.SECONDS)) }
        catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt(); throw AssertionError(interrupted)
        }
    }

    private class FakeAudio(private val onStop: Runnable) : PcmSource {
        lateinit var sink: MicPcmSource.FrameSink
        lateinit var session: AndroidModelRuntime.WebSocketModelSession
        var stops = 0
        var stoppedWhileSessionLocked = false
        var emitStopTail = false
        override fun start(sink: MicPcmSource.FrameSink) { this.sink = sink }
        override fun stop() {
            stops++
            stoppedWhileSessionLocked = stoppedWhileSessionLocked || Thread.holdsLock(session)
            onStop.run()
            if (emitStopTail) sink.onFrame(ByteArray(3200))
        }
        override fun label() = "CONTROLLED_AUDIO"
        override fun mode() = AudioInputMode.CZUR_BROKER
    }
}
