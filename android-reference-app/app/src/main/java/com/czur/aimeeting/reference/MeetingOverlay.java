package com.czur.aimeeting.reference;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.czur.aimeeting.reference.meeting.MeetingDisplayState;

/**
 * 参考 App 的最小跨应用字幕窗。只负责展示和转发停止点击；模型连接、会议收口和
 * 字幕修订仍由会议页面管理。窗口只在用户主动打开且授予悬浮窗权限后创建。
 */
final class MeetingOverlay {
    private final Context context;
    private final WindowManager windowManager;
    private View root;
    private TextView transcriptView;
    private TextView elapsedView;
    private Button stopButton;

    MeetingOverlay(Context context) {
        this.context = context;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isShowing() { return root != null; }

    void show(Runnable onOpenApp, Runnable onStop) {
        if (root != null) return;
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(24, 18, 24, 18);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE6222933);
        background.setCornerRadius(18);
        panel.setBackground(background);

        LinearLayout controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText("实时字幕");
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        controls.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        elapsedView = new TextView(context);
        elapsedView.setTextColor(Color.WHITE);
        controls.addView(elapsedView);
        panel.addView(controls);

        transcriptView = new TextView(context);
        transcriptView.setTextSize(22);
        transcriptView.setTextColor(Color.WHITE);
        transcriptView.setPadding(0, 10, 0, 10);
        panel.addView(transcriptView);

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.END);
        Button openButton = new Button(context);
        openButton.setText("返回会议");
        openButton.setOnClickListener(view -> onOpenApp.run());
        actions.addView(openButton);
        stopButton = new Button(context);
        stopButton.setText("停止");
        stopButton.setOnClickListener(view -> onStop.run());
        actions.addView(stopButton);
        panel.addView(actions);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = 24;
        windowManager.addView(panel, params);
        root = panel;
    }

    void render(MeetingDisplayState state, long elapsedRealtimeMs, boolean canStop) {
        if (root == null) return;
        transcriptView.setText(state.transcript());
        transcriptView.setAlpha(state.transcriptAlpha());
        elapsedView.setText(state.elapsed(elapsedRealtimeMs));
        stopButton.setEnabled(canStop);
    }

    void dismiss() {
        if (root == null) return;
        windowManager.removeView(root);
        root = null;
        transcriptView = null;
        elapsedView = null;
        stopButton = null;
    }
}
