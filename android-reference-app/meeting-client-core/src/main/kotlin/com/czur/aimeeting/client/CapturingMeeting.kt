package com.czur.aimeeting.client

import java.io.File
import java.io.IOException

/**
 * 已创建、尚未确认停止的原场现场，只覆盖进程异常退出后的续办必需事实。
 *
 * 不保存当前账号、展示名、宿主私有载荷或停止键；账号归属由宿主 Store 核对，
 * 宿主的展示方式等私有数据由 Store 自己附带。录音文件是本场同源输入在封口前的
 * 位置，未封口时不能当作已发布或已计费事实。sourceLanguage／targetLanguage 只用于
 * 原场重跑时重新上报服务端纪要语言，targetLanguage 为空表示本场没有实时翻译。
 */
class CapturingMeeting(
    private val meetingId: String,
    private val serverUrl: String,
    private val clientIdentity: ClientIdentity,
    private val audioFile: File,
    private val journalFile: File?,
    private val startedAtMs: Long,
    private val sourceLanguage: String,
    private val summaryEnabled: Boolean,
    private val retainAudio: Boolean,
    private val activeDelivery: Boolean,
    private val targetLanguage: String?,
    private val standardEventJournal: Boolean,
) {
    /** 兼容 B8 和已编译宿主。旧记录不宣称包含可重放的原标准事件。 */
    constructor(meetingId: String, serverUrl: String, clientIdentity: ClientIdentity,
        audioFile: File, journalFile: File?, startedAtMs: Long, sourceLanguage: String,
        summaryEnabled: Boolean, retainAudio: Boolean, activeDelivery: Boolean, targetLanguage: String?) : this(
        meetingId, serverUrl, clientIdentity, audioFile, journalFile, startedAtMs, sourceLanguage,
        summaryEnabled, retainAudio, activeDelivery, targetLanguage, false)

    /** 兼容没有保存目标语种的旧现场记录；原场重跑按单语纪要上报。 */
    constructor(meetingId: String, serverUrl: String, clientIdentity: ClientIdentity,
        audioFile: File, journalFile: File?, startedAtMs: Long, sourceLanguage: String,
        summaryEnabled: Boolean, retainAudio: Boolean, activeDelivery: Boolean) : this(
        meetingId, serverUrl, clientIdentity, audioFile, journalFile, startedAtMs,
        sourceLanguage, summaryEnabled, retainAudio, activeDelivery, null)

    init {
        require(meetingId.isNotBlank()) { "恢复记录缺少会议编号" }
        require(serverUrl.isNotBlank()) { "恢复记录缺少会议服务地址" }
        require(sourceLanguage.isNotBlank()) { "恢复记录缺少原文语种" }
        require(targetLanguage == null || targetLanguage.isNotBlank()) { "恢复记录目标语种不能为空" }
        require(audioFile.absoluteFile.parentFile != null) { "恢复记录缺少录音目录" }
        require(!activeDelivery || retainAudio) { "主动交付要求本场保留同源录音" }
    }
    fun meetingId(): String = meetingId
    fun serverUrl(): String = serverUrl
    fun clientIdentity(): ClientIdentity = clientIdentity
    fun audioFile(): File = audioFile.absoluteFile
    /** 本场最终字幕日志；未装配或未写过为空。 */
    fun journalFile(): File? = journalFile?.absoluteFile
    fun startedAtMs(): Long = startedAtMs
    fun sourceLanguage(): String = sourceLanguage
    /** 本场实时翻译目标语种；没有实时翻译时为 null。 */
    fun targetLanguage(): String? = targetLanguage
    fun summaryEnabled(): Boolean = summaryEnabled
    fun retainAudio(): Boolean = retainAudio
    /** NO_ACTIVE_DELIVERY 场次只为停止收口，不读取结果清单。 */
    fun activeDelivery(): Boolean = activeDelivery
    fun hasStandardEventJournal(): Boolean = standardEventJournal

    /** A saved READY flag alone does not authorize discarding an unconfirmed local transcript. */
    @Throws(IOException::class)
    fun requireAcknowledgedStandardEvents() {
        if (PendingStandardEvents.open(this)?.pending()?.isNotEmpty() == true)
            throw UnverifiedStandardEventsException()
    }
    /** 持续保留确认位，直到宿主成功移除本场现场指针。 */
    @Throws(IOException::class)
    fun discardAcknowledgedStandardEvents(checkScope: Runnable, removeRecord: Runnable) {
        val pending = PendingStandardEvents.open(this)
        if (pending == null) {
            checkScope.run()
            removeRecord.run()
        } else pending.discardAcknowledged(checkScope, removeRecord)
    }

    override fun toString(): String =
        "CapturingMeeting[meetingId=$meetingId, startedAt=$startedAtMs, summary=$summaryEnabled, delivery=$activeDelivery]"
}
