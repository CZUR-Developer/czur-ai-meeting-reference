package com.czur.aimeeting.client

/**
 * 进程异常退出后原场续办的结果。
 *
 * result 为该场真实 READY 清单；NO_ACTIVE_DELIVERY 场次只收口到 ENDED，不读取清单，
 * 因此 result 为 null，但 meetingId 仍表示已确认停止的原场。
 */
class RecoveryResult(meetingId: String, result: MeetingResult?) : FinalizationResult(meetingId, result)
