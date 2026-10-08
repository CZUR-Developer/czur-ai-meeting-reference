package com.czur.aimeeting.reference.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.reference.meeting.MeetingSetup;
import com.czur.aimeeting.ui.OverlayController;
import com.czur.aimeeting.ui.OverlayPresentation;
import com.czur.aimeeting.ui.SubtitleTextView;
import java.util.List;

/**
 * 伙伴可编辑的一个形态示例：只注册 partner.single，不实现四形态、Mini或新的会议逻辑。
 * 有 UI AAR 时在首次显示前调用 MeetingAppAssembly.registerOverlayFactory(new SingleFormOverlayFactory())。
 * 只改本文件和装配，不改音源、模型、分栏或收口；没有专用演示页面或运行时切换按钮。
 */
public final class SingleFormOverlayFactory implements MeetingOverlayFactory {
    public static final String FORM_ID = "partner.single";
    @Override public Window create(Context context, Runnable onOpenApp, Runnable onSwapColumns, Runnable onStop) {
        return new SingleWindow(context, onOpenApp, onSwapColumns, onStop);
    }

    static final class SingleWindow implements Window {
        final OverlayController controller;
        SingleWindow(Context context, Runnable onOpenApp, Runnable onSwapColumns, Runnable onStop) {
            controller = new OverlayController(context);
            controller.registerForm(FORM_ID, (owner, formId) -> content(owner, onOpenApp, onSwapColumns, onStop), id -> {
                float scale = OverlayViewFactory.scale(context);
                var metrics = context.getResources().getDisplayMetrics();
                int width = OverlayViewFactory.px(1800, scale), height = OverlayViewFactory.px(293, scale);
                return new OverlayController.WindowSpec(width, height, (metrics.widthPixels - width) / 2,
                        metrics.heightPixels - height - OverlayViewFactory.px(10, scale), Gravity.TOP | Gravity.LEFT);
            });
        }
        @Override public boolean show(MeetingDisplaySnapshot initial) {
            return controller.show(FORM_ID) == OverlayController.DisplayCondition.READY;
        }
        @Override public boolean isShowing() { return controller.isShowing(); }
        @Override public void render(MeetingDisplaySnapshot display, MeetingSetup.Mode mode, boolean summaryEnabled,
                String timeText, boolean canStop, int audioLevel) {
            controller.render(OverlayViewFactory.presentation(display, mode, summaryEnabled, timeText, canStop, audioLevel));
        }
        @Override public void dismiss() { controller.release(); }
    }

    private static OverlayController.Content content(Context context, Runnable onOpenApp, Runnable onSwapColumns, Runnable onStop) {
        var style = OverlayViewFactory.style(context);
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(style.paddingPx, style.paddingPx, style.paddingPx, style.paddingPx);
        panel.setBackgroundColor(0xE6222933);
        TextView caption = new TextView(context);
        caption.setTag("single.caption");
        caption.setTextColor(Color.WHITE);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_PX, style.textSizePx * 0.6f);
        panel.addView(caption, new LinearLayout.LayoutParams(-1, style.lineHeightPx));
        SubtitleTextView subtitle = subtitle(context, "single.subtitle");
        panel.addView(subtitle);
        LinearLayout columns = new LinearLayout(context);
        SubtitleTextView left = subtitle(context, "single.left"), right = subtitle(context, "single.right");
        columns.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        columns.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(columns);
        LinearLayout actions = new LinearLayout(context);
        actions.addView(button(context, "返回会议", "single.open", onOpenApp));
        Button swap = button(context, "交换左右", "single.swap", onSwapColumns);
        actions.addView(swap);
        Button stop = button(context, "停止", "single.stop", onStop);
        actions.addView(stop);
        panel.addView(actions, new LinearLayout.LayoutParams(-1, style.actionHeightPx));
        return new OverlayController.Content() {
            public View view() { return panel; }
            public void render(OverlayPresentation input) {
                caption.setText(input.title() + " · " + input.timeText() + " · " + input.audioLevelLabel());
                boolean bilingual = !input.rows().isEmpty();
                subtitle.setVisibility(bilingual ? View.GONE : View.VISIBLE);
                columns.setVisibility(bilingual ? View.VISIBLE : View.GONE);
                swap.setVisibility(bilingual ? View.VISIBLE : View.GONE);
                subtitle.setParts(input.subtitles());
                if (bilingual) {
                    var row = input.rows().get(input.rows().size() - 1);
                    left.setParts(List.of(row.left()));
                    right.setParts(List.of(row.right()));
                }
                stop.setEnabled(input.canStop());
            }
        };
    }
    private static SubtitleTextView subtitle(Context context, String tag) {
        var style = OverlayViewFactory.style(context);
        SubtitleTextView view = new SubtitleTextView(context);
        view.setTag(tag);
        view.setTextColor(style.textColor);
        view.setTextSizePx(style.textSizePx);
        view.setFixedLineHeightPx(style.lineHeightPx);
        view.setMaxVisibleLines(3);
        view.setReserveLineSlots(true);
        return view;
    }
    private static Button button(Context context, String text, String tag, Runnable callback) {
        var style = OverlayViewFactory.style(context);
        Button view = new Button(context);
        view.setTag(tag);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, style.textSizePx * 0.6f);
        view.setMinHeight(0);
        view.setMinimumHeight(0);
        view.setPadding(style.paddingPx, 0, style.paddingPx, 0);
        view.setLayoutParams(new LinearLayout.LayoutParams(-2, style.actionHeightPx));
        view.setOnClickListener(clicked -> callback.run());
        return view;
    }
}
