package com.czur.aimeeting.model;

/**
 * 同一实际输入流的不可变显示电平，不携带 PCM，也不表示校准后的环境声压分贝。
 *
 * <p>SDK 对 PCM16LE 的均方根值使用旧字幕业务的对数尺度：相对满幅的 -60～0 dB
 * 映射为 0～100。默认每三个完整 100 ms 帧测量当次帧，约 300 ms 通知一次；
 * 它不是三个帧的累计能量窗口。音源间隔期间不补造值，全零帧如实得到 0。
 * 电平仅供显示，不参与发言判断、无字幕超时、停止或采音用量计费。
 */
public final class AudioInputLevel {
    private final int level;
    private final long sampledAtElapsedRealtimeMs;

    /**
     * @param level 0～100 的输入显示电平
     * @param sampledAtElapsedRealtimeMs 测量时的 Android elapsedRealtime 单调时刻，毫秒；
     *        不是墙钟、HAL 采样时刻或会议媒体时间戳
     */
    public AudioInputLevel(int level, long sampledAtElapsedRealtimeMs) {
        if (level < 0 || level > 100) throw new IllegalArgumentException("电平必须在0～100之间");
        this.level = level;
        this.sampledAtElapsedRealtimeMs = sampledAtElapsedRealtimeMs;
    }

    /** 0～100 的旧 RMS 显示尺度；0 也可能是低于 -60 dB 的有效输入。 */
    public int level() { return level; }

    /** 单调测量时刻，单位毫秒；通知排队不会改写这个值。 */
    public long sampledAtElapsedRealtimeMs() { return sampledAtElapsedRealtimeMs; }
}
