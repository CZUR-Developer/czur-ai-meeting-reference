package com.czur.aimeeting.reference

import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.czur.aimeeting.sdk.ScreenCaptureClient
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SDK 薄转接：授权和前台服务归 App，每场使用同一 SDK 黑盒会话。
 * SDK 交付的临时 File 由本层负责 IO 读取及删除，App 不再裁剪、缩放、过滤、去重或编码。
 */
object ScreenCaptureSourceFactory {
    @JvmStatic fun isSupported() = true
    @JvmStatic fun create(context: Context, resultCode: Int, consentData: Intent?,
        callbackExecutor: Executor, onStopped: Runnable): ScreenCaptureSource {
        val client = ScreenCaptureClient(context, resultCode, consentData!!, callbackExecutor, onStopped)
        val session = client.createSession(enableProcessing = true)
        val scope = CoroutineScope(SupervisorJob() + callbackExecutor.asCoroutineDispatcher())
        val directory = File(context.cacheDir, "meeting-presentation")
        return object : ScreenCaptureSource {
            override fun mode() = client.mode().name
            override fun supportsWindowExclusion() = client.supportsWindowExclusion()
            override fun capture(excludedWindowTokens: Array<IBinder?>, callback: ScreenCaptureSource.Callback) {
                scope.launch {
                    try {
                        // 未挂载的窗口不能静默忽略；整屏回退平台由上层明确传空列表。
                        val exclusions = excludedWindowTokens.map { requireNotNull(it) { "排除窗口尚未挂载" } }
                        withContext(Dispatchers.IO) {
                            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("截图目录无法创建")
                        }
                        val captured = session.captureFile(directory, exclusions)
                        if (captured == null) {
                            callback.onSuccess(null)
                            return@launch
                        }
                        val jpeg = try {
                            withContext(Dispatchers.IO) { captured.value.readBytes() }
                        } finally {
                            // File 已交给调用方；读取失败或取消同样由本层清理，不占用主线程。
                            withContext(NonCancellable + Dispatchers.IO) { captured.value.delete() }
                        }
                        callback.onSuccess(ScreenCaptureSource.Image(jpeg, captured::confirmAccepted))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: ScreenCaptureClient.CaptureException) {
                        callback.onError(failure.message ?: "SDK截图失败")
                    } catch (failure: IOException) {
                        callback.onError("演示截图文件处理失败")
                    } catch (failure: IllegalArgumentException) {
                        callback.onError("排除窗口尚未挂载")
                    }
                }
            }
            override fun close() {
                scope.cancel()
                session.close()
                client.close()
            }
        }
    }
}
