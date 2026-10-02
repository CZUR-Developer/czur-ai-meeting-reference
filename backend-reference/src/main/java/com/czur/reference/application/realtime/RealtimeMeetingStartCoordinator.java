package com.czur.reference.application.realtime;

import com.czur.reference.application.port.MeetingStartExecutionUnitOfWork;
import com.czur.reference.application.port.ProductProfileCatalog;
import com.czur.reference.application.port.RealtimeStartFactsLookup;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 已授权实时连接的最小会议 Start 协调器。
 *
 * <p>调用前必须由实时入口核验 Token、会议归属、单流身份及凭据有效期；本类不能
 * 被未授权 WSS 请求直接调用。它只接收 meetingId，不从客户端取标题、Profile、
 * 能力或 startedAt。首次输入由 V013 赢家与 Meeting 确定，当前完整 Profile 和
 * Mock 能力由可信服务端配置提供；首次 startedAt 来自服务端时钟。V011 已存在时
 * 必须改用该事实冻结的 startedAt、标题与完整 Profile 严格重放，不能按当前配置
 * 重建历史。</p>
 *
 * <p>并发首次调用可能都先读到 V011 缺失；只有既有 Start 工作单元明确报告
 * EXECUTION_FACT_CONFLICT，且随后只读查到 V011 赢家时，才按赢家重放一次。
 * 其它错误、尤其事务提交未知，保持原分类，不猜测已提交。这里不保存音频、发送
 * ACK、打开 WSS 或改变 V011 原子事务。</p>
 */
public final class RealtimeMeetingStartCoordinator {
    private static final Logger LOGGER = Logger.getLogger(
            RealtimeMeetingStartCoordinator.class.getName());

    private final String deploymentScope;
    private final RealtimeStartFactsLookup facts;
    private final ProductProfileCatalog profiles;
    private final CapabilityDescriptor mockCapabilities;
    private final DeploymentLimits capabilityLimits;
    private final Clock clock;
    private final MeetingStartExecutionUnitOfWork startUnit;

    public RealtimeMeetingStartCoordinator(String deploymentScope,
            RealtimeStartFactsLookup facts, ProductProfileCatalog profiles,
            CapabilityDescriptor mockCapabilities, DeploymentLimits capabilityLimits,
            Clock clock, MeetingStartExecutionUnitOfWork startUnit) {
        if (deploymentScope == null || deploymentScope.isBlank()) {
            throw new IllegalArgumentException("deploymentScope is required");
        }
        this.deploymentScope = deploymentScope;
        this.facts = Objects.requireNonNull(facts, "facts");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.mockCapabilities = Objects.requireNonNull(mockCapabilities, "mockCapabilities");
        this.capabilityLimits = Objects.requireNonNull(capabilityLimits, "capabilityLimits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startUnit = Objects.requireNonNull(startUnit, "startUnit");
    }

    /**
     * 以可信事实首次开始或按 V011 重放；只返回既有 Start 的固定结果类型。
     *
     * @param meetingId 已由上游实时凭据核验的会议标识
     */
    public MeetingStartExecutionUnitOfWork.Result start(String meetingId) {
        MeetingIdPolicy.requireValid(meetingId);
        try {
            RealtimeStartFactsLookup.Result found = facts.find(deploymentScope, meetingId);
            if (found instanceof RealtimeStartFactsLookup.Rejected rejected) {
                return failure(map(rejected.reason()));
            }
            MeetingStartExecutionUnitOfWork.StartRequest request = request(meetingId, found);
            MeetingStartExecutionUnitOfWork.Result result =
                    startUnit.startCompleteCreate(request);
            if (found instanceof RealtimeStartFactsLookup.First
                    && result instanceof MeetingStartExecutionUnitOfWork.Failure failure
                    && failure.code()
                    == MeetingStartExecutionUnitOfWork.ErrorCode.EXECUTION_FACT_CONFLICT) {
                RealtimeStartFactsLookup.Result current = facts.find(deploymentScope, meetingId);
                if (current instanceof RealtimeStartFactsLookup.Started) {
                    result = startUnit.startCompleteCreate(request(meetingId, current));
                } else if (current instanceof RealtimeStartFactsLookup.Rejected rejected) {
                    return failure(map(rejected.reason()));
                }
            }
            LOGGER.log(Level.FINE, "实时会议 Start 结果 category={0}",
                    result instanceof MeetingStartExecutionUnitOfWork.Success success
                            ? success.code().name()
                            : ((MeetingStartExecutionUnitOfWork.Failure) result).code().name());
            return result;
        } catch (InputRejected rejected) {
            return failure(rejected.code);
        } catch (RuntimeException unexpected) {
            LOGGER.log(Level.WARNING, "实时会议 Start 失败 category=INTERNAL");
            return failure(MeetingStartExecutionUnitOfWork.ErrorCode.INTERNAL);
        }
    }

    private MeetingStartExecutionUnitOfWork.StartRequest request(String meetingId,
            RealtimeStartFactsLookup.Result found) {
        if (found instanceof RealtimeStartFactsLookup.Started started) {
            try {
                var execution = started.executionFact();
                // 既有 Start 的 V011 重放不读取 requested/model/limits 来重新规划。
                // StartRequest 仍要求非空集合；冻结计划已启用的实时能力满足形状
                // 要求，且不会被重放路径用于推导另一份历史请求。
                return new MeetingStartExecutionUnitOfWork.StartRequest(
                        deploymentScope, started.subjectScope(), meetingId,
                        execution.startedAt(), execution.title(),
                        execution.restoreProductProfile(),
                        execution.capabilityPlan().enabledCapabilities(),
                        mockCapabilities, capabilityLimits);
            } catch (RuntimeException corrupt) {
                throw new InputRejected(
                        MeetingStartExecutionUnitOfWork.ErrorCode.FACT_INCONSISTENT);
            }
        }
        RealtimeStartFactsLookup.First first = (RealtimeStartFactsLookup.First) found;
        ProductProfile profile;
        try {
            Optional<ProductProfile> chosen = Objects.requireNonNull(
                    profiles.find(first.profileId(), first.profileRevision()),
                    "profiles.find()");
            profile = chosen.orElse(null);
        } catch (RuntimeException unavailable) {
            throw new InputRejected(
                    MeetingStartExecutionUnitOfWork.ErrorCode.CAPABILITY_PLAN_FAILED);
        }
        if (profile == null || !profile.id().equals(first.profileId())
                || profile.revision() != first.profileRevision()) {
            throw new InputRejected(MeetingStartExecutionUnitOfWork.ErrorCode.PROFILE_MISMATCH);
        }
        Instant startedAt;
        try {
            startedAt = Objects.requireNonNull(clock.instant(), "clock.instant()")
                    .truncatedTo(ChronoUnit.MICROS);
        } catch (RuntimeException unavailable) {
            throw new InputRejected(MeetingStartExecutionUnitOfWork.ErrorCode.INTERNAL);
        }
        try {
            return new MeetingStartExecutionUnitOfWork.StartRequest(
                    deploymentScope, first.subjectScope(), meetingId, startedAt,
                    first.title(), profile, first.requestedCapabilities(),
                    mockCapabilities, capabilityLimits);
        } catch (RuntimeException malformed) {
            throw new InputRejected(
                    MeetingStartExecutionUnitOfWork.ErrorCode.CREATE_FACT_INCONSISTENT);
        }
    }

    private static MeetingStartExecutionUnitOfWork.ErrorCode map(
            RealtimeStartFactsLookup.Reason reason) {
        return switch (reason) {
            case MISSING -> MeetingStartExecutionUnitOfWork.ErrorCode.MEETING_NOT_FOUND;
            case ACCESS_DENIED -> MeetingStartExecutionUnitOfWork.ErrorCode.ACCESS_DENIED;
            case CREATE_FACT_INCONSISTENT ->
                    MeetingStartExecutionUnitOfWork.ErrorCode.CREATE_FACT_INCONSISTENT;
            case FACT_INCONSISTENT ->
                    MeetingStartExecutionUnitOfWork.ErrorCode.FACT_INCONSISTENT;
            case UNAVAILABLE -> MeetingStartExecutionUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE;
        };
    }

    private static MeetingStartExecutionUnitOfWork.Failure failure(
            MeetingStartExecutionUnitOfWork.ErrorCode code) {
        LOGGER.log(code.retryable() ? Level.WARNING : Level.FINE,
                "实时会议 Start 拒绝 category={0}", code.name());
        return new MeetingStartExecutionUnitOfWork.Failure(code);
    }

    private static final class InputRejected extends RuntimeException {
        private final MeetingStartExecutionUnitOfWork.ErrorCode code;

        private InputRejected(MeetingStartExecutionUnitOfWork.ErrorCode code) {
            this.code = code;
        }
    }
}
