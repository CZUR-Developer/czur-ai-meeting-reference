package com.czur.aimeeting.model

/** SDK 本场实际音源模式，仅说明来源，不代表模型或业务能力。平台识别和开关由 SDK 处理。 */
enum class AudioInputMode {
    /** 标准麦克风；普通 Android 和没有兼容 Broker 的旧系统使用。 */
    MIC,
    /** CZUR 系统共享音频出口，竞合由系统处理。 */
    CZUR_BROKER,
    /** 仅 Debug SDK 支持的测试 WAV，不是实体麦克风验收。 */
    DEBUG_WAV,
}
