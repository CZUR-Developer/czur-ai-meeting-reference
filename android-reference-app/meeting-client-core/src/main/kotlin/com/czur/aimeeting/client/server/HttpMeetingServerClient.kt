package com.czur.aimeeting.client.server

import com.czur.aimeeting.client.*
import com.czur.aimeeting.model.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/** 参考会议后端 HTTP 实现，身份从冻结配置取得，不写入 APK 或日志。 */
class HttpMeetingServerClient(config: MeetingClientConfig) : MeetingServerClient {
    private val baseUrl = config.serverUrl().removeSuffix("/")
    private val bearerToken = config.accessToken()
    private val identity = config.clientIdentity()
    private val profile = config.profile()
    private val stopKey = UUID.randomUUID().toString()

    @Throws(IOException::class)
    override fun discover(): DeploymentOptions = HttpMeetingSessionClient(baseUrl).deploymentOptions()
    @Throws(IOException::class)
    override fun createMeeting(setup: MeetingOptions): CreatedMeeting {
        try {
            val body = JSONObject().put("clientSessionId", UUID.randomUUID().toString())
                .put("profile", JSONObject().put("id", profile.id()).put("revision", profile.revision()))
                .put("client", JSONObject().put("appId", identity.appId()).put("appVersion", identity.appVersion())
                    .put("contractVersion", identity.contractVersion()).put("deviceId", identity.deviceId()))
                .put("title", setup.title()).put("timezone", setup.timezone())
                .put("languages", JSONObject().put("source", setup.sourceLanguage()).put("targets", JSONArray(setup.targetLanguages())))
                .put("audioProfile", JSONObject().put("encoding", "PCM_S16LE").put("sampleRateHz", 16000)
                    .put("channelCount", 1).put("frameDurationMs", 100))
                .put("requestedCapabilities", JSONArray(setup.requestedCapabilities()))
                .put("artifactPolicy", JSONObject().put("retainAudio", setup.retainAudio()).put("presentationCapture", setup.presentationCapture()))
            val created = JSONObject(post("/v1/meetings", body.toString(), UUID.randomUUID().toString(), 201).body)
            return CreatedMeeting(created.getString("meetingId"), null, MeetingCapabilityState.fromCreateResponse(created),
                created.getJSONObject("limits").getLong("maxMeetingDurationMs"))
        } catch (invalidResponse: JSONException) { throw IOException("创建会议响应格式错误", invalidResponse) }
    }
    @Throws(IOException::class)
    override fun requestModelSession(meetingId: String): ModelSessionGrant {
        try {
            val model = JSONObject(post("/v1/meetings/$meetingId:modelSession", null, null, 200).body)
            val initParams = HashMap<String, String>()
            val params = model.getJSONObject("initParams")
            val keys = params.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                initParams[key] = params.getString(key)
            }
            if (meetingId != model.getString("meetingId")) throw IOException("模型会话与会议 ID 不一致")
            return ModelSessionGrant(meetingId, model.getString("adapterId"), model.getString("endpoint"),
                model.getString("expiresAt"), model.optString("token", null), initParams)
        } catch (invalidResponse: JSONException) { throw IOException("模型会话响应格式错误", invalidResponse) }
    }
    @Throws(IOException::class)
    override fun requestSummarySession(meetingId: String): SummarySessionGrant {
        try {
            val body = JSONObject(post("/v1/meetings/$meetingId:summarySession", null, null, 200).body)
            if (meetingId != body.getString("meetingId")) throw IOException("文本模型材料与会议 ID 不一致")
            return SummarySessionGrant(body.getString("adapterId"), body.getString("endpoint"), body.getString("expiresAt"), body.getString("token"))
        } catch (invalidResponse: JSONException) { throw IOException("文本模型材料响应格式错误", invalidResponse) }
    }
    @Throws(IOException::class)
    override fun startClient(meetingId: String) { post("/v1/meetings/$meetingId:startClient", null, null, 200) }
    @Throws(IOException::class)
    override fun appendTranscript(meetingId: String, clientSequence: Int, event: TranscriptEvent) {
        try {
            val transcript = JSONObject().put("segmentId", event.segmentId()).put("revision", event.revision())
                .put("operation", "UPSERT_OPERATION_UPSERT").put("startMs", event.startMs()).put("endMs", event.endMs())
                .put("language", event.language()).put("text", event.text()).put("final", event.isFinal())
            if (event.sessionSpeakerId() != null) transcript.put("speaker", JSONObject().put("sessionSpeakerId", event.sessionSpeakerId()))
            val body = JSONObject().put("clientEventId", "${event.segmentId()}-r${event.revision()}")
                .put("clientSequence", clientSequence).put("transcriptUpsert", transcript)
            post("/v1/meetings/$meetingId/events:append", body.toString(), null, 200, 201)
        } catch (invalidEvent: JSONException) { throw IOException("字幕事件格式错误", invalidEvent) }
    }
    @Throws(IOException::class)
    override fun appendTranslation(meetingId: String, clientSequence: Int, event: TranslationEvent) {
        try {
            // 与字幕 ID 空间分离，同一修订重试保持同一个 ID。
            val body = JSONObject().put("clientEventId", "translation:${event.translationId()}:r${event.revision()}")
                .put("clientSequence", clientSequence).put("translationUpsert", JSONObject()
                    .put("translationId", event.translationId()).put("sourceSegmentId", event.sourceSegmentId())
                    .put("revision", event.revision()).put("operation", "UPSERT_OPERATION_UPSERT")
                    .put("sourceLanguage", event.sourceLanguage()).put("targetLanguage", event.targetLanguage())
                    .put("text", event.text()).put("final", event.isFinal()).put("supersedesTranslationIds", JSONArray()))
            post("/v1/meetings/$meetingId/events:append", body.toString(), null, 200, 201)
        } catch (invalidEvent: JSONException) { throw IOException("译文事件格式错误", invalidEvent) }
    }
    @Throws(IOException::class)
    override fun appendPresentation(meetingId: String, chunkSequence: Int, offsetMs: Long, jpeg: ByteArray) {
        try {
            val body = presentationBody(chunkSequence, offsetMs, jpeg)
            val response = parseJson(post("/v1/meetings/$meetingId/presentation-captures:append", body, null, 200, 201).body)
            // 首存201／重放200均须确认本包一帧及产物 ID，不将空 JSON 当成功。
            if (response.getInt("chunkSequence") != chunkSequence || response.getInt("frameCount") != 1 ||
                response.getString("artifactId").isBlank()) throw IOException("演示截图保存确认与当前包不一致")
        } catch (invalidResponse: JSONException) { throw IOException("演示截图保存确认格式错误", invalidResponse) }
    }
    @Throws(IOException::class)
    override fun submitUsage(meetingId: String, audioDurationMs: Long) {
        try {
            val response = parseJson(post("/v1/meetings/$meetingId/usage:submit",
                JSONObject().put("audioDurationMs", audioDurationMs).toString(), null, 201, 200).body)
            if (meetingId != response.getString("meetingId") || audioDurationMs != response.getLong("audioDurationMs") ||
                !response.getBoolean("accepted")) throw IOException("采音用量确认与本场冻结值不一致")
        } catch (invalidResponse: JSONException) { throw IOException("采音用量响应格式错误", invalidResponse) }
    }
    @Throws(IOException::class)
    override fun submitSummary(meetingId: String, summary: SummaryResult) {
        try {
            val body = JSONObject().put("language", summary.language()).put("title", summary.title()).put("overview", summary.overview())
                .put("keyPoints", JSONArray(summary.keyPoints())).put("actionItems", JSONArray(summary.actionItems()))
            post("/v1/meetings/$meetingId/summary:submit", body.toString(), null, 200, 201)
        } catch (invalidSummary: JSONException) { throw IOException("标准纪要格式错误", invalidSummary) }
    }
    @Throws(IOException::class)
    override fun stopMeeting(meetingId: String): StopMeetingResponse = stopMeeting(meetingId, stopKey)
    @Throws(IOException::class)
    override fun stopMeeting(meetingId: String, stableStopKey: String): StopMeetingResponse {
        val response = post("/v1/meetings/$meetingId:stop", null, stableStopKey, 200, 202)
        val body = parseJson(response.body)
        return StopMeetingResponse(response.status == 200,
            body.optString("inputState", if (response.status == 200) "INPUT_ACCEPTED" else "INPUT_COMPLETE_PENDING"),
            body.optString("finalizationReceipt", null), body.optString("manifestState", "NOT_STARTED"))
    }
    @Throws(IOException::class)
    override fun sendResultEmail(meetingId: String, recipients: List<String>, resultUrl: String) {
        try {
            val body = JSONObject().put("recipients", JSONArray(recipients)).put("resultUrl", resultUrl)
            post("/v1/meetings/$meetingId:sendResultEmail", body.toString(), null, 200)
        } catch (invalid: JSONException) { throw IOException("邮箱结果请求格式错误", invalid) }
    }
    @Throws(IOException::class)
    override fun readResult(meetingId: String, genTime: Long, includeContent: Boolean): MeetingResult {
        val query = "?genTime=$genTime"
        val encodedMeetingId = URLEncoder.encode(meetingId, StandardCharsets.UTF_8)
        var manifest: JSONObject? = null
        for (attempt in 0 until 20) {
            val response = get("/v1/meetings/$encodedMeetingId/manifest$query", 200, 202)
            if (response.status == 200) {
                val current = parseJson(response.body)
                val currentStatus = current.optString("status")
                if (currentStatus == "READY" || currentStatus == "READY_WITH_WARNINGS") { manifest = current; break }
                if (currentStatus == "FAILED") throw IOException("本场结果未就绪：FAILED")
            }
            try { Thread.sleep(1500L) }
            catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("等待本场结果时被中断", interrupted)
            }
        }
        val readyManifest = manifest ?: throw IOException("本场结果尚未就绪，请稍后重试")
        val status = readyManifest.optString("status")
        val title = if (readyManifest.isNull("title")) "参考会议" else readyManifest.optString("title", "参考会议")
        val artifacts = readyManifest.optJSONArray("artifacts")
        val ready = ArrayList<JSONObject>()
        val types = HashSet<String>()
        if (artifacts != null) for (index in 0 until artifacts.length()) {
            val ref = artifacts.optJSONObject(index)
            if (ref != null && ref.optString("status") == "READY") { ready.add(ref); types.add(ref.optString("type")) }
        }
        // Web／邮件仅查清单，不为生成 PDF 下载所有内容。
        if (!includeContent) return MeetingResult(meetingId, title, genTime, MeetingResult.Status.valueOf(status), types, null)
        ready.sortBy { it.optInt("revision", 0) }
        var transcriptText = ""
        val segments = ArrayList<FinalTranscriptSegment>()
        val translations = ArrayList<FinalTranslation>()
        var summary: SummaryResult? = null
        val frames = ArrayList<PresentationFrame>()
        try {
            for (ref in ready) {
                val type = ref.optString("type")
                if (type !in listOf("transcript.final", "translation.final", "summary.structured", "presentation.capture")) continue
                val artifactId = ref.optString("artifactId")
                if (artifactId.isBlank()) throw IOException("READY 产物缺少 artifactId")
                val content = parseJson(get("/v1/artifacts/${URLEncoder.encode(artifactId, StandardCharsets.UTF_8)}$query", 200).body)
                when (type) {
                    "transcript.final" -> {
                        transcriptText = content.optString("text")
                        segments.clear()
                        val values = content.optJSONArray("segments")
                        if (values != null) for (index in 0 until values.length()) {
                            val value = values.getJSONObject(index)
                            segments.add(FinalTranscriptSegment(value.optString("segmentId"), value.optLong("startMs"), value.optLong("endMs"),
                                value.optString("language", content.optString("language")), value.optString("text"), value.optString("sessionSpeakerId", null)))
                        }
                    }
                    "translation.final" -> {
                        translations.clear()
                        val values = content.optJSONArray("translations")
                        if (values != null) for (index in 0 until values.length()) {
                            val value = values.getJSONObject(index)
                            translations.add(FinalTranslation(value.optString("translationId"), value.optString("sourceSegmentId"),
                                value.optString("sourceLanguage"), value.optString("targetLanguage"), value.optString("text")))
                        }
                    }
                    "summary.structured" -> summary = SummaryResult(content.optString("language"), content.optString("title"),
                        content.optString("overview"), strings(content.optJSONArray("keyPoints")), strings(content.optJSONArray("actionItems")))
                    "presentation.capture" -> {
                        val values = content.optJSONArray("frames")
                        if (values != null) for (index in 0 until values.length()) {
                            val value = values.getJSONObject(index)
                            val jpeg = value.optString("jpegBase64")
                            if (jpeg.isNotEmpty()) frames.add(PresentationFrame(value.optLong("offsetMs"), Base64.getDecoder().decode(jpeg)))
                        }
                    }
                }
            }
        } catch (invalidContent: JSONException) { throw IOException("本场最终产物格式错误", invalidContent) }
        catch (invalidContent: IllegalArgumentException) { throw IOException("本场最终产物格式错误", invalidContent) }
        return MeetingResult(meetingId, title, genTime, MeetingResult.Status.valueOf(status), types,
            MeetingResultContent(types, transcriptText, segments, translations, summary, frames))
    }
    private fun get(path: String, vararg accepted: Int): Response {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            for (expected in accepted) if (status == expected) return connection.inputStream.use { Response(status, readUtf8(it)) }
            throw IOException("读取本场结果失败，HTTP $status")
        } finally { connection.disconnect() }
    }
    private fun post(path: String, jsonBody: String?, idempotencyKey: String?, vararg accepted: Int): Response {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            connection.setRequestProperty("Accept", "application/json")
            if (idempotencyKey != null) connection.setRequestProperty("Idempotency-Key", idempotencyKey)
            val bytes = jsonBody?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
            if (jsonBody != null) connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            for (expected in accepted) if (status == expected) return connection.inputStream.use { Response(status, readUtf8(it)) }
            throw IOException("后端请求失败，HTTP $status")
        } finally { connection.disconnect() }
    }
    private data class Response(val status: Int, val body: String)
    companion object {
        // 传输预算与服务端规范化保存正文一致，Base64／字段／schemaVersion 都计入。
        private const val MAX_PRESENTATION_REQUEST_BYTES = 1_048_576
        /** 只做同编码器的1MiB预检，不分配序号、持有图片或请求 HTTP。 */
        @JvmStatic @Throws(IOException::class)
        fun validatePresentationRequestSize(chunkSequence: Int, offsetMs: Long, jpeg: ByteArray) {
            presentationBody(chunkSequence, offsetMs, jpeg)
        }
        private fun presentationBody(chunkSequence: Int, offsetMs: Long, jpeg: ByteArray): String {
            try {
                val frame = JSONObject().put("offsetMs", offsetMs).put("jpegBase64", Base64.getEncoder().encodeToString(jpeg))
                val frames = JSONArray().put(frame)
                val body = JSONObject().put("chunkSequence", chunkSequence).put("frames", frames).toString()
                // 计入后端真实保存结构，不把 schemaVersion 发给请求。
                val canonical = JSONObject().put("schemaVersion", "presentation-capture/v1").put("chunkSequence", chunkSequence).put("frames", frames).toString()
                if (body.toByteArray(StandardCharsets.UTF_8).size > MAX_PRESENTATION_REQUEST_BYTES ||
                    canonical.toByteArray(StandardCharsets.UTF_8).size > MAX_PRESENTATION_REQUEST_BYTES)
                    throw IOException("演示截图请求或保存JSON超过1MiB")
                return body
            } catch (invalidFrame: JSONException) { throw IOException("演示截图格式错误", invalidFrame) }
        }
        private fun strings(values: JSONArray?): List<String> = if (values == null) emptyList()
            else (0 until values.length()).map(values::getString)
        private fun parseJson(body: String): JSONObject = try { JSONObject(body) }
            catch (invalid: JSONException) { throw IOException("本场结果 JSON 格式错误", invalid) }
        private fun readUtf8(input: InputStream): String {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                output.write(buffer, 0, count)
            }
            return output.toString(StandardCharsets.UTF_8)
        }
    }
}
