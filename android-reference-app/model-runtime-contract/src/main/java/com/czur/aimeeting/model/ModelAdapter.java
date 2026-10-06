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

    /**
     * 同时接收字幕与译文的模型消息。现有仅转写的 Adapter 无需修改；支持互译的
     * Adapter 覆盖此方法，并为同一原文片段提供稳定的 sourceSegmentId。SDK 仅转发
     * 已归一化事件，不解释供应商原始回包；译文是否持久化由应用业务层决定。
     */
    default ModelSignal onServerMessage(String message, TranscriptListener transcriptListener,
            TranslationListener translationListener) {
        return onServerMessage(message, transcriptListener);
    }

    /**
     * 带句开始事实的统一解析入口。支持句开始的实现先发 SegmentStartedEvent，再发该段
     * 的字幕/译文；SDK 保持这些回调的先后顺序。默认调用旧三参数入口，既有伙伴 Adapter
     * 不需新增方法。语种修正仍用更高修订号的标准字幕表达，不另建原始消息旁路。
     */
    default ModelSignal onServerMessage(String message, TranscriptListener transcriptListener,
            TranslationListener translationListener, SegmentStartedListener segmentStartedListener) {
        return onServerMessage(message, transcriptListener, translationListener);
    }

    /** 停止输入后的协议收尾消息；不需要文本收尾的协议可以返回 null。 */
    default String stopMessage(ModelSessionGrant grant) { return null; }
}
