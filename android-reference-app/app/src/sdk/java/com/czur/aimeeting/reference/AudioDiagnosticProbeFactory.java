package com.czur.aimeeting.reference;

import android.content.Context;
import com.czur.aimeeting.model.AudioDirectionSample;
import com.czur.aimeeting.model.AudioInputErrorCode;
import com.czur.aimeeting.model.AudioInputMode;
import com.czur.aimeeting.sdk.AudioInputMonitor;

/** SDK 模式的设备诊断与正式会议共用自动音频源选择。 */
final class AudioDiagnosticProbeFactory {
    private AudioDiagnosticProbeFactory() {}

    static AudioDiagnosticProbe create(Context context) {
        AudioInputMonitor monitor = new AudioInputMonitor(context);
        return new AudioDiagnosticProbe() {
            @Override public void start(Listener listener) {
                monitor.start(new AudioInputMonitor.Listener() {
                    @Override public void onLevel(String source, long frames, int peak) {
                        listener.onLevel(source, frames, peak);
                    }

                    @Override public void onError(String message) {
                        listener.onError(message);
                    }

                    @Override public void onAudioInputMode(AudioInputMode mode) {
                        listener.onAudioInputMode(mode);
                    }

                    @Override public void onAudioDirection(AudioDirectionSample sample) {
                        listener.onAudioDirection(sample);
                    }

                    @Override public void onAudioError(AudioInputErrorCode code, String message) {
                        listener.onAudioError(code, message);
                    }
                });
            }

            @Override public void close() {
                monitor.close();
            }
        };
    }
}
