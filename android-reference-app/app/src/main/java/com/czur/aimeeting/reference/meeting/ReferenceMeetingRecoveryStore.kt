package com.czur.aimeeting.reference.meeting

import android.content.Context
import com.czur.aimeeting.client.CapturingMeeting
import com.czur.aimeeting.client.ClientIdentity
import com.czur.aimeeting.client.MeetingRecoveryStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Optional
import org.json.JSONObject

/**
 * 参考实现的会议现场存储：一场会议一份 JSON，放在应用私有目录。
 *
 * <p>SDK 在采音起点写入，正常收口或续办成功后删除；进程异常退出时保留，宿主下次
 * 启动逐场调用 `recoverInterrupted`。伙伴可以换成数据库、偏好或其他存储，只要实现
 * `MeetingRecoveryStore` 的保存、读回和删除；不要扫描音频目录或伪造记录。</p>
 */
class ReferenceMeetingRecoveryStore(context: Context) : MeetingRecoveryStore {

    private val directory = File(context.applicationContext.filesDir, "meeting-active")

    @Synchronized
    @Throws(IOException::class)
    override fun save(record: CapturingMeeting) {
        val identity = record.clientIdentity()
        val json = JSONObject()
            .put("meetingId", record.meetingId())
            .put("serverUrl", record.serverUrl())
            .put("clientIdentity", JSONObject()
                .put("appId", identity.appId())
                .put("appVersion", identity.appVersion())
                .put("contractVersion", identity.contractVersion())
                .put("deviceId", identity.deviceId()))
            .put("audioFile", record.audioFile().absolutePath)
            .put("journalFile", record.journalFile()?.absolutePath ?: JSONObject.NULL)
            .put("startedAtMs", record.startedAtMs())
            .put("sourceLanguage", record.sourceLanguage())
            .put("summaryEnabled", record.summaryEnabled())
            .put("retainAudio", record.retainAudio())
            .put("activeDelivery", record.activeDelivery())
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建会议现场目录")
        val target = file(record.meetingId())
        val temporary = File(directory, "${record.meetingId()}.json.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) throw IOException("会议现场写入失败")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    @Synchronized
    @Throws(IOException::class)
    override fun read(meetingId: String): Optional<CapturingMeeting> {
        val target = file(meetingId)
        if (!target.isFile) return Optional.empty()
        val json = JSONObject(target.readText())
        val identity = json.getJSONObject("clientIdentity")
        return Optional.of(CapturingMeeting(
            json.getString("meetingId"),
            json.getString("serverUrl"),
            ClientIdentity(identity.getString("appId"), identity.getString("appVersion"),
                identity.getString("contractVersion"), identity.getString("deviceId")),
            File(json.getString("audioFile")),
            if (json.isNull("journalFile")) null else File(json.getString("journalFile")),
            json.getLong("startedAtMs"),
            json.getString("sourceLanguage"),
            json.getBoolean("summaryEnabled"),
            json.getBoolean("retainAudio"),
            json.getBoolean("activeDelivery"),
        ))
    }

    @Synchronized
    @Throws(IOException::class)
    override fun delete(meetingId: String) {
        val target = file(meetingId)
        if (target.exists() && !target.delete()) throw IOException("会议现场删除失败")
    }

    /** 启动扫描用：只列出已保存的会议编号，坏文件留给单场续办报告。 */
    fun pendingIds(): List<String> = directory.listFiles { file -> file.isFile && file.extension == "json" }
        ?.map { it.nameWithoutExtension }?.sorted().orEmpty()

    private fun file(meetingId: String): File {
        if (!meetingId.matches(Regex("[A-Za-z0-9_-]+"))) throw IOException("会议编号不可用")
        return File(directory, "$meetingId.json")
    }
}
