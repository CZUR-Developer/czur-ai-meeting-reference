package com.czur.aimeeting.client

import java.io.File

/**
 * 已创建、尚未确认停止的原场现场，只覆盖进程异常退出后的续办必需事实。
 *
 * 不保存当前账号、展示名、宿主私有载荷或停止键；账号归属由宿主 Store 核对，
 * 宿主的展示方式等私有数据由 Store 自己附带。录音文件是本场同源输入在封口前的
 * 位置，未封口时不能当作已发布或已计费事实。
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
) {
    init {
        require(meetingId.isNotBlank()) { "恢复记录缺少会议编号" }
        require(serverUrl.isNotBlank()) { "恢复记录缺少会议服务地址" }
        require(sourceLanguage.isNotBlank()) { "恢复记录缺少原文语种" }
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
    fun summaryEnabled(): Boolean = summaryEnabled
    fun retainAudio(): Boolean = retainAudio
    /** NO_ACTIVE_DELIVERY 场次只为停止收口，不读取结果清单。 */
    fun activeDelivery(): Boolean = activeDelivery
    override fun toString(): String =
        "CapturingMeeting[meetingId=$meetingId, startedAt=$startedAtMs, summary=$summaryEnabled, delivery=$activeDelivery]"
}
