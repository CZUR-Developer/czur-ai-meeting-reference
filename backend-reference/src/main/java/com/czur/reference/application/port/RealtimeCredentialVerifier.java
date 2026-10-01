package com.czur.reference.application.port;

import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.time.Instant;
import java.util.Objects;

/**
 * 已持久化实时会话凭据的只读核验端口。
 *
 * <p>实现必须在同一个一致性快照中读取会议归属、生命周期/输入状态、V013 首次
 * Create 响应以及另存的有效续期事实；只接受与会议及其稳定逻辑流绑定的未过期
 * Token。V013 候选分配器的进程缓存、用户登录 Bearer 和旧 v1 会议均不能代替
 * 持久会话事实。Token 比较必须使用恒时摘要比较；数据库异常或损坏事实不能
 * 回退到缓存或放行。</p>
 *
 * <p>本端口不写会议、续期事实或音频，也不创建 WSS 连接。调用方持有并关闭
 * {@link Request#credential()}；实现只能在同步调用栈内读取，不得保留、日志记录
 * 或返回凭据。已建立连接还须在每次输入前检查有效期限和写入状态，不能把握手
 * 时的 {@link Granted} 无限期缓存成授权。</p>
 */
public interface RealtimeCredentialVerifier {
    /** 对一次拟建立或恢复的实时连接执行持久事实核验。 */
    Result verify(Request request);

    /** 会议、逻辑流与短生命周期凭据；不携带用户 Bearer。 */
    record Request(String meetingId, String streamId, SecretCredential credential) {
        public Request {
            MeetingIdPolicy.requireValid(meetingId);
            MeetingIdPolicy.requireValid(streamId);
            Objects.requireNonNull(credential, "credential");
        }

        /** 不输出 Token 或会议标识，避免调用方直接记录入站对象。 */
        @Override
        public String toString() {
            return "RealtimeCredentialVerifier.Request[credential=REDACTED]";
        }
    }

    /** 所有结果均不携带 Token 或数据库/身份正文。 */
    sealed interface Result permits Granted, Denied, FactInconsistent, Unavailable {
    }

    /** 仅由有效凭据和一致持久快照产生，授权期限为独占截止时刻。 */
    record Granted(AccessMode access, Instant expiresAt) implements Result {
        public Granted {
            Objects.requireNonNull(access, "access");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /** 凭据已经通过核验后的最小用途；拒绝/坏事实不能表示为 Granted。 */
    enum AccessMode {
        /** 可按后续持久音频游标和 v2 Start 规则写入。 */
        WRITE,
        /** 只允许按持久事件游标补拉，不允许音频和完成声明。 */
        READ_ONLY
    }

    /** 无匹配凭据、错会议/流、过期、旧 v1 或合法但禁止实时连接，统一拒绝。 */
    record Denied() implements Result {
    }

    /** 同域持久事实损坏或状态组合不一致；入口关闭式映射冲突/内部错误。 */
    record FactInconsistent() implements Result {
    }

    /** 数据库故障；不可把此结果解释为不存在或使用进程缓存放行。 */
    record Unavailable(boolean retryable) implements Result {
    }
}
