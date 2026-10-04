package com.czur.aimeeting.model;

/**
 * 模型译文的一次修订。sourceSegmentId 指向同场原文片段；translationId 在该译文的
 * partial/final 之间保持不变。App 用同一标准事件分别更新会中显示，并按本场
 * clientSequence 送往后端形成最终译文产物；模型原始响应不进入后端接口。
 */
public record TranslationEvent(String translationId, String sourceSegmentId, int revision,
        String sourceLanguage, String targetLanguage, String text, boolean isFinal) {}
