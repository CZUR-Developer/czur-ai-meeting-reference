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
    /**
     * 国内互译的一个发言条目：原文和译文始终在同一侧上下排列。
     * side是交换后的物理显示侧：A表示左栏，B表示右栏，UNRESOLVED等待路由确定。
     * 该值仅供View呈现，不会写回SDK、重判语言或改变发言人的真实来源。
     */
    public record SpeakerColumnRow(String segmentId, MeetingDisplaySnapshot.Side side,
            String source, String translated, float sourceAlpha, float translatedAlpha,
            boolean languageFallback) { }

    /**
     * 将SDK已配对的一整段原译投影到发言侧，符合国内item_trans_sentence上下显示语义。
     * @param row SDK给出的同段原文、译文和sourceSide，任一文字尚未到达可为空
     * @param swapped 用户的纯显示交换开关；只交换A/B，不为UNRESOLVED猜测发言侧
     */
    public static SpeakerColumnRow speakerColumn(MeetingDisplaySnapshot.Row row, boolean swapped) {
        MeetingDisplaySnapshot.Side side = row.sourceSide();
        if (swapped && side != MeetingDisplaySnapshot.Side.UNRESOLVED)
            side = side == MeetingDisplaySnapshot.Side.A
                    ? MeetingDisplaySnapshot.Side.B : MeetingDisplaySnapshot.Side.A;
        boolean fallback = row.basis() == DisplayRouter.Basis.MODEL_LANGUAGE
                || row.basis() == DisplayRouter.Basis.TEXT_LANGUAGE
                || row.basis() == DisplayRouter.Basis.LANGUAGE_UNRESOLVED;
        return new SpeakerColumnRow(row.segmentId(), side,
                row.transcript() == null ? "" : row.transcript().text(),
                row.translation() == null ? "" : row.translation().text(),
                row.transcript() == null || row.transcript().isFinal() ? 1.0f : 0.8f,
                row.translation() == null || row.translation().isFinal() ? 1.0f : 0.8f,
                fallback);
    }

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
