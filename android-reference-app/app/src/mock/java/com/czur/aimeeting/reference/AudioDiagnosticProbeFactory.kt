package com.czur.aimeeting.reference

import android.content.Context

/** Mock 不接麦克风，Debug 页继续明确提示实际能力。 */
object AudioDiagnosticProbeFactory {
    @JvmStatic fun create(context: Context): AudioDiagnosticProbe = object : AudioDiagnosticProbe {
        override fun start(listener: AudioDiagnosticProbe.Listener) { listener.onError("Mock 构建未接入音频 SDK") }
        override fun close() {}
    }
}
