package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.MeetingCapabilityState;
import com.czur.aimeeting.client.MeetingClient;
import com.czur.aimeeting.client.MeetingClientConfig;
import com.czur.aimeeting.client.MeetingCoordinator;
import com.czur.aimeeting.client.MeetingRuntimePort;

import android.content.Context;
import android.os.SystemClock;
import com.czur.aimeeting.model.ModelAdapter;
import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSessionListener;
import com.czur.aimeeting.model.SummaryAdapter;
import com.czur.aimeeting.model.SummaryInput;
import com.czur.aimeeting.model.SummaryResult;
import com.czur.aimeeting.model.SummarySessionGrant;
import com.czur.aimeeting.model.TranscriptListener;
import com.czur.aimeeting.reference.mock.MockModelAdapter;
import com.czur.aimeeting.reference.mock.MockSummaryAdapter;
import java.io.IOException;
import java.util.Set;

/**
 * 无私有AAR时消费同一集中装配注册表，其余登录、建会、显示与收口共用同一协调器。
 * 模型运行端口仍固定演示字幕／纪要，不领取真实模型材料，也不执行伙伴Adapter。
 */
public final class MeetingRuntimeFactory {
    private MeetingRuntimeFactory() {}

    public static MeetingClient create(Context context, MeetingClientConfig config) {
        return new MeetingCoordinator(config, MeetingAppAssembly.modelAdapters(), new MeetingRuntimePort() {
            // 与 Service 计时、MediaProjection offset 使用同一个 Android 单调时钟。
            @Override public long elapsedRealtimeMs() { return SystemClock.elapsedRealtime(); }
            @Override public boolean usesServerModelMaterials() { return false; }

            @Override
            public ModelSession open(ModelSessionGrant grant, ModelAdapter adapter,
                    TranscriptListener transcriptListener,
                    ModelSessionListener sessionListener) {
                return new MockModelAdapter().open(transcriptListener);
            }

            @Override
            public boolean waitsForModelFinish() { return false; }

            @Override
            public Set<String> localCapabilities() {
                return Set.of(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SUMMARY);
            }

            @Override public SummaryResult summarize(SummarySessionGrant grant,
                    SummaryAdapter adapter, SummaryInput input) throws IOException {
                return new MockSummaryAdapter().parseResponse("{\"title\":\"参考会议纪要\","
                        + "\"overview\":\"本场讨论了 AI 会议纪要参考流程。\","
                        + "\"keyPoints\":[\"标准字幕已形成最终结果\"],"
                        + "\"actionItems\":[\"检查会后纪要结果\"]}", input.language());
            }
        });
    }

    /** 仅供 App 选择权限申请与 Android 前台服务类型，不参与会议业务判断。 */
    public static boolean requiresMicrophone() { return false; }
}
