package com.czur.reference.application.command;

import com.czur.reference.application.port.InputState;
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

    public AuthenticatedMeetingStopApplicationService(
            String deploymentScope,
            IdentityProvider identityProvider,
            OwnedMeetingStopUnitOfWork stopUnitOfWork,
            MeetingQueryPort queryPort) {
        if (deploymentScope == null || deploymentScope.isBlank()) {
            throw new IllegalArgumentException("deploymentScope must not be blank");
        }
        this.deploymentScope = deploymentScope;
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        this.stopUnitOfWork = Objects.requireNonNull(stopUnitOfWork, "stopUnitOfWork");
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
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

    private static AuthenticatedMeetingStopResult.InternalFailure internal(
            AuthenticatedMeetingStopResult.Stage stage) {
        LOGGER.log(Level.WARNING, "meeting stop internal stage={0}", stage);
        return new AuthenticatedMeetingStopResult.InternalFailure(stage);
    }
}
