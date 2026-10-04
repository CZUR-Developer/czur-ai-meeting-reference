package com.czur.aimeeting.reference;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** 一次用户授权对应一场连续屏幕投影；会议 Service 负责定时取帧和上传。 */
final class PresentationCapture implements AutoCloseable {
    private static final int MAX_EDGE = 1280;
    // 后端 HTTP 请求体上限为 1 MiB，Base64 和 JSON 仍需留空间。
    private static final int MAX_JPEG_BYTES = 750_000;

    record Frame(long offsetMs, byte[] jpeg) {}

    private final long meetingStartedAtMs;
    private final Runnable onStopped;
    private final MediaProjection projection;
    private final MediaProjection.Callback callback;
    private ImageReader reader;
    private VirtualDisplay display;
    private Image latestImage;
    private boolean closed;

    PresentationCapture(Context context, int resultCode, Intent consentData,
            long meetingStartedAtMs, Runnable onStopped) {
        this.meetingStartedAtMs = meetingStartedAtMs;
        this.onStopped = onStopped;
        MediaProjectionManager manager = context.getSystemService(MediaProjectionManager.class);
        projection = manager.getMediaProjection(resultCode, consentData);
        callback = new MediaProjection.Callback() {
            @Override public void onStop() {
                release(false);
                onStopped.run();
            }
        };
        projection.registerCallback(callback, new Handler(Looper.getMainLooper()));

        try {
            WindowManager windows = context.getSystemService(WindowManager.class);
            int width;
            int height;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Rect bounds = windows.getMaximumWindowMetrics().getBounds();
                width = bounds.width();
                height = bounds.height();
            } else {
                Point size = new Point();
                windows.getDefaultDisplay().getRealSize(size);
                width = size.x;
                height = size.y;
            }
            double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(width, height));
            width = Math.max(1, (int) Math.round(width * scale));
            height = Math.max(1, (int) Math.round(height * scale));
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(source -> {
                synchronized (PresentationCapture.this) {
                    if (closed) return;
                    Image newer = source.acquireLatestImage();
                    if (newer == null) return;
                    if (latestImage != null) latestImage.close();
                    latestImage = newer;
                }
            }, new Handler(Looper.getMainLooper()));
            display = projection.createVirtualDisplay("AI Meeting presentation", width, height,
                    context.getResources().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, null);
        } catch (RuntimeException failure) {
            release(true);
            throw failure;
        }
    }

    synchronized Frame capture(long nowMs) throws IOException {
        if (closed) return null;
        Image current = latestImage;
        latestImage = null;
        try (Image image = current) {
            if (image == null) return null;
            Image.Plane plane = image.getPlanes()[0];
            int paddedWidth = Math.max(image.getWidth(),
                    plane.getRowStride() / plane.getPixelStride());
            Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Bitmap frame = null;
            try {
                padded.copyPixelsFromBuffer(plane.getBuffer());
                frame = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                if (!frame.compress(Bitmap.CompressFormat.JPEG, 65, encoded)) {
                    throw new IOException("演示截图 JPEG 编码失败");
                }
                if (encoded.size() > MAX_JPEG_BYTES) {
                    encoded.reset();
                    frame.compress(Bitmap.CompressFormat.JPEG, 45, encoded);
                }
                if (encoded.size() > MAX_JPEG_BYTES) {
                    throw new IOException("演示截图超过本场 HTTP 上传上限");
                }
                return new Frame(Math.max(0, nowMs - meetingStartedAtMs),
                        encoded.toByteArray());
            } finally {
                if (frame != null) frame.recycle();
                padded.recycle();
            }
        }
    }

    @Override public void close() { release(true); }

    private synchronized void release(boolean stopProjection) {
        if (closed) return;
        closed = true;
        if (latestImage != null) latestImage.close();
        latestImage = null;
        if (display != null) display.release();
        if (reader != null) {
            reader.setOnImageAvailableListener(null, null);
            reader.close();
        }
        projection.unregisterCallback(callback);
        if (stopProjection) projection.stop();
    }
}
