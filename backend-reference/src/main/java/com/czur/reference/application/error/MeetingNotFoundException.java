package com.czur.reference.application.error;

/** 查询或停止不存在的会议时抛出。 */
public final class MeetingNotFoundException extends IllegalArgumentException {
    public MeetingNotFoundException(String meetingId) {
        super("Meeting not found: " + meetingId);
    }
}
