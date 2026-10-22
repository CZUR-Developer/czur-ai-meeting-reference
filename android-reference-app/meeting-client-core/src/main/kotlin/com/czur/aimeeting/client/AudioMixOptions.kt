package com.czur.aimeeting.client

/**
 * 本场共享音频混流选项，由宿主随会议设置在开场前冻结。
 *
 * 只影响设备端采集：打开系统共享流时告诉 Broker 是否把远端播放混入、两路增益多少。
 * 不进入 Create 请求、不改变模型或结果协议。增益是百分比，100 表示原值；两路增益只在
 * 播放捕获开启且系统混音可用时生效，MIC-only 路径不受影响。
 */
data class AudioMixOptions(
    /** true 时把扬声器/远端播放混入共享流；false 只给 MIC。 */
    val capturePlayback: Boolean = true,
    /** MIC 路增益百分比，范围 0～400。 */
    val micGainPercent: Int = 100,
    /** 播放路增益百分比，范围 0～400。 */
    val playbackGainPercent: Int = 100,
) {
    init {
        require(micGainPercent in 0..400) { "micGainPercent 必须在 0～400" }
        require(playbackGainPercent in 0..400) { "playbackGainPercent 必须在 0～400" }
    }
}
