package com.czur.reference.application.port.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Driver 交给 Meeting Orchestrator 的规范化字幕事件。
 *
 * <p>同一 {@code segmentId} 的 revision 必须递增；partial 使用 final=false，
 * 收口版本使用 final=true。Driver 可以保留供应商事件 ID 做内部诊断，但
 * 公共事件 ID 和 eventSequence 必须由 Orchestrator 重新分配。</p>
 */
public record CanonicalTranscriptEvent(
        String segmentId,
        int revision,
        Operation operation,
        long startMs,
        long endMs,
        String language,
        String text,
        boolean isFinal,
        SpeakerReference speaker,
        double confidence,
        List<String> qualityFlags,
        List<String> supersedesSegmentIds) {

    public CanonicalTranscriptEvent {
        requireText("segmentId", segmentId);
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(operation, "operation");
        if (startMs < 0 || endMs < startMs) {
            throw new IllegalArgumentException("invalid transcript time range");
        }
        requireText("language", language);
        Objects.requireNonNull(text, "text");
        if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        Objects.requireNonNull(qualityFlags, "qualityFlags");
        Objects.requireNonNull(supersedesSegmentIds, "supersedesSegmentIds");
        qualityFlags = immutableStrings(qualityFlags, "qualityFlags");
        supersedesSegmentIds = immutableStrings(supersedesSegmentIds, "supersedesSegmentIds");
    }

    /** 标准字幕 upsert/replacement 操作。 */
    public enum Operation {
        UPSERT,
        REPLACE
    }

    private static List<String> immutableStrings(List<String> values, String name) {
        for (String value : values) {
            requireText(name + " item", value);
        }
        return Collections.unmodifiableList(List.copyOf(values));
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
