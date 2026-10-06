package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.model.SummaryInput;
import com.czur.aimeeting.model.TranscriptEvent;

/** P6-6 迁移期薄转发；最终字幕收集与排序只有 core 的一份实现。 */
public final class FinalTranscriptCollector {
    private final com.czur.aimeeting.client.FinalTranscriptCollector delegate =
            new com.czur.aimeeting.client.FinalTranscriptCollector();
    public void clear() { delegate.clear(); }
    public void apply(TranscriptEvent event) { delegate.apply(event); }
    public SummaryInput input(String language) { return delegate.input(language); }
}
