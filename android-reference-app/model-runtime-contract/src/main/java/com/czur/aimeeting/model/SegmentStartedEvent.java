package com.czur.aimeeting.model;

/**
 * 模型确认一段话开始的标准事实，不含供应商原始消息或显示侧。
 *
 * <p>{@code segmentId} 必须与后续字幕的稳定 ID 一致；{@code startMs} 是模型提供的
 * 本场音频相对毫秒。会议显示层在收到本事件时拍下已有方向样本，避免首条文字较晚到达
 * 时误用下一人的角度。没有句开始事件的 Adapter 可以不发，由显示层在第一条文字兜底。</p>
 */
public record SegmentStartedEvent(String segmentId, long startMs) { }
