package com.czur.aimeeting.reference

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.CheckBox

/**
 * 国内 CZSwitch 的纯显示摘取：保持原生 CheckBox 的状态、启用和监听语义。
 * 只复用配色、76×40 比例及拇指位置，不依赖 Starry 属性、pt 扩展或业务状态。
 */
class CzurReferenceSwitch(context: Context, attributes: AttributeSet?) : CheckBox(context, attributes) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { buttonDrawable = null }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = height / 48f
        val capsuleWidth = 76 * unit
        val capsuleHeight = 40 * unit
        val right = width.toFloat()
        val left = right - capsuleWidth
        val top = (height - capsuleHeight) / 2f
        paint.style = Paint.Style.FILL
        paint.color = if (isChecked) BLUE else Color.rgb(241, 243, 254)
        canvas.drawRoundRect(left, top, right, top + capsuleHeight, capsuleHeight / 2, capsuleHeight / 2, paint)
        paint.style = Paint.Style.STROKE
        paint.color = BLUE
        paint.strokeWidth = 2 * unit
        canvas.drawRoundRect(left + unit, top + unit, right - unit, top + capsuleHeight - unit,
            capsuleHeight / 2, capsuleHeight / 2, paint)
        paint.style = Paint.Style.FILL
        val radius = capsuleHeight / 2 - 6 * unit
        val thumbX = if (isChecked) right - capsuleHeight / 2 else left + capsuleHeight / 2
        paint.color = if (isChecked) Color.WHITE else BLUE
        canvas.drawCircle(thumbX, top + capsuleHeight / 2, radius, paint)
        paint.color = if (isChecked) Color.WHITE else BLUE
        paint.textSize = 16 * unit
        paint.textAlign = if (isChecked) Paint.Align.LEFT else Paint.Align.RIGHT
        val baseline = top + (capsuleHeight - paint.fontMetrics.top - paint.fontMetrics.bottom) / 2
        canvas.drawText(if (isChecked) "ON" else "OFF",
            if (isChecked) left + 10 * unit else right - 10 * unit, baseline, paint)
    }
    override fun setChecked(checked: Boolean) { super.setChecked(checked); invalidate() }
    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else .5f }
    companion object { private val BLUE = Color.rgb(88, 121, 252) }
}
