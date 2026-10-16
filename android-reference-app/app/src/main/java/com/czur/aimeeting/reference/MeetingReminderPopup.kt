package com.czur.aimeeting.reference

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.czur.aimeeting.reference.meeting.MeetingReminderPresentation

/** 国内蓝卡提醒的纯显示摘取，不计时、不改阈值，确认只交给原宿主。 */
class MeetingReminderPopup(context: Context, cardOnly: Boolean, onConfirm: Runnable) {
    private val root: View
    private val title: TextView
    private val message: TextView
    init {
        val card = LinearLayout(context).apply {
            id = R.id.reminder_card
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pt(context, 40), pt(context, 25), pt(context, 40), pt(context, 30))
            background = GradientDrawable().apply { setColor(context.getColor(R.color.reference_blue)); cornerRadius = pt(context, 40).toFloat() }
        }
        root = if (cardOnly) card.apply { minimumWidth = pt(context, 500); minimumHeight = pt(context, 360) }
        else FrameLayout(context).apply {
            setBackgroundColor(0x80000000.toInt()); isClickable = false
            addView(card, FrameLayout.LayoutParams(pt(context, 500), pt(context, 360), Gravity.CENTER))
        }
        root.visibility = View.GONE
        title = TextView(context).apply {
            id = R.id.reminder_title; includeFontPadding = false; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, pt(context, 36).toFloat()); setTypeface(null, Typeface.BOLD)
        }
        card.addView(title, LinearLayout.LayoutParams(-1, -2))
        message = TextView(context).apply {
            id = R.id.reminder_message; includeFontPadding = false; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, pt(context, 24).toFloat()); setTypeface(null, Typeface.BOLD)
            setLineSpacing(pt(context, 10).toFloat(), 1f)
        }
        card.addView(message, LinearLayout.LayoutParams(-1, 0, 1f))
        val confirm = Button(context).apply {
            id = R.id.reminder_confirm; text = "确认"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, pt(context, 20).toFloat()); setTypeface(null, Typeface.BOLD)
            setTextColor(context.getColor(R.color.reference_blue)); backgroundTintList = null; stateListAnimator = null
            setPadding(0, 0, 0, 0); minWidth = 0; minHeight = 0
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = pt(context, 15).toFloat() }
            setOnClickListener { onConfirm.run() }
        }
        card.addView(confirm, LinearLayout.LayoutParams(pt(context, 180), pt(context, 50)))
    }
    fun view() = root
    fun render(notice: MeetingReminderPresentation.Notice) {
        if (notice.kind() == MeetingReminderPresentation.Kind.NONE) { root.visibility = View.GONE; return }
        title.text = notice.title(); message.text = notice.message(); root.visibility = View.VISIBLE
    }
    private fun pt(context: Context, units: Int) = ReferenceUiScale.px(context, units)
}
