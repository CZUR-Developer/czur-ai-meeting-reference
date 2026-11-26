package com.czur.aimeeting.client.server

import com.czur.aimeeting.model.TranscriptEvent
import com.czur.aimeeting.model.TranslationEvent
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The persisted payload is the exact original HTTP request body, not reconstructed text. */
internal class StandardEventPacket private constructor(
    val body: String,
    private val json: JSONObject,
    val sequence: Int,
    val key: String,
    private val type: String,
) {
    fun requireReceipt(meetingId: String, response: String) {
        val receipt = JSONObject(response)
        val expected = json.getJSONObject(type)
        val confirmed = receipt.optJSONObject(type)
        val validBody = confirmed != null && (if (type == TRANSCRIPT) {
            fieldMatches(expected, confirmed, "segmentId", "language")
                && number(expected, "startMs") == number(confirmed, "startMs", zeroIfMissing = true)
                && number(expected, "endMs") == number(confirmed, "endMs", zeroIfMissing = true)
                && (!confirmed.has("speaker") || confirmed.optJSONObject("speaker") != null)
                && matchesSpeaker(expected.optJSONObject("speaker"), confirmed.optJSONObject("speaker"))
        } else {
            fieldMatches(expected, confirmed, "translationId", "sourceSegmentId", "sourceLanguage", "targetLanguage")
                && stringList(expected, "supersedesTranslationIds") == stringList(confirmed, "supersedesTranslationIds")
        }) && fieldMatches(expected, confirmed, "operation")
            && number(expected, "revision") == number(confirmed, "revision")
            && expected.getString("text") == (if (confirmed.has("text")) confirmed.getString("text") else "")
            && expected.getBoolean("final") == (if (confirmed.has("final")) confirmed.getBoolean("final") else false)
        if (receipt.opt("specVersion") != "0.1" || receipt.opt("meetingId") != meetingId ||
            receipt.opt("streamId") != meetingId || receipt.opt("eventId") != serverEventId(meetingId, key) ||
            number(receipt, "eventSequence") <= 0 ||
            receipt.has(if (type == TRANSCRIPT) TRANSLATION else TRANSCRIPT) || !validBody) {
            throw IOException("标准事件回执与原场原事件不匹配")
        }
    }

    /** B9 的诊断行只可用来绑定仍存在的原包，不能用来重建请求。 */
    fun matchesLegacyFinal(line: JSONObject): Boolean {
        val original = json.optJSONObject(TRANSCRIPT) ?: return false
        return original.getBoolean("final") &&
            fieldMatches(original, line, "segmentId", "language", "text") &&
            number(original, "revision") == number(line, "revision") &&
            number(original, "startMs") == number(line, "startMs")
    }

    companion object {
        private const val TRANSCRIPT = "transcriptUpsert"
        private const val TRANSLATION = "translationUpsert"
        private const val UPSERT = "UPSERT_OPERATION_UPSERT"

        fun transcript(sequence: Int, event: TranscriptEvent): StandardEventPacket {
            val payload = JSONObject().put("segmentId", event.segmentId()).put("revision", event.revision())
                .put("operation", UPSERT).put("startMs", event.startMs()).put("endMs", event.endMs())
                .put("language", event.language()).put("text", event.text()).put("final", event.isFinal())
            event.sessionSpeakerId()?.let { payload.put("speaker", JSONObject().put("sessionSpeakerId", it)) }
            return build(sequence, "${event.segmentId()}-r${event.revision()}", TRANSCRIPT, payload)
        }

        fun translation(sequence: Int, event: TranslationEvent): StandardEventPacket {
            val payload = JSONObject().put("translationId", event.translationId())
                .put("sourceSegmentId", event.sourceSegmentId()).put("revision", event.revision())
                .put("operation", UPSERT).put("sourceLanguage", event.sourceLanguage())
                .put("targetLanguage", event.targetLanguage()).put("text", event.text())
                .put("final", event.isFinal()).put("supersedesTranslationIds", JSONArray())
            return build(sequence, "translation:${event.translationId()}:r${event.revision()}", TRANSLATION, payload)
        }

        private fun build(sequence: Int, key: String, type: String, payload: JSONObject): StandardEventPacket {
            val json = JSONObject().put("clientEventId", key).put("clientSequence", sequence).put(type, payload)
            return StandardEventPacket(json.toString(), json, sequence, key, type)
        }

        fun read(body: String): StandardEventPacket {
            val json = JSONObject(body)
            val sequence = number(json, "clientSequence")
            val key = json.getString("clientEventId")
            val type = listOf(TRANSCRIPT, TRANSLATION).singleOrNull { json.optJSONObject(it) != null }
                ?: throw JSONException("标准事件类型不明确")
            if (sequence !in 1L..Int.MAX_VALUE.toLong() || key.isBlank() || json.length() != 3)
                throw JSONException("标准事件原身份不可用")
            return StandardEventPacket(body, json, sequence.toInt(), key, type)
        }

        private fun fieldMatches(a: JSONObject, b: JSONObject, vararg fields: String): Boolean =
            fields.all { a.get(it) == b.get(it) }

        private fun matchesSpeaker(a: JSONObject?, b: JSONObject?): Boolean =
            if (a == null || b == null) a == null && b == null else fieldMatches(a, b, "sessionSpeakerId")

        private fun number(obj: JSONObject, field: String, zeroIfMissing: Boolean = false): Long =
            if (zeroIfMissing && !obj.has(field)) 0L
            else obj.get(field).toString().toLongOrNull() ?: throw JSONException("数值字段不可用")

        private fun stringList(json: JSONObject, key: String): List<String> =
            if (!json.has(key)) emptyList() else json.getJSONArray(key).let { values ->
                List(values.length()) { index -> values.getString(index) }
            }

        /** Public core's EventLog key is SHA-256(meetingId + NUL + clientEventId). */
        internal fun serverEventId(meetingId: String, key: String): String =
            "evt_client_" + standardEventDigest("$meetingId\u0000$key")
    }
}

internal fun standardEventDigest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }
