package com.czur.aimeeting.reference.meeting

import com.czur.aimeeting.client.MeetingError
import com.czur.aimeeting.client.MeetingErrorCode

/**
 * 参考宿主自己的错误话术：SDK 只回 MeetingErrorCode，不返回终端用户句子。
 * 合作伙伴可整体替换本文件，不需要改 SDK 或协议。
 */
internal fun MeetingError.userText(): String = when (code) {
    MeetingErrorCode.LOGIN_FAILED -> "登录失败"
    MeetingErrorCode.DISCOVERY_FAILED -> "部署发现失败，请联系应用提供方"
    MeetingErrorCode.LOGIN_REQUIRED -> "请先登录"
    MeetingErrorCode.IDENTITY_ENTRY_MISMATCH -> "当前身份与登录入口不匹配"
    MeetingErrorCode.BUSY -> "已有会议或收尾正在进行"
    MeetingErrorCode.FINALIZATION_FAILED -> "原场停止收尾未完成，请再次续办"
    MeetingErrorCode.RECOVERY_FAILED -> "原场现场续办未完成，请再次续办"
    MeetingErrorCode.STOP_STORE_MISSING -> "未找到原场停止记录"
    MeetingErrorCode.RECOVERY_STORE_MISSING -> "未找到原场现场"
    MeetingErrorCode.START_FAILED,
    MeetingErrorCode.STEP_FAILED,
    MeetingErrorCode.MODEL_START_FAILED -> "会议启动失败，请稍后重试"
    MeetingErrorCode.RESULT_NOT_READY -> "本场结果尚未就绪"
    MeetingErrorCode.RESULT_SERVICE_UNAVAILABLE -> "当前部署未提供所选结果服务"
    MeetingErrorCode.RESULT_ACTION_FAILED -> "结果动作执行失败"
    MeetingErrorCode.RESULT_ARTIFACT_MISSING -> "本场缺少所选结果产物"
    MeetingErrorCode.AUDIO_RETAIN_UNSUPPORTED -> "当前部署不支持录音保留"
    MeetingErrorCode.PRESENTATION_REJECTED -> "本场当前不接受演示截图"
    MeetingErrorCode.PRESENTATION_TOO_LARGE -> "演示截图超过传输预算"
    MeetingErrorCode.PRESENTATION_SAVE_FAILED -> "演示截图保存未确认"
    MeetingErrorCode.UNKNOWN -> "操作失败，请稍后重试"
}
