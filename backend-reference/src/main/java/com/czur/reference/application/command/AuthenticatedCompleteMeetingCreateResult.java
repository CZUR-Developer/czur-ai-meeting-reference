package com.czur.reference.application.command;

import com.czur.reference.application.port.CompleteMeetingCreationUnitOfWork;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.identity.IdentityProviderError;

import java.util.Objects;

/**
 * 完整 Create 应用编排的稳定结果，不复用旧版窄请求的创建结果类型。
 *
 * <p>只有 {@link Success} 携带 V013 已提交或只读回查的首次响应；候选响应、
 * 原始凭据、Grant 和 Reservation 永不进入失败结果。传输层应按类型和固定枚举
 * 映射错误，不能解析 Provider 或数据库异常正文。所有诊断输出均省略业务标识、
 * 实时 URL 和 Token。</p>
 */
public sealed interface AuthenticatedCompleteMeetingCreateResult
        permits AuthenticatedCompleteMeetingCreateResult.Success,
        AuthenticatedCompleteMeetingCreateResult.IdentityFailure,
        AuthenticatedCompleteMeetingCreateResult.EntitlementFailure,
        AuthenticatedCompleteMeetingCreateResult.Conflict,
        AuthenticatedCompleteMeetingCreateResult.Inconsistent,
        AuthenticatedCompleteMeetingCreateResult.Unavailable,
        AuthenticatedCompleteMeetingCreateResult.PreparationFailure,
        AuthenticatedCompleteMeetingCreateResult.CreationFailure {

    /** 本次实际提交，或以 V013 原响应完成的重放。 */
    enum SuccessCode { CREATED, REPLAYED }

    /** 用于诊断和公开错误映射的固定阶段，不包含可识别主体。 */
    enum Stage {
        IDENTITY,
        CREATE_REPLAY,
        PROFILE,
        RESERVE_LOOKUP,
        ENTITLEMENT,
        RESPONSE_ASSEMBLY,
        CREATE_COMMIT,
        CREATE_RECOVERY
    }

    /** 只有已持久化的响应可以进入此成功分支。 */
    record Success(SuccessCode code, CreateMeetingResponseFact response)
            implements AuthenticatedCompleteMeetingCreateResult {
        public Success {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(response, "response");
        }

        @Override public String toString() {
            return "AuthenticatedCompleteMeetingCreateResult.Success[code=" + code
                    + ",response=redacted]";
        }
    }

    /** 身份验证或 CREATE 权限拒绝。 */
    record IdentityFailure(IdentityProviderError error)
            implements AuthenticatedCompleteMeetingCreateResult {
        public IdentityFailure { Objects.requireNonNull(error, "error"); }
    }

    /** 开会资格或预占失败；不包含计费规则和动态响应正文。 */
    record EntitlementFailure(EntitlementProviderError error)
            implements AuthenticatedCompleteMeetingCreateResult {
        public EntitlementFailure { Objects.requireNonNull(error, "error"); }
    }

    /** 同范围、幂等键已有不同的完整 Create 请求。 */
    record Conflict() implements AuthenticatedCompleteMeetingCreateResult {
    }

    /** 已保存事实或可信配置不自洽，不能继续创建。 */
    record Inconsistent(Stage stage) implements AuthenticatedCompleteMeetingCreateResult {
        public Inconsistent { Objects.requireNonNull(stage, "stage"); }
    }

    /** 只读事实源不可用，不允许视为未命中或推断其可重试性。 */
    record Unavailable(Stage stage)
            implements AuthenticatedCompleteMeetingCreateResult {
        public Unavailable { Objects.requireNonNull(stage, "stage"); }
    }

    /** 首次响应组装拒绝；该分支从不持有候选 Token。 */
    record PreparationFailure(CompleteMeetingResponseAssembler.FailureCode code)
            implements AuthenticatedCompleteMeetingCreateResult {
        public PreparationFailure { Objects.requireNonNull(code, "code"); }
    }

    /** 事务工作单元拒绝或提交结果未知；不携带候选响应。 */
    record CreationFailure(CompleteMeetingCreationUnitOfWork.ErrorCode code,
                           boolean retryable)
            implements AuthenticatedCompleteMeetingCreateResult {
        public CreationFailure { Objects.requireNonNull(code, "code"); }
    }
}
