package com.czur.aimeeting.reference.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.reference.meeting.MeetingSetup

/**
 * 参考 App 的基础窗口工厂：复用同一套 CzurOverlayViews 和已路由快照。
 *
 * 字幕只提供普通和 Mini；互译提供普通、大窗和 Mini。形态切换只替换 View 和
 * WindowManager 尺寸，不重启会议、音源或计时；窗口位置按形态分别记忆。
 * 收起与展开带过渡：缩小淡出并平移到 Mini 位、从 Mini 位放大淡入，参数与
 * CZUR 原悬浮窗一致（400ms、AccelerateDecelerate）。
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
        private var animator: Animator? = null
        private var transitioning = false
        private var suppressSettle = false
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

        /** 没有拖动记录时的落点：Mini 贴右上角，其余形态居中靠底。 */
        private fun defaultPosition(form: String, width: Int, height: Int): OverlayWindowPlacement.Position {
            val scale = CzurOverlayViews.designScale(context)
            return if (form == "mini") {
                OverlayWindowPlacement.Position(
                    (screenWidth - width - Math.round(MINI_RIGHT_MARGIN * scale)).coerceAtLeast(0),
                    MINI_TOP_MARGIN)
            } else {
                OverlayWindowPlacement.Position((screenWidth - width) / 2,
                    (screenHeight - height - Math.round(10 * scale)).coerceAtLeast(0))
            }
        }

        private fun positionFor(form: String, width: Int, height: Int): OverlayWindowPlacement.Position {
            val fallback = defaultPosition(form, width, height)
            return placement.resolve(form, width, height, screenWidth, screenHeight, fallback.x, fallback.y)
        }

        private fun attachLatest() = attach(alpha = 1f, scale = 1f, pivotX = null, explicit = null)

        private fun attach(alpha: Float, scale: Float, pivotX: Float?,
            explicit: OverlayWindowPlacement.Position?) {
            val latest = display ?: return
            val currentForm = form()
            val created = CzurOverlayViews(context, currentForm, CzurOverlayViews.Actions(
                open, swap, stop,
                Runnable { setMini(true) },
                Runnable { setTranslationLarge(true) },
                Runnable { setTranslationLarge(false) },
                Runnable { setMini(false) },
                null))
            views = created
            val metrics = context.resources.displayMetrics
            screenWidth = metrics.widthPixels; screenHeight = metrics.heightPixels
            val (width, height) = formSize(currentForm)
            val position = explicit ?: positionFor(currentForm, width, height)
            val layout = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            layout.gravity = Gravity.TOP or Gravity.LEFT
            layout.x = position.x; layout.y = position.y
            params = layout
            created.enableDragging { dx, dy ->
                if (transitioning) return@enableDragging
                val current = params!!
                val moved = placement.move(currentForm, current.x + dx, current.y + dy, current.width, current.height,
                    screenWidth, screenHeight)
                current.x = moved.x; current.y = moved.y
                manager.updateViewLayout(created.view(), current)
            }
            val root = created.view()
            if (alpha != 1f || scale != 1f) {
                root.alpha = alpha
                root.scaleX = scale
                root.scaleY = scale
                pivotX?.let { root.pivotX = it }
                root.pivotY = 0f
            }
            manager.addView(root, layout)
            created.render(latest, timeText, canStop, audioLevel, false)
        }

        /** 形态切换时只替换同一窗口的内容和尺寸，会议 ID、音源和计时都在上层保持。 */
        private fun reattach() {
            if (!showing) return
            cancelTransition()
            detachCurrent()
            attachLatest()
        }

        private fun detachCurrent() {
            views?.let { manager.removeViewImmediate(it.view()) }
            views = null
        }

        private fun setMini(value: Boolean) {
            if (mini == value) return
            cancelTransition()
            if (value) collapseToMini() else expandFromMini()
        }

        /** 收起：当前窗口缩小淡出并上移到 Mini 位，结束后换成 Mini 布局。 */
        private fun collapseToMini() {
            val current = views ?: return
            val view = current.view()
            val currentParams = params ?: return
            val (targetWidth, targetHeight) = formSize("mini")
            val target = positionFor("mini", targetWidth, targetHeight)
            val geometry = OverlayFormTransition.collapse(
                currentParams.x, currentParams.width, target.x, targetWidth)
            mini = true
            startTransition(view, currentParams,
                fromY = currentParams.y, toY = target.y,
                fromAlpha = 1f, toAlpha = 0f,
                fromScale = 1f, toScale = geometry.scale, pivotX = geometry.pivotX,
                onSettled = { detachCurrent(); attachLatest() })
        }

        /** 展开：先按目标尺寸铺开（透明），再从 Mini 位放大淡入并下移到目标位。 */
        private fun expandFromMini() {
            val current = views ?: return
            val currentParams = params ?: return
            val miniX = currentParams.x
            val miniY = currentParams.y
            val miniWidth = currentParams.width
            mini = false
            val targetForm = form()
            val (targetWidth, targetHeight) = formSize(targetForm)
            val target = positionFor(targetForm, targetWidth, targetHeight)
            val geometry = OverlayFormTransition.expand(miniX, miniWidth, target.x, targetWidth)
            detachCurrent()
            attach(alpha = 0f, scale = geometry.scale, pivotX = geometry.pivotX,
                explicit = OverlayWindowPlacement.Position(target.x, miniY))
            val view = views ?: return
            val newParams = params ?: return
            startTransition(view.view(), newParams,
                fromY = miniY, toY = target.y,
                fromAlpha = 0f, toAlpha = 1f,
                fromScale = geometry.scale, toScale = 1f, pivotX = geometry.pivotX,
                onSettled = {
                    newParams.x = target.x
                    newParams.y = target.y
                    if (view.view().parent != null) manager.updateViewLayout(view.view(), newParams)
                })
        }

        private fun startTransition(view: View, layout: WindowManager.LayoutParams,
            fromY: Int, toY: Int, fromAlpha: Float, toAlpha: Float,
            fromScale: Float, toScale: Float, pivotX: Float, onSettled: () -> Unit) {
            transitioning = true
            view.pivotX = pivotX
            view.pivotY = 0f
            val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, fromScale, toScale)
            val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, fromScale, toScale)
            val alpha = ObjectAnimator.ofFloat(view, View.ALPHA, fromAlpha, toAlpha)
            val moveY = ValueAnimator.ofInt(fromY, toY).apply {
                addUpdateListener { animation ->
                    layout.y = animation.animatedValue as Int
                    if (view.parent != null) manager.updateViewLayout(view, layout)
                }
            }
            val set = AnimatorSet().apply {
                playTogether(scaleX, scaleY, alpha, moveY)
                duration = OverlayFormTransition.DURATION_MS
                interpolator = AccelerateDecelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // 取消也要复位属性，避免留下半透明或带缩放的窗口。
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.pivotX = layout.width / 2f
                        view.pivotY = layout.height / 2f
                        if (animator === animation) animator = null
                        transitioning = false
                        // 会议已停止时不再重建窗口；停止路径自己会 dismiss。
                        if (showing && !suppressSettle) onSettled()
                    }
                })
            }
            animator = set
            set.start()
        }

        private fun cancelTransition() {
            val running = animator ?: return
            animator = null
            suppressSettle = true
            running.cancel()
            suppressSettle = false
            transitioning = false
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
            // 先清 showing，取消动画时的回调就不会再重建窗口。
            showing = false
            context.unregisterComponentCallbacks(configurationCallbacks)
            cancelTransition()
            detachCurrent()
            params = null
        }

        private fun setTranslationLarge(value: Boolean) {
            if (display?.bilingual() != true) return
            val target = if (value) "translation-large" else "translation-normal"
            if (fullForm == target && !mini) return
            cancelTransition()
            fullForm = target
            mini = false
            reattach()
        }

        companion object {
            /** Mini 与普通窗口的右侧留白一致，视觉上和 1800 宽窗口右边缘对齐。 */
            private const val MINI_RIGHT_MARGIN = 60
            private const val MINI_TOP_MARGIN = 0
        }
    }
}
