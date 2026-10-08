package com.czur.aimeeting.reference.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.reference.meeting.DisplaySnapshotPresentation;
import com.czur.aimeeting.reference.meeting.MeetingRuntimeFactory;
import com.czur.aimeeting.reference.meeting.MeetingSetup;

/**
 * 无 UI AAR 的干净构建：一个基础字幕／双栏窗口，不复制默认四形态或 Mini 管理。
 * 有 UI AAR 时此文件不参与编译，改用 meeting-ui 同名工厂。所有入口由原宿主在主线程调用。
 */
public final class OverlayViewFactory implements MeetingOverlayFactory {
    @Override public Window create(Context context, Runnable onOpenApp, Runnable onSwapColumns, Runnable onStop) {
        return new BasicWindow(context, onOpenApp, onSwapColumns, onStop);
    }

    private static final class BasicWindow implements Window {
        private final Context context;
        private final WindowManager manager;
        private final LinearLayout panel, columns;
        private final TextView title, time, subtitle, left, right;
        private final ProgressBar level;
        private final Button stop, swap;
        private boolean showing;

        BasicWindow(Context context, Runnable onOpenApp, Runnable onSwapColumns, Runnable onStop) {
            this.context = context;
            manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            panel = new LinearLayout(context);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(24, 12, 24, 12);
            panel.setBackgroundColor(0xE6222933);
            title = label(context);
            panel.addView(title);
            time = label(context);
            panel.addView(time);
            level = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            level.setMax(100);
            panel.addView(level, new LinearLayout.LayoutParams(-1, -2));
            subtitle = SubtitleViewFactory.create(context, 22, 3, true);
            subtitle.setTextColor(Color.WHITE);
            panel.addView(subtitle);
            columns = new LinearLayout(context);
            left = SubtitleViewFactory.create(context, 22, 3, true);
            right = SubtitleViewFactory.create(context, 22, 3, true);
            left.setTextColor(Color.WHITE);
            right.setTextColor(Color.WHITE);
            columns.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
            columns.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
            panel.addView(columns);
            LinearLayout actions = new LinearLayout(context);
            actions.addView(button(context, "返回会议", onOpenApp));
            swap = button(context, "交换左右", onSwapColumns);
            actions.addView(swap);
            stop = button(context, "停止", onStop);
            actions.addView(stop);
            panel.addView(actions);
        }
        @Override public boolean show(MeetingDisplaySnapshot initial) {
            if (showing) return true;
            if (!Settings.canDrawOverlays(context)) return false;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    Math.max(1, context.getResources().getDisplayMetrics().widthPixels - 48), -2,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            params.y = 24;
            manager.addView(panel, params);
            showing = true;
            render(initial, MeetingSetup.Mode.LIVE_TRANSCRIPT, false, "", false, 0);
            return true;
        }
        @Override public boolean isShowing() { return showing; }
        @Override public void render(MeetingDisplaySnapshot display, MeetingSetup.Mode mode, boolean summaryEnabled,
                String timeText, boolean canStop, int audioLevel) {
            title.setText(display.bilingual() ? "双语互译 · " + DisplaySnapshotPresentation.leftLabel(display)
                    + " / " + DisplaySnapshotPresentation.rightLabel(display) : summaryEnabled ? "AI 会议纪要" : "实时字幕");
            time.setText(timeText + (MeetingRuntimeFactory.requiresMicrophone()
                    ? " · 输入电平：" + audioLevel + "/100" : " · Mock 未实采"));
            level.setProgress(audioLevel);
            subtitle.setVisibility(display.bilingual() ? View.GONE : View.VISIBLE);
            columns.setVisibility(display.bilingual() ? View.VISIBLE : View.GONE);
            swap.setVisibility(display.bilingual() ? View.VISIBLE : View.GONE);
            var latest = DisplaySnapshotPresentation.latestSubtitle(display);
            SubtitleViewFactory.render(subtitle, latest.segmentId(), latest.text(), latest.alpha());
            if (display.bilingual() && !display.rows().isEmpty()) {
                var row = DisplaySnapshotPresentation.bilingual(display.rows().get(display.rows().size() - 1), display.swapped());
                SubtitleViewFactory.render(left, row.segmentId(), row.left(), row.leftAlpha());
                SubtitleViewFactory.render(right, row.segmentId(), row.right(), row.rightAlpha());
            } else {
                SubtitleViewFactory.render(left, "", "等待字幕…", 0.8f);
                SubtitleViewFactory.render(right, "", "", 1.0f);
            }
            stop.setEnabled(canStop);
        }
        @Override public void dismiss() {
            if (!showing) return;
            manager.removeViewImmediate(panel);
            showing = false;
        }
        private static TextView label(Context context) {
            TextView view = new TextView(context);
            view.setTextColor(Color.WHITE);
            return view;
        }
        private static Button button(Context context, String caption, Runnable callback) {
            Button view = new Button(context);
            view.setText(caption);
            view.setOnClickListener(clicked -> callback.run());
            return view;
        }
    }
}
