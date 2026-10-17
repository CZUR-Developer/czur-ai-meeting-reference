package com.czur.aimeeting.client
import org.junit.Assert.*

import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.client.provider.TingwuModelAdapter
import com.czur.aimeeting.client.provider.BailianSummaryAdapter
import com.czur.aimeeting.client.identity.IdentityProvider
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.identity.IdentitySession
import com.czur.aimeeting.client.identity.ReferenceIdentityProvider
import com.czur.aimeeting.client.server.*
import com.czur.aimeeting.model.*
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Collections
import java.util.Optional
import java.util.OptionalLong
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
/** 只覆盖正常会议编排及明确能力门：同一核心使用真实材料端口或本地 Mock 端口。 */
class MeetingCoordinatorTest {
    companion object {
        private val ALL = setOf(MeetingCapabilityState.TRANSCRIPT,
            MeetingCapabilityState.TRANSLATION, MeetingCapabilityState.SUMMARY, MeetingCapabilityState.SPEAKER)
    }

    @Test fun optionalSpeakerIsRequestedOnlyWhenDeploymentAndLocalAdapterProvideIt() {
        Fixture(true, setOf(MeetingCapabilityState.TRANSCRIPT)).use { f ->
            f.server.deploymentCapabilities = setOf(MeetingCapabilityState.TRANSCRIPT)
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, false)
            assertEquals(listOf(MeetingCapabilityState.TRANSCRIPT), f.server.requested.requestedCapabilities())
            assertFalse(f.probe.info!!.availableCapabilities().contains(MeetingCapabilityState.SPEAKER))
        }
    }

    @Test fun optionalSpeakerRemainsInCreateWhenDeploymentExposesIt() {
        Fixture(true, ALL).use { f ->
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, false)
            assertTrue(f.server.requested.requestedCapabilities().contains(MeetingCapabilityState.SPEAKER))
            assertTrue(f.probe.info!!.availableCapabilities().contains(MeetingCapabilityState.SPEAKER))
        }
    }
    @Test fun legacyConfigAndRuntimeDefaultsHaveNoRecordingOrPublication() {
        val config = MeetingClientConfig.reference("https://service.example.test", "fixture-token")
        assertFalse(config.artifactPublisher().supportsAudioPublication())
        val publisher = Publisher()
        val configured = config.withArtifactPublisher(publisher)
        assertSame(publisher, configured.artifactPublisher())
        assertFalse("配置复制不能改变旧配置", config.artifactPublisher().supportsAudioPublication())
        assertSame(publisher, configured.forSession(IdentitySession("scene-token", "用户")).artifactPublisher())
        assertTrue(config.stoppedMeetingStore().isEmpty)
        val store = MemoryStore()
        val withStore = configured.withStoppedMeetingStore(store)
        assertSame(store, withStore.stoppedMeetingStore().orElseThrow())
        assertSame(store, withStore.withArtifactPublisher(publisher)
            .forSession(IdentitySession("scene-token", "用户")).stoppedMeetingStore().orElseThrow())
        assertSame(publisher, withStore.artifactPublisher())
        assertTrue("复制配置不为旧调用启用停止记录", configured.stoppedMeetingStore().isEmpty)

        // 合法的惰性材料只用于证明旧重载转发；本测试不启动模型或伪造空参数协议。
        val session = object : ModelSession {
            override fun start() {}
            override fun stopInput() {}
            override fun close() {}
        }
        val grant = ModelSessionGrant("legacy-fixture", "partner-legacy",
            "wss://model.example.test/legacy", "2030-01-01T00:00:00Z", null, emptyMap())
        val partner = object : ModelAdapter {
            override fun id() = "partner-legacy"
            override fun startMessage(grant: ModelSessionGrant): String = "{}"
            override fun onServerMessage(raw: String, listener: TranscriptListener) = ModelSignal.NONE
        }
        val transcripts = TranscriptListener { fail("惰性运行时不能产生字幕") }
        val listener = object : ModelSessionListener {
            override fun onFinished() {}
            override fun onError(message: String) { fail(message) }
        }
        val legacy = ModelRuntime { _, _, _, _ -> session }
        assertFalse(legacy.supportsRetainedAudio())
        assertSame(session, legacy.open(grant, partner, transcripts, listener, false))
        assertThrows(IllegalStateException::class.java) {
            legacy.open(grant, partner, transcripts, listener, true)
        }
        val unused = ModelSessionReconnector { throw AssertionError("旧实现不能自行续领") }
        assertSame(session, legacy.open(grant, partner, transcripts, listener, false, unused))
        assertThrows(IllegalStateException::class.java) {
            legacy.open(grant, partner, transcripts, listener, true, unused)
        }
        val port = object : MeetingRuntimePort {
            override fun open(g: ModelSessionGrant?, a: ModelAdapter?,
                t: TranscriptListener, l: ModelSessionListener) = session
            override fun summarize(g: SummarySessionGrant?, a: SummaryAdapter?, i: SummaryInput): SummaryResult =
                throw AssertionError("本测试不调用总结")
        }
        assertFalse(port.supportsRetainedAudio())
        assertSame(session, port.open(null, null, transcripts, listener, false))
        assertThrows(IllegalStateException::class.java) { port.open(null, null, transcripts, listener, true) }
        assertSame(session, port.open(null, null, transcripts, listener, false, unused))
        assertThrows(IllegalStateException::class.java) { port.open(null, null, transcripts, listener, true, unused) }
        assertFalse("伙伴旧Adapter默认仍终止，不隐式重连", partner.supportsSessionReconnect())
        partner.drainConfirmedOnDisconnect { fail("默认不伪造final") }
        assertTrue(session.sealedAudioArtifact().isEmpty)
    }


    @Test fun reconnectMaterialsKeepFrozenIdentityAndMeetingWithFreshAdapters() {
        val identity: ReferenceIdentityProvider = ReferenceIdentityProvider("original-scene-token")
        Fixture(true, ALL, identity).use { f ->
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, false)
            val initial: ModelAdapter = f.runtime.adapter!!
            identity.updateSession(IdentitySession("another-scene-token", "其他账号"))
            val first: ModelSessionConnection = f.runtime.reconnector!!.request()
            val second: ModelSessionConnection = f.runtime.reconnector!!.request()
            assertEquals(f.probe.info!!.meetingId(), first.grant().meetingId())
            assertEquals(f.probe.info!!.meetingId(), second.grant().meetingId())
            assertEquals(initial.id(), first.adapter().id())
            assertNotSame(initial, first.adapter())
            assertNotSame("每次新模型任务都用新解析实例", first.adapter(), second.adapter())
            assertEquals("original-scene-token", f.sessionToken)
            assertEquals(listOf("mtg_test", "mtg_test", "mtg_test"), f.server.modelSessionRequests)
            assertEquals(1, Collections.frequency(f.server.steps, "create"))
            assertEquals(1, Collections.frequency(f.server.steps, "start"))
            assertEquals("续领不重新打开运行时或音源", 1, f.runtime.openCalls)
        }
    }

    @Test fun blockedAppendCannotDelayStopButResultStillWaitsForRealAck() {
        Fixture(true, ALL).use { f ->
            f.server.blockAppend = true
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, false)
            f.runtime.emit("s1", 1, "zh-CN", "断网前字幕", true)
            assertTrue(f.server.appendEntered.await(2, TimeUnit.SECONDS))
            f.session.requestStop()
            assertTrue("停源不能排在挂起HTTP后", f.runtime.stopped.await(2, TimeUnit.SECONDS))
            assertEquals(1, f.runtime.stopCalls)
            assertEquals(listOf("start", "stop"), f.runtime.controlCalls)
            assertEquals("尚未释放标准事件HTTP", 1, f.server.releaseAppend.count)
            assertTrue("未收到事件ACK不能伪造结果", f.probe.results.isEmpty())
            assertFalse(f.server.steps.contains("stop"))
            f.server.releaseAppend.countDown()
            assertNotNull(f.probe.result())
        }
    }

    @Test fun blockedReconnectMaterialsCannotDelayRuntimeStop() {
        Fixture(true, ALL).use { f ->
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, false)
            f.server.blockModelGrant = true
            val materials: CompletableFuture<ModelSessionConnection> = CompletableFuture.supplyAsync({
                try { f.runtime.reconnector!!.request() }
                catch (failure: IOException) { throw java.util.concurrent.CompletionException(failure) }
            })
            assertTrue(f.server.modelGrantEntered.await(2, TimeUnit.SECONDS))
            f.session.requestStop()
            assertTrue("材料HTTP不能占用音源控制队列", f.runtime.stopped.await(2, TimeUnit.SECONDS))
            assertEquals(1, f.server.releaseModelGrant.count)
            assertNotNull("停止／原场结果无需等待续领HTTP", f.probe.result())
            f.server.releaseModelGrant.countDown()
            assertEquals("mtg_test", materials.get(2, TimeUnit.SECONDS).grant().meetingId())
            // 此处只验证端口与线程边界；迟到材料不再接模型由Android Runtime另行验收。
            assertEquals(1, f.runtime.openCalls)
            assertEquals(1, f.runtime.stopCalls)
        }
    }

    @Test fun defaultMeetingNeverReadsAudioArtifactOrCallsConfiguredPublisher() {
        val publisher: Publisher = Publisher()
        Fixture(true, ALL, ReferenceIdentityProvider("fixture-token"), publisher).use { f ->
            f.runtime.duration = OptionalLong.of(1000)
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, false)
            f.session.requestStop()
            assertNotNull(f.probe.result())
            assertFalse(f.runtime.retainAudio)
            assertFalse(f.server.requested.retainAudio())
            assertEquals(0, f.runtime.artifactReads)
            assertTrue(publisher.contexts.isEmpty())
            assertEquals(listOf("discover", "create", "grant", "start", "usage", "stop", "manifest"), f.server.steps)
        }
    }

    @Test fun shortMinutesWithoutFinalTranscriptSkipsSummaryAndStillPublishesThenStops() {
        val publisher: Publisher = Publisher()
        Fixture(true, ALL, ReferenceIdentityProvider("fixture-token"), publisher).use { f ->
            val sealed: SealedAudioArtifact = audio("mtg_test", 32000)
            f.runtime.prepareAudio(sealed)
            f.start(retainedOptions(MeetingOptions.Mode.MEETING_MINUTES))
            f.session.requestStop()
            assertNotNull(f.probe.result())
            assertNull("没有最终字幕不调用总结运行时", f.runtime.summaryInput)
            assertTrue("不添加占位字幕或伪总结", f.server.transcripts.isEmpty())
            assertTrue(f.probe.states.any { value -> value.message().contains("没有最终字幕") })
            assertSame(sealed, publisher.contexts.get(0).audio())
            assertEquals(listOf(1000L), f.server.usages)
            assertEquals(1, f.runtime.stopCalls)
            assertEquals(listOf("discover", "create", "grant", "start", "usage",
                    "publish", "stop", "manifest"), f.server.steps)
        }
    }

    @Test fun retainAudioWithoutRuntimeIsRejectedBeforeCreate() {
        Fixture(true, ALL, ReferenceIdentityProvider("fixture-token"), Publisher()).use { f ->
            f.session = f.client.start(retainedOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT), f.probe)
            assertTrue(f.probe.awaitPhase(MeetingSessionState.Phase.FAILED).message().contains("同源录音"))
            assertTrue(f.server.steps.isEmpty())
            assertEquals(0, f.runtime.openCalls)
        }
    }

    @Test fun retainAudioWithoutPublisherIsRejectedBeforeCreate() {
        Fixture(true, ALL).use { f ->
            f.runtime.retainedAudioSupported = true
            f.session = f.client.start(retainedOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT), f.probe)
            assertTrue(f.probe.awaitPhase(MeetingSessionState.Phase.FAILED).message().contains("产物发布"))
            assertTrue(f.server.steps.isEmpty())
            assertEquals(0, f.runtime.openCalls)
        }
    }

    @Test fun retainedTailUsageAndSelectedSummaryAreSavedBeforePublicationThenStop() {
        val publisher: Publisher = Publisher()
        Fixture(true, ALL, ReferenceIdentityProvider("fixture-token"), publisher).use { f ->
            val audio: SealedAudioArtifact = audio("mtg_test", 32000)
            f.runtime.prepareAudio(audio)
            f.runtime.emitTail = true
            f.start(retainedOptions(MeetingOptions.Mode.MEETING_MINUTES))
            f.session.requestStop()
            assertNotNull(f.probe.result())
            assertTrue(f.runtime.retainAudio)
            assertTrue(f.server.requested.retainAudio())
            assertSame(audio, publisher.contexts.get(0).audio())
            assertEquals("mtg_test", publisher.contexts.get(0).meetingId())
            assertEquals(1000, publisher.contexts.get(0).frozenAudioDurationMs())
            assertEquals("最后字幕", f.runtime.summaryInput!!.transcript())
            assertEquals(listOf("discover", "create", "grant", "start", "event1", "usage",
                    "summaryGrant", "summary", "publish", "stop", "manifest"), f.server.steps)
            assertEquals(1, f.runtime.artifactReads)
            assertEquals(1, f.runtime.durationReads)
            assertEquals(1, f.runtime.stopCalls)
        }
    }

    @Test fun finalEventSaveFailurePreventsAudioPublicationAndBackendStop() {
        val publisher: Publisher = Publisher()
        Fixture(true, ALL, ReferenceIdentityProvider("fixture-token"), publisher).use { f ->
            f.runtime.prepareAudio(audio("mtg_test", 32000))
            f.runtime.emitTail = true
            f.server.failAppend = true
            f.start(retainedOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT))
            f.session.requestStop()
            f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING)
            assertEquals(listOf("discover", "create", "grant", "start", "event1", "event1"), f.server.steps)
            assertTrue(publisher.contexts.isEmpty())
            assertEquals(0, f.runtime.artifactReads)
            assertEquals(0, f.runtime.durationReads)
            assertEquals(1, f.runtime.stopCalls)
        }
    }

    @Test fun laterEventConfirmsOriginalTranscriptBeforeQueuedTranslationAndRevision() {
        Fixture(true, ALL).use { f ->
            f.start(MeetingOptions.Mode.LIVE_TRANSLATION, MeetingOptions.ResultDelivery.WEB, false)
            val first: TranscriptEvent = TranscriptEvent("s1", 1, 0, 100, "zh-CN", "原文", false)
            val translation: TranslationEvent = TranslationEvent("t1", "s1", 1,
                    "zh-CN", "en-US", "Source", false)
            val revision: TranscriptEvent = TranscriptEvent("s1", 2, 0, 200, "zh-CN", "原文修订", true)
            f.server.failAppend = true
            f.runtime.transcripts.onTranscript(first)
            assertEquals(0, f.probe.awaitMessage("尚未确认").savedEventCount())
            f.runtime.listener.onTranslation(translation)
            f.probe.awaitMessage("尚未确认")
            assertEquals("失败队首未确认前不发送后续译文", listOf(
                    "discover", "create", "grant", "start", "event1", "event1"), f.server.steps)
            f.server.failAppend = false
            f.runtime.transcripts.onTranscript(revision)
            f.probe.awaitSavedEvents(2)
            f.session.requestStop()
            assertNotNull(f.probe.result())
            assertEquals(listOf("discover", "create", "grant", "start", "event1", "event1",
                    "event1", "translation2", "event3", "stop", "manifest"), f.server.steps)
            assertSame(first, f.server.standardAttempts.get(0).transcript())
            assertSame(first, f.server.standardAttempts.get(1).transcript())
            assertSame(first, f.server.standardAttempts.get(2).transcript())
            assertSame(translation, f.server.standardAccepted.get(2)!!.translation())
            assertSame(revision, f.server.standardAccepted.get(3)!!.transcript())
            assertEquals(3, f.server.standardAccepted.size)
            assertEquals(1, f.runtime.openCalls)
        }
    }

    @Test fun failedTranslationRetriesOriginalWithoutRepeatingAcknowledgedTranscript() {
        Fixture(true, ALL).use { f ->
            f.start(MeetingOptions.Mode.LIVE_TRANSLATION, MeetingOptions.ResultDelivery.WEB, false)
            f.runtime.emit("s1", 1, "zh-CN", "已确认原文", true)
            f.probe.awaitSavedEvents(1)
            val translation: TranslationEvent = TranslationEvent("t1", "s1", 1,
                    "zh-CN", "en-US", "Confirmed source", true)
            f.server.failAppend = true
            f.runtime.listener.onTranslation(translation)
            assertEquals(1, f.probe.awaitMessage("尚未确认").savedEventCount())
            f.server.failAppend = false
            f.runtime.emit("s2", 1, "zh-CN", "恢复后的原文", true)
            f.probe.awaitSavedEvents(2)
            f.session.requestStop()
            assertNotNull(f.probe.result())
            assertEquals(listOf("discover", "create", "grant", "start", "event1",
                    "translation2", "translation2", "event3", "stop", "manifest"), f.server.steps)
            assertSame(translation, f.server.standardAttempts.get(1).translation())
            assertSame(translation, f.server.standardAttempts.get(2).translation())
            assertEquals("已确认原文不重复提交", 1, Collections.frequency(f.server.steps, "event1"))
            assertEquals(3, f.server.standardAccepted.size)
        }
    }

    @Test fun lostTranscriptAckReusesOriginalEventBeforeUsageSummaryPublicationAndStop() {
        val publisher: Publisher = Publisher()
        Fixture(true, ALL, ReferenceIdentityProvider("fixture-token"), publisher).use { f ->
            f.runtime.prepareAudio(audio("mtg_test", 32000))
            f.runtime.emitTail = true
            f.server.loseAppendAckOnce = true
            f.start(retainedOptions(MeetingOptions.Mode.MEETING_MINUTES))
            f.session.requestStop()
            assertNotNull(f.probe.result())
            assertEquals(listOf("discover", "create", "grant", "start", "event1", "event1",
                    "usage", "summaryGrant", "summary", "publish", "stop", "manifest"), f.server.steps)
            assertEquals("远端第一次已保存，响应丢失后的同包只确认一次事实", 1, f.server.standardAccepted.size)
            assertEquals(2, f.server.standardAttempts.size)
            assertEquals(f.server.standardAttempts.get(0), f.server.standardAttempts.get(1))
            assertEquals(1, f.probe.awaitPhase(MeetingSessionState.Phase.READY).savedEventCount())
            assertEquals(1, publisher.contexts.size)
            assertEquals(1, f.runtime.stopCalls)
        }
    }

    @Test fun pendingStopConfirmsSameOriginalEventsWithoutStoppingModelAgain() {
        val publisher: Publisher = Publisher()
        Fixture(true, ALL, ReferenceIdentityProvider("fixture-token"), publisher).use { f ->
            f.runtime.prepareAudio(audio("mtg_test", 32000))
            f.runtime.emitTail = true
            f.server.failAppend = true
            f.start(retainedOptions(MeetingOptions.Mode.LIVE_TRANSLATION))
            f.session.requestStop()
            val pending: MeetingSessionState = f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING)
            assertTrue(pending.canStop())
            assertEquals(0, pending.savedEventCount())
            assertTrue(f.server.standardAccepted.isEmpty())
            assertTrue(publisher.contexts.isEmpty())
            assertFalse(f.server.steps.contains("usage"))
            assertFalse(f.server.steps.contains("translation2"))
            assertFalse(f.server.steps.contains("stop"))
            f.server.failAppend = false
            f.session.requestStop()
            assertNotNull(f.probe.result())
            assertEquals(listOf("discover", "create", "grant", "start", "event1", "event1",
                    "event1", "event1", "translation2", "usage", "publish", "stop", "manifest"), f.server.steps)
            assertEquals(2, f.server.standardAccepted.size)
            val original: StandardAppend = f.server.standardAttempts.get(0)
            for (attempt in 1 until 4)
                assertEquals(original, f.server.standardAttempts.get(attempt))
            assertEquals(1, f.probe.awaitPhase(MeetingSessionState.Phase.READY).savedEventCount())
            assertEquals(1, f.probe.beforeStopCalls.get())
            assertEquals(1, f.runtime.stopCalls)
            assertEquals(1, f.runtime.openCalls)
            assertEquals(1, publisher.contexts.size)
        }
    }

    @Test fun failedPublicationRetriesSameSealedAndFrozenAccessWithoutReadingIdentityAgain() {
        val identity: ReferenceIdentityProvider = ReferenceIdentityProvider("opening-fixture-token")
        val identityReads: AtomicInteger = AtomicInteger()
        val publisher: Publisher = Publisher()
        publisher.failOnce = true
        Fixture(true, ALL,
                { identityReads.incrementAndGet(); identity.currentIdentity() }, publisher).use { f ->
            assertEquals("Provider构造配置初始Token为空", "", f.config.accessToken())
            val original: SealedAudioArtifact = audio("mtg_test", 32000)
            f.runtime.prepareAudio(original)
            f.runtime.emitTail = true
            f.start(retainedOptions(MeetingOptions.Mode.MEETING_MINUTES))
            identity.clearSession()
            f.session.requestStop()
            assertTrue(f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING).canStop())
            assertFalse(f.server.steps.contains("stop"))
            assertTrue(f.probe.results.isEmpty())
            identity.updateSession(IdentitySession("other-account-token", "另一用户"))
            f.runtime.artifact = Optional.of(audio("other-scene", 64000))
            f.runtime.duration = OptionalLong.of(2000)
            f.session.requestStop()
            assertNotNull(f.probe.result())
            assertEquals(2, publisher.contexts.size)
            val context = publisher.contexts.get(0)
            assertSame(context, publisher.contexts.get(1))
            assertSame(original, context.audio())
            assertEquals("opening-fixture-token", context.accessToken())
            assertEquals(f.config.serverUrl(), context.serverUrl())
            assertSame(f.config.clientIdentity(), context.clientIdentity())
            assertFalse(context.toString().contains("opening-fixture-token"))
            assertFalse(context.toString().contains(f.config.serverUrl()))
            assertFalse(context.toString().contains(original.file().path))
            assertEquals(1, identityReads.get())
            assertEquals(1, f.runtime.openCalls)
            assertEquals(1, f.runtime.stopCalls)
            assertEquals(1, f.runtime.artifactReads)
            assertEquals(1, f.runtime.durationReads)
            assertEquals(1, f.probe.beforeStopCalls.get())
            assertEquals(listOf(1000L), f.server.usages)
            assertEquals(listOf("discover", "create", "grant", "start", "event1", "usage",
                    "summaryGrant", "summary", "publish", "publish", "stop", "manifest"), f.server.steps)
        }
    }

    @Test fun publicationAckIsNotRepeatedWhenBackendStopNeedsSameSceneRetry() {
        val publisher: Publisher = Publisher()
        Fixture(true, ALL, ReferenceIdentityProvider("fixture-token"), publisher).use { f ->
            f.runtime.prepareAudio(audio("mtg_test", 0))
            f.server.pendingStop = true
            f.start(retainedOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT))
            f.session.requestStop()
            f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING)
            assertEquals(1, publisher.contexts.size)
            assertEquals(0, publisher.contexts.get(0).audio().capturedPcmBytes())
            assertEquals(0, publisher.contexts.get(0).frozenAudioDurationMs())
            f.session.requestStop()
            assertNotNull(f.probe.result())
            assertEquals(listOf("discover", "create", "grant", "start", "usage", "publish", "stop", "stop", "manifest"), f.server.steps)
            assertEquals(1, f.runtime.artifactReads)
            assertEquals(1, f.runtime.stopCalls)
            assertEquals(1, publisher.contexts.size)
        }
    }

    @Test fun unconfirmedTailUsageOrSelectedSummaryNeverCreatesStoppedCheckpoint() {
        // 只覆盖真实切点的三个前置屏障，不建立未 ACK 队列的磁盘恢复。
        for (barrier in 0 until 3) {
            val store: MemoryStore = MemoryStore()
            val publisher: Publisher = Publisher()
            Fixture(true, ALL, ReferenceIdentityProvider("test-token"),
                    publisher, store).use { f ->
                f.runtime.prepareAudio(audio("mtg_test", 32000))
                f.runtime.emitTail = true
                f.server.failAppend = barrier == 0
                f.server.failUsageOnce = barrier == 1
                f.server.failSummaryOnce = barrier == 2
                f.start(retainedOptions(MeetingOptions.Mode.MEETING_MINUTES))
                f.session.requestStop()
                f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING)
                assertTrue("前半段未确认不能留下可恢复记录", store.read("mtg_test").isEmpty())
                assertTrue(publisher.contexts.isEmpty())
                assertFalse(f.server.steps.contains("stop"))
                assertEquals(0, f.runtime.artifactReads)
                assertEquals("未接受 Stop 前不生成 genTime", 0, f.clockCalls.get())
            }
        }
    }

    @Test fun publicationFailureResumesOriginalSealedAccessWithoutOpeningMeetingOrModels() {
        val store: MemoryStore = MemoryStore()
        val firstPublisher: Publisher = Publisher()
        firstPublisher.failOnce = true
        lateinit var server: Server
        lateinit var pending: StoppedMeetingCheckpoint
        val sealed: SealedAudioArtifact = audio("mtg_test", 32000)
        Fixture(true, ALL, ReferenceIdentityProvider("original-token"),
                firstPublisher, store).use { f ->
            f.runtime.prepareAudio(sealed)
            f.runtime.emitTail = true
            f.start(retainedOptions(MeetingOptions.Mode.MEETING_MINUTES))
            f.session.requestStop()
            f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING)
            pending = store.read("mtg_test").orElseThrow()
            assertEquals(StoppedMeetingCheckpoint.Phase.PUBLISH_PENDING, pending.phase())
            assertNull(pending.genTime())
            assertEquals(0, f.clockCalls.get())
            assertEquals(1, f.runtime.artifactReads)
            assertTrue(f.probe.results.isEmpty())
            server = f.server
        }
        val restartedStore: MemoryStore = store.reopened()
        val publisher: Publisher = Publisher()
        publisher.server = server
        val freshRuntime: Runtime = Runtime(true)
        val currentIdentityReads: AtomicInteger = AtomicInteger()
        resumeClient(restartedStore, publisher, server, freshRuntime,
                currentIdentityReads, { it.run() }, { 777 }).use { client ->
            val result = resumeResult(client).get(2, TimeUnit.SECONDS)
            assertEquals("mtg_test", result.meetingId())
            assertEquals(777, result.genTime())
            assertEquals(listOf(pending.stableStopKey()), server.stopKeys)
            val actual = publisher.contexts.get(0)
            assertEquals(sealed.file(), actual.audio().file())
            assertEquals(sealed.capturedPcmBytes(), actual.audio().capturedPcmBytes())
            assertEquals(1000, actual.frozenAudioDurationMs())
            assertEquals("original-token", actual.accessToken())
            assertEquals(StoppedMeetingCheckpoint.Phase.READY,
                    restartedStore.read("mtg_test").orElseThrow().phase())
            assertResumeDidNotRestartBusiness(server, freshRuntime, currentIdentityReads)
            assertEquals("已 ACK 的总结不重新请求", 1, Collections.frequency(server.steps, "summaryGrant"))
            assertEquals(1, Collections.frequency(server.steps, "summary"))
            assertEquals(listOf(1000L), server.usages)
        }
    }

    @Test fun acceptedStopWithLostAckResumesSameKeyWithoutRepeatingPublication() {
        val store: MemoryStore = MemoryStore()
        val originalPublisher: Publisher = Publisher()
        lateinit var server: Server
        lateinit var originalKey: String
        Fixture(true, ALL, ReferenceIdentityProvider("test-token"),
                originalPublisher, store).use { f ->
            f.runtime.prepareAudio(audio("mtg_test", 32000))
            f.server.loseStopAckOnce = true
            f.start(retainedOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT))
            f.session.requestStop()
            f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING)
            val saved = store.read("mtg_test").orElseThrow()
            assertEquals(StoppedMeetingCheckpoint.Phase.PUBLISHED, saved.phase())
            assertNull(saved.genTime())
            assertEquals(0, f.clockCalls.get())
            originalKey = saved.stableStopKey()
            server = f.server
        }
        val reopened: MemoryStore = store.reopened()
        val publisher: Publisher = Publisher()
        publisher.server = server
        val freshRuntime: Runtime = Runtime(true)
        val identityReads: AtomicInteger = AtomicInteger()
        resumeClient(reopened, publisher, server, freshRuntime,
                identityReads, { it.run() }, { 888 }).use { client ->
            assertEquals(MeetingResult.Status.READY, resumeResult(client).get(2, TimeUnit.SECONDS).status())
            assertEquals(listOf(originalKey, originalKey), server.stopKeys)
            assertTrue("发布已 ACK 落盘，恢复不再发布", publisher.contexts.isEmpty())
            assertEquals(1, originalPublisher.contexts.size)
            assertResumeDidNotRestartBusiness(server, freshRuntime, identityReads)
        }
    }

    @Test fun acceptedStopRetainsGenTimeAndOnlyActualManifestCompletesOnConfiguredExecutor() {
        val store: MemoryStore = MemoryStore()
        val publisher: Publisher = Publisher()
        lateinit var server: Server
        Fixture(true, ALL, ReferenceIdentityProvider("test-token"),
                publisher, store).use { f ->
            f.runtime.prepareAudio(audio("mtg_test", 32000))
            f.server.failManifest = true
            f.start(retainedOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT))
            f.session.requestStop()
            f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING)
            val saved = store.read("mtg_test").orElseThrow()
            assertEquals(StoppedMeetingCheckpoint.Phase.STOP_ACCEPTED, saved.phase())
            assertEquals(555L, saved.genTime())
            assertTrue("Stop 受理不能制造结果", f.probe.results.isEmpty())
            server = f.server
        }
        val reopened: MemoryStore = store.reopened()
        val callbacks: BlockingQueue<Runnable> = LinkedBlockingQueue()
        val freshRuntime: Runtime = Runtime(true)
        val identityReads: AtomicInteger = AtomicInteger()
        server.failManifest = false
        resumeClient(reopened, publisher, server, freshRuntime,
                identityReads, callbacks::add, { throw AssertionError("不得重建 genTime"); }).use { client ->
            val result = resumeResult(client)
            val callback: Runnable = callbacks.poll(2, TimeUnit.SECONDS)
            assertNotNull(callback)
            assertFalse("回调执行器运行前不交付结果", result.isDone)
            callback!!.run()
            assertEquals(555, result.get(2, TimeUnit.SECONDS).genTime())
            assertEquals(listOf(555L, 555L), server.genTimes)
            assertEquals(1, server.stopKeys.size)
            assertEquals(1, publisher.contexts.size)
            assertEquals(StoppedMeetingCheckpoint.Phase.READY,
                    reopened.read("mtg_test").orElseThrow().phase())
            assertResumeDidNotRestartBusiness(server, freshRuntime, identityReads)
            // 本地 READY 也不能替代实际 Server 结果；读取失败仍返回错误。
            server.failManifest = true
            val failed = resumeResult(client)
            val failedCallback: Runnable = callbacks.poll(2, TimeUnit.SECONDS)
            assertNotNull(failedCallback)
            failedCallback!!.run()
            assertThrows(ExecutionException::class.java, { failed.get(2, TimeUnit.SECONDS) })
            assertEquals(1, server.stopKeys.size)
        }
    }

    @Test fun checkpointWriteFailureNeverAdvancesToTheNextExternalStep() {
        for (failurePhase in listOf(StoppedMeetingCheckpoint.Phase.PUBLISH_PENDING,
                StoppedMeetingCheckpoint.Phase.PUBLISHED, StoppedMeetingCheckpoint.Phase.STOP_ACCEPTED)) {
            val store: MemoryStore = MemoryStore()
            store.failPhase = failurePhase
            val publisher: Publisher = Publisher()
            val renewals = AtomicInteger()
            val identity = object : IdentityProvider {
                override fun currentIdentity(): IdentityResult {
                    return IdentityResult.loggedIn(IdentitySession("test-token", "原账号"))
                }
                override fun identityForFinalization(id: String, original: IdentitySession): IdentityResult
                        {
                    assertTrue("私有身份扩展核原账号前，首次候选也必须已保存", store.read(id).isPresent())
                    assertEquals("test-token", store.read(id).orElseThrow().context().accessToken())
                    renewals.incrementAndGet()
                    return IdentityResult.loggedIn(original)
                }
            }
            Fixture(true, ALL, identity,
                    publisher, store).use { f ->
                f.runtime.prepareAudio(audio("mtg_test", 32000))
                f.start(retainedOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT))
                f.session.requestStop()
                f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING)
                assertTrue(f.probe.results.isEmpty())
                assertFalse("保存 Stop ACK 前不得读结果", f.server.steps.contains("manifest"))
                if (failurePhase == StoppedMeetingCheckpoint.Phase.PUBLISH_PENDING)
                    assertTrue("候选未持久不得发布", publisher.contexts.isEmpty())
                if (failurePhase != StoppedMeetingCheckpoint.Phase.STOP_ACCEPTED)
                    assertTrue("发布 ACK 未持久不得 Stop", f.server.stopKeys.isEmpty())
                assertEquals("首次停止不续签", 0, renewals.get())
                store.failPhase = null
                f.session.requestStop()
                assertNotNull(f.probe.result())
                assertEquals("重按只续签一次，且记录已存在可核原账号", 1, renewals.get())
                assertEquals(1, f.runtime.openCalls)
                assertEquals(1, f.runtime.artifactReads)
                assertEquals(1, f.runtime.durationReads)
                assertEquals("同进程 Stop ACK 写失败也保持首次 genTime", 1, f.clockCalls.get())
                if (f.server.stopKeys.size == 2)
                    assertEquals(f.server.stopKeys.get(0), f.server.stopKeys.get(1))
            }
        }
    }

    @Test fun checkpointMergePreservesOriginalFactsAndNeverDowngradesAcknowledgedStage() {
        val config = MeetingClientConfig.reference("http://original.example.test", "original-token")
        val sealed = audio("mtg_test", 32000)
        val pending = StoppedMeetingCheckpoint.restore(sealed, 1000, config, "original-key",
                StoppedMeetingCheckpoint.Phase.PUBLISH_PENDING, null)
        val accepted = pending.published().stopAccepted(555)
        assertSame(accepted, accepted.merge(pending))
        assertSame(accepted, pending.merge(accepted))
        assertThrows(IOException::class.java, { accepted.merge(pending.published().stopAccepted(556)) })
        val wrongKey = StoppedMeetingCheckpoint.restore(sealed, 1000, config, "different-key",
                StoppedMeetingCheckpoint.Phase.PUBLISH_PENDING, null)
        assertThrows(IOException::class.java, { accepted.merge(wrongKey) })
        assertFalse(accepted.toString().contains("original-token"))
        assertFalse(accepted.toString().contains("original-key"))
        assertFalse(accepted.toString().contains(sealed.file().path))
        assertThrows(IllegalArgumentException::class.java, { StoppedMeetingCheckpoint.restore(sealed,
                1000, config, "key", StoppedMeetingCheckpoint.Phase.PUBLISH_PENDING, 555L) })
        // 同账号正常续签的 Bearer 是可更新访问材料；较早阶段也不能抹掉已 ACK 的事实。
        val renewedPending = pending.withAccessToken("renewed-scene-token")
        val merged = accepted.merge(renewedPending)
        assertEquals("renewed-scene-token", merged.context().accessToken())
        assertEquals(StoppedMeetingCheckpoint.Phase.STOP_ACCEPTED, merged.phase())
        assertEquals(555L, merged.genTime())
        assertEquals(accepted.stableStopKey(), merged.stableStopKey())
        assertSame(sealed, merged.context().audio())
    }

    @Test fun pendingStopRenewsSameSceneAccessAndDoesNotOverwriteItOnAnotherRetry() {
        val identityReads = AtomicInteger()
        val renewalReads = AtomicInteger()
        val identity = object : IdentityProvider {
            override fun currentIdentity(): IdentityResult {
                identityReads.incrementAndGet()
                return IdentityResult.loggedIn(IdentitySession("expired-scene-token", "原账号"))
            }
            override fun identityForFinalization(id: String, previous: IdentitySession): IdentityResult {
                assertEquals("mtg_test", id)
                val call: Int = renewalReads.incrementAndGet()
                assertEquals(if (call == 1) "expired-scene-token" else "renewed-scene-token", previous.accessToken())
                return IdentityResult.loggedIn(IdentitySession("renewed-scene-token", "原账号"))
            }
        }
        val publisherTokens = ArrayList<String>()
        val publisher = MeetingArtifactPublisher { context ->
            publisherTokens.add(context.accessToken())
            if (publisherTokens.size <= 2) throw IOException("fixture-unconfirmed-publication")
        }
        val store: MemoryStore = MemoryStore()
        Fixture(true, ALL, identity, { it.run() }, publisher, store).use { f ->
            val sealed = audio("mtg_test", 32000)
            f.runtime.prepareAudio(sealed)
            f.start(retainedOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT))
            f.session.requestStop()
            f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING)
            val originalKey: String = store.read("mtg_test").orElseThrow().stableStopKey()
            assertEquals("首次停止不额外续签", 0, renewalReads.get())
            f.session.requestStop()
            f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING)
            assertEquals("renewed-scene-token", store.read("mtg_test").orElseThrow().context().accessToken())
            f.session.requestStop()
            assertEquals(MeetingResult.Status.READY, f.probe.result().status())
            assertEquals(listOf("expired-scene-token", "renewed-scene-token", "renewed-scene-token"), publisherTokens)
            assertEquals("renewed-scene-token", f.sessionToken)
            assertEquals(listOf(originalKey), f.server.stopKeys)
            assertEquals(listOf(1000L), f.server.usages)
            assertSame(sealed, store.read("mtg_test").orElseThrow().context().audio())
            assertEquals(1, identityReads.get())
            assertEquals(2, renewalReads.get())
            assertEquals(1, f.runtime.openCalls)
            assertEquals(1, f.runtime.artifactReads)
            assertEquals(1, f.runtime.durationReads)
            assertEquals(1, Collections.frequency(f.server.steps, "create"))
            assertFalse(f.server.steps.contains("summary"))
        }
    }

    @Test fun expiredSavedAccessResumesOverRealHttpWithRenewedBearerAndOriginalFacts() {
        val http: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = Collections.synchronizedList(ArrayList<String>())
        val stopKeys = Collections.synchronizedList(ArrayList<String>())
        http.createContext("/") { exchange ->
            val path: String = exchange.getRequestURI().path
            val authorization: String = exchange.getRequestHeaders().getFirst("Authorization")
            requests.add(path + "|" + authorization)
            exchange.getRequestBody().readAllBytes()
            val code = if ("Bearer renewed-scene-token" == authorization) 200 else 401
            if (path.endsWith(":stop")) stopKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"))
            val body = if (path.endsWith("/manifest")) "{\"status\":\"READY\",\"title\":\"原场\",\"artifacts\":[]}"
                    else if (path.endsWith(":stop")) "{\"inputState\":\"INPUT_ACCEPTED\",\"manifestState\":\"READY\"}" else "{}"
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.getResponseBody().use { output -> output.write(bytes); }
            exchange.close()
        }
        http.start()
        try {
            val base: String = "http://127.0.0.1:" + http.getAddress().getPort()
            val store: MemoryStore = MemoryStore()
            val sealed = audio("mtg_test", 32000)
            store.save(StoppedMeetingCheckpoint.restore(sealed, 1000,
                    MeetingClientConfig.reference(base, "expired-scene-token"), "original-stable-key",
                    StoppedMeetingCheckpoint.Phase.PUBLISH_PENDING, null))
            val renewals = AtomicInteger()
            val identity = object : IdentityProvider {
                override fun currentIdentity(): IdentityResult { throw AssertionError("不能调用当前账号替代原场"); }
                override fun identityForFinalization(id: String, original: IdentitySession): IdentityResult {
                    assertEquals("mtg_test", id)
                    return IdentityResult.loggedIn(IdentitySession(if (renewals.incrementAndGet() == 1)
                            original.accessToken() else "renewed-scene-token", ""))
                }
            }
            val publisher = MeetingArtifactPublisher { context ->
                assertEquals(sealed.file(), context.audio().file())
                assertEquals(32000, context.audio().capturedPcmBytes())
                assertEquals(1000, context.frozenAudioDurationMs())
                val connection = URI.create(context.serverUrl()
                        + "/v1/meetings/" + context.meetingId() + "/publication:prepare").toURL().openConnection() as HttpURLConnection
                try {
                    connection.setRequestMethod("POST")
                    connection.setRequestProperty("Authorization", "Bearer " + context.accessToken())
                    if (connection.getResponseCode() != 200) throw IOException("fixture-publication-HTTP-401")
                } finally { connection.disconnect(); }
            }
            val config = MeetingClientConfig(base, identity, ClientIdentity.reference(),
                    MeetingProfile.reference(), { it.run() }).withStoppedMeetingStore(store)
                    .withArtifactPublisher(publisher)
            val runtime: Runtime = Runtime(true)
            MeetingCoordinator(config, ModelAdapterRegistry(), runtime,
                    ::HttpMeetingServerClient, { throw AssertionError("不能重采"); }, { 777 }).use { client ->
                assertThrows(ExecutionException::class.java, { resumeResult(client).get(2, TimeUnit.SECONDS) })
                assertEquals(StoppedMeetingCheckpoint.Phase.PUBLISH_PENDING, store.read("mtg_test").orElseThrow().phase())
                assertEquals("expired-scene-token", store.read("mtg_test").orElseThrow().context().accessToken())
                assertEquals(MeetingResult.Status.READY, resumeResult(client).get(2, TimeUnit.SECONDS).status())
                val actual = store.read("mtg_test").orElseThrow()
                assertEquals("renewed-scene-token", actual.context().accessToken())
                assertEquals("original-stable-key", actual.stableStopKey())
                assertEquals(777L, actual.genTime()!!.toLong())
                assertEquals(listOf("original-stable-key"), stopKeys)
                assertEquals(listOf("/v1/meetings/mtg_test/publication:prepare|Bearer expired-scene-token",
                        "/v1/meetings/mtg_test/publication:prepare|Bearer renewed-scene-token",
                        "/v1/meetings/mtg_test:stop|Bearer renewed-scene-token",
                        "/v1/meetings/mtg_test/manifest|Bearer renewed-scene-token"), requests)
                assertEquals(0, runtime.openCalls)
                assertEquals(0, runtime.durationReads)
                assertEquals(0, runtime.artifactReads)
                assertNull(runtime.summaryInput)
                // 已 READY 再读只用已保存新访问，不额外交换身份或再次发布／Stop。
                assertEquals(MeetingResult.Status.READY, resumeResult(client).get(2, TimeUnit.SECONDS).status())
                assertEquals(2, renewals.get())
                assertEquals(1, stopKeys.size)
            }
        } finally { http.stop(0); }
    }

    @Test fun noActiveDeliveryNeverWritesCheckpointOrReadsReadyEvenWhenStoreConfigured() {
        val store: MemoryStore = MemoryStore()
        val publisher: Publisher = Publisher()
        Fixture(true, ALL, ReferenceIdentityProvider("test-token"),
                publisher, store).use { f ->
            f.runtime.prepareAudio(audio("mtg_test", 32000))
            f.start(options(MeetingOptions.Mode.LIVE_TRANSLATION, MeetingOptions.ResultDelivery.TRACELESS,
                    false).withCreateParameters("无痕原行为", "Asia/Shanghai", true))
            f.session.requestStop()
            f.probe.awaitPhase(MeetingSessionState.Phase.ENDED)
            assertTrue(store.read("mtg_test").isEmpty())
            assertTrue(f.server.stopKeys.isEmpty())
            assertEquals(1, Collections.frequency(f.server.steps, "stop"))
            assertFalse(f.server.steps.contains("manifest"))
            assertTrue(f.probe.results.isEmpty())
        }
    }

    @Test fun resumeRequiresConfiguredRecordAndCannotRunAlongsideActiveMeeting() {
        Fixture(true, ALL).use { f ->
            assertThrows(ExecutionException::class.java, { resumeResult(f.client).get(2, TimeUnit.SECONDS) })
            assertTrue(f.server.steps.isEmpty())
        }
        val store: MemoryStore = MemoryStore()
        Fixture(true, ALL, ReferenceIdentityProvider("test-token"),
                Publisher(), store).use { f ->
            assertThrows(ExecutionException::class.java, { resumeResult(f.client).get(2, TimeUnit.SECONDS) })
            assertTrue(f.server.steps.isEmpty())
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, false)
            assertThrows(ExecutionException::class.java, { resumeResult(f.client).get(2, TimeUnit.SECONDS) })
            assertEquals(1, f.runtime.openCalls)
            assertFalse(f.server.steps.contains("stop"))
        }
    }

    private fun resumeResult(client: MeetingClient): CompletableFuture<MeetingResult> {
        val result = CompletableFuture<MeetingResult>()
        client.resumeFinalization("mtg_test", resultCallback(result))
        return result
    }

    /** 重建客户端故意使用另一部署和当前身份；续办必须仍只用磁盘读回的原冻结事实。 */
    private fun resumeClient(store: MemoryStore, publisher: Publisher,
        server: Server, runtime: Runtime, identityReads: AtomicInteger,
        callbacks: java.util.concurrent.Executor, wallClock: java.util.function.LongSupplier): MeetingCoordinator {
        val original = store.read("mtg_test").orElseThrow().context()
        val currentIdentity = IdentityProvider {
            identityReads.incrementAndGet()
            IdentityResult.loggedIn(IdentitySession("another-token", "后来账号"))
        }
        val config = MeetingClientConfig("http://another-deployment.example.test", currentIdentity,
            ClientIdentity("another-app", "2", "v1", "another-device"),
            MeetingProfile.reference(), callbacks).withArtifactPublisher(publisher)
            .withStoppedMeetingStore(store)
        return MeetingCoordinator(config, ModelAdapterRegistry(), runtime,
            java.util.function.Function { resolved ->
                assertEquals(original.serverUrl(), resolved.serverUrl())
                assertEquals(original.accessToken(), resolved.accessToken())
                assertEquals(original.clientIdentity(), resolved.clientIdentity())
                server
            }, { throw AssertionError("恢复不得读取采音时钟") }, wallClock)
    }

    private fun assertResumeDidNotRestartBusiness(server: Server, runtime: Runtime,
        identityReads: AtomicInteger) {
        assertEquals(0, identityReads.get())
        assertEquals(0, runtime.openCalls)
        assertEquals(0, runtime.durationReads)
        assertEquals(0, runtime.artifactReads)
        assertNull(runtime.summaryInput)
        assertEquals(1, Collections.frequency(server.steps, "discover"))
        assertEquals(1, Collections.frequency(server.steps, "create"))
        assertEquals(1, Collections.frequency(server.steps, "grant"))
        assertEquals(1, Collections.frequency(server.steps, "start"))
    }

    @Test fun wrongMeetingDurationOrMissingUsageNeverPublishesOrStops() {
        assertAudioRejected(Optional.of(audio("other-scene", 32000)), OptionalLong.of(1000), null)
        assertAudioRejected(Optional.of(audio("mtg_test", 64000)), OptionalLong.of(1000), null)
        assertAudioRejected(Optional.of(audio("mtg_test", 32000)), OptionalLong.empty(), null)
    }

    @Test fun missingSealedAndSealFailureCannotBecomeZeroFrameSuccessOnRetry() {
        assertAudioRejected(Optional.empty(), OptionalLong.of(0), null)
        assertAudioRejected(Optional.empty(), OptionalLong.of(0), IOException("fixture-seal-failed"))
    }

    private fun assertAudioRejected(artifact: Optional<SealedAudioArtifact>,
        duration: OptionalLong, failure: IOException?) {
        val publisher = Publisher()
        Fixture(true, ALL, ReferenceIdentityProvider("fixture-token"), publisher).use { f ->
            f.runtime.retainedAudioSupported = true
            f.runtime.artifact = artifact
            f.runtime.duration = duration
            f.runtime.artifactFailure = failure
            f.start(retainedOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT))
            f.session.requestStop()
            f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING)
            // 即使不符合约定的运行时后来换值，协调器仍复用第一次收口的失败事实，不重新 seal。
            f.runtime.artifact = Optional.of(audio("mtg_test", 0))
            f.runtime.artifactFailure = null
            f.runtime.duration = OptionalLong.of(0)
            f.session.requestStop()
            f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING)
            assertTrue(publisher.contexts.isEmpty())
            assertFalse(f.server.steps.contains("stop"))
            assertEquals(1, f.runtime.artifactReads)
            assertEquals(1, f.runtime.durationReads)
            assertEquals(1, f.runtime.stopCalls)
            assertEquals(1, f.runtime.openCalls)
        }
    }

    @Test fun audioErrorStopsWithoutClaimingNormalAudioPublication() {
        val publisher: Publisher = Publisher()
        Fixture(true, ALL, ReferenceIdentityProvider("fixture-token"), publisher).use { f ->
            f.runtime.prepareAudio(audio("mtg_test", 32000))
            f.runtime.emitFinished = false
            f.start(retainedOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT))
            f.runtime.listener.onAudioError(AudioInputErrorCode.PERMISSION_DENIED, "fixture-audio-ended")
            f.session.requestStop()
            f.probe.awaitPhase(MeetingSessionState.Phase.ENDED)
            assertEquals(0, f.runtime.artifactReads)
            assertTrue(publisher.contexts.isEmpty())
            assertEquals(1, Collections.frequency(f.server.steps, "stop"))
            assertTrue(f.probe.results.isEmpty())
        }
    }

    @Test fun modelErrorPublishesActuallySealedAudioBeforeBackendStop() {
        val publisher: Publisher = Publisher()
        Fixture(true, ALL, ReferenceIdentityProvider("fixture-token"), publisher).use { f ->
            val sealed: SealedAudioArtifact = audio("mtg_test", 32000)
            f.runtime.prepareAudio(sealed)
            f.runtime.emitFinished = false
            f.start(retainedOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT))
            f.runtime.listener.onError("fixture-model-ended")
            f.session.requestStop()
            assertNotNull(f.probe.result())
            assertEquals(1, f.runtime.artifactReads)
            assertEquals(1, publisher.contexts.size)
            assertSame(sealed, publisher.contexts.get(0).audio())
            assertEquals(1000, publisher.contexts.get(0).frozenAudioDurationMs())
            assertEquals(listOf("discover", "create", "grant", "start", "usage",
                    "publish", "stop", "manifest"), f.server.steps)
        }
    }

    @Test fun modelErrorWithoutSealedAudioCannotBecomeNormalPublicationOrStop() {
        // 分别覆盖运行时没有封口事实和确实封口失败；模型异常不能把二者视作零帧成功。
        for (sealFailure in listOf(null, IOException("fixture-seal-failed"))) {
            val publisher: Publisher = Publisher()
            Fixture(true, ALL, ReferenceIdentityProvider("fixture-token"), publisher).use { f ->
                f.runtime.retainedAudioSupported = true
                f.runtime.duration = OptionalLong.of(1000)
                f.runtime.artifactFailure = sealFailure
                f.runtime.emitFinished = false
                f.start(retainedOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT))
                f.runtime.listener.onError("fixture-model-ended")
                f.session.requestStop()
                f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING)
                assertEquals(1, f.runtime.artifactReads)
                assertTrue(publisher.contexts.isEmpty())
                assertFalse(f.server.steps.contains("stop"))
                assertTrue(f.probe.results.isEmpty())
            }
        }
    }

    @Test fun cancellationDuringQueuedUsagePreventsNewAudioPublicationAndNormalStop() {
        val publisher: Publisher = Publisher()
        Fixture(true, ALL, ReferenceIdentityProvider("fixture-token"), publisher).use { f ->
            f.runtime.prepareAudio(audio("mtg_test", 32000))
            f.server.blockUsage = true
            f.start(retainedOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT))
            f.session.requestStop()
            assertTrue(f.server.usageEntered.await(2, TimeUnit.SECONDS))
            // close同步关公开门，音源控制队列释放模型，不用等挂起的用量HTTP。
            f.client.close()
            assertTrue(f.runtime.closed.await(2, TimeUnit.SECONDS))
            assertEquals(1, f.server.releaseUsage.count)
            f.server.releaseUsage.countDown()
            assertTrue(publisher.contexts.isEmpty())
            assertEquals(0, f.runtime.artifactReads)
            assertFalse(f.server.steps.contains("stop"))
            assertTrue(f.probe.results.isEmpty())
        }
    }

    @Test fun sealedAudioUsesCapturedPcmDurationAndRedactsLocalPosition() {
        val opus = SealedAudioArtifact("mtg_test", File("fixture-audio.opus"),
                SealedAudioArtifact.Format.OPUS_OGG, 17, 32640)
        assertEquals("audio/ogg", opus.contentType())
        assertEquals(17, opus.encodedBytes())
        assertEquals(1020, opus.durationMs())
        assertEquals(16000, opus.sampleRateHz())
        assertEquals(1, opus.channelCount())
        assertEquals(16, opus.bitsPerSample())
        assertFalse(opus.toString().contains("fixture-audio.opus"))
        assertEquals(0, audio("mtg_test", 0).durationMs())
        assertThrows(IllegalArgumentException::class.java, { audio("", 0) })
        assertThrows(IllegalArgumentException::class.java, { audio("mtg_test", -2) })
        assertThrows(IllegalArgumentException::class.java, { audio("mtg_test", 1) })
        assertThrows(IllegalArgumentException::class.java, { SealedAudioArtifact("mtg_test",
                File("fixture.pcm"), SealedAudioArtifact.Format.PCM16, 1, 0) })
    }

    private fun audio(meetingId: String, pcmBytes: Long): SealedAudioArtifact =
        SealedAudioArtifact(meetingId, File("fixture-audio.pcm"),
            SealedAudioArtifact.Format.PCM16, pcmBytes, pcmBytes)

    private fun retainedOptions(mode: MeetingOptions.Mode): MeetingOptions =
        options(mode, MeetingOptions.ResultDelivery.WEB, false)
            .withCreateParameters("同源录音测试", "Asia/Shanghai", true)

    @Test fun sameStreamLevelUsesCallbackExecutorAndQueuedLevelStopsWithNormalStop() {
        val callbacks: BlockingQueue<Runnable> = LinkedBlockingQueue()
        Fixture(true, setOf(MeetingCapabilityState.TRANSCRIPT),
                ReferenceIdentityProvider("test-token"), callbacks::add).use { f ->
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, false)
            var task: Runnable?
            while (true) { task = callbacks.poll() ?: break; task!!.run() }
            val level: AudioInputLevel = AudioInputLevel(50, 1200)
            f.runtime.listener.onAudioLevel(level)
            task = callbacks.poll(2, TimeUnit.SECONDS)
            assertNotNull(task)
            assertTrue("配置的执行器派发前不进入宿主", f.probe.levels.isEmpty())
            task.run()
            assertSame(level, f.probe.levels.poll(2, TimeUnit.SECONDS))
            f.runtime.listener.onAudioLevel(AudioInputLevel(80, 1500))
            val queuedLevel: Runnable = callbacks.poll(2, TimeUnit.SECONDS)
            assertNotNull(queuedLevel)
            f.session.requestStop()
            val stoppingState: Runnable = callbacks.poll(2, TimeUnit.SECONDS)
            assertNotNull(stoppingState); // 状态队列已经受理正常 Stop 并关电平门。
            queuedLevel!!.run()
            assertTrue(f.probe.levels.isEmpty())
            stoppingState!!.run()
            f.probe.awaitPhase(MeetingSessionState.Phase.STOPPING)
            while (f.probe.results.isEmpty()) {
                task = callbacks.poll(3, TimeUnit.SECONDS)
                assertNotNull("正常停止应继续完成READY", task)
                task.run()
            }
            assertNotNull(f.probe.result())
            f.runtime.listener.onAudioLevel(AudioInputLevel(100, 1800))
            while (true) { task = callbacks.poll(100, TimeUnit.MILLISECONDS) ?: break; task.run() }
            assertTrue(f.probe.levels.isEmpty())
            assertEquals(1, f.runtime.stopCalls)
        }
    }

    @Test fun missingIdentityIsVisibleAndNeverCreatesMeeting() {
        Fixture(false, setOf(MeetingCapabilityState.TRANSCRIPT),
                ReferenceIdentityProvider()).use { f ->
            val refreshed: BlockingQueue<IdentityResult> = LinkedBlockingQueue()
            f.client.refreshIdentity(identityCallback(refreshed))
            assertEquals(IdentityResult.Status.LOGIN_REQUIRED, refreshed.poll(2, TimeUnit.SECONDS).status())
            f.session = f.client.start(options(MeetingOptions.Mode.LIVE_TRANSCRIPT,
                    MeetingOptions.ResultDelivery.WEB, false), f.probe)
            assertEquals("请先登录", f.probe.awaitPhase(MeetingSessionState.Phase.FAILED).message())
            assertTrue(f.server.steps.isEmpty())
            assertNull(f.sessionToken)
        }
    }

    @Test fun meetingReadsLatestIdentityAndKeepsItsCredentialSnapshot() {
        val identity: ReferenceIdentityProvider = ReferenceIdentityProvider()
        identity.updateSession(IdentitySession("initial-fixture", "参考用户"))
        Fixture(false, setOf(MeetingCapabilityState.TRANSCRIPT), identity).use { f ->
            val refreshed: BlockingQueue<IdentityResult> = LinkedBlockingQueue()
            f.client.refreshIdentity(identityCallback(refreshed))
            assertEquals("initial-fixture", refreshed.poll(2, TimeUnit.SECONDS).session()!!.accessToken())
            identity.updateSession(IdentitySession("latest-fixture", "参考用户"))
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, false)
            assertEquals("latest-fixture", f.sessionToken)
            identity.clearSession()
            f.client.refreshIdentity(identityCallback(refreshed))
            assertEquals(IdentityResult.Status.LOGIN_REQUIRED, refreshed.poll(2, TimeUnit.SECONDS).status())
            assertEquals("latest-fixture", f.sessionToken)
            f.runtime.emit("s1", 1, "zh-CN", "最终字幕", true)
            f.probe.awaitCanStop()
            f.session.requestStop()
            assertNotNull(f.probe.result())
        }
    }

    @Test fun identityReadFailureReturnsTypedMessageWithoutStartingRuntime() {
        Fixture(false, setOf(MeetingCapabilityState.TRANSCRIPT),
                { throw IOException("fixture-read-unavailable"); }).use { f ->
            val refreshed: BlockingQueue<IdentityResult> = LinkedBlockingQueue()
            f.client.refreshIdentity(identityCallback(refreshed))
            val identity: IdentityResult = refreshed.poll(2, TimeUnit.SECONDS)
            assertEquals(IdentityResult.Status.READ_FAILED, identity.status())
            assertEquals("读取登录状态失败，请稍后重试", identity.message())
            f.session = f.client.start(options(MeetingOptions.Mode.LIVE_TRANSCRIPT,
                    MeetingOptions.ResultDelivery.WEB, false), f.probe)
            assertEquals(identity.message(), f.probe.awaitPhase(MeetingSessionState.Phase.FAILED).message())
            assertTrue(f.server.steps.isEmpty())
            assertEquals(1, f.runtime.started.count)
        }
    }

    private fun identityCallback(results: BlockingQueue<IdentityResult>): MeetingClient.Callback<IdentityResult> =
        object : MeetingClient.Callback<IdentityResult> {
            override fun onSuccess(result: IdentityResult) { results.add(result) }
            override fun onError(message: String) { fail(message) }
        }

    @Test fun mockMinutesSharesCoordinatorAndKeepsSummaryCapabilityAndFinalCaptureOrder() {
        Fixture(false, setOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SUMMARY)).use { f ->
            f.start(MeetingOptions.Mode.MEETING_MINUTES, MeetingOptions.ResultDelivery.LOCAL, true)
            f.runtime.emit("s1", 1, "zh-CN", "初稿", false)
            f.runtime.emit("s1", 2, "zh-CN", "最终字幕", true)
            f.probe.awaitCanStop()
            f.probe.beforeStop = java.util.function.Consumer { continuation ->
                val jpeg = byteArrayOf(1, 2)
                f.session.submitPresentation(1000, jpeg)
                jpeg[0] = 9
                continuation.run()
            }
            f.session.requestStop()
            val result: MeetingResult = f.probe.result()
            assertNull(f.runtime.grant)
            assertNull(f.runtime.adapter)
            assertTrue(f.probe.info!!.availableCapabilities().contains(MeetingCapabilityState.SUMMARY))
            assertEquals("最终字幕", f.runtime.summaryInput!!.transcript())
            assertEquals(listOf("discover", "create", "start", "event1", "event2", "capture1", "summary", "stop", "manifest"), f.server.steps)
            assertNull(f.server.transcripts.get(0).sessionSpeakerId())
            assertArrayEquals(byteArrayOf(1, 2), f.server.jpeg)
            assertEquals(MeetingResult.DeliveryStatus.NOT_REQUESTED, result.deliveryStatus())
            assertNull(result.content())
            assertFalse(f.server.includeContent)
            f.probe.awaitPhase(MeetingSessionState.Phase.READY)
            val content = CompletableFuture<MeetingResultContent?>()
            f.session.resultActionAccess().readContent(resultCallback(content))
            assertEquals("最终字幕", content.get(2, TimeUnit.SECONDS)!!.transcriptText())
            assertEquals(555L, result.genTime())
        }
    }

    @Test fun presentationAckUsesCallbackExecutorAndOwnsSubmittedJpeg() {
        val callbacks: BlockingQueue<Runnable> = LinkedBlockingQueue()
        Fixture(true, ALL,
                ReferenceIdentityProvider("test-token"), callbacks::add).use { f ->
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, true)
            var queued: Runnable?
            while (true) { queued = callbacks.poll() ?: break; queued!!.run() }
            val ack: CompletableFuture<Void?> = CompletableFuture()
            val jpeg = byteArrayOf(1, 2)
            f.session.submitPresentation(1000, jpeg, resultCallback(ack))
            jpeg[0] = 9
            queued = callbacks.poll(2, TimeUnit.SECONDS)
            assertNotNull("保存确认应交给配置的回调Executor", queued)
            assertFalse("不能在HTTP/状态线程直接回调App", ack.isDone)
            queued!!.run()
            ack.get(2, TimeUnit.SECONDS)
            assertEquals(1, f.server.captures.size)
            assertEquals(1, f.server.captures.get(0).sequence())
            assertArrayEquals(byteArrayOf(1, 2), f.server.captures.get(0).jpeg())
        }
    }

    @Test fun unconfirmedCaptureReplaysSameBytesBeforeNextFrameAndSequence() {
        Fixture(true, ALL).use { f ->
            f.server.failPresentationCalls = 1
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, true)
            val first: CompletableFuture<Void?> = CompletableFuture()
            val original = byteArrayOf(1, 2)
            f.session.submitPresentation(1000, original, resultCallback(first))
            original[0] = 9
            assertThrows(ExecutionException::class.java, { first.get(2, TimeUnit.SECONDS) })
            val next: CompletableFuture<Void?> = CompletableFuture()
            f.session.submitPresentation(2000, byteArrayOf(3), resultCallback(next))
            next.get(2, TimeUnit.SECONDS)
            assertEquals(listOf(1, 1, 2), f.server.captures.stream().map(Capture::sequence).toList())
            assertEquals(1000, f.server.captures.get(0).offsetMs())
            assertEquals(1000, f.server.captures.get(1).offsetMs())
            assertArrayEquals(byteArrayOf(1, 2), f.server.captures.get(0).jpeg())
            assertArrayEquals(f.server.captures.get(0).jpeg(), f.server.captures.get(1).jpeg())
            assertArrayEquals(byteArrayOf(3), f.server.captures.get(2).jpeg())
            f.session.requestStop()
            assertNotNull(f.probe.result())
            assertEquals(1, f.runtime.stopCalls)
        }
    }

    @Test fun failedReplayKeepsOriginalFrameAndNeverOverwritesItsSequence() {
        Fixture(true, ALL).use { f ->
            f.server.failPresentationCalls = 2
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, true)
            val first: CompletableFuture<Void?> = CompletableFuture()
            f.session.submitPresentation(1000, byteArrayOf(1), resultCallback(first))
            assertThrows(ExecutionException::class.java, { first.get(2, TimeUnit.SECONDS) })
            val discarded: CompletableFuture<Void?> = CompletableFuture()
            f.session.submitPresentation(2000, byteArrayOf(2), resultCallback(discarded))
            assertThrows(ExecutionException::class.java, { discarded.get(2, TimeUnit.SECONDS) })
            assertEquals(listOf(1, 1), f.server.captures.stream().map(Capture::sequence).toList())
            val confirmed: CompletableFuture<Void?> = CompletableFuture()
            f.session.submitPresentation(3000, byteArrayOf(3), resultCallback(confirmed))
            confirmed.get(2, TimeUnit.SECONDS)
            assertEquals(listOf(1, 1, 1, 2), f.server.captures.stream().map(Capture::sequence).toList())
            for (i in 0 until 3) {
                assertEquals(1000, f.server.captures.get(i).offsetMs())
                assertArrayEquals(byteArrayOf(1), f.server.captures.get(i).jpeg())
            }
            assertEquals(3000, f.server.captures.get(3).offsetMs())
            assertArrayEquals(byteArrayOf(3), f.server.captures.get(3).jpeg())
        }
    }

    @Test fun oversizedCaptureUsesNoSequenceAndNextValidFrameStillStopsNormally() {
        Fixture(true, ALL).use { f ->
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, true)
            val oversized: CompletableFuture<Void?> = CompletableFuture()
            // 请求1048573B尚在预算内，保存正文补schemaVersion后1048615B超限；
            // 必须在保留原包前发现，不能卡住后续正常帧的序号。
            f.session.submitPresentation(10000, ByteArray(786381), resultCallback(oversized))
            val failure: ExecutionException = assertThrows(ExecutionException::class.java,
                    { oversized.get(2, TimeUnit.SECONDS) })
            assertTrue(failure.cause!!.message!!.contains("1MiB"))
            assertTrue(f.server.captures.isEmpty())
            val valid: CompletableFuture<Void?> = CompletableFuture()
            f.session.submitPresentation(2000, byteArrayOf(1), resultCallback(valid))
            valid.get(2, TimeUnit.SECONDS)
            assertEquals(listOf(1), f.server.captures.stream().map(Capture::sequence).toList())
            assertEquals(2000, f.server.captures.get(0).offsetMs())
            f.session.requestStop()
            assertNotNull(f.probe.result())
            assertEquals(1, f.runtime.stopCalls)
        }
    }

    @Test fun failedFinalCaptureContinuesBeforeStopAndDoesNotPoisonTextResult() {
        Fixture(true, ALL).use { f ->
            f.server.failPresentationCalls = 1
            f.runtime.emitTail = true
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, true)
            val captureErrors: AtomicInteger = AtomicInteger()
            f.probe.beforeStop = java.util.function.Consumer { continuation ->
                f.session.submitPresentation(1000, byteArrayOf(1),
                    object : MeetingClient.Callback<Void?> {
                        override fun onSuccess(ignored: Void?) { continuation.run(); }
                        override fun onError(message: String) {
                            captureErrors.incrementAndGet()
                            continuation.run()
                        }
                    })
            }
            f.session.requestStop()
            assertNotNull(f.probe.result())
            assertEquals(1, captureErrors.get())
            assertEquals(1, f.probe.beforeStopCalls.get())
            assertEquals(1, f.runtime.stopCalls)
            assertEquals(listOf("discover", "create", "grant", "start", "capture1",
                    "event1", "stop", "manifest"), f.server.steps)
            assertEquals("最后字幕", f.server.transcripts.get(0).text())
            f.probe.awaitPhase(MeetingSessionState.Phase.READY)
        }
    }

    @Test fun serverGrantChoosesFreshAdapterAndTailEventsAreSavedBeforeStop() {
        Fixture(true, setOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION)).use { f ->
            f.runtime.emitTail = true
            f.runtime.duration = OptionalLong.of(0)
            f.start(MeetingOptions.Mode.LIVE_TRANSLATION, MeetingOptions.ResultDelivery.WEB, true)
            assertEquals(TingwuModelAdapter.ID, f.runtime.adapter!!.id())
            assertEquals("wss://model.example.test/signed", f.runtime.grant!!.endpoint())
            f.probe.beforeStop = java.util.function.Consumer { continuation -> f.session.submitPresentation(1500, byteArrayOf(3)); continuation.run(); }
            f.session.requestStop()
            val result: MeetingResult = f.probe.result()
            assertEquals(listOf("discover", "create", "grant", "start", "capture1", "event1", "translation2", "usage", "stop", "manifest"), f.server.steps)
            assertEquals(listOf(0L), f.server.usages)
            assertFalse(f.server.includeContent)
            assertEquals(MeetingResult.DeliveryStatus.NOT_REQUESTED, result.deliveryStatus())
            assertNull(result.resultUrl())
            assertNull(result.content())
            assertEquals(555L, result.genTime())
            assertEquals(1, f.runtime.stopCalls)
            assertEquals(1, f.probe.beforeStopCalls.get())
            assertTrue(f.probe.info!!.startedAtMs() > 0)
            assertEquals(600000L, f.probe.info!!.maxMeetingDurationMs())
        }
    }

    @Test fun translationSummaryWaitsForTailAndSavesBothBeforeStop() {
        Fixture(true, ALL).use { f ->
            f.runtime.emitTail = true
            f.runtime.duration = OptionalLong.of(1000)
            f.session = f.client.start(MeetingOptions(MeetingOptions.Mode.LIVE_TRANSLATION,
                    "zh-CN", "en-US", false, MeetingOptions.ResultDelivery.WEB,
                    "https://result.example.test", listOf(), true), f.probe)
            assertTrue(f.runtime.started.await(2, TimeUnit.SECONDS))
            f.session.requestStop()
            assertNotNull(f.probe.result())
            assertTrue(f.probe.info!!.availableCapabilities().contains(MeetingCapabilityState.TRANSLATION))
            assertTrue(f.probe.info!!.availableCapabilities().contains(MeetingCapabilityState.SUMMARY))
            assertEquals("最后字幕", f.runtime.summaryInput!!.transcript())
            assertEquals(listOf("discover", "create", "grant", "start", "event1", "translation2",
                    "usage", "summaryGrant", "summary", "stop", "manifest"), f.server.steps)
            assertEquals(listOf(1000L), f.server.usages)
            assertTrue(f.server.requested.summaryEnabled())
        }
    }

    @Test fun checkedSummaryIsRequiredAlongsideTranslation() {
        Fixture(true, setOf(MeetingCapabilityState.TRANSCRIPT,
                MeetingCapabilityState.TRANSLATION)).use { f ->
            f.session = f.client.start(MeetingOptions(MeetingOptions.Mode.LIVE_TRANSLATION,
                    "zh-CN", "en-US", false, MeetingOptions.ResultDelivery.WEB,
                    "https://result.example.test", listOf(), true), f.probe)
            assertEquals(MeetingSessionState.Phase.FAILED,
                    f.probe.awaitPhase(MeetingSessionState.Phase.FAILED).phase())
            assertEquals(listOf("discover", "create"), f.server.steps)
            assertEquals(1, f.runtime.started.count)
        }
    }

    @Test fun partnerDeferredLanguageUsesAdapterDeclarationWithoutTingwuId() {
        val partnerId: String = "partner-delayed-language"
        val capabilities: Set<String> = setOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION)
        Fixture(true, capabilities).use { f ->
            f.server.adapterId = partnerId
            f.adapters.register(partnerId, { object : ModelAdapter {
                override fun id(): String { return partnerId; }
                override fun mayEmitDeferredSourceLanguage(): Boolean { return true; }
                override fun startMessage(grant: ModelSessionGrant): String = "{}"
                override fun onServerMessage(message: String, listener: TranscriptListener): ModelSignal {
                    return ModelSignal.NONE
                }
            } }, capabilities)
            f.start(MeetingOptions.Mode.LIVE_TRANSLATION, MeetingOptions.ResultDelivery.WEB, false)
            assertEquals(partnerId, f.runtime.adapter!!.id())
            assertFalse(TingwuModelAdapter.ID.equals(f.runtime.grant!!.adapterId()))
            assertTrue(f.probe.display().rows().isEmpty()); // 先读走本场初始显示快照。

            // 只测组合层的中性声明；复用现有Runtime直接交付归一化事件，不另模拟私有协议。
            f.runtime.listener.onAudioDirection(AudioDirectionSample(90f, 90f, 0, AudioDirectionSample.Status.AVAILABLE))
            f.runtime.listener.onSegmentStarted(SegmentStartedEvent("partner-segment", 0))
            f.runtime.emit("partner-segment", 1, "und", "Hello", false)
            assertTrue("伙伴未知语种应进入既有等待窗口", f.probe.display().rows().isEmpty())
            f.runtime.emit("partner-segment", 2, "en-US", "Hello", false)
            val shown: MeetingDisplaySnapshot = f.probe.visible()
            assertEquals("en-US", shown.rows().get(0).transcript()!!.language())
            assertEquals(MeetingDisplaySnapshot.Side.B, shown.rows().get(0).sourceSide())
        }
    }

    @Test fun blockedHttpDoesNotBlockActivityBeginDirectionDeadlineOrSnapshotSwap() {
        Fixture(true, setOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION)).use { f ->
            f.server.blockAppend = true
            f.start(MeetingOptions.Mode.LIVE_TRANSLATION, MeetingOptions.ResultDelivery.WEB, false)
            f.runtime.listener.onAudioDirection(AudioDirectionSample(90f, 90f, 0, AudioDirectionSample.Status.AVAILABLE))
            f.runtime.listener.onSegmentStarted(SegmentStartedEvent("s1", 0))
            f.runtime.emit("s1", 1, "und", "Hello", false)
            assertTrue(f.server.appendEntered.await(1, TimeUnit.SECONDS))
            assertTrue(f.probe.activity.await(1, TimeUnit.SECONDS))
            val shown: MeetingDisplaySnapshot = f.probe.visible()
            assertEquals("und", shown.rows().get(0).transcript()!!.language())
            assertEquals(MeetingDisplaySnapshot.Side.B, shown.rows().get(0).sourceSide())
            assertEquals("s1", shown.latestTranscriptSegmentId())
            assertEquals(1, f.server.releaseAppend.count)
            f.session.swapDisplayColumns()
            val swapped: MeetingDisplaySnapshot = f.probe.display()
            assertTrue(swapped.swapped())
            assertFalse(shown.swapped())
            f.runtime.emit("s2", 1, "und", "末尾快照", true)
            f.runtime.listener.onFinished()
            val flushed: MeetingDisplaySnapshot = f.probe.visibleWithRows(2)
            assertEquals("s2", flushed.latestTranscriptSegmentId())
            f.server.releaseAppend.countDown()
        }
    }

    @Test fun missingMandatoryTranscriptDoesNotRequestMaterialsOrOpenRuntime() {
        Fixture(true, setOf(MeetingCapabilityState.TRANSLATION)).use { f ->
            f.session = f.client.start(options(MeetingOptions.Mode.LIVE_TRANSLATION, MeetingOptions.ResultDelivery.WEB, false), f.probe)
            assertEquals(MeetingSessionState.Phase.FAILED, f.probe.awaitPhase(MeetingSessionState.Phase.FAILED).phase())
            assertEquals(listOf("discover", "create"), f.server.steps)
            assertEquals(1, f.runtime.started.count)
            assertNull(f.probe.info)
        }
    }

    @Test fun stopPendingThenEmailFailureUsesOneAcceptedGenTimeAndSameLink() {
        Fixture(false, setOf(MeetingCapabilityState.TRANSCRIPT)).use { f ->
            f.server.pendingStop = true
            f.server.failEmail = true
            f.runtime.duration = OptionalLong.of(1000)
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.EMAIL, false)
            f.runtime.emit("s1", 1, "zh-CN", "最终字幕", true)
            f.probe.awaitCanStop()
            f.session.requestStop()
            f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING)
            assertEquals(0, f.clockCalls.get())
            assertTrue(f.server.genTimes.isEmpty())
            f.runtime.duration = OptionalLong.of(2000); // 重试不重新读取／生成本场用量。
            f.session.requestStop()
            val result: MeetingResult = f.probe.result()
            assertEquals(MeetingResult.DeliveryStatus.NOT_REQUESTED, result.deliveryStatus())
            assertNull(result.resultUrl())
            assertFalse(f.server.steps.contains("email"))
            f.probe.awaitPhase(MeetingSessionState.Phase.READY)
            val failed = CompletableFuture<Void?>()
            val link: String = "https://result.example.test/?meetingId=" + result.meetingId() +
                    "&genTime=" + result.genTime() + "#section"
            f.session.resultActionAccess().sendEmail(listOf("recipient@example.test"), link, resultCallback(failed))
            assertThrows(java.util.concurrent.ExecutionException::class.java, { failed.get(2, TimeUnit.SECONDS) })
            assertEquals(link, f.server.emailUrl)
            assertTrue(f.probe.states.isEmpty())
            assertEquals(listOf(555L), f.server.genTimes)
            assertEquals(1, f.clockCalls.get())
            assertEquals(1, f.runtime.stopCalls)
            assertEquals(1, f.probe.beforeStopCalls.get())
            assertEquals(1, f.runtime.durationReads)
            assertEquals(listOf(1000L), f.server.usages)
        }
    }

    @Test fun usageConfirmationRetryKeepsFrozenSnapshotBeforeSummaryAndStop() {
        Fixture(true, ALL).use { f ->
            f.runtime.emitTail = true
            f.runtime.duration = OptionalLong.of(1000)
            f.server.failUsageOnce = true
            f.start(MeetingOptions.Mode.MEETING_MINUTES, MeetingOptions.ResultDelivery.WEB, false)
            f.session.requestStop()
            f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING)
            assertFalse(f.server.steps.contains("summary"))
            assertFalse(f.server.steps.contains("stop"))
            f.runtime.duration = OptionalLong.of(2000)
            f.session.requestStop()
            assertNotNull(f.probe.result())
            assertEquals(listOf("discover", "create", "grant", "start", "event1", "usage", "usage",
                    "summaryGrant", "summary", "stop", "manifest"), f.server.steps)
            assertEquals(listOf(1000L, 1000L), f.server.usages)
            assertEquals(1, f.runtime.durationReads)
            assertEquals(1, f.runtime.stopCalls)
        }
    }

    @Test fun tracelessEndsWithoutClaimingReadyOrActivelyDeliveringResults() {
        Fixture(false, setOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION)).use { f ->
            f.start(MeetingOptions.Mode.LIVE_TRANSLATION, MeetingOptions.ResultDelivery.TRACELESS, false)
            f.runtime.emit("s1", 1, "zh-CN", "最终字幕", true)
            f.probe.awaitCanStop()
            f.session.requestStop()
            assertFalse(f.probe.awaitPhase(MeetingSessionState.Phase.ENDED).active())
            assertFalse(f.server.steps.contains("manifest"))
            assertTrue(f.probe.results.isEmpty())
        }
    }

    /** 保留旧 App StopRule 覆盖：音频终止后用户正常停止，不等待已关闭模型的 FINISHED。 */
    @Test fun terminatedAudioCanStopWithoutModelFinishedCallback() {
        Fixture(true, setOf(MeetingCapabilityState.TRANSCRIPT)).use { f ->
            f.runtime.emitFinished = false
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, false)
            assertTrue(f.runtime.waitsForModelFinish())
            f.probe.awaitCanStop()
            f.runtime.listener.onAudioError(AudioInputErrorCode.PERMISSION_DENIED, "测试音频输入已终止")
            f.session.requestStop()
            val ended: MeetingSessionState = f.probe.awaitPhase(MeetingSessionState.Phase.ENDED)
            assertFalse(ended.active())
            assertFalse(ended.canStop())
            assertEquals(1, f.runtime.stopCalls)
            assertEquals(1, f.probe.beforeStopCalls.get())
            assertEquals(listOf("discover", "create", "grant", "start", "stop"), f.server.steps)
            assertFalse(f.server.steps.contains("manifest"))
            assertTrue(f.probe.results.isEmpty())
        }
    }

    private fun options(mode: MeetingOptions.Mode, delivery: MeetingOptions.ResultDelivery,
        capture: Boolean): MeetingOptions =
        MeetingOptions(mode, "zh-CN", "en-US", capture, delivery,
            "https://result.example.test/index.html?existing=1#section", listOf("recipient@example.test"))

    @Test fun readyAccessKeepsSameResultAfterEmailFailureAndManualExecution() {
        val identity: ReferenceIdentityProvider = ReferenceIdentityProvider("same-scene-fixture")
        Fixture(false, setOf(MeetingCapabilityState.TRANSCRIPT), identity).use { f ->
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, false)
            f.runtime.emit("s1", 1, "zh-CN", "最终字幕", true)
            f.probe.awaitCanStop()
            f.session.requestStop()
            val result: MeetingResult = f.probe.result()
            f.probe.awaitPhase(MeetingSessionState.Phase.READY)
            identity.clearSession()
            val content = CompletableFuture<MeetingResultContent?>()
            f.session.resultActionAccess().readContent(resultCallback(content))
            assertEquals("最终字幕", content.get(2, TimeUnit.SECONDS)!!.transcriptText())
            f.server.failEmail = true
            val failed = CompletableFuture<Void?>()
            f.session.resultActionAccess().sendEmail(listOf("recipient@example.test"), "https://result.example.test/?meetingId=" + result.meetingId() + "&genTime=" + result.genTime(), resultCallback(failed))
            assertThrows(java.util.concurrent.ExecutionException::class.java, { failed.get(2, TimeUnit.SECONDS) })
            f.server.failEmail = false
            val sent = CompletableFuture<Void?>()
            f.session.resultActionAccess().sendEmail(listOf("recipient@example.test"), "https://result.example.test/?meetingId=" + result.meetingId() + "&genTime=" + result.genTime(), resultCallback(sent))
            sent.get(2, TimeUnit.SECONDS)
            assertEquals("same-scene-fixture", f.sessionToken)
            assertEquals(MeetingResult.Status.READY, result.status())
            assertEquals(listOf(555L, 555L), f.server.genTimes)
            assertEquals(1, f.clockCalls.get())
            assertEquals(1, f.runtime.stopCalls)
            assertEquals(1, Collections.frequency(f.server.steps, "stop"))
            assertEquals(2, Collections.frequency(f.server.steps, "email"))
            assertTrue("结果访问不会更新会议阶段", f.probe.states.isEmpty())
        }
    }

    @Test fun resultAccessBeforeReadyNeverStartsResultProtocol() {
        Fixture(false, setOf(MeetingCapabilityState.TRANSCRIPT)).use { f ->
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, false)
            val beforeReady = CompletableFuture<MeetingResultContent?>()
            f.session.resultActionAccess().readContent(resultCallback(beforeReady))
            assertThrows(java.util.concurrent.ExecutionException::class.java, { beforeReady.get(2, TimeUnit.SECONDS) })
            assertFalse(f.server.steps.contains("manifest"))
            assertFalse(f.server.steps.contains("stop"))
        }
    }

    @Test fun partnerStringActionOnlyReturnsManifestAndNeverLoadsOrNotifies() {
        Fixture(false, setOf(MeetingCapabilityState.TRANSCRIPT)).use { f ->
            f.session = f.client.start(MeetingOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT,
                    "zh-CN", "en-US", false,
                    com.czur.aimeeting.client.result.ResultOptions.action("partner.notice"),
                    "", listOf(), false), f.probe)
            assertTrue(f.runtime.started.await(2, TimeUnit.SECONDS))
            f.runtime.emit("s1", 1, "zh-CN", "最终字幕", true)
            f.probe.awaitCanStop()
            f.session.requestStop()
            val result: MeetingResult = f.probe.result()
            f.probe.awaitPhase(MeetingSessionState.Phase.READY)
            assertNull(result.content())
            assertNull(result.resultUrl())
            assertFalse(f.server.includeContent)
            assertFalse(f.server.steps.contains("email"))
            assertEquals(1, Collections.frequency(f.server.steps, "stop"))
        }
    }

    @Test fun contentFailureCanReadSameReadyAgainWithoutRepeatingSummaryStopOrUsage() {
        Fixture(false, ALL).use { f ->
            f.runtime.duration = OptionalLong.of(1000)
            f.start(MeetingOptions.Mode.MEETING_MINUTES, MeetingOptions.ResultDelivery.LOCAL, false)
            f.runtime.emit("s1", 1, "zh-CN", "最终字幕", true)
            f.probe.awaitCanStop()
            f.session.requestStop()
            val ready: MeetingResult = f.probe.result()
            f.probe.awaitPhase(MeetingSessionState.Phase.READY)
            f.server.failContent = true
            val failed = CompletableFuture<MeetingResultContent?>()
            f.session.resultActionAccess().readContent(resultCallback(failed))
            assertThrows(java.util.concurrent.ExecutionException::class.java, { failed.get(2, TimeUnit.SECONDS) })
            f.server.failContent = false
            val retried = CompletableFuture<MeetingResultContent?>()
            f.session.resultActionAccess().readContent(resultCallback(retried))
            assertEquals("最终字幕", retried.get(2, TimeUnit.SECONDS)!!.transcriptText())
            assertEquals(MeetingResult.Status.READY, ready.status())
            assertEquals(listOf(555L, 555L, 555L), f.server.genTimes)
            assertEquals(1, Collections.frequency(f.server.steps, "summary"))
            assertEquals(1, Collections.frequency(f.server.steps, "stop"))
            assertEquals(1, Collections.frequency(f.server.steps, "usage"))
            assertEquals(1, f.runtime.stopCalls)
            assertTrue(f.probe.states.isEmpty())
        }
    }


    /** 回调沿用原方法式合同；Void 成功由调用方显式使用 Void? 接收 null。 */
    private fun <T> resultCallback(future: CompletableFuture<T>): MeetingClient.Callback<T> =
        object : MeetingClient.Callback<T> {
            override fun onSuccess(result: T) { future.complete(result) }
            override fun onError(message: String) { future.completeExceptionally(IOException(message)) }
        }

    private class Fixture(
        real: Boolean,
        granted: Set<String>,
        identity: IdentityProvider = ReferenceIdentityProvider("test-token"),
        callbackExecutor: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() },
        publisher: MeetingArtifactPublisher = MeetingArtifactPublisher.none(),
        store: StoppedMeetingStore? = null,
    ) : AutoCloseable {
        val server = Server(granted)
        val runtime = Runtime(real)
        val probe = Probe()
        val adapters = ModelAdapterRegistry()
        val clockCalls = AtomicInteger()
        val client: MeetingCoordinator
        val config: MeetingClientConfig
        @Volatile var sessionToken: String? = null
        lateinit var session: MeetingSession

        constructor(real: Boolean, granted: Set<String>, identity: IdentityProvider, publisher: Publisher) :
            this(real, granted, identity, java.util.concurrent.Executor { it.run() }, publisher)
        constructor(real: Boolean, granted: Set<String>, identity: IdentityProvider, publisher: Publisher,
            store: StoppedMeetingStore) :
            this(real, granted, identity, java.util.concurrent.Executor { it.run() }, publisher, store)

        init {
            if (publisher is Publisher) publisher.server = server
            val configured = MeetingClientConfig("http://127.0.0.1", identity,
                ClientIdentity.reference(), MeetingProfile.reference(), callbackExecutor)
                .withArtifactPublisher(publisher)
            config = if (store == null) configured else configured.withStoppedMeetingStore(store)
            client = MeetingCoordinator(config, adapters, runtime,
                java.util.function.Function { resolved ->
                    sessionToken = resolved.accessToken()
                    server
                }, { System.nanoTime() / 1_000_000L }, { clockCalls.incrementAndGet(); 555L })
        }
        fun start(mode: MeetingOptions.Mode, delivery: MeetingOptions.ResultDelivery, capture: Boolean) {
            start(MeetingOptions(mode, "zh-CN", "en-US", capture, delivery,
                "https://result.example.test/index.html?existing=1#section", listOf("recipient@example.test")))
        }
        fun start(options: MeetingOptions) {
            session = client.start(options, probe)
            assertTrue(runtime.started.await(2, TimeUnit.SECONDS))
        }
        override fun close() {
            server.releaseAppend.countDown()
            server.releaseUsage.countDown()
            server.releaseModelGrant.countDown()
            client.close()
        }
    }

    /** 只用于公共合同测试；重开时经 public restore 重建值，不冒充 Android 磁盘持久化。 */
    private class MemoryStore : StoppedMeetingStore {
        private var saved: StoppedMeetingCheckpoint? = null
        @Volatile var failPhase: StoppedMeetingCheckpoint.Phase? = null
        @Synchronized override fun save(checkpoint: StoppedMeetingCheckpoint): StoppedMeetingCheckpoint {
            if (checkpoint.phase() == failPhase) throw IOException("fixture-store-save-failed")
            val merged = saved?.merge(checkpoint) ?: checkpoint
            saved = merged
            return merged
        }
        @Synchronized override fun read(id: String): Optional<StoppedMeetingCheckpoint> {
            val value = saved
            return if (value != null && value.checkpointId() == id) Optional.of(value) else Optional.empty()
        }
        @Synchronized fun reopened(): MemoryStore {
            val checkpoint = saved!!
            val original = checkpoint.context()
            val originalConfig = MeetingClientConfig(original.serverUrl(), original.accessToken(),
                original.clientIdentity(), MeetingProfile.reference(), { it.run() })
            val copy = MemoryStore()
            copy.saved = StoppedMeetingCheckpoint.restore(original.audio(), original.frozenAudioDurationMs(),
                originalConfig, checkpoint.stableStopKey(), checkpoint.phase(), checkpoint.genTime())
            return copy
        }
    }

    private class Publisher : MeetingArtifactPublisher {
        val contexts: MutableList<MeetingArtifactPublisher.Context> = Collections.synchronizedList(ArrayList())
        lateinit var server: Server
        var failOnce = false
        override fun publish(context: MeetingArtifactPublisher.Context) {
            contexts.add(context)
            server.steps.add("publish")
            if (failOnce) { failOnce = false; throw IOException("fixture-publication-unconfirmed") }
        }
    }

    private class Probe : MeetingListener {
        val states: BlockingQueue<MeetingSessionState> = LinkedBlockingQueue()
        val displays: BlockingQueue<MeetingDisplaySnapshot> = LinkedBlockingQueue()
        val results: BlockingQueue<MeetingResult> = LinkedBlockingQueue()
        val levels: BlockingQueue<AudioInputLevel> = LinkedBlockingQueue()
        val activity = CountDownLatch(1)
        val beforeStopCalls = AtomicInteger()
        var beforeStop = java.util.function.Consumer<Runnable> { it.run() }
        @Volatile var info: MeetingStarted? = null
        override fun onStarted(started: MeetingStarted) { info = started }
        override fun onState(state: MeetingSessionState) { states.add(state) }
        override fun onDisplay(display: MeetingDisplaySnapshot) { displays.add(display) }
        override fun onTranscriptActivity(nowMs: Long) { activity.countDown() }
        override fun onAudioLevel(level: AudioInputLevel) { levels.add(level) }
        override fun beforeStop(continuation: Runnable) {
            beforeStopCalls.incrementAndGet()
            beforeStop.accept(continuation)
        }
        override fun onResult(result: MeetingResult) { results.add(result) }
        fun awaitPhase(phase: MeetingSessionState.Phase): MeetingSessionState {
            while (true) {
                val next = states.poll(3, TimeUnit.SECONDS)
                assertNotNull("应收到会议状态", next)
                if (next!!.phase() == phase) return next
            }
        }
        fun awaitMessage(text: String): MeetingSessionState {
            while (true) {
                val next = states.poll(3, TimeUnit.SECONDS)
                assertNotNull("应收到事件保存状态", next)
                if (next!!.message().contains(text)) return next
            }
        }
        fun awaitSavedEvents(count: Int) {
            while (true) {
                val next = states.poll(3, TimeUnit.SECONDS)
                assertNotNull("应收到真实字幕ACK计数", next)
                if (next!!.savedEventCount() < count) continue
                assertEquals("译文或重发不能重复增加原文保存计数", count, next.savedEventCount())
                return
            }
        }
        fun awaitCanStop() {
            while (true) {
                val next = states.poll(3, TimeUnit.SECONDS)
                assertNotNull("最终事件保存后可停止", next)
                if (next!!.canStop()) return
            }
        }
        fun display(): MeetingDisplaySnapshot {
            val value = displays.poll(3, TimeUnit.SECONDS)
            assertNotNull("应收到显示快照", value)
            return value!!
        }
        fun visible(): MeetingDisplaySnapshot = visibleWithRows(1)
        fun visibleWithRows(count: Int): MeetingDisplaySnapshot {
            while (true) {
                val value = display()
                if (value.rows().size >= count) return value
            }
        }
        fun result(): MeetingResult {
            val result = results.poll(3, TimeUnit.SECONDS)
            assertNotNull("应收到实际就绪结果", result)
            return result!!
        }
    }

    private class Runtime(private val real: Boolean) : MeetingRuntimePort {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val controlCalls: MutableList<String> = Collections.synchronizedList(ArrayList())
        lateinit var transcripts: TranscriptListener
        lateinit var listener: ModelSessionListener
        var grant: ModelSessionGrant? = null
        var adapter: ModelAdapter? = null
        var reconnector: ModelSessionReconnector? = null
        var summaryInput: SummaryInput? = null
        @Volatile var stopCalls = 0
        var emitTail = false
        var emitFinished = true
        @Volatile var duration = OptionalLong.empty()
        @Volatile var durationReads = 0
        @Volatile var artifactReads = 0
        @Volatile var openCalls = 0
        var retainedAudioSupported = false
        var retainAudio = false
        @Volatile var artifact: Optional<SealedAudioArtifact> = Optional.empty()
        @Volatile var artifactFailure: IOException? = null
        override fun usesServerModelMaterials() = real
        override fun localCapabilities() = ALL
        override fun supportsRetainedAudio() = retainedAudioSupported
        override fun open(grant: ModelSessionGrant?, adapter: ModelAdapter?, transcripts: TranscriptListener,
            listener: ModelSessionListener, retainAudio: Boolean, reconnector: ModelSessionReconnector?): ModelSession {
            this.reconnector = reconnector
            return open(grant, adapter, transcripts, listener, retainAudio)
        }
        override fun open(grant: ModelSessionGrant?, adapter: ModelAdapter?, transcripts: TranscriptListener,
            listener: ModelSessionListener, retainAudio: Boolean): ModelSession {
            if (retainAudio && !retainedAudioSupported)
                return super<MeetingRuntimePort>.open(grant, adapter, transcripts, listener, true)
            this.retainAudio = retainAudio
            return open(grant, adapter, transcripts, listener)
        }
        override fun open(grant: ModelSessionGrant?, adapter: ModelAdapter?, transcripts: TranscriptListener,
            listener: ModelSessionListener): ModelSession {
            ++openCalls
            this.grant = grant
            this.adapter = adapter
            this.transcripts = transcripts
            this.listener = listener
            return object : ModelSession {
                override fun start() { controlCalls.add("start"); started.countDown() }
                override fun stopInput() {
                    controlCalls.add("stop")
                    stopCalls++
                    stopped.countDown()
                    if (real) {
                        if (emitTail) {
                            emit("tail", 1, "zh-CN", "最后字幕", true)
                            listener.onTranslation(TranslationEvent("t1", "tail", 1,
                                "zh-CN", "en-US", "Final text", true))
                        }
                        if (emitFinished) listener.onFinished()
                    }
                }
                override fun close() { controlCalls.add("close"); closed.countDown() }
                override fun frozenAudioDurationMs(): OptionalLong {
                    ++durationReads
                    assertTrue("协调器必须在停止输入后读取用量", stopCalls > 0)
                    return duration
                }
                override fun sealedAudioArtifact(): Optional<SealedAudioArtifact> {
                    ++artifactReads
                    assertTrue("协调器必须在停止输入后读取封口音频", stopCalls > 0)
                    artifactFailure?.let { throw it }
                    return artifact
                }
            }
        }
        fun prepareAudio(audio: SealedAudioArtifact) {
            retainedAudioSupported = true
            artifact = Optional.of(audio)
            duration = OptionalLong.of(audio.durationMs())
        }
        fun emit(id: String, revision: Int, language: String, text: String, end: Boolean) {
            transcripts.onTranscript(TranscriptEvent(id, revision, 0, 100, language, text, end, "speaker-1"))
        }
        override fun summarize(grant: SummarySessionGrant?, adapter: SummaryAdapter?, input: SummaryInput): SummaryResult {
            summaryInput = input
            return SummaryResult(input.language(), "总结", "概览", listOf("要点"), emptyList())
        }
    }

    private data class Capture(private val sequence: Int, private val offsetMs: Long, private val jpeg: ByteArray) {
        fun sequence() = sequence
        fun offsetMs() = offsetMs
        fun jpeg() = jpeg
    }
    private data class StandardAppend(private val meetingId: String, private val sequence: Int,
        private val transcript: TranscriptEvent?, private val translation: TranslationEvent?) {
        fun meetingId() = meetingId
        fun sequence() = sequence
        fun transcript() = transcript
        fun translation() = translation
    }

    private class Server(private val granted: Set<String>) : MeetingServerClient {
        @Volatile var deploymentCapabilities: Set<String> = ALL
        val steps: MutableList<String> = Collections.synchronizedList(ArrayList())
        val transcripts: MutableList<TranscriptEvent> = Collections.synchronizedList(ArrayList())
        val genTimes: MutableList<Long> = Collections.synchronizedList(ArrayList())
        val usages: MutableList<Long> = Collections.synchronizedList(ArrayList())
        val stopKeys: MutableList<String> = Collections.synchronizedList(ArrayList())
        val captures: MutableList<Capture> = Collections.synchronizedList(ArrayList())
        val standardAttempts: MutableList<StandardAppend> = Collections.synchronizedList(ArrayList())
        val modelSessionRequests: MutableList<String> = Collections.synchronizedList(ArrayList())
        val standardAccepted: MutableMap<Int, StandardAppend> = ConcurrentHashMap()
        val appendEntered = CountDownLatch(1)
        val releaseAppend = CountDownLatch(1)
        val usageEntered = CountDownLatch(1)
        val releaseUsage = CountDownLatch(1)
        val modelGrantEntered = CountDownLatch(1)
        val releaseModelGrant = CountDownLatch(1)
        @Volatile var blockAppend = false
        @Volatile var blockUsage = false
        @Volatile var blockModelGrant = false
        @Volatile var failAppend = false
        var loseAppendAckOnce = false
        var pendingStop = false
        var failEmail = false
        var failContent = false
        var failUsageOnce = false
        var failSummaryOnce = false
        var loseStopAckOnce = false
        var failManifest = false
        var acceptedStopKey: String? = null
        var failPresentationCalls = 0
        var includeContent = false
        var jpeg: ByteArray? = null
        var emailUrl: String? = null
        var adapterId = TingwuModelAdapter.ID
        lateinit var requested: MeetingOptions
        override fun discover(): DeploymentOptions {
            steps.add("discover")
            return DeploymentOptions(deploymentCapabilities, 86400000L, true)
        }
        override fun createMeeting(options: MeetingOptions): CreatedMeeting {
            requested = options
            steps.add("create")
            try {
                val decisions = JSONArray()
                for (capability in granted)
                    decisions.put(JSONObject().put("capability", capability).put("availability", "AVAILABLE"))
                return CreatedMeeting("mtg_test", null, MeetingCapabilityState.fromCreateResponse(
                    JSONObject().put("capabilityDecisions", decisions)), 600000L)
            } catch (failure: Exception) { throw IOException(failure) }
        }
        override fun requestModelSession(id: String): ModelSessionGrant {
            steps.add("grant")
            modelSessionRequests.add(id)
            if (blockModelGrant) {
                modelGrantEntered.countDown()
                try {
                    if (!releaseModelGrant.await(4, TimeUnit.SECONDS)) throw IOException("测试未释放模型材料HTTP")
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException(interrupted)
                }
            }
            return ModelSessionGrant(id, adapterId, "wss://model.example.test/signed",
                "2030-01-01T00:00:00Z", null, emptyMap())
        }
        override fun requestSummarySession(id: String): SummarySessionGrant {
            steps.add("summaryGrant")
            return SummarySessionGrant(BailianSummaryAdapter.ID, "https://summary.example.test",
                "2030-01-01T00:00:00Z", "fixture-summary")
        }
        override fun startClient(id: String) { steps.add("start") }
        override fun appendTranscript(id: String, number: Int, event: TranscriptEvent) {
            steps.add("event" + number)
            transcripts.add(event)
            appendEntered.countDown()
            val append = StandardAppend(id, number, event, null)
            standardAttempts.add(append)
            if (blockAppend) try {
                if (!releaseAppend.await(4, TimeUnit.SECONDS)) throw IOException("测试未释放 HTTP")
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException(interrupted)
            }
            acceptStandardAppend(append)
        }
        override fun appendTranslation(id: String, number: Int, event: TranslationEvent) {
            steps.add("translation" + number)
            val append = StandardAppend(id, number, null, event)
            standardAttempts.add(append)
            acceptStandardAppend(append)
        }
        private fun acceptStandardAppend(append: StandardAppend) {
            if (failAppend) throw IOException("fixture-event-save-failed")
            val previous = standardAccepted.putIfAbsent(append.sequence(), append)
            if (previous != null) assertEquals("同号重交必须保持原场和原内容", previous, append)
            else assertEquals("后来的事件不能跨过未确认序号", standardAccepted.size, append.sequence())
            if (loseAppendAckOnce) {
                loseAppendAckOnce = false
                throw IOException("fixture-event-saved-but-ack-lost")
            }
        }
        override fun appendPresentation(id: String, number: Int, offset: Long, jpeg: ByteArray) {
            steps.add("capture" + number)
            captures.add(Capture(number, offset, jpeg.clone()))
            this.jpeg = jpeg
            if (failPresentationCalls > 0) {
                --failPresentationCalls
                throw IOException("受控截图响应未确认")
            }
        }
        override fun submitSummary(id: String, summary: SummaryResult) {
            steps.add("summary")
            if (failSummaryOnce) { failSummaryOnce = false; throw IOException("fixture-summary-ack-lost") }
        }
        override fun submitUsage(id: String, duration: Long) {
            assertEquals("mtg_test", id)
            steps.add("usage")
            usages.add(duration)
            usageEntered.countDown()
            if (blockUsage) try {
                if (!releaseUsage.await(4, TimeUnit.SECONDS)) throw IOException("测试未释放用量提交")
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException(interrupted)
            }
            if (failUsageOnce) { failUsageOnce = false; throw IOException("受控用量响应未确认") }
        }
        override fun stopMeeting(id: String): StopMeetingResponse {
            steps.add("stop")
            val accepted = !pendingStop
            pendingStop = false
            return StopMeetingResponse(accepted, if (accepted) "INPUT_ACCEPTED" else "INPUT_COMPLETE_PENDING",
                if (accepted) "receipt" else null, "GENERATING")
        }
        override fun stopMeeting(id: String, stableStopKey: String): StopMeetingResponse {
            assertEquals("mtg_test", id)
            assertFalse(stableStopKey.isBlank())
            stopKeys.add(stableStopKey)
            if (acceptedStopKey != null) assertEquals("原场 Stop 必须复用首次键", acceptedStopKey, stableStopKey)
            val response = stopMeeting(id)
            if (response.accepted()) acceptedStopKey = stableStopKey
            if (loseStopAckOnce) {
                loseStopAckOnce = false
                throw IOException("fixture-stop-accepted-but-ack-lost")
            }
            return response
        }
        override fun readResult(id: String, genTime: Long, includeContent: Boolean): MeetingResult {
            steps.add("manifest")
            genTimes.add(genTime)
            this.includeContent = includeContent
            if (failManifest) throw IOException("fixture-manifest-not-ready")
            if (includeContent && failContent) throw IOException("受控正文读取失败")
            return MeetingResult(id, "本场", genTime, MeetingResult.Status.READY, setOf("transcript.final"),
                if (includeContent) MeetingResultContent(setOf("transcript.final"), "最终字幕",
                    emptyList(), emptyList(), null, emptyList()) else null)
        }
        override fun sendResultEmail(id: String, recipients: List<String>, url: String) {
            steps.add("email")
            emailUrl = url
            if (failEmail) throw IOException("测试邮箱未接通")
        }
    }
}
