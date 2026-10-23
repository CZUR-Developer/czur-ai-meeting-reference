package com.czur.aimeeting.sdk

import com.czur.aimeeting.client.ClientIdentity
import com.czur.aimeeting.client.MeetingArtifactPublisher
import com.czur.aimeeting.client.MeetingCapabilityState
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingError
import com.czur.aimeeting.client.MeetingClientConfig
import com.czur.aimeeting.client.MeetingDiscovery
import com.czur.aimeeting.client.MeetingListener
import com.czur.aimeeting.client.MeetingOptions
import com.czur.aimeeting.client.MeetingProfile
import com.czur.aimeeting.client.MeetingResult
import com.czur.aimeeting.client.MeetingResultContent
import com.czur.aimeeting.client.MeetingSession
import com.czur.aimeeting.client.MeetingSessionState
import com.czur.aimeeting.client.MeetingStarted
import com.czur.aimeeting.client.ModelAdapterRegistry
import com.czur.aimeeting.client.StoppedMeetingCheckpoint
import com.czur.aimeeting.client.StoppedMeetingStore
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.client.server.ReferenceLoginSession
import com.czur.aimeeting.client.identity.IdentityProvider
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.identity.ReferenceIdentityProvider
import com.czur.aimeeting.model.AudioInputMode
import com.czur.aimeeting.model.ModelAdapter
import com.czur.aimeeting.model.ModelRuntime
import com.czur.aimeeting.model.ModelSessionGrant
import com.czur.aimeeting.model.ModelSessionListener
import com.czur.aimeeting.model.ModelSignal
import com.czur.aimeeting.model.SegmentStartedEvent
import com.czur.aimeeting.model.SegmentStartedListener
import com.czur.aimeeting.model.SealedAudioArtifact
import com.czur.aimeeting.model.SummaryRuntime
import com.czur.aimeeting.model.TranscriptEvent
import com.czur.aimeeting.model.TranscriptListener
import com.czur.aimeeting.model.TranslationListener
import java.io.IOException
import java.nio.file.Files
import java.util.Arrays
import java.util.Collections
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 高层入口 + 实际环回 HTTP/WebSocket + 可控完整 PCM；不代签 Android 音源或真实厂商。 */
class AiMeetingClientTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun typedLoginAndWholeMeetingPreserveTailCaptureSummaryAndReadyOrder() {
        val ui = Executors.newSingleThreadExecutor { Thread(it, "meeting-ui") }
        val callbackThreads: MutableList<String> = Collections.synchronizedList(ArrayList())
        try {
            Fixture().use { fixture ->
                val adapters = ModelAdapterRegistry()
                adapters.register("partner-string", ::PartnerAdapter, setOf(MeetingCapabilityState.TRANSCRIPT))
                // 页面与会议入口复用一个可注入来源，不经 Intent 复制 token。
                val identity = ReferenceIdentityProvider()
                fixture.client(identity, adapters, ui).use { beforeLogin ->
                    val initialIdentity = CompletableFuture<IdentityResult>()
                    beforeLogin.refreshIdentity(callback(initialIdentity, callbackThreads))
                    assertEquals(IdentityResult.Status.LOGIN_REQUIRED, initialIdentity.get(5, TimeUnit.SECONDS).status())
                    val discovery = CompletableFuture<MeetingDiscovery>()
                    beforeLogin.discover(callback(discovery, callbackThreads))
                    assertEquals(setOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SUMMARY),
                        discovery.get(5, TimeUnit.SECONDS).availableCapabilities())
                    val login = CompletableFuture<ReferenceLoginSession>()
                    beforeLogin.login("fixture-user", "fixture-password", callback(login, callbackThreads))
                    assertEquals("fixture-access", login.get(5, TimeUnit.SECONDS).accessToken())
                    val afterLogin = CompletableFuture<IdentityResult>()
                    beforeLogin.refreshIdentity(callback(afterLogin, callbackThreads))
                    assertEquals("fixture-user", afterLogin.get(5, TimeUnit.SECONDS).session()!!.displayName())
                }
                val ready = CountDownLatch(1)
                val notifications: MutableList<String> = Collections.synchronizedList(ArrayList())
                val session = AtomicReference<MeetingSession>()
                val result = AtomicReference<MeetingResult>()
                val display = AtomicReference<MeetingDisplaySnapshot>()
                val started = AtomicReference<MeetingStarted>()
                val terminal = AtomicReference<MeetingSessionState>()
                val captureConfirmations = AtomicInteger()
                val captureErrors = AtomicInteger()
                val stopContinuations = AtomicInteger()
                val audioStopsAtCaptureAck = AtomicInteger(-1)
                fixture.client(identity, adapters, ui).use { client ->
                    session.set(client.start(MeetingOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT,
                        "zh-CN", "en-US", true, MeetingOptions.ResultDelivery.LOCAL, "", emptyList(), true),
                        object : MeetingListener {
                            private fun thread() { callbackThreads.add(Thread.currentThread().name) }
                            override fun onStarted(value: MeetingStarted) { thread(); started.set(value) }
                            override fun onState(state: MeetingSessionState) {
                                thread(); notifications.add(state.phase().name)
                                if (state.phase() == MeetingSessionState.Phase.READY ||
                                    state.phase() == MeetingSessionState.Phase.FAILED ||
                                    state.phase() == MeetingSessionState.Phase.STOP_PENDING) {
                                    terminal.set(state); ready.countDown()
                                }
                            }
                            override fun onAudioInputMode(mode: AudioInputMode) { thread() }
                            override fun onTranscriptActivity(nowMs: Long) { thread() }
                            override fun onDisplay(value: MeetingDisplaySnapshot) { thread(); display.set(value) }
                            override fun beforeStop(continuation: Runnable) {
                                thread()
                                val jpeg = byteArrayOf(1, 2, 3)
                                // 同一既有整场用例等真实 HTTP 保存 ACK；void 只排队，不能证明末帧已保存。
                                // 成功和失败都续接正常 Stop，单帧失败不升级为文字会议失败。
                                session.get().submitPresentation(200, jpeg, object : MeetingClient.Callback<Void?> {
                                    override fun onSuccess(result: Void?) {
                                        thread(); captureConfirmations.incrementAndGet()
                                        audioStopsAtCaptureAck.set(fixture.audio.stops)
                                        notifications.add("capture-ack"); stopContinuations.incrementAndGet()
                                        notifications.add("stop-continuation"); continuation.run()
                                    }
                                    override fun onError(error: MeetingError) {
                                        thread(); captureErrors.incrementAndGet(); stopContinuations.incrementAndGet()
                                        notifications.add("stop-continuation"); continuation.run()
                                    }
                                })
                                jpeg[0] = 9 // 截图异步保存必须持有调用时副本。
                            }
                            override fun onResult(value: MeetingResult) {
                                thread(); result.set(value); notifications.add("result")
                            }
                        }))
                    assertTrue("首帧应经过真实 WebSocket", fixture.firstFrame.await(5, TimeUnit.SECONDS))
                    session.get().requestStop()
                    assertTrue("正常 Stop 应获得实际 READY", ready.await(10, TimeUnit.SECONDS))
                    assertEquals(MeetingSessionState.Phase.READY, terminal.get().phase())
                    assertEquals("实际ACK成功一次，不能把空JSON或排队当保存成功", 1, captureConfirmations.get())
                    assertEquals("本成功fixture不得经错误分支伪造成功", 0, captureErrors.get())
                    assertEquals("末帧确认后只续接一次正常Stop", 1, stopContinuations.get())
                    assertEquals("ACK到达前不提前停止音源", 0, audioStopsAtCaptureAck.get())
                    assertTrue(notifications.indexOf("capture-ack") < notifications.indexOf("stop-continuation"))
                    assertTrue(notifications.indexOf("stop-continuation") < notifications.indexOf("READY"))
                    assertNotNull(started.get())
                    assertEquals("mtg_facade", started.get().meetingId())
                    assertEquals(600000L, started.get().maxMeetingDurationMs())
                    assertEquals(setOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SUMMARY),
                        started.get().availableCapabilities())
                    assertEquals(listOf("begin:server-tag", "A", "T", "end"), fixture.modelMessages)
                    assertEquals(1, fixture.audio.starts)
                    assertEquals(1, fixture.audio.stops)
                    assertEquals("s-tail", display.get().latestTranscriptSegmentId())
                    assertEquals("最后确认字幕", display.get().rows()[0].transcript()!!.text())
                    assertNull(display.get().rows()[0].transcript()!!.sessionSpeakerId())
                    val value = result.get()
                    assertEquals(MeetingResult.DeliveryStatus.NOT_REQUESTED, value.deliveryStatus())
                    assertNull(value.resultUrl()); assertNull(value.content())
                    assertTrue(fixture.requests.none { it.requestUrl!!.encodedPath.startsWith("/v1/artifacts/") })
                    assertTrue(notifications.indexOf("result") < notifications.indexOf("READY"))
                    // READY 之后按需协议访问沿用本场身份；当前账号退出不替换原冻结凭据。
                    identity.clearSession()
                    val content = CompletableFuture<MeetingResultContent?>()
                    session.get().resultActionAccess().readContent(callback(content, callbackThreads))
                    val full = content.get(5, TimeUnit.SECONDS)!!
                    assertEquals("最后确认字幕", full.transcriptText())
                    assertEquals("本场总结", full.summary()!!.title())
                    assertEquals(1, full.presentationFrames().size)
                    fixture.assertRequests(value.genTime())
                    val email = CompletableFuture<Void?>()
                    val resultUrl = "https://result.example.test/index.html?meetingId=mtg_facade&genTime=" + value.genTime()
                    session.get().resultActionAccess().sendEmail(listOf("recipient@example.test"), resultUrl,
                        callback(email, callbackThreads))
                    email.get(5, TimeUnit.SECONDS)
                    val sent = fixture.request("/v1/meetings/mtg_facade:sendResultEmail")
                    assertEquals("Bearer fixture-access", sent.getHeader("Authorization"))
                    assertEquals(resultUrl, JSONObject(sent.body.clone().readUtf8()).getString("resultUrl"))
                    assertEquals(1, fixture.audio.starts)
                    assertEquals(1, fixture.audio.stops)
                }
                assertFalse(callbackThreads.isEmpty())
                assertTrue(callbackThreads.all { it == "meeting-ui" })
            }
        } finally { ui.shutdownNow() }
    }

    @Test fun resumeFinalizationForwardsOriginalCheckpointWithoutCaptureOrModels() {
        val ui = Executors.newSingleThreadExecutor { Thread(it, "meeting-resume-ui") }
        val callbackThreads: MutableList<String> = Collections.synchronizedList(ArrayList())
        val storeThreads: MutableList<String> = Collections.synchronizedList(ArrayList())
        val savedPhases = ArrayList<StoppedMeetingCheckpoint.Phase>()
        val savedGenTimes = ArrayList<Long?>()
        val identityReads = AtomicInteger()
        val modelOpens = AtomicInteger()
        val summaries = AtomicInteger()
        val publications = AtomicInteger()
        val reads = AtomicInteger()
        val published = AtomicReference<MeetingArtifactPublisher.Context>()
        val atPublication = AtomicReference<StoppedMeetingCheckpoint>()
        try {
            Fixture().use { fixture ->
                val pcm = temporaryFolder.newFile("stopped.pcm")
                Files.write(pcm.toPath(), ByteArray(3200)) // 已封口的 100ms 真实 PCM 文件，不冒称 Opus。
                val audio = SealedAudioArtifact("mtg_facade", pcm, SealedAudioArtifact.Format.PCM16, pcm.length(), 3200)
                val originalIdentity = ClientIdentity("original.app", "1.0", "v1", "original-device")
                val original = MeetingClientConfig(fixture.server.url("/").toString(), "original-access",
                    originalIdentity, MeetingProfile("original-profile", 1), ui)
                val initial = StoppedMeetingCheckpoint.restore(audio, 100, original,
                    "original-stop-key", StoppedMeetingCheckpoint.Phase.PUBLISH_PENDING, null)
                val stored = AtomicReference(initial)
                // 这里只验证 SDK 薄转发；内存替身不代表应用私有磁盘 Store 已经交付。
                val store = object : StoppedMeetingStore {
                    override fun save(update: StoppedMeetingCheckpoint): StoppedMeetingCheckpoint {
                        storeThreads.add(Thread.currentThread().name)
                        val saved = stored.get().merge(update)
                        stored.set(saved); savedPhases.add(saved.phase()); savedGenTimes.add(saved.genTime())
                        return saved
                    }
                    override fun read(checkpointId: String): Optional<StoppedMeetingCheckpoint> {
                        storeThreads.add(Thread.currentThread().name); reads.incrementAndGet()
                        return if (initial.checkpointId() == checkpointId) Optional.of(stored.get()) else Optional.empty()
                    }
                }
                val current = MeetingClientConfig(fixture.server.url("/wrong-current/").toString(), IdentityProvider {
                    identityReads.incrementAndGet(); throw AssertionError("续办不能读取当前身份")
                }, ClientIdentity("current.app", "9.0", "v9", "current-device"), MeetingProfile("current-profile", 9), ui)
                    .withStoppedMeetingStore(store)
                    .withArtifactPublisher {
                        publications.incrementAndGet(); published.set(it); atPublication.set(stored.get())
                    }
                val resumedAt = System.currentTimeMillis()
                AiMeetingClient(current, ModelAdapterRegistry(), ModelRuntime { _, _, _, _ ->
                    modelOpens.incrementAndGet(); throw AssertionError("续办不能重开采音或实时模型")
                }, SummaryRuntime { _, _, _ ->
                    summaries.incrementAndGet(); throw AssertionError("续办不能重跑总结")
                }).use { client ->
                    val ready = CompletableFuture<MeetingResult>()
                    client.resumeFinalization(initial.checkpointId(), callback(ready, callbackThreads))
                    val result = ready.get(10, TimeUnit.SECONDS)
                    assertEquals("mtg_facade", result.meetingId())
                    assertEquals(MeetingResult.Status.READY, result.status())
                    assertEquals(MeetingResult.DeliveryStatus.NOT_REQUESTED, result.deliveryStatus())
                    assertNull(result.content()); assertNull(result.resultUrl())
                    assertTrue(result.genTime() >= resumedAt)
                    assertEquals(result.genTime(), stored.get().genTime())
                    assertEquals(StoppedMeetingCheckpoint.Phase.READY, stored.get().phase())
                    assertEquals(listOf(StoppedMeetingCheckpoint.Phase.PUBLISH_PENDING, StoppedMeetingCheckpoint.Phase.PUBLISHED,
                        StoppedMeetingCheckpoint.Phase.STOP_ACCEPTED, StoppedMeetingCheckpoint.Phase.READY), savedPhases)
                    assertNull(savedGenTimes[0]); assertNull(savedGenTimes[1])
                    assertEquals(result.genTime(), savedGenTimes[2])
                    assertEquals(savedGenTimes[2], savedGenTimes[3])
                    assertEquals(StoppedMeetingCheckpoint.Phase.PUBLISH_PENDING, atPublication.get().phase())
                    assertNull(atPublication.get().genTime())
                    assertSame(audio, published.get().audio())
                    assertEquals(100L, published.get().frozenAudioDurationMs())
                    assertEquals(original.serverUrl(), published.get().serverUrl())
                    assertEquals("original-access", published.get().accessToken())
                    assertEquals(originalIdentity, published.get().clientIdentity())
                    val base = "/v1/meetings/mtg_facade"
                    val paths = ArrayList<String>()
                    fixture.requests.forEach { request ->
                        paths.add(request.requestUrl!!.encodedPath)
                        assertEquals("Bearer original-access", request.getHeader("Authorization"))
                        if (request.requestUrl!!.encodedPath.endsWith("/manifest"))
                            assertEquals(result.genTime().toString(), request.requestUrl!!.queryParameter("genTime"))
                    }
                    // 续办在发布前补交冻结用量；随后才是 Stop 与两次 READY 读取。
                    assertEquals(listOf(base + "/usage:submit", base + ":stop",
                        base + "/manifest", base + "/manifest"), paths)
                    assertEquals("original-stop-key", fixture.request(base + ":stop").getHeader("Idempotency-Key"))
                    assertEquals(1, reads.get()); assertEquals(1, publications.get())
                    assertEquals(0, identityReads.get()); assertEquals(0, modelOpens.get()); assertEquals(0, summaries.get())
                    assertEquals(0, fixture.audio.starts); assertEquals(0, fixture.audio.stops)
                    assertTrue(fixture.modelMessages.isEmpty())
                    assertEquals(listOf("meeting-resume-ui"), callbackThreads)
                    assertEquals(5, storeThreads.size)
                    assertTrue(storeThreads.none { it == Thread.currentThread().name })
                    assertTrue(storeThreads.none { it == "meeting-resume-ui" })
                }
            }
        } finally { ui.shutdownNow() }
    }

    private fun <T> callback(future: CompletableFuture<T>, threads: MutableList<String>): MeetingClient.Callback<T> =
        object : MeetingClient.Callback<T> {
            override fun onSuccess(result: T) { threads.add(Thread.currentThread().name); future.complete(result) }
            override fun onError(error: MeetingError) { future.completeExceptionally(IOException(error.code.name)) }
        }

    private class PartnerAdapter : ModelAdapter {
        override fun id() = "partner-string"
        override fun startMessage(grant: ModelSessionGrant) = "begin:" + grant.initParams()["sessionTag"]
        override fun stopMessage(grant: ModelSessionGrant) = "end"
        override fun onServerMessage(text: String, listener: TranscriptListener): ModelSignal =
            onServerMessage(text, listener, TranslationListener {}, SegmentStartedListener {})
        override fun onServerMessage(text: String, transcripts: TranscriptListener,
            translations: TranslationListener, segments: SegmentStartedListener): ModelSignal {
            if (text == "ready") return ModelSignal.AUDIO_READY
            if (text == "final") {
                segments.onSegmentStarted(SegmentStartedEvent("s-tail", 0))
                transcripts.onTranscript(TranscriptEvent("s-tail", 1, 0, 200, "zh-CN", "最后确认字幕", true, "optional-speaker"))
                return ModelSignal.FINISHED
            }
            return ModelSignal.NONE
        }
    }

    private class ControlledAudio : PcmSource {
        lateinit var sink: MicPcmSource.FrameSink
        var starts = 0
        var stops = 0
        var active = false
        override fun start(sink: MicPcmSource.FrameSink) {
            this.sink = sink; starts++; active = true
            sink.onAudioInputMode(AudioInputMode.CZUR_BROKER); emit('A')
        }
        fun emit(marker: Char) {
            val pcm = ByteArray(3200) { marker.code.toByte() }
            sink.onFrame(pcm); Arrays.fill(pcm, 0.toByte())
        }
        override fun stop() { if (!active) return; active = false; stops++; emit('T') }
        override fun label() = "CONTROLLED_AUDIO"
    }

    private class Fixture : AutoCloseable {
        val server = MockWebServer()
        val audio = ControlledAudio()
        val requests: MutableList<RecordedRequest> = Collections.synchronizedList(ArrayList())
        val modelMessages: MutableList<String> = Collections.synchronizedList(ArrayList())
        val firstFrame = CountDownLatch(1)
        var manifests = 0

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    return when (request.requestUrl!!.encodedPath) {
                        "/v1/capabilities" -> json(200, """{"capabilities":["transcript.realtime","summary.structured"],"resultLinkTtlMs":600000,"resultEmailEnabled":false}""")
                        "/v1/reference/session:login" -> json(200, """{"accessToken":"fixture-access","expiresAt":"2030-01-01T00:00:00Z"}""")
                        "/v1/meetings" -> json(201, """{"meetingId":"mtg_facade","limits":{"maxMeetingDurationMs":600000},"capabilityDecisions":[{"capability":"transcript.realtime","availability":"AVAILABLE"},{"capability":"summary.structured","availability":"AVAILABLE"}]}""")
                        "/v1/meetings/mtg_facade:modelSession" -> json(200, """{"meetingId":"mtg_facade","adapterId":"partner-string","endpoint":"${server.url("/model")}","expiresAt":"2030-01-01T00:00:00Z","initParams":{"sessionTag":"server-tag"}}""")
                        "/v1/meetings/mtg_facade:summarySession" -> json(200, """{"adapterId":"bailian-summary","meetingId":"mtg_facade","endpoint":"${server.url("/summary-model")}","expiresAt":"2030-01-01T00:00:00Z","token":"fixture-summary"}""")
                        "/summary-model" -> json(200, "{\"output\":{\"text\":\"" + SUMMARY.replace("\"", "\\\"") + "\"}}")
                        "/model" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                modelMessages.add(text)
                                if (text.startsWith("begin:")) webSocket.send("ready")
                                if (text == "end") webSocket.send("final")
                            }
                            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                                modelMessages.add(bytes[0].toInt().toChar().toString())
                                if (bytes[0] == 'A'.code.toByte()) firstFrame.countDown()
                            }
                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                        })
                        "/v1/meetings/mtg_facade:stop" -> json(200, """{"inputState":"INPUT_ACCEPTED","finalizationReceipt":"fixture-receipt","manifestState":"GENERATING"}""")
                        "/v1/meetings/mtg_facade/usage:submit" -> {
                            val submitted = JSONObject(request.body.clone().readUtf8()).getLong("audioDurationMs")
                            json(201, """{"meetingId":"mtg_facade","audioDurationMs":$submitted,"accepted":true}""")
                        }
                        "/v1/meetings/mtg_facade:sendResultEmail" -> json(200, "{}")
                        "/v1/meetings/mtg_facade/manifest" -> {
                            if (++manifests == 1) json(202, """{"meetingId":"mtg_facade","state":"FINALIZING","inputState":"INPUT_ACCEPTED","finalizationReceipt":"fixture-receipt","manifestState":"GENERATING","updatedAt":"2026-10-07T00:00:00Z"}""")
                            else json(200, """{"status":"READY","title":"本场","artifacts":[{"type":"transcript.final","status":"READY","artifactId":"text"},{"type":"summary.structured","status":"READY","artifactId":"summary"},{"type":"presentation.capture","status":"READY","artifactId":"slides"}]}""")
                        }
                        "/v1/artifacts/text" -> json(200, """{"text":"最后确认字幕","segments":[{"segmentId":"s-tail","text":"最后确认字幕","language":"zh-CN","startMs":0,"endMs":200}]}""")
                        "/v1/artifacts/summary" -> json(200, SUMMARY)
                        "/v1/artifacts/slides" -> json(200, """{"frames":[{"offsetMs":200,"jpegBase64":"AQID"}]}""")
                        "/v1/meetings/mtg_facade/presentation-captures:append" -> json(201, """{"artifactId":"slides","chunkSequence":1,"frameCount":1}""")
                        "/v1/meetings/mtg_facade:startClient", "/v1/meetings/mtg_facade/events:append",
                        "/v1/meetings/mtg_facade/summary:submit" -> json(200, "{}")
                        else -> json(404, "{}")
                    }
                }
            }
            server.start()
        }
        fun client(identity: IdentityProvider, adapters: ModelAdapterRegistry, ui: ExecutorService): AiMeetingClient {
            val config = MeetingClientConfig(server.url("/").toString(), identity,
                ClientIdentity("partner.app", "2.0", "v1", "fixture-device"), MeetingProfile("partner-meeting", 3), ui)
            return AiMeetingClient(config, adapters, ModelRuntime { grant, adapter, transcripts, listener ->
                AndroidModelRuntime.WebSocketModelSession(grant, adapter, transcripts, listener, audio)
            }, AndroidSummaryRuntime())
        }
        fun request(path: String): RecordedRequest = requests.first { it.requestUrl!!.encodedPath == path }
        fun assertRequests(genTime: Long) {
            val paths = requests.map { it.requestUrl!!.encodedPath }
            val base = "/v1/meetings/mtg_facade"
            assertEquals(listOf("/v1/capabilities", "/v1/reference/session:login", "/v1/capabilities",
                "/v1/meetings", base + ":modelSession", base + ":startClient", "/model",
                base + "/presentation-captures:append", base + "/events:append", base + "/usage:submit", base + ":summarySession",
                "/summary-model", base + "/summary:submit", base + ":stop", base + "/manifest", base + "/manifest",
                base + "/manifest", "/v1/artifacts/text", "/v1/artifacts/summary", "/v1/artifacts/slides"), paths)
            val create = JSONObject(request("/v1/meetings").body.clone().readUtf8())
            assertEquals("partner.app", create.getJSONObject("client").getString("appId"))
            assertEquals("partner-meeting", create.getJSONObject("profile").getString("id"))
            assertEquals("Bearer fixture-access", request("/v1/meetings").getHeader("Authorization"))
            val capture = JSONObject(request(base + "/presentation-captures:append").body.clone().readUtf8())
            assertEquals(1, capture.getInt("chunkSequence"))
            assertEquals(1, capture.getJSONArray("frames").length())
            assertEquals(200L, capture.getJSONArray("frames").getJSONObject(0).getLong("offsetMs"))
            assertEquals("AQID", capture.getJSONArray("frames").getJSONObject(0).getString("jpegBase64"))
            val event = JSONObject(request(base + "/events:append").body.clone().readUtf8())
            assertEquals(1, event.getInt("clientSequence"))
            assertFalse(event.getJSONObject("transcriptUpsert").has("speaker"))
            val usage = JSONObject(request(base + "/usage:submit").body.clone().readUtf8())
            assertEquals(200L, usage.getLong("audioDurationMs")) // A + 完整尾帧 T，各100ms。
            assertEquals("Bearer fixture-access", request(base + "/usage:submit").getHeader("Authorization"))
            assertTrue(request("/summary-model").body.clone().readUtf8().contains("最后确认字幕"))
            assertEquals("Bearer fixture-summary", request("/summary-model").getHeader("Authorization"))
            assertNotNull(request(base + ":stop").getHeader("Idempotency-Key"))
            requests.filter { it.requestUrl!!.encodedPath.contains("/manifest") || it.requestUrl!!.encodedPath.startsWith("/v1/artifacts/") }
                .forEach { assertEquals(genTime.toString(), it.requestUrl!!.queryParameter("genTime")) }
        }
        override fun close() = server.shutdown()
        companion object {
            private const val SUMMARY = """{"language":"zh-CN","title":"本场总结","overview":"已确认计划","keyPoints":["计划"],"actionItems":[]}"""
            private fun json(code: Int, body: String): MockResponse =
                MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
        }
    }
}
