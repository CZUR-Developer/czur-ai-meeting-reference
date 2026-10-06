package com.czur.aimeeting.client.display;

import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import java.util.List;

/**
 * 会中文字的不可变显示快照。SDK 组合层生成一份，页面与悬浮窗消费同一份。
 * sourceSide 指会前语义 A/B；swapped 仅交换显示位置，模型真实 language 保存在事件中。
 * 文字透明度由 App 使用 isFinal 渲染 80%/100%，本类型不依赖 Android 颜色或控件。
 */
public record MeetingDisplaySnapshot(boolean bilingual, String languageA, String languageB,
        boolean swapped, List<Row> rows, String latestTranscriptSegmentId) {
    public MeetingDisplaySnapshot { rows = List.copyOf(rows); }
    /** 旧低层调用兼容；最近可见事实仅由正式 DisplayState 的六参快照填充。 */
    public MeetingDisplaySnapshot(boolean bilingual, String languageA, String languageB,
            boolean swapped, List<Row> rows) {
        this(bilingual, languageA, languageB, swapped, rows, null);
    }
    public enum Side { UNRESOLVED, A, B }

    /** 原文或译文尚未到达时相应字段可为 null；同一源片段 ID 始终对应同一显示行。 */
    public record Row(String segmentId, TranscriptEvent transcript, TranslationEvent translation,
            Side sourceSide, DisplayRouter.Basis basis) { }
}
