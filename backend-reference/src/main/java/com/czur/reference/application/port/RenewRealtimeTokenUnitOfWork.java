package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.time.Instant;
import java.util.Objects;

/**
 * 已登录会议所有者换取短期实时 Token 的原子持久化端口。
 *
 * <p>调用方先由用户 Bearer 建立可信 {@link MeetingCommandContext}。实现必须在
 * 同一数据库事务中锁定会议、校验部署和主体归属、检查实时状态，再为新键签发
 * 并保存独立的续期事实；不能修改 V013 首次 Create 响应，也不能把用户 Bearer
 * 当作实时 Token。相同会议/主体/幂等键须从持久事实重放同一个 Grant，换键签发
 * 不同 Token；旧续期 Token 在各自到期前仍有效。提交结果不确定时只读回查，
 * 不能根据内存候选声称成功。</p>
 *
 * <p>本端口不负责 HTTP Bearer 解码、WSS 握手、事件或音频。实现从服务端可信时钟
 * 和安全随机源生成短期凭据，不接受客户端指定 Token、过期时间或部署范围。
 * 成功结果保存持久赢家的规范 JSON 原文，HTTP 只能原样写出而不得重新编码，
 * 保证同键续期在进程重启和未来编码器升级后仍逐字节重放。该 JSON 含敏感 Token，
 * 不得记录、放进异常或返回给其它业务端口。</p>
 */
public interface RenewRealtimeTokenUnitOfWork {
    /** 对当前所有者续期，或按幂等键重放已提交的续期结果。 */
    Result renew(Request request);

    /** 不含原始 Bearer、候选 Token 或客户端时间的认证输入。 */
    record Request(MeetingCommandContext context, String meetingId, String idempotencyKey) {
        public Request {
            Objects.requireNonNull(context, "context");
            MeetingIdPolicy.requireValid(meetingId);
            if (idempotencyKey == null || idempotencyKey.length() < 16
                    || idempotencyKey.length() > 128
                    || idempotencyKey.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("idempotencyKey is invalid");
            }
        }

        /** 隐藏主体、会议与幂等键。 */
        @Override
        public String toString() {
            return "RenewRealtimeTokenUnitOfWork.Request[authenticated=true]";
        }
    }

    /** 续期结果；成功分支之外绝不能携带 Token。 */
    sealed interface Result permits Success, Failure {
    }

    /** 已持久提交的新 Grant JSON 或同键原字节重放；不是待提交的候选值。 */
    record Success(SuccessCode code, String persistedGrantJson) implements Result {
        public Success {
            Objects.requireNonNull(code, "code");
            if (persistedGrantJson == null || persistedGrantJson.isBlank()) {
                throw new IllegalArgumentException("persistedGrantJson is required");
            }
        }

        @Override
        public String toString() {
            return "RenewRealtimeTokenUnitOfWork.Success[code=" + code + ",grant=REDACTED]";
        }
    }

    /** 与公开 RealtimeTokenGrant 同义的不可变续期事实。 */
    record Grant(String meetingId, String streamId, String sessionToken, Instant expiresAt) {
        public Grant {
            MeetingIdPolicy.requireValid(meetingId);
            if (!meetingId.equals(streamId)) {
                throw new IllegalArgumentException("P2 streamId must equal meetingId");
            }
            if (sessionToken == null || sessionToken.isBlank()) {
                throw new IllegalArgumentException("sessionToken is required");
            }
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        /** 绝不输出可用于 WSS 鉴权的 Token 或关联会议。 */
        @Override
        public String toString() {
            return "RenewRealtimeTokenUnitOfWork.Grant[REDACTED]";
        }
    }

    /** CREATED 表示本键首次提交；REPLAYED 必须返回同一持久 Grant。 */
    enum SuccessCode { CREATED, REPLAYED }

    /** 稳定失败，不携带 SQL、主体、Token 或幂等键。 */
    record Failure(ErrorCode code, boolean retryable) implements Result {
        public Failure {
            Objects.requireNonNull(code, "code");
            if (retryable != code.retryable()) {
                throw new IllegalArgumentException("retryable does not match error code");
            }
        }

        public Failure(ErrorCode code) {
            this(code, code.retryable());
        }
    }

    /** OUTCOME_UNKNOWN 仅用于 commit 已开始而结果无法判定。 */
    enum ErrorCode {
        NOT_FOUND(false),
        ACCESS_DENIED(false),
        STATE_CONFLICT(false),
        FACT_INCONSISTENT(false),
        STORAGE_UNAVAILABLE(true),
        OUTCOME_UNKNOWN(true),
        INTERNAL(false);

        private final boolean retryable;

        ErrorCode(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
