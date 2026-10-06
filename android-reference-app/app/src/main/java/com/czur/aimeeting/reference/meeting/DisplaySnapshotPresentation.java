package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.display.DisplayRouter;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;

/**
 * 页面和悬浮窗共用的无状态呈现工具。输入已经由 SDK 完成配对和分栏，不处理方向角、
 * 模型语种、修订顺序或等待窗口；只把语义 A/B 与交换开关投影到实际左右控件。
 * 每次投影都读取不可变快照，不维护第二份会议状态，也不会改变事件中的真实语言。
 */
public final class DisplaySnapshotPresentation {
    private DisplaySnapshotPresentation() { }

    public record SubtitleRow(String segmentId, String text, float alpha) { }
    public record BilingualRow(String segmentId, String left, String right,
            float leftAlpha, float rightAlpha, boolean languageFallback) { }

    public static String leftLabel(MeetingDisplaySnapshot snapshot) {
        return languageLabel(snapshot.swapped() ? snapshot.languageB() : snapshot.languageA());
    }

    public static String rightLabel(MeetingDisplaySnapshot snapshot) {
        return languageLabel(snapshot.swapped() ? snapshot.languageA() : snapshot.languageB());
    }

    private static String languageLabel(String language) {
        if (language.startsWith("zh")) return "中文";
        if (language.startsWith("en")) return "English";
        return language;
    }

    /** 译文先到时没有原文行；字幕页跳过该行，互译页仍可呈现译文。 */
    public static SubtitleRow subtitle(MeetingDisplaySnapshot.Row row) {
        return row.transcript() == null ? null : new SubtitleRow(row.segmentId(),
                row.transcript().text(), row.transcript().isFinal() ? 1.0f : 0.8f);
    }

    /**
     * sourceSide 已是 SDK 的决定，不依据文字或语言再分栏。UNRESOLVED 沿用旧 UI 的
     * “语言识别中”占位；原文和译文各自使用自己的 final 标记设置透明度。
     */
    public static BilingualRow bilingual(MeetingDisplaySnapshot.Row row, boolean swapped) {
        String source = row.transcript() == null ? "" : row.transcript().text();
        String translated = row.translation() == null ? "" : row.translation().text();
        float sourceAlpha = row.transcript() == null || row.transcript().isFinal() ? 1.0f : 0.8f;
        float translatedAlpha = row.translation() == null || row.translation().isFinal() ? 1.0f : 0.8f;
        boolean fallback = row.basis() == DisplayRouter.Basis.MODEL_LANGUAGE
                || row.basis() == DisplayRouter.Basis.TEXT_LANGUAGE
                || row.basis() == DisplayRouter.Basis.LANGUAGE_UNRESOLVED;
        if (row.sourceSide() == MeetingDisplaySnapshot.Side.UNRESOLVED) {
            return new BilingualRow(row.segmentId(), "语言识别中：" + source, "",
                    sourceAlpha, 1.0f, fallback);
        }
        boolean sourceOnLeft = (row.sourceSide() == MeetingDisplaySnapshot.Side.A) != swapped;
        return sourceOnLeft
                ? new BilingualRow(row.segmentId(), source, translated,
                        sourceAlpha, translatedAlpha, fallback)
                : new BilingualRow(row.segmentId(), translated, source,
                        translatedAlpha, sourceAlpha, fallback);
    }

    /**
     * 单语悬浮窗保持“最近可见原文事件”的行为，而非取历史列表最后一段。旧段迟到修订
     * 时，SDK 给出的 ID 仍指向那一段；App 不通过 revision 比较重建事件顺序。
     */
    public static SubtitleRow latestSubtitle(MeetingDisplaySnapshot snapshot) {
        for (MeetingDisplaySnapshot.Row row : snapshot.rows()) {
            if (row.segmentId().equals(snapshot.latestTranscriptSegmentId())) return subtitle(row);
        }
        return new SubtitleRow("", "等待字幕…", 0.8f);
    }
}
