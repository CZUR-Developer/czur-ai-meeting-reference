package com.czur.aimeeting.model;

/**
 * 标准句开始监听器；Adapter 按模型消息顺序调用，SDK 与字幕/译文使用同一回调队列。
 * 监听方只处理业务快照，不打开第二路音源，也不反向解释模型 JSON。
 */
@FunctionalInterface
public interface SegmentStartedListener {
    void onSegmentStarted(SegmentStartedEvent event);
}
