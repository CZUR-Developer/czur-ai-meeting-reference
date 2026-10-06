package com.czur.aimeeting.reference;

import android.content.Context;

/** Mock 构建不接麦克风；保持 Debug 诊断页可打开并明确提示。 */
final class AudioDiagnosticProbeFactory {
    private AudioDiagnosticProbeFactory() {}

    static AudioDiagnosticProbe create(Context context) {
        return new AudioDiagnosticProbe() {
            @Override public void start(Listener listener) {
                listener.onError("Mock 构建未接入音频 SDK");
            }

            @Override public void close() {}
        };
    }
}
