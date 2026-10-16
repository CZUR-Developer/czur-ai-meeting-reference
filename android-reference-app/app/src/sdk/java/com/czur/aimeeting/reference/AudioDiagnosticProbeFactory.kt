package com.czur.aimeeting.reference

import android.content.Context
import com.czur.aimeeting.model.AudioDirectionSample
import com.czur.aimeeting.model.AudioInputErrorCode
import com.czur.aimeeting.model.AudioInputMode
import com.czur.aimeeting.sdk.AudioInput

/** 设备诊断与正式会议共用自动音频源选择，直接消费伙伴可用的基础 PCM API。 */
object AudioDiagnosticProbeFactory {
    @JvmStatic fun create(context: Context): AudioDiagnosticProbe {
        val input = AudioInput(context)
        return object : AudioDiagnosticProbe {
            override fun start(listener: AudioDiagnosticProbe.Listener) {
                input.start(object : AudioInput.Listener {
                    private var frameCount = 0L
                    @Volatile private var source = "AUTO_AUDIO"
                    override fun onFrame(pcm: ByteArray, deliveredAtElapsedRealtimeMs: Long) {
                        val count = ++frameCount
                        if (count % 10 != 0L) return
                        var peak = 0
                        for (i in pcm.indices step 2) {
                            val sample = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
                            peak = maxOf(peak, kotlin.math.abs(sample))
                        }
                        listener.onLevel(source, count, peak)
                    }
                    override fun onAudioInputMode(mode: AudioInputMode) { source = mode.name; listener.onAudioInputMode(mode) }
                    override fun onAudioDirection(sample: AudioDirectionSample) = listener.onAudioDirection(sample)
                    override fun onAudioError(code: AudioInputErrorCode, message: String) = listener.onAudioError(code, message)
                })
            }
            override fun close() = input.close()
        }
    }
}
