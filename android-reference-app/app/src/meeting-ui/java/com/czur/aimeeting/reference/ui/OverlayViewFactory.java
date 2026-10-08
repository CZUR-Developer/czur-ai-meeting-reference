package com.czur.aimeeting.reference.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.DisplayMetrics;
import android.view.Gravity;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.reference.meeting.MeetingSetup;
import com.czur.aimeeting.ui.DefaultMeetingOverlay;
import com.czur.aimeeting.ui.OverlayController;
import com.czur.aimeeting.ui.OverlayPresentation;
import java.util.List;

/**
 * 默认 UI AAR 装配。公开 App 提供国内版窗口内容与资源，SDK 保持窗口管理及 Mini 恢复。
 * 伙伴可以替换这里的工厂；显示变化不修改音源、模型、DOA／语种分栏、停止或结果核心。
 */
public final class OverlayViewFactory implements MeetingOverlayFactory {
    @Override public Window create(Context context, Runnable onOpenApp, Runnable onSwapColumns, Runnable onStop) {
        return new DefaultWindow(context, onOpenApp, onSwapColumns, onStop);
    }

    static final class DefaultWindow implements Window {
        final DefaultMeetingOverlay overlay;
        private MeetingDisplaySnapshot display;
        private String timeText = "";
        private boolean canStop, simpleMode;
        private int audioLevel;
        DefaultWindow(Context context, Runnable onOpenApp, Runnable onSwapColumns, Runnable onStop) {
            OverlayController.LayoutPolicy layouts = formId -> layout(context, formId, simpleMode);
            overlay = new DefaultMeetingOverlay(context, layouts, style(context),
                    new DefaultMeetingOverlay.Callbacks() {
                        public void onOpenApp() { onOpenApp.run(); }
                        public void onSwapColumns() { onSwapColumns.run(); }
                        public void onStop() { onStop.run(); }
                    });
            for (String form : List.of(DefaultMeetingOverlay.SUBTITLE, DefaultMeetingOverlay.TRANSLATION_NORMAL,
                    DefaultMeetingOverlay.TRANSLATION_LARGE, DefaultMeetingOverlay.MINI)) {
                overlay.controller().registerForm(form, (host, formId) -> {
                    CzurOverlayViews views = new CzurOverlayViews(host, formId, new CzurOverlayViews.Actions(
                            onOpenApp, onSwapColumns, onStop,
                            () -> { if (simpleMode) onSwapColumns.run(); else overlay.collapse(); },
                            () -> { simpleMode = false; overlay.showLarge(); }, overlay::showNormal, overlay::restore,
                            () -> {
                                simpleMode = !simpleMode;
                                overlay.controller().show(DefaultMeetingOverlay.TRANSLATION_NORMAL);
                                renderLatest();
                            }));
                    return new OverlayController.Content() {
                        public android.view.View view() { return views.view(); }
                        public void render(OverlayPresentation ignored) {
                            // SDK 继续管理唯一窗口和最后呈现；App 读取已路由的同一个显示快照。
                            if (display != null) views.render(display, timeText, canStop, audioLevel, simpleMode);
                        }
                    };
                }, layouts);
            }
        }
        @Override public boolean show(MeetingDisplaySnapshot initial) {
            display = initial;
            return overlay.show(initial.bilingual()) == OverlayController.DisplayCondition.READY;
        }
        @Override public boolean isShowing() { return overlay.controller().isShowing(); }
        @Override public void render(MeetingDisplaySnapshot display, MeetingSetup.Mode mode, boolean summaryEnabled,
                String timeText, boolean canStop, int audioLevel) {
            this.display = display;
            this.timeText = timeText;
            this.canStop = canStop;
            this.audioLevel = audioLevel;
            renderLatest();
        }
        private void renderLatest() { overlay.render(OverlayPresentation.empty()); }
        @Override public void dismiss() { overlay.release(); }
    }

    /** App 单位换算点：布局策略每次显示读取当前像素，不让 UI AAR 解释 pt/dp。 */
    public static OverlayController.LayoutPolicy layouts(Context context) {
        return formId -> layout(context, formId, false);
    }
    private static OverlayController.WindowSpec layout(Context context, String formId, boolean simpleMode) {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            float scale = scale(context);
            boolean mini = DefaultMeetingOverlay.MINI.equals(formId);
            int designHeight;
            switch (formId) {
                case DefaultMeetingOverlay.SUBTITLE: designHeight = 222; break;
                case DefaultMeetingOverlay.TRANSLATION_NORMAL: designHeight = simpleMode ? 164 : 293; break;
                case DefaultMeetingOverlay.TRANSLATION_LARGE: designHeight = 1008; break;
                case DefaultMeetingOverlay.MINI: designHeight = 60; break;
                default: throw new IllegalArgumentException("unknown default form");
            }
            int width = px(mini ? 126 : 1800, scale);
            int height = px(designHeight, scale);
            return new OverlayController.WindowSpec(width, height,
                    mini ? metrics.widthPixels - width - px(60, scale) : (metrics.widthPixels - width) / 2,
                    mini ? 0 : metrics.heightPixels - height - px(10, scale), Gravity.TOP | Gravity.LEFT);
    }
    /** SDK默认工具的初始化样式；实际国内View内容由首次show前注册的公开工厂覆盖。 */
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

}
