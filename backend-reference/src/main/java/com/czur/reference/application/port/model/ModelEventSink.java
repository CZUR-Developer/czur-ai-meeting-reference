package com.czur.reference.application.port.model;

/**
 * Driver 输出规范化事件的回调端口。
 *
 * <p>回调由 Driver 异步或同步触发均可，但实现不得假设回调线程持有 Driver
 * 内部锁。调用方应尽快复制事件并交给 Orchestrator；不要在回调中同步调用
 * Driver 的生命周期方法。回调抛异常表示事件未被可靠接管，Driver 必须向
 * 调用方返回 CALLBACK_FAILED；terminal 回调可能按同一 session/终态重试，
 * 因此实现必须幂等。</p>
 */
public interface ModelEventSink {
    /** 接收一个已经完成字段规范化的字幕修订事件。 */
    void onTranscript(CanonicalTranscriptEvent event);

    /** 接收模型实际消耗量；默认实现不统计用量。 */
    default void onUsage(long audioDurationMs) {
        // 参考 Mock 不计费；生产 Driver 应发送标准化的累积用量。
    }

    /** 接收可恢复告警；错误对象不得包含供应商原始响应正文。 */
    default void onWarning(ModelDriverError warning) {
        // 默认忽略可选告警，编排器可覆盖。
    }

    /** 接收会话终态；成功完成时 terminal.error 为 null。 */
    default void onTerminal(ModelTerminal terminal) {
        // 默认忽略终态，测试和编排器通常会记录它。
    }
}
