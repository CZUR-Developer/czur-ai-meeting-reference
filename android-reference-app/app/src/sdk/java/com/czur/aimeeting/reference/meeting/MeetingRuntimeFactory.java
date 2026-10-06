package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.ModelAdapterRegistry;

import android.content.Context;
import com.czur.aimeeting.model.ModelRuntime;
import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSessionListener;
import com.czur.aimeeting.model.SummaryInput;
import com.czur.aimeeting.model.SummaryResult;
import com.czur.aimeeting.model.SummarySessionGrant;
import com.czur.aimeeting.model.TranscriptListener;
import com.czur.aimeeting.sdk.AndroidModelRuntime;
import com.czur.aimeeting.sdk.AndroidSummaryRuntime;
import java.io.IOException;
import java.util.Set;

/** SDK 装配与具体模型协议在构建期确定，通用会议页面不依赖私有类。 */
public final class MeetingRuntimeFactory {
    private MeetingRuntimeFactory() {}

    public static MeetingRuntimeBridge create(Context context) {
        ModelRuntime runtime = new AndroidModelRuntime(context);
        AndroidSummaryRuntime summaryRuntime = new AndroidSummaryRuntime();
        ModelAdapterRegistry adapters = new ModelAdapterRegistry();
        return new MeetingRuntimeBridge() {
            @Override
            public ModelSession open(CreatedMeeting created, TranscriptListener transcriptListener,
                    ModelSessionListener sessionListener) {
                ModelSessionGrant grant = created.modelSession();
                if (grant == null) {
                    throw new IllegalArgumentException("创建会议响应缺少 modelSession");
                }
                return runtime.open(grant, adapters.create(grant.adapterId()),
                        transcriptListener, sessionListener);
            }

            @Override
            public boolean waitsForModelFinish() { return true; }

            @Override
            public String displayName() { return "模型"; }

            @Override
            public Set<String> setupCapabilities() {
                return adapters.setupCapabilities();
            }

            @Override
            public Set<String> realtimeCapabilities(CreatedMeeting created) {
                return adapters.realtimeCapabilities(created.modelSession().adapterId());
            }

            @Override public SummaryResult summarize(SummaryInput input,
                    SummarySessionGrant grant) throws IOException {
                if (grant == null) throw new IOException("本场缺少文本总结材料");
                return summaryRuntime.summarize(grant,
                        adapters.createSummary(grant.adapterId()), input);
            }
        };
    }
}
