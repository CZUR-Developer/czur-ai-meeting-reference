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

    /**
     * 是否可能先输出未知源语种，再在同段修订或配对译文中补充可信源语种。
     *
     * <p>默认 false 保留既有 Adapter 的即时显示行为，伙伴的旧实现无需新增方法。
     * 有此消息时序的实现可返回 true，组合层便在本场双语路由允许等待时复用已有
     * 语种窗口；明确方向、窗口到期及正常停止仍使用原显示规则。组合层在本场
     * 第一条字幕前读取该声明，不根据供应商名称、注册 ID 或原始 JSON 推定。</p>
     *
     * <p>这只声明消息时序，不代表服务端授权或可支持语言，也不配置等待时长。
     * 后续语种仍通过同一片段的更高修订号字幕或标准译文回调表达，不新增协议旁路。</p>
     */
    default boolean mayEmitDeferredSourceLanguage() { return false; }

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
