package com.czur.reference.application.port;

import com.czur.reference.domain.meeting.MeetingIdPolicy;
import com.czur.reference.domain.meeting.MeetingOwnership;

import java.util.Objects;

/**
 * 原子接管会议输入并注册最终收口工作流的应用端口。
 *
 * <p>首次成功必须在同一个本地数据库事务中完成：核对会议归属和冻结的执行事实，
 * 持久化完整 V008 输入事实，只从该事实形成 V009 最终用量，生成稳定 receipt，
 * 冻结最终收口 checkpoint，注册持久 Workflow，并把会议推进到
 * {@code FINALIZING + INPUT_ACCEPTED + GENERATING}。任何一步失败都必须整体回滚，
 * 不能留下可被 Worker 误认为已经受理的半状态。</p>
 *
 * <p>等价重试必须只读取已经提交的 V008、V009、execution fact、receipt 和 Workflow
 * checkpoint，并验证它们仍然互相一致。重放不得重新运行能力规划、读取当前 Profile、
 * 读取当前时钟或重新生成 receipt。提交确认未知时，实现必须废弃对应数据库连接；
 * 调用方随后使用同一个显式请求重试，由持久事实裁决首次成功或稳定重放。</p>
 *
 * <p>该端口只编排本地持久事实，不调用模型、Artifact Store、对象存储或权益 Provider，
 * 也不发布没有消费者契约的 Outbox 事件。</p>
 */
public interface FinalizationAdmissionUnitOfWork {

    /** 接管输入并注册最终收口工作流，或按已提交事实稳定重放。 */
    Result admit(AdmissionRequest request);

    /**
     * 在提交确认未知后执行独立的只读恢复。
     *
     * <p>实现只能读取和核对持久事实，不得写入 V008/V009、生成 receipt、运行
     * Planner、注册 Workflow 或推进 Manifest。返回 {@link ErrorCode#ADMISSION_NOT_FOUND}
     * 表示数据库已经确认不存在完整 Admission；它不是“继续重试 recover”的临时
     * 错误。调用方必须按该明确 code 结束恢复分支，再另行调用
     * {@link #admit(AdmissionRequest)} 开始一次新的首次尝试。</p>
     */
    Result recover(AdmissionRequest request);

    /**
     * Admission 的完整可信输入。
     *
     * <p>部署与主体仅用于核对数据库中的 owned Meeting，不能改变会议归属。
     * {@code inputFact} 必须完整属于同一会议；其接管时间必须是 PostgreSQL
     * {@code timestamptz(6)} 可以无损保存的微秒精度值。</p>
     */
    record AdmissionRequest(
            String deploymentScope,
            String subjectScope,
            String meetingId,
            FinalizationInputFact inputFact) {
        public AdmissionRequest {
            requireText("deploymentScope", deploymentScope);
            subjectScope = MeetingOwnership.requireOwnedSubjectScope(subjectScope);
            meetingId = MeetingIdPolicy.requireValid(meetingId);
            Objects.requireNonNull(inputFact, "inputFact");
            if (!meetingId.equals(inputFact.meetingId())) {
                throw new IllegalArgumentException(
                        "meetingId must match inputFact.meetingId");
            }
            if (inputFact.acceptedAt().getNano() % 1_000 != 0) {
                throw new IllegalArgumentException(
                        "inputFact.acceptedAt must use PostgreSQL microsecond precision");
            }
        }

        /** 不输出会议、主体、binding、语言或摘要。 */
        @Override
        public String toString() {
            return "FinalizationAdmissionUnitOfWork.AdmissionRequest[redacted]";
        }
    }

    sealed interface Result permits Success, Failure {
    }

    /**
     * 已确认提交或已由完整持久事实证明的成功结果。
     *
     * <p>结果只返回调用方继续跟踪所需的 receipt、最终用量和稳定 Workflow key；
     * 完整 checkpoint 只保存在持久 Workflow 中，避免形成第二份可漂移的数据源。</p>
     */
    record Success(
            SuccessCode code,
            FinalizationFact finalization,
            FinalUsageFact finalUsage,
            WorkflowStep.Key workflowKey) implements Result {
        public Success {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(finalization, "finalization");
            Objects.requireNonNull(finalUsage, "finalUsage");
            Objects.requireNonNull(workflowKey, "workflowKey");
            if (!finalization.isAccepted()) {
                throw new IllegalArgumentException(
                        "Admission success requires accepted input");
            }
            if (!finalUsage.meetingId().equals(workflowKey.meetingId())) {
                throw new IllegalArgumentException(
                        "final usage and workflow must belong to the same meeting");
            }
        }

        /** 不输出 receipt、会议、Workflow key 或输入来源。 */
        @Override
        public String toString() {
            return "FinalizationAdmissionUnitOfWork.Success[code=" + code
                    + ", finalization=redacted, finalUsage=redacted, workflow=redacted]";
        }
    }

    /** 不携带 SQL、连接地址、动态正文或任何业务标识的固定失败。 */
    record Failure(ErrorCode code, boolean retryable) implements Result {
        public Failure {
            Objects.requireNonNull(code, "code");
            if (retryable != code.retryable()) {
                throw new IllegalArgumentException(
                        "retryable must match the stable error-code policy");
            }
        }

        public Failure(ErrorCode code) {
            this(Objects.requireNonNull(code, "code"), code.retryable());
        }
    }

    enum SuccessCode {
        ADMITTED,
        REPLAYED
    }

    /**
     * Admission 的稳定错误分类。只有临时存储不可用和提交结果未知允许自动重试。
     */
    enum ErrorCode {
        MEETING_NOT_FOUND(false),
        ACCESS_DENIED(false),
        PROFILE_MISMATCH(false),
        INVALID_MEETING_STATE(false),
        EXECUTION_FACT_MISSING(false),
        INPUT_FACT_CONFLICT(false),
        FINAL_USAGE_CONFLICT(false),
        RESERVATION_INCONSISTENT(false),
        RECEIPT_GENERATION_FAILED(false),
        WORKFLOW_CONFLICT(false),
        MANIFEST_CONFLICT(false),
        FACT_INCONSISTENT(false),
        ADMISSION_NOT_FOUND(false),
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
        MeetingIdPolicy.utf8Length(value);
    }
}
