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
    private TextView chineseView;
    private TextView englishView;
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

        transcriptView = new TextView(context);
        transcriptView.setTextSize(22);
        transcriptView.setTextColor(Color.WHITE);
        transcriptView.setPadding(0, 10, 0, 10);
        panel.addView(transcriptView);

        bilingualRow = new LinearLayout(context);
        bilingualRow.setOrientation(LinearLayout.HORIZONTAL);
        chineseView = new TextView(context);
        chineseView.setTextColor(Color.WHITE);
        chineseView.setTextSize(22);
        chineseView.setPadding(0, 10, 12, 10);
        bilingualRow.addView(chineseView, new LinearLayout.LayoutParams(0, -2, 1));
        englishView = new TextView(context);
        englishView.setTextColor(Color.WHITE);
        englishView.setTextSize(22);
        englishView.setPadding(12, 10, 0, 10);
        bilingualRow.addView(englishView, new LinearLayout.LayoutParams(0, -2, 1));
        bilingualRow.setVisibility(View.GONE);
        panel.addView(bilingualRow);

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
        boolean bilingual = state.hasTranslation();
        titleView.setText(bilingual ? "双语互译  中文 / English" : "实时字幕");
        transcriptView.setVisibility(bilingual ? View.GONE : View.VISIBLE);
        bilingualRow.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        transcriptView.setText(state.transcript());
        transcriptView.setAlpha(state.transcriptAlpha());
        if (bilingual) {
            MeetingDisplayState.BilingualRow current = state.latestBilingualRow();
            if (current != null) {
                chineseView.setText(current.chinese());
                chineseView.setAlpha(current.chineseAlpha());
                englishView.setText(current.english());
                englishView.setAlpha(current.englishAlpha());
            }
        }
        elapsedView.setText(state.elapsed(elapsedRealtimeMs));
        stopButton.setEnabled(canStop);
    }

    void dismiss() {
        if (root == null) return;
        windowManager.removeView(root);
        root = null;
        titleView = null;
        transcriptView = null;
        bilingualRow = null;
        chineseView = null;
        englishView = null;
        elapsedView = null;
        stopButton = null;
    }
}
