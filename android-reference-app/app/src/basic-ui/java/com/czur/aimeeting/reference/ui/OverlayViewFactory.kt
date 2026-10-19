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

/** 无 UI AAR 的基础窗口，复用同一资源和 SDK 已路由快照，不复制四形态生命周期。 */
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
        private val placement = OverlayWindowPlacement()
        private var screenWidth = 0
        private var screenHeight = 0
        private val configurationCallbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                val metrics = context.resources.displayMetrics
                if (!showing || (screenWidth == metrics.widthPixels && screenHeight == metrics.heightPixels)) return
                // 只重建同一个基础形态的 View，不增加第二套四形态管理器或重启会议。
                manager.removeViewImmediate(views!!.view())
                attachLatest()
            }
            override fun onLowMemory() = Unit
        }
        override fun show(initial: MeetingDisplaySnapshot): Boolean {
            if (showing) return true
            if (!Settings.canDrawOverlays(context)) return false
            display = initial
            attachLatest()
            showing = true
            context.registerComponentCallbacks(configurationCallbacks)
            return true
        }
        private fun attachLatest() {
            val latest = display!!
            val form = if (latest.bilingual()) "translation-normal" else "subtitle"
            val created = CzurOverlayViews(context, form,
                CzurOverlayViews.Actions(open, swap, stop, Runnable { dismiss() }, null, null, null, null))
            views = created
            val scale = CzurOverlayViews.designScale(context)
            val width = Math.round(1800 * scale)
            val height = Math.round((if (latest.bilingual()) 293 else 222) * scale)
            val metrics = context.resources.displayMetrics
            screenWidth = metrics.widthPixels; screenHeight = metrics.heightPixels
            val position = placement.resolve(form, width, height, screenWidth, screenHeight,
                (screenWidth - width) / 2, screenHeight - height - Math.round(10 * scale))
            val layout = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            layout.gravity = Gravity.TOP or Gravity.LEFT
            layout.x = position.x; layout.y = position.y
            params = layout
            created.enableDragging { dx, dy ->
                val current = params!!
                val moved = placement.move(form, current.x + dx, current.y + dy, current.width, current.height,
                    screenWidth, screenHeight)
                current.x = moved.x; current.y = moved.y
                manager.updateViewLayout(created.view(), current)
            }
            manager.addView(created.view(), layout)
            created.render(latest, timeText, canStop, audioLevel, false)
        }
        override fun isShowing() = showing
        override fun windowToken(): IBinder? = if (showing) views?.view()?.windowToken else null
        override fun controlBounds() = overlayControlBounds(views?.view())
        override fun render(display: MeetingDisplaySnapshot, mode: MeetingSetup.Mode, summaryEnabled: Boolean,
            timeText: String, canStop: Boolean, audioLevel: Int) {
            this.display = display; this.timeText = timeText; this.canStop = canStop; this.audioLevel = audioLevel
            views?.render(display, timeText, canStop, audioLevel, false)
        }
        override fun dismiss() {
            if (!showing) return
            context.unregisterComponentCallbacks(configurationCallbacks)
            manager.removeViewImmediate(views!!.view())
            views = null
            params = null
            showing = false
        }
    }
}
