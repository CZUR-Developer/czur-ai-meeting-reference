package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.MeetingCapabilityState;

import android.content.Context;
import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.ModelSessionListener;
import com.czur.aimeeting.model.SummaryInput;
import com.czur.aimeeting.model.SummaryResult;
import com.czur.aimeeting.model.SummarySessionGrant;
import com.czur.aimeeting.model.TranscriptListener;
import com.czur.aimeeting.reference.mock.MockModelAdapter;
import com.czur.aimeeting.reference.mock.MockSummaryAdapter;
import java.io.IOException;
import java.util.Set;

/** 无私有 AAR 时的演示装配。 */
public final class MeetingRuntimeFactory {
    private MeetingRuntimeFactory() {}

    public static MeetingRuntimeBridge create(Context context) {
        return new MeetingRuntimeBridge() {
            @Override
            public ModelSession open(CreatedMeeting created, TranscriptListener transcriptListener,
                    ModelSessionListener sessionListener) {
                return new MockModelAdapter().open(transcriptListener);
            }

            @Override
            public boolean waitsForModelFinish() { return false; }

            @Override
            public String displayName() { return "Mock"; }

            @Override
            public Set<String> setupCapabilities() {
                return Set.of(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SUMMARY);
            }

            @Override
            public Set<String> realtimeCapabilities(CreatedMeeting created) {
                return Set.of(MeetingCapabilityState.TRANSCRIPT);
            }

            @Override public SummaryResult summarize(SummaryInput input,
                    SummarySessionGrant grant) throws IOException {
                return new MockSummaryAdapter().parseResponse("{\"title\":\"参考会议纪要\","
                        + "\"overview\":\"本场讨论了 AI 会议纪要参考流程。\","
                        + "\"keyPoints\":[\"标准字幕已形成最终结果\"],"
                        + "\"actionItems\":[\"检查会后纪要结果\"]}", input.language());
            }
        };
    }
}
