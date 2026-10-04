package com.czur.aimeeting.model;

import java.util.List;
import java.util.Objects;

/** 已归一化的本场纪要；字段属于公开结果，不含供应商原始回包或访问材料。 */
public record SummaryResult(String language, String title, String overview,
        List<String> keyPoints, List<String> actionItems) {
    public SummaryResult {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(overview, "overview");
        keyPoints = List.copyOf(Objects.requireNonNull(keyPoints, "keyPoints"));
        actionItems = List.copyOf(Objects.requireNonNull(actionItems, "actionItems"));
        if (language.isBlank() || title.isBlank() || overview.isBlank()) {
            throw new IllegalArgumentException("纪要缺少必要文字");
        }
    }
}
