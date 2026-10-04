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
 * 字幕修订由前台会议 Service 管理。窗口只在用户主动打开且授予悬浮窗权限后创建。
 */
final class MeetingOverlay {
    private final Context context;
    private final WindowManager windowManager;
    private View root;
    private TextView titleView;
    private TextView transcriptView;
    private LinearLayout bilingualRow;
    private TextView leftView;
    private TextView rightView;
    private TextView elapsedView;
    private TextView reminderView;
    private Button stopButton;
    private Button swapButton;

    MeetingOverlay(Context context) {
        this.context = context;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isShowing() { return root != null; }

    void show(Runnable onOpenApp, Runnable onSwapColumns, Runnable onStop) {
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
        titleView = new TextView(context);
        titleView.setText("实时字幕");
        titleView.setTextColor(Color.WHITE);
        titleView.setTypeface(null, Typeface.BOLD);
        controls.addView(titleView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        elapsedView = new TextView(context);
        elapsedView.setTextColor(Color.WHITE);
        controls.addView(elapsedView);
        panel.addView(controls);
        reminderView = new TextView(context);
        reminderView.setTextColor(0xFFFFD166);
        reminderView.setVisibility(View.GONE);
        panel.addView(reminderView);

        transcriptView = new TextView(context);
        transcriptView.setTextSize(22);
        transcriptView.setTextColor(Color.WHITE);
        transcriptView.setPadding(0, 10, 0, 10);
        panel.addView(transcriptView);

        bilingualRow = new LinearLayout(context);
        bilingualRow.setOrientation(LinearLayout.HORIZONTAL);
        leftView = new TextView(context);
        leftView.setTextColor(Color.WHITE);
        leftView.setTextSize(22);
        leftView.setPadding(0, 10, 12, 10);
        bilingualRow.addView(leftView, new LinearLayout.LayoutParams(0, -2, 1));
        rightView = new TextView(context);
        rightView.setTextColor(Color.WHITE);
        rightView.setTextSize(22);
        rightView.setPadding(12, 10, 0, 10);
        bilingualRow.addView(rightView, new LinearLayout.LayoutParams(0, -2, 1));
        bilingualRow.setVisibility(View.GONE);
        panel.addView(bilingualRow);

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.END);
        Button openButton = new Button(context);
        openButton.setText("返回会议");
        openButton.setOnClickListener(view -> onOpenApp.run());
        actions.addView(openButton);
        swapButton = new Button(context);
        swapButton.setText("交换左右");
        swapButton.setOnClickListener(view -> onSwapColumns.run());
        actions.addView(swapButton);
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

    void render(MeetingDisplayState state, String timeText, String reminderText,
            boolean canStop) {
        if (root == null) return;
        boolean bilingual = state.isBilingual();
        MeetingDisplayState.BilingualRow current = bilingual ? state.latestBilingualRow() : null;
        titleView.setText(bilingual ? "双语互译  " + state.leftLabel() + " / "
                + state.rightLabel()
                + (current != null && current.languageFallback() ? "（语种降级）" : "")
                : "实时字幕");
        transcriptView.setVisibility(bilingual ? View.GONE : View.VISIBLE);
        bilingualRow.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        swapButton.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        transcriptView.setText(state.transcript());
        transcriptView.setAlpha(state.transcriptAlpha());
        if (bilingual) {
            if (current != null) {
                leftView.setText(current.left());
                leftView.setAlpha(current.leftAlpha());
                rightView.setText(current.right());
                rightView.setAlpha(current.rightAlpha());
            } else {
                leftView.setText("等待字幕…");
                rightView.setText("");
            }
        }
        elapsedView.setText(timeText);
        reminderView.setText(reminderText);
        reminderView.setVisibility(reminderText.isEmpty() ? View.GONE : View.VISIBLE);
        stopButton.setEnabled(canStop);
    }

    void dismiss() {
        if (root == null) return;
        windowManager.removeView(root);
        root = null;
        titleView = null;
        transcriptView = null;
        bilingualRow = null;
        leftView = null;
        rightView = null;
        elapsedView = null;
        reminderView = null;
        stopButton = null;
        swapButton = null;
    }
}
