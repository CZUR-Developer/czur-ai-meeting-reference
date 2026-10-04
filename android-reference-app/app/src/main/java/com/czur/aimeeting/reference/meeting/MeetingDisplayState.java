package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.model.TranscriptEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 一场会议的会中显示快照。应用内页面和跨应用悬浮窗读取同一个实例，避免两套控件
 * 分别处理模型修订消息而显示不同文字。计时使用单调时钟，避免设备时间校准影响时长。
 */
public final class MeetingDisplayState {
    public static final class Segment {
        private final String id;
        private final int revision;
        private final String text;
        private final boolean isFinal;

        private Segment(String id, int revision, String text, boolean isFinal) {
            this.id = id;
            this.revision = revision;
            this.text = text;
            this.isFinal = isFinal;
        }

        public String id() { return id; }
        public String text() { return text; }
        public float alpha() { return isFinal ? 1.0f : 0.8f; }
    }

    private final LinkedHashMap<String, Segment> segments = new LinkedHashMap<>();
    private String transcript = "等待字幕…";
    private boolean finalTranscript;
    private long startedAtElapsedMs;

    public void start(long elapsedRealtimeMs) {
        segments.clear();
        transcript = "等待字幕…";
        finalTranscript = false;
        startedAtElapsedMs = elapsedRealtimeMs;
    }

    public void apply(TranscriptEvent event) {
        Segment previous = segments.get(event.segmentId());
        if (previous != null && event.revision() < previous.revision) return;
        segments.put(event.segmentId(), new Segment(event.segmentId(), event.revision(),
                event.text(), event.isFinal()));
        transcript = event.text();
        finalTranscript = event.isFinal();
    }

    /** 按首次收到片段的顺序排列；修订更新原行，不另起一行。 */
    public List<Segment> segments() { return new ArrayList<>(segments.values()); }

    public Segment segment(String id) { return segments.get(id); }

    public String transcript() { return transcript; }

    public float transcriptAlpha() { return finalTranscript ? 1.0f : 0.8f; }

    public String elapsed(long elapsedRealtimeMs) {
        long seconds = Math.max(0L, (elapsedRealtimeMs - startedAtElapsedMs) / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600L,
                (seconds / 60L) % 60L, seconds % 60L);
    }
}
