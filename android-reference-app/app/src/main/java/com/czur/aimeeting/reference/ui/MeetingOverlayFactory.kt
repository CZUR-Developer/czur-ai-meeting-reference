package com.czur.aimeeting.reference.ui

import android.content.Context
import android.graphics.Rect
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot
import com.czur.aimeeting.reference.meeting.MeetingSetup

/**
 * 构建期窗口装配点，只创建显示宿主；会议、音源、模型和收口由原 Service 持有。
 * 主线程创建和操作，传入 Service Context，工厂不保留 Activity，也不申请权限。
 */
fun interface MeetingOverlayFactory {
    fun create(context: Context, onOpenApp: Runnable, onSwapColumns: Runnable, onStop: Runnable): Window

    interface Window {
        fun show(initial: MeetingDisplaySnapshot): Boolean
        fun isShowing(): Boolean
        /** 当前挂载 View 的公开 token；宿主每次截图现查，不缓存跨挂载句柄。 */
        fun windowToken(): IBinder?
        /** 当前可见按钮的屏幕矩形，供 Debug 自动化读取；自定义窗口可不提供。 */
        fun controlBounds(): Map<String, Rect> = emptyMap()
        fun render(display: MeetingDisplaySnapshot, mode: MeetingSetup.Mode, summaryEnabled: Boolean,
            timeText: String, canStop: Boolean, audioLevel: Int)
        /** 只释放本窗口，不代表停止会议，可重复调用。 */
        fun dismiss()
    }
}

/** 只读当前已挂载 View，不缓存坐标，不修改窗口焦点或会议状态。 */
internal fun overlayControlBounds(root: View?): Map<String, Rect> {
    val controls = linkedMapOf<String, Rect>()
    fun visit(view: View) {
        if (!view.isShown || !view.isAttachedToWindow) return
        val description = view.contentDescription?.toString()
        if (view.isClickable && view.isEnabled && view.width > 0 && view.height > 0 && !description.isNullOrEmpty()) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            controls[description] = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
    }
    root?.let(::visit)
    return controls
}
