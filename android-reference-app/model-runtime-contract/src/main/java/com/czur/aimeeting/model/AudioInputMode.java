package com.czur.aimeeting.model;

/**
 * SDK 本次实际使用的音频输入模式。应用通过回调获知结果，无需自行识别设备、探测
 * 系统服务或持有底层音频句柄；模式只说明数据来源，不代表模型或业务能力。
 */
public enum AudioInputMode {
    /** 标准 Android 麦克风；普通 Android 设备及没有兼容 Broker 的旧 CZUR 系统使用。 */
    MIC,
    /** 兼容的 CZUR 系统共享音频出口；音源竞合由系统处理，应用不再争抢 MIC。 */
    CZUR_BROKER,
    /** 仅 Debug SDK 可用的本地测试 WAV；明确区分于真实硬件麦克风验收。 */
    DEBUG_WAV
}
