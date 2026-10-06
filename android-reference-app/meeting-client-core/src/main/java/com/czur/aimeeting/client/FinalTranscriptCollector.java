package com.czur.aimeeting.client;

import com.czur.aimeeting.model.SummaryInput;
import com.czur.aimeeting.model.TranscriptEvent;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** 会中保留每段最新最终字幕，供模型完成后生成本场纪要；不保存原始模型响应。 */
public final class FinalTranscriptCollector {
    private final Map<String, TranscriptEvent> latest = new HashMap<>();

    public synchronized void clear() { latest.clear(); }

    public synchronized void apply(TranscriptEvent event) {
        if (!event.isFinal()) return;
        TranscriptEvent previous = latest.get(event.segmentId());
        if (previous == null || event.revision() > previous.revision()) {
            latest.put(event.segmentId(), event);
        }
    }

    public synchronized SummaryInput input(String language) {
        String text = latest.values().stream()
                .sorted(Comparator.comparingLong(TranscriptEvent::startMs)
                        .thenComparing(TranscriptEvent::segmentId))
                .map(TranscriptEvent::text)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return new SummaryInput(language, text);
    }
}
