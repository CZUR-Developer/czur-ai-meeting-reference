package com.czur.reference.application.port.speaker;

import com.czur.reference.application.command.MeetingCommandContext;

/**
 * 已认证用户为 READY 会议中的发言人命名／注册的业务端口。
 *
 * <p>旧入口按已认证所有者校验；改名附带的登记可接受宿主批准的结果编辑范围，
 * 人物库仍取真实会议归属与冻结能力。成功后同场 Manifest revision
 * 增加，原时间、原文、录音与结果链接保持不变；调用方重新读取原链接即可。</p>
 */
@FunctionalInterface
public interface SpeakerIdentityEnrollmentPort {
    Result enroll(MeetingCommandContext context, String meetingId,
            String sessionSpeakerId, String displayName);

    /** 仅供已批准的结果改名流程复用；独立人物库管理不因此开放。 */
    default Result enrollAuthorized(SpeakerResultAccess access, String meetingId,
            String sessionSpeakerId, String displayName) {
        return access.ownerContext() == null ? new Rejected(Reason.UNAVAILABLE)
                : enroll(access.ownerContext(), meetingId, sessionSpeakerId, displayName);
    }

    sealed interface Result permits Enrolled, Rejected { }

    record Enrolled(SpeakerIdentityProvider.Person person, int manifestRevision) implements Result { }

    record Rejected(Reason reason) implements Result { }

    enum Reason { NOT_FOUND, ACCESS_DENIED, INVALID_STATE, NOT_GRANTED, SAMPLE_UNAVAILABLE, UNAVAILABLE }
}
