package com.czur.aimeeting.client

import com.czur.aimeeting.client.server.DeploymentOptions

/**
 * 只读历史会议入口：与实时会议共用同一后端地址、冻结身份和协议解析，
 * 不向 UI 暴露 HTTP、JSON 或 token。
 */
interface MeetingHistoryClient : AutoCloseable {
    /** 匿名部署发现，用于判断结果动作所需的部署服务。 */
    fun discover(callback: MeetingClient.Callback<DeploymentOptions>)

    /** 分页读取当前身份可见的会议；pageToken 为空表示第一页。 */
    fun list(pageToken: String?, pageSize: Int, callback: MeetingClient.Callback<MeetingHistoryPage>)

    /** 按当前时间生成结果链接并读取 READY 清单；正文按 includeContent 决定是否读取。 */
    fun readResult(meetingId: String, genTime: Long, includeContent: Boolean,
                   callback: MeetingClient.Callback<MeetingResult>)

    /** 发送本场结果链接邮件；结果动作上下文通过它执行邮件插件。 */
    fun sendEmail(meetingId: String, recipients: List<String>, resultUrl: String,
                  callback: MeetingClient.Callback<Void?>)

    override fun close()
}

/** 历史会议分页结果；nextPageToken 为空表示没有下一页。 */
data class MeetingHistoryPage(
    val items: List<MeetingHistoryItem>,
    val nextPageToken: String?
)

/** 历史列表只保留展示和动作分发需要的只读字段，不携带 token、正文或内部状态。 */
data class MeetingHistoryItem(
    val meetingId: String,
    val title: String?,
    val state: String,
    val manifestState: String?,
    val createdAt: String?,
    val updatedAt: String?
)
