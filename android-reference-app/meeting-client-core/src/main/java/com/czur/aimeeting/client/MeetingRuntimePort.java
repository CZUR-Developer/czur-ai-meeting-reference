package com.czur.aimeeting.client;

import com.czur.aimeeting.model.ModelAdapter;
import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSessionListener;
import com.czur.aimeeting.model.SummaryAdapter;
import com.czur.aimeeting.model.SummaryInput;
import com.czur.aimeeting.model.SummaryResult;
import com.czur.aimeeting.model.SummarySessionGrant;
import com.czur.aimeeting.model.TranscriptListener;
import java.io.IOException;
import java.util.Set;

/**
 * 平台/Mock 的窄运行时端口，不进行 Server 请求、选默认 Adapter 或会议编排。
 * SDK 使用真实材料及 Coordinator 选出的新 Adapter；本地 Mock 两类材料/Adapter 均为 null。
 */
public interface MeetingRuntimePort {
    /** Android 装配使用 elapsedRealtime；所有段/deadline/活跃事实共用此单调时基。 */
    default long elapsedRealtimeMs() { return System.nanoTime() / 1_000_000L; }
    default boolean usesServerModelMaterials() { return true; }
    default boolean waitsForModelFinish() { return usesServerModelMaterials(); }
    /** 仅本地 Mock 模式使用的固定能力；真实模式从注册表获得能力。 */
    default Set<String> localCapabilities() { return Set.of(); }
    ModelSession open(ModelSessionGrant grant, ModelAdapter adapter,
            TranscriptListener transcripts, ModelSessionListener listener);
    SummaryResult summarize(SummarySessionGrant grant, SummaryAdapter adapter, SummaryInput input)
            throws IOException;
}
