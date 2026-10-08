package com.czur.aimeeting.reference.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.DisplayMetrics;
import android.view.Gravity;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.reference.meeting.DisplaySnapshotPresentation;
import com.czur.aimeeting.reference.meeting.MeetingRuntimeFactory;
import com.czur.aimeeting.reference.meeting.MeetingSetup;
import com.czur.aimeeting.ui.DefaultMeetingOverlay;
import com.czur.aimeeting.ui.OverlayController;
import com.czur.aimeeting.ui.OverlayPresentation;
import com.czur.aimeeting.ui.SubtitlePart;
import java.util.ArrayList;
import java.util.List;

/**
 * 默认 UI AAR 装配。这里的布局和样式是公开可编辑的 App 策略；工具只接收实际 px。
 * 以 1920×1080 设计尺度对照字幕1800×222、普通互译1800×293、大互译1800×1008、
 * Mini126×60，按当前屏幕等比换算并贴边。无 AutoSize、机型库、品牌资源或私有联动。
 */
public final class OverlayViewFactory implements MeetingOverlayFactory {
    @Override public Window create(Context context, Runnable onOpenApp, Runnable onSwapColumns, Runnable onStop) {
        return new DefaultWindow(context, onOpenApp, onSwapColumns, onStop);
    }

    static final class DefaultWindow implements Window {
        final DefaultMeetingOverlay overlay;
        DefaultWindow(Context context, Runnable onOpenApp, Runnable onSwapColumns, Runnable onStop) {
            overlay = new DefaultMeetingOverlay(context, layouts(context), style(context),
                    new DefaultMeetingOverlay.Callbacks() {
                        public void onOpenApp() { onOpenApp.run(); }
                        public void onSwapColumns() { onSwapColumns.run(); }
                        public void onStop() { onStop.run(); }
                    });
        }
        @Override public boolean show(MeetingDisplaySnapshot initial) {
            return overlay.show(initial.bilingual()) == OverlayController.DisplayCondition.READY;
        }
        @Override public boolean isShowing() { return overlay.controller().isShowing(); }
        @Override public void render(MeetingDisplaySnapshot display, MeetingSetup.Mode mode, boolean summaryEnabled,
                String timeText, boolean canStop, int audioLevel) {
            overlay.render(presentation(display, mode, summaryEnabled, timeText, canStop, audioLevel));
        }
        @Override public void dismiss() { overlay.release(); }
    }

    /** App 单位换算点：布局策略每次显示读取当前像素，不让 UI AAR 解释 pt/dp。 */
    public static OverlayController.LayoutPolicy layouts(Context context) {
        return formId -> {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            float scale = scale(context);
            boolean mini = DefaultMeetingOverlay.MINI.equals(formId);
            int designHeight;
            switch (formId) {
                case DefaultMeetingOverlay.SUBTITLE: designHeight = 222; break;
                case DefaultMeetingOverlay.TRANSLATION_NORMAL: designHeight = 293; break;
                case DefaultMeetingOverlay.TRANSLATION_LARGE: designHeight = 1008; break;
                case DefaultMeetingOverlay.MINI: designHeight = 60; break;
                default: throw new IllegalArgumentException("unknown default form");
            }
            int width = px(mini ? 126 : 1800, scale);
            int height = px(designHeight, scale);
            return new OverlayController.WindowSpec(width, height,
                    mini ? metrics.widthPixels - width - px(60, scale) : (metrics.widthPixels - width) / 2,
                    mini ? 0 : metrics.heightPixels - height - px(10, scale), Gravity.TOP | Gravity.LEFT);
        };
    }
    /** 配合222px字幕高度：24px边距＋34px标题＋12px电平＋102px三行＋44px按钮＝216px。 */
    public static DefaultMeetingOverlay.Style style(Context context) {
        float scale = scale(context);
        return new DefaultMeetingOverlay.Style(26 * scale, px(34, scale), px(12, scale), px(44, scale),
                Color.WHITE, 0xE6222933);
    }
    static float scale(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.min(metrics.widthPixels / 1920f, metrics.heightPixels / 1080f);
    }
    static int px(int designValue, float scale) { return Math.max(1, Math.round(designValue * scale)); }

    /**
     * 唯一已有投影器继续决定最新原文、左右栏、交换和各自final；本方法只转成 UI 数据。
     * 大窗沿原参考上限取最近8条；单语旧段迟到修订仍按latestTranscriptSegmentId选段。
     */
    public static OverlayPresentation presentation(MeetingDisplaySnapshot display, MeetingSetup.Mode mode,
            boolean summaryEnabled, String timeText, boolean canStop, int audioLevel) {
        var subtitle = DisplaySnapshotPresentation.latestSubtitle(display);
        List<OverlayPresentation.Row> rows = new ArrayList<>();
        boolean fallback = false;
        if (display.bilingual()) {
            for (int index = Math.max(0, display.rows().size() - 8); index < display.rows().size(); index++) {
                var row = DisplaySnapshotPresentation.bilingual(display.rows().get(index), display.swapped());
                rows.add(new OverlayPresentation.Row(part(row.segmentId(), row.left(), row.leftAlpha()),
                        part(row.segmentId(), row.right(), row.rightAlpha())));
                fallback = row.languageFallback();
            }
            if (rows.isEmpty()) rows.add(new OverlayPresentation.Row(part("", "等待字幕…", 0.8f), part("", "", 1f)));
        }
        String title = display.bilingual() ? "双语互译" + (summaryEnabled ? "＋纪要" : "")
                + (fallback ? "（语种降级）" : "") : summaryEnabled ? "AI 会议纪要" : "实时字幕";
        return new OverlayPresentation(title, DisplaySnapshotPresentation.leftLabel(display),
                DisplaySnapshotPresentation.rightLabel(display), timeText, audioLevel,
                MeetingRuntimeFactory.requiresMicrophone() ? "输入电平 " + audioLevel + "/100" : "Mock 未实采",
                canStop, List.of(part(subtitle.segmentId(), subtitle.text(), subtitle.alpha())), rows);
    }
    private static SubtitlePart part(String id, String text, float alpha) { return new SubtitlePart(id, text, alpha == 1f); }
}
