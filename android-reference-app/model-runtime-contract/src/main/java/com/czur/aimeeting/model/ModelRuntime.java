package com.czur.aimeeting.model;

/**
 * 闭源 SDK 实现的会议模型运行时。普通 Android MIC 和 WebSocket 由实现内部管理。
 * 参考 App 只装配 Adapter 并消费标准事件，不接收原始模型消息或音频帧。
 */
public interface ModelRuntime {
    ModelSession open(ModelSessionGrant grant, ModelAdapter adapter,
            TranscriptListener transcriptListener, ModelSessionListener sessionListener);
}
