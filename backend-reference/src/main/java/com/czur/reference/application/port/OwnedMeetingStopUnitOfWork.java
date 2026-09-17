package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.domain.meeting.MeetingOwnership;
import com.czur.reference.domain.meeting.MeetingState;

import java.util.Objects;

/**
 * 将一场具有真实归属的会议停止意图作为单个本地数据库事务提交。
 *
 * <p>实现必须先锁定会议并校验部署与主体归属，然后才允许读取该主体的停止
 * 幂等记录。首次从 {@code STARTING}/{@code RUNNING} 接受停止时，会议状态、
 * 幂等结果和 {@code MeetingStopRequested} Outbox 必须一起提交；已经处于
 * {@code STOPPING}/{@code FINALIZING} 的会议只需保存本次新幂等键的稳定结果，
 * 不得重复更新会议或追加事件。</p>
 *
 * <p>该端口只表达认证后的数据库边界，不校验当前身份是否具有 Stop 权限，
 * 也不接收原始 Token、密码或请求时间。权限检查属于上层认证命令服务；事务
 * 时间必须由实现锁定会议后读取一次。Grant 到期不会撤销既有会议归属，因此
 * 也不能阻止所有者停止会议。</p>
 *
 * <p>返回值只包含稳定错误码和重试标志，不携带 JDBC 异常、SQL、主体、幂等
 * 键/摘要或动态错误正文。只有 {@code Connection.commit()} 调用已经开始、
 * 因而无法确认最终提交或回滚时，才允许返回
 * {@link ErrorCode#OUTCOME_UNKNOWN}。</p>
 */
public interface OwnedMeetingStopUnitOfWork {

    /**
     * 尝试接受或重放一场 owned 会议的停止意图。
     *
     * @param request 已完成身份验证且不包含原始凭据和客户端时间的停止输入
     * @return 已提交的接受/重放结果，或不带动态异常正文的稳定失败
     */
    Result stop(StopRequest request);

    /**
     * owned 停止事务的完整输入。
     *
     * <p>{@code canonicalRequestDigest} 必须由上层按公开停止意图计算，只包含
     * 协议版本和 meetingId；幂等键、身份、部署范围、服务端时间和凭据都不得
     * 混入摘要。{@link MeetingCommandContext} 只包含服务端部署范围和已经验证
     * 的身份，不代表这里已经完成权限检查。</p>
     */
    record StopRequest(
            MeetingCommandContext context,
            String meetingId,
            String idempotencyKey,
            String canonicalRequestDigest) {

        public StopRequest {
            Objects.requireNonNull(context, "context");
            requireText("meetingId", meetingId);
            requireText("idempotencyKey", idempotencyKey);
            requireText("canonicalRequestDigest", canonicalRequestDigest);
        }

        /**
         * 返回不包含部署、主体、身份声明、幂等键或摘要的安全诊断文本。
         */
        @Override
        public String toString() {
            return "StopRequest[meetingId=" + meetingId
                    + ", authenticated=true]";
        }
    }

    /** 稳定结果联合类型；调用方必须显式处理成功与失败两个分支。 */
    sealed interface Result permits Success, Failure {
    }

    /**
     * 已经提交成功的停止结果。
     *
     * <p>{@link SuccessCode#ACCEPTED} 表示本次键首次保存了停止结果；会议可能
     * 刚进入 {@code STOPPING}，也可能原本已经处于
     * {@code STOPPING}/{@code FINALIZING}。{@link SuccessCode#REPLAYED} 表示返回
     * 相同键和摘要此前提交的稳定结果。两者都必须携带
     * {@link MeetingCommandResult.Operation#STOP_ACCEPTED}。</p>
     */
    record Success(SuccessCode code, MeetingCommandResult result) implements Result {
        public Success {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(result, "result");
            if (result.operation() != MeetingCommandResult.Operation.STOP_ACCEPTED) {
                throw new IllegalArgumentException(
                        "owned meeting stop success requires STOP_ACCEPTED result");
            }
            if (MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(
                    result.meeting().subjectScope())) {
                throw new IllegalArgumentException(
                        "owned meeting stop cannot return legacy meeting");
            }
            if (result.meeting().state() != MeetingState.STOPPING
                    && result.meeting().state() != MeetingState.FINALIZING) {
                throw new IllegalArgumentException(
                        "owned meeting stop result must be STOPPING or FINALIZING");
            }
        }

        /** 避免 record 默认输出主体、Grant 和 Reservation 归属正文。 */
        @Override
        public String toString() {
            return "Success[code=" + code
                    + ", meetingId=" + result.meeting().meetingId()
                    + ", state=" + result.meeting().state() + ']';
        }
    }

    /**
     * 未提交成功的稳定失败。retryable 由错误码唯一决定，调用方不能自行覆盖。
     */
    record Failure(ErrorCode code, boolean retryable) implements Result {
        public Failure {
            Objects.requireNonNull(code, "code");
            if (retryable != code.retryable()) {
                throw new IllegalArgumentException(
                        "retryable must match the stable error-code policy");
            }
        }

        /** 按冻结错误策略构造失败，避免调用方重复判断重试语义。 */
        public Failure(ErrorCode code) {
            this(Objects.requireNonNull(code, "code"), code.retryable());
        }
    }

    enum SuccessCode {
        ACCEPTED,
        REPLAYED
    }

    /**
     * owned 停止工作单元的冻结错误集合。
     *
     * <p>只有临时存储故障和提交结果未知允许重试。访问拒绝、状态非法和幂等
     * 冲突都是确定业务结果；{@code INTERNAL} 表示持久事实或实现偏离契约，
     * 也不能由客户端盲目重试。</p>
     */
    enum ErrorCode {
        MEETING_NOT_FOUND(false),
        ACCESS_DENIED(false),
        IDEMPOTENCY_CONFLICT(false),
        INVALID_MEETING_STATE(false),
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

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
