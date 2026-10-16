package com.czur.aimeeting.reference.ui

import android.content.Context
import android.os.IBinder
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
        fun render(display: MeetingDisplaySnapshot, mode: MeetingSetup.Mode, summaryEnabled: Boolean,
            timeText: String, canStop: Boolean, audioLevel: Int)
        /** 只释放本窗口，不代表停止会议，可重复调用。 */
        fun dismiss()
    }
}
