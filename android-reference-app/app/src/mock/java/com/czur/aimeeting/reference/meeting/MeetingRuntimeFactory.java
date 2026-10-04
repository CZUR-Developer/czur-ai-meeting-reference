package com.czur.aimeeting.reference.meeting;

import android.content.Context;
import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.ModelSessionListener;
import com.czur.aimeeting.model.TranscriptListener;
import com.czur.aimeeting.reference.mock.MockModelAdapter;
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
                return Set.of(MeetingCapabilityState.TRANSCRIPT);
            }

            @Override
            public Set<String> realtimeCapabilities(CreatedMeeting created) {
                return Set.of(MeetingCapabilityState.TRANSCRIPT);
            }
        };
    }
}
