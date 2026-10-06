package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.assertEquals;
import com.czur.aimeeting.model.AudioInputErrorCode;
import com.czur.aimeeting.model.ModelSessionListener;
import org.junit.Test;

/** 老调用方只实现 onError 时仍可收到新音频错误；无需修改 Mock 或合作伙伴的旧回调。 */
public final class ModelSessionListenerCompatibilityTest {
    @Test public void audioErrorReachesExistingErrorCallback() {
        String[] received = new String[1];
        ModelSessionListener listener = new ModelSessionListener() {
            @Override public void onFinished() {}
            @Override public void onError(String message) { received[0] = message; }
        };
        listener.onAudioError(AudioInputErrorCode.PERMISSION_DENIED, "需要麦克风权限");
        assertEquals("需要麦克风权限", received[0]);
    }
}
