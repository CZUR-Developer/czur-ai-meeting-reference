package com.czur.reference.application.error;

/** 创建命令引用了已经存在、但没有对应幂等记录的会议。 */
public final class MeetingAlreadyExistsException extends IllegalStateException {
    public MeetingAlreadyExistsException(String meetingId) {
        super("Meeting already exists: " + meetingId);
    }
}
