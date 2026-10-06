package com.czur.aimeeting.reference;

import com.czur.aimeeting.model.AudioDirectionSample;
import com.czur.aimeeting.model.AudioInputErrorCode;
import com.czur.aimeeting.model.AudioInputMode;

/** Debug 设备验收使用的音频读帧抽象；正式会议入口不会调用它。 */
interface AudioDiagnosticProbe extends AutoCloseable {
    interface Listener {
        void onLevel(String source, long frames, int peak);
        void onError(String message);
        /** 音频 SDK 选择完成后的真实来源，诊断页直接展示，不自行猜测平台。 */
        default void onAudioInputMode(AudioInputMode mode) {}
        /** 系统角度／不可用状态，用于检查正式 SDK 数据出口。 */
        default void onAudioDirection(AudioDirectionSample sample) {}
        /** Debug 自检只检查错误回调，权限申请仍由页面正常开始流程负责。 */
        default void onAudioError(AudioInputErrorCode code, String message) { onError(message); }
    }

    void start(Listener listener);
    @Override void close();
}
