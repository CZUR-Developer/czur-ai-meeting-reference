package com.czur.aimeeting.reference

import com.czur.aimeeting.model.AudioDirectionSample
import com.czur.aimeeting.model.AudioInputErrorCode
import com.czur.aimeeting.model.AudioInputMode

/** Debug 设备验收的读帧端口；正式会议不调用，权限申请仍由页面负责。 */
interface AudioDiagnosticProbe : AutoCloseable {
    interface Listener {
        fun onLevel(source: String, frames: Long, peak: Int)
        fun onError(message: String)
        fun onAudioInputMode(mode: AudioInputMode) {}
        fun onAudioDirection(sample: AudioDirectionSample) {}
        fun onAudioError(code: AudioInputErrorCode, message: String) { onError(message) }
    }
    fun start(listener: Listener)
    override fun close()
}
