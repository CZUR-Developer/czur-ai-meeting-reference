package com.czur.aimeeting.sdk

import com.czur.aimeeting.client.provider.TingwuModelAdapter
import com.czur.aimeeting.model.ModelSessionConnection
import com.czur.aimeeting.model.ModelSessionGrant
import com.czur.aimeeting.model.ModelSessionListener
import com.czur.aimeeting.model.ModelSessionReconnector
import com.czur.aimeeting.model.SealedAudioArtifact
import com.czur.aimeeting.model.SegmentStartedEvent
import com.czur.aimeeting.model.ModelHttpRequest
import com.czur.aimeeting.model.ModelAdapter
import com.czur.aimeeting.model.TranscriptEvent
import com.czur.aimeeting.model.TranslationEvent
import java.io.File
import java.io.IOException
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 真听悟 Adapter 加可控音源/连接，验证同场续接，不接外网或设备。 */
class AndroidModelRuntimeReconnectTest {
    @Test fun realtimeHandshakeUsesAdapterHeadersInsteadOfInjectingBearer() {
        val adapter = object : ModelAdapter by TingwuModelAdapter() {
            override fun connectionRequest(grant: ModelSessionGrant) =
                ModelHttpRequest("GET", mapOf("x-api-key" to "fixture-ws-token", "api-version" to "v2"))
        }
        Fixture(adapter).use { f ->
            val socket = f.start()
            assertEquals("fixture-ws-token", socket.request().header("x-api-key"))
            assertEquals("v2", socket.request().header("api-version"))
            assertNull(socket.request().header("Authorization"))
        }
    }

    @Test fun reconnectKeepsOneInputAndRecordingAndSeparatesTaskIdsAndTime() {
        Fixture().use { f ->
            val first = f.start(); first.ready(); f.audio.emit('A'.code)
            first.message("SentenceBegin", JSONObject().put("index", 1).put("time", 100))
            first.message("SentenceEnd", source("Confirmed first.", 100, 800))
            assertEquals(0, f.finalTranscripts().size)
            first.disconnect()
            assertEquals("Confirmed first.", f.finalTranscripts()[0].text())
            val second = f.sockets.take()
            f.audio.emit('B'.code); second.open(); second.ready()
            assertEquals(1, second.binary.size); assertEquals('B'.code, second.binary[0][0].toInt())
            second.message("SentenceBegin", JSONObject().put("index", 1).put("time", 50))
            second.message("SentenceEnd", source("Confirmed next.", 50, 500))
            second.message("TranslationResultChanged", JSONObject().put("source_lang", "multilingual")
                .put("target_lang", "CN").put("translate_result", JSONArray().put(JSONObject().put("index", 1)
                    .put("text", "下一句确认。").put("partial", false).put("detected_source_lang", "EN"))))
            val next = f.finalTranscripts()[1]
            assertEquals("reconnect-1:tingwu-1", next.segmentId())
            assertEquals(1050L, next.startMs()); assertEquals(1500L, next.endMs())
            assertEquals("reconnect-1:0", next.sessionSpeakerId())
            assertEquals("tingwu-1", f.finalTranscripts()[0].segmentId())
            assertEquals("0", f.finalTranscripts()[0].sessionSpeakerId())
            assertEquals(1050L, f.begins[1].startMs()); assertEquals(next.segmentId(), f.begins[1].segmentId())
            assertEquals("reconnect-1:tingwu-translation-1-zh-CN", f.translations[0].translationId())
            assertEquals(next.segmentId(), f.translations[0].sourceSegmentId())
            val events = f.transcripts.size
            first.message("SentenceEnd", source("Late old task.", 0, 999))
            first.message("TranscriptionCompleted", JSONObject()); first.disconnect()
            assertEquals("旧任务回调不能覆盖新任务或结束同一会议", events, f.transcripts.size)
            assertEquals(0, f.finished.get()); assertTrue(f.errors.isEmpty())
            f.audio.emit('C'.code); f.session.stopInput()
            assertEquals(3000L, f.session.frozenAudioDurationMs().asLong)
            assertEquals(3000L, f.session.sealedAudioArtifact().orElseThrow().durationMs())
            second.complete()
            assertEquals(1, f.finished.get())
            assertEquals(listOf('A'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte()), f.recording.markers)
            assertEquals(1, f.audio.starts); assertEquals(1, f.audio.stops)
            assertEquals(1, f.recording.seals); assertEquals(2, f.sockets.total.get())
        }
    }
    @Test fun rejectedSendKeepsOriginalFrameWithoutDoubleRecordingOrUsage() {
        Fixture().use { f ->
            val first = f.start(); first.ready(); first.rejectNextBinary = true; f.audio.emit('A'.code)
            val second = f.sockets.take(); second.open(); second.ready()
            assertEquals(0, first.binary.size); assertEquals(1, second.binary.size)
            assertEquals('A'.code, second.binary[0][0].toInt())
            f.session.stopInput(); second.complete()
            assertEquals(listOf('A'.code.toByte()), f.recording.markers)
            assertEquals(1000L, f.session.frozenAudioDurationMs().asLong)
            assertEquals(1000L, f.session.sealedAudioArtifact().orElseThrow().durationMs())
            assertEquals(1, f.audio.starts); assertEquals(1, f.audio.stops); assertTrue(f.errors.isEmpty())
        }
    }
    @Test fun unavailableMaterialsRetryWithoutRestartingInput() {
        Fixture().use { f ->
            val attempts = AtomicInteger()
            f.nextMaterials = ModelSessionReconnector {
                if (attempts.incrementAndGet() == 1) throw IOException("controlled-temporary-offline")
                ModelSessionConnection(grant(1), TingwuModelAdapter())
            }
            val first = f.start(); first.ready(); f.audio.emit('A'.code); first.disconnect(); f.audio.emit('B'.code)
            val second = f.sockets.take()
            assertEquals(2, attempts.get()); assertEquals(1, f.audio.starts); assertEquals(0, f.audio.stops)
            second.open(); second.ready(); f.session.stopInput(); second.complete()
            assertEquals(2000L, f.session.sealedAudioArtifact().orElseThrow().durationMs())
            assertEquals(listOf('A'.code.toByte(), 'B'.code.toByte()), f.recording.markers)
            assertTrue(f.errors.isEmpty())
        }
    }
    @Test fun fiveMinuteNetworkFifoDropsOnlyOldPendingAudioAndKeepsWholeRecording() {
        Fixture().use { f ->
            val socket = f.start()
            repeat(301) { f.audio.emit(it % 100 + 1); f.recording.awaitFrames(it + 1) }
            assertTrue(socket.binary.isEmpty()); socket.ready()
            assertEquals(300, socket.binary.size); assertEquals(2, socket.binary[0][0].toInt())
            socket.message("SentenceEnd", source("Still the original recording.", 0, 100))
            f.session.stopInput(); socket.complete()
            assertEquals(1000L, f.finalTranscripts()[0].startMs()); assertEquals(1100L, f.finalTranscripts()[0].endMs())
            assertEquals(301, f.recording.markers.size)
            assertEquals(301_000L, f.session.frozenAudioDurationMs().asLong)
            assertEquals(301_000L, f.session.sealedAudioArtifact().orElseThrow().durationMs()); assertTrue(f.errors.isEmpty())
        }
    }
    @Test fun stopDuringUninterruptibleMaterialRequestSealsWithoutFinishedOrLateSocket() {
        val requesting = CountDownLatch(1); val allowMaterials = CountDownLatch(1); val materialsReturned = CountDownLatch(1)
        val stopFailure = AtomicReference<Throwable>()
        Fixture().use { f ->
            f.nextMaterials = ModelSessionReconnector {
                requesting.countDown(); awaitIgnoringInterrupt(allowMaterials); materialsReturned.countDown()
                ModelSessionConnection(grant(1), TingwuModelAdapter())
            }
            val first = f.start(); first.ready(); f.audio.emit('A'.code)
            first.message("SentenceEnd", source("Confirmed before disconnect.", 100, 800)); first.disconnect()
            await(requesting); f.audio.emit('B'.code)
            val stop = Thread({ try { f.session.stopInput() } catch (failure: Throwable) { stopFailure.set(failure) } },
                "test-stop-with-blocked-materials")
            try {
                stop.start(); stop.join(3000)
                assertFalse("停源/封口不得等待材料HTTP", stop.isAlive); assertNull(stopFailure.get())
                assertEquals(1, f.audio.stops); assertEquals(2000L, f.session.frozenAudioDurationMs().asLong)
                assertEquals(2000L, f.session.sealedAudioArtifact().orElseThrow().durationMs())
                assertEquals(1, f.errors.size); assertEquals(0, f.finished.get())
                assertEquals("Confirmed before disconnect.", f.finalTranscripts()[0].text())
                allowMaterials.countDown(); await(materialsReturned)
                assertNull("Stop后迟到材料不能开启第二个模型连接", f.sockets.pending.poll(150, TimeUnit.MILLISECONDS))
                assertEquals(1, f.sockets.total.get()); assertEquals(1, f.recording.seals)
                f.audio.emit('L'.code); assertEquals(2000L, f.session.frozenAudioDurationMs().asLong)
            } finally { allowMaterials.countDown(); stop.join(3000) }
        }
    }

    private class Fixture(initialAdapter: ModelAdapter = TingwuModelAdapter()) : AutoCloseable {
        val audio = FakeAudio(); val recording = RecordingSink(); val sockets = FakeSockets()
        val transcripts: MutableList<TranscriptEvent> = Collections.synchronizedList(ArrayList())
        val translations: MutableList<TranslationEvent> = Collections.synchronizedList(ArrayList())
        val begins: MutableList<SegmentStartedEvent> = Collections.synchronizedList(ArrayList())
        val errors: MutableList<String> = Collections.synchronizedList(ArrayList())
        val finished = AtomicInteger()
        @Volatile var nextMaterials = ModelSessionReconnector { ModelSessionConnection(grant(1), TingwuModelAdapter()) }
        val session: AndroidModelRuntime.WebSocketModelSession
        init {
            session = AndroidModelRuntime.WebSocketModelSession(grant(0), initialAdapter,
                { event -> assertFalse(Thread.holdsLock(sessionRef())); transcripts.add(event) },
                object : ModelSessionListener {
                    override fun onTranslation(event: TranslationEvent) { translations.add(event) }
                    override fun onSegmentStarted(event: SegmentStartedEvent) { begins.add(event) }
                    override fun onFinished() { finished.incrementAndGet() }
                    override fun onError(message: String) { errors.add(message) }
                }, audio, { 1000L }, recording, ::requestMaterials, sockets)
            audio.session = session; recording.session = session
        }
        private fun sessionRef() = session
        private fun requestMaterials(): ModelSessionConnection {
            assertFalse("续领HTTP不能持有会话锁", Thread.holdsLock(session))
            return nextMaterials.request()
        }
        fun start(): FakeSocket { session.start(); return sockets.take().also { it.open() } }
        fun finalTranscripts(): List<TranscriptEvent> = synchronized(transcripts) { transcripts.filter { it.isFinal() } }
        override fun close() = session.close()
    }
    private class FakeAudio : PcmSource {
        lateinit var sink: MicPcmSource.FrameSink
        lateinit var session: AndroidModelRuntime.WebSocketModelSession
        var starts = 0; var stops = 0
        override fun start(sink: MicPcmSource.FrameSink) {
            assertFalse(Thread.holdsLock(session)); this.sink = sink; starts++
        }
        override fun stop() { assertFalse("真实停源必须在会话锁外", Thread.holdsLock(session)); stops++ }
        override fun label(): String = "controlled-shared-stream"
        fun emit(marker: Int) {
            val pcm = ByteArray(32_000) { marker.toByte() }
            sink.onFrame(pcm); Arrays.fill(pcm, 0.toByte())
        }
    }
    private class RecordingSink : AudioArtifactSink {
        lateinit var session: AndroidModelRuntime.WebSocketModelSession
        val markers = ArrayList<Byte>()
        var bytes = 0L; var seals = 0
        private val monitor = Object()
        override fun write(pcm: ByteArray) = synchronized(monitor) {
            assertFalse(Thread.holdsLock(session)); markers.add(pcm[0]); bytes += pcm.size; monitor.notifyAll()
        }
        override fun seal(capturedPcmBytes: Long): SealedAudioArtifact = synchronized(monitor) {
            assertFalse(Thread.holdsLock(session)); assertEquals(bytes, capturedPcmBytes); seals++
            SealedAudioArtifact("same-meeting", File("fixture-same-meeting.pcm"),
                SealedAudioArtifact.Format.PCM16, bytes, capturedPcmBytes)
        }
        override fun cancel() {}
        fun awaitFrames(count: Int) = synchronized(monitor) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (markers.size < count && System.nanoTime() < deadline) monitor.wait(10)
            assertEquals(count, markers.size)
        }
    }
    private class FakeSockets : WebSocket.Factory {
        val pending = LinkedBlockingQueue<FakeSocket>(); val total = AtomicInteger()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeSocket(request, listener).also { total.incrementAndGet(); pending.add(it) }
        fun take(): FakeSocket {
            val socket = pending.poll(3, TimeUnit.SECONDS)
            assertNotNull("模型连接应在3秒内建立", socket)
            return socket!!
        }
    }
    private class FakeSocket(private val request: Request, private val listener: WebSocketListener) : WebSocket {
        val binary: MutableList<ByteString> = Collections.synchronizedList(ArrayList())
        val text: MutableList<String> = Collections.synchronizedList(ArrayList())
        var rejectNextBinary = false
        override fun request(): Request = request
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { this.text.add(text); return true }
        override fun send(bytes: ByteString): Boolean {
            if (rejectNextBinary) { rejectNextBinary = false; return false }
            binary.add(bytes); return true
        }
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
        fun open() {
            // Kotlin OkHttp 回调要求合法非空握手响应；旧替身传 null 会触发参数契约检查。
            listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(101).message("Switching Protocols").build())
        }
        fun ready() = message("TranscriptionStarted", JSONObject())
        fun complete() = message("TranscriptionCompleted", JSONObject())
        fun message(event: String, payload: JSONObject) = listener.onMessage(this,
            JSONObject().put("header", JSONObject().put("name", event)).put("payload", payload).toString())
        fun disconnect() = listener.onFailure(this, IOException("controlled-network-loss"), null)
    }
    private companion object {
        fun source(text: String, start: Long, end: Long): JSONObject = JSONObject().put("index", 1)
            .put("result", text).put("begin_time", start).put("time", end).put("speaker_id", "0")
        fun grant(task: Int) = ModelSessionGrant("same-meeting", TingwuModelAdapter.ID,
            "wss://example.test/model/$task", "2030-01-01T00:00:00Z", null,
            mapOf("taskId" to "task-$task", "translationEnabled" to "true"))
        fun await(latch: CountDownLatch) { assertTrue("受控步骤应在3秒内完成", latch.await(3, TimeUnit.SECONDS)) }
        fun awaitIgnoringInterrupt(latch: CountDownLatch) {
            var interrupted = false
            while (true) try { latch.await(); break } catch (ignored: InterruptedException) { interrupted = true }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
