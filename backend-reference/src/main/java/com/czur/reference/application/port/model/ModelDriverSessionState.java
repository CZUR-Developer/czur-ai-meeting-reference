package com.czur.reference.application.port.model;

/** 单个 Model Driver 会话的状态机。 */
public enum ModelDriverSessionState {
    /** 会话对象尚未完成创建。 */
    NEW,
    /** 正在与供应商建立会话；Mock 中该状态不会对外暴露。 */
    OPENING,
    /** 已就绪，可以调用 start。 */
    READY,
    /** 正在接受音频帧。 */
    STREAMING,
    /** 已收到 completeInput，正在排空最终事件。 */
    DRAINING,
    /** 已完成输入排空。 */
    COMPLETED,
    /** 因取消、超时或模型错误终止。 */
    FAILED,
    /** 资源已释放，不再接受任何操作。 */
    CLOSED
}
