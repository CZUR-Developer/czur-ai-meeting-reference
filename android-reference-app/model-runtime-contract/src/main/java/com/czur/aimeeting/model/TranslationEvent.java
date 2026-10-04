package com.czur.aimeeting.model;

/**
 * 模型译文的一次修订。sourceSegmentId 指向同场原文片段；translationId 在该译文的
 * partial/final 之间保持不变。这里仅是设备侧显示事件，后端持久化在后续切片接入。
 */
public record TranslationEvent(String translationId, String sourceSegmentId, int revision,
        String sourceLanguage, String targetLanguage, String text, boolean isFinal) {}
