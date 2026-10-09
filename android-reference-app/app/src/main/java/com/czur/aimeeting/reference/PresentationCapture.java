package com.czur.aimeeting.reference;

import android.graphics.Bitmap;
import android.os.IBinder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * App的演示帧处理：通过薄端口取得SDK截图，保持原1280长边／JPEG体积与相对时间约束。
 * 不持有MediaProjection、VirtualDisplay或ImageReader；周期和末帧仍由MeetingService安排。
 * 请求发起和结果回调在主线程；实际采集在SDK工作线程，缩放／编码在App的platformWork执行。
 * 不在主线程或回调队列同步等待截图结果。
 */
final class PresentationCapture implements AutoCloseable {
    private static final int MAX_EDGE = 1280;
    // 后端 HTTP 请求体上限为 1 MiB，Base64 和 JSON 仍需留空间。
    private static final int MAX_JPEG_BYTES = 750_000;
    record Frame(long offsetMs, byte[] jpeg) {}
    interface Callback {
        void onSuccess(Frame frame);
        void onError(String message);
    }
    private final ScreenCaptureSource source;
    private final long meetingStartedAtMs;
    private final Executor work;
    private final Executor callbacks;
    /** 已交付给App但尚未开始编码的图像，宿主关闭队列前由本层回收。 */
    private final Set<Bitmap> pendingEncoding = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    PresentationCapture(ScreenCaptureSource source, long meetingStartedAtMs,
            Executor work, Executor callbacks) {
        this.source = source;
        this.meetingStartedAtMs = meetingStartedAtMs;
        this.work = work;
        this.callbacks = callbacks;
    }

    String mode() { return source.mode(); }
    boolean supportsWindowExclusion() { return source.supportsWindowExclusion(); }

    /** 主线程发起；nowMs与会议起点为同一elapsedRealtime时基，异步压缩不改变该偏移。 */
    void capture(long nowMs, IBinder[] excluded, Callback callback) {
        if (closed) { callback.onError("本场截图已关闭"); return; }
        source.captureBitmap(excluded, new ScreenCaptureSource.Callback() {
            @Override public void onSuccess(Bitmap bitmap) {
                if (closed) { bitmap.recycle(); callback.onError("本场截图已关闭"); return; }
                pendingEncoding.add(bitmap);
                try {
                    work.execute(() -> {
                        // 与close只争用尚未开始的图像；已开始编码的图像由encode的finally回收。
                        if (!pendingEncoding.remove(bitmap)) return;
                        Frame result;
                        try { result = encode(bitmap, Math.max(0, nowMs - meetingStartedAtMs)); }
                        catch (IOException | RuntimeException failure) {
                            callbacks.execute(() -> callback.onError("演示截图JPEG处理失败"));
                            return;
                        }
                        callbacks.execute(() -> callback.onSuccess(result));
                    });
                } catch (RejectedExecutionException ended) {
                    if (pendingEncoding.remove(bitmap)) bitmap.recycle();
                    callback.onError("本场截图处理队列已关闭");
                }
            }
            @Override public void onError(String message) { callback.onError(message); }
        });
    }

    /** App工作线程调用；接管传入的软件Bitmap并在所有编码出口回收。 */
    static Frame encode(Bitmap bitmap, long offsetMs) throws IOException {
        Bitmap scaled = bitmap;
        try {
            double ratio = Math.min(1.0, (double) MAX_EDGE / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            if (ratio < 1.0) {
                scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, (int) Math.round(bitmap.getWidth() * ratio)),
                        Math.max(1, (int) Math.round(bitmap.getHeight() * ratio)), true);
            }
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 65, encoded))
                throw new IOException("演示截图 JPEG 编码失败");
            if (encoded.size() > MAX_JPEG_BYTES) {
                encoded.reset();
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 45, encoded))
                    throw new IOException("演示截图 JPEG 编码失败");
            }
            if (encoded.size() > MAX_JPEG_BYTES)
                throw new IOException("演示截图超过本场 HTTP 上传上限");
            return new Frame(offsetMs, encoded.toByteArray());
        } finally {
            if (scaled != bitmap) scaled.recycle();
            bitmap.recycle();
        }
    }

    /** 主线程关闭SDK授权；正常末帧处理完再调用，不阻塞主线程。 */
    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Bitmap bitmap : pendingEncoding)
            if (pendingEncoding.remove(bitmap)) bitmap.recycle();
        source.close();
    }
}
