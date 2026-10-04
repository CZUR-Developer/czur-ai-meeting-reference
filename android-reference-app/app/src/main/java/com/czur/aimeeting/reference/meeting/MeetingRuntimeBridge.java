package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.ModelSessionListener;
import com.czur.aimeeting.model.SummaryInput;
import com.czur.aimeeting.model.SummaryResult;
import com.czur.aimeeting.model.SummarySessionGrant;
import com.czur.aimeeting.model.TranscriptListener;
import java.io.IOException;
import java.util.Set;

/** App 会话流程使用的构建期运行时装配端口；公开 Mock 和闭源 SDK 共用页面。 */
public interface MeetingRuntimeBridge {
    ModelSession open(CreatedMeeting created, TranscriptListener transcriptListener,
            ModelSessionListener sessionListener);

    /** 真实模型须等最终消息再请求后端 Stop；Mock 在固定结果发出后可直接结束。 */
    boolean waitsForModelFinish();

    String displayName();

    /** 构建期已接入并能由当前 App 处理的能力，供会前关闭不可用模式。 */
    Set<String> setupCapabilities();

    /** 本场实际被后端选中的 Adapter 能归一化哪些实时模型能力。 */
    Set<String> realtimeCapabilities(CreatedMeeting created);

    /** 仅本场总结能力获准且最终字幕齐备后调用；Mock 不需要短期 grant。 */
    SummaryResult summarize(SummaryInput input, SummarySessionGrant grant) throws IOException;
}
