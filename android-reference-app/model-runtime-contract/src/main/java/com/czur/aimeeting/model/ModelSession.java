package com.czur.aimeeting.model;

/** 一次模型会话；start 后产生事件，stopInput 后仍接收模型最终结果。 */
public interface ModelSession extends AutoCloseable {
    /** 开始本次会话的数据处理。 */
    void start();

    /** 停止采集/发送音频并发送 Adapter 的停止消息；最终结果经监听器返回。 */
    void stopInput();

    /** 取消并释放资源。调用方不再需要本次会话的后续事件。 */
    @Override
    void close();
}
