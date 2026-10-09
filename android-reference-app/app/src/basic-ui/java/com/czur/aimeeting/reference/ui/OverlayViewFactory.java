package com.czur.aimeeting.reference.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.reference.meeting.MeetingSetup;

/**
 * 未提供 UI AAR 的基础构建，同样复用国内版 View／资源及 SDK 已决定的发言方快照。
 *
 * <p>只提供当前字幕或普通双栏，不复制 SDK 的四形态／Mini 生命周期。完整参考交付依赖
 * UI AAR，由 meeting-ui 工厂装配 SDK 默认管理工具。权限和会议仍由原宿主负责。
 */
public final class OverlayViewFactory implements MeetingOverlayFactory {
    @Override public Window create(Context context, Runnable onOpenApp, Runnable onSwapColumns, Runnable onStop) {
        return new BasicWindow(context, onOpenApp, onSwapColumns, onStop);
    }

    private static final class BasicWindow implements Window {
        private final Context context;
        private final WindowManager manager;
        private final Runnable open, swap, stop;
        private CzurOverlayViews views;
        private boolean showing;

        BasicWindow(Context context, Runnable onOpenApp, Runnable onSwapColumns, Runnable onStop) {
            this.context = context;
            manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            open = onOpenApp;
            swap = onSwapColumns;
            stop = onStop;
        }
        @Override public boolean show(MeetingDisplaySnapshot initial) {
            if (showing) return true;
            if (!Settings.canDrawOverlays(context)) return false;
            views = new CzurOverlayViews(context, initial.bilingual() ? "translation-normal" : "subtitle",
                    new CzurOverlayViews.Actions(open, swap, stop, this::dismiss, null, null, null, null));
            float scale = CzurOverlayViews.designScale(context);
            int width = Math.round(1800 * scale), height = Math.round((initial.bilingual() ? 293 : 222) * scale);
            var metrics = context.getResources().getDisplayMetrics();
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = (metrics.widthPixels - width) / 2;
            params.y = metrics.heightPixels - height - Math.round(10 * scale);
            manager.addView(views.view(), params);
            showing = true;
            views.render(initial, "", false, 0, false);
            return true;
        }
        @Override public boolean isShowing() { return showing; }
        @Override public IBinder windowToken() {
            return showing && views != null ? views.view().getWindowToken() : null;
        }
        @Override public void render(MeetingDisplaySnapshot display, MeetingSetup.Mode mode, boolean summaryEnabled,
                String timeText, boolean canStop, int audioLevel) {
            if (views != null) views.render(display, timeText, canStop, audioLevel, false);
        }
        @Override public void dismiss() {
            if (!showing) return;
            manager.removeViewImmediate(views.view());
            views = null;
            showing = false;
        }
    }
}
