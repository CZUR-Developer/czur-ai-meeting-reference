package com.czur.reference.application.port.model;

import java.util.Objects;

/**
 * Driver 已停止接收新帧并排空 Provider final 回调后的结果。
 *
 * <p>该对象只证明模型侧输入排空，不等价于服务端的 INPUT_ACCEPTED 或
 * 会议 READY；后两者需要由应用层持久化和最终工作流完成。</p>
 */
public record InputCompletion(
        long lastStreamSequence,
        long lastAudioPositionMs,
        int finalEventCount) {

    public InputCompletion {
        if (lastStreamSequence < 0 || lastAudioPositionMs < 0 || finalEventCount < 0) {
            throw new IllegalArgumentException("completion values must not be negative");
        }
    }
}
