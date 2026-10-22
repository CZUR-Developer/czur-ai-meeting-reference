package com.czur.aimeeting.reference.meeting

import android.content.Context
import com.czur.aimeeting.client.ClientIdentity
import com.czur.aimeeting.client.MeetingClientConfig
import com.czur.aimeeting.client.MeetingProfile
import com.czur.aimeeting.client.StoppedMeetingCheckpoint
import com.czur.aimeeting.client.StoppedMeetingStore
import com.czur.aimeeting.model.SealedAudioArtifact
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Optional
import java.util.concurrent.Executor
import org.json.JSONObject

/**
 * 参考实现的停止记录存储：一场会议一份 JSON，放在应用私有目录。
 *
 * <p>SDK 在停止尾部确认后写入，记录封口音频事实、冻结用量、服务归属与 Stop 幂等键；
 * 续办只推进已确认阶段，不重建会议。伙伴可以换成数据库，但不得改写已确认事实。</p>
 */
class ReferenceStoppedMeetingStore(context: Context) : StoppedMeetingStore {

    private val directory = File(context.applicationContext.filesDir, "meeting-stopped")

    @Synchronized
    @Throws(IOException::class)
    override fun save(checkpoint: StoppedMeetingCheckpoint): StoppedMeetingCheckpoint {
        val existing = read(checkpoint.checkpointId()).orElse(null)
        val merged = existing?.merge(checkpoint) ?: checkpoint
        persist(merged)
        return merged
    }

    @Synchronized
    @Throws(IOException::class)
    override fun read(checkpointId: String): Optional<StoppedMeetingCheckpoint> {
        val target = file(checkpointId)
        if (!target.isFile) return Optional.empty()
        val json = JSONObject(target.readText())
        val audioJson = json.getJSONObject("audio")
        val identity = json.getJSONObject("clientIdentity")
        val audio = SealedAudioArtifact(
            json.getString("meetingId"),
            File(audioJson.getString("file")),
            SealedAudioArtifact.Format.valueOf(audioJson.getString("format")),
            audioJson.getLong("encodedBytes"),
            audioJson.getLong("capturedPcmBytes"),
        )
        // 参考实现不复用原账号续签：恢复只需要原服务地址、地址内凭据和设备身份。
        val config = MeetingClientConfig(json.getString("serverUrl"), json.getString("accessToken"),
            ClientIdentity(identity.getString("appId"), identity.getString("appVersion"),
                identity.getString("contractVersion"), identity.getString("deviceId")),
            MeetingProfile.reference(), Executor { it.run() })
        return Optional.of(StoppedMeetingCheckpoint.restore(audio,
            audioJson.getLong("frozenAudioDurationMs"), config,
            json.getString("stableStopKey"),
            StoppedMeetingCheckpoint.Phase.valueOf(json.getString("phase")),
            if (json.isNull("genTime")) null else json.getLong("genTime")))
    }

    /** 尚未收口到 READY 的停止记录；交给 resumeFinalization 逐场推进。 */
    @Synchronized
    @Throws(IOException::class)
    fun pendingIds(): List<String> = directory.listFiles { file -> file.isFile && file.extension == "json" }
        ?.filter { file ->
            val saved = read(file.nameWithoutExtension)
            saved.isPresent && saved.get().phase() != StoppedMeetingCheckpoint.Phase.READY
        }?.map { it.nameWithoutExtension }?.sorted().orEmpty()

    /** READY 后删除停止记录；失败保留，下次启动继续。 */
    @Synchronized
    fun delete(checkpointId: String) { file(checkpointId).delete() }

    private fun persist(checkpoint: StoppedMeetingCheckpoint) {
        val context = checkpoint.context()
        val audio = context.audio()
        val json = JSONObject()
            .put("meetingId", checkpoint.checkpointId())
            .put("serverUrl", context.serverUrl())
            .put("accessToken", context.accessToken())
            .put("clientIdentity", JSONObject()
                .put("appId", context.clientIdentity().appId())
                .put("appVersion", context.clientIdentity().appVersion())
                .put("contractVersion", context.clientIdentity().contractVersion())
                .put("deviceId", context.clientIdentity().deviceId()))
            .put("audio", JSONObject()
                .put("file", audio.file().absolutePath)
                .put("format", audio.format().name)
                .put("encodedBytes", audio.encodedBytes())
                .put("capturedPcmBytes", audio.capturedPcmBytes())
                .put("frozenAudioDurationMs", context.frozenAudioDurationMs()))
            .put("stableStopKey", checkpoint.stableStopKey())
            .put("phase", checkpoint.phase().name)
            .put("genTime", checkpoint.genTime() ?: JSONObject.NULL)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建停止记录目录")
        val target = file(checkpoint.checkpointId())
        val temporary = File(directory, "${checkpoint.checkpointId()}.json.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) throw IOException("停止记录写入失败")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun file(checkpointId: String): File {
        if (!checkpointId.matches(Regex("[A-Za-z0-9_-]+"))) throw IOException("会议编号不可用")
        return File(directory, "$checkpointId.json")
    }
}
