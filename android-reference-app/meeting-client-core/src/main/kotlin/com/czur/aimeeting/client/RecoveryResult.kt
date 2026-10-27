package com.czur.aimeeting.client

/**
 * 进程异常退出后原场续办的结果。
 *
 * result 为该场真实 READY 清单；NO_ACTIVE_DELIVERY 场次只收口到 ENDED，不读取清单，
 * 因此 result 为 null，但 meetingId 仍表示已确认停止的原场。原场停止记录已是 READY
 * 时只清理残留现场、不重复读取清单，此时 result 同样为 null，成功回调表示本地可删除记录。
 */
class RecoveryResult(meetingId: String, result: MeetingResult?) : FinalizationResult(meetingId, result)
