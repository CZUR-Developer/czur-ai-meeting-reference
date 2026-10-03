package com.czur.aimeeting.model;

/** 一次模型会话；开始后产生事件，关闭时停止产生事件并释放资源。 */
public interface ModelSession extends AutoCloseable {
    /** 开始本次会话的数据处理。 */
    void start();

    /** 关闭会话。调用方不再需要本次会话的后续事件。 */
    @Override
    void close();
}
