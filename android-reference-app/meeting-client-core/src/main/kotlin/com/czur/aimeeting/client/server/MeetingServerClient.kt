package com.czur.aimeeting.client.server

import com.czur.aimeeting.client.MeetingHistoryPage
import com.czur.aimeeting.client.MeetingOptions
import com.czur.aimeeting.client.MeetingResult
import com.czur.aimeeting.model.*
import java.io.IOException

/** Coordinator 内部 Server 协议端口，阻塞请求在唯一 IO 队列执行，不暴露给业务 UI。 */
interface MeetingServerClient {
    /** 匿名发现；实际场次授权以 Create 为准。 */
    @Throws(IOException::class) fun discover(): DeploymentOptions
    /** 只读历史会议列表；默认端口未实现，HTTP 实现覆盖。 */
    @Throws(IOException::class) fun listMeetings(pageToken: String?, pageSize: Int): MeetingHistoryPage {
        throw IOException("当前后端端口未实现会议列表")
    }
    @Throws(IOException::class) fun createMeeting(setup: MeetingOptions): CreatedMeeting
    /** 声明本场由 App 提交标准事件。 */
    @Throws(IOException::class) fun startClient(meetingId: String)
    /**
     * 会议活跃期发送一次服务端心跳；默认不实现，Mock 或旧服务端不产生额外流量。
     * 调用方按 Create 回告的间隔定时触发，失败只记录、不重试。
     */
    @Throws(IOException::class) fun sendHeartbeat(meetingId: String) { }
    /** 模型直连材料独立领取，Create realtime 不是模型地址。 */
    @Throws(IOException::class) fun requestModelSession(meetingId: String): ModelSessionGrant
    /** 独立翻译短期材料临近过期时按原场续领；不重签实时 ASR、不创建第二场会议。 */
    @Throws(IOException::class) fun requestTranslationSession(meetingId: String): TranslationSessionGrant {
        throw IOException("当前后端端口未实现独立翻译材料续领")
    }
    /** 最终字幕齐备后领取文本模型材料，Mock 不调用。 */
    @Throws(IOException::class) fun requestSummarySession(meetingId: String): SummarySessionGrant
    @Throws(IOException::class) fun appendTranscript(meetingId: String, clientSequence: Int, event: TranscriptEvent)
    /** 译文与字幕共用 clientSequence，仅授权互译时提交。 */
    @Throws(IOException::class) fun appendTranslation(meetingId: String, clientSequence: Int, event: TranslationEvent)
    /** 演示使用独立包序号，每包至少一帧。 */
    @Throws(IOException::class) fun appendPresentation(meetingId: String, chunkSequence: Int, offsetMs: Long, jpeg: ByteArray)
    /**
     * 最终事件后、总结／Stop 前提交已冻结累计实采毫秒，允许零，同场重试值不变。
     * 默认明确不支持；只有模型会话提供实际冻结值时才调用。
     */
    @Throws(IOException::class) fun submitUsage(meetingId: String, audioDurationMs: Long) {
        throw IOException("当前后端端口未实现采音用量提交")
    }
    @Throws(IOException::class) fun submitSummary(meetingId: String, summary: SummaryResult)
    /** accepted 只表示输入接管，202 尚在收尾，实际 READY 需读清单。 */
    @Throws(IOException::class) fun stopMeeting(meetingId: String): StopMeetingResponse
    /** 原场首次及重试共用稳定键，默认不委托单参入口以免生成新键。 */
    @Throws(IOException::class) fun stopMeeting(meetingId: String, stableStopKey: String): StopMeetingResponse {
        throw IOException("当前后端端口未实现显式停止幂等键")
    }
    @Throws(IOException::class) fun sendResultEmail(meetingId: String, recipients: List<String>, resultUrl: String)
    /** Stop200 后等实际 Manifest，只读 READY 产物。 */
    @Throws(IOException::class) fun readResult(meetingId: String, genTime: Long, includeContent: Boolean): MeetingResult
}
