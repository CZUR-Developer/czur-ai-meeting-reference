package com.czur.aimeeting.reference.ui

import android.content.Context
import android.util.TypedValue
import android.widget.TextView
import com.czur.aimeeting.ui.SubtitlePart
import com.czur.aimeeting.ui.SubtitleTextView
import com.czur.aimeeting.reference.meeting.DisplaySnapshotPresentation.SubtitleRow

/** SDK UI 薄工厂，只消费已投影文字，不判断语种、方向或左右栏。 */
object SubtitleViewFactory {
    @JvmStatic fun create(context: Context, textSizeSp: Float, maxVisibleLines: Int, reserveLineSlots: Boolean): TextView {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp, context.resources.displayMetrics)
        return SubtitleTextView(context).apply {
            setTextSizePx(px)
            setFixedLineHeightPx(Math.round(px * 1.3f))
            setMaxVisibleLines(maxVisibleLines)
            setReserveLineSlots(reserveLineSlots)
        }
    }
    @JvmStatic fun setLineHeightPx(view: TextView, lineHeightPx: Int) = (view as SubtitleTextView).setFixedLineHeightPx(lineHeightPx)
    /** 全量输入 final 历史和当前句，连续显示，SDK 负责裁最近可见行。 */
    @JvmStatic fun renderParts(view: TextView, rows: List<SubtitleRow>) {
        (view as SubtitleTextView).setPartSeparator("")
        view.setParts(rows.map { SubtitlePart(it.segmentId(), it.text(), it.alpha() == 1f) })
    }
    @JvmStatic fun render(view: TextView, segmentId: String, text: CharSequence, alpha: Float) {
        (view as SubtitleTextView).setParts(listOf(SubtitlePart(segmentId, text, alpha == 1f)))
    }
}
