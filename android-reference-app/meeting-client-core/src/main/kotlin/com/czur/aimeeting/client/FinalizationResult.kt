package com.czur.aimeeting.client

/**
 * 停止记录续办的完成结果。
 *
 * 主动交付场次携带真实 READY 清单；NO_ACTIVE_DELIVERY 场次只收口到 ENDED，
 * 不读取清单，因此 result 为 null，但 meetingId 仍表示已确认停止的原场。
 * 回调成功即表示停止记录已推进到 READY，宿主可删除本地记录；是否交付结果
 * 由 [delivered] 区分，不能用空结果伪装成已交付。
 */
open class FinalizationResult(private val meetingId: String, private val result: MeetingResult?) {
    fun meetingId(): String = meetingId
    fun result(): MeetingResult? = result
    fun delivered(): Boolean = result != null
}
