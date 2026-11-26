package com.czur.aimeeting.client

/**
 * 宿主可映射为自身话术的稳定错误码。
 *
 * <p>SDK 不返回面向终端用户的句子：错误细节写日志，宿主按码决定文案和交互。
 * 合作伙伴可以完全替换显示语言，不依赖 SDK 的中文或英文副本。</p>
 */
enum class MeetingErrorCode {
    /** 部署能力发现失败。 */
    DISCOVERY_FAILED,
    /** 当前身份来源与宿主登录入口不匹配。 */
    IDENTITY_ENTRY_MISMATCH,
    /** 登录失败。 */
    LOGIN_FAILED,
    /** 未装配停止记录存储，无法续办停止收尾。 */
    STOP_STORE_MISSING,
    /** 未装配原场现场存储，无法续办异常退出。 */
    RECOVERY_STORE_MISSING,
    /** 已有会议或原场收尾正在进行。 */
    BUSY,
    /** 原场停止收尾未完成，可再次续办。 */
    FINALIZATION_FAILED,
    /** 原场现场续办未完成，可再次续办。 */
    RECOVERY_FAILED,
    /** 本场当前不接收演示截图。 */
    PRESENTATION_REJECTED,
    /** 演示截图超过传输预算。 */
    PRESENTATION_TOO_LARGE,
    /** 演示截图保存未确认。 */
    PRESENTATION_SAVE_FAILED,
    /** 本场结果尚未就绪。 */
    RESULT_NOT_READY,
    /** 当前部署未提供所选结果服务。 */
    RESULT_SERVICE_UNAVAILABLE,
    /** 结果动作执行失败。 */
    RESULT_ACTION_FAILED,
    /** 本场未产生所选动作需要的结果产物。 */
    RESULT_ARTIFACT_MISSING,
    /** 同源音频保留需要装配录音与发布端口。 */
    AUDIO_RETAIN_UNSUPPORTED,
    /** 当前身份未登录，需要先完成宿主登录。 */
    LOGIN_REQUIRED,
    /** 创建或启动会议失败。 */
    START_FAILED,
    /** 模型会话打开失败。 */
    MODEL_START_FAILED,
    /** 某一步骤失败；具体步骤只写日志。 */
    STEP_FAILED,
    /** SDK 未归类的内部错误。 */
    UNKNOWN,
    /** The server explicitly rejected the current identity for this original meeting. */
    STOP_ACCESS_DENIED,
    /** The server explicitly rejected the original Stop key. */
    STOP_KEY_CONFLICT,
    /** Protected local transcript still requires confirmed original event receipts. */
    STOP_EVENTS_UNVERIFIED,
}

/** 只携带稳定错误码；不包含任何可直接展示给终端用户的句子。 */
data class MeetingError(val code: MeetingErrorCode)
