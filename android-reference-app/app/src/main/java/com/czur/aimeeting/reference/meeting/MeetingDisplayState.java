package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
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
        private final String language;
        private final boolean isFinal;

        private Segment(String id, int revision, String text, String language, boolean isFinal) {
            this.id = id;
            this.revision = revision;
            this.text = text;
            this.language = language;
            this.isFinal = isFinal;
        }

        public String id() { return id; }
        public String text() { return text; }
        public String language() { return language; }
        public float alpha() { return isFinal ? 1.0f : 0.8f; }
    }

    /** 一行中英互译；空栏表示对应片段的原文或译文尚未到达。 */
    public static final class BilingualRow {
        private final String id;
        private final String chinese;
        private final String english;
        private final float chineseAlpha;
        private final float englishAlpha;

        private BilingualRow(String id, String chinese, String english,
                float chineseAlpha, float englishAlpha) {
            this.id = id;
            this.chinese = chinese;
            this.english = english;
            this.chineseAlpha = chineseAlpha;
            this.englishAlpha = englishAlpha;
        }

        public String id() { return id; }
        public String chinese() { return chinese; }
        public String english() { return english; }
        public float chineseAlpha() { return chineseAlpha; }
        public float englishAlpha() { return englishAlpha; }
    }

    private static final class TranslationSegment {
        private final String sourceId;
        private final int revision;
        private final String sourceLanguage;
        private final String text;
        private final boolean isFinal;

        private TranslationSegment(TranslationEvent event) {
            sourceId = event.sourceSegmentId();
            revision = event.revision();
            sourceLanguage = event.sourceLanguage();
            text = event.text();
            isFinal = event.isFinal();
        }

        private float alpha() { return isFinal ? 1.0f : 0.8f; }
    }

    private final LinkedHashMap<String, Segment> segments = new LinkedHashMap<>();
    private final LinkedHashMap<String, TranslationSegment> translations = new LinkedHashMap<>();
    private String transcript = "等待字幕…";
    private boolean finalTranscript;
    private long startedAtElapsedMs;

    public void start(long elapsedRealtimeMs) {
        segments.clear();
        translations.clear();
        transcript = "等待字幕…";
        finalTranscript = false;
        startedAtElapsedMs = elapsedRealtimeMs;
    }

    public void apply(TranscriptEvent event) {
        Segment previous = segments.get(event.segmentId());
        if (previous != null && event.revision() < previous.revision) return;
        segments.put(event.segmentId(), new Segment(event.segmentId(), event.revision(),
                event.text(), event.language(), event.isFinal()));
        transcript = event.text();
        finalTranscript = event.isFinal();
    }

    /** 同一 translationId 原位修订；旧 revision 不得覆盖更新的显示内容。 */
    public void apply(TranslationEvent event) {
        TranslationSegment previous = translations.get(event.translationId());
        if (previous != null && event.revision() < previous.revision) return;
        translations.put(event.translationId(), new TranslationSegment(event));
    }

    public boolean hasTranslation() { return !translations.isEmpty(); }

    /** 以片段 ID 配对原文和译文；语言决定中英栏位，与发言方向无关。 */
    public List<BilingualRow> bilingualRows() {
        LinkedHashMap<String, TranslationSegment> bySource = new LinkedHashMap<>();
        for (TranslationSegment translation : translations.values()) {
            bySource.put(translation.sourceId, translation);
        }
        List<BilingualRow> rows = new ArrayList<>();
        for (Segment source : segments.values()) {
            rows.add(bilingualRow(source.id, source, bySource.remove(source.id)));
        }
        for (String sourceId : bySource.keySet()) {
            rows.add(bilingualRow(sourceId, null, bySource.get(sourceId)));
        }
        return rows;
    }

    public BilingualRow latestBilingualRow() {
        List<BilingualRow> rows = bilingualRows();
        return rows.isEmpty() ? null : rows.get(rows.size() - 1);
    }

    private static BilingualRow bilingualRow(String id, Segment source,
            TranslationSegment translation) {
        String sourceText = source == null ? "" : source.text;
        float sourceAlpha = source == null ? 1.0f : source.alpha();
        String translatedText = translation == null ? "" : translation.text;
        float translatedAlpha = translation == null ? 1.0f : translation.alpha();
        String sourceLanguage = translation == null
                ? (source == null ? "" : source.language) : translation.sourceLanguage;
        if (sourceLanguage != null && sourceLanguage.toLowerCase(Locale.ROOT).startsWith("en")) {
            return new BilingualRow(id, translatedText, sourceText,
                    translatedAlpha, sourceAlpha);
        }
        return new BilingualRow(id, sourceText, translatedText, sourceAlpha, translatedAlpha);
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
