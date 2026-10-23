package com.czur.aimeeting.reference.ui

import android.content.Context
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.reference.meeting.MeetingSetup

/**
 * 参考 App 的基础窗口工厂：复用同一套 CzurOverlayViews 和已路由快照。
 *
 * 字幕只提供普通和 Mini；互译提供普通、大窗和 Mini。形态切换只替换 View 和
 * WindowManager 尺寸，不重启会议、音源或计时；窗口位置按形态分别记忆。
 */
class OverlayViewFactory : MeetingOverlayFactory {
    override fun create(context: Context, onOpenApp: Runnable, onSwapColumns: Runnable, onStop: Runnable): MeetingOverlayFactory.Window =
        BasicWindow(context, onOpenApp, onSwapColumns, onStop)

    private class BasicWindow(private val context: Context, private val open: Runnable,
        private val swap: Runnable, private val stop: Runnable) : MeetingOverlayFactory.Window {
        private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private var views: CzurOverlayViews? = null
        private var showing = false
        private var params: WindowManager.LayoutParams? = null
        private var display: MeetingDisplaySnapshot? = null
        private var timeText = ""
        private var canStop = false
        private var audioLevel = 0
        private var fullForm = "subtitle"
        private var mini = false
        private val placement = OverlayWindowPlacement()
        private var screenWidth = 0
        private var screenHeight = 0
        private val configurationCallbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                val metrics = context.resources.displayMetrics
                if (!showing || (screenWidth == metrics.widthPixels && screenHeight == metrics.heightPixels)) return
                // 只重建当前形态的 View，不增加第二套四形态管理器或重启会议。
                reattach()
            }
            override fun onLowMemory() = Unit
        }
        override fun show(initial: MeetingDisplaySnapshot): Boolean {
            if (showing) return true
            if (!Settings.canDrawOverlays(context)) return false
            display = initial
            fullForm = if (initial.bilingual()) "translation-normal" else "subtitle"
            mini = false
            attachLatest()
            showing = true
            context.registerComponentCallbacks(configurationCallbacks)
            return true
        }
        private fun form(): String = if (mini) "mini" else fullForm

        private fun formSize(form: String): Pair<Int, Int> {
            val scale = CzurOverlayViews.designScale(context)
            return when (form) {
                "mini" -> Math.round(126 * scale) to Math.round(60 * scale)
                "translation-large" -> Math.round(1800 * scale) to Math.round(1008 * scale)
                "translation-normal" -> Math.round(1800 * scale) to Math.round(293 * scale)
                else -> Math.round(1800 * scale) to Math.round(222 * scale)
            }
        }

        private fun attachLatest() {
            val latest = display!!
            val currentForm = form()
            val created = CzurOverlayViews(context, currentForm, CzurOverlayViews.Actions(
                open, swap, stop,
                Runnable { setMini(true) },
                Runnable { setTranslationLarge(true) },
                Runnable { setTranslationLarge(false) },
                Runnable { setMini(false) },
                null))
            views = created
            val scale = CzurOverlayViews.designScale(context)
            val (width, height) = formSize(currentForm)
            val metrics = context.resources.displayMetrics
            screenWidth = metrics.widthPixels; screenHeight = metrics.heightPixels
            val position = placement.resolve(currentForm, width, height, screenWidth, screenHeight,
                (screenWidth - width) / 2, screenHeight - height - Math.round(10 * scale))
            val layout = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            layout.gravity = Gravity.TOP or Gravity.LEFT
            layout.x = position.x; layout.y = position.y
            params = layout
            created.enableDragging { dx, dy ->
                val current = params!!
                val moved = placement.move(currentForm, current.x + dx, current.y + dy, current.width, current.height,
                    screenWidth, screenHeight)
                current.x = moved.x; current.y = moved.y
                manager.updateViewLayout(created.view(), current)
            }
            manager.addView(created.view(), layout)
            created.render(latest, timeText, canStop, audioLevel, false)
        }

        /** 形态切换时只替换同一窗口的内容和尺寸，会议 ID、音源和计时都在上层保持。 */
        private fun reattach() {
            if (!showing) return
            views?.let { manager.removeViewImmediate(it.view()) }
            views = null
            attachLatest()
        }

        private fun setMini(value: Boolean) {
            if (mini == value) return
            mini = value
            reattach()
        }

        private fun setTranslationLarge(value: Boolean) {
            if (display?.bilingual() != true) return
            val target = if (value) "translation-large" else "translation-normal"
            if (fullForm == target && !mini) return
            fullForm = target
            mini = false
            reattach()
        }

        override fun isShowing() = showing
        override fun windowToken(): IBinder? = if (showing) views?.view()?.windowToken else null
        override fun controlBounds() = overlayControlBounds(views?.view())
        override fun render(display: MeetingDisplaySnapshot, mode: MeetingSetup.Mode, summaryEnabled: Boolean,
            timeText: String, canStop: Boolean, audioLevel: Int) {
            this.display = display; this.timeText = timeText; this.canStop = canStop; this.audioLevel = audioLevel
            val expected = if (display.bilingual()) {
                if (fullForm == "translation-large") "translation-large" else "translation-normal"
            } else "subtitle"
            if (!mini && fullForm != expected) {
                fullForm = expected
                reattach()
                return
            }
            views?.render(display, timeText, canStop, audioLevel, false)
        }
        override fun dismiss() {
            if (!showing) return
            context.unregisterComponentCallbacks(configurationCallbacks)
            views?.let { manager.removeViewImmediate(it.view()) }
            views = null
            params = null
            showing = false
        }
    }
}
