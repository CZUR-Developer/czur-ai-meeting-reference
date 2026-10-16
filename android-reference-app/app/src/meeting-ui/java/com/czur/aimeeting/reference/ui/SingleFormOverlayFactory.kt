package com.czur.aimeeting.reference.ui

import android.content.Context
import android.os.IBinder
import android.view.Gravity
import android.view.View
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.reference.meeting.MeetingSetup
import com.czur.aimeeting.ui.OverlayController
import com.czur.aimeeting.ui.OverlayPresentation

/** 伙伴单形态替换例，仅登记 partner.single，不要求实现四形态或新的会议逻辑。 */
class SingleFormOverlayFactory : MeetingOverlayFactory {
    override fun create(context: Context, onOpenApp: Runnable, onSwapColumns: Runnable, onStop: Runnable): MeetingOverlayFactory.Window =
        SingleWindow(context, onOpenApp, onSwapColumns, onStop)

    class SingleWindow(context: Context, open: Runnable, swap: Runnable, stop: Runnable) : MeetingOverlayFactory.Window {
        @JvmField val controller = OverlayController(context)
        private var display: MeetingDisplaySnapshot? = null
        private var timeText = ""
        private var canStop = false
        private var audioLevel = 0
        init {
            controller.registerForm(FORM_ID, { owner, _ ->
                val views = CzurOverlayViews(owner, if (display!!.bilingual()) "translation-normal" else "subtitle",
                    CzurOverlayViews.Actions(open, swap, stop, null, null, null, null, null))
                object : OverlayController.Content {
                    override fun view(): View = views.view()
                    override fun render(ignored: OverlayPresentation) { views.render(display!!, timeText, canStop, audioLevel, false) }
                }
            }, {
                val scale = OverlayViewFactory.scale(context)
                val metrics = context.resources.displayMetrics
                val width = OverlayViewFactory.px(1800, scale)
                val height = OverlayViewFactory.px(293, scale)
                OverlayController.WindowSpec(width, height, (metrics.widthPixels - width) / 2,
                    metrics.heightPixels - height - OverlayViewFactory.px(10, scale), Gravity.TOP or Gravity.LEFT)
            })
        }
        override fun show(initial: MeetingDisplaySnapshot): Boolean {
            if (display == null) display = initial
            return controller.show(FORM_ID) == OverlayController.DisplayCondition.READY
        }
        override fun isShowing() = controller.isShowing()
        override fun windowToken(): IBinder? = if (isShowing()) controller.contentView()?.windowToken else null
        override fun render(display: MeetingDisplaySnapshot, mode: MeetingSetup.Mode, summaryEnabled: Boolean,
            timeText: String, canStop: Boolean, audioLevel: Int) {
            this.display = display; this.timeText = timeText; this.canStop = canStop; this.audioLevel = audioLevel
            controller.render(OverlayPresentation.empty())
        }
        override fun dismiss() = controller.release()
    }
    companion object { const val FORM_ID = "partner.single" }
}
