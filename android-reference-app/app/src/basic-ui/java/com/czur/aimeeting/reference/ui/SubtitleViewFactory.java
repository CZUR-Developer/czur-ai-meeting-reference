package com.czur.aimeeting.reference.ui;

import android.content.Context;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * 未提供UI AAR时编译的基础TextView工厂，保障干净Mock可重建。
 * 只承载原有基础文字和状态，不复制SDK完整输入裁切、重排或窗口工具。
 * 与UI工具工厂具有相同窄入口，页面／会议逻辑无需按构建模式分支。
 */
public final class SubtitleViewFactory {
    private SubtitleViewFactory() { }

    /** 主线程创建基础View；字号由宿主以sp给定，窗口可保留基础行槽位。 */
    public static TextView create(Context context, float textSizeSp, int maxVisibleLines,
            boolean reserveLineSlots) {
        TextView view = new TextView(context);
        view.setTextSize(textSizeSp);
        view.setIncludeFontPadding(false);
        view.setMaxLines(maxVisibleLines);
        float textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp,
                context.getResources().getDisplayMetrics());
        if (reserveLineSlots) view.setMinHeight(Math.round(textSizePx * 1.3f) * maxVisibleLines);
        return view;
    }

    /** 全量替换已投影文字；透明度写在文字span上，View自身alpha保持默认1。 */
    public static void render(TextView view, String segmentId, CharSequence text, float alpha) {
        SpannableStringBuilder styled = new SpannableStringBuilder(text);
        if (styled.length() > 0 && alpha != 1.0f) {
            int color = view.getCurrentTextColor();
            int opacity = Math.round(Color.alpha(color) * alpha);
            styled.setSpan(new ForegroundColorSpan((color & 0x00FFFFFF) | (opacity << 24)),
                    0, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(styled);
    }
}
