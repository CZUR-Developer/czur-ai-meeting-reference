package com.czur.aimeeting.reference.meeting

import android.os.Handler
import com.czur.aimeeting.client.MeetingHeartbeatTimer

/**
 * 用前台服务已有的主线程 Handler 串接心跳触发。
 *
 * <p>定时任务只调用会议会话的 heartbeat，SDK 用它独立的单线程执行器发送，
 * 与字幕／翻译的 IO 队列分开；主线程不承担网络请求。取消时移除尚未执行的回调，
 * 停止后不会补发。</p>
 */
class MainThreadHeartbeatScheduler(private val handler: Handler) : MeetingHeartbeatTimer.Scheduler {
    override fun scheduleAtFixedRate(
        initialDelayMs: Long,
        intervalMs: Long,
        task: Runnable,
    ): MeetingHeartbeatTimer.Cancellation {
        val ticker = object : Runnable {
            override fun run() {
                task.run()
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.postDelayed(ticker, initialDelayMs)
        return MeetingHeartbeatTimer.Cancellation { handler.removeCallbacks(ticker) }
    }
}
