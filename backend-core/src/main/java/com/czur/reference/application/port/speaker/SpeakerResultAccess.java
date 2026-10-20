package com.czur.reference.application.port.speaker;

import com.czur.reference.application.command.MeetingAccessGuard;
import com.czur.reference.application.command.MeetingCommandContext;

/**
 * 服务端已批准的发言人结果编辑范围，仅用于改名及其附带的跨会议登记。
 * 链接访客不冒充会议所有者；数据库仍按真实会议归属选择人物库。
 * 此对象由宿主授权策略创建，不能从请求正文反序列化，也不用于建会、停止或删除。
 */
@FunctionalInterface
public interface SpeakerResultAccess {
    boolean allows(String meetingId, String storedDeploymentScope, String storedSubjectScope);

    /** 仅原所有者路径携带真实认证上下文；链接访客始终返回 null。 */
    default MeetingCommandContext ownerContext() { return null; }

    /** 兼容原有已登录所有者调用；仍使用公共归属校验。 */
    static SpeakerResultAccess owner(MeetingCommandContext context) {
        return new SpeakerResultAccess() {
            @Override public MeetingCommandContext ownerContext() { return context; }
            @Override public boolean allows(String meetingId, String deployment, String subject) {
                return MeetingAccessGuard.evaluate(context, deployment, subject) == MeetingAccessGuard.Decision.ALLOWED;
            }
        };
    }
}
