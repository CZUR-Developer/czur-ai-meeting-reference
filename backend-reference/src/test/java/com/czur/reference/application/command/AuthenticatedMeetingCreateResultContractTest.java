package com.czur.reference.application.command;

import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingOwnership;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 认证会议创建应用结果的联合分支、不变量和脱敏输出契约。 */
class AuthenticatedMeetingCreateResultContractTest {
    private static final Instant CREATED = Instant.parse("2026-09-20T01:00:00Z");

    @Test
    void successCarriesCreatedAndReplayedCodesAndOwnedCreateSnapshotOnly() {
        MeetingCommandResult result = createdResult();

        assertEquals(result,
                new AuthenticatedMeetingCreateResult.Success(
                        AuthenticatedMeetingCreateResult.SuccessCode.CREATED, result).result());
        assertEquals(AuthenticatedMeetingCreateResult.SuccessCode.REPLAYED,
                new AuthenticatedMeetingCreateResult.Success(
                        AuthenticatedMeetingCreateResult.SuccessCode.REPLAYED, result).code());
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticatedMeetingCreateResult.Success(
                        AuthenticatedMeetingCreateResult.SuccessCode.CREATED,
                        new MeetingCommandResult(
                                MeetingCommandResult.Operation.STOP_ACCEPTED,
                                result.meeting())));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticatedMeetingCreateResult.Success(
                        AuthenticatedMeetingCreateResult.SuccessCode.CREATED,
                        ownedResult(MeetingState.RUNNING, CREATED, CREATED)));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticatedMeetingCreateResult.Success(
                        AuthenticatedMeetingCreateResult.SuccessCode.CREATED,
                        ownedResult(MeetingState.CREATED, CREATED, CREATED.plusSeconds(1))));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticatedMeetingCreateResult.Success(
                        AuthenticatedMeetingCreateResult.SuccessCode.CREATED,
                        legacyResult()));
    }

    @Test
    void providerFailuresKeepOnlyStableProviderErrors() {
        IdentityProviderError identityError = new IdentityProviderError(
                IdentityProviderError.Code.INVALID_CREDENTIAL, false);
        EntitlementProviderError entitlementError = new EntitlementProviderError(
                EntitlementProviderError.Code.ENTITLEMENT_DENIED, false);

        assertEquals(identityError,
                new AuthenticatedMeetingCreateResult.IdentityFailure(identityError).error());
        assertEquals(entitlementError,
                new AuthenticatedMeetingCreateResult.EntitlementFailure(entitlementError)
                        .error());
        assertThrows(NullPointerException.class,
                () -> new AuthenticatedMeetingCreateResult.IdentityFailure(null));
        assertThrows(NullPointerException.class,
                () -> new AuthenticatedMeetingCreateResult.EntitlementFailure(null));
    }

    @Test
    void unavailableAndInconsistentRequireStableStageAndReason() {
        var unavailable = new AuthenticatedMeetingCreateResult.Unavailable(
                AuthenticatedMeetingCreateResult.Stage.MEETING_REPLAY_LOOKUP, true);
        var inconsistent = new AuthenticatedMeetingCreateResult.Inconsistent(
                AuthenticatedMeetingCreateResult.Stage.USAGE_RESERVE_LOOKUP,
                AuthenticatedMeetingCreateResult.InconsistencyReason
                        .OWNED_RESERVE_INVARIANT_VIOLATION);

        assertTrue(unavailable.retryable());
        assertEquals(AuthenticatedMeetingCreateResult.Stage.MEETING_REPLAY_LOOKUP,
                unavailable.stage());
        assertEquals(AuthenticatedMeetingCreateResult.InconsistencyReason
                        .OWNED_RESERVE_INVARIANT_VIOLATION,
                inconsistent.reason());
        assertThrows(NullPointerException.class,
                () -> new AuthenticatedMeetingCreateResult.Unavailable(null, true));
        assertThrows(NullPointerException.class,
                () -> new AuthenticatedMeetingCreateResult.Inconsistent(null,
                        AuthenticatedMeetingCreateResult.InconsistencyReason.STORED_OUTCOME_INVALID));
        assertThrows(NullPointerException.class,
                () -> new AuthenticatedMeetingCreateResult.Inconsistent(
                        AuthenticatedMeetingCreateResult.Stage.MEETING_RECOVERY, null));
    }

    @Test
    void creationFailureFreezesRetryableAndTerminalSemantics() {
        var terminal = new AuthenticatedMeetingCreateResult.CreationFailure(
                OwnedMeetingCreationUnitOfWork.ErrorCode.RESERVATION_EXPIRED,
                false, true);
        var retryable = new AuthenticatedMeetingCreateResult.CreationFailure(
                OwnedMeetingCreationUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE,
                true, false);
        var failClosedButUnknown = new AuthenticatedMeetingCreateResult.CreationFailure(
                OwnedMeetingCreationUnitOfWork.ErrorCode.INTERNAL,
                false, false);

        assertTrue(terminal.terminal());
        assertFalse(retryable.terminal());
        assertFalse(failClosedButUnknown.terminal());
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticatedMeetingCreateResult.CreationFailure(
                        OwnedMeetingCreationUnitOfWork.ErrorCode.MEETING_CONFLICT,
                        true, true));
        for (OwnedMeetingCreationUnitOfWork.ErrorCode code : EnumSet.of(
                OwnedMeetingCreationUnitOfWork.ErrorCode.STORAGE_UNAVAILABLE,
                OwnedMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                OwnedMeetingCreationUnitOfWork.ErrorCode.INTERNAL)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AuthenticatedMeetingCreateResult.CreationFailure(
                            code, false, true));
        }
        assertThrows(NullPointerException.class,
                () -> new AuthenticatedMeetingCreateResult.CreationFailure(null, false, false));
    }

    @Test
    void diagnosticsNeverExposeSensitiveMeetingOrProviderFacts() {
        String output = String.join("\n",
                new AuthenticatedMeetingCreateResult.Success(
                        AuthenticatedMeetingCreateResult.SuccessCode.CREATED,
                        createdResult()).toString(),
                new AuthenticatedMeetingCreateResult.IdentityFailure(
                        new IdentityProviderError(
                                IdentityProviderError.Code.INVALID_CREDENTIAL, false)).toString(),
                new AuthenticatedMeetingCreateResult.EntitlementFailure(
                        new EntitlementProviderError(
                                EntitlementProviderError.Code.ENTITLEMENT_DENIED, false)).toString(),
                new AuthenticatedMeetingCreateResult.Conflict().toString(),
                new AuthenticatedMeetingCreateResult.Unavailable(
                        AuthenticatedMeetingCreateResult.Stage.MEETING_CREATION, true).toString(),
                new AuthenticatedMeetingCreateResult.Inconsistent(
                        AuthenticatedMeetingCreateResult.Stage.MEETING_RECOVERY,
                        AuthenticatedMeetingCreateResult.InconsistencyReason.STORED_OUTCOME_INVALID)
                        .toString(),
                new AuthenticatedMeetingCreateResult.CreationFailure(
                        OwnedMeetingCreationUnitOfWork.ErrorCode.INTERNAL, false, false).toString());

        for (String secret : Set.of(
                "meeting-secret", "deployment-secret", "session-secret", "subject-secret",
                "grant-secret", "reservation-secret", "token-secret", "idempotency-secret",
                "digest-secret", "provider-exception-secret")) {
            assertFalse(output.contains(secret), "结果诊断信息泄露敏感值: " + secret);
        }
        assertTrue(output.contains("CREATED"));
        assertTrue(output.contains("INVALID_CREDENTIAL"));
    }

    private static MeetingCommandResult createdResult() {
        return ownedResult(MeetingState.CREATED, CREATED, CREATED);
    }

    private static MeetingCommandResult ownedResult(
            MeetingState state, Instant createdAt, Instant updatedAt) {
        return new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED,
                MeetingAggregate.MeetingSnapshot.owned(
                        "meeting-secret", "deployment-secret", "session-secret",
                        "subject-secret", "grant-secret", CREATED.plusSeconds(600),
                        "reservation-secret", "profile", 1, state, createdAt, updatedAt));
    }

    private static MeetingCommandResult legacyResult() {
        return new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED,
                MeetingAggregate.MeetingSnapshot.legacyUnowned(
                        "legacy", "deployment", "session", "profile", 1,
                        MeetingState.CREATED, CREATED, CREATED));
    }
}
