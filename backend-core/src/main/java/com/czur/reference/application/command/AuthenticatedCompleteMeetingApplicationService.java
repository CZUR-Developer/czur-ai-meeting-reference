package com.czur.reference.application.command;

import com.czur.reference.application.port.CompleteMeetingCreateReplayLookup;
import com.czur.reference.application.port.CompleteMeetingCreationUnitOfWork;
import com.czur.reference.application.port.MeetingIdGenerator;
import com.czur.reference.application.port.ProductProfileCatalog;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReserveLookup;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.util.Optional;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 完整 Create 的认证应用编排，与旧 {@code meeting.create.v1} 并行且不修改旧路径。
 *
 * <p>顺序是身份与 CREATE 权限、V013 只读重放、V003 首次预占查找、必要时
 * authorize/reserve 并再次只读查找持久赢家、能力与响应组装、v2 原子工作单元。
 * 历史 Create 重放发生在读取 Profile/模型能力之前；V003 首次赢家的查询也
 * 先于 Profile 版本读取，当前策略变化不能跳过已持久化的赢家。首次预估量与
 * 能力配置只来自服务端装配，完整客户端快照不
 * 包含预估量。该类不调用模型、不处理 HTTP，也不保存数据库连接。</p>
 *
 * <p>任何成功只交付 V013 工作单元或只读查询给出的持久首次响应。提交未知时
 * 只允许再查 V013；查无事实仍保持 UNKNOWN，不用候选响应伪造成功，不重复
 * 提交，也不取消可能已绑定的 Reservation。所有失败分支都不含候选 Token。
 * 凭据在同步调用退出前无条件关闭，端口不得异步持有它。</p>
 */
public final class AuthenticatedCompleteMeetingApplicationService {
    private static final Logger LOGGER = Logger.getLogger(
            AuthenticatedCompleteMeetingApplicationService.class.getName());

    private final String deploymentScope;
    private final IdentityProvider identityProvider;
    private final CompleteMeetingCreateReplayLookup createReplayLookup;
    private final UsageReserveLookup reserveLookup;
    private final EntitlementProvider entitlementProvider;
    private final MeetingIdGenerator meetingIdGenerator;
    private final CompleteMeetingResponseAssembler assembler;
    private final CompleteMeetingCreationUnitOfWork creationUnitOfWork;
    private final UsageEstimate trustedEstimate;
    private final ProductProfileCatalog profiles;
    private final CapabilityDescriptor providerCapabilities;
    private final DeploymentLimits capabilityLimits;

    /**
     * @param deploymentScope 部署配置的稳定范围，不能由客户端覆盖
     * @param trustedEstimate 服务端决定的首次会议时长预估，仅供权益预占，
     *                        不能作为成功响应的会议时长上限
     * @param profiles 服务端可信版本化 Product Profile；不得把最新版冒充旧版
     * @param providerCapabilities 服务端注册的当前模型能力
     * @param capabilityLimits 服务端配置的当前能力开关
     */
    public AuthenticatedCompleteMeetingApplicationService(
            String deploymentScope,
            IdentityProvider identityProvider,
            CompleteMeetingCreateReplayLookup createReplayLookup,
            UsageReserveLookup reserveLookup,
            EntitlementProvider entitlementProvider,
            MeetingIdGenerator meetingIdGenerator,
            CompleteMeetingResponseAssembler assembler,
            CompleteMeetingCreationUnitOfWork creationUnitOfWork,
            UsageEstimate trustedEstimate,
            ProductProfileCatalog profiles,
            CapabilityDescriptor providerCapabilities,
            DeploymentLimits capabilityLimits) {
        if (deploymentScope == null || deploymentScope.isBlank()) {
            throw new IllegalArgumentException("deploymentScope must not be blank");
        }
        this.deploymentScope = deploymentScope;
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        this.createReplayLookup = Objects.requireNonNull(createReplayLookup, "createReplayLookup");
        this.reserveLookup = Objects.requireNonNull(reserveLookup, "reserveLookup");
        this.entitlementProvider = Objects.requireNonNull(entitlementProvider, "entitlementProvider");
        this.meetingIdGenerator = Objects.requireNonNull(meetingIdGenerator, "meetingIdGenerator");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.creationUnitOfWork = Objects.requireNonNull(creationUnitOfWork, "creationUnitOfWork");
        this.trustedEstimate = Objects.requireNonNull(trustedEstimate, "trustedEstimate");
        if (trustedEstimate.unit() != UsageUnit.MEETING_DURATION_MS) {
            throw new IllegalArgumentException("trustedEstimate must use meeting duration");
        }
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.providerCapabilities = Objects.requireNonNull(
                providerCapabilities, "providerCapabilities");
        this.capabilityLimits = Objects.requireNonNull(capabilityLimits, "capabilityLimits");
    }

    /**
     * 执行一次完整 Create；调用者负责在入口层构造严格校验的请求事实。
     *
     * @param verificationRequest 同步身份验证输入；凭据由本方法关闭
     * @param requestFact 带严格规范摘要的完整 Create 请求，不得包含凭据
     * @return 只有成功分支包含 V013 持久响应，所有其它分支均无 Token
     */
    public AuthenticatedCompleteMeetingCreateResult create(
            IdentityVerificationRequest verificationRequest,
            CreateMeetingRequestFact requestFact) {
        Objects.requireNonNull(verificationRequest, "verificationRequest");
        SecretCredential credential = verificationRequest.credential();
        try {
            Objects.requireNonNull(requestFact, "requestFact");
            LOGGER.log(Level.FINE, "完整 Create 认证编排开始");
            IdentityProviderResult<VerifiedIdentity> verified =
                    identityProvider.verifyCredential(verificationRequest);
            if (!verified.isSuccess()) {
                return new AuthenticatedCompleteMeetingCreateResult.IdentityFailure(
                        verified.errorOrThrow());
            }
            VerifiedIdentity identity = verified.valueOrThrow();
            if (!identity.hasPermission(MeetingPermissions.CREATE)) {
                return new AuthenticatedCompleteMeetingCreateResult.IdentityFailure(
                        new com.czur.reference.application.port.identity.IdentityProviderError(
                                com.czur.reference.application.port.identity.IdentityProviderError
                                        .Code.PERMISSION_DENIED, false));
            }

            var request = requestFact.requestSnapshot();
            String subjectScope = identity.subject().subjectScope();
            var replayRequest = new CompleteMeetingCreateReplayLookup.Request(
                    deploymentScope, subjectScope, request.idempotencyKey(),
                    requestFact.canonicalRequestDigest());
            var replay = createReplayLookup.lookup(replayRequest);
            if (!(replay instanceof CompleteMeetingCreateReplayLookup.NotFound)) {
                return mapCreateReplay(replay, AuthenticatedCompleteMeetingCreateResult.Stage
                        .CREATE_REPLAY);
            }

            String reserveKey = CompleteMeetingReserveFacts.internalReserveKey(requestFact);
            var reserveQuery = new UsageReserveLookup.Request(deploymentScope, subjectScope,
                    reserveKey, requestFact.canonicalRequestDigest());
            UsageReserveLookup.Result reserveRead = reserveLookup.lookup(reserveQuery);
            UsageOperationResult winner;
            ProductProfile profile = null;
            if (reserveRead instanceof UsageReserveLookup.Replayed replayed) {
                winner = replayed.result();
            } else if (reserveRead instanceof UsageReserveLookup.NotFound) {
                profile = findTrustedProfile(request.profile()).orElse(null);
                if (profile == null) {
                    return new AuthenticatedCompleteMeetingCreateResult.Unavailable(
                            AuthenticatedCompleteMeetingCreateResult.Stage.PROFILE);
                }
                if (!profile.allCapabilities().containsAll(request.requestedCapabilities())) {
                    return new AuthenticatedCompleteMeetingCreateResult.PreparationFailure(
                            CompleteMeetingResponseAssembler.FailureCode.CAPABILITY_UNAVAILABLE);
                }
                var first = reserveFirst(identity, requestFact, reserveKey, reserveQuery,
                        profile);
                if (first instanceof ReserveFirstFailure failure) {
                    return failure.result();
                }
                winner = ((ReserveFirstWinner) first).winner();
            } else {
                return mapReserveLookup(reserveRead);
            }
            if (!CompleteMeetingReserveFacts.isMatchingInitialWinner(
                    winner, requestFact, deploymentScope, subjectScope)) {
                return new AuthenticatedCompleteMeetingCreateResult.Inconsistent(
                        AuthenticatedCompleteMeetingCreateResult.Stage.RESERVE_LOOKUP);
            }
            if (profile == null) {
                profile = findTrustedProfile(request.profile()).orElse(null);
                if (profile == null) {
                    return new AuthenticatedCompleteMeetingCreateResult.Unavailable(
                            AuthenticatedCompleteMeetingCreateResult.Stage.PROFILE);
                }
            }

            var prepared = assembler.assemble(new CompleteMeetingResponseAssembler.Input(
                    requestFact, winner, deploymentScope, subjectScope, profile,
                    providerCapabilities, capabilityLimits));
            if (prepared instanceof CompleteMeetingResponseAssembler.Failure failure) {
                return new AuthenticatedCompleteMeetingCreateResult.PreparationFailure(
                        failure.code());
            }
            var candidate = ((CompleteMeetingResponseAssembler.Success) prepared).response();
            var committed = creationUnitOfWork.create(
                    new CompleteMeetingCreationUnitOfWork.CreateRequest(
                            deploymentScope, subjectScope, requestFact, candidate, winner));
            if (committed instanceof CompleteMeetingCreationUnitOfWork.Success success) {
                var code = success.code() == CompleteMeetingCreationUnitOfWork.SuccessCode.CREATED
                        ? AuthenticatedCompleteMeetingCreateResult.SuccessCode.CREATED
                        : AuthenticatedCompleteMeetingCreateResult.SuccessCode.REPLAYED;
                return new AuthenticatedCompleteMeetingCreateResult.Success(
                        code, success.response());
            }
            var failure = (CompleteMeetingCreationUnitOfWork.Failure) committed;
            if (failure.code() == CompleteMeetingCreationUnitOfWork.ErrorCode.IDEMPOTENCY_CONFLICT) {
                return new AuthenticatedCompleteMeetingCreateResult.Conflict();
            }
            if (failure.code() != CompleteMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN) {
                return new AuthenticatedCompleteMeetingCreateResult.CreationFailure(
                        failure.code(), failure.retryable());
            }
            // 查无事实不能证明事务未提交；不重试写入，也不取消预占。
            var recovered = createReplayLookup.lookup(replayRequest);
            if (recovered instanceof CompleteMeetingCreateReplayLookup.NotFound
                    || recovered instanceof CompleteMeetingCreateReplayLookup.Unavailable) {
                // 数据库不可用同样无法判定 commit 结果；保持 UNKNOWN 而非普通查询失败。
                return new AuthenticatedCompleteMeetingCreateResult.CreationFailure(
                        CompleteMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, true);
            }
            return mapCreateReplay(recovered,
                    AuthenticatedCompleteMeetingCreateResult.Stage.CREATE_RECOVERY);
        } finally {
            credential.close();
        }
    }

    private Optional<ProductProfile> findTrustedProfile(
            CreateMeetingRequestSnapshot.ProfileRef requested) {
        try {
            Optional<ProductProfile> found = Objects.requireNonNull(
                    profiles.find(requested.id(), requested.revision()), "profiles.find()");
            return found.filter(profile -> requested.id().equals(profile.id())
                    && requested.revision() == profile.revision());
        } catch (RuntimeException catalogFailure) {
            LOGGER.log(Level.WARNING, "完整 Create Profile 版本读取失败 reason=PROFILE_UNAVAILABLE");
            return Optional.empty();
        }
    }

    private ReserveFirstResult reserveFirst(VerifiedIdentity identity,
            CreateMeetingRequestFact requestFact, String reserveKey,
            UsageReserveLookup.Request reserveQuery, ProductProfile profile) {
        var request = requestFact.requestSnapshot();
        var authorization = entitlementProvider.authorizeStart(new AuthorizeStartRequest(
                deploymentScope, identity.subject(), request.profile().id(),
                request.profile().revision(), request.requestedCapabilities(),
                trustedEstimate, request.clientSessionId()));
        if (!authorization.isSuccess()) {
            // 并发首次 miss 后另一请求可能已形成赢家；先看持久事实再返回拒绝。
            var read = reserveLookup.lookup(reserveQuery);
            if (read instanceof UsageReserveLookup.Replayed replayed) {
                return new ReserveFirstWinner(replayed.result());
            }
            if (!(read instanceof UsageReserveLookup.NotFound)) {
                return new ReserveFirstFailure(mapReserveLookup(read));
            }
            return new ReserveFirstFailure(
                    new AuthenticatedCompleteMeetingCreateResult.EntitlementFailure(
                            authorization.errorOrThrow()));
        }
        MeetingGrant grant = authorization.valueOrThrow();
        if (!deploymentScope.equals(grant.deploymentScope())
                || !identity.subject().subjectScope().equals(grant.subjectScope())
                || !request.profile().id().equals(grant.profileId())
                || request.profile().revision() != grant.profileRevision()
                || !request.requestedCapabilities().equals(grant.requestedCapabilities())
                || !grant.grantedCapabilities().containsAll(request.requestedCapabilities())
                || !profile.allCapabilities().containsAll(grant.grantedCapabilities())
                || trustedEstimate.amount() > grant.maxDurationMs()) {
            return new ReserveFirstFailure(
                    new AuthenticatedCompleteMeetingCreateResult.Inconsistent(
                            AuthenticatedCompleteMeetingCreateResult.Stage.ENTITLEMENT));
        }
        if (!grant.grantedCapabilities().containsAll(profile.requiredCapabilities())) {
            return new ReserveFirstFailure(
                    new AuthenticatedCompleteMeetingCreateResult.PreparationFailure(
                            CompleteMeetingResponseAssembler.FailureCode.CAPABILITY_UNAVAILABLE));
        }
        String candidateMeetingId;
        try {
            candidateMeetingId = meetingIdGenerator.newMeetingId();
        } catch (RuntimeException generatorFailure) {
            LOGGER.log(Level.WARNING, "完整 Create 会议标识分配失败 reason=ID_UNAVAILABLE");
            return new ReserveFirstFailure(
                    new AuthenticatedCompleteMeetingCreateResult.Unavailable(
                            AuthenticatedCompleteMeetingCreateResult.Stage.ENTITLEMENT));
        }
        boolean validMeetingId;
        try {
            MeetingIdPolicy.requireValid(candidateMeetingId);
            validMeetingId = candidateMeetingId.matches("mtg_[A-Za-z0-9_-]+");
        } catch (IllegalArgumentException | NullPointerException invalidId) {
            validMeetingId = false;
        }
        if (!validMeetingId) {
            return new ReserveFirstFailure(
                    new AuthenticatedCompleteMeetingCreateResult.Inconsistent(
                            AuthenticatedCompleteMeetingCreateResult.Stage.ENTITLEMENT));
        }
        var reserved = entitlementProvider.reserveUsage(new ReserveUsageRequest(
                deploymentScope, identity.subject().subjectScope(),
                UsageOperationScopes.RESERVE, reserveKey,
                requestFact.canonicalRequestDigest(), grant, trustedEstimate,
                candidateMeetingId));
        // 无论 Provider 返回本次候选还是并发赢家，都只接受数据库只读回查的首次事实。
        var read = reserveLookup.lookup(reserveQuery);
        if (read instanceof UsageReserveLookup.Replayed replayed) {
            return new ReserveFirstWinner(replayed.result());
        }
        if (!(read instanceof UsageReserveLookup.NotFound)) {
            return new ReserveFirstFailure(mapReserveLookup(read));
        }
        if (!reserved.isSuccess()) {
            return new ReserveFirstFailure(
                    new AuthenticatedCompleteMeetingCreateResult.EntitlementFailure(
                            reserved.errorOrThrow()));
        }
        return new ReserveFirstFailure(
                new AuthenticatedCompleteMeetingCreateResult.Inconsistent(
                        AuthenticatedCompleteMeetingCreateResult.Stage.RESERVE_LOOKUP));
    }

    private static AuthenticatedCompleteMeetingCreateResult mapCreateReplay(
            CompleteMeetingCreateReplayLookup.Result read,
            AuthenticatedCompleteMeetingCreateResult.Stage stage) {
        if (read instanceof CompleteMeetingCreateReplayLookup.Replayed replayed) {
            return new AuthenticatedCompleteMeetingCreateResult.Success(
                    AuthenticatedCompleteMeetingCreateResult.SuccessCode.REPLAYED,
                    replayed.response());
        }
        if (read instanceof CompleteMeetingCreateReplayLookup.Conflict) {
            return new AuthenticatedCompleteMeetingCreateResult.Conflict();
        }
        if (read instanceof CompleteMeetingCreateReplayLookup.Inconsistent) {
            return new AuthenticatedCompleteMeetingCreateResult.Inconsistent(stage);
        }
        if (read instanceof CompleteMeetingCreateReplayLookup.Unavailable) {
            return new AuthenticatedCompleteMeetingCreateResult.Unavailable(stage);
        }
        throw new IllegalArgumentException("NOT_FOUND cannot be mapped as a terminal replay");
    }

    private static AuthenticatedCompleteMeetingCreateResult mapReserveLookup(
            UsageReserveLookup.Result read) {
        if (read instanceof UsageReserveLookup.Conflict) {
            return new AuthenticatedCompleteMeetingCreateResult.Conflict();
        }
        if (read instanceof UsageReserveLookup.Inconsistent) {
            return new AuthenticatedCompleteMeetingCreateResult.Inconsistent(
                    AuthenticatedCompleteMeetingCreateResult.Stage.RESERVE_LOOKUP);
        }
        if (read instanceof UsageReserveLookup.Unavailable) {
            return new AuthenticatedCompleteMeetingCreateResult.Unavailable(
                    AuthenticatedCompleteMeetingCreateResult.Stage.RESERVE_LOOKUP);
        }
        throw new IllegalArgumentException("reserve result is not a failure");
    }

    private sealed interface ReserveFirstResult permits ReserveFirstWinner, ReserveFirstFailure {
    }

    private record ReserveFirstWinner(UsageOperationResult winner) implements ReserveFirstResult {
    }

    private record ReserveFirstFailure(AuthenticatedCompleteMeetingCreateResult result)
            implements ReserveFirstResult {
    }
}
