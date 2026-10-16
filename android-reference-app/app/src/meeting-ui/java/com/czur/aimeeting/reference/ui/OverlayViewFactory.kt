package com.czur.aimeeting.reference.ui

import android.content.Context
import android.graphics.Color
import android.os.IBinder
import android.view.Gravity
import android.view.View
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.reference.meeting.MeetingSetup
import com.czur.aimeeting.ui.DefaultMeetingOverlay
import com.czur.aimeeting.ui.OverlayController
import com.czur.aimeeting.ui.OverlayPresentation

/** 默认 UI AAR 装配，App 持有国内窗口内容，SDK 管理窗口形态与 Mini 恢复。 */
class OverlayViewFactory : MeetingOverlayFactory {
    override fun create(context: Context, onOpenApp: Runnable, onSwapColumns: Runnable, onStop: Runnable): MeetingOverlayFactory.Window =
        DefaultWindow(context, onOpenApp, onSwapColumns, onStop)

    class DefaultWindow(context: Context, onOpenApp: Runnable, onSwapColumns: Runnable, onStop: Runnable) : MeetingOverlayFactory.Window {
        @JvmField val overlay: DefaultMeetingOverlay
        private var display: MeetingDisplaySnapshot? = null
        private var timeText = ""
        private var canStop = false
        private var simpleMode = false
        private var audioLevel = 0
        init {
            val layouts = OverlayController.LayoutPolicy { formId -> layout(context, formId, simpleMode) }
            overlay = DefaultMeetingOverlay(context, layouts, style(context), object : DefaultMeetingOverlay.Callbacks {
                override fun onOpenApp() = onOpenApp.run()
                override fun onSwapColumns() = onSwapColumns.run()
                override fun onStop() = onStop.run()
            })
            for (form in listOf(DefaultMeetingOverlay.SUBTITLE, DefaultMeetingOverlay.TRANSLATION_NORMAL,
                DefaultMeetingOverlay.TRANSLATION_LARGE, DefaultMeetingOverlay.MINI)) {
                overlay.controller().registerForm(form, { host, formId ->
                    val views = CzurOverlayViews(host, formId, CzurOverlayViews.Actions(onOpenApp, onSwapColumns, onStop,
                        Runnable { if (simpleMode) onSwapColumns.run() else overlay.collapse() },
                        Runnable { simpleMode = false; overlay.showLarge() }, Runnable { overlay.showNormal() }, Runnable { overlay.restore() },
                        Runnable {
                            simpleMode = !simpleMode
                            overlay.controller().show(DefaultMeetingOverlay.TRANSLATION_NORMAL)
                            renderLatest()
                        }))
                    object : OverlayController.Content {
                        override fun view(): View = views.view()
                        override fun render(ignored: OverlayPresentation) {
                            display?.let { views.render(it, timeText, canStop, audioLevel, simpleMode) }
                        }
                    }
                }, layouts)
            }
        }
        override fun show(initial: MeetingDisplaySnapshot): Boolean {
            display = initial
            return overlay.show(initial.bilingual()) == OverlayController.DisplayCondition.READY
        }
        override fun isShowing() = overlay.controller().isShowing()
        override fun windowToken(): IBinder? = if (isShowing()) overlay.controller().contentView()?.windowToken else null
        override fun render(display: MeetingDisplaySnapshot, mode: MeetingSetup.Mode, summaryEnabled: Boolean,
            timeText: String, canStop: Boolean, audioLevel: Int) {
            this.display = display; this.timeText = timeText; this.canStop = canStop; this.audioLevel = audioLevel
            renderLatest()
        }
        private fun renderLatest() = overlay.render(OverlayPresentation.empty())
        override fun dismiss() = overlay.release()
    }
    companion object {
        @JvmStatic fun layouts(context: Context) = OverlayController.LayoutPolicy { formId -> layout(context, formId, false) }
        private fun layout(context: Context, formId: String, simpleMode: Boolean): OverlayController.WindowSpec {
            val metrics = context.resources.displayMetrics
            val scale = scale(context)
            val mini = formId == DefaultMeetingOverlay.MINI
            val designHeight = when (formId) {
                DefaultMeetingOverlay.SUBTITLE -> 222
                DefaultMeetingOverlay.TRANSLATION_NORMAL -> if (simpleMode) 164 else 293
                DefaultMeetingOverlay.TRANSLATION_LARGE -> 1008
                DefaultMeetingOverlay.MINI -> 60
                else -> throw IllegalArgumentException("unknown default form")
            }
            val width = px(if (mini) 126 else 1800, scale)
            val height = px(designHeight, scale)
            return OverlayController.WindowSpec(width, height,
                if (mini) metrics.widthPixels - width - px(60, scale) else (metrics.widthPixels - width) / 2,
                if (mini) 0 else metrics.heightPixels - height - px(10, scale), Gravity.TOP or Gravity.LEFT)
        }
        @JvmStatic fun style(context: Context): DefaultMeetingOverlay.Style {
            val scale = scale(context)
            return DefaultMeetingOverlay.Style(26 * scale, px(34, scale), px(12, scale), px(44, scale), Color.WHITE, 0xe6222933.toInt())
        }
        @JvmStatic fun scale(context: Context): Float {
            val metrics = context.resources.displayMetrics
            return minOf(metrics.widthPixels / 1920f, metrics.heightPixels / 1080f)
        }
        @JvmStatic fun px(designValue: Int, scale: Float) = maxOf(1, Math.round(designValue * scale))
    }
}
