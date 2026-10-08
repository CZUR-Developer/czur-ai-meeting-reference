package com.czur.aimeeting.reference.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.reference.meeting.MeetingSetup;
import com.czur.aimeeting.ui.OverlayController;
import com.czur.aimeeting.ui.OverlayPresentation;
import java.util.Objects;

/**
 * 伙伴的单形态替换例：只注册 partner.single，不要求实现四形态、Mini或新的会议逻辑。
 *
 * <p>使用相同的国内版显示内容与同侧原译投影，未装配的形态按钮自动隐藏。伙伴可以只
 * 改内容工厂和布局策略；音源、模型、左右路由、Stop及结果不需要改。实际装配仍是
 * MeetingAppAssembly.registerOverlayFactory(new SingleFormOverlayFactory())。
 */
public final class SingleFormOverlayFactory implements MeetingOverlayFactory {
    public static final String FORM_ID = "partner.single";
    @Override public Window create(Context context, Runnable onOpenApp, Runnable onSwapColumns, Runnable onStop) {
        return new SingleWindow(context, onOpenApp, onSwapColumns, onStop);
    }

    static final class SingleWindow implements Window {
        final OverlayController controller;
        private MeetingDisplaySnapshot display;
        private String timeText = "";
        private boolean canStop;
        private int audioLevel;

        SingleWindow(Context context, Runnable open, Runnable swap, Runnable stop) {
            controller = new OverlayController(context);
            controller.registerForm(FORM_ID, (owner, formId) -> {
                CzurOverlayViews views = new CzurOverlayViews(owner,
                        display.bilingual() ? "translation-normal" : "subtitle",
                        new CzurOverlayViews.Actions(open, swap, stop, null, null, null, null, null));
                return new OverlayController.Content() {
                    public View view() { return views.view(); }
                    public void render(OverlayPresentation ignored) {
                        views.render(display, timeText, canStop, audioLevel, false);
                    }
                };
            }, id -> {
                float scale = OverlayViewFactory.scale(context);
                var metrics = context.getResources().getDisplayMetrics();
                int width = OverlayViewFactory.px(1800, scale), height = OverlayViewFactory.px(293, scale);
                return new OverlayController.WindowSpec(width, height, (metrics.widthPixels - width) / 2,
                        metrics.heightPixels - height - OverlayViewFactory.px(10, scale), Gravity.TOP | Gravity.LEFT);
            });
        }

        @Override public boolean show(MeetingDisplaySnapshot initial) {
            // 再次显示已隐藏的窗口保留最近输入，不以宿主传来的旧初始快照覆盖。
            if (display == null) display = Objects.requireNonNull(initial);
            return controller.show(FORM_ID) == OverlayController.DisplayCondition.READY;
        }
        @Override public boolean isShowing() { return controller.isShowing(); }
        @Override public void render(MeetingDisplaySnapshot display, MeetingSetup.Mode mode, boolean summaryEnabled,
                String timeText, boolean canStop, int audioLevel) {
            this.display = display;
            this.timeText = timeText;
            this.canStop = canStop;
            this.audioLevel = audioLevel;
            controller.render(OverlayPresentation.empty());
        }
        @Override public void dismiss() { controller.release(); }
    }
}
