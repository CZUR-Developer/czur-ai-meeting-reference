package com.czur.reference.application.port;

import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.domain.meeting.MeetingIdPolicy;
import com.czur.reference.domain.meeting.MeetingOwnership;

/**
 * 完整 Create 首次响应使用的参考实时会话描述分配端口。
 *
 * <p>稳定身份由可信部署、已认证主体、持久赢家的会议 ID 和 Reservation ID
 * 组成。有效期内相同身份的并发或重复调用必须拿到同一描述；尚未持久化的候选
 * 到期后允许重签，不同主体或预占不得借同一会议 ID 复用令牌。该端口只分配
 * 参考会话身份，不打开模型 Driver，也不启动 WSS。首次
 * 响应仍须写入 V013；跨进程和重启重放以 V013 为权威，本地分配器不代替持久化。
 * 已持久化的描述必须从 V013 重放，不能通过本端口续期。令牌续期属于独立流程，
 * 不能通过重新调用 Create 实现。</p>
 */
public interface CreateRealtimeSessionAllocator {
    /**
     * 分配或重取进程内有效期内稳定的首次会话描述。
     *
     * @param request 来自身份及首次预占赢家的稳定归属；不含客户端 Token
     * @return 包含会议 ID 的地址、固定子协议、非空令牌和固定首次过期时间
     */
    CreateMeetingResponseFact.RealtimeSession allocate(Request request);

    /** 分配身份；诊断输出始终脱敏。 */
    record Request(String deploymentScope, String subjectScope,
                   String meetingId, String reservationId) {
        public Request {
            requireText("deploymentScope", deploymentScope);
            MeetingOwnership.requireOwnedSubjectScope(subjectScope);
            MeetingIdPolicy.requireValid(meetingId);
            if (!meetingId.matches("mtg_[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("meetingId must match the public Create protocol");
            }
            requireText("reservationId", reservationId);
        }

        @Override
        public String toString() {
            return "CreateRealtimeSessionAllocator.Request[redacted]";
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
