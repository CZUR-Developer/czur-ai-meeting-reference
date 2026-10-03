package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.ModelSessionListener;
import com.czur.aimeeting.model.TranscriptListener;

/** App 会话流程使用的构建期运行时装配端口；公开 Mock 和闭源 SDK 共用页面。 */
public interface MeetingRuntimeBridge {
    ModelSession open(CreatedMeeting created, TranscriptListener transcriptListener,
            ModelSessionListener sessionListener);

    /** 真实模型须等最终消息再请求后端 Stop；Mock 在固定结果发出后可直接结束。 */
    boolean waitsForModelFinish();

    String displayName();
}
