package com.czur.aimeeting.sdk

import java.util.ArrayDeque

/**
 * 单场有序回调队列，不新建线程。状态锁内 enqueue、锁外 drain，已接纳字幕先于结束通知。
 * 执行回调时不持有队列或会话锁，避免回调停止音频时与采集线程 join 形成等待环。
 */
internal class SessionCallbackQueue {
    private val pending = ArrayDeque<Runnable>()
    private var draining = false
    private var cancelled = false

    @Synchronized
    fun enqueue(callback: Runnable) { if (!cancelled) pending.addLast(callback) }

    /** 一个线程取得派发权；其他线程只入队，不等待正在执行的 App 回调。 */
    fun drain() {
        synchronized(this) {
            if (draining) return
            draining = true
        }
        var firstFailure: RuntimeException? = null
        while (true) {
            val callback = synchronized(this) {
                val next = pending.pollFirst()
                if (next == null) draining = false
                next
            } ?: break
            try {
                callback.run()
            } catch (failure: RuntimeException) {
                // 完成本轮已接纳回调并释放派发权，再交回第一个 App 异常。
                if (firstFailure == null) firstFailure = failure
            } catch (failure: Error) {
                synchronized(this) { draining = false }
                throw failure
            }
        }
        firstFailure?.let { throw it }
    }

    /** 主动释放时只丢弃尚未派发的通知，已进入 App 的回调允许正常返回。 */
    @Synchronized
    fun cancelPending() {
        cancelled = true
        pending.clear()
    }
}
