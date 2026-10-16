package com.czur.aimeeting.reference

import android.graphics.Bitmap
import android.os.IBinder

/**
 * App 对闭源截图 SDK 的薄端口。主线程创建、调用与回调；JPEG 后处理交 App 原工作队列。
 * 成功交付的 Bitmap 归 App，编码后回收；这里不复制系统采集引擎。
 */
interface ScreenCaptureSource : AutoCloseable {
    fun mode(): String
    fun supportsWindowExclusion(): Boolean
    interface Callback {
        fun onSuccess(bitmap: Bitmap)
        fun onError(message: String)
    }
    fun captureBitmap(excludedWindowTokens: Array<IBinder?>, callback: Callback)
    override fun close()
}
