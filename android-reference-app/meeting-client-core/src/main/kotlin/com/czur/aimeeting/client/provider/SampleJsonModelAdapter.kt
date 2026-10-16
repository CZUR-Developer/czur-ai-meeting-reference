package com.czur.aimeeting.client.provider

import com.czur.aimeeting.model.*
import org.json.JSONException
import org.json.JSONObject

/**
 * 第二消息格式示例，顶层 type／segment／revision；网络／音源仍由同一 SDK 处理。
 * start 后 ready，PCM 后 transcript 递增修订，stop 后最终字幕及 finished；仅声明实时字幕。
 */
class SampleJsonModelAdapter : ModelAdapter {
    private val revisions = HashMap<String, Int>()
    override fun id(): String = ID
    override fun startMessage(grant: ModelSessionGrant): String = "{\"type\":\"start\"}"
    override fun stopMessage(grant: ModelSessionGrant): String = "{\"type\":\"stop\"}"
    override fun onServerMessage(message: String, listener: TranscriptListener): ModelSignal {
        try {
            val json = JSONObject(message)
            return when (json.getString("type")) {
                "ready" -> ModelSignal.AUDIO_READY
                "finished" -> ModelSignal.FINISHED
                "transcript" -> {
                    val segment = json.getString("segment")
                    val revision = json.getInt("revision")
                    if (revision > revisions.getOrDefault(segment, 0)) {
                        listener.onTranscript(TranscriptEvent(segment, revision, json.getLong("startMs"),
                            json.getLong("endMs"), json.getString("language"), json.getString("text"), json.getBoolean("final")))
                        revisions[segment] = revision
                    }
                    ModelSignal.NONE
                }
                else -> ModelSignal.NONE
            }
        } catch (malformed: JSONException) { throw IllegalArgumentException("样例模型消息格式错误", malformed) }
    }
    companion object { const val ID = "sample-json-ws" }
}
