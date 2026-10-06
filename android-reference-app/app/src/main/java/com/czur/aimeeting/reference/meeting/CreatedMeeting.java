package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.MeetingCapabilityState;

import com.czur.aimeeting.model.ModelSessionGrant;

/** 创建会议的应用可用信息；modelSession 在 Mock 后端响应中可缺省。 */
public final class CreatedMeeting {
    private final String meetingId;
    private final ModelSessionGrant modelSession;
    private final MeetingCapabilityState capabilities;
    private final long maxMeetingDurationMs;

    public CreatedMeeting(String meetingId, ModelSessionGrant modelSession,
            MeetingCapabilityState capabilities, long maxMeetingDurationMs) {
        this.meetingId = meetingId;
        this.modelSession = modelSession;
        this.capabilities = capabilities;
        this.maxMeetingDurationMs = maxMeetingDurationMs;
    }

    public String meetingId() { return meetingId; }
    public ModelSessionGrant modelSession() { return modelSession; }
    public MeetingCapabilityState capabilities() { return capabilities; }
    /** Create 返回的本场时长上限，不由 App 固定写成某一种权益档位。 */
    public long maxMeetingDurationMs() { return maxMeetingDurationMs; }

    public CreatedMeeting withModelSession(ModelSessionGrant grant) {
        return new CreatedMeeting(meetingId, grant, capabilities, maxMeetingDurationMs);
    }
}
