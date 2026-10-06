package com.czur.aimeeting.model;

/** 音频输入的最小错误分类；SDK 返回错误，权限申请与用户提示由应用处理。 */
public enum AudioInputErrorCode {
    /** 应用没有 RECORD_AUDIO，或开流时被系统权限／录音策略拒绝。 */
    PERMISSION_DENIED,
    /** 已选定的音源未能正常打开；已成功协商的 Broker 开流失败不自动转 MIC。 */
    OPEN_FAILED,
    /** 开流成功后发生读取错误；本次音频输入已无法继续。 */
    READ_FAILED
}
