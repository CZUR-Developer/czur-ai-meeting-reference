package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.FinalTranscriptSegment;
import com.czur.aimeeting.client.FinalTranslation;
import com.czur.aimeeting.client.MeetingResultContent;
import com.czur.aimeeting.client.PresentationFrame;
import com.czur.aimeeting.model.SummaryResult;
import java.util.ArrayList;
import java.util.List;

/** 把已类型化的 READY 产物转成 PDF 章节，App 不解释协议 JSON。 */
public final class LocalPdfContent {
    public enum Kind { HEADING, TEXT, IMAGE }
    public record Entry(Kind kind, String text, byte[] jpeg) { }
    private LocalPdfContent() { }

    public static List<Entry> from(LocalMeetingResult result) {
        List<Entry> entries = new ArrayList<>();
        MeetingResultContent content = result.content();
        if (content.artifactTypes().contains("transcript.final")) {
            entries.add(heading("字幕"));
            if (!content.transcriptSegments().isEmpty()) {
                List<String> speakers = new ArrayList<>();
                for (FinalTranscriptSegment segment : content.transcriptSegments()) {
                    entries.add(text(segment.text()));
                    String id = segment.sessionSpeakerId();
                    if (id != null && !id.isBlank() && !speakers.contains(id.trim())) {
                        speakers.add(id.trim());
                    }
                }
                if (!speakers.isEmpty()) {
                    entries.add(heading("本场发言人"));
                    for (String id : speakers) entries.add(text("本场发言人 ID：" + id));
                }
            } else entries.add(text(content.transcriptText()));
        }
        if (content.artifactTypes().contains("translation.final")) {
            entries.add(heading("译文"));
            for (FinalTranslation item : content.translations()) {
                entries.add(text(item.sourceLanguage() + " → " + item.targetLanguage() + "：" + item.text()));
            }
        }
        SummaryResult summary = content.summary();
        if (summary != null) {
            entries.add(heading("结构化纪要"));
            entries.add(text(summary.title()));
            entries.add(text(summary.overview()));
            appendItems(entries, "关键要点", summary.keyPoints());
            appendItems(entries, "待办事项", summary.actionItems());
        }
        boolean hasFrames = false;
        for (PresentationFrame frame : content.presentationFrames()) {
            if (!hasFrames) {
                entries.add(heading("演示画面"));
                hasFrames = true;
            }
            long seconds = frame.offsetMs() / 1000L;
            entries.add(new Entry(Kind.IMAGE,
                    String.format(java.util.Locale.ROOT, "会议 %02d:%02d", seconds / 60, seconds % 60),
                    frame.jpeg()));
        }
        if (entries.isEmpty()) entries.add(text("本场没有可用的最终产物"));
        return List.copyOf(entries);
    }
    private static void appendItems(List<Entry> entries, String label, List<String> items) {
        if (items.isEmpty()) return;
        entries.add(text(label));
        for (String item : items) entries.add(text("• " + item));
    }
    private static Entry heading(String value) { return new Entry(Kind.HEADING, value, null); }
    private static Entry text(String value) { return new Entry(Kind.TEXT, value, null); }
}
