package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.domain.meeting.MeetingOwnership;
import com.czur.reference.domain.meeting.MeetingState;

import java.util.Objects;

/**
 * authenticated-create 可持久化并稳定重放的终态结果。
 *
 * <p>成功分支保存首次提交的 {@link MeetingCommandResult}；失败分支只保存
 * {@link MeetingCreateFailure}。首次执行还是重放属于本次调用元数据，不进入
 * Outcome 本身。该联合类型不携带幂等范围、键或摘要，它们由
 * {@link MeetingCreateOutcomeStore.OutcomeRecord} 单独绑定。</p>
 */
public sealed interface MeetingCreateOutcome
        permits MeetingCreateOutcome.Success, MeetingCreateOutcome.Failure {

    /**
     * 创建成功的稳定结果。
     *
     * <p>成功结果必须保留会议创建事务刚提交时的原始快照：操作为
     * {@code CREATED}、会议状态为 {@code CREATED}，并且 {@code updatedAt}
     * 等于 {@code createdAt}。后续已经进入运行、停止或仅更新时间发生变化的
     * 当前会议快照，都不能替代首次创建结果。</p>
     *
     * <p>普通构造还拒绝没有真实主体归属的历史会议；只有
     * {@link MeetingCreateOutcome#restoreHistoricalSuccess(MeetingCommandResult)}
     * 可以恢复 v1 只读事实，供升级后明确识别并由 authenticated-create 上层
     * fail closed，禁止把历史匿名结果冒充当前主体的成功。历史恢复只放宽归属，
     * 不放宽上述创建瞬间快照规则。</p>
     */
    final class Success implements MeetingCreateOutcome {
        private final MeetingCommandResult result;

        public Success(MeetingCommandResult result) {
            this(result, false);
        }

        private Success(MeetingCommandResult result, boolean allowLegacyRestore) {
            this.result = Objects.requireNonNull(result, "result");
            if (result.operation() != MeetingCommandResult.Operation.CREATED) {
                throw new IllegalArgumentException(
                        "meeting create success requires CREATED command result");
            }
            if (result.meeting().state() != MeetingState.CREATED) {
                throw new IllegalArgumentException(
                        "meeting create success requires CREATED meeting state");
            }
            if (!result.meeting().createdAt().equals(result.meeting().updatedAt())) {
                throw new IllegalArgumentException(
                        "meeting create success requires unchanged creation timestamp");
            }
            if (!allowLegacyRestore && MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(
                    result.meeting().subjectScope())) {
                throw new IllegalArgumentException(
                        "authenticated meeting create success requires owned meeting");
            }
        }

        public MeetingCommandResult result() {
            return result;
        }

        /** 避免默认 record 输出主体、Grant、Reservation 和完整会议快照。 */
        @Override
        public String toString() {
            return "MeetingCreateOutcome.Success[result=redacted]";
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof Success success && result.equals(success.result);
        }

        @Override
        public int hashCode() {
            return result.hashCode();
        }
    }

    /** 已确认不会提交会议的不可重试稳定失败。 */
    record Failure(MeetingCreateFailure failure) implements MeetingCreateOutcome {
        public Failure {
            Objects.requireNonNull(failure, "failure");
        }

        @Override
        public String toString() {
            return "MeetingCreateOutcome.Failure[code=" + failure.code() + ']';
        }
    }

    /** 将已提交的会议创建结果包装为稳定成功 Outcome。 */
    static Success success(MeetingCommandResult result) {
        return new Success(result);
    }

    /**
     * 仅供持久化适配器恢复 v1 历史成功正文；普通业务代码不得调用。
     * 返回值仍保留 {@code LEGACY_UNOWNED}，不会补造当前主体或权益引用。
     */
    static Success restoreHistoricalSuccess(MeetingCommandResult result) {
        if (!MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(
                Objects.requireNonNull(result, "result").meeting().subjectScope())) {
            throw new IllegalArgumentException("historical success must be legacy unowned");
        }
        return new Success(result, true);
    }

    /** 将已确认的不可重试失败包装为稳定失败 Outcome。 */
    static Failure failure(MeetingCreateFailure failure) {
        return new Failure(failure);
    }
}
