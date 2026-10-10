package com.czur.reference.application.port;

import java.util.Objects;

/**
 * 已验证实时凭据的会议事件补拉端口。返回值与 REST 事件页完全相同，适配器
 * 必须复用同一持久查询和删除撤权规则；本端口不接收客户端声称的部署或主体。
 * 调用方须在每次补拉前重新验证实时凭据及期限，不能复用过期授权。
 */
public interface RealtimeEventLogPort {
    MeetingEventLogPort.Result read(Request request);

    /** 从当前实时凭据核验结果读取该凭据所绑定会议的全局事件页。 */
    record Request(RealtimeCredentialVerifier.Granted grant, long afterSequence, int limit) {
        public Request {
            Objects.requireNonNull(grant, "grant");
            if (afterSequence < 0) {
                throw new IllegalArgumentException("afterSequence must not be negative");
            }
            if (limit < 1 || limit > 1000) {
                throw new IllegalArgumentException("limit must be between 1 and 1000");
            }
        }

        @Override
        public String toString() {
            return "RealtimeEventLogPort.Request[afterSequence=" + afterSequence
                    + ", limit=" + limit + ", verified=true]";
        }
    }
}
