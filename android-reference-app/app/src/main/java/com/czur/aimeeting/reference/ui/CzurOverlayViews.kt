package com.czur.aimeeting.reference.ui

import android.content.Context
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.reference.R
import com.czur.aimeeting.reference.meeting.DisplaySnapshotPresentation
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.loader.ResourceStreamLoader
import java.io.IOException

/**
 * 国内窗口的可编辑内容：只复用 View、资源和 1920×1080 几何关系，不持有音源或会议。
 * SDK 管理唯一窗口与 Mini 恢复；原译按已路由的同一发言方快照显示，不在 UI 判断 DOA。
 */
class CzurOverlayViews(context: Context, private val form: String, actions: Actions) {
    /** null 表示宿主未装配的显示能力，相关按钮不出现。 */
    data class Actions(private val open: Runnable?, private val swap: Runnable?, private val stop: Runnable?,
        private val collapse: Runnable?, private val large: Runnable?, private val normal: Runnable?,
        private val restore: Runnable?, private val toggleSimple: Runnable?) {
        fun open() = open
        fun swap() = swap
        fun stop() = stop
        fun collapse() = collapse
        fun large() = large
        fun normal() = normal
        fun restore() = restore
        fun toggleSimple() = toggleSimple
    }
    private val scale = designScale(context)
    private val root = FrameLayout(context).apply { tag = "overlay.panel"; clipChildren = true }
    private val background = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val voice = CzurVoiceLevelView(context).apply { tag = "overlay.level" }
    private val time = label(context, 22, "overlay.open")
    private val stop: ImageView
    private val collapse: ImageView
    private val size: ImageView
    private val swap: ImageView
    private val simple: ImageView
    private val subtitle = caption(context, "overlay.subtitle", WHITE, 3)
    private val leftSource = caption(context, "overlay.left", WHITE, 3)
    private val leftTranslation = caption(context, "overlay.left-translation", YELLOW, 3)
    private val rightSource = caption(context, "overlay.right", WHITE, 3)
    private val rightTranslation = caption(context, "overlay.right-translation", YELLOW, 3)
    private val leftColumn = column(context, leftSource, leftTranslation)
    private val rightColumn = column(context, rightSource, rightTranslation)
    private val recording = label(context, 24, "overlay.recording")
    private val recordingHint = label(context, 20, "overlay.recording-hint")
    private val recordingAnimation = RecordingImage(context)
    private val leftHistory = history(context, "overlay.history-left-column")
    private val rightHistory = history(context, "overlay.history-right-column")
    private val leftScroll = scroll(context, leftHistory, true)
    private val rightScroll = scroll(context, rightHistory, false)
    private var lastLeftScroll = 0L
    private var lastRightScroll = 0L
    init {
        when (form) {
            "subtitle" -> background.setImageResource(R.drawable.czur_overlay_subtitle_background)
            "translation-large" -> background.setImageResource(R.drawable.czur_overlay_large_background)
            "translation-normal" -> background.setImageResource(R.drawable.czur_overlay_normal_background)
        }
        root.addView(background, FrameLayout.LayoutParams(-1, -1))
        time.setTextColor(0xccffffff.toInt())
        time.contentDescription = context.getString(R.string.czur_overlay_open)
        time.setOnClickListener { actions.open()!!.run() }
        stop = icon(context, R.drawable.czur_overlay_stop, R.string.czur_overlay_stop, "overlay.stop", actions.stop())
        collapse = icon(context, R.drawable.czur_overlay_collapse, R.string.czur_overlay_collapse, "overlay.collapse", actions.collapse())
        size = icon(context, if (form == "translation-large") R.drawable.czur_overlay_normal else R.drawable.czur_overlay_large,
            if (form == "translation-large") R.string.czur_overlay_normal else R.string.czur_overlay_large,
            "overlay.size", if (form == "translation-large") actions.normal() else actions.large())
        swap = icon(context, R.drawable.czur_overlay_swap, R.string.czur_overlay_swap, "overlay.swap", actions.swap())
        simple = icon(context, R.drawable.czur_overlay_simple, R.string.czur_overlay_simple, "overlay.simple", actions.toggleSimple())
        recording.text = context.getString(R.string.czur_overlay_recording)
        recording.setTextColor(YELLOW)
        recording.setTypeface(null, Typeface.BOLD)
        recordingHint.text = context.getString(R.string.czur_overlay_recording_hint)
        when (form) {
            "mini" -> {
                val mini = MiniImage(context).apply {
                    tag = "overlay.restore"; contentDescription = context.getString(R.string.czur_overlay_restore)
                    setOnClickListener { actions.restore()!!.run() }
                }
                root.addView(mini, FrameLayout.LayoutParams(-1, -1))
                put(voice, 10, 15, 61, 30)
                voice.setOnClickListener { actions.restore()!!.run() }
                background.visibility = View.GONE
            }
            "subtitle" -> {
                put(time, 650, 14, 170, 44); put(voice, 832, 14, 136, 44)
                put(stop, 968, 14, 40, 40); put(collapse, 1028, 14, 40, 40)
                put(subtitle, 40, 82, 1700, 108); put(recording, 800, 82, 200, 40)
                put(recordingAnimation, 966, 86, 32, 32); put(recordingHint, 660, 126, 480, 36)
            }
            "translation-large" -> {
                put(time, 805, 50, 190, 30); put(voice, 832, 110, 136, 44)
                put(swap, 874, 184, 52, 28); put(stop, 880, 242, 40, 40)
                put(collapse, 880, 312, 40, 40); put(size, 880, 382, 40, 40)
                put(leftScroll, 40, 40, 700, 928); put(rightScroll, 1060, 40, 700, 928)
            }
            else -> {
                put(time, 805, 50, 190, 30); put(voice, 832, 98, 136, 44)
                put(swap, 874, 170, 52, 28); put(stop, 790, 223, 40, 40)
                put(collapse, 850, 223, 40, 40); put(size, 910, 223, 40, 40); put(simple, 970, 223, 40, 40)
                put(leftColumn, 40, 40, 700, 216); put(rightColumn, 1060, 40, 700, 216)
            }
        }
    }
    fun view(): View = root

    /** 完整替换已路由输入，不追加事件账本；计时、电平和停止状态来自同一宿主。 */
    fun render(display: MeetingDisplaySnapshot, timeText: String, canStop: Boolean, audioLevel: Int, simpleMode: Boolean) {
        time.text = timeText.replace("已进行 ", "").split(" · ", limit = 2)[0].replace(":", " : ")
        stop.isEnabled = canStop; stop.alpha = if (canStop) 1f else .4f
        voice.setLevel(audioLevel)
        if (form == "mini") return
        if (form == "subtitle") {
            val parts = display.rows().mapNotNull { DisplaySnapshotPresentation.subtitle(it) }.filter { it.text().isNotBlank() }
            SubtitleViewFactory.renderParts(subtitle, parts)
            val visible = if (parts.isNotEmpty()) View.GONE else View.VISIBLE
            recording.visibility = visible; recordingAnimation.visibility = visible; recordingHint.visibility = visible
            return
        }
        val left = mutableListOf<DisplaySnapshotPresentation.SpeakerColumnRow>()
        val right = mutableListOf<DisplaySnapshotPresentation.SpeakerColumnRow>()
        for (row in display.rows()) {
            val item = DisplaySnapshotPresentation.speakerColumn(row, display.swapped())
            when (item.side()) {
                MeetingDisplaySnapshot.Side.A -> left += item
                MeetingDisplaySnapshot.Side.B -> right += item
                MeetingDisplaySnapshot.Side.UNRESOLVED -> Unit
            }
        }
        if (form == "translation-large") {
            renderHistory(leftHistory, left, "overlay.history-left", "overlay.history-left-translation")
            renderHistory(rightHistory, right, "overlay.history-right", "overlay.history-right-translation")
            if (SystemClock.uptimeMillis() - lastLeftScroll >= 2000) leftScroll.post { leftScroll.fullScroll(View.FOCUS_DOWN) }
            if (SystemClock.uptimeMillis() - lastRightScroll >= 2000) rightScroll.post { rightScroll.fullScroll(View.FOCUS_DOWN) }
        } else {
            renderColumn(leftSource, leftTranslation, left)
            renderColumn(rightSource, rightTranslation, right)
            applySimpleMode(simpleMode)
        }
    }
    private fun renderColumn(source: TextView, translation: TextView, rows: List<DisplaySnapshotPresentation.SpeakerColumnRow>) {
        val sources = rows.filter { it.source().isNotBlank() }.map { DisplaySnapshotPresentation.SubtitleRow(it.segmentId(), it.source(), it.sourceAlpha()) }
        val translations = rows.filter { it.translated().isNotBlank() }.map { DisplaySnapshotPresentation.SubtitleRow(it.segmentId(), it.translated(), it.translatedAlpha()) }
        SubtitleViewFactory.renderParts(source, sources)
        SubtitleViewFactory.renderParts(translation, translations)
    }
    private fun renderHistory(history: LinearLayout, rows: List<DisplaySnapshotPresentation.SpeakerColumnRow>, sourceTag: String, translatedTag: String) {
        while (history.childCount > rows.size) history.removeViewAt(history.childCount - 1)
        for ((index, row) in rows.withIndex()) {
            val item = if (index == history.childCount) LinearLayout(root.context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, px(16))
                addView(caption(root.context, sourceTag, WHITE, Int.MAX_VALUE))
                addView(caption(root.context, translatedTag, YELLOW, Int.MAX_VALUE))
                history.addView(this, LinearLayout.LayoutParams(-1, -2))
            } else history.getChildAt(index) as LinearLayout
            SubtitleViewFactory.render(item.getChildAt(0) as TextView, row.segmentId(), row.source(), row.sourceAlpha())
            SubtitleViewFactory.render(item.getChildAt(1) as TextView, row.segmentId(), row.translated(), row.translatedAlpha())
        }
    }
    private fun applySimpleMode(enabled: Boolean) {
        background.visibility = if (enabled) View.INVISIBLE else View.VISIBLE
        time.visibility = if (enabled) View.GONE else View.VISIBLE
        voice.visibility = if (enabled) View.GONE else View.VISIBLE
        swap.visibility = if (enabled) View.GONE else View.VISIBLE
        for (cell in listOf(leftSource, leftTranslation, rightSource, rightTranslation)) {
            cell.maxLines = if (enabled) 2 else 3
            cell.setShadowLayer(if (enabled) px(4).toFloat() else 0f, 0f, if (enabled) px(2).toFloat() else 0f,
                if (enabled) 0x80000000.toInt() else Color.TRANSPARENT)
        }
        position(leftColumn, 40, if (enabled) 2 else 40, 700, if (enabled) 148 else 216)
        position(rightColumn, 1060, if (enabled) 2 else 40, 700, if (enabled) 148 else 216)
        val buttonTop = if (enabled) 99 else 223
        position(stop, 790, buttonTop, 40, 40); position(collapse, 852, buttonTop, 40, 40)
        position(size, 914, buttonTop, 40, 40); position(simple, 976, buttonTop, 40, 40)
        stop.setImageResource(if (enabled) R.drawable.czur_overlay_simple_stop else R.drawable.czur_overlay_stop)
        collapse.setImageResource(if (enabled) R.drawable.czur_overlay_simple_swap else R.drawable.czur_overlay_collapse)
        size.setImageResource(if (enabled) R.drawable.czur_overlay_simple_large else R.drawable.czur_overlay_large)
        simple.setImageResource(if (enabled) R.drawable.czur_overlay_simple_exit else R.drawable.czur_overlay_simple)
        simple.contentDescription = root.context.getString(if (enabled) R.string.czur_overlay_simple_exit else R.string.czur_overlay_simple)
    }
    private fun label(context: Context, size: Int, tag: String) = TextView(context).apply {
        this.tag = tag; includeFontPadding = false; setTextColor(WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale); gravity = Gravity.CENTER; setSingleLine(true)
    }
    private fun caption(context: Context, tag: String, color: Int, lines: Int): TextView =
        SubtitleViewFactory.create(context, 24 * scale / context.resources.displayMetrics.scaledDensity, lines, false).apply {
            this.tag = tag; setTextColor(color); includeFontPadding = false
            SubtitleViewFactory.setLineHeightPx(this, px(36)); gravity = Gravity.TOP or Gravity.START
        }
    private fun column(context: Context, source: TextView, translated: TextView) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(source, LinearLayout.LayoutParams(-1, -2)); addView(translated, LinearLayout.LayoutParams(-1, -2))
    }
    private fun icon(context: Context, drawable: Int, description: Int, tag: String, action: Runnable?) = ImageView(context).apply {
        this.tag = tag; scaleType = ImageView.ScaleType.FIT_XY; setImageResource(drawable)
        contentDescription = context.getString(description)
        if (action == null) visibility = View.GONE else setOnClickListener { Log.d(TAG, "窗口操作 $tag"); action.run() }
    }
    private fun history(context: Context, tag: String) = LinearLayout(context).apply { this.tag = tag; orientation = LinearLayout.VERTICAL }
    private fun scroll(context: Context, history: LinearLayout, left: Boolean) = ScrollView(context).apply {
        isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false
        addView(history, FrameLayout.LayoutParams(-1, -2))
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
                if (left) lastLeftScroll = SystemClock.uptimeMillis() else lastRightScroll = SystemClock.uptimeMillis()
            }
            false
        }
    }
    private fun put(view: View, x: Int, y: Int, width: Int, height: Int) { root.addView(view); position(view, x, y, width, height) }
    private fun position(view: View, x: Int, y: Int, width: Int, height: Int) {
        view.layoutParams = FrameLayout.LayoutParams(px(width), px(height)).apply { leftMargin = px(x); topMargin = px(y); gravity = Gravity.TOP or Gravity.LEFT }
    }
    private fun px(value: Int) = Math.round(value * scale)
    private class MiniImage(context: Context) : ImageView(context) {
        private val animation = APNGDrawable(ResourceStreamLoader(context, R.raw.czur_overlay_mini))
        init { scaleType = ScaleType.FIT_XY; setImageDrawable(animation) }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); animation.start() }
        override fun onDetachedFromWindow() { animation.stop(); super.onDetachedFromWindow() }
    }
    private class RecordingImage(context: Context) : ImageView(context) {
        init {
            scaleType = ScaleType.FIT_XY
            if (Build.VERSION.SDK_INT >= 28) {
                try { setImageDrawable(ImageDecoder.decodeDrawable(ImageDecoder.createSource(resources, R.raw.czur_overlay_recording))) }
                catch (failed: IOException) { Log.w(TAG, "记录中动画解码失败，使用静态素材", failed); setImageResource(R.raw.czur_overlay_recording) }
            } else setImageResource(R.raw.czur_overlay_recording)
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); if (Build.VERSION.SDK_INT >= 28) (drawable as? AnimatedImageDrawable)?.start() }
        override fun onDetachedFromWindow() { if (Build.VERSION.SDK_INT >= 28) (drawable as? AnimatedImageDrawable)?.stop(); super.onDetachedFromWindow() }
    }
    companion object {
        private const val TAG = "AiMeetingReferenceUi"
        private const val WHITE = Color.WHITE
        private val YELLOW = 0xffffdb10.toInt()
        @JvmStatic fun designScale(context: Context): Float {
            val metrics = context.resources.displayMetrics
            return minOf(metrics.widthPixels / 1920f, metrics.heightPixels / 1080f)
        }
    }
}
