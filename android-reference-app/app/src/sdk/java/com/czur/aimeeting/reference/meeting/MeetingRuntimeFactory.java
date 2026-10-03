package com.czur.aimeeting.reference.meeting;

import android.content.Context;
import com.czur.aimeeting.model.ModelRuntime;
import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSessionListener;
import com.czur.aimeeting.model.TranscriptListener;
import com.czur.aimeeting.reference.tingwu.TingwuModelAdapter;
import com.czur.aimeeting.sdk.AndroidModelRuntime;

/** SDK 装配与具体模型协议在构建期确定，通用会议页面不依赖私有类。 */
public final class MeetingRuntimeFactory {
    private MeetingRuntimeFactory() {}

    public static MeetingRuntimeBridge create(Context context) {
        ModelRuntime runtime = new AndroidModelRuntime(context);
        return new MeetingRuntimeBridge() {
            @Override
            public ModelSession open(CreatedMeeting created, TranscriptListener transcriptListener,
                    ModelSessionListener sessionListener) {
                ModelSessionGrant grant = created.modelSession();
                if (grant == null) {
                    throw new IllegalArgumentException("创建会议响应缺少 modelSession");
                }
                TingwuModelAdapter adapter = new TingwuModelAdapter();
                if (!adapter.id().equals(grant.adapterId())) {
                    throw new IllegalArgumentException("本次模型 Adapter 与参考 App 不匹配");
                }
                return runtime.open(grant, adapter, transcriptListener, sessionListener);
            }

            @Override
            public boolean waitsForModelFinish() { return true; }

            @Override
            public String displayName() { return "听悟"; }
        };
    }
}
