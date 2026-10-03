package com.czur.aimeeting.model;

/**
 * 模型 WebSocket 协议端口。实现者只处理模型消息；MIC、连接和二进制 PCM 由 SDK 管理。
 *
 * <p>一次 {@link ModelRuntime#open} 使用一个 Adapter 实例。实现者可在实例内保存本场片段修订号，
 * 不应把临时访问材料写入日志或标准字幕事件。
 */
public interface ModelAdapter {
    /** 与后端 modelSession.adapterId 一致的构建期注册标识。 */
    String id();

    /** WebSocket 建立后发送的协议启动消息。音频须等到 AUDIO_READY 才可发送。 */
    String startMessage(ModelSessionGrant grant);

    /**
     * 处理一条模型文本消息，先回调本消息中的标准字幕，再返回生命周期信号。
     * FINISHED 表示本场模型输出已结束；返回后 SDK 才通知上层完成。
     */
    ModelSignal onServerMessage(String message, TranscriptListener listener);

    /** 停止输入后的协议收尾消息；不需要文本收尾的协议可以返回 null。 */
    default String stopMessage(ModelSessionGrant grant) { return null; }
}
