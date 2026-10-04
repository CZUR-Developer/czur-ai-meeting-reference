package com.czur.aimeeting.reference.meeting;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 把 READY 产物转成 PDF 章节；章节存在性由产物决定，不从会前模式猜测。 */
public final class LocalPdfContent {
    public enum Kind { HEADING, TEXT, IMAGE }
    public record Entry(Kind kind, String text, String imageBase64) { }

    private LocalPdfContent() { }

    public static List<Entry> from(LocalMeetingResult result) {
        List<Entry> entries = new ArrayList<>();
        JSONObject transcript = result.transcript();
        if (transcript != null) {
            entries.add(heading("字幕"));
            JSONArray segments = transcript.optJSONArray("segments");
            if (segments != null && segments.length() > 0) {
                for (int i = 0; i < segments.length(); i++) {
                    JSONObject segment = segments.optJSONObject(i);
                    if (segment != null) entries.add(text(segment.optString("text")));
                }
                List<String> speakers = new ArrayList<>();
                for (int i = 0; i < segments.length(); i++) {
                    JSONObject segment = segments.optJSONObject(i);
                    if (segment == null) continue;
                    String id = segment.optString("sessionSpeakerId").trim();
                    if (!id.isEmpty() && !speakers.contains(id)) speakers.add(id);
                }
                if (!speakers.isEmpty()) {
                    entries.add(heading("本场发言人"));
                    for (String id : speakers) entries.add(text("本场发言人 ID：" + id));
                }
            } else {
                entries.add(text(transcript.optString("text")));
            }
        }
        JSONObject translation = result.translation();
        if (translation != null) {
            entries.add(heading("译文"));
            JSONArray items = translation.optJSONArray("translations");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) entries.add(text(item.optString("sourceLanguage") + " → "
                        + item.optString("targetLanguage") + "：" + item.optString("text")));
            }
        }
        JSONObject summary = result.summary();
        if (summary != null) {
            entries.add(heading("结构化纪要"));
            entries.add(text(summary.optString("title")));
            entries.add(text(summary.optString("overview")));
            appendItems(entries, "关键要点", summary.optJSONArray("keyPoints"));
            appendItems(entries, "待办事项", summary.optJSONArray("actionItems"));
        }
        boolean hasFrames = false;
        for (JSONObject capture : result.presentationCaptures()) {
            JSONArray frames = capture.optJSONArray("frames");
            if (frames == null) continue;
            for (int i = 0; i < frames.length(); i++) {
                JSONObject frame = frames.optJSONObject(i);
                if (frame == null) continue;
                String jpeg = frame.optString("jpegBase64");
                if (jpeg.isEmpty()) continue;
                if (!hasFrames) {
                    entries.add(heading("演示画面"));
                    hasFrames = true;
                }
                long seconds = frame.optLong("offsetMs") / 1000L;
                entries.add(new Entry(Kind.IMAGE,
                        String.format(java.util.Locale.ROOT, "会议 %02d:%02d",
                                seconds / 60, seconds % 60), jpeg));
            }
        }
        if (entries.isEmpty()) entries.add(text("本场没有可用的最终产物"));
        return List.copyOf(entries);
    }

    private static void appendItems(List<Entry> entries, String label, JSONArray items) {
        if (items == null || items.length() == 0) return;
        entries.add(text(label));
        for (int i = 0; i < items.length(); i++) {
            entries.add(text("• " + items.optString(i)));
        }
    }

    private static Entry heading(String value) { return new Entry(Kind.HEADING, value, null); }
    private static Entry text(String value) { return new Entry(Kind.TEXT, value, null); }
}
