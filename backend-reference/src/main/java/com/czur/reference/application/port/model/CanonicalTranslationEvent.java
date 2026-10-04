package com.czur.reference.application.port.model;

import java.util.List;
import java.util.Objects;

/**
 * App Adapter 已归一化的译文修订；与模型厂商原始响应分离。
 *
 * <p>{@code translationId} 在本场内稳定，{@code sourceSegmentId} 指向原文片段；
 * 同一 ID 的修订与字幕共用客户端事件顺序。事件 ID 和会议全局序号由后端分配。
 * 源与目标语言来自实际译文，不由会前默认源语言覆盖。</p>
 */
public record CanonicalTranslationEvent(String translationId, String sourceSegmentId,
        int revision, CanonicalTranscriptEvent.Operation operation,
        String sourceLanguage, String targetLanguage, String text, boolean isFinal,
        List<String> supersedesTranslationIds) {
    public CanonicalTranslationEvent {
        requireText(translationId, "translationId");
        requireText(sourceSegmentId, "sourceSegmentId");
        if (revision <= 0) throw new IllegalArgumentException("revision must be positive");
        Objects.requireNonNull(operation, "operation");
        requireText(sourceLanguage, "sourceLanguage");
        requireText(targetLanguage, "targetLanguage");
        if (sourceLanguage.equals(targetLanguage)) {
            throw new IllegalArgumentException("translation languages must differ");
        }
        Objects.requireNonNull(text, "text");
        supersedesTranslationIds = List.copyOf(supersedesTranslationIds);
        supersedesTranslationIds.forEach(id -> requireText(id, "supersedesTranslationIds item"));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
