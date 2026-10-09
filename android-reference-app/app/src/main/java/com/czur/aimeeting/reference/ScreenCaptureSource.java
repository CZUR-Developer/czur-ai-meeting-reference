package com.czur.aimeeting.reference;

import android.graphics.Bitmap;
import android.os.IBinder;

/**
 * App对闭源截图SDK的薄接入端口，main源码不复制采集引擎。创建／调用在主线程，回调
 * 由SDK的主线程Executor交付；App随后把JPEG处理转到自己的工作线程。
 */
interface ScreenCaptureSource extends AutoCloseable {
    /** 实际平台路径，用于说明整屏／排除能力差异，不根据机型猜测。 */
    String mode();
    boolean supportsWindowExclusion();
    /** Bitmap成功交付后归App；编码完成后须回收。 */
    interface Callback {
        void onSuccess(Bitmap bitmap);
        void onError(String message);
    }
    void captureBitmap(IBinder[] excludedWindowTokens, Callback callback);
    @Override void close();
}
