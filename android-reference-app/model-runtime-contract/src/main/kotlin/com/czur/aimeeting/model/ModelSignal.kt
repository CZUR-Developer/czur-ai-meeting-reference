package com.czur.aimeeting.model

/** Adapter 从模型消息归一化出的状态，不含供应商字段。 */
enum class ModelSignal {
    NONE,
    /** 模型确认开始，可以发送音频。 */
    AUDIO_READY,
    /** 模型最终输出已到达。 */
    FINISHED,
}
