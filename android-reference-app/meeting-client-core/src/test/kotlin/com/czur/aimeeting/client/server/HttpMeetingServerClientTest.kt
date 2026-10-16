package com.czur.aimeeting.client.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

import com.czur.aimeeting.client.ClientIdentity
import com.czur.aimeeting.client.MeetingClientConfig
import com.czur.aimeeting.client.MeetingOptions
import com.czur.aimeeting.client.MeetingProfile
import com.czur.aimeeting.client.MeetingResult
import com.czur.aimeeting.client.PresentationFrame
import com.czur.aimeeting.client.result.ResultOptions
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** 真实本机 HTTP；无 Android 或新增 HTTP 测试依赖，验证正常协议及类型化结果。 */
class HttpMeetingServerClientTest {
    @Test fun coordinatorDiscoveryUsesExistingAnonymousCapabilitiesProtocol() {
        Fixture().use { fixture ->
            fixture.reply(200, "{\"capabilities\":[\"transcript.realtime\"],\"resultLinkTtlMs\":86400000,"
                    + "\"resultEmailEnabled\":true,\"languages\":[\"en-US\",\"ja-JP\"]}")
            val options = fixture.client().discover()
            assertEquals(setOf("transcript.realtime"), options.capabilities())
            assertEquals(86400000, options.resultLinkTtlMs())
            assertTrue(options.resultEmailEnabled())
            assertEquals(listOf("en-US", "ja-JP"), options.languages())
            val request = fixture.request()
            assertEquals("/v1/capabilities", request.path())
            assertNull(request.authorization())
        }
    }

    @Test fun createUsesConfiguredClientAndProfileWithoutModelOrDeliveryFields() {
        Fixture().use { fixture ->
            fixture.reply(201, "{\"meetingId\":\"mtg_config\",\"limits\":{\"maxMeetingDurationMs\":600000}}")
            val config = MeetingClientConfig(fixture.url(), "test-token",
                    ClientIdentity("partner.app", "2.0", "v1", "device-7"),
                    MeetingProfile("partner-meeting", 3), { it.run() })
            val created = HttpMeetingServerClient(config).createMeeting(options())
            val request = fixture.request()
            val body = JSONObject(request.body())
            assertEquals("/v1/meetings", request.path())
            assertEquals("Bearer test-token", request.authorization())
            assertEquals("partner.app", body.getJSONObject("client").getString("appId"))
            assertEquals("2.0", body.getJSONObject("client").getString("appVersion"))
            assertEquals("v1", body.getJSONObject("client").getString("contractVersion"))
            assertEquals("device-7", body.getJSONObject("client").getString("deviceId"))
            assertEquals("partner-meeting", body.getJSONObject("profile").getString("id"))
            assertEquals(3, body.getJSONObject("profile").getInt("revision"))
            assertEquals("参考会议", body.getString("title"))
            assertEquals("Asia/Shanghai", body.getString("timezone"))
            assertFalse(body.getJSONObject("artifactPolicy").getBoolean("retainAudio"))
            assertFalse(body.has("endpoint"))
            assertFalse(body.has("resultDelivery"))
            assertEquals("mtg_config", created.meetingId())
            assertEquals(600000, created.maxMeetingDurationMs())
            assertFalse(config.toString().contains("test-token"))
        }
    }

    @Test fun createUsesFrozenParametersAndOmitsAnAbsentTitle() {
        Fixture().use { fixture ->
            val selected = MeetingOptions(MeetingOptions.Mode.LIVE_TRANSLATION,
                    "en-US", "zh-CN", true, ResultOptions.action("partner.web"),
                    "https://result.example.test/index.html", listOf("fixture@example.test"), true)
            fixture.reply(201, "{\"meetingId\":\"mtg_parameters\",\"limits\":{\"maxMeetingDurationMs\":600000}}")
            fixture.client().createMeeting(selected.withCreateParameters("客户会议", "Europe/Paris", true))
            val request = fixture.request()
            val body = JSONObject(request.body())
            assertEquals("/v1/meetings", request.path())
            assertEquals("Bearer test-token", request.authorization())
            assertEquals("客户会议", body.getString("title"))
            assertEquals("Europe/Paris", body.getString("timezone"))
            assertTrue(body.getJSONObject("artifactPolicy").getBoolean("retainAudio"))
            assertTrue(body.getJSONObject("artifactPolicy").getBoolean("presentationCapture"))
            assertEquals("en-US", body.getJSONObject("languages").getString("source"))
            assertEquals(listOf("zh-CN"), body.getJSONObject("languages").getJSONArray("targets").toList())
            assertEquals(selected.requestedCapabilities(), body.getJSONArray("requestedCapabilities").toList())
            assertEquals(setOf("clientSessionId", "profile", "client", "title", "timezone",
                    "languages", "audioProfile", "requestedCapabilities", "artifactPolicy"), body.keySet())
            // null标题是现有协议的正常可省略字段，不替调用者重新填入参考标题。
            fixture.reply(201, "{\"meetingId\":\"mtg_untitled\",\"limits\":{\"maxMeetingDurationMs\":600000}}")
            fixture.client().createMeeting(selected.withCreateParameters(null, "Europe/Paris", false))
            val untitled = JSONObject(fixture.request().body())
            assertFalse(untitled.has("title"))
            assertEquals("Europe/Paris", untitled.getString("timezone"))
            assertFalse(untitled.getJSONObject("artifactPolicy").getBoolean("retainAudio"))
        }
    }

    @Test fun modelEndpointAndInitParamsComeOnlyFromServer() {
        Fixture().use { fixture ->
            fixture.reply(200, "{\"meetingId\":\"mtg_model\",\"adapterId\":\"partner-format\","
                    + "\"endpoint\":\"wss://model.example.test/signed\",\"expiresAt\":\"2030-01-01T00:00:00Z\","
                    + "\"initParams\":{\"taskId\":\"task-1\"}}")
            val grant = fixture.client().requestModelSession("mtg_model")
            assertEquals("wss://model.example.test/signed", grant.endpoint())
            assertEquals("partner-format", grant.adapterId())
            assertEquals("task-1", grant.initParams().get("taskId"))
            assertEquals("/v1/meetings/mtg_model:modelSession", fixture.request().path())
        }
    }

    @Test fun stopAcceptanceKeepsManifestStateSeparateAndReusesSameStopKey() {
        Fixture().use { fixture ->
            fixture.reply(202, "{\"inputState\":\"INPUT_COMPLETE_PENDING\",\"manifestState\":\"GENERATING\"}")
            fixture.reply(200, "{\"inputState\":\"INPUT_ACCEPTED\",\"finalizationReceipt\":\"receipt-1\","
                    + "\"manifestState\":\"GENERATING\"}")
            val client = fixture.client()
            assertFalse(client.stopMeeting("mtg_stop").accepted())
            val accepted = client.stopMeeting("mtg_stop")
            assertTrue(accepted.accepted())
            assertEquals("GENERATING", accepted.manifestState())
            assertEquals("receipt-1", accepted.finalizationReceipt())
            val first = fixture.request()
            val replay = fixture.request()
            assertNotNull(first.stopKey())
            assertFalse(first.stopKey()!!.isBlank())
            assertEquals(first.stopKey(), replay.stopKey())
        }
    }

    @Test fun explicitStopKeyIsReusedByIndependentClientsForOriginalMeeting() {
        Fixture().use { fixture ->
            fixture.reply(202, "{\"inputState\":\"INPUT_COMPLETE_PENDING\",\"manifestState\":\"GENERATING\"}")
            fixture.reply(200, "{\"inputState\":\"INPUT_ACCEPTED\",\"finalizationReceipt\":\"receipt-1\","
                    + "\"manifestState\":\"GENERATING\"}")
            val firstClient = fixture.client()
            val rebuiltClient = fixture.client()
            val stableStopKey = "original-meeting-stop-key"
            val pending = firstClient.stopMeeting("mtg_stop", stableStopKey)
            val accepted = rebuiltClient.stopMeeting("mtg_stop", stableStopKey)
            assertFalse(pending.accepted())
            assertEquals("INPUT_COMPLETE_PENDING", pending.inputState())
            assertTrue(accepted.accepted())
            assertEquals("INPUT_ACCEPTED", accepted.inputState())
            assertEquals("GENERATING", accepted.manifestState())
            assertEquals("receipt-1", accepted.finalizationReceipt())
            val first = fixture.request()
            val replay = fixture.request()
            assertEquals("/v1/meetings/mtg_stop:stop", first.path())
            assertEquals(first.path(), replay.path())
            assertEquals("", first.body())
            assertEquals(first.body(), replay.body())
            assertEquals("Bearer test-token", first.authorization())
            assertEquals(first.authorization(), replay.authorization())
            assertEquals(stableStopKey, first.stopKey())
            assertEquals(stableStopKey, replay.stopKey())
        }
    }

    @Test fun frozenUsageUsesSameAuthenticatedBodyForFirstSubmitAndReplay() {
        Fixture().use { fixture ->
            val confirmation = "{\"meetingId\":\"mtg_usage\",\"audioDurationMs\":1000,\"accepted\":true}"
            fixture.reply(201, confirmation)
            fixture.reply(200, confirmation)
            val client = fixture.client()
            client.submitUsage("mtg_usage", 1000)
            client.submitUsage("mtg_usage", 1000)
            val first = fixture.request()
            val replay = fixture.request()
            assertEquals("/v1/meetings/mtg_usage/usage:submit", first.path())
            assertEquals(first.path(), replay.path())
            assertEquals(first.body(), replay.body())
            assertEquals(1000, JSONObject(first.body()).getLong("audioDurationMs"))
            assertEquals("Bearer test-token", first.authorization())
            assertEquals(first.authorization(), replay.authorization())
        }
    }

    @Test fun readyMetadataDoesNotDownloadArtifactsForWebOrEmail() {
        Fixture().use { fixture ->
            fixture.reply(200, "{\"status\":\"READY_WITH_WARNINGS\",\"title\":\"本场\",\"artifacts\":["
                    + "{\"type\":\"presentation.capture\",\"status\":\"READY\",\"artifactId\":\"art_slides\"},"
                    + "{\"type\":\"summary.structured\",\"status\":\"PENDING\"}]}")
            val result = fixture.client().readResult("mtg_web", 123, false)
            assertEquals(MeetingResult.Status.READY_WITH_WARNINGS, result.status())
            assertEquals(setOf("presentation.capture"), result.artifactTypes())
            assertNull(result.content())
            assertEquals("/v1/meetings/mtg_web/manifest?genTime=123", fixture.request().path())
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun failedManifestWithHttp200IsNotReady() {
        Fixture().use { fixture ->
            fixture.reply(200, "{\"status\":\"FAILED\",\"artifacts\":[]}")
            val failure = assertThrows(IOException::class.java) { fixture.client().readResult("mtg_failed", 123, false) }
            assertTrue(failure.message!!.contains("FAILED"))
        }
    }

    @Test fun pendingManifestWithHttp202KeepsPollingUntilReady() {
        Fixture().use { fixture ->
            fixture.reply(202, "{\"meetingId\":\"mtg_pending\",\"state\":\"FINALIZING\","
                    + "\"inputState\":\"INPUT_ACCEPTED\",\"finalizationReceipt\":\"receipt-1\","
                    + "\"manifestState\":\"GENERATING\",\"updatedAt\":\"2026-10-07T00:00:00Z\"}")
            fixture.reply(200, "{\"status\":\"READY\",\"artifacts\":[]}")
            val result = fixture.client().readResult("mtg_pending", 123, false)
            assertEquals(MeetingResult.Status.READY, result.status())
            assertNull(result.content())
            assertEquals("/v1/meetings/mtg_pending/manifest?genTime=123", fixture.request().path())
            assertEquals("/v1/meetings/mtg_pending/manifest?genTime=123", fixture.request().path())
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun typedFinalContentsKeepLanguageAssociationSummaryAndOrderedImages() {
        Fixture().use { fixture ->
            fixture.reply(200, "{\"status\":\"READY\",\"title\":null,\"artifacts\":["
                    + "{\"type\":\"presentation.capture\",\"status\":\"READY\",\"revision\":2,\"artifactId\":\"art_slide2\"},"
                    + "{\"type\":\"transcript.final\",\"status\":\"READY\",\"revision\":1,\"artifactId\":\"art_text\"},"
                    + "{\"type\":\"translation.final\",\"status\":\"READY\",\"revision\":1,\"artifactId\":\"art_translation\"},"
                    + "{\"type\":\"summary.structured\",\"status\":\"READY\",\"revision\":1,\"artifactId\":\"art_summary\"},"
                    + "{\"type\":\"presentation.capture\",\"status\":\"READY\",\"revision\":1,\"artifactId\":\"art_slide1\"}]}")
            fixture.reply(200, "{\"language\":\"en-US\",\"text\":\"Hello\",\"segments\":[{\"segmentId\":\"s1\","
                    + "\"startMs\":100,\"endMs\":200,\"language\":\"en-US\",\"text\":\"Hello\",\"sessionSpeakerId\":\"speaker-1\"}]}")
            fixture.reply(200, "{\"translations\":[{\"translationId\":\"t1\",\"sourceSegmentId\":\"s1\","
                    + "\"sourceLanguage\":\"en-US\",\"targetLanguage\":\"zh-CN\",\"text\":\"你好\"}]}")
            fixture.reply(200, "{\"language\":\"en-US\",\"title\":\"Summary\",\"overview\":\"Discussed plan\","
                    + "\"keyPoints\":[\"Start\"],\"actionItems\":[\"Review\"]}")
            fixture.reply(200, "{\"frames\":[{\"offsetMs\":1000,\"jpegBase64\":\"AQI=\"}]}")
            fixture.reply(200, "{\"frames\":[{\"offsetMs\":2000,\"jpegBase64\":\"AwQ=\"}]}")
            val result = fixture.client().readResult("mtg_content", 123, true)
            val content = result.content()!!
            assertEquals("参考会议", result.title())
            assertEquals("Hello", content.transcriptText())
            assertEquals("en-US", content.transcriptSegments().get(0).language())
            assertEquals("speaker-1", content.transcriptSegments().get(0).sessionSpeakerId())
            assertEquals("s1", content.translations().get(0).sourceSegmentId())
            assertEquals("zh-CN", content.translations().get(0).targetLanguage())
            assertEquals(listOf("Review"), content.summary()!!.actionItems())
            assertEquals(1000, content.presentationFrames().get(0).offsetMs())
            assertArrayEquals(byteArrayOf(1, 2), content.presentationFrames().get(0).jpeg())
            assertEquals(2000, content.presentationFrames().get(1).offsetMs())
            for (index in 0 until 6) assertTrue(fixture.request().path().endsWith("?genTime=123"))
        }
    }

    @Test fun presentationFrameOwnsAnImmutableCopyAcrossAsyncUse() {
        val jpeg = byteArrayOf(1, 2)
        val frame = PresentationFrame(1000, jpeg)
        jpeg[0] = 7
        val returned = frame.jpeg()
        returned[1] = 8
        assertArrayEquals(byteArrayOf(1, 2), frame.jpeg())
    }

    @Test fun presentationFirstSaveAndReplayRequireSameSingleFrameAck() {
        Fixture().use { fixture ->
            val ack = "{\"artifactId\":\"art_slide1\",\"chunkSequence\":1,\"frameCount\":1}"
            fixture.reply(201, ack)
            fixture.reply(200, ack)
            val client = fixture.client()
            val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 0xff.toByte(), 0xd9.toByte())
            client.appendPresentation("mtg_slide", 1, 1000, jpeg)
            client.appendPresentation("mtg_slide", 1, 1000, jpeg)
            val first = fixture.request()
            val replay = fixture.request()
            assertEquals("/v1/meetings/mtg_slide/presentation-captures:append", first.path())
            assertEquals("Bearer test-token", first.authorization())
            assertEquals(first.body(), replay.body())
            val body = JSONObject(first.body())
            assertEquals(1, body.getInt("chunkSequence"))
            assertEquals(1, body.getJSONArray("frames").length())
            assertEquals(1000, body.getJSONArray("frames").getJSONObject(0).getLong("offsetMs"))
        }
    }

    @Test fun presentationAckMustMatchChunkSingleFrameAndNonemptyArtifact() {
        Fixture().use { fixture ->
            val unconfirmed = listOf(
                    "{\"artifactId\":\"art_slide2\",\"chunkSequence\":2,\"frameCount\":1}",
                    "{\"artifactId\":\"art_slide1\",\"chunkSequence\":1,\"frameCount\":2}",
                    "{\"artifactId\":\" \",\"chunkSequence\":1,\"frameCount\":1}",
                    "{}")
            val client = fixture.client()
            for (response in unconfirmed) {
                fixture.reply(200, response)
                assertThrows(IOException::class.java) { client.appendPresentation("mtg_slide", 1, 1000, byteArrayOf(1)) }
                assertEquals("/v1/meetings/mtg_slide/presentation-captures:append", fixture.request().path())
            }
        }
    }

    @Test fun presentationJsonOverExistingBudgetFailsBeforeHttpRequest() {
        Fixture().use { fixture ->
            val failure = assertThrows(IOException::class.java) { fixture.client().appendPresentation("mtg_slide", 1, 1000, ByteArray(786432)) }
            assertTrue(failure.message!!.contains("1MiB"))
            assertTrue("本地超限不得发送HTTP请求", fixture.requests.isEmpty())
        }
    }

    @Test fun presentationCanonicalSchemaOverBudgetAlsoFailsBeforeHttpRequest() {
        Fixture().use { fixture ->
            val jpeg = ByteArray(786381)
            jpeg[0] = 0xff.toByte()
            jpeg[1] = 0xd8.toByte()
            jpeg[jpeg.size - 2] = 0xff.toByte()
            jpeg[jpeg.size - 1] = 0xd9.toByte()
            val frames = JSONArray().put(JSONObject().put("offsetMs", 10000)
                    .put("jpegBase64", Base64.getEncoder().encodeToString(jpeg)))
            val request = JSONObject().put("chunkSequence", 1).put("frames", frames)
            assertEquals(1048573, request.toString().toByteArray(StandardCharsets.UTF_8).size)
            // HTTP 请求尚在1MiB内，但后端补 schemaVersion 后已超限；发送前必须拒绝。
            val canonical = JSONObject().put("schemaVersion", "presentation-capture/v1")
                    .put("chunkSequence", 1).put("frames", frames)
            assertEquals(1048615, canonical.toString().toByteArray(StandardCharsets.UTF_8).size)
            HttpMeetingServerClient.validatePresentationRequestSize(1, 10000, ByteArray(750000))
            val failure = assertThrows(IOException::class.java) { fixture.client().appendPresentation("mtg_slide", 1, 10000, jpeg) }
            assertTrue(failure.message!!.contains("1MiB"))
            assertTrue("保存正文超限也不得发送HTTP请求", fixture.requests.isEmpty())
        }
    }

    private fun options(): MeetingOptions = MeetingOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT, "en-US", "zh-CN", false,
        MeetingOptions.ResultDelivery.WEB, "https://result.example.test/index.html", emptyList())
    private data class Reply(val status: Int, val body: String)
    private data class Request(private val path: String, private val body: String, private val authorization: String?, private val stopKey: String?) {
        fun path(): String = path
        fun body(): String = body
        fun authorization(): String? = authorization
        fun stopKey(): String? = stopKey
    }
    private class Fixture : AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val replies: BlockingQueue<Reply> = LinkedBlockingQueue()
        val requests: BlockingQueue<Request> = LinkedBlockingQueue()
        init {
            server.createContext("/") { exchange ->
                requests.add(Request(exchange.requestURI.toString(), String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8),
                    exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("Idempotency-Key")))
                val reply = replies.poll()
                val body = (reply?.body ?: "{}").toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(reply?.status ?: 500, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
            server.start()
        }
        fun url(): String = "http://127.0.0.1:" + server.address.port
        fun client(): HttpMeetingServerClient = HttpMeetingServerClient(MeetingClientConfig.reference(url(), "test-token"))
        fun reply(status: Int, body: String) { replies.add(Reply(status, body)) }
        fun request(): Request {
            val request = requests.poll(3, TimeUnit.SECONDS)
            assertNotNull("正常HTTP请求应到达", request)
            return request!!
        }
        override fun close() { server.stop(0) }
    }
}
