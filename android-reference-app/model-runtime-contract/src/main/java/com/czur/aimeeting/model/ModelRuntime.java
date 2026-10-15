package com.czur.aimeeting.model;

/**
 * 闭源 SDK 实现的会议模型运行时。普通 Android MIC 和 WebSocket 由实现内部管理。
 * 参考 App 只装配 Adapter 并消费标准事件，不接收原始模型消息或音频帧。
 */
public interface ModelRuntime {
    ModelSession open(ModelSessionGrant grant, ModelAdapter adapter,
            TranscriptListener transcriptListener, ModelSessionListener sessionListener);

    /** 只有已装配同源录音及严格封口实现的运行时才返回 true，默认不产生录音文件。 */
    default boolean supportsRetainedAudio() { return false; }

    /**
     * 本场不可变录音选择；原四参入口保留。false 委托旧入口，true 必须由支持录音的
     * 实现显式处理，默认拒绝，不能静默变成“服务端请求保留、客户端未保存”。
     */
    default ModelSession open(ModelSessionGrant grant, ModelAdapter adapter,
            TranscriptListener transcriptListener, ModelSessionListener sessionListener,
            boolean retainAudio) {
        if (retainAudio) throw new IllegalStateException("同源录音尚未装配");
        return open(grant, adapter, transcriptListener, sessionListener);
    }

    /**
     * 带可选同场续接材料端口的组合入口，基础四参和录音五参入口均继续可用。
     *
     * <p>只有 Runtime 实际支持续接且 Adapter 显式声明支持时才使用该端口；
     * {@code reconnector == null} 表示未装配。默认仍委托原五参实现，不尝试重连，
     * 既有伙伴 Runtime／lambda 的启动、录音和终止行为不变。具体实现只能替换
     * 模型连接，保留唯一音源、录音、用量与业务会议；Stop 不得等待材料 HTTP。</p>
     */
    default ModelSession open(ModelSessionGrant grant, ModelAdapter adapter,
            TranscriptListener transcriptListener, ModelSessionListener sessionListener,
            boolean retainAudio, ModelSessionReconnector reconnector) {
        return open(grant, adapter, transcriptListener, sessionListener, retainAudio);
    }
}
