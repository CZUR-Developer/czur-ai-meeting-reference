package com.czur.aimeeting.reference.ui

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.widget.TextView
import com.czur.aimeeting.reference.meeting.DisplaySnapshotPresentation.SubtitleRow

/** 无 UI AAR 的基础 TextView 工厂，不复制完整排版或窗口工具。 */
object SubtitleViewFactory {
    @JvmStatic fun create(context: Context, textSizeSp: Float, maxVisibleLines: Int, reserveLineSlots: Boolean): TextView =
        TextView(context).apply {
            textSize = textSizeSp
            includeFontPadding = false
            maxLines = maxVisibleLines
            val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp, context.resources.displayMetrics)
            if (reserveLineSlots) minHeight = Math.round(px * 1.3f) * maxVisibleLines
        }
    @JvmStatic fun setLineHeightPx(view: TextView, lineHeightPx: Int) {
        view.setLineSpacing(lineHeightPx - (view.paint.fontMetrics.descent - view.paint.fontMetrics.ascent), 1f)
    }
    @JvmStatic fun renderParts(view: TextView, rows: List<SubtitleRow>) {
        val styled = SpannableStringBuilder()
        for (row in rows) {
            val start = styled.length
            styled.append(row.text())
            style(view, styled, start, row.alpha())
        }
        view.text = styled
    }
    @JvmStatic fun render(view: TextView, segmentId: String, text: CharSequence, alpha: Float) {
        val styled = SpannableStringBuilder(text)
        style(view, styled, 0, alpha)
        view.text = styled
    }
    private fun style(view: TextView, styled: SpannableStringBuilder, start: Int, alpha: Float) {
        if (alpha == 1f || styled.length <= start) return
        val color = view.currentTextColor
        val opacity = Math.round(Color.alpha(color) * alpha)
        styled.setSpan(ForegroundColorSpan((color and 0x00ffffff) or (opacity shl 24)), start, styled.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
