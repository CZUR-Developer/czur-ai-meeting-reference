package com.czur.aimeeting.reference

import android.os.IBinder

/**
 * 单场截图的 App 编排：只保留会议相对时间、窗口排除以及周期帧／末帧的先后顺序。
 * 图像处理全部由 SDK 完成，字节原样提交。主线程发起并接收结果；上一帧业务回执结束前，
 * 不开始下一帧，忙时跳过周期请求。Stop 空闲时采末帧，否则只等待已接纳帧，
 * 避免在保存 ACK 之后才截取 Stop 后的画面。周期和停止规则仍归 Service。
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
    private var current: Request? = null
    private var closed = false
    private var stopping = false
    private var stopCompleted: Runnable? = null

    fun mode() = source.mode()
    fun supportsWindowExclusion() = source.supportsWindowExclusion()

    fun capture(nowMs: Long, excluded: Array<IBinder?>, callback: Callback) {
        if (closed) { callback.onError("本场截图已关闭"); return }
        if (stopping || current != null) { callback.onSuccess(null); return }
        start(Request(nowMs, excluded.copyOf(), callback))
    }

    /**
     * 停止边界只接受一次：空闲时立即采图；忙时不增加末帧，也不启动迟到截图。
     * 已交付帧的真实业务 ACK／失败释放 Frame 后才续接；关闭或撤销同样只续接一次。
     */
    fun stop(nowMs: Long, excluded: Array<IBinder?>, callback: Callback, completed: Runnable) {
        if (stopping) return
        stopping = true
        stopCompleted = completed
        if (closed) { completeStop(); return }
        if (current == null) start(Request(nowMs, excluded.copyOf(), callback))
    }

    private fun start(request: Request) {
        current = request
        try {
            source.capture(request.excluded, object : ScreenCaptureSource.Callback {
                override fun onSuccess(image: ScreenCaptureSource.Image?) {
                    if (closed || current !== request || request.delivered) return
                    if (image == null) {
                        request.delivered = true
                        try { request.callback.onSuccess(null) } finally { finish(request) }
                    } else {
                        request.delivered = true
                        request.callback.onSuccess(Frame(maxOf(0L, request.nowMs - meetingStartedAtMs), image) {
                            finish(request)
                        })
                    }
                }
                override fun onError(message: String) {
                    if (closed || current !== request || request.delivered) return
                    request.delivered = true
                    try { request.callback.onError(message) } finally { finish(request) }
                }
            })
        } catch (failure: RuntimeException) {
            if (current !== request) throw failure
            request.delivered = true
            try { request.callback.onError("SDK截图请求无法执行") } finally { finish(request) }
        }
    }

    private fun finish(request: Request) {
        if (current !== request) return
        current = null
        completeStop()
    }

    private fun completeStop() {
        val completed = stopCompleted ?: return
        stopCompleted = null
        completed.run()
    }

    /** 正常末帧确认后关闭；授权撤销也释放等待中的请求，迟到输出不会重复续接停止。 */
    override fun close() {
        if (closed) return
        closed = true
        val waiting = current?.takeUnless { it.delivered }
        current = null
        // 先结束 SDK 会话和授权，再通知末帧失败，保持回页／停止的原顺序。
        try { source.close() } finally {
            try { waiting?.callback?.onError("本场截图已关闭") } finally { completeStop() }
        }
    }
}
