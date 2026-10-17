package com.czur.aimeeting.reference

import android.os.IBinder
import java.util.ArrayDeque

/**
 * 单场截图的 App 编排：只保留会议相对时间、窗口排除以及周期帧／末帧的先后顺序。
 * 图像处理全部由 SDK 完成，字节原样提交。主线程发起并接收结果；上一帧业务回执结束前，
 * 不开始下一帧，避免末帧越过周期帧的接受确认。周期和停止规则仍归 Service。
 */
class PresentationCapture(
    private val source: ScreenCaptureSource, private val meetingStartedAtMs: Long
) : AutoCloseable {
    /**
     * 一次交付及其本场编排生命周期。只有业务 ACK 后调用 confirmAccepted；成功、失败均需
     * close 释放本次请求，之后才允许下一帧。close 不代表业务接受，不执行图片处理或上传。
     */
    class Frame internal constructor(
        private val offsetMs: Long, private val image: ScreenCaptureSource.Image,
        private val finished: () -> Unit
    ) : AutoCloseable {
        private var closed = false
        fun offsetMs() = offsetMs
        fun jpeg() = image.jpeg
        fun confirmAccepted() = image.confirmAccepted()
        override fun close() {
            if (closed) return
            closed = true
            finished()
        }
    }

    interface Callback {
        /** null 是 SDK 的正常无新图结果，不应变成失败提示或阻塞停止。 */
        fun onSuccess(frame: Frame?)
        fun onError(message: String)
    }

    private class Request(val nowMs: Long, val excluded: Array<IBinder?>, val callback: Callback) {
        var delivered = false
    }
    private val queued = ArrayDeque<Request>()
    private var current: Request? = null
    private var closed = false

    fun mode() = source.mode()
    fun supportsWindowExclusion() = source.supportsWindowExclusion()

    fun capture(nowMs: Long, excluded: Array<IBinder?>, callback: Callback) {
        if (closed) { callback.onError("本场截图已关闭"); return }
        queued.add(Request(nowMs, excluded.copyOf(), callback))
        startNext()
    }

    private fun startNext() {
        if (closed || current != null || queued.isEmpty()) return
        val request = queued.remove()
        current = request
        try {
            source.capture(request.excluded, object : ScreenCaptureSource.Callback {
                override fun onSuccess(image: ScreenCaptureSource.Image?) {
                    if (closed || current !== request) return
                    if (image == null) {
                        finish(request)
                        request.callback.onSuccess(null)
                    } else {
                        request.delivered = true
                        request.callback.onSuccess(Frame(maxOf(0L, request.nowMs - meetingStartedAtMs), image) {
                            finish(request)
                        })
                    }
                }
                override fun onError(message: String) {
                    if (closed || current !== request) return
                    finish(request)
                    request.callback.onError(message)
                }
            })
        } catch (failure: RuntimeException) {
            if (current !== request) throw failure
            finish(request)
            request.callback.onError("SDK截图请求无法执行")
        }
    }

    private fun finish(request: Request) {
        if (current !== request) return
        current = null
        startNext()
    }

    /** 正常末帧确认后关闭；授权撤销也释放等待中的请求，迟到输出不会重复续接停止。 */
    override fun close() {
        if (closed) return
        closed = true
        val waiting = ArrayList<Request>()
        current?.takeUnless { it.delivered }?.let(waiting::add)
        waiting.addAll(queued)
        current = null
        queued.clear()
        // 先结束 SDK 会话和授权，再通知末帧失败，保持回页／停止的原顺序。
        source.close()
        for (request in waiting) request.callback.onError("本场截图已关闭")
    }
}
