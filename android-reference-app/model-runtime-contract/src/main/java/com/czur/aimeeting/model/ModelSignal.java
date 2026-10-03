package com.czur.aimeeting.model;

/** Adapter 从模型消息归一化出的会话状态，不含供应商原始字段。 */
public enum ModelSignal {
    NONE,
    /** 模型已确认开始，可发送音频。 */
    AUDIO_READY,
    /** 模型最终输出已到达。 */
    FINISHED
}
