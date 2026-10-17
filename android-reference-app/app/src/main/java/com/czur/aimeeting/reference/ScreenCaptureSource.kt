package com.czur.aimeeting.reference

import android.os.IBinder

/**
 * App 对闭源截图 SDK 的薄端口。主线程创建、调用与回调，图像处理和编码全部由 SDK 完成。
 * 本层只搬运 SDK 已交付的 JPEG 字节，不识别算法、判断依据或中间处理步骤。
 */
interface ScreenCaptureSource : AutoCloseable {
    fun mode(): String
    fun supportsWindowExclusion(): Boolean

    /**
     * SDK 已交付图片的原始字节与接受通知。只有业务保存成功的真实回执到达后才确认，
     * 失败或取消不确认；确认本身不发起网络请求，也不改变字节的调用方所有权。
     */
    class Image(val jpeg: ByteArray, private val accepted: () -> Unit) {
        fun confirmAccepted() = accepted()
    }

    interface Callback {
        /** null 表示本次没有新图片，是正常结果；调用方仍须续接末帧或停止。 */
        fun onSuccess(image: Image?)
        fun onError(message: String)
    }

    /** 排除窗口由 App 每次在主线程取最新句柄；文件读取在端口实现的 IO 协程执行。 */
    fun capture(excludedWindowTokens: Array<IBinder?>, callback: Callback)
    override fun close()
}
