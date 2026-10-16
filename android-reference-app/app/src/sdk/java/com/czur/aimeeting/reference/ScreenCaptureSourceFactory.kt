package com.czur.aimeeting.reference

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import com.czur.aimeeting.sdk.ScreenCaptureClient
import java.util.concurrent.Executor

/** SDK 薄转接；授权和前台服务归 App，采集与转换归闭源 SDK。 */
object ScreenCaptureSourceFactory {
    @JvmStatic fun isSupported() = true
    @JvmStatic fun create(context: Context, resultCode: Int, consentData: Intent?,
        callbackExecutor: Executor, onStopped: Runnable): ScreenCaptureSource {
        val client = ScreenCaptureClient(context, resultCode, consentData!!, callbackExecutor, onStopped)
        return object : ScreenCaptureSource {
            override fun mode() = client.mode().name
            override fun supportsWindowExclusion() = client.supportsWindowExclusion()
            override fun captureBitmap(excludedWindowTokens: Array<IBinder?>, callback: ScreenCaptureSource.Callback) {
                client.captureBitmap(excludedWindowTokens, object : ScreenCaptureClient.Callback<Bitmap> {
                    override fun onSuccess(bitmap: Bitmap) = callback.onSuccess(bitmap)
                    override fun onError(code: ScreenCaptureClient.ErrorCode, message: String) = callback.onError("$code：$message")
                })
            }
            override fun close() = client.close()
        }
    }
}
