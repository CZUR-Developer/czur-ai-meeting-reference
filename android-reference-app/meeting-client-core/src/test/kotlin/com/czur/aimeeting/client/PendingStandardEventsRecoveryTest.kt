package com.czur.aimeeting.client

import com.czur.aimeeting.client.server.HttpMeetingServerClient
import com.czur.aimeeting.client.server.StandardEventPacket
import com.czur.aimeeting.client.server.standardEventDigest
import com.czur.aimeeting.model.SealedAudioArtifact
import com.czur.aimeeting.model.TranscriptEvent
import com.czur.aimeeting.model.TranslationEvent
import com.czur.aimeeting.model.ModelAdapter
import com.czur.aimeeting.model.ModelSessionGrant
import com.czur.aimeeting.model.ModelSessionListener
import com.czur.aimeeting.model.TranscriptListener
import com.czur.aimeeting.model.SummaryAdapter
import com.czur.aimeeting.model.SummaryInput
import com.czur.aimeeting.model.SummarySessionGrant
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingStandardEventsRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun notAcceptedAndLostAckBothReplayByteIdenticalEventsBeforeUsage() {
        for (firstReachedServer in listOf(false, true)) {
            fixture { f ->
                val pending = PendingStandardEvents.initialize(f.record)
                pending.append(f.transcript)
                pending.append(f.translation)
                f.firstMode = if (firstReachedServer) FirstMode.COMMITTED_NO_RESPONSE else FirstMode.UNREACHABLE
                assertThrows(IOException::class.java) {
                    HttpMeetingServerClient(f.config).appendStoredStandardEvent(ID, f.transcript.body)
                }
                assertEquals(listOf(f.transcript.body, f.translation.body),
                    PendingStandardEvents.open(f.record)!!.pending().map { it.body })
                assertEquals(firstReachedServer, f.committed.containsKey(f.transcript.key))
                assertEquals(ID, f.recover().meetingId())
                assertEquals(listOf(f.transcript.body, f.transcript.body, f.translation.body), f.sentBodies)
                assertEquals(setOf(f.transcript.key, f.translation.key), f.committed.keys)
                assertEquals(listOf(if (firstReachedServer) 200 else 201, 201), f.confirmedStatuses)
                assertEquals(listOf("event", "event", "event", "usage", "publish", "summary", "stop", "manifest"), f.routes)
                assertEquals(listOf("the-original-stop-key"), f.stopKeys)
                assertFalse(f.eventDirectory.exists())
                assertNull(f.recovery.record)
            }
        }
    }

    @Test fun foreignReceiptCannotAcknowledgePendingInputAndDoesNotAdvanceStop() = fixture { f ->
        val pending = PendingStandardEvents.initialize(f.record)
        pending.append(f.transcript)
        f.firstMode = FirstMode.WRONG_MEETING_RECEIPT
        assertEquals(MeetingErrorCode.RECOVERY_FAILED, f.recoverError())
        assertEquals(listOf("event"), f.routes)
        assertEquals(listOf(f.transcript.body), PendingStandardEvents.open(f.record)!!.pending().map { it.body })
        assertTrue(File(f.eventDirectory, "1.event").isFile)
        assertTrue(f.stopped.read(ID).isEmpty)
        assertEquals(ID, f.recover().meetingId())
        assertEquals(1, f.committed.size)
        assertEquals(listOf(200), f.confirmedStatuses)
    }

    @Test fun legacyFinalTranscriptWithoutOriginalPacketIsPreservedAndNotFinalized() = fixture(legacy = true) { f ->
        assertEquals(MeetingErrorCode.STOP_EVENTS_UNVERIFIED, f.recoverError())
        assertTrue(f.routes.isEmpty())
        assertEquals(f.record, f.recovery.record)
        assertTrue(f.record.journalFile()!!.isFile)
        assertTrue(f.stopped.read(ID).isEmpty)
    }

    @Test fun cloudFlagAndEmptyQueueCannotDiscardUnmatchedLocalFinal() = fixture(unmappedFinal = true) { f ->
        PendingStandardEvents.initialize(f.record)
        val journal = f.record.journalFile()!!
        val original = journal.readBytes()

        val result = f.runRecovery()

        assertEquals("no finalization request may precede transcript confirmation", emptyList<String>(), f.routes)
        assertEquals(MeetingErrorCode.STOP_EVENTS_UNVERIFIED, (result as MeetingError).code)
        assertArrayEquals(original, journal.readBytes())
        assertEquals(f.record, f.recovery.record)
        assertTrue(f.stopped.read(ID).isEmpty)
    }

    @Test fun readyCleanupCannotUseCloudFlagAsProofOfJournalAcknowledgment() = fixture(unmappedFinal = true) { f ->
        PendingStandardEvents.initialize(f.record)
        val journal = f.record.journalFile()!!
        val original = journal.readBytes()

        assertThrows(UnverifiedStandardEventsException::class.java) { f.record.requireAcknowledgedStandardEvents() }
        assertArrayEquals(original, journal.readBytes())
    }

    @Test fun partialLocalReceiptAfterShutdownReplaysOnlyTheLastUnconfirmedExactPacket() = fixture { f ->
        val queue = PendingStandardEvents.initialize(f.record)
        queue.append(f.transcript)
        queue.append(f.translation)
        HttpMeetingServerClient(f.config).appendStoredStandardEvent(ID, f.transcript.body)
        queue.acknowledge(f.transcript)
        val journal = File(f.record.audioFile().parentFile, "$ID.standard-events")
        File(journal, "receipt.json.part").writeText("interrupted state write")
        assertEquals(listOf(f.translation.body), PendingStandardEvents.open(f.record)!!.pending().map { it.body })
        assertEquals(ID, f.recover().meetingId())
        assertEquals(listOf(f.transcript.body, f.translation.body), f.sentBodies)
    }

    @Test fun scopeLossBetweenOriginalReceiptAndLocalAckKeepsThePacketForOriginalAccount() = fixture { f ->
        val queue = PendingStandardEvents.initialize(f.record)
        queue.append(f.transcript)
        f.onAppend = { f.allowed = false }
        assertEquals(MeetingErrorCode.RECOVERY_FAILED, f.recoverError())
        assertEquals(listOf("event"), f.routes)
        assertEquals(listOf(f.transcript.body), PendingStandardEvents.open(f.record)!!.pending().map { it.body })
        assertEquals(MeetingErrorCode.RECOVERY_FAILED, f.recoverError())
        assertEquals(listOf("event"), f.routes)
        f.onAppend = {}
        f.allowed = true
        assertEquals(ID, f.recover().meetingId())
        assertEquals(1, f.committed.size)
        assertEquals(listOf(201, 200), f.confirmedStatuses)
    }

    @Test fun durableFinalIsReplayedFromItsOnlyFileAfterInterruptedRename() = fixture { f ->
        val queue = PendingStandardEvents.initialize(f.record)
        queue.append(f.transcript)
        val packet = File(f.eventDirectory, "1.event")
        assertTrue(packet.renameTo(File(f.eventDirectory, "1.event.part")))
        assertNull(f.record.journalFile())
        f.onAppend = {
            assertTrue(packet.isFile)
            assertEquals(0, JSONObject(File(f.eventDirectory, "receipt.json").readText())
                .getInt("acknowledgedThrough"))
        }

        assertEquals(ID, f.recover().meetingId())

        assertEquals(listOf(f.transcript.body), f.sentBodies)
        assertEquals(listOf("event", "usage", "publish", "summary", "stop", "manifest"), f.routes)
        assertFalse(f.eventDirectory.exists())
    }

    @Test fun legacyDiagnosticMatchingOriginalPacketCanResumeAcrossAnotherAccountLoss() = fixture { f ->
        val queue = PendingStandardEvents.initialize(f.record)
        queue.append(f.transcript)
        queue.append(f.translation)
        File(f.eventDirectory, "receipt.json").writeText(
            JSONObject().put("version", 1).put("meetingId", ID).put("acknowledgedThrough", 0).toString())
        val diagnostic = File(f.eventDirectory.parentFile, "$ID.jsonl")
        TranscriptJournal(diagnostic).append(TranscriptEvent("segment", 1, 10, 90, "zh-CN", "本机保存的最终原文", true, "speaker-1"))
        f.onAppend = { if (f.committed.size == 2) f.allowed = false }

        assertEquals(MeetingErrorCode.RECOVERY_FAILED, f.recoverError())
        assertTrue(diagnostic.isFile)
        assertTrue(File(f.eventDirectory, "1.event").isFile)
        assertEquals(listOf(f.translation.body), PendingStandardEvents.open(f.record)!!.pending().map { it.body })
        f.onAppend = {}
        f.allowed = true

        assertEquals(ID, f.recover().meetingId())
        assertEquals(listOf(f.transcript.body, f.translation.body, f.translation.body), f.sentBodies)
        assertFalse(diagnostic.exists())
    }

    @Test fun consecutiveReceiptCannotConfirmAnotherBodyWithTheSameSequenceAndKey() = fixture { f ->
        val queue = PendingStandardEvents.initialize(f.record)
        queue.append(f.transcript)
        HttpMeetingServerClient(f.config).appendStoredStandardEvent(ID, f.transcript.body)
        queue.acknowledge(f.transcript)
        val badBody = JSONObject(f.transcript.body).apply {
            getJSONObject("transcriptUpsert").put("text", "different local input")
        }.toString()
        File(f.eventDirectory, "1.event").writeText(JSONObject().put("body", badBody)
            .put("sha256", standardEventDigest(badBody)).toString())

        assertEquals(MeetingErrorCode.STOP_EVENTS_UNVERIFIED, f.recoverError())
        assertEquals(listOf("event"), f.routes)
        assertTrue(File(f.eventDirectory, "1.event").isFile)
    }

    @Test fun malformedAndMissingSequenceRemainOnDiskWithoutStartingFinalization() {
        for (tornPacket in listOf(true, false)) {
            fixture { f ->
                val queue = PendingStandardEvents.initialize(f.record)
                queue.append(f.transcript)
                queue.append(f.translation)
                val first = File(f.eventDirectory, "1.event")
                assertTrue(first.delete())
                val torn = File(f.eventDirectory, "1.event.part")
                if (tornPacket) torn.writeText("{\"body\":")
                assertEquals(MeetingErrorCode.STOP_EVENTS_UNVERIFIED, f.recoverError())
                assertTrue(f.routes.isEmpty())
                assertEquals(tornPacket, torn.exists())
                assertTrue(File(f.eventDirectory, "2.event").isFile)
            }
        }
    }

    @Test fun capturingDeleteFailureKeepsReceiptAndCanRetryWithoutNetwork() = fixture { f ->
        val queue = PendingStandardEvents.initialize(f.record)
        queue.append(f.transcript)
        HttpMeetingServerClient(f.config).appendStoredStandardEvent(ID, f.transcript.body)
        queue.acknowledge(f.transcript)
        assertThrows(IOException::class.java) {
            f.record.discardAcknowledgedStandardEvents({}) { throw IOException("capture record not removed") }
        }
        assertTrue(File(f.eventDirectory, "receipt.json").isFile)
        assertEquals(f.record, f.recovery.record)
        f.record.discardAcknowledgedStandardEvents({}) { f.recovery.delete(ID) }
        assertFalse(f.eventDirectory.exists())
        assertNull(f.recovery.record)
        assertEquals(listOf("event"), f.routes)
    }

    private fun fixture(legacy: Boolean = false, unmappedFinal: Boolean = false, run: (Fixture) -> Unit) {
        Fixture(temporary.newFolder(), legacy, unmappedFinal).use(run)
    }

    private enum class FirstMode { NORMAL, UNREACHABLE, COMMITTED_NO_RESPONSE, WRONG_MEETING_RECEIPT }

    private class Fixture(root: File, legacy: Boolean, unmappedFinal: Boolean) : AutoCloseable {
        val eventDirectory = File(root, "$ID.standard-events")
        val transcript = StandardEventPacket.transcript(1,
            TranscriptEvent("segment", 1, 10, 90, "zh-CN", "本机保存的最终原文", true, "speaker-1"))
        val translation = StandardEventPacket.translation(2,
            TranslationEvent("translation", "segment", 1, "zh-CN", "en-US", "The original translation", true))
        val routes = mutableListOf<String>()
        val sentBodies = mutableListOf<String>()
        val confirmedStatuses = mutableListOf<Int>()
        val committed = linkedMapOf<String, Pair<String, String>>()
        val stopKeys = mutableListOf<String>()
        var firstMode = FirstMode.NORMAL
        @Volatile var allowed = true
        var onAppend: () -> Unit = {}
        val record: CapturingMeeting
        val recovery: RecoveryStore
        val stopped = TestStopStore()
        val config: MeetingClientConfig
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            http.createContext("/") { exchange ->
                try {
                    val path = exchange.requestURI.path
                    assertEquals("Bearer original-owner-fixture", exchange.requestHeaders.getFirst("Authorization"))
                    val body = exchange.requestBody.readAllBytes().decodeToString()
                    when (path) {
                        "/v1/meetings/$ID/events:append" -> append(exchange, body)
                        "/v1/meetings/$ID/usage:submit" -> {
                            routes += "usage"
                            assertEquals(committed.size, JSONObject(File(eventDirectory, "receipt.json").readText())
                                .getInt("acknowledgedThrough"))
                            assertEquals(100, JSONObject(body).getInt("audioDurationMs"))
                            respond(exchange, 201, """{"meetingId":"$ID","audioDurationMs":100,"accepted":true}""")
                        }
                        "/v1/meetings/$ID/summary:request" -> {
                            routes += "summary"
                            respond(exchange, 201, """{"meetingId":"$ID","accepted":true,"languages":["zh-CN"]}""")
                        }
                        "/v1/meetings/$ID:stop" -> {
                            routes += "stop"
                            stopKeys += exchange.requestHeaders.getFirst("Idempotency-Key")
                            respond(exchange, 200, """{"inputState":"INPUT_ACCEPTED","manifestState":"READY"}""")
                        }
                        "/v1/meetings/$ID/manifest" -> {
                            routes += "manifest"
                            respond(exchange, 200, """{"meetingId":"$ID","profile":{"id":"reference-asr","revision":1},"status":"READY","artifacts":[]}""")
                        }
                        else -> respond(exchange, 404, "{}")
                    }
                } finally {
                    exchange.close()
                }
            }
            http.start()
            val base = "http://127.0.0.1:${http.address.port}"
            val audio = File(root, "$ID.pcm").apply { writeBytes(ByteArray(3200)) }
            val journal = if (legacy || unmappedFinal) File(root, "$ID.jsonl").also {
                TranscriptJournal(it).append(TranscriptEvent("segment", 1, 10, 90, "zh-CN", "本机原文", true))
            } else null
            record = CapturingMeeting(ID, base, ClientIdentity.reference(), audio, journal, 1, "zh-CN",
                true, true, true, null, !legacy)
            recovery = RecoveryStore(record, SealedAudioArtifact(ID, audio, SealedAudioArtifact.Format.PCM16, 3200, 3200))
            config = MeetingClientConfig.reference(base, "original-owner-fixture")
                .withRequestGuard(BooleanSupplier { allowed })
                .withArtifactPublisher { routes += "publish" }
                .withStoppedMeetingStore(stopped).withMeetingRecoveryStore(recovery)
        }

        private fun append(exchange: HttpExchange, body: String) {
            routes += "event"
            sentBodies += body
            val mode = firstMode.also { firstMode = FirstMode.NORMAL }
            if (mode == FirstMode.UNREACHABLE) {
                respond(exchange, 503, "{}")
                return
            }
            val original = JSONObject(body)
            val packet = StandardEventPacket.read(body)
            val kind = if (original.has("transcriptUpsert")) "transcriptUpsert" else "translationUpsert"
            val previous = committed[packet.key]
            if (previous != null && previous.first != body) {
                respond(exchange, 409, """{"error":{"code":"EVENT_CONFLICT"}}""")
                return
            }
            val receipt = previous?.second ?: JSONObject().put("specVersion", "0.1").put("meetingId", ID)
                .put("streamId", ID).put("eventSequence", (10 + packet.sequence).toString())
                .put("eventId", StandardEventPacket.serverEventId(ID, packet.key))
                .put(kind, original.getJSONObject(kind)).toString()
            committed[packet.key] = body to receipt
            onAppend()
            if (mode == FirstMode.COMMITTED_NO_RESPONSE) return
            val status = if (previous == null) 201 else 200
            if (mode == FirstMode.WRONG_MEETING_RECEIPT) {
                respond(exchange, status, JSONObject(receipt).put("meetingId", "mtg_other").toString())
            } else {
                confirmedStatuses += status
                respond(exchange, status, receipt)
            }
        }

        fun recover(): RecoveryResult = runRecovery() as RecoveryResult
        fun recoverError(): MeetingErrorCode = (runRecovery() as MeetingError).code

        fun runRecovery(): Any {
            val result = CompletableFuture<Any>()
            val noRuntime = object : MeetingRuntimePort {
                override fun open(grant: ModelSessionGrant?, adapter: ModelAdapter?, transcripts: TranscriptListener,
                    listener: ModelSessionListener): Nothing = error("冷启不能新开录音")
                override fun summarize(grant: SummarySessionGrant?, adapter: SummaryAdapter?,
                    input: SummaryInput): Nothing = error("冷启不能打开本地模型")
            }
            MeetingCoordinator(config, ModelAdapterRegistry(), noRuntime).use { client ->
                client.recoverInterrupted(ID, object : MeetingClient.Callback<RecoveryResult> {
                    override fun onSuccess(value: RecoveryResult) { result.complete(value) }
                    override fun onError(error: MeetingError) { result.complete(error) }
                })
                return result.get(4, TimeUnit.SECONDS)
            }
        }

        override fun close() = http.stop(0)
    }

    private class RecoveryStore(var record: CapturingMeeting?, artifact: SealedAudioArtifact) : MeetingStopIntentStore {
        private var intent = MeetingStopIntent("the-original-stop-key", 100, artifact)
        override fun usesDurableStandardEvents(): Boolean = true
        override fun save(record: CapturingMeeting) { this.record = record }
        override fun read(meetingId: String): Optional<CapturingMeeting> = Optional.ofNullable(record)
        override fun delete(meetingId: String) { record = null }
        override fun beginExplicitStop(meetingId: String): String = intent.stopKey
        override fun saveFrozenStop(meetingId: String, stop: MeetingStopIntent) { intent = stop }
        override fun readExplicitStop(meetingId: String): MeetingStopIntent = intent
    }

    private class TestStopStore : StoppedMeetingStore {
        private var saved: StoppedMeetingCheckpoint? = null
        override fun save(checkpoint: StoppedMeetingCheckpoint): StoppedMeetingCheckpoint =
            (saved?.merge(checkpoint) ?: checkpoint).also { saved = it }
        override fun read(checkpointId: String): Optional<StoppedMeetingCheckpoint> = Optional.ofNullable(saved)
    }

    companion object {
        private const val ID = "mtg_original"
        private fun respond(exchange: HttpExchange, status: Int, text: String) {
            val body = text.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }
}
