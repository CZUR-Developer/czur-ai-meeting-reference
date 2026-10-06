package com.czur.aimeeting.reference;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.czur.aimeeting.client.display.MeetingDisplayState;
import com.czur.aimeeting.reference.meeting.MeetingReminderPresentation;
import com.czur.aimeeting.reference.meeting.MeetingSetup;
import com.czur.aimeeting.reference.meeting.OverlayLayoutState;
import java.util.List;

/**
 * 参考 App 的最小跨应用字幕窗。只负责展示和转发停止点击；模型连接、会议收口和
 * 字幕修订由前台会议 Service 管理。窗口只在用户主动打开且授予悬浮窗权限后创建。
 */
final class MeetingOverlay {
    private static final String TAG = "AiMeetingReference";
    private final Context context;
    private final WindowManager windowManager;
    private View root;
    private WindowManager.LayoutParams params;
    private OverlayLayoutState layout;
    private LinearLayout expandedPanel;
    private Button miniButton;
    private TextView titleView;
    private TextView transcriptView;
    private LinearLayout bilingualRow;
    private TextView leftView;
    private TextView rightView;
    private TextView elapsedView;
    private View reminderScrim;
    private MeetingReminderPopup reminderPopup;
    private Runnable onConfirmReminder;
    private Button stopButton;
    private Button swapButton;
    private Button sizeButton;
    private ScrollView historyScroll;
    private LinearLayout historyRows;
    private MeetingDisplayState lastDisplay;
    private MeetingSetup.Mode lastMode = MeetingSetup.Mode.LIVE_TRANSCRIPT;
    private String lastTimeText = "";
    private MeetingReminderPresentation.Notice lastReminder =
            MeetingReminderPresentation.Notice.NONE;
    private boolean lastAllowReminder;
    private boolean lastCanStop;

    MeetingOverlay(Context context) {
        this.context = context;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isShowing() { return root != null; }

    void show(MeetingDisplayState state, Runnable onOpenApp, Runnable onSwapColumns,
            Runnable onConfirmReminder, Runnable onStop) {
        if (root != null) return;
        this.onConfirmReminder = onConfirmReminder;
        layout = new OverlayLayoutState(state.isBilingual());
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE6222933);
        background.setCornerRadius(18);
        panel.setBackground(background);
        expandedPanel = new LinearLayout(context);
        expandedPanel.setOrientation(LinearLayout.VERTICAL);
        expandedPanel.setPadding(24, 18, 24, 18);
        panel.addView(expandedPanel);
        miniButton = new Button(context);
        miniButton.setText("AI 会议 · 恢复");
        miniButton.setOnClickListener(view -> {
            layout.restore();
            Log.i(TAG, "悬浮窗已从 Mini 恢复为 " + layout.current());
            applyLayout();
            renderLastState();
        });
        miniButton.setVisibility(View.GONE);
        panel.addView(miniButton);

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
        expandedPanel.addView(controls);
        transcriptView = new TextView(context);
        transcriptView.setTextSize(22);
        transcriptView.setTextColor(Color.WHITE);
        transcriptView.setPadding(0, 10, 0, 10);
        expandedPanel.addView(transcriptView);

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
        expandedPanel.addView(bilingualRow);

        historyRows = new LinearLayout(context);
        historyRows.setOrientation(LinearLayout.VERTICAL);
        historyScroll = new ScrollView(context);
        historyScroll.addView(historyRows);
        historyScroll.setVisibility(View.GONE);
        expandedPanel.addView(historyScroll, new LinearLayout.LayoutParams(-1, dp(400)));

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
        sizeButton = new Button(context);
        sizeButton.setOnClickListener(view -> {
            if (layout.current() == OverlayLayoutState.Type.TRANSLATION_NORMAL) layout.showLarge();
            else layout.showNormal();
            Log.i(TAG, "互译悬浮窗切换为 " + layout.current());
            applyLayout();
            renderLastState();
        });
        actions.addView(sizeButton);
        Button collapseButton = new Button(context);
        collapseButton.setText("收起");
        collapseButton.setOnClickListener(view -> {
            layout.collapse();
            Log.i(TAG, "悬浮窗已收起到 Mini");
            applyLayout();
        });
        actions.addView(collapseButton);
        stopButton = new Button(context);
        stopButton.setText("停止");
        stopButton.setOnClickListener(view -> onStop.run());
        actions.addView(stopButton);
        expandedPanel.addView(actions);

        params = new WindowManager.LayoutParams(
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

    void render(MeetingDisplayState state, MeetingSetup.Mode mode, String timeText,
            MeetingReminderPresentation.Notice reminder, boolean canStop,
            boolean allowOverlayReminder) {
        if (root == null) return;
        lastDisplay = state;
        lastMode = mode;
        lastTimeText = timeText;
        lastReminder = reminder;
        lastAllowReminder = allowOverlayReminder;
        lastCanStop = canStop;
        renderReminder(reminder, allowOverlayReminder);
        if (layout.current() == OverlayLayoutState.Type.MINI) return;
        boolean bilingual = state.isBilingual();
        boolean large = layout.current() == OverlayLayoutState.Type.TRANSLATION_LARGE;
        MeetingDisplayState.BilingualRow current = bilingual ? state.latestBilingualRow() : null;
        titleView.setText(bilingual ? "双语互译  " + state.leftLabel() + " / "
                + state.rightLabel()
                + (large ? " · 大窗" : " · 普通窗")
                + (current != null && current.languageFallback() ? "（语种降级）" : "")
                : mode == MeetingSetup.Mode.MEETING_MINUTES ? "AI 会议纪要" : "实时字幕");
        transcriptView.setVisibility(bilingual ? View.GONE : View.VISIBLE);
        bilingualRow.setVisibility(bilingual && !large ? View.VISIBLE : View.GONE);
        historyScroll.setVisibility(bilingual && large ? View.VISIBLE : View.GONE);
        swapButton.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        sizeButton.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        sizeButton.setText(large ? "普通窗" : "大窗");
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
            if (large) renderHistory(state.bilingualRows());
        }
        elapsedView.setText(timeText);
        stopButton.setEnabled(canStop);
    }

    private void renderReminder(MeetingReminderPresentation.Notice notice, boolean allowed) {
        if (!allowed || notice.kind() == MeetingReminderPresentation.Kind.NONE) {
            clearReminder();
            return;
        }
        if (reminderPopup == null) {
            try {
                reminderScrim = new View(context);
                reminderScrim.setBackgroundColor(0xB8000000);
                windowManager.addView(reminderScrim, new WindowManager.LayoutParams(
                        -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT));
                reminderPopup = new MeetingReminderPopup(context, true, onConfirmReminder);
                WindowManager.LayoutParams cardParams = new WindowManager.LayoutParams(
                        Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(48),
                                dp(440)), WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        android.graphics.PixelFormat.TRANSLUCENT);
                cardParams.gravity = Gravity.CENTER;
                reminderPopup.render(notice);
                windowManager.addView(reminderPopup.view(), cardParams);
            } catch (RuntimeException unavailable) {
                clearReminder();
                Log.w(TAG, "跨应用提醒窗不可用 reason="
                        + unavailable.getClass().getSimpleName());
            }
        } else {
            reminderPopup.render(notice);
        }
    }

    private void clearReminder() {
        if (reminderPopup != null) {
            if (reminderPopup.view().getParent() != null) {
                windowManager.removeView(reminderPopup.view());
            }
            reminderPopup = null;
        }
        if (reminderScrim != null) {
            if (reminderScrim.getParent() != null) windowManager.removeView(reminderScrim);
            reminderScrim = null;
        }
    }

    /** 大窗展示最近几行；行数据始终取自 Service 的同一份已路由会议状态。 */
    private void renderHistory(List<MeetingDisplayState.BilingualRow> rows) {
        historyRows.removeAllViews();
        if (rows.isEmpty()) {
            TextView waiting = new TextView(context);
            waiting.setText("等待字幕…");
            waiting.setTextColor(Color.WHITE);
            historyRows.addView(waiting);
            return;
        }
        for (int index = Math.max(0, rows.size() - 8); index < rows.size(); index++) {
            MeetingDisplayState.BilingualRow segment = rows.get(index);
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView left = historyCell(segment.left(), segment.leftAlpha());
            TextView right = historyCell(segment.right(), segment.rightAlpha());
            row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
            historyRows.addView(row);
        }
    }

    private TextView historyCell(String text, float alpha) {
        TextView cell = new TextView(context);
        cell.setText(text);
        cell.setTextColor(Color.WHITE);
        cell.setTextSize(20);
        cell.setAlpha(alpha);
        cell.setPadding(0, 8, 12, 8);
        return cell;
    }

    private void renderLastState() {
        if (lastDisplay != null) render(lastDisplay, lastMode, lastTimeText, lastReminder,
                lastCanStop, lastAllowReminder);
    }

    /** Mini 调整成右上角的小入口；展开后仍使用原会话窗口和最近的字幕快照。 */
    private void applyLayout() {
        boolean mini = layout.current() == OverlayLayoutState.Type.MINI;
        expandedPanel.setVisibility(mini ? View.GONE : View.VISIBLE);
        miniButton.setVisibility(mini ? View.VISIBLE : View.GONE);
        params.width = mini ? dp(180) : WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = mini ? Gravity.TOP | Gravity.END : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.x = mini ? dp(16) : 0;
        params.y = dp(24);
        windowManager.updateViewLayout(root, params);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    void dismiss() {
        if (root == null) return;
        clearReminder();
        windowManager.removeView(root);
        root = null;
        params = null;
        layout = null;
        expandedPanel = null;
        miniButton = null;
        titleView = null;
        transcriptView = null;
        bilingualRow = null;
        leftView = null;
        rightView = null;
        elapsedView = null;
        onConfirmReminder = null;
        stopButton = null;
        swapButton = null;
        sizeButton = null;
        historyScroll = null;
        historyRows = null;
        lastDisplay = null;
    }
}
