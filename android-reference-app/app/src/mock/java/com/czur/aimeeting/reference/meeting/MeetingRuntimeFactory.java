package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.MeetingCapabilityState;
import com.czur.aimeeting.client.MeetingClient;
import com.czur.aimeeting.client.MeetingClientConfig;
import com.czur.aimeeting.client.MeetingCoordinator;
import com.czur.aimeeting.client.MeetingRuntimePort;
import com.czur.aimeeting.client.ModelAdapterRegistry;

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

/** 无私有 AAR 时仅替换模型运行端口；其余登录、建会、显示与收口共用同一协调器。 */
public final class MeetingRuntimeFactory {
    private MeetingRuntimeFactory() {}

    public static MeetingClient create(Context context, MeetingClientConfig config) {
        return new MeetingCoordinator(config, new ModelAdapterRegistry(), new MeetingRuntimePort() {
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
