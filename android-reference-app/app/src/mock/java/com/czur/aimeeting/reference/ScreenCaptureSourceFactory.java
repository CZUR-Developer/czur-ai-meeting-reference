package com.czur.aimeeting.reference;

import android.content.Context;
import android.content.Intent;
import java.util.concurrent.Executor;

/** 干净Mock不含闭源AAR，不声明或模拟屏幕截图能力，也不保留另一份MediaProjection引擎。 */
final class ScreenCaptureSourceFactory {
    private ScreenCaptureSourceFactory() {}
    static boolean isSupported() { return false; }
    static ScreenCaptureSource create(Context context, int resultCode, Intent consentData,
            Executor callbackExecutor, Runnable onStopped) {
        throw new UnsupportedOperationException("Mock构建不提供屏幕截图，请使用SDK构建");
    }
}
