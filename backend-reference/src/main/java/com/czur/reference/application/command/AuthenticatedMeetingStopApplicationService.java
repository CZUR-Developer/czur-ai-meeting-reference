package com.czur.reference.application.command;

import com.czur.reference.application.finalization.MeetingInputReconciler;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ClientMeetingResultFinalizer;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.application.port.OwnedMeetingStopUnitOfWork;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.VerifiedIdentity;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 验证身份与停止权限，提交 owned 停止意图，再读取当前持久化输入接管事实。 */
public final class AuthenticatedMeetingStopApplicationService {
    private static final Logger LOGGER = Logger.getLogger(
            AuthenticatedMeetingStopApplicationService.class.getName());

    private final String deploymentScope;
    private final IdentityProvider identityProvider;
    private final OwnedMeetingStopUnitOfWork stopUnitOfWork;
    private final MeetingQueryPort queryPort;
    private final MeetingInputReconciler inputReconciler;
    private final ClientMeetingResultFinalizer clientResultFinalizer;

    /** 旧装配保持只提交 Stop 并查询状态；不隐式启用实时输入收口。 */
    public AuthenticatedMeetingStopApplicationService(
            String deploymentScope,
            IdentityProvider identityProvider,
            OwnedMeetingStopUnitOfWork stopUnitOfWork,
            MeetingQueryPort queryPort) {
        this(deploymentScope, identityProvider, stopUnitOfWork, queryPort, null);
    }

    /**
     * 显式接入同一会议级输入收口编排器。
     *
     * <p>只有 Stop 事务确认成功后才触发 Reconciler；其待补帧或故障不改变已提交
     * Stop 的裁决。响应仍从后续 Meeting 查询读取持久 inputState/receipt，绝不
     * 根据 Reconciler 的内存返回值拼装凭证。{@code null} 只供旧装配保持原行为。</p>
     */
    public AuthenticatedMeetingStopApplicationService(
            String deploymentScope,
            IdentityProvider identityProvider,
            OwnedMeetingStopUnitOfWork stopUnitOfWork,
            MeetingQueryPort queryPort,
            MeetingInputReconciler inputReconciler) {
        this(deploymentScope, identityProvider, stopUnitOfWork, queryPort,
                inputReconciler, null);
    }

    /** 新客户端事件会议优先同步冻结字幕；旧音频会议沿用原收口器。 */
    public AuthenticatedMeetingStopApplicationService(
            String deploymentScope,
            IdentityProvider identityProvider,
            OwnedMeetingStopUnitOfWork stopUnitOfWork,
            MeetingQueryPort queryPort,
            MeetingInputReconciler inputReconciler,
            ClientMeetingResultFinalizer clientResultFinalizer) {
        if (deploymentScope == null || deploymentScope.isBlank()) {
            throw new IllegalArgumentException("deploymentScope must not be blank");
        }
        this.deploymentScope = deploymentScope;
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        this.stopUnitOfWork = Objects.requireNonNull(stopUnitOfWork, "stopUnitOfWork");
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.inputReconciler = inputReconciler;
        this.clientResultFinalizer = clientResultFinalizer;
    }

    public AuthenticatedMeetingStopResult stop(
            IdentityVerificationRequest verificationRequest, StopMeetingIntent intent) {
        Objects.requireNonNull(verificationRequest, "verificationRequest");
        Objects.requireNonNull(intent, "intent");
        try {
            final IdentityProviderResult<VerifiedIdentity> verified;
            try {
                verified = Objects.requireNonNull(
                        identityProvider.verifyCredential(verificationRequest), "identity result");
            } catch (RuntimeException failure) {
                return internal(AuthenticatedMeetingStopResult.Stage.IDENTITY);
            }
            if (!verified.isSuccess()) {
                return new AuthenticatedMeetingStopResult.IdentityFailure(verified.errorOrThrow());
            }
            VerifiedIdentity identity = verified.valueOrThrow();
            if (!identity.hasPermission(MeetingPermissions.STOP)) {
                return new AuthenticatedMeetingStopResult.IdentityFailure(new IdentityProviderError(
                        IdentityProviderError.Code.PERMISSION_DENIED, false));
            }

            MeetingCommandContext context = new MeetingCommandContext(deploymentScope, identity);
            final OwnedMeetingStopUnitOfWork.Result stopped;
            try {
                stopped = Objects.requireNonNull(stopUnitOfWork.stop(
                        new OwnedMeetingStopUnitOfWork.StopRequest(
                                context, intent.meetingId(), intent.idempotencyKey(),
                                MeetingRequestCanonicalizer.digest(intent))), "stop result");
            } catch (RuntimeException failure) {
                return internal(AuthenticatedMeetingStopResult.Stage.STOP);
            }
            if (stopped instanceof OwnedMeetingStopUnitOfWork.Failure failed) {
                return new AuthenticatedMeetingStopResult.StopFailure(failed.code());
            }
            OwnedMeetingStopUnitOfWork.Success success =
                    (OwnedMeetingStopUnitOfWork.Success) stopped;
            if (!intent.meetingId().equals(success.result().meeting().meetingId())) {
                return internal(AuthenticatedMeetingStopResult.Stage.STOP);
            }

            // Stop 已经提交，不能因音频尚缺、Admission 暂不可用或本次触发异常
            // 把它改报为停止失败。下次完成声明/末帧或显式扫描仍可调用同一编排器。
            triggerInputClosure(intent.meetingId());

            final MeetingQueryPort.Result queried;
            try {
                queried = Objects.requireNonNull(queryPort.get(
                        new MeetingQueryPort.GetRequest(context, intent.meetingId())),
                        "query result");
            } catch (RuntimeException failure) {
                return internal(AuthenticatedMeetingStopResult.Stage.QUERY);
            }
            if (queried instanceof MeetingQueryPort.Found found) {
                MeetingStatus status = found.meeting();
                if (!intent.meetingId().equals(status.meetingId())
                        || (status.inputState() != InputState.INPUT_COMPLETE_PENDING
                        && status.inputState() != InputState.INPUT_ACCEPTED)) {
                    return internal(AuthenticatedMeetingStopResult.Stage.QUERY_INCONSISTENT);
                }
                return new AuthenticatedMeetingStopResult.Success(success.code(), status);
            }
            if (queried instanceof MeetingQueryPort.NotFound) {
                return new AuthenticatedMeetingStopResult.QueryFailure(
                        AuthenticatedMeetingStopResult.QueryFailureCode.NOT_FOUND, false);
            }
            if (queried instanceof MeetingQueryPort.AccessDenied) {
                return new AuthenticatedMeetingStopResult.QueryFailure(
                        AuthenticatedMeetingStopResult.QueryFailureCode.ACCESS_DENIED, false);
            }
            if (queried instanceof MeetingQueryPort.Conflict) {
                return new AuthenticatedMeetingStopResult.QueryFailure(
                        AuthenticatedMeetingStopResult.QueryFailureCode.CONFLICT, false);
            }
            MeetingQueryPort.Unavailable unavailable = (MeetingQueryPort.Unavailable) queried;
            return new AuthenticatedMeetingStopResult.QueryFailure(
                    AuthenticatedMeetingStopResult.QueryFailureCode.UNAVAILABLE,
                    unavailable.retryable());
        } finally {
            verificationRequest.credential().close();
        }
    }

    private void triggerInputClosure(String meetingId) {
        if (clientResultFinalizer != null) {
            try {
                if (clientResultFinalizer.finalizeIfClient(meetingId)) return;
            } catch (RuntimeException failure) {
                LOGGER.log(Level.WARNING, "客户端结果同步收口暂未完成 category=INTERNAL");
                return;
            }
        }
        if (inputReconciler == null) return;
        try {
            MeetingInputReconciler.Result result = inputReconciler.reconcile(meetingId);
            if (result instanceof MeetingInputReconciler.Failure failure) {
                LOGGER.log(Level.WARNING, "meeting stop 收口未完成 reason={0}",
                        failure.reason());
            } else if (result instanceof MeetingInputReconciler.Pending pending) {
                LOGGER.log(Level.FINE, "meeting stop 收口待输入 reason={0}",
                        pending.reason());
            } else {
                LOGGER.log(Level.FINE, "meeting stop 收口已接管");
            }
        } catch (RuntimeException unexpected) {
            // Reconciler 正常会返回固定分类；意外异常仍不能覆盖已提交 Stop。
            LOGGER.log(Level.WARNING, "meeting stop 收口触发异常 category=INTERNAL");
        }
    }

    private static AuthenticatedMeetingStopResult.InternalFailure internal(
            AuthenticatedMeetingStopResult.Stage stage) {
        LOGGER.log(Level.WARNING, "meeting stop internal stage={0}", stage);
        return new AuthenticatedMeetingStopResult.InternalFailure(stage);
    }
}
