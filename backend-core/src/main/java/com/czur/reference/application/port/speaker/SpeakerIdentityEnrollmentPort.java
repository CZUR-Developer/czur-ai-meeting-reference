package com.czur.reference.application.port.speaker;

import com.czur.reference.application.command.MeetingCommandContext;

/**
 * 已认证用户为 READY 会议中的发言人命名／注册的业务端口。
 *
 * <p>调用方负责建立可信身份，本端口核对原始会议归属和已冻结的能力。匿名
 * genTime 只用于结果读取，不能代替写入身份。成功后同场 Manifest revision
 * 增加，原时间、原文、录音与结果链接保持不变；调用方重新读取原链接即可。</p>
 */
@FunctionalInterface
public interface SpeakerIdentityEnrollmentPort {
    Result enroll(MeetingCommandContext context, String meetingId,
            String sessionSpeakerId, String displayName);

    sealed interface Result permits Enrolled, Rejected { }

    record Enrolled(SpeakerIdentityProvider.Person person, int manifestRevision) implements Result { }

    record Rejected(Reason reason) implements Result { }

    enum Reason { NOT_FOUND, ACCESS_DENIED, INVALID_STATE, NOT_GRANTED, SAMPLE_UNAVAILABLE, UNAVAILABLE }
}
