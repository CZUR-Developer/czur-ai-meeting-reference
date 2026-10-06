package com.czur.aimeeting.reference;

import android.content.Context;
import com.czur.aimeeting.model.AudioDirectionSample;
import com.czur.aimeeting.model.AudioInputErrorCode;
import com.czur.aimeeting.model.AudioInputMode;
import com.czur.aimeeting.sdk.AudioInput;

/** SDK 模式的设备诊断与正式会议共用自动音频源选择。 */
final class AudioDiagnosticProbeFactory {
    private AudioDiagnosticProbeFactory() {}

    static AudioDiagnosticProbe create(Context context) {
        AudioInput input = new AudioInput(context);
        return new AudioDiagnosticProbe() {
            @Override public void start(Listener listener) {
                input.start(new AudioInput.Listener() {
                    private long frameCount;
                    private volatile String source = "AUTO_AUDIO";

                    @Override public void onFrame(byte[] pcm, long deliveredAtElapsedRealtimeMs) {
                        // 诊断直接消费伙伴可用的基础 PCM API，无 Server/模型依赖。
                        // 电平是 UI 诊断用途，SDK 只提供格式统一的原始音频数据。
                        long count = ++frameCount;
                        if (count % 10 != 0) return;
                        int peak = 0;
                        for (int i = 0; i < pcm.length; i += 2) {
                            int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
                            peak = Math.max(peak, Math.abs(sample));
                        }
                        listener.onLevel(source, count, peak);
                    }

                    @Override public void onAudioInputMode(AudioInputMode mode) {
                        source = mode.name();
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
                input.close();
            }
        };
    }
}
