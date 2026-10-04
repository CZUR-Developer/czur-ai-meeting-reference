package com.czur.aimeeting.reference;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.czur.aimeeting.reference.meeting.MeetingReminderPresentation;

/** 会议页和已开启的跨应用悬浮窗共用的全屏遮罩与中央提醒卡片。 */
final class MeetingReminderPopup {
    private final View root;
    private final TextView title;
    private final TextView message;

    MeetingReminderPopup(Context context, boolean cardOnly, Runnable onConfirm) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 28), dp(context, 28), dp(context, 28), dp(context, 28));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(context, 20));
        card.setBackground(background);
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        if (cardOnly) {
            root = card;
        } else {
            FrameLayout screen = new FrameLayout(context);
            screen.setBackgroundColor(0xB8000000);
            screen.setClickable(false);
            FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                    Math.min(screenWidth - dp(context, 48), dp(context, 440)),
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            screen.addView(card, cardParams);
            root = screen;
        }
        root.setVisibility(View.GONE);

        title = new TextView(context);
        title.setTextColor(0xFF1F2937);
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);
        message = new TextView(context);
        message.setTextColor(0xFF4B5563);
        message.setTextSize(17);
        message.setLineSpacing(dp(context, 4), 1);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(-1, -2);
        messageParams.topMargin = dp(context, 18);
        card.addView(message, messageParams);
        Button confirm = new Button(context);
        confirm.setText("确认");
        confirm.setOnClickListener(view -> onConfirm.run());
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(-2, -2);
        confirmParams.gravity = Gravity.END;
        confirmParams.topMargin = dp(context, 22);
        card.addView(confirm, confirmParams);
    }

    View view() { return root; }

    void render(MeetingReminderPresentation.Notice notice) {
        if (notice.kind() == MeetingReminderPresentation.Kind.NONE) {
            root.setVisibility(View.GONE);
            return;
        }
        title.setText(notice.title());
        message.setText(notice.message());
        root.setVisibility(View.VISIBLE);
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
