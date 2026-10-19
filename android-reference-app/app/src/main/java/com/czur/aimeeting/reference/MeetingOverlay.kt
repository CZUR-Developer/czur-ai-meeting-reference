package com.czur.aimeeting.reference

import android.content.Context
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import com.czur.aimeeting.reference.meeting.MeetingReminderPresentation
import com.czur.aimeeting.reference.meeting.MeetingSetup
import com.czur.aimeeting.reference.ui.MeetingOverlayFactory

/**
 * App 薄窗口宿主，主线程读取 Service 同一快照。权限、通知、提醒与屏采仍归 App；
 * 隐藏或释放只影响显示，不停止会议，不改变音源。
 */
class MeetingOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var window: MeetingOverlayFactory.Window? = null
    private var reminderScrim: View? = null
    private var reminderPopup: MeetingReminderPopup? = null
    private var onConfirmReminder: Runnable? = null
    fun isShowing() = window?.isShowing() == true
    fun controlBounds() = window?.controlBounds().orEmpty()

    /**
     * 每次采集现取本应用全部浮窗。已挂载但尚无 token 的 null 必须保留，交 SDK 明确报错，
     * 避免静默漏排窗口；跨挂载不缓存句柄。
     */
    fun captureWindowTokens(): Array<IBinder?> {
        val tokens = mutableListOf<IBinder?>()
        if (isShowing()) tokens += window?.windowToken()
        reminderScrim?.takeIf { it.parent != null }?.let { tokens += it.windowToken }
        reminderPopup?.view()?.takeIf { it.parent != null }?.let { tokens += it.windowToken }
        return tokens.toTypedArray()
    }
    fun show(state: MeetingDisplaySnapshot, onOpenApp: Runnable, onSwapColumns: Runnable,
        onConfirmReminder: Runnable, onStop: Runnable) {
        if (isShowing()) return
        this.onConfirmReminder = onConfirmReminder
        val created = MeetingAppAssembly.overlayFactory().create(context, onOpenApp, onSwapColumns, onStop)
        window = created
        if (!created.show(state)) { created.dismiss(); window = null; Log.w(TAG, "跨应用窗口需要显示权限") }
    }
    fun render(state: MeetingDisplaySnapshot, mode: MeetingSetup.Mode, timeText: String,
        reminder: MeetingReminderPresentation.Notice, canStop: Boolean, allowOverlayReminder: Boolean) =
        render(state, mode, mode == MeetingSetup.Mode.MEETING_MINUTES, timeText, reminder, canStop, allowOverlayReminder)
    @JvmOverloads fun render(state: MeetingDisplaySnapshot, mode: MeetingSetup.Mode, summaryEnabled: Boolean,
        timeText: String, reminder: MeetingReminderPresentation.Notice, canStop: Boolean,
        allowOverlayReminder: Boolean, audioLevel: Int = 0) {
        val current = window ?: return
        current.render(state, mode, summaryEnabled, timeText, canStop, audioLevel)
        renderReminder(reminder, allowOverlayReminder && current.isShowing())
    }
    private fun renderReminder(notice: MeetingReminderPresentation.Notice, allowed: Boolean) {
        if (!allowed || notice.kind() == MeetingReminderPresentation.Kind.NONE) { clearReminder(); return }
        val existing = reminderPopup
        if (existing != null) { existing.render(notice); return }
        try {
            val scrim = View(context).apply { setBackgroundColor(0x80000000.toInt()) }
            reminderScrim = scrim
            windowManager.addView(scrim, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT))
            val popup = MeetingReminderPopup(context, true, onConfirmReminder!!)
            reminderPopup = popup
            val params = WindowManager.LayoutParams(ReferenceUiScale.px(context, 500), ReferenceUiScale.px(context, 360),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            params.gravity = Gravity.CENTER
            popup.render(notice)
            windowManager.addView(popup.view(), params)
        } catch (unavailable: RuntimeException) {
            clearReminder()
            Log.w(TAG, "跨应用提醒窗不可用 reason=${unavailable.javaClass.simpleName}")
        }
    }
    private fun clearReminder() {
        reminderPopup?.view()?.let { if (it.parent != null) windowManager.removeView(it) }
        reminderPopup = null
        reminderScrim?.let { if (it.parent != null) windowManager.removeView(it) }
        reminderScrim = null
    }
    fun dismiss() { clearReminder(); window?.dismiss(); window = null; onConfirmReminder = null }
    companion object { private const val TAG = "AiMeetingReference" }
}
