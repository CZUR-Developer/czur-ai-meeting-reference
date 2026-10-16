package com.czur.aimeeting.model

/**
 * 同一实际输入流的不可变显示电平，不携带 PCM，不表示校准声压。
 * PCM16LE 均方根使用旧业务对数尺度，将相对满幅 -60～0 dB 映射为 0～100。
 * 默认每三个完整 100 ms 帧测量当次帧，约 300 ms 回调一次，并非累计能量窗口。
 * 间隔不补值，全零帧为 0；此值不参与发言判断、无字幕超时、停止或计费。
 *
 * @param level 0～100 的显示电平。
 * @param sampledAtElapsedRealtimeMs 测量时 elapsedRealtime 单调毫秒，不是墙钟或媒体时间。
 */
class AudioInputLevel(private val level: Int, private val sampledAtElapsedRealtimeMs: Long) {
    init { require(level in 0..100) { "电平必须在0～100之间" } }

    /** 旧 RMS 显示尺度；0 也可表示低于 -60 dB 的有效输入。 */
    fun level(): Int = level
    /** 测量单调时刻，回调排队不改写它。 */
    fun sampledAtElapsedRealtimeMs(): Long = sampledAtElapsedRealtimeMs
}
