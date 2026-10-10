package com.czur.reference.application.port;

import com.czur.reference.application.command.CreateMeetingRequestFact;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.domain.meeting.MeetingOwnership;

import java.util.Objects;

/**
 * 完整 Create 事实的本地原子提交端口，与旧版 owned 创建端口并行。
 *
 * <p>调用方须先完成身份、权益 reserve、能力规划及实时会话分配，并把确定的
 * 请求/响应事实传入本端口。实现只在一个本地数据库事务中绑定 Reservation、
 * 插入 Meeting、保存 V013 唯一 Create 事实并追加 Outbox；不得调用外部服务。
 * 相同 scope/key/digest 必须返回持久化的原响应，不能使用本次传入的候选响应
 * 重新生成令牌或时间。旧 {@code meeting.create.v1} 幂等表不属于此端口。</p>
 */
public interface CompleteMeetingCreationUnitOfWork {
    /**
     * 同步提交首次 Create，或按服务端范围和幂等键重放/拒绝。
     *
     * <p>调用者传入已组装且不含原始凭据的事实；实现每次调用独占一条事务连接，
     * 方法返回前结束并关闭该连接，不把连接或事务跨线程传递。并发调用可共享实现
     * 实例，但同一 scope/key 必须由数据库串行化。结果只含 CREATED、REPLAYED
     * 或稳定失败码；OUTCOME_UNKNOWN 表示提交结果不可判定，调用者须先回查 V013。
     * 未来增加新的响应字段或能力计划时，应版本化事实/协议并保持旧响应重放，
     * 不得原地重写旧 v1 幂等结果。</p>
     *
     * @param request 完整请求/首次响应和首次 reserve 的确定性候选事实
     * @return 已提交的原响应、原响应重放，或不含动态数据库正文的失败
     */
    Result create(CreateRequest request);

    /**
     * 已组装的确定性输入。构造校验只防止自相矛盾的候选事实，不能代替事务内
     * 从数据库严格读回首次 reserve 结果并锁定当前 Reservation。
     */
    record CreateRequest(
            String deploymentScope,
            String subjectScope,
            CreateMeetingRequestFact requestFact,
            CreateMeetingResponseFact responseFact,
            UsageOperationResult reserveResult) {
        public CreateRequest {
            requireText("deploymentScope", deploymentScope);
            MeetingOwnership.requireOwnedSubjectScope(subjectScope);
            Objects.requireNonNull(requestFact, "requestFact");
            Objects.requireNonNull(responseFact, "responseFact");
            Objects.requireNonNull(reserveResult, "reserveResult");
            if (!reserveResult.supportsOwnedMeetingCreation()) {
                throw new IllegalArgumentException("first reserve result is required");
            }
            UsageReservation reservation = reserveResult.reservation();
            AuthorizedProfileSnapshot profile =
                    reserveResult.requireAuthorizedProfileSnapshot();
            if (!deploymentScope.equals(reserveResult.deploymentScope())
                    || !subjectScope.equals(reserveResult.subjectScope())
                    || !deploymentScope.equals(reservation.deploymentScope())
                    || !subjectScope.equals(reservation.subjectScope())
                    || !responseFact.meetingId().equals(reservation.expectedMeetingId())
                    || reservation.state() != UsageReservation.State.RESERVED
                    || reservation.isBound() || reservation.version() != 0) {
                throw new IllegalArgumentException("Create scope or reservation is inconsistent");
            }
            if (!responseFact.acceptedProfile().id().equals(profile.profileId())
                    || responseFact.acceptedProfile().revision() != profile.profileRevision()
                    || !requestFact.requestSnapshot().profile().id().equals(profile.profileId())
                    || requestFact.requestSnapshot().profile().revision()
                            != profile.profileRevision()
                    || !responseFact.grantedCapabilities().equals(profile.grantedCapabilities())
                    || !requestFact.requestSnapshot().requestedCapabilities()
                            .equals(profile.requestedCapabilities())
                    || !responseFact.acceptedAudioProfile()
                            .equals(requestFact.requestSnapshot().audioProfile())
                    || responseFact.limits().maxMeetingDurationMs() > profile.maxDurationMs()) {
                throw new IllegalArgumentException("Create capability/profile facts are inconsistent");
            }
            long frameBytes = (long) responseFact.acceptedAudioProfile().sampleRateHz()
                    * responseFact.acceptedAudioProfile().channelCount() * 2
                    * responseFact.acceptedAudioProfile().frameDurationMs() / 1_000;
            if (responseFact.limits().maxAudioFrameBytes() < frameBytes
                    || responseFact.capabilityDecisions().stream().anyMatch(decision ->
                            decision.action()
                                    == com.czur.reference.domain.capability.CapabilityDecision
                                            .Action.FAIL_MEETING)) {
                throw new IllegalArgumentException("Create response cannot support meeting start");
            }
        }

        /** 防止日志默认输出主体、请求字段或实时令牌。 */
        @Override
        public String toString() {
            return "CreateRequest[requestPresent=true,responsePresent=true,reservePresent=true]";
        }
    }

    /** 调用方必须穷尽处理首次成功、重放和失败。 */
    sealed interface Result permits Success, Failure {
    }

    /** 成功时仅返回 V013 保存的响应事实；即使重放时传入另一候选响应也不使用它。 */
    record Success(SuccessCode code, CreateMeetingResponseFact response) implements Result {
        public Success {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(response, "response");
        }

        @Override
        public String toString() {
            return "Success[code=" + code + ",response=redacted]";
        }
    }

    /** 稳定错误分类，不传递 SQL、scope、key、Token 或驱动正文。 */
    record Failure(ErrorCode code, boolean retryable) implements Result {
        public Failure {
            Objects.requireNonNull(code, "code");
        }
    }

    enum SuccessCode { CREATED, REPLAYED }

    /** commit 已开始后无法证明结果时才允许返回 OUTCOME_UNKNOWN。 */
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
