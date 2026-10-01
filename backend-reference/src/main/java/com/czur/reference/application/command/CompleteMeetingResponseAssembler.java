package com.czur.reference.application.command;

import com.czur.reference.application.port.CreateRealtimeSessionAllocator;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingState;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 将首次持久预占赢家与可信能力配置组装为完整 Create 成功响应。
 *
 * <p>本类不负责认证、权益预占、数据库或 HTTP；调用方须先读回首次赢家，并传入
 * 已验证主体、部署以及可信的 Product Profile、模型能力和部署限制。能力计划
 * 固定版本 1，原样进入响应事实；只有计划可执行且实时字幕明确 ENABLE 时才
 * 分配本地参考会话。请求音频格式不隐式重采样，物理帧大小必须由可信部署上限
 * 容纳。会议时长上限是首次授权 Profile 与部署上限的较小值，而不是首次用量
 * 预估；首次预估仅由赢家自洽校验处理。</p>
 *
 * <p>本类生成的是 T16 接入前的参考会话描述，尚无实际 WSS 连接或令牌校验。
 * 上层必须把响应交给 V013 原子事务持久化；并发败者以 V013 原响应为准，不能
 * 将临时候选 Token 作为权威对外返回。</p>
 */
public final class CompleteMeetingResponseAssembler {
    private static final Logger LOGGER = Logger.getLogger(
            CompleteMeetingResponseAssembler.class.getName());
    private static final long FIRST_PLAN_REVISION = 1;

    private final CapabilityPlanner planner;
    private final CreateRealtimeSessionAllocator sessions;
    private final TrustedCreateRuntimeLimits trustedDeploymentLimits;

    /**
     * @param planner 无外部副作用的能力计划器
     * @param sessions 本地参考首次会话身份分配端口
     * @param trustedDeploymentLimits 部署时注入的服务端限制，不能来自客户端 Create
     */
    public CompleteMeetingResponseAssembler(CapabilityPlanner planner,
            CreateRealtimeSessionAllocator sessions,
            TrustedCreateRuntimeLimits trustedDeploymentLimits) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.trustedDeploymentLimits = Objects.requireNonNull(
                trustedDeploymentLimits, "trustedDeploymentLimits");
    }

    /**
     * 组装首次完整响应；拒绝路径不会分配实时 Token。
     *
     * @param input 首次预占赢家、完整请求、可信归属与配置
     * @return 成功响应事实或固定拒绝原因；所有结果诊断输出均脱敏
     */
    public Result assemble(Input input) {
        Objects.requireNonNull(input, "input");
        if (!CompleteMeetingReserveFacts.isMatchingInitialWinner(
                input.winner(), input.requestFact(),
                input.deploymentScope(), input.subjectScope())) {
            return reject(FailureCode.WINNER_INCONSISTENT);
        }
        CreateMeetingRequestSnapshot request = input.requestFact().requestSnapshot();
        AuthorizedProfileSnapshot authorized = input.winner()
                .requireAuthorizedProfileSnapshot();
        if (!input.profile().id().equals(authorized.profileId())
                || input.profile().revision() != authorized.profileRevision()
                || !input.profile().allCapabilities().containsAll(
                        authorized.grantedCapabilities())) {
            return reject(FailureCode.PROFILE_MISMATCH);
        }
        final CapabilityPlan plan;
        try {
            plan = planner.plan(input.provider(), input.profile(),
                    request.requestedCapabilities(), authorized.grantedCapabilities(),
                    input.capabilityLimits(), FIRST_PLAN_REVISION);
        } catch (IllegalArgumentException failure) {
            return reject(FailureCode.CAPABILITY_UNAVAILABLE);
        }
        CapabilityDecision realtime = plan.decisionFor(CapabilityIds.TRANSCRIPT_REALTIME);
        if (plan.failed() || realtime == null
                || realtime.action() != CapabilityDecision.Action.ENABLE) {
            return reject(FailureCode.CAPABILITY_UNAVAILABLE);
        }
        AudioProfile audio = request.audioProfile();
        long requiredFrameBytes = (long) audio.sampleRateHz()
                * audio.channelCount() * 2L * audio.frameDurationMs() / 1_000L;
        if (requiredFrameBytes > trustedDeploymentLimits.maxAudioFrameBytes()) {
            return reject(FailureCode.AUDIO_UNSUPPORTED);
        }
        var limits = trustedDeploymentLimits.authorizedLimits(authorized.maxDurationMs());
        CreateMeetingResponseFact.RealtimeSession session;
        try {
            session = sessions.allocate(new CreateRealtimeSessionAllocator.Request(
                    input.deploymentScope(), input.subjectScope(),
                    input.winner().reservation().expectedMeetingId(),
                    input.winner().reservation().reservationId()));
        } catch (RuntimeException failure) {
            return reject(FailureCode.SESSION_UNAVAILABLE);
        }
        var response = new CreateMeetingResponseFact(
                input.winner().reservation().expectedMeetingId(), MeetingState.CREATED,
                request.profile(), authorized.grantedCapabilities(), plan.decisions(),
                audio, session, limits);
        LOGGER.log(Level.FINE, "完整 Create 首次响应已组装 planRevision={0}",
                plan.planRevision());
        return new Success(response);
    }

    /** 组装输入；所有字段是已认证或服务端配置事实，不保存原始凭据。 */
    public record Input(CreateMeetingRequestFact requestFact,
                        UsageOperationResult winner,
                        String deploymentScope, String subjectScope,
                        ProductProfile profile,
                        CapabilityDescriptor provider,
                        DeploymentLimits capabilityLimits) {
        public Input {
            Objects.requireNonNull(requestFact, "requestFact");
            Objects.requireNonNull(winner, "winner");
            Objects.requireNonNull(deploymentScope, "deploymentScope");
            Objects.requireNonNull(subjectScope, "subjectScope");
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(capabilityLimits, "capabilityLimits");
        }

        @Override
        public String toString() {
            return "CompleteMeetingResponseAssembler.Input[redacted]";
        }
    }

    /** 稳定组装结果，拒绝分支不含 Provider/模型/数据库动态详情。 */
    public sealed interface Result permits Success, Failure {
    }

    /** 可以交给 V013 工作单元持久化的候选响应；仍非已提交结果。 */
    public record Success(CreateMeetingResponseFact response) implements Result {
        public Success { Objects.requireNonNull(response, "response"); }
        @Override public String toString() {
            return "CompleteMeetingResponseAssembler.Success[response=redacted]";
        }
    }

    /** 本次组装未产生可持久化响应，也未对外签发令牌。 */
    public record Failure(FailureCode code) implements Result {
        public Failure { Objects.requireNonNull(code, "code"); }
    }

    /** 不泄露业务正文的固定失败类别。 */
    public enum FailureCode {
        WINNER_INCONSISTENT,
        PROFILE_MISMATCH,
        CAPABILITY_UNAVAILABLE,
        AUDIO_UNSUPPORTED,
        SESSION_UNAVAILABLE
    }

    private static Failure reject(FailureCode code) {
        LOGGER.log(Level.FINE, "完整 Create 首次响应组装拒绝 reason={0}", code);
        return new Failure(code);
    }
}
