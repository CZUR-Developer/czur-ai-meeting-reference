package com.czur.aimeeting.reference.ui;

import android.content.Context;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.widget.TextView;
import com.czur.aimeeting.reference.meeting.DisplaySnapshotPresentation.SubtitleRow;
import java.util.List;

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

    /**
     * 主线程设置宿主确定的固定行高，单位px。国内24设计字号对应36设计行高；
     * 仅改变基础TextView排版，不修改文字、修订、透明度或发言方路由。
     */
    public static void setLineHeightPx(TextView view, int lineHeightPx) {
        float fontHeight = view.getPaint().getFontMetrics().descent - view.getPaint().getFontMetrics().ascent;
        view.setLineSpacing(lineHeightPx - fontHeight, 1f);
    }

    /**
     * 主线程全量提交已路由的历史＋当前字幕，按国内连续文本拼接。
     * 每段保留自己的80%／100%文字样式；空列表清空，不追加历史或重算完成状态。
     * 基础Mock只使用系统TextView，SDK组合使用完整的尾部裁切字幕工具。
     */
    public static void renderParts(TextView view, List<SubtitleRow> rows) {
        SpannableStringBuilder styled = new SpannableStringBuilder();
        for (SubtitleRow row : rows) {
            int start = styled.length();
            styled.append(row.text());
            if (row.alpha() != 1f && styled.length() > start) {
                int color = view.getCurrentTextColor();
                int opacity = Math.round(Color.alpha(color) * row.alpha());
                styled.setSpan(new ForegroundColorSpan((color & 0x00FFFFFF) | (opacity << 24)),
                        start, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        view.setText(styled);
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
