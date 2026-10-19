package com.czur.aimeeting.reference.ui

import android.content.Context
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.Color
import android.os.IBinder
import android.util.Log
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

    class DefaultWindow(private val context: Context, private val onOpenApp: Runnable,
        private val onSwapColumns: Runnable, private val onStop: Runnable) : MeetingOverlayFactory.Window {
        @JvmField val overlay: DefaultMeetingOverlay
        private var display: MeetingDisplaySnapshot? = null
        private var timeText = ""
        private var canStop = false
        private var simpleMode = false
        private var audioLevel = 0
        private val placement = OverlayWindowPlacement()
        private var screenWidth = context.resources.displayMetrics.widthPixels
        private var screenHeight = context.resources.displayMetrics.heightPixels
        private var watchingConfiguration = false
        private val configurationCallbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = rearrangeForCurrentScreen()
            override fun onLowMemory() = Unit
        }
        init {
            overlay = DefaultMeetingOverlay(context, OverlayController.LayoutPolicy(::currentLayout), style(context), object : DefaultMeetingOverlay.Callbacks {
                override fun onOpenApp() = onOpenApp.run()
                override fun onSwapColumns() = onSwapColumns.run()
                override fun onStop() = onStop.run()
            })
            registerForms()
        }
        private fun registerForms() {
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
                    views.enableDragging { dx, dy -> moveWindow(formId, dx, dy) }
                    object : OverlayController.Content {
                        override fun view(): View = views.view()
                        override fun render(ignored: OverlayPresentation) {
                            display?.let { views.render(it, timeText, canStop, audioLevel, simpleMode) }
                        }
                    }
                }, OverlayController.LayoutPolicy(::currentLayout))
            }
        }
        private fun currentLayout(form: String): OverlayController.WindowSpec {
            val defaults = layout(context, form, simpleMode)
            if (form == DefaultMeetingOverlay.MINI) return defaults
            val metrics = context.resources.displayMetrics
            val position = placement.resolve(form, defaults.widthPx, defaults.heightPx,
                metrics.widthPixels, metrics.heightPixels, defaults.xPx, defaults.yPx)
            return OverlayController.WindowSpec(defaults.widthPx, defaults.heightPx, position.x, position.y, defaults.gravity)
        }
        private fun moveWindow(form: String, dx: Int, dy: Int) {
            val controller = overlay.controller()
            val current = controller.windowSpec() ?: return
            val metrics = context.resources.displayMetrics
            val position = placement.move(form, current.xPx + dx, current.yPx + dy,
                current.widthPx, current.heightPx, metrics.widthPixels, metrics.heightPixels)
            controller.updateLayout(OverlayController.WindowSpec(current.widthPx, current.heightPx,
                position.x, position.y, current.gravity))
        }
        /**
         * 系统已更新 Resources 后重建 View，让构造时固定的设计缩放重新生效。
         * 不替换 DefaultMeetingOverlay，因此 Mini 的恢复形态、简洁开关和同场快照均保留；
         * 隐藏时只更新工厂，不能因分辨率变化把已退窗的会议重新挂出。
         */
        private fun rearrangeForCurrentScreen() {
            val controller = overlay.controller()
            if (controller.state() == OverlayController.State.RELEASED) return
            val metrics = context.resources.displayMetrics
            if (screenWidth == metrics.widthPixels && screenHeight == metrics.heightPixels) return
            screenWidth = metrics.widthPixels; screenHeight = metrics.heightPixels
            val currentForm = controller.currentFormId()
            registerForms()
            if (controller.isShowing() && currentForm != null) {
                controller.show(currentForm)
                renderLatest()
            }
            Log.d(TAG, "参考窗口按当前屏幕重排：${screenWidth}x${screenHeight}，形态=$currentForm")
        }
        override fun show(initial: MeetingDisplaySnapshot): Boolean {
            display = initial
            val ready = overlay.show(initial.bilingual()) == OverlayController.DisplayCondition.READY
            if (ready && !watchingConfiguration) {
                context.registerComponentCallbacks(configurationCallbacks)
                watchingConfiguration = true
            }
            return ready
        }
        override fun isShowing() = overlay.controller().isShowing()
        override fun windowToken(): IBinder? = if (isShowing()) overlay.controller().contentView()?.windowToken else null
        override fun controlBounds() = overlayControlBounds(overlay.controller().contentView())
        override fun render(display: MeetingDisplaySnapshot, mode: MeetingSetup.Mode, summaryEnabled: Boolean,
            timeText: String, canStop: Boolean, audioLevel: Int) {
            this.display = display; this.timeText = timeText; this.canStop = canStop; this.audioLevel = audioLevel
            renderLatest()
        }
        private fun renderLatest() = overlay.render(OverlayPresentation.empty())
        override fun dismiss() {
            if (watchingConfiguration) {
                context.unregisterComponentCallbacks(configurationCallbacks)
                watchingConfiguration = false
            }
            overlay.release()
        }
    }
    companion object {
        private const val TAG = "AiMeetingReferenceUi"
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
