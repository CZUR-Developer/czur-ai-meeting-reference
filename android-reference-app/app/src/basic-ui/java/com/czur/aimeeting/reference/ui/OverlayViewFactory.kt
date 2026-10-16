package com.czur.aimeeting.reference.ui

import android.content.Context
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
        override fun show(initial: MeetingDisplaySnapshot): Boolean {
            if (showing) return true
            if (!Settings.canDrawOverlays(context)) return false
            val created = CzurOverlayViews(context, if (initial.bilingual()) "translation-normal" else "subtitle",
                CzurOverlayViews.Actions(open, swap, stop, Runnable { dismiss() }, null, null, null, null))
            views = created
            val scale = CzurOverlayViews.designScale(context)
            val width = Math.round(1800 * scale)
            val height = Math.round((if (initial.bilingual()) 293 else 222) * scale)
            val metrics = context.resources.displayMetrics
            val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            params.gravity = Gravity.TOP or Gravity.LEFT
            params.x = (metrics.widthPixels - width) / 2
            params.y = metrics.heightPixels - height - Math.round(10 * scale)
            manager.addView(created.view(), params)
            showing = true
            created.render(initial, "", false, 0, false)
            return true
        }
        override fun isShowing() = showing
        override fun windowToken(): IBinder? = if (showing) views?.view()?.windowToken else null
        override fun render(display: MeetingDisplaySnapshot, mode: MeetingSetup.Mode, summaryEnabled: Boolean,
            timeText: String, canStop: Boolean, audioLevel: Int) {
            views?.render(display, timeText, canStop, audioLevel, false)
        }
        override fun dismiss() {
            if (!showing) return
            manager.removeViewImmediate(views!!.view())
            views = null
            showing = false
        }
    }
}
