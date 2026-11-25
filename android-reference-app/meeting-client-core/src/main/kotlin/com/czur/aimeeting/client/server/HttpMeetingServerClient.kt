package com.czur.aimeeting.client.server

import com.czur.aimeeting.client.*
import com.czur.aimeeting.model.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/** 参考会议后端 HTTP 实现，身份从冻结配置取得，不写入 APK 或日志。 */
class HttpMeetingServerClient(config: MeetingClientConfig) : MeetingServerClient {
    /** 录音发布器只需要地址和本场冻结 Token，不重新读取或刷新应用身份。 */
    constructor(serverUrl: String, accessToken: String) : this(
        MeetingClientConfig.reference(serverUrl, accessToken))

    private val baseUrl = config.serverUrl().removeSuffix("/")
    private val bearerToken = config.accessToken()
    private val identity = config.clientIdentity()
    private val profile = config.profile()
    private val stopKey = UUID.randomUUID().toString()

    @Throws(IOException::class)
    override fun discover(): DeploymentOptions = HttpMeetingSessionClient(baseUrl).deploymentOptions()
    @Throws(IOException::class)
    override fun listMeetings(pageToken: String?, pageSize: Int): MeetingHistoryPage {
        val query = StringBuilder("?pageSize=").append(pageSize.coerceIn(1, 200))
        if (!pageToken.isNullOrBlank()) {
            query.append("&pageToken=").append(URLEncoder.encode(pageToken, StandardCharsets.UTF_8))
        }
        val body = parseJson(get("/v1/meetings$query", 200).body)
        val items = ArrayList<MeetingHistoryItem>()
        val values = body.optJSONArray("items")
        if (values != null) for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: continue
            val meetingId = item.optString("meetingId")
            if (meetingId.isBlank()) continue
            items.add(MeetingHistoryItem(
                meetingId = meetingId,
                title = if (item.isNull("title")) null else item.optString("title").takeIf { it.isNotBlank() },
                state = item.optString("state"),
                manifestState = if (item.isNull("manifestState")) null
                    else item.optString("manifestState").takeIf { it.isNotBlank() },
                createdAt = if (item.isNull("createdAt")) null
                    else item.optString("createdAt").takeIf { it.isNotBlank() },
                updatedAt = if (item.isNull("updatedAt")) null
                    else item.optString("updatedAt").takeIf { it.isNotBlank() }
            ))
        }
        val nextPageToken = if (body.isNull("nextPageToken")) null
            else body.optString("nextPageToken").takeIf { it.isNotBlank() }
        return MeetingHistoryPage(items, nextPageToken)
    }
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
            val limits = created.getJSONObject("limits")
            return CreatedMeeting(created.getString("meetingId"), null, MeetingCapabilityState.fromCreateResponse(created),
                limits.getLong("maxMeetingDurationMs"),
                limits.optLong("heartbeatIntervalMs", 0L).coerceAtLeast(0L))
        } catch (invalidResponse: JSONException) { throw IOException("创建会议响应格式错误", invalidResponse) }
    }
    @Throws(IOException::class)
    override fun requestModelSession(meetingId: String): ModelSessionGrant {
        try {
            val model = JSONObject(post("/v1/meetings/$meetingId:modelSession", null, null, 200,
                readTimeoutMs = 30_000).body)
            val initParams = HashMap<String, String>()
            val params = model.getJSONObject("initParams")
            val keys = params.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                initParams[key] = params.getString(key)
            }
            if (meetingId != model.getString("meetingId")) throw IOException("模型会话与会议 ID 不一致")
            return ModelSessionGrant(meetingId, model.getString("adapterId"), model.getString("endpoint"),
                model.getString("expiresAt"), model.optString("token", null), initParams,
                model.optJSONObject("translationSession")?.let { translation ->
                    TranslationSessionGrant(translation.getString("adapterId"), translation.getString("endpoint"),
                        translation.getString("expiresAt"), translation.optString("token", null),
                        modelParams(translation.optJSONObject("initParams")))
                })
        } catch (invalidResponse: JSONException) { throw IOException("模型会话响应格式错误", invalidResponse) }
    }
    @Throws(IOException::class)
    override fun requestSummary(meetingId: String, languages: List<String>): SummaryRequestAck {
        if (languages.isEmpty() || languages.size > 2) throw IOException("纪要语言数量必须为1～2个")
        try {
            val body = JSONObject().put("languages", JSONArray(languages))
            val response = parseJson(post("/v1/meetings/$meetingId/summary:request",
                body.toString(), null, 200, 201).body)
            if (response.optString("meetingId") != meetingId) throw IOException("纪要请求确认与会议 ID 不一致")
            return SummaryRequestAck(meetingId, response.optBoolean("accepted", true),
                response.optString("summaryStatus").takeIf { it.isNotBlank() })
        } catch (invalidResponse: JSONException) { throw IOException("纪要请求响应格式错误", invalidResponse) }
    }
    @Deprecated("服务端生成纪要后不再由设备调用，改用 requestSummary")
    @Throws(IOException::class)
    override fun requestSummarySession(meetingId: String): SummarySessionGrant {
        try {
            val body = JSONObject(post("/v1/meetings/$meetingId:summarySession", null, null, 200,
                readTimeoutMs = 30_000).body)
            if (meetingId != body.getString("meetingId")) throw IOException("文本模型材料与会议 ID 不一致")
            return SummarySessionGrant(body.getString("adapterId"), body.getString("endpoint"),
                body.getString("expiresAt"), body.optString("token", null), modelParams(body.optJSONObject("initParams")))
        } catch (invalidResponse: JSONException) { throw IOException("文本模型材料响应格式错误", invalidResponse) }
    }
    @Throws(IOException::class)
    override fun requestTranslationSession(meetingId: String): TranslationSessionGrant {
        try {
            val body = JSONObject(post("/v1/meetings/$meetingId:translationSession", null, null, 200,
                readTimeoutMs = 30_000).body)
            if (meetingId != body.getString("meetingId")) throw IOException("翻译材料与会议 ID 不一致")
            return TranslationSessionGrant(body.getString("adapterId"), body.getString("endpoint"),
                body.getString("expiresAt"), body.optString("token", null), modelParams(body.optJSONObject("initParams")))
        } catch (invalid: JSONException) { throw IOException("翻译材料响应格式错误", invalid) }
    }
    /** 可选厂商参数只传给匹配 Adapter；旧服务不提供时得到空只读材料。 */
    private fun modelParams(params: JSONObject?): Map<String, String> = buildMap {
        if (params != null) {
            val keys = params.keys()
            while (keys.hasNext()) { val key = keys.next(); put(key, params.getString(key)) }
        }
    }
    @Throws(IOException::class)
    override fun startClient(meetingId: String) { post("/v1/meetings/$meetingId:startClient", null, null, 200) }
    @Throws(IOException::class)
    override fun sendHeartbeat(meetingId: String) {
        // 心跳无请求体、无幂等键；服务端用自身时钟记录开始与最后活跃。
        post("/v1/meetings/$meetingId:heartbeat", null, null, 200)
    }
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
        appendPresentation(meetingId, chunkSequence, offsetMs, jpeg, null)
    }

    @Throws(IOException::class)
    override fun appendPresentation(
        meetingId: String,
        chunkSequence: Int,
        offsetMs: Long,
        jpeg: ByteArray,
        ocrText: String?,
    ) {
        try {
            val body = presentationBody(chunkSequence, offsetMs, jpeg, ocrText)
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
    @Deprecated("服务端生成纪要后不再由设备调用，改用 requestSummary")
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
        // Stop 同步等待本场结算和最终产物，长会可超过普通事件的10秒预算。
        // 仅放宽本动作读取等待；仍要求原键真实ACK，不增加自动重试或放宽连接超时。
        val response = post("/v1/meetings/$meetingId:stop", null, stableStopKey, 200, 202,
            readTimeoutMs = 120_000)
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

    /**
     * 发布 SDK 已封口的原始音频。正文始终是文件流，服务端返回的登记事实必须与本场
     * meetingId、文件长度和 accepted 标记一致；不把对象地址或存储 binding 传播到 App。
     */
    @Throws(IOException::class)
    fun publishAudio(meetingId: String, audio: SealedAudioArtifact) {
        if (meetingId.isBlank() || meetingId != audio.meetingId()) {
            throw IOException("音频产物与会议 ID 不一致")
        }
        val file = audio.file()
        val size = file.length()
        if (!file.isFile || size <= 0L || size != audio.encodedBytes()) {
            throw IOException("封口音频文件事实不一致")
        }
        val checksum = sha256(file)
        val response = postBinary("/v1/meetings/$meetingId/audio-original", file,
            audio.contentType(), size, checksum)
        try {
            val body = JSONObject(response.body)
            if (body.getString("meetingId") != meetingId
                || body.getLong("size") != size
                || body.getString("artifactId").isBlank()
                || !body.getBoolean("accepted")) {
                throw IOException("音频登记确认与本场封口事实不一致")
            }
        } catch (invalid: JSONException) {
            throw IOException("音频登记响应格式错误", invalid)
        }
    }
    @Throws(IOException::class)
    override fun readResult(meetingId: String, genTime: Long, includeContent: Boolean): MeetingResult =
        resultFromManifest(awaitReadyManifest(meetingId, genTime), meetingId, genTime, includeContent)

    @Throws(IOException::class)
    override fun readSavedReadyResult(meetingId: String, genTime: Long, includeContent: Boolean): MeetingResult =
        resultFromCheckpointManifest(awaitReadyManifest(meetingId, genTime), meetingId, genTime, includeContent)

    private fun awaitReadyManifest(meetingId: String, genTime: Long): JSONObject {
        val encodedMeetingId = URLEncoder.encode(meetingId, StandardCharsets.UTF_8)
        for (attempt in 0 until 20) {
            val response = get("/v1/meetings/$encodedMeetingId/manifest?genTime=$genTime", 200, 202)
            if (response.status == 200) {
                val current = parseJson(response.body)
                val currentStatus = current.optString("status")
                if (currentStatus == "READY" || currentStatus == "READY_WITH_WARNINGS") return current
                if (currentStatus == "FAILED") throw IOException("本场结果未就绪：FAILED")
            }
            try { Thread.sleep(1500L) }
            catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("等待本场结果时被中断", interrupted)
            }
        }
        throw IOException("本场结果尚未就绪，请稍后重试")
    }

    @Throws(IOException::class)
    override fun readReconciledResult(meetingId: String, genTime: Long): MeetingResult {
        val path = "/v1/meetings/" + URLEncoder.encode(meetingId, StandardCharsets.UTF_8)
        val ownerStatus = parseJson(get(path, 200, followRedirects = false).body)
        val state = ownerStatus.opt("state")
        if (ownerStatus.opt("meetingId") != meetingId) rejectReconciliation("状态响应的会议不匹配")
        if (state != "READY" && state != "READY_WITH_WARNINGS") {
            val reason = when (state) {
                "CANCELLED", "FAILED", "DELETION_PENDING", "DELETED" -> "会议状态为 $state"
                else -> "会议尚无可确认的成功终态"
            }
            rejectReconciliation(reason)
        }
        if (ownerStatus.opt("inputState") != "INPUT_ACCEPTED" || ownerStatus.opt("manifestState") != state)
            rejectReconciliation("输入或清单状态与成功终态不一致")

        val manifest = parseJson(get("$path/manifest?genTime=$genTime", 200, followRedirects = false).body)
        if (manifest.opt("status") != state) rejectReconciliation("清单状态与原场终态不一致")
        return resultFromCheckpointManifest(manifest, meetingId, genTime, includeContent = false)
    }

    private fun rejectReconciliation(reason: String): Nothing {
        java.util.logging.Logger.getLogger(HttpMeetingServerClient::class.java.name).warning("原场终态核验失败：$reason")
        throw IOException(reason)
    }

    private fun resultFromCheckpointManifest(manifest: JSONObject, meetingId: String, genTime: Long,
        includeContent: Boolean): MeetingResult {
        val manifestProfile = manifest.optJSONObject("profile")
        if (manifest.opt("meetingId") != meetingId || manifestProfile?.opt("id") != profile.id() ||
            manifestProfile.opt("revision") != profile.revision())
            rejectReconciliation("结果清单的会议或档位与原场不一致")
        val artifacts = manifest.optJSONArray("artifacts") ?: rejectReconciliation("清单缺少产物数组")
        for (i in 0 until artifacts.length()) {
            val item = artifacts.optJSONObject(i) ?: rejectReconciliation("清单产物格式错误")
            when (item.opt("status")) {
                "PENDING", "FAILED", "DELETED" -> Unit
                "READY" -> if ((item.opt("artifactId") as? String).isNullOrBlank() ||
                    (item.opt("type") as? String).isNullOrBlank()) rejectReconciliation("就绪产物缺少标识或类型")
                else -> rejectReconciliation("清单产物状态无效")
            }
        }
        return resultFromManifest(manifest, meetingId, genTime, includeContent)
    }

    private fun resultFromManifest(readyManifest: JSONObject, meetingId: String, genTime: Long,
        includeContent: Boolean): MeetingResult {
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
        val query = "?genTime=$genTime"
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
    private fun get(path: String, vararg accepted: Int,
        followRedirects: Boolean = HttpURLConnection.getFollowRedirects()): Response {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = followRedirects
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            for (expected in accepted) if (status == expected) return connection.inputStream.use { Response(status, readUtf8(it)) }
            throw IOException("读取本场结果失败，HTTP $status")
        } finally { connection.disconnect() }
    }
    /** 模型材料发行允许等待厂商20秒，调用处单独留30秒；普通JSON请求保持原10秒。 */
    private fun post(path: String, jsonBody: String?, idempotencyKey: String?, vararg accepted: Int,
        readTimeoutMs: Int = 10_000): Response {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = readTimeoutMs
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
            // 错误正文只取稳定 code；不把正文或 messageKey 带进异常和日志。
            throw MeetingHttpStatusException(status, readErrorCode(connection.errorStream))
        } finally { connection.disconnect() }
    }

    private fun postBinary(path: String, file: File, contentType: String,
        contentLength: Long, checksum: String): Response {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("Content-Length", contentLength.toString())
            connection.setRequestProperty("X-Content-SHA256", checksum)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(contentLength)
            file.inputStream().use { input ->
                connection.outputStream.use { output -> input.copyTo(output, 16 * 1024) }
            }
            val status = connection.responseCode
            if (status != 200 && status != 201) {
                throw IOException("音频上传失败，HTTP $status")
            }
            return connection.inputStream.use { Response(status, readUtf8(it)) }
        } finally { connection.disconnect() }
    }

    private fun sha256(file: File): String {
        val digest = try { MessageDigest.getInstance("SHA-256") }
        catch (missing: Exception) { throw IOException("SHA-256 不可用", missing) }
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val hex = StringBuilder("sha256:")
        for (value in digest.digest()) hex.append("%02x".format(value.toInt() and 0xff))
        return hex.toString()
    }
    private data class Response(val status: Int, val body: String)
    companion object {
        // 传输预算与服务端规范化保存正文一致，Base64／字段／schemaVersion 都计入。
        private const val MAX_PRESENTATION_REQUEST_BYTES = 1_048_576
        /** 错误正文只需要读到稳定 code；固定上限避免异常响应拖住调用方或占用内存。 */
        private const val MAX_ERROR_BODY_BYTES = 8 * 1024
        /** 只做同编码器的1MiB预检，不分配序号、持有图片或请求 HTTP。 */
        @JvmStatic @Throws(IOException::class)
        fun validatePresentationRequestSize(chunkSequence: Int, offsetMs: Long, jpeg: ByteArray) {
            presentationBody(chunkSequence, offsetMs, jpeg, null)
        }
        @JvmStatic @Throws(IOException::class)
        fun validatePresentationRequestSize(chunkSequence: Int, offsetMs: Long, jpeg: ByteArray, ocrText: String?) {
            presentationBody(chunkSequence, offsetMs, jpeg, ocrText)
        }
        private fun presentationBody(chunkSequence: Int, offsetMs: Long, jpeg: ByteArray): String =
            presentationBody(chunkSequence, offsetMs, jpeg, null)
        private fun presentationBody(chunkSequence: Int, offsetMs: Long, jpeg: ByteArray, ocrText: String?): String {
            try {
                val frame = JSONObject().put("offsetMs", offsetMs).put("jpegBase64", Base64.getEncoder().encodeToString(jpeg))
                if (!ocrText.isNullOrBlank()) frame.put("ocrText", ocrText)
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

        /** 只读取 ErrorEnvelope 的稳定 code；缺字段、非 JSON 或读取失败一律视为无 code。 */
        private fun readErrorCode(input: InputStream?): String? {
            if (input == null) return null
            return try {
                val bytes = input.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (output.size() < MAX_ERROR_BODY_BYTES) {
                        val count = stream.read(buffer, 0,
                            minOf(buffer.size, MAX_ERROR_BODY_BYTES - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                JSONObject(String(bytes, StandardCharsets.UTF_8))
                    .optJSONObject("error")?.optString("code")?.takeIf { it.isNotBlank() }
            } catch (unreadable: Exception) { null }
        }
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
