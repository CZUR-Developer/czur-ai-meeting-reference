package com.czur.aimeeting.client;

import java.util.Objects;

/**
 * 相对会议起点的 JPEG 截图；采集授权和图片生成属于 App。
 * 构造与读取均复制数组，异步提交后调用方可复用原缓冲区。
 */
public final class PresentationFrame {
    private final long offsetMs;
    private final byte[] jpeg;
    public PresentationFrame(long offsetMs, byte[] jpeg) {
        this.offsetMs = offsetMs;
        this.jpeg = Objects.requireNonNull(jpeg, "jpeg").clone();
    }
    public long offsetMs() { return offsetMs; }
    public byte[] jpeg() { return jpeg.clone(); }
}
