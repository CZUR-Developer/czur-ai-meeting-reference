package com.czur.reference.application.command;

import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.domain.meeting.MeetingOwnership;
import com.czur.reference.domain.meeting.MeetingState;

import java.util.Objects;

/**
 * 认证会议创建应用服务的稳定结果联合类型。
 *
 * <p>该类型把身份验证、权益判断、幂等冲突、只读事实矛盾、暂时不可用和本地
 * 创建失败区分开来。传输层或 Android 适配器可以据此选择 HTTP 状态、重试策略
 * 和用户提示，而不需要捕获 Provider/JDBC 异常或解析动态异常文本。结果对象
 * 不保存原始 Token、主体、幂等键、规范摘要、Grant、Reservation 或底层异常。</p>
 *
 * <p>这是一次调用的结果，不是可持久化的创建 Outcome。只有
 * {@link OwnedMeetingCreationUnitOfWork.ErrorCode} 中被工作单元确认的稳定失败
 * 才能由 {@link CreationFailure#terminal()} 标记为可以进入持久化终态；
 * {@link CreationFailure#retryable()} 与该标记必须按构造器不变量解释，不能由
 * 传输层自行推断。</p>
 */
public sealed interface AuthenticatedMeetingCreateResult
        permits AuthenticatedMeetingCreateResult.Success,
        AuthenticatedMeetingCreateResult.IdentityFailure,
        AuthenticatedMeetingCreateResult.EntitlementFailure,
        AuthenticatedMeetingCreateResult.Conflict,
        AuthenticatedMeetingCreateResult.Unavailable,
        AuthenticatedMeetingCreateResult.Inconsistent,
        AuthenticatedMeetingCreateResult.CreationFailure {

    /** 已提交创建结果的来源：首次提交或同幂等请求重放。 */
    enum SuccessCode {
        CREATED,
        REPLAYED
    }

    /** 应用编排中可被稳定记录的阶段；枚举不携带请求或异常正文。 */
    enum Stage {
        IDENTITY_VERIFICATION,
        ENTITLEMENT_AUTHORIZATION,
        MEETING_REPLAY_LOOKUP,
        USAGE_RESERVE_LOOKUP,
        MEETING_RECOVERY,
        MEETING_CREATION
    }

    /** 持久事实无法解释为本次认证创建结果的固定原因。 */
    enum InconsistencyReason {
        STORED_OUTCOME_INVALID,
        OUTCOME_MISSING_WITH_MEETING,
        OUTCOME_MISSING_WITH_BOUND_RESERVATION,
        SUCCESS_OUTCOME_FACT_MISMATCH,
        FAILURE_OUTCOME_WITH_MEETING,
        FAILURE_OUTCOME_WITH_BOUND_RESERVATION,
        RESERVATION_FACT_MISMATCH,
        STORED_RESERVE_INVALID,
        RESERVATION_REFERENCE_MISMATCH,
        OWNED_RESERVE_INVARIANT_VIOLATION
    }

    /**
     * 身份已验证、权益已检查且会议创建事实已提交的稳定结果。
     *
     * <p>{@code result} 必须是 {@link MeetingCommandResult.Operation#CREATED}，并且
     * 保留创建瞬间的 {@code CREATED} 快照。后续会议进入 RUNNING、STOPPING 或
     * 其它状态时，重放仍应返回首次创建结果，不能把当前快照塞进此对象。历史
     * {@link MeetingOwnership#LEGACY_UNOWNED_SUBJECT_SCOPE} 会议没有真实主体，
     * 不能通过认证创建入口返回。</p>
     */
    record Success(SuccessCode code, MeetingCommandResult result)
            implements AuthenticatedMeetingCreateResult {
        public Success {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(result, "result");
            if (result.operation() != MeetingCommandResult.Operation.CREATED) {
                throw new IllegalArgumentException(
                        "authenticated meeting create success requires CREATED operation");
            }
            if (result.meeting().state() != MeetingState.CREATED) {
                throw new IllegalArgumentException(
                        "authenticated meeting create success requires CREATED state");
            }
            if (!result.meeting().createdAt().equals(result.meeting().updatedAt())) {
                throw new IllegalArgumentException(
                        "authenticated meeting create success requires creation snapshot");
            }
            if (MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(
                    result.meeting().subjectScope())) {
                throw new IllegalArgumentException(
                        "authenticated meeting create success cannot be legacy unowned");
            }
        }

        /** 避免日志输出会议快照、主体、Grant、Reservation 或其它未来新增字段。 */
        @Override
        public String toString() {
            return "AuthenticatedMeetingCreateResult.Success[code=" + code + ']';
        }
    }

    /** 身份 Provider 拒绝或无法完成本次身份验证。错误只使用稳定脱敏枚举。 */
    record IdentityFailure(IdentityProviderError error)
            implements AuthenticatedMeetingCreateResult {
        public IdentityFailure {
            Objects.requireNonNull(error, "error");
        }

        /** 不依赖 Provider 错误对象未来的 toString 实现，显式冻结日志边界。 */
        @Override
        public String toString() {
            return "AuthenticatedMeetingCreateResult.IdentityFailure[code="
                    + error.code() + ", retryable=" + error.retryable() + ']';
        }
    }

    /** 权益、能力或计费 Provider 拒绝本次会议创建。 */
    record EntitlementFailure(EntitlementProviderError error)
            implements AuthenticatedMeetingCreateResult {
        public EntitlementFailure {
            Objects.requireNonNull(error, "error");
        }

        /** 错误输出只包含固定码和重试属性，不包含 Grant、额度或 Provider 正文。 */
        @Override
        public String toString() {
            return "AuthenticatedMeetingCreateResult.EntitlementFailure[code="
                    + error.code() + ", retryable=" + error.retryable() + ']';
        }
    }

    /** 相同认证范围和幂等键已经对应另一份业务意图；既有结果不得继续解释。 */
    record Conflict() implements AuthenticatedMeetingCreateResult {
        @Override
        public String toString() {
            return "AuthenticatedMeetingCreateResult.Conflict";
        }
    }

    /** 存储或其它阶段暂时不可用；是否可重试由调用方读取 retryable。 */
    record Unavailable(Stage stage, boolean retryable)
            implements AuthenticatedMeetingCreateResult {
        public Unavailable {
            Objects.requireNonNull(stage, "stage");
        }

        @Override
        public String toString() {
            return "AuthenticatedMeetingCreateResult.Unavailable[stage=" + stage
                    + ", retryable=" + retryable + ']';
        }
    }

    /** 多份持久事实发生矛盾，禁止通过重试、取消或推断结果自动修复。 */
    record Inconsistent(Stage stage, InconsistencyReason reason)
            implements AuthenticatedMeetingCreateResult {
        public Inconsistent {
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String toString() {
            return "AuthenticatedMeetingCreateResult.Inconsistent[stage=" + stage
                    + ", reason=" + reason + ']';
        }
    }

    /**
     * 会议本地创建工作单元返回的稳定失败。
     *
     * <p>{@code terminal=true} 表示本次失败已确认不会提交会议，且可以作为创建
     * 幂等终态持久化；因此可重试失败绝不能是终态。提交结果未知、暂时存储故障
     * 和内部故障没有稳定终态语义，即使当次调用选择 fail-closed，也必须保持
     * {@code terminal=false}，后续由事实回查或重试处理。</p>
     */
    record CreationFailure(
            OwnedMeetingCreationUnitOfWork.ErrorCode code,
            boolean retryable,
            boolean terminal) implements AuthenticatedMeetingCreateResult {
        public CreationFailure {
            Objects.requireNonNull(code, "code");
            if (retryable && terminal) {
                throw new IllegalArgumentException(
                        "retryable creation failure cannot be terminal");
            }
            if (terminal && !isPersistableTerminal(code)) {
                throw new IllegalArgumentException(
                        "error code cannot be marked as terminal creation failure");
            }
        }

        /** 禁止暂时存储、提交未知和内部错误冻结为持久化失败终态。 */
        private static boolean isPersistableTerminal(
                OwnedMeetingCreationUnitOfWork.ErrorCode code) {
            return code != OwnedMeetingCreationUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE
                    && code != OwnedMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN
                    && code != OwnedMeetingCreationUnitOfWork.ErrorCode.INTERNAL;
        }

        @Override
        public String toString() {
            return "AuthenticatedMeetingCreateResult.CreationFailure[code=" + code
                    + ", retryable=" + retryable + ", terminal=" + terminal + ']';
        }
    }
}
