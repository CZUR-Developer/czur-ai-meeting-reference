package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.domain.meeting.MeetingOwnership;

import java.util.Objects;

/**
 * 将新归属会议的创建事实作为一个原子工作单元提交。
 *
 * <p>实现必须在同一个本地数据库事务中完成以下四项工作：绑定用量预占、
 * 插入 owned Meeting、保存会议创建幂等结果、追加 {@code MeetingCreated}
 * Outbox。任何一项在提交前失败都必须整体回滚。该端口不调用身份、权益、
 * 模型、对象存储或消息发布等外部系统；这些跨系统动作不得混入本地事务。</p>
 *
 * <p>本端口只冻结应用层可以依赖的结果分类，不暴露 JDBC 异常、唯一约束名、
 * SQL 文本或供应商动态正文。实现只有在 {@code Connection.commit()} 已经开始、
 * 因而无法证明事务最终提交或回滚时，才可以返回
 * {@link ErrorCode#OUTCOME_UNKNOWN}。提交之前能够确认的失败必须映射为更具体的
 * 稳定错误码。</p>
 */
public interface OwnedMeetingCreationUnitOfWork {

    /**
     * 尝试创建或重放一场具有真实主体和权益归属的会议。
     *
     * @param request 已通过身份与权益编排、且不包含原始凭据的稳定创建输入
     * @return 创建、重放或稳定失败；结果不携带动态异常正文
     */
    Result create(CreateRequest request);

    /**
     * owned 会议创建事务的完整输入。
     *
     * <p>{@code canonicalRequestDigest} 是上层根据客户端业务意图计算的规范摘要；
     * 它不得包含服务端生成的 meetingId、Grant、Reservation 或事务时间。
     * {@code reserveResult} 必须是首次 reserve 保存并可稳定重放的结果。Profile
     * ID 和 revision 只能通过 {@link #authorizedProfileSnapshot()} 从该首次授权
     * 快照取得，调用方没有第二套字段可以覆盖它们。</p>
     *
     * <p>事务时间故意不属于本请求。数据库实现应在锁定并验证 Reservation 后
     * 从注入时钟读取一次数据库微秒精度时间，并把同一个时间用于 bind、
     * Meeting、会议幂等和 Outbox，避免一次请求产生互相矛盾的时间事实。</p>
     */
    record CreateRequest(
            String deploymentScope,
            String subjectScope,
            String meetingId,
            String clientSessionId,
            String idempotencyKey,
            String canonicalRequestDigest,
            UsageOperationResult reserveResult) {

        public CreateRequest {
            requireText("deploymentScope", deploymentScope);
            requireText("subjectScope", subjectScope);
            requireText("meetingId", meetingId);
            requireText("clientSessionId", clientSessionId);
            requireText("idempotencyKey", idempotencyKey);
            requireText("canonicalRequestDigest", canonicalRequestDigest);
            Objects.requireNonNull(reserveResult, "reserveResult");
            if (MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(subjectScope)) {
                throw new IllegalArgumentException(
                        "owned meeting cannot use LEGACY_UNOWNED subject");
            }
            if (!reserveResult.supportsOwnedMeetingCreation()) {
                throw new IllegalArgumentException(
                        "reserveResult does not contain the first authorized profile snapshot");
            }
            UsageReservation reservation = reserveResult.reservation();
            if (!deploymentScope.equals(reserveResult.deploymentScope())
                    || !subjectScope.equals(reserveResult.subjectScope())
                    || !deploymentScope.equals(reservation.deploymentScope())
                    || !subjectScope.equals(reservation.subjectScope())) {
                throw new IllegalArgumentException(
                        "request scope does not match reserveResult scope");
            }
            if (!meetingId.equals(reservation.expectedMeetingId())) {
                throw new IllegalArgumentException(
                        "meetingId does not match reservation expectedMeetingId");
            }
            if (reservation.state() != UsageReservation.State.RESERVED
                    || reservation.isBound()
                    || reservation.version() != 0) {
                throw new IllegalArgumentException(
                        "reserveResult must contain the initial unbound reservation snapshot");
            }
        }

        /** 返回首次授权时冻结的 Profile 与能力，绝不重新查询当前策略。 */
        public AuthorizedProfileSnapshot authorizedProfileSnapshot() {
            return reserveResult.requireAuthorizedProfileSnapshot();
        }

        /** 便于实现构造 Meeting；值只派生自首次授权快照。 */
        public String profileId() {
            return authorizedProfileSnapshot().profileId();
        }

        /** 便于实现构造 Meeting；值只派生自首次授权快照。 */
        public int profileRevision() {
            return authorizedProfileSnapshot().profileRevision();
        }

        /**
         * 避免 record 默认输出主体、幂等摘要及 Reservation 正文。
         */
        @Override
        public String toString() {
            return "CreateRequest[meetingId=" + meetingId
                    + ", profileId=" + profileId()
                    + ", profileRevision=" + profileRevision() + ']';
        }
    }

    /** 稳定结果联合类型；调用方必须显式处理成功与失败两个分支。 */
    sealed interface Result permits Success, Failure {
    }

    /**
     * 已提交成功的稳定结果。
     *
     * <p>{@link SuccessCode#CREATED} 表示本次事务首次建立事实，
     * {@link SuccessCode#REPLAYED} 表示复用了此前已提交的幂等结果。两者都返回
     * 同一份稳定 MeetingCommandResult，不能因重放重新生成 Profile 或事件。</p>
     */
    record Success(SuccessCode code, MeetingCommandResult result) implements Result {
        public Success {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(result, "result");
            if (result.operation() != MeetingCommandResult.Operation.CREATED) {
                throw new IllegalArgumentException(
                        "owned meeting creation success requires CREATED command result");
            }
            if (MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(
                    result.meeting().subjectScope())) {
                throw new IllegalArgumentException(
                        "owned meeting creation cannot return legacy meeting");
            }
        }

        /** 避免 record 默认输出主体、Grant 和 Reservation 归属正文。 */
        @Override
        public String toString() {
            return "Success[code=" + code
                    + ", meetingId=" + result.meeting().meetingId() + ']';
        }
    }

    /**
     * 未提交成功的稳定结果。错误只包含机器可判断的 code 和重试提示，禁止附带
     * SQL、连接地址、主体、幂等摘要、Reservation 正文或动态异常消息。
     */
    record Failure(ErrorCode code, boolean retryable) implements Result {
        public Failure {
            Objects.requireNonNull(code, "code");
        }
    }

    enum SuccessCode {
        CREATED,
        REPLAYED
    }

    /**
     * owned 创建工作单元的冻结错误集合。
     *
     * <p>{@code STORAGE_UNAVAILABLE} 表示尚未进入不可判定提交阶段的暂时存储
     * 故障；{@code OUTCOME_UNKNOWN} 仅表示 commit 已开始但结果无法确认。后续
     * 编排必须先回查事实，不能因为后一个错误直接取消 Reservation。</p>
     */
    enum ErrorCode {
        IDEMPOTENCY_CONFLICT,
        RESERVATION_NOT_FOUND,
        RESERVATION_EXPIRED,
        RESERVATION_STATE_CONFLICT,
        VERSION_CONFLICT,
        MEETING_CONFLICT,
        STORAGE_UNAVAILABLE,
        OUTCOME_UNKNOWN,
        INTERNAL
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
