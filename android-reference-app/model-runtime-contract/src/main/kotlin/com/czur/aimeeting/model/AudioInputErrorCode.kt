package com.czur.aimeeting.model

/** 音频输入的最小错误分类；SDK 返回错误，权限申请及提示由应用负责。 */
enum class AudioInputErrorCode {
    /** 没有 RECORD_AUDIO，或开流被系统权限／录音策略拒绝。 */
    PERMISSION_DENIED,
    /** 已选音源打开失败；已协商成功的 Broker 不自动降级 MIC。 */
    OPEN_FAILED,
    /** 开流后的读取失败，本场音频输入无法继续。 */
    READ_FAILED,
}
