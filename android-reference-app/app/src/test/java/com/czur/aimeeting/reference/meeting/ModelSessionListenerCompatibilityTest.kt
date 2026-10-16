package com.czur.aimeeting.reference.meeting

import org.junit.Assert.assertEquals
import org.junit.Test
import com.czur.aimeeting.model.AudioInputErrorCode
import com.czur.aimeeting.model.ModelSessionListener

/** 只实现旧错误回调时仍能收到新增的音频错误。 */
class ModelSessionListenerCompatibilityTest {
    @Test fun audioErrorReachesExistingErrorCallback() {
        var received: String? = null
        val listener = object : ModelSessionListener {
            override fun onFinished() {}
            override fun onError(message: String) { received = message }
        }
        listener.onAudioError(AudioInputErrorCode.PERMISSION_DENIED, "需要麦克风权限")
        assertEquals("需要麦克风权限", received)
    }
}
