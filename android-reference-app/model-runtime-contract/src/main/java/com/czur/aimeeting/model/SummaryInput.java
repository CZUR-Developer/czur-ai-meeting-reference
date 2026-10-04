package com.czur.aimeeting.model;

import java.util.Objects;

/** 模型最终字幕齐备后交给文本总结 Adapter 的本场标准输入，不包含音频或长期密钥。 */
public record SummaryInput(String language, String transcript) {
    public SummaryInput {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(transcript, "transcript");
        if (language.isBlank() || transcript.isBlank()) {
            throw new IllegalArgumentException("纪要需要语言和最终字幕");
        }
    }
}
