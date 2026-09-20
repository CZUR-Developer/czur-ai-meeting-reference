package com.czur.reference.application.command;

import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.MeetingCreateOutcome;
import com.czur.reference.application.port.MeetingCreateOutcomeStore;
import com.czur.reference.application.port.MeetingCreateFailure;
import com.czur.reference.application.port.MeetingCreateReplayLookup;
import com.czur.reference.application.port.MeetingIdGenerator;
import com.czur.reference.application.port.MeetingRecoveryLookup;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.CancelReservationRequest;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReserveLookup;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.port.identity.VerifiedIdentity;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 认证会议创建的应用编排入口。
 *
 * <p>该类只负责把已经冻结的端口按一个可审计的顺序串起来：验证凭据、检查
 * 创建权限、计算业务摘要、查询创建和预占重放事实、申请 Grant、生成候选会议
 * 标识、预占用量，最后调用本地原子创建工作单元。模型、数据库、登录协议和
 * 计费规则仍由各端口实现；本类不把任何一种 Provider 实现写死在业务流程中。</p>
 *
 * <p>所有持久事实均采用 fail-closed 语义。重放查询发生冲突、不一致或不可用时
 * 不会继续调用权益 Provider；预占赢家的首次 scope、幂等键、摘要、授权 Profile、
 * 能力、估计用量和初始 Reservation 必须逐项与当前请求相符，不能因为“看起来
 * 是同一场会议”而猜测。创建工作单元返回提交结果未知时只允许只读恢复查询；
 * 恢复返回 {@code NOT_FOUND} 仍不写失败、不取消预占。只有已证明不存在会议和
 * Outcome 的确定失败，且失败 Outcome 原子争抢成功后，才使用预期会议标识和
 * 初始版本执行条件取消。</p>
 *
 * <p>原始 {@link SecretCredential} 的生命周期由该入口统一负责。无论身份 Provider
 * 返回、拒绝还是抛出运行时异常，方法结束前都会关闭凭据；Provider 不得把凭据
 * 传递到结果、领域对象、日志或其它异步工作。</p>
 */
public final class AuthenticatedMeetingApplicationService {
    private static final Logger LOGGER = Logger.getLogger(
            AuthenticatedMeetingApplicationService.class.getName());
    private static final String CREATE_FAILURE_CANCEL_PREFIX = "MEETING_CREATE_";

    private final String deploymentScope;
    private final IdentityProvider identityProvider;
    private final EntitlementProvider entitlementProvider;
    private final MeetingIdGenerator meetingIdGenerator;
    private final MeetingCreateReplayLookup createReplayLookup;
    private final UsageReserveLookup reserveLookup;
    private final OwnedMeetingCreationUnitOfWork creationUnitOfWork;
    private final MeetingRecoveryLookup recoveryLookup;
    private final MeetingCreateOutcomeStore outcomeStore;

    /**
     * 创建认证会议应用服务。
     *
     * <p>{@code deploymentScope} 是部署装配时冻结的值，不能由终端请求覆盖；其余
     * 参数均为应用层端口。这样参考后端、CZUR 自有实现和合作伙伴实现可以分别
     * 装配自己的认证、权益、存储和数据库适配器，而不复制本编排顺序。</p>
     */
    public AuthenticatedMeetingApplicationService(
            String deploymentScope,
            IdentityProvider identityProvider,
            EntitlementProvider entitlementProvider,
            MeetingIdGenerator meetingIdGenerator,
            MeetingCreateReplayLookup createReplayLookup,
            UsageReserveLookup reserveLookup,
            OwnedMeetingCreationUnitOfWork creationUnitOfWork,
            MeetingRecoveryLookup recoveryLookup,
            MeetingCreateOutcomeStore outcomeStore) {
        this.deploymentScope = requireText("deploymentScope", deploymentScope);
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        this.entitlementProvider = Objects.requireNonNull(
                entitlementProvider, "entitlementProvider");
        this.meetingIdGenerator = Objects.requireNonNull(meetingIdGenerator, "meetingIdGenerator");
        this.createReplayLookup = Objects.requireNonNull(
                createReplayLookup, "createReplayLookup");
        this.reserveLookup = Objects.requireNonNull(reserveLookup, "reserveLookup");
        this.creationUnitOfWork = Objects.requireNonNull(
                creationUnitOfWork, "creationUnitOfWork");
        this.recoveryLookup = Objects.requireNonNull(recoveryLookup, "recoveryLookup");
        this.outcomeStore = Objects.requireNonNull(outcomeStore, "outcomeStore");
    }

    /**
     * 执行一次认证会议创建请求。
     *
     * <p>调用方应在网络层把同一业务重试保持为同一 {@link CreateMeetingIntent}。
     * 返回值只包含稳定错误码、重放快照或已冻结创建失败，不包含原始凭据和底层
     * 异常正文。该方法是同步编排；端口实现不得在返回后继续读取凭据。</p>
     */
    public AuthenticatedMeetingCreateResult create(
            IdentityVerificationRequest verificationRequest,
            CreateMeetingIntent intent) {
        Objects.requireNonNull(verificationRequest, "verificationRequest");
        Objects.requireNonNull(intent, "intent");
        SecretCredential credential = verificationRequest.credential();
        try {
            LOGGER.log(Level.FINE, "认证会议创建编排开始");
            IdentityProviderResult<VerifiedIdentity> identityResult =
                    identityProvider.verifyCredential(verificationRequest);
            if (!identityResult.isSuccess()) {
                return new AuthenticatedMeetingCreateResult.IdentityFailure(
                        identityResult.errorOrThrow());
            }
            VerifiedIdentity identity = identityResult.valueOrThrow();
            if (!identity.hasPermission(MeetingPermissions.CREATE)) {
                return new AuthenticatedMeetingCreateResult.IdentityFailure(
                        new IdentityProviderError(
                                IdentityProviderError.Code.PERMISSION_DENIED, false));
            }

            String digest = MeetingRequestCanonicalizer.digest(intent);
            String subjectScope = identity.subject().subjectScope();
            MeetingCreateReplayLookup.Request replayRequest = new MeetingCreateReplayLookup.Request(
                    deploymentScope,
                    subjectScope,
                    intent.clientSessionId(),
                    intent.idempotencyKey(),
                    digest);

            AuthenticatedMeetingCreateResult replay = mapCreateReplay(
                    createReplayLookup.lookup(replayRequest));
            if (replay != null) {
                return replay;
            }

            UsageReserveLookup.Request reserveLookupRequest = new UsageReserveLookup.Request(
                    deploymentScope, subjectScope, intent.idempotencyKey(), digest);
            UsageOperationResult reserveResult = null;
            UsageReserveLookup.Result reserveLookupResult = reserveLookup.lookup(
                    reserveLookupRequest);
            if (reserveLookupResult instanceof UsageReserveLookup.Replayed replayed) {
                reserveResult = replayed.result();
                if (!isMatchingReserveWinner(
                        reserveResult, intent, deploymentScope, subjectScope, digest, null)) {
                    return inconsistent(
                            AuthenticatedMeetingCreateResult.Stage.USAGE_RESERVE_LOOKUP,
                            AuthenticatedMeetingCreateResult.InconsistencyReason
                                    .OWNED_RESERVE_INVARIANT_VIOLATION);
                }
            } else if (!(reserveLookupResult instanceof UsageReserveLookup.NotFound)) {
                return mapReserveLookup(reserveLookupResult);
            }

            if (reserveResult == null) {
                EntitlementProviderResult<MeetingGrant> authorization =
                        entitlementProvider.authorizeStart(new AuthorizeStartRequest(
                                deploymentScope,
                                identity.subject(),
                                intent.profileId(),
                                intent.profileRevision(),
                                intent.requestedCapabilities(),
                                intent.estimatedUsage(),
                                intent.clientSessionId()));
                if (!authorization.isSuccess()) {
                    return new AuthenticatedMeetingCreateResult.EntitlementFailure(
                            authorization.errorOrThrow());
                }
                MeetingGrant grant = authorization.valueOrThrow();
                String candidateMeetingId = requireGeneratedMeetingId(
                        meetingIdGenerator.newMeetingId());
                ReserveUsageRequest reserveRequest = new ReserveUsageRequest(
                        deploymentScope,
                        subjectScope,
                        UsageOperationScopes.RESERVE,
                        intent.idempotencyKey(),
                        digest,
                        grant,
                        intent.estimatedUsage(),
                        candidateMeetingId);
                EntitlementProviderResult<UsageOperationResult> reserved =
                        entitlementProvider.reserveUsage(reserveRequest);
                if (!reserved.isSuccess()) {
                    return new AuthenticatedMeetingCreateResult.EntitlementFailure(
                            reserved.errorOrThrow());
                }
                reserveResult = reserved.valueOrThrow();
                if (!isMatchingReserveWinner(
                        reserveResult, intent, deploymentScope, subjectScope, digest, grant)) {
                    return inconsistent(
                            AuthenticatedMeetingCreateResult.Stage.ENTITLEMENT_AUTHORIZATION,
                            AuthenticatedMeetingCreateResult.InconsistencyReason
                                    .OWNED_RESERVE_INVARIANT_VIOLATION);
                }
            }

            OwnedMeetingCreationUnitOfWork.CreateRequest createRequest =
                    new OwnedMeetingCreationUnitOfWork.CreateRequest(
                            deploymentScope,
                            subjectScope,
                            reserveResult.reservation().expectedMeetingId(),
                            intent.clientSessionId(),
                            intent.idempotencyKey(),
                            digest,
                            reserveResult);
            OwnedMeetingCreationUnitOfWork.Result created = creationUnitOfWork.create(
                    createRequest);
            return mapCreationResult(created, createRequest, intent);
        } finally {
            // 即使 Provider 抛出未声明的运行时异常，也不能留下可继续读取的凭据。
            credential.close();
        }
    }

    private AuthenticatedMeetingCreateResult mapCreateReplay(
            MeetingCreateReplayLookup.Result result) {
        if (result instanceof MeetingCreateReplayLookup.NotFound) {
            return null;
        }
        if (result instanceof MeetingCreateReplayLookup.Replayed replayed) {
            return mapOutcome(replayed.outcome(),
                    AuthenticatedMeetingCreateResult.SuccessCode.REPLAYED);
        }
        if (result instanceof MeetingCreateReplayLookup.Conflict) {
            return new AuthenticatedMeetingCreateResult.Conflict();
        }
        if (result instanceof MeetingCreateReplayLookup.Inconsistent inconsistent) {
            return inconsistent(
                    AuthenticatedMeetingCreateResult.Stage.MEETING_REPLAY_LOOKUP,
                    mapInconsistency(inconsistent.reason().name()));
        }
        MeetingCreateReplayLookup.Unavailable unavailable =
                (MeetingCreateReplayLookup.Unavailable) result;
        return new AuthenticatedMeetingCreateResult.Unavailable(
                AuthenticatedMeetingCreateResult.Stage.MEETING_REPLAY_LOOKUP,
                unavailable.retryable());
    }

    private AuthenticatedMeetingCreateResult mapReserveLookup(UsageReserveLookup.Result result) {
        if (result instanceof UsageReserveLookup.Conflict) {
            return new AuthenticatedMeetingCreateResult.Conflict();
        }
        if (result instanceof UsageReserveLookup.Inconsistent inconsistent) {
            return inconsistent(
                    AuthenticatedMeetingCreateResult.Stage.USAGE_RESERVE_LOOKUP,
                    mapInconsistency(inconsistent.reason().name()));
        }
        UsageReserveLookup.Unavailable unavailable =
                (UsageReserveLookup.Unavailable) result;
        return new AuthenticatedMeetingCreateResult.Unavailable(
                AuthenticatedMeetingCreateResult.Stage.USAGE_RESERVE_LOOKUP,
                unavailable.retryable());
    }

    private AuthenticatedMeetingCreateResult mapCreationResult(
            OwnedMeetingCreationUnitOfWork.Result result,
            OwnedMeetingCreationUnitOfWork.CreateRequest createRequest,
            CreateMeetingIntent intent) {
        if (result instanceof OwnedMeetingCreationUnitOfWork.Success success) {
            AuthenticatedMeetingCreateResult.SuccessCode code =
                    success.code() == OwnedMeetingCreationUnitOfWork.SuccessCode.CREATED
                            ? AuthenticatedMeetingCreateResult.SuccessCode.CREATED
                            : AuthenticatedMeetingCreateResult.SuccessCode.REPLAYED;
            return new AuthenticatedMeetingCreateResult.Success(code, success.result());
        }
        OwnedMeetingCreationUnitOfWork.Failure failure =
                (OwnedMeetingCreationUnitOfWork.Failure) result;
        if (failure.code() == OwnedMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN) {
            return recoverUnknown(createRequest);
        }
        if (failure.retryable() || !isPersistableFailure(failure.code())) {
            return new AuthenticatedMeetingCreateResult.CreationFailure(
                    failure.code(), failure.retryable(), false);
        }
        return persistFailureAfterRecovery(failure, createRequest, intent);
    }

    private AuthenticatedMeetingCreateResult recoverUnknown(
            OwnedMeetingCreationUnitOfWork.CreateRequest createRequest) {
        MeetingRecoveryLookup.Result recovered = recoveryLookup.lookup(createRequest);
        if (recovered instanceof MeetingRecoveryLookup.Replayed replayed) {
            return mapOutcome(replayed.outcome(),
                    AuthenticatedMeetingCreateResult.SuccessCode.REPLAYED);
        }
        if (recovered instanceof MeetingRecoveryLookup.NotFound) {
            // 只读恢复未发现事实不能证明本次 commit 未提交；不写失败、不取消。
            return new AuthenticatedMeetingCreateResult.CreationFailure(
                    OwnedMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, true, false);
        }
        if (recovered instanceof MeetingRecoveryLookup.Conflict) {
            return new AuthenticatedMeetingCreateResult.Conflict();
        }
        if (recovered instanceof MeetingRecoveryLookup.Inconsistent inconsistent) {
            return inconsistent(
                    AuthenticatedMeetingCreateResult.Stage.MEETING_RECOVERY,
                    mapInconsistency(inconsistent.reason().name()));
        }
        MeetingRecoveryLookup.Unavailable unavailable =
                (MeetingRecoveryLookup.Unavailable) recovered;
        return new AuthenticatedMeetingCreateResult.Unavailable(
                AuthenticatedMeetingCreateResult.Stage.MEETING_RECOVERY,
                unavailable.retryable());
    }

    private AuthenticatedMeetingCreateResult persistFailureAfterRecovery(
            OwnedMeetingCreationUnitOfWork.Failure failure,
            OwnedMeetingCreationUnitOfWork.CreateRequest createRequest,
            CreateMeetingIntent intent) {
        MeetingRecoveryLookup.Result recovered = recoveryLookup.lookup(createRequest);
        if (recovered instanceof MeetingRecoveryLookup.Replayed replayed) {
            return mapOutcome(replayed.outcome(),
                    AuthenticatedMeetingCreateResult.SuccessCode.REPLAYED);
        }
        if (recovered instanceof MeetingRecoveryLookup.NotFound) {
            return persistFailureAndConditionallyCancel(failure, createRequest, intent);
        }
        if (recovered instanceof MeetingRecoveryLookup.Conflict) {
            return new AuthenticatedMeetingCreateResult.Conflict();
        }
        if (recovered instanceof MeetingRecoveryLookup.Inconsistent inconsistent) {
            return inconsistent(
                    AuthenticatedMeetingCreateResult.Stage.MEETING_RECOVERY,
                    mapInconsistency(inconsistent.reason().name()));
        }
        MeetingRecoveryLookup.Unavailable unavailable =
                (MeetingRecoveryLookup.Unavailable) recovered;
        return new AuthenticatedMeetingCreateResult.Unavailable(
                AuthenticatedMeetingCreateResult.Stage.MEETING_RECOVERY,
                unavailable.retryable());
    }

    private AuthenticatedMeetingCreateResult persistFailureAndConditionallyCancel(
            OwnedMeetingCreationUnitOfWork.Failure failure,
            OwnedMeetingCreationUnitOfWork.CreateRequest createRequest,
            CreateMeetingIntent intent) {
        final MeetingCreateFailure persistedFailure;
        try {
            persistedFailure = new MeetingCreateFailure(failure.code(), false);
        } catch (IllegalArgumentException notPersistable) {
            return new AuthenticatedMeetingCreateResult.CreationFailure(
                    failure.code(), false, false);
        }
        MeetingCreateOutcomeStore.OutcomeRecord candidate =
                new MeetingCreateOutcomeStore.OutcomeRecord(
                        IdempotencyScopeCodec.meetingCreate(
                                createRequest.deploymentScope(), createRequest.subjectScope()),
                        createRequest.idempotencyKey(),
                        createRequest.canonicalRequestDigest(),
                        MeetingCreateOutcome.failure(persistedFailure));
        final MeetingCreateOutcomeStore.Reservation outcomeReservation;
        try {
            outcomeReservation = outcomeStore.reserve(candidate);
        } catch (MeetingCreateOutcomeStore.StoreException failureStore) {
            return mapOutcomeStoreFailure(failureStore);
        }
        if (outcomeReservation.status() == MeetingCreateOutcomeStore.ReservationStatus.CONFLICT) {
            return new AuthenticatedMeetingCreateResult.Conflict();
        }
        if (!sameOutcomeIdentity(candidate, outcomeReservation.record())) {
            return inconsistent(
                    AuthenticatedMeetingCreateResult.Stage.MEETING_CREATION,
                    AuthenticatedMeetingCreateResult.InconsistencyReason.STORED_OUTCOME_INVALID);
        }
        if (outcomeReservation.status() == MeetingCreateOutcomeStore.ReservationStatus.REPLAY) {
            return mapOutcome(outcomeReservation.record().outcome(),
                    AuthenticatedMeetingCreateResult.SuccessCode.REPLAYED);
        }

        // 只有当前调用原子取得失败 Outcome 后，才有权带着初始版本做条件取消。
        cancelAfterFailure(createRequest, intent, failure.code());
        return new AuthenticatedMeetingCreateResult.CreationFailure(
                failure.code(), false, true);
    }

    private void cancelAfterFailure(
            OwnedMeetingCreationUnitOfWork.CreateRequest createRequest,
            CreateMeetingIntent intent,
            OwnedMeetingCreationUnitOfWork.ErrorCode failureCode) {
        UsageReservation reservation = createRequest.reserveResult().reservation();
        CancelReservationRequest request = new CancelReservationRequest(
                createRequest.deploymentScope(),
                createRequest.subjectScope(),
                UsageOperationScopes.CANCEL,
                intent.idempotencyKey(),
                createRequest.canonicalRequestDigest(),
                reservation.reservationId(),
                reservation.expectedMeetingId(),
                reservation.version(),
                CREATE_FAILURE_CANCEL_PREFIX + failureCode.name());
        try {
            EntitlementProviderResult<UsageOperationResult> cancelled =
                    entitlementProvider.cancelReservation(request);
            if (!cancelled.isSuccess()) {
                LOGGER.log(Level.WARNING,
                        "会议创建失败 Outcome 已持久化，但预占条件取消未完成 code={0}",
                        failureCode);
            }
        } catch (RuntimeException cancelFailure) {
            // Outcome 已经冻结失败；取消失败交给孤儿预占清理流程，不能再猜测或重写 Outcome。
            LOGGER.log(Level.WARNING,
                    "会议创建失败 Outcome 已持久化，但预占条件取消抛出异常 code={0}",
                    failureCode);
        }
    }

    private AuthenticatedMeetingCreateResult mapOutcome(
            MeetingCreateOutcome outcome,
            AuthenticatedMeetingCreateResult.SuccessCode successCode) {
        if (outcome instanceof MeetingCreateOutcome.Success success) {
            return new AuthenticatedMeetingCreateResult.Success(successCode, success.result());
        }
        MeetingCreateOutcome.Failure failure = (MeetingCreateOutcome.Failure) outcome;
        return new AuthenticatedMeetingCreateResult.CreationFailure(
                failure.failure().code(), false, true);
    }

    private AuthenticatedMeetingCreateResult mapOutcomeStoreFailure(
            MeetingCreateOutcomeStore.StoreException failure) {
        return switch (failure.code()) {
            case OUTCOME_UNKNOWN -> new AuthenticatedMeetingCreateResult.CreationFailure(
                    OwnedMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, true, false);
            case STORAGE_UNAVAILABLE -> new AuthenticatedMeetingCreateResult.Unavailable(
                    AuthenticatedMeetingCreateResult.Stage.MEETING_CREATION, true);
            case CORRUPT_DATA -> inconsistent(
                    AuthenticatedMeetingCreateResult.Stage.MEETING_CREATION,
                    AuthenticatedMeetingCreateResult.InconsistencyReason.STORED_OUTCOME_INVALID);
        };
    }

    private static boolean sameOutcomeIdentity(
            MeetingCreateOutcomeStore.OutcomeRecord expected,
            MeetingCreateOutcomeStore.OutcomeRecord actual) {
        return expected.scope().equals(actual.scope())
                && expected.key().equals(actual.key())
                && expected.requestDigest().equals(actual.requestDigest());
    }

    private static boolean isMatchingReserveWinner(
            UsageOperationResult result,
            CreateMeetingIntent intent,
            String deploymentScope,
            String subjectScope,
            String digest,
            MeetingGrant grant) {
        if (!UsageReserveLookup.isReplayableOwnedReserveResult(result)
                || !deploymentScope.equals(result.deploymentScope())
                || !subjectScope.equals(result.subjectScope())
                || !UsageOperationScopes.RESERVE.equals(result.operationScope())
                || !intent.idempotencyKey().equals(result.idempotencyKey())
                || !digest.equals(result.requestDigest())) {
            return false;
        }
        AuthorizedProfileSnapshot profile = result.requireAuthorizedProfileSnapshot();
        if (!intent.profileId().equals(profile.profileId())
                || intent.profileRevision() != profile.profileRevision()
                || !intent.requestedCapabilities().equals(profile.requestedCapabilities())
                || !profile.grantedCapabilities().containsAll(intent.requestedCapabilities())
                || !intent.estimatedUsage().equals(result.reservation().reservedUsage())) {
            return false;
        }
        if (grant != null) {
            if (!grant.expiresAt().equals(result.grantExpiresAt())
                    || !AuthorizedProfileSnapshot.from(grant).equals(profile)) {
                return false;
            }
            if (!grant.grantId().equals(result.reservation().grantId())) {
                return false;
            }
        }
        UsageReservation reservation = result.reservation();
        return reservation.state() == UsageReservation.State.RESERVED
                && !reservation.isBound()
                && reservation.version() == 0
                && deploymentScope.equals(reservation.deploymentScope())
                && subjectScope.equals(reservation.subjectScope())
                && reservation.expectedMeetingId() != null
                && !reservation.expectedMeetingId().isBlank();
    }

    private static boolean isPersistableFailure(
            OwnedMeetingCreationUnitOfWork.ErrorCode code) {
        return code != OwnedMeetingCreationUnitOfWork.ErrorCode.IDEMPOTENCY_CONFLICT
                && code != OwnedMeetingCreationUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE
                && code != OwnedMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN
                && code != OwnedMeetingCreationUnitOfWork.ErrorCode.INTERNAL;
    }

    private static AuthenticatedMeetingCreateResult.Inconsistent inconsistent(
            AuthenticatedMeetingCreateResult.Stage stage,
            AuthenticatedMeetingCreateResult.InconsistencyReason reason) {
        return new AuthenticatedMeetingCreateResult.Inconsistent(stage, reason);
    }

    private static AuthenticatedMeetingCreateResult.InconsistencyReason mapInconsistency(
            String reasonName) {
        try {
            return AuthenticatedMeetingCreateResult.InconsistencyReason.valueOf(reasonName);
        } catch (IllegalArgumentException unsupportedReason) {
            return AuthenticatedMeetingCreateResult.InconsistencyReason
                    .STORED_OUTCOME_INVALID;
        }
    }

    private static String requireGeneratedMeetingId(String meetingId) {
        if (meetingId == null || meetingId.isBlank()) {
            throw new IllegalStateException("meeting ID generator returned blank value");
        }
        return meetingId;
    }

    private static String requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
