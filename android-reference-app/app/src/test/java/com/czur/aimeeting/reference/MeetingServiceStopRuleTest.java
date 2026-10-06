package com.czur.aimeeting.reference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** 音频启动被拒绝后仍能结束会议、回到会前权限流程，不等待 SDK 已关闭的模型回调。 */
public final class MeetingServiceStopRuleTest {
    @Test public void terminatedAudioCanStopWithoutModelFinishedCallback() {
        assertFalse(MeetingService.canCompleteAfterStop(true, false, false));
        assertTrue(MeetingService.canCompleteAfterStop(true, false, true));
        assertTrue(MeetingService.canCompleteAfterStop(true, true, false));
    }
}
