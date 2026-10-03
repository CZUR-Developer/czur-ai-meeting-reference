package com.czur.aimeeting.model;

/**
 * 模型接入端口。合作伙伴在自己的应用中实现并注册 Adapter，应用只消费标准事件。
 *
 * <p>本端口不规定模型供应商的原始数据格式或网络协议。Adapter 负责把其模型产生的
 * 字幕转换为 {@link TranscriptEvent}；SDK 后续可以在此端口外管理连接与音频传输。
 */
public interface ModelAdapter {
    /** 稳定的 Adapter 标识，供应用选择本次使用的实现。 */
    String id();

    /** 为一次会议打开独立会话。事件按模型产生的顺序交给监听器。 */
    ModelSession open(TranscriptListener listener);
}
