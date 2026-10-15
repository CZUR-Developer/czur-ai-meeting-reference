package com.czur.aimeeting.client;

import com.czur.aimeeting.model.ModelAdapter;
import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSessionListener;
import com.czur.aimeeting.model.ModelSessionReconnector;
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
    /** 与 ModelRuntime 同义；旧 Mock/伙伴实现默认没有同源录音装配。 */
    default boolean supportsRetainedAudio() { return false; }
    /** false 保留旧入口；true 须由具体运行时显式接入唯一标准 PCM 流。 */
    default ModelSession open(ModelSessionGrant grant, ModelAdapter adapter,
            TranscriptListener transcripts, ModelSessionListener listener, boolean retainAudio) {
        if (retainAudio) throw new IllegalStateException("同源录音尚未装配");
        return open(grant, adapter, transcripts, listener);
    }
    /**
     * 与 ModelRuntime 同义的可选续接桥；组合层捕获原场身份、Server及meetingId。
     * 默认委托旧五参入口，Mock和伙伴旧端口不会因新增方法自动改变终止语义。
     * 真正支持续接的Android运行时应在独立工作线程调用材料端口，Stop不等待HTTP。
     */
    default ModelSession open(ModelSessionGrant grant, ModelAdapter adapter,
            TranscriptListener transcripts, ModelSessionListener listener, boolean retainAudio,
            ModelSessionReconnector reconnector) {
        return open(grant, adapter, transcripts, listener, retainAudio);
    }
    SummaryResult summarize(SummarySessionGrant grant, SummaryAdapter adapter, SummaryInput input)
            throws IOException;
}
