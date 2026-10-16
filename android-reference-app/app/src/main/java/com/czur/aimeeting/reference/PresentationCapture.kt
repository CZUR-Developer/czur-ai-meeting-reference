package com.czur.aimeeting.reference

import android.graphics.Bitmap
import android.os.IBinder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * SDK 截图的 App 薄装配，保持原长边、JPEG 体积及会议相对时间规则。
 * 主线程发起并接收结果，缩放编码仍使用 platformWork；周期、末帧由 Service 控制。
 */
class PresentationCapture(
    private val source: ScreenCaptureSource, private val meetingStartedAtMs: Long,
    private val work: Executor, private val callbacks: Executor
) : AutoCloseable {
    data class Frame(private val offsetMs: Long, private val jpeg: ByteArray) {
        fun offsetMs() = offsetMs
        fun jpeg() = jpeg
    }
    interface Callback {
        fun onSuccess(frame: Frame)
        fun onError(message: String)
    }
    /** 已交付但尚未开始编码的图片由本层关闭时回收，编码中的图归 encode 的 finally。 */
    private val pendingEncoding = ConcurrentHashMap.newKeySet<Bitmap>()
    @Volatile private var closed = false

    fun mode() = source.mode()
    fun supportsWindowExclusion() = source.supportsWindowExclusion()

    fun capture(nowMs: Long, excluded: Array<IBinder?>, callback: Callback) {
        if (closed) { callback.onError("本场截图已关闭"); return }
        source.captureBitmap(excluded, object : ScreenCaptureSource.Callback {
            override fun onSuccess(bitmap: Bitmap) {
                if (closed) { bitmap.recycle(); callback.onError("本场截图已关闭"); return }
                pendingEncoding += bitmap
                try {
                    work.execute {
                        if (!pendingEncoding.remove(bitmap)) return@execute
                        val result = try { encode(bitmap, maxOf(0L, nowMs - meetingStartedAtMs)) }
                        catch (failure: IOException) {
                            callbacks.execute { callback.onError("演示截图JPEG处理失败") }
                            return@execute
                        } catch (failure: RuntimeException) {
                            callbacks.execute { callback.onError("演示截图JPEG处理失败") }
                            return@execute
                        }
                        callbacks.execute { callback.onSuccess(result) }
                    }
                } catch (ended: RejectedExecutionException) {
                    if (pendingEncoding.remove(bitmap)) bitmap.recycle()
                    callback.onError("本场截图处理队列已关闭")
                }
            }
            override fun onError(message: String) = callback.onError(message)
        })
    }

    /** 正常末帧完成后关闭，不阻塞主线程。 */
    override fun close() {
        if (closed) return
        closed = true
        for (bitmap in pendingEncoding) if (pendingEncoding.remove(bitmap)) bitmap.recycle()
        source.close()
    }

    companion object {
        private const val MAX_EDGE = 1280
        private const val MAX_JPEG_BYTES = 750_000

        /** 工作线程接管 Bitmap，在全部编码出口释放；语言迁移不调整现有 JPEG 策略。 */
        @JvmStatic @Throws(IOException::class)
        fun encode(bitmap: Bitmap, offsetMs: Long): Frame {
            var scaled = bitmap
            try {
                val ratio = minOf(1.0, MAX_EDGE.toDouble() / maxOf(bitmap.width, bitmap.height))
                if (ratio < 1.0) {
                    scaled = Bitmap.createScaledBitmap(bitmap,
                        maxOf(1, Math.round(bitmap.width * ratio).toInt()),
                        maxOf(1, Math.round(bitmap.height * ratio).toInt()), true)
                }
                val encoded = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 65, encoded)) throw IOException("演示截图 JPEG 编码失败")
                if (encoded.size() > MAX_JPEG_BYTES) {
                    encoded.reset()
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, 45, encoded)) throw IOException("演示截图 JPEG 编码失败")
                }
                if (encoded.size() > MAX_JPEG_BYTES) throw IOException("演示截图超过本场 HTTP 上传上限")
                return Frame(offsetMs, encoded.toByteArray())
            } finally {
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }
        }
    }
}
