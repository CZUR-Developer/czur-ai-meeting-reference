package com.czur.aimeeting.reference

import android.content.Context
import android.content.Intent
import java.util.concurrent.Executor

/** 干净 Mock 不声明截图能力，不复制或模拟 MediaProjection 引擎。 */
object ScreenCaptureSourceFactory {
    @JvmStatic fun isSupported() = false
    @JvmStatic fun create(context: Context?, resultCode: Int, consentData: Intent?,
        callbackExecutor: Executor, onStopped: Runnable): ScreenCaptureSource {
        throw UnsupportedOperationException("Mock构建不提供屏幕截图，请使用SDK构建")
    }
}
