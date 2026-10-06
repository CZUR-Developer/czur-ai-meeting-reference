package com.czur.aimeeting.client;

/** 最终译文与原文的标准关联，真实源/目标语种不由显示侧覆盖。 */
public record FinalTranslation(String translationId, String sourceSegmentId,
        String sourceLanguage, String targetLanguage, String text) { }
