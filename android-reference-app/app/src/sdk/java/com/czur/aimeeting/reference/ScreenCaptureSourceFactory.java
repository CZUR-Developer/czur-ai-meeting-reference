package com.czur.aimeeting.reference;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.IBinder;
import com.czur.aimeeting.sdk.ScreenCaptureClient;
import java.util.concurrent.Executor;

/** 构建期SDK薄转接；授权／前台服务在App，图像采集与转换全部在闭源SDK。 */
final class ScreenCaptureSourceFactory {
    private ScreenCaptureSourceFactory() {}
    static boolean isSupported() { return true; }
    static ScreenCaptureSource create(Context context, int resultCode, Intent consentData,
            Executor callbackExecutor, Runnable onStopped) {
        ScreenCaptureClient client = new ScreenCaptureClient(context, resultCode, consentData,
                callbackExecutor, onStopped);
        return new ScreenCaptureSource() {
            @Override public String mode() { return client.mode().name(); }
            @Override public boolean supportsWindowExclusion() { return client.supportsWindowExclusion(); }
            @Override public void captureBitmap(IBinder[] excluded, Callback callback) {
                client.captureBitmap(excluded, new ScreenCaptureClient.Callback<Bitmap>() {
                    @Override public void onSuccess(Bitmap bitmap) { callback.onSuccess(bitmap); }
                    @Override public void onError(ScreenCaptureClient.ErrorCode code, String message) {
                        callback.onError(code + "：" + message);
                    }
                });
            }
            @Override public void close() { client.close(); }
        };
    }
}
