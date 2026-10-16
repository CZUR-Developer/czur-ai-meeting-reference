package com.czur.aimeeting.client.provider

import com.czur.aimeeting.model.*
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.logging.Logger

/**
 * 听悟公开协议解析器，只构造启动／停止消息及归一化字幕／译文，不持有网络或 MIC。
 * 每个任务新实例；partial 完整替换，同 index 修订递增。互译按原文 final 顺序等待配对译文，
 * 结束保留缺译文的已确认原文。迟到语种／发言人可更新事实，旧 partial 不重开 final 或覆盖正文。
 */
class TingwuModelAdapter : ModelAdapter {
    private val segments = HashMap<String, SegmentState>()
    private val translations = HashMap<String, SegmentState>()
    private val sentenceStarts = HashMap<Int, Long>()
    private val sentenceSpeakers = HashMap<Int, String>()
    private val sources = LinkedHashMap<Int, SourceState>()
    private val latestTranslations = HashMap<Int, TranslationEvent>()
    /** 按原文 final 到达顺序，译文乱序不能抢先收后续原文。 */
    private val pendingFinals = LinkedHashSet<Int>()
    private val emptyTranslationFinals = LinkedHashSet<Int>()
    private val finalizedIndexes = LinkedHashSet<Int>()
    private var fallbackIndex = 0
    @Volatile private var translationEnabled = false

    override fun id(): String = ID
    override fun mayEmitDeferredSourceLanguage(): Boolean = true
    override fun supportsSessionReconnect(): Boolean = true
    /** 仅交出已收到 SentenceEnd 的真实 final，不把 stash／当前 partial 伪造成 final。 */
    override fun drainConfirmedOnDisconnect(transcriptListener: TranscriptListener) { drainSourceFinals(true, transcriptListener) }
    override fun startMessage(grant: ModelSessionGrant): String {
        try {
            // 本场开关来自 Server 已冻结能力，不从目标语种／全局配置推断。
            translationEnabled = grant.initParams()["translationEnabled"] == "true"
            return command("StartTranscription", taskId(grant)).put("payload", JSONObject()
                .put("enable_intermediate_result", true).put("enable_translate_result", translationEnabled)).toString()
        } catch (invalid: JSONException) { throw IllegalStateException("无法构造听悟启动消息", invalid) }
    }
    override fun stopMessage(grant: ModelSessionGrant): String {
        try { return command("StopTranscription", taskId(grant)).toString() }
        catch (invalid: JSONException) { throw IllegalStateException("无法构造听悟停止消息", invalid) }
    }
    override fun onServerMessage(message: String, listener: TranscriptListener): ModelSignal =
        onServerMessage(message, listener, TranslationListener {})
    override fun onServerMessage(message: String, transcriptListener: TranscriptListener,
        translationListener: TranslationListener): ModelSignal =
        onServerMessage(message, transcriptListener, translationListener, SegmentStartedListener {})

    override fun onServerMessage(message: String, transcriptListener: TranscriptListener,
        translationListener: TranslationListener, segmentStartedListener: SegmentStartedListener): ModelSignal {
        try {
            val root = JSONObject(message)
            val header = root.optJSONObject("header")
            val name = header?.optString("name", header.optString("event", "")) ?: ""
            if (name == "TranscriptionStarted") return ModelSignal.AUDIO_READY
            if (name == "TaskFailed" || name == "TranscriptionFailed") {
                // 只记录已确认失败事件名／数字状态，不输出正文／status_text／Throwable。
                LOG.warning("听悟失败事件 name=$name status=${header!!.optLong("status", -1)}")
                throw IllegalStateException("听悟会话失败：$name")
            }
            val payload = root.optJSONObject("payload")
            if (payload != null) {
                val index = payload.optInt("index", -1)
                val speaker = speakerId(payload)
                if (index >= 0 && speaker != null) sentenceSpeakers[index] = speaker
                if (name == "SentenceBegin" && payload.has("index")) {
                    val startMs = maxOf(0L, payload.optLong("time", 0))
                    if (!sentenceStarts.containsKey(index)) {
                        sentenceStarts[index] = startMs
                        segmentStartedListener.onSegmentStarted(SegmentStartedEvent("tingwu-$index", startMs))
                    }
                }
                val text = payload.optString("result", "").trim()
                if (text.isNotEmpty() && (name == "TranscriptionResultChanged" || name == "SentenceEnd")) {
                    val isFinal = name == "SentenceEnd"
                    if (index < 0) emit(payload, text, isFinal, transcriptListener)
                    else if (finalizedIndexes.contains(index)) {
                        val finalized = sources[index]
                        if (isFinal) rememberSource(index, payload, text, true) else updateSourceFacts(finalized, payload)
                        emitSource(index, true, transcriptListener)
                    } else {
                        rememberSource(index, payload, text, isFinal)
                        rememberStash(payload, segmentStartedListener)
                        if (isFinal) pendingFinals.add(index)
                        if (!isFinal || translationEnabled) emitSource(index, false, transcriptListener)
                        drainSourceFinals(false, transcriptListener)
                    }
                }
                if (translationEnabled && payload.has("translate_result"))
                    emitTranslations(payload, transcriptListener, translationListener)
            }
            if (name == "TranscriptionCompleted" || name == "Completed") {
                drainSourceFinals(true, transcriptListener)
                return ModelSignal.FINISHED
            }
            return ModelSignal.NONE
        } catch (invalid: JSONException) { throw IllegalArgumentException("听悟消息格式错误", invalid) }
    }

    /** 独立 translate_result 的 index 对应原文，先回传源语种修订再派发配对译文。 */
    private fun emitTranslations(payload: JSONObject, transcriptListener: TranscriptListener, listener: TranslationListener) {
        val results = payload.optJSONArray("translate_result") ?: return
        val declaredSource = payload.optString("source_lang", "")
        val declaredTarget = payload.optString("target_lang", "")
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val index = item.optInt("index", -1)
            val text = item.optString("text", "").trim()
            var source = translationLanguage(item.optString("detected_source_lang", ""))
            if (source.isEmpty()) source = translationLanguage(declaredSource)
            val target = translationLanguage(declaredTarget)
            if (index < 0 || source.isEmpty() || target.isEmpty() || source.equals(target, true)) continue
            val isFinal = !item.optBoolean("partial", false)
            val sourceState = sources[index]
            if (sourceState != null) {
                sourceState.language = source
                speakerId(item)?.let { sourceState.speaker = it }
                if (finalizedIndexes.contains(index)) emitSource(index, true, transcriptListener)
            }
            if (text.isEmpty() || text.equals("null", true)) {
                if (isFinal) {
                    emptyTranslationFinals.add(index)
                    drainSourceFinals(false, transcriptListener)
                }
                continue
            }
            val id = "tingwu-translation-$index-$target"
            val previous = translations[id]
            if (previous != null && (previous.isFinal && !isFinal || previous.isFinal == isFinal &&
                previous.text == text && previous.language == source)) continue
            val revision = if (previous == null) 1 else previous.revision + 1
            val event = TranslationEvent(id, "tingwu-$index", revision, source, target, text, isFinal)
            latestTranslations[index] = event
            translations[id] = SegmentState(revision, text, isFinal, source, null)
            if (sourceState != null && !finalizedIndexes.contains(index)) emitSource(index, false, transcriptListener)
            listener.onTranslation(event)
            drainSourceFinals(false, transcriptListener)
            if (isFinal) emitLatestStash(transcriptListener)
        }
    }
    private fun rememberSource(index: Int, payload: JSONObject, text: String, sourceFinal: Boolean) {
        val previous = sources[index]
        if (previous != null && previous.sourceFinal && !sourceFinal) {
            // 等待译文期间的迟到 partial 只补事实，不改已确认正文与时间。
            updateSourceFacts(previous, payload)
            return
        }
        val knownStart = previous?.startMs ?: sentenceStarts.getOrDefault(index, 0L)
        val startMs = maxOf(0L, payload.optLong("begin_time", payload.optLong("start_time", knownStart)))
        val endMs = maxOf(startMs, payload.optLong("time", payload.optLong("end_time", startMs)))
        var detected = language(payload.optString("detected_source_lang", payload.optString("source_lang", payload.optString("source_language", ""))))
        if (detected == "und" && previous != null) detected = previous.language
        if (detected == "und" && latestTranslations.containsKey(index)) detected = latestTranslations.getValue(index).sourceLanguage()
        val speaker = speakerId(payload) ?: if (previous == null) sentenceSpeakers[index] else previous.speaker
        sources[index] = SourceState(text, detected, startMs, endMs, speaker, sourceFinal || previous?.sourceFinal == true)
    }
    /** stash 是下一句提前快照，不拼入上一句，已确认正文仍不可被 stash 改写。 */
    private fun rememberStash(payload: JSONObject, beginListener: SegmentStartedListener) {
        val stash = payload.optJSONObject("stash_result") ?: return
        val index = stash.optInt("index", -1)
        val text = stash.optString("text", "").trim()
        if (index < 0 || text.isEmpty() || finalizedIndexes.contains(index)) return
        val startMs = maxOf(0L, stash.optLong("beginTime", payload.optLong("time", 0)))
        if (!sentenceStarts.containsKey(index)) {
            sentenceStarts[index] = startMs
            beginListener.onSegmentStarted(SegmentStartedEvent("tingwu-$index", startMs))
        }
        val previous = sources[index]
        if (previous?.sourceFinal == true) {
            updateSourceFacts(previous, stash)
            return
        }
        val detected = language(stash.optString("detected_source_lang", ""))
        val speaker = speakerId(stash)
        sources[index] = SourceState(text, if (detected == "und" && previous != null) previous.language else detected,
            previous?.startMs ?: startMs, previous?.endMs ?: startMs,
            if (speaker == null && previous != null) previous.speaker else speaker, previous?.sourceFinal == true)
    }
    private fun emitSource(index: Int, isFinal: Boolean, listener: TranscriptListener) {
        val state = sources[index] ?: return
        val id = "tingwu-$index"
        val previous = segments[id]
        if (previous != null && previous.text == state.text && previous.language == state.language &&
            previous.isFinal == isFinal && previous.speaker == state.speaker) return
        val revision = if (previous == null) 1 else previous.revision + 1
        listener.onTranscript(TranscriptEvent(id, revision, state.startMs, state.endMs, state.language, state.text, isFinal, state.speaker))
        segments[id] = SegmentState(revision, state.text, isFinal, state.language, state.speaker)
    }
    /** 按原文确认顺序等待同 index 译文 final，完成／断线时可只保留已确认原文。 */
    private fun drainSourceFinals(forceWithoutTranslation: Boolean, listener: TranscriptListener) {
        val iterator = pendingFinals.iterator()
        while (iterator.hasNext()) {
            val index = iterator.next()
            val translation = latestTranslations[index]
            val translated = translation?.isFinal() == true || emptyTranslationFinals.contains(index)
            if (translationEnabled && !translated && !forceWithoutTranslation) break
            emitSource(index, true, listener)
            finalizedIndexes.add(index)
            iterator.remove()
        }
    }
    private fun emitLatestStash(listener: TranscriptListener) {
        var latest: Int? = null
        for ((index, source) in sources) if (!source.sourceFinal && !finalizedIndexes.contains(index)) latest = index
        if (latest != null) emitSource(latest, false, listener)
    }
    private fun emit(payload: JSONObject, text: String, isFinal: Boolean, listener: TranscriptListener) {
        val index = payload.optInt("index", -1)
        val knownStart = if (index < 0) 0L else sentenceStarts.getOrDefault(index, 0L)
        val startMs = maxOf(0L, payload.optLong("begin_time", payload.optLong("start_time", knownStart)))
        val endMs = maxOf(startMs, payload.optLong("time", payload.optLong("end_time", startMs)))
        val segmentId = when {
            index >= 0 -> "tingwu-$index"
            startMs > 0 -> "tingwu-at-$startMs"
            else -> {
                val id = "tingwu-fallback-$fallbackIndex"
                if (isFinal) fallbackIndex++
                id
            }
        }
        val previous = segments[segmentId]
        val language = language(payload.optString("detected_source_lang", payload.optString("source_lang", payload.optString("source_language", ""))))
        val speaker = speakerId(payload) ?: if (index >= 0) sentenceSpeakers[index] else null
        if (previous != null) {
            if (previous.isFinal) return
            if (!isFinal && previous.text == text && previous.language == language && previous.speaker == speaker) return
        }
        val revision = if (previous == null) 1 else previous.revision + 1
        listener.onTranscript(TranscriptEvent(segmentId, revision, startMs, endMs, language, text, isFinal, speaker))
        segments[segmentId] = SegmentState(revision, text, isFinal, language, speaker)
    }
    private data class SegmentState(val revision: Int, val text: String, val isFinal: Boolean,
        val language: String = "", val speaker: String? = null)
    private class SourceState(val text: String, var language: String, val startMs: Long, val endMs: Long,
        var speaker: String?, val sourceFinal: Boolean)

    companion object {
        const val ID = "tingwu-realtime"
        private val LOG = Logger.getLogger(TingwuModelAdapter::class.java.name)
        private fun updateSourceFacts(state: SourceState?, payload: JSONObject) {
            if (state == null) return
            val detected = language(payload.optString("detected_source_lang", payload.optString("source_lang", payload.optString("source_language", ""))))
            if (detected != "und") state.language = detected
            speakerId(payload)?.let { state.speaker = it }
        }
        private fun speakerId(payload: JSONObject): String? {
            speakerIdIn(payload)?.let { return it }
            speakerIdIn(payload.optJSONObject("transcription"))?.let { return it }
            val words = payload.optJSONArray("words")
            if (words != null) for (index in 0 until words.length()) speakerIdIn(words.optJSONObject(index))?.let { return it }
            return null
        }
        private fun speakerIdIn(source: JSONObject?): String? {
            if (source == null) return null
            for (key in arrayOf("SpeakerId", "speaker_id", "speakerId", "speaker")) {
                val value = source.optString(key, "").trim()
                if (value.isNotEmpty() && !value.equals("null", true)) return value
            }
            return null
        }
        private fun taskId(grant: ModelSessionGrant): String {
            val taskId = grant.initParams()["taskId"]
            require(taskId != null && taskId.trim().isNotEmpty()) { "听悟会话缺少 taskId" }
            return taskId
        }
        private fun command(name: String, taskId: String): JSONObject = JSONObject().put("header", JSONObject()
            .put("message_id", UUID.randomUUID().toString().replace("-", "")).put("task_id", taskId)
            .put("namespace", "SpeechTranscriber").put("name", name).put("appkey", "default"))
        private fun language(raw: String): String = when (raw.trim().replace('_', '-').uppercase(Locale.ROOT)) {
            "CN", "ZH", "ZH-CN" -> "zh-CN"
            "EN", "EN-US" -> "en-US"
            "JA", "JP", "JA-JP" -> "ja-JP"
            "KO", "KR", "KO-KR" -> "ko-KR"
            "DE", "DE-DE" -> "de-DE"
            "FR", "FR-FR" -> "fr-FR"
            "RU", "RU-RU" -> "ru-RU"
            else -> if (raw.trim().isEmpty() || raw.equals("auto", true) || raw.equals("multilingual", true)) "und" else raw
        }
        /** 译文未知不能默认中文，避免误锁 A 栏。 */
        private fun translationLanguage(raw: String): String {
            val trimmed = raw.trim()
            return if (trimmed.isEmpty() || trimmed.equals("auto", true) || trimmed.equals("multilingual", true)) "" else language(trimmed)
        }
    }
}
