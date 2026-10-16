package com.czur.aimeeting.client

/** 同场真实 READY 事实；正常收口只返回清单，正文和结果动作通过独立访问按需执行。 */
data class MeetingResult(
    private val meetingId: String,
    private val title: String,
    private val genTime: Long,
    private val status: Status,
    private var artifactTypes: Set<String>,
    private val content: MeetingResultContent?,
    private val resultUrl: String?,
    private val deliveryStatus: DeliveryStatus,
) {
    init {
        artifactTypes = java.util.Set.copyOf(artifactTypes)
    }
    fun meetingId(): String = meetingId
    fun title(): String = title
    fun genTime(): Long = genTime
    fun status(): Status = status
    fun artifactTypes(): Set<String> = artifactTypes
    fun content(): MeetingResultContent? = content
    fun resultUrl(): String? = resultUrl
    fun deliveryStatus(): DeliveryStatus = deliveryStatus
    constructor(meetingId: String, title: String, genTime: Long, status: Status,
        artifactTypes: Set<String>, content: MeetingResultContent?) : this(
        meetingId, title, genTime, status, artifactTypes, content, null, DeliveryStatus.NOT_REQUESTED)
    enum class Status { READY, READY_WITH_WARNINGS }
    /** 仅保留旧公开接口适配，不参与会议阶段判断。 */
    enum class DeliveryStatus { NOT_REQUESTED, WEB_AVAILABLE, LOCAL_READY, EMAIL_SENT, EMAIL_FAILED }
    fun withDelivery(url: String?, delivery: DeliveryStatus): MeetingResult = MeetingResult(
        meetingId, title, genTime, status, artifactTypes, content, url, delivery)
}
