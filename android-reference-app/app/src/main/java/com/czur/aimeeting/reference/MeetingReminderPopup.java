package com.czur.aimeeting.reference;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.czur.aimeeting.reference.meeting.MeetingReminderPresentation;

/**
 * 国内提醒的纯显示摘取，来源alert_warn_time.xml／common_float_double_btn.xml。
 * 500×360蓝卡、36白色标题、24白色正文、180×50确认；遮罩为50%黑色。
 * Notice及确认回调沿用既有规则，本类不计时、不改变阈值、不主动停止会议。
 */
final class MeetingReminderPopup {
    private final View root;
    private final TextView title;
    private final TextView message;

    MeetingReminderPopup(Context context, boolean cardOnly, Runnable onConfirm) {
        LinearLayout card = new LinearLayout(context);
        card.setId(R.id.reminder_card);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(pt(context, 40), pt(context, 25), pt(context, 40), pt(context, 30));
        GradientDrawable background = new GradientDrawable();
        background.setColor(context.getColor(R.color.reference_blue));
        background.setCornerRadius(pt(context, 40));
        card.setBackground(background);
        if (cardOnly) {
            root = card;
            card.setMinimumWidth(pt(context, 500));
            card.setMinimumHeight(pt(context, 360));
        } else {
            FrameLayout screen = new FrameLayout(context);
            screen.setBackgroundColor(0x80000000);
            screen.setClickable(false);
            screen.addView(card, new FrameLayout.LayoutParams(pt(context, 500), pt(context, 360), Gravity.CENTER));
            root = screen;
        }
        root.setVisibility(View.GONE);
        title = new TextView(context);
        title.setId(R.id.reminder_title);
        title.setIncludeFontPadding(false);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, pt(context, 36));
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        message = new TextView(context);
        message.setId(R.id.reminder_message);
        message.setIncludeFontPadding(false);
        message.setGravity(Gravity.CENTER);
        message.setTextColor(Color.WHITE);
        message.setTextSize(TypedValue.COMPLEX_UNIT_PX, pt(context, 24));
        message.setTypeface(null, Typeface.BOLD);
        message.setLineSpacing(pt(context, 10), 1);
        card.addView(message, new LinearLayout.LayoutParams(-1, 0, 1));
        Button confirm = new Button(context);
        confirm.setId(R.id.reminder_confirm);
        confirm.setText("确认");
        confirm.setTextSize(TypedValue.COMPLEX_UNIT_PX, pt(context, 20));
        confirm.setTypeface(null, Typeface.BOLD);
        confirm.setTextColor(context.getColor(R.color.reference_blue));
        confirm.setBackgroundTintList(null);
        confirm.setStateListAnimator(null);
        confirm.setPadding(0, 0, 0, 0);
        confirm.setMinWidth(0); confirm.setMinHeight(0);
        GradientDrawable confirmBackground = new GradientDrawable();
        confirmBackground.setColor(Color.WHITE);
        confirmBackground.setCornerRadius(pt(context, 15));
        confirm.setBackground(confirmBackground);
        confirm.setOnClickListener(view -> onConfirm.run());
        card.addView(confirm, new LinearLayout.LayoutParams(pt(context, 180), pt(context, 50)));
    }

    View view() { return root; }

    /** 只切换既有Notice的文字及可见性，确认只转发给原会议宿主。 */
    void render(MeetingReminderPresentation.Notice notice) {
        if (notice.kind() == MeetingReminderPresentation.Kind.NONE) {
            root.setVisibility(View.GONE);
            return;
        }
        title.setText(notice.title());
        message.setText(notice.message());
        root.setVisibility(View.VISIBLE);
    }

    private static int pt(Context context, int units) { return ReferenceUiScale.px(context, units); }
}
