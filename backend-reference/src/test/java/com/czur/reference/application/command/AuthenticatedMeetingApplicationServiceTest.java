package com.czur.reference.application.command;

import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.MeetingCreateOutcome;
import com.czur.reference.application.port.MeetingCreateOutcomeStore;
import com.czur.reference.application.port.MeetingCreateReplayLookup;
import com.czur.reference.application.port.MeetingIdGenerator;
import com.czur.reference.application.port.MeetingRecoveryLookup;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.CancelReservationRequest;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReserveLookup;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.identity.IdentityProvider;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 认证会议创建应用编排的顺序、短路、恢复和补偿门禁。 */
class AuthenticatedMeetingApplicationServiceTest {
    private static final String DEPLOYMENT = "deployment-test";
    private static final String SUBJECT = "subject-test";
    private static final String SESSION = "session-test";
    private static final String PROFILE = "profile-test";
    private static final String KEY = "create-key-test";
    private static final Instant NOW = Instant.parse("2026-09-20T01:00:00Z");
    private static final UsageEstimate ESTIMATE =
            new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000);

    @Test
    void firstCreateUsesFrozenOrderAndClosesCredential() {
        List<String> calls = new ArrayList<>();
        FakeEntitlement entitlement = new FakeEntitlement(calls);
        UsageOperationResult reserve = reserveResult("meeting-winner", "reservation-first");
        entitlement.grant = grant();
        entitlement.reserveResult = reserve;
        RecordingCreationUnitOfWork creation = new RecordingCreationUnitOfWork(calls);
        creation.result = new OwnedMeetingCreationUnitOfWork.Success(
                OwnedMeetingCreationUnitOfWork.SuccessCode.CREATED, createdResult("meeting-winner"));

        IdentityProvider identityProvider = request -> {
            calls.add("verify");
            return IdentityProviderResult.success(identity());
        };
        SecretCredential credential = SecretCredential.copyOf(new byte[]{1, 2, 3});
        AuthenticatedMeetingCreateResult result = service(
                calls, identityProvider, entitlement, creation,
                request -> {
                    calls.add("create-replay");
                    return new MeetingCreateReplayLookup.NotFound();
                },
                request -> {
                    calls.add("reserve-replay");
                    return new UsageReserveLookup.NotFound();
                },
                request -> new MeetingRecoveryLookup.NotFound(),
                noOutcomeStore()).create(request(credential), intent());

        assertInstanceOf(AuthenticatedMeetingCreateResult.Success.class, result);
        assertEquals(List.of("verify", "create-replay", "reserve-replay", "authorize",
                        "meeting-id", "reserve", "create"), calls);
        assertTrue(credential.isDestroyed());
    }

    @Test
    void identityAndPermissionFailuresShortCircuitBeforeDigestAndLookups() {
        List<String> calls = new ArrayList<>();
        IdentityProvider denied = request -> {
            calls.add("verify");
            return IdentityProviderResult.failure(new IdentityProviderError(
                    IdentityProviderError.Code.INVALID_CREDENTIAL, false));
        };
        SecretCredential credential = SecretCredential.copyOf(new byte[]{4});
        AuthenticatedMeetingCreateResult result = service(calls, denied,
                new FakeEntitlement(calls), new RecordingCreationUnitOfWork(calls),
                request -> failLookup(), request -> failReserveLookup(),
                request -> new MeetingRecoveryLookup.NotFound(), noOutcomeStore())
                .create(request(credential), intent());
        assertInstanceOf(AuthenticatedMeetingCreateResult.IdentityFailure.class, result);
        assertEquals(List.of("verify"), calls);
        assertTrue(credential.isDestroyed());

        calls.clear();
        IdentityProvider noPermission = request -> {
            calls.add("verify");
            return IdentityProviderResult.success(identityWithoutCreatePermission());
        };
        credential = SecretCredential.copyOf(new byte[]{5});
        result = service(calls, noPermission, new FakeEntitlement(calls),
                new RecordingCreationUnitOfWork(calls), request -> failLookup(),
                request -> failReserveLookup(), request -> new MeetingRecoveryLookup.NotFound(),
                noOutcomeStore()).create(request(credential), intent());
        AuthenticatedMeetingCreateResult.IdentityFailure failure = assertInstanceOf(
                AuthenticatedMeetingCreateResult.IdentityFailure.class, result);
        assertEquals(IdentityProviderError.Code.PERMISSION_DENIED, failure.error().code());
        assertEquals(List.of("verify"), calls);
        assertTrue(credential.isDestroyed());
    }

    @Test
    void reserveReplayUsesWinnerWithoutAuthorizeOrNewId() {
        List<String> calls = new ArrayList<>();
        FakeEntitlement entitlement = new FakeEntitlement(calls);
        RecordingCreationUnitOfWork creation = new RecordingCreationUnitOfWork(calls);
        creation.result = new OwnedMeetingCreationUnitOfWork.Success(
                OwnedMeetingCreationUnitOfWork.SuccessCode.REPLAYED,
                createdResult("meeting-winner"));
        UsageOperationResult winner = reserveResult("meeting-winner", "reservation-winner");
        SecretCredential credential = SecretCredential.copyOf(new byte[]{6});
        AuthenticatedMeetingCreateResult result = service(calls,
                request -> {
                    calls.add("verify");
                    return IdentityProviderResult.success(identity());
                }, entitlement, creation,
                request -> {
                    calls.add("create-replay");
                    return new MeetingCreateReplayLookup.NotFound();
                }, request -> {
                    calls.add("reserve-replay");
                    return new UsageReserveLookup.Replayed(winner);
                }, request -> new MeetingRecoveryLookup.NotFound(), noOutcomeStore())
                .create(request(credential), intent());

        assertInstanceOf(AuthenticatedMeetingCreateResult.Success.class, result);
        assertEquals(List.of("verify", "create-replay", "reserve-replay", "create"), calls);
        assertEquals("meeting-winner", creation.request.meetingId());
        assertTrue(credential.isDestroyed());
    }

    @Test
    void terminalFailureCompetesOutcomeBeforeConditionalCancel() {
        List<String> calls = new ArrayList<>();
        FakeEntitlement entitlement = new FakeEntitlement(calls);
        entitlement.grant = grant();
        entitlement.reserveResult = reserveResult("meeting-failed", "reservation-failed");
        entitlement.cancelled = entitlement.reserveResult.reservation().cancel(
                "MEETING_CREATE_RESERVATION_EXPIRED", NOW.plusSeconds(1), 0);
        RecordingCreationUnitOfWork creation = new RecordingCreationUnitOfWork(calls);
        creation.result = new OwnedMeetingCreationUnitOfWork.Failure(
                OwnedMeetingCreationUnitOfWork.ErrorCode.RESERVATION_EXPIRED, false);
        CapturingOutcomeStore outcomes = new CapturingOutcomeStore();
        SecretCredential credential = SecretCredential.copyOf(new byte[]{7});
        AuthenticatedMeetingCreateResult result = service(calls,
                request -> {
                    calls.add("verify");
                    return IdentityProviderResult.success(identity());
                }, entitlement, creation,
                request -> new MeetingCreateReplayLookup.NotFound(),
                request -> new UsageReserveLookup.NotFound(),
                request -> new MeetingRecoveryLookup.NotFound(), outcomes)
                .create(request(credential), intent());

        AuthenticatedMeetingCreateResult.CreationFailure failure = assertInstanceOf(
                AuthenticatedMeetingCreateResult.CreationFailure.class, result);
        assertTrue(failure.terminal());
        assertEquals(1, outcomes.reserveCount);
        assertTrue(entitlement.cancelRequest != null);
        assertEquals(UsageOperationScopes.CANCEL,
                entitlement.cancelRequest.operationScope());
        assertEquals(KEY, entitlement.cancelRequest.idempotencyKey());
        assertEquals(MeetingRequestCanonicalizer.digest(intent()),
                entitlement.cancelRequest.requestDigest());
        assertEquals("meeting-failed", entitlement.cancelRequest.expectedMeetingId());
        assertEquals(0, entitlement.cancelRequest.expectedVersion());
        assertEquals("reservation-failed", entitlement.cancelRequest.reservationId());
        assertEquals("MEETING_CREATE_RESERVATION_EXPIRED",
                entitlement.cancelRequest.reasonCode());
        assertTrue(credential.isDestroyed());
    }

    @Test
    void outcomeCompetitionReplayWinsAndDoesNotCancel() {
        List<String> calls = new ArrayList<>();
        FakeEntitlement entitlement = new FakeEntitlement(calls);
        entitlement.grant = grant();
        entitlement.reserveResult = reserveResult("meeting-failed", "reservation-failed");
        RecordingCreationUnitOfWork creation = new RecordingCreationUnitOfWork(calls);
        creation.result = new OwnedMeetingCreationUnitOfWork.Failure(
                OwnedMeetingCreationUnitOfWork.ErrorCode.RESERVATION_EXPIRED, false);
        CapturingOutcomeStore outcomes = new CapturingOutcomeStore();
        outcomes.status = MeetingCreateOutcomeStore.ReservationStatus.REPLAY;
        outcomes.replayOutcome = MeetingCreateOutcome.failure(
                new com.czur.reference.application.port.MeetingCreateFailure(
                        OwnedMeetingCreationUnitOfWork.ErrorCode.RESERVATION_EXPIRED, false));
        SecretCredential credential = SecretCredential.copyOf(new byte[]{8});
        AuthenticatedMeetingCreateResult result = service(calls,
                request -> IdentityProviderResult.success(identity()), entitlement, creation,
                request -> new MeetingCreateReplayLookup.NotFound(),
                request -> new UsageReserveLookup.NotFound(),
                request -> new MeetingRecoveryLookup.NotFound(), outcomes)
                .create(request(credential), intent());
        assertInstanceOf(AuthenticatedMeetingCreateResult.CreationFailure.class, result);
        assertEquals(0, entitlement.cancelCount);
        assertTrue(credential.isDestroyed());
    }

    @Test
    void outcomeCompetitionConflictShortCircuitsWithoutCancel() {
        List<String> calls = new ArrayList<>();
        FakeEntitlement entitlement = new FakeEntitlement(calls);
        entitlement.grant = grant();
        entitlement.reserveResult = reserveResult("meeting-failed", "reservation-failed");
        RecordingCreationUnitOfWork creation = new RecordingCreationUnitOfWork(calls);
        creation.result = new OwnedMeetingCreationUnitOfWork.Failure(
                OwnedMeetingCreationUnitOfWork.ErrorCode.RESERVATION_EXPIRED, false);
        CapturingOutcomeStore outcomes = new CapturingOutcomeStore();
        outcomes.status = MeetingCreateOutcomeStore.ReservationStatus.CONFLICT;
        SecretCredential credential = SecretCredential.copyOf(new byte[]{10});
        AuthenticatedMeetingCreateResult result = service(calls,
                request -> IdentityProviderResult.success(identity()), entitlement, creation,
                request -> new MeetingCreateReplayLookup.NotFound(),
                request -> new UsageReserveLookup.NotFound(),
                request -> new MeetingRecoveryLookup.NotFound(), outcomes)
                .create(request(credential), intent());
        assertInstanceOf(AuthenticatedMeetingCreateResult.Conflict.class, result);
        assertEquals(0, entitlement.cancelCount);
        assertTrue(credential.isDestroyed());
    }

    @Test
    void unknownCommitAndRecoveryNotFoundNeverWriteFailureOrCancel() {
        List<String> calls = new ArrayList<>();
        FakeEntitlement entitlement = new FakeEntitlement(calls);
        entitlement.grant = grant();
        entitlement.reserveResult = reserveResult("meeting-unknown", "reservation-unknown");
        RecordingCreationUnitOfWork creation = new RecordingCreationUnitOfWork(calls);
        creation.result = new OwnedMeetingCreationUnitOfWork.Failure(
                OwnedMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, true);
        CapturingOutcomeStore outcomes = new CapturingOutcomeStore();
        SecretCredential credential = SecretCredential.copyOf(new byte[]{9});
        AuthenticatedMeetingCreateResult result = service(calls,
                request -> IdentityProviderResult.success(identity()), entitlement, creation,
                request -> new MeetingCreateReplayLookup.NotFound(),
                request -> new UsageReserveLookup.NotFound(),
                request -> new MeetingRecoveryLookup.NotFound(), outcomes)
                .create(request(credential), intent());
        AuthenticatedMeetingCreateResult.CreationFailure failure = assertInstanceOf(
                AuthenticatedMeetingCreateResult.CreationFailure.class, result);
        assertEquals(OwnedMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, failure.code());
        assertFalse(failure.terminal());
        assertEquals(0, outcomes.reserveCount);
        assertEquals(0, entitlement.cancelCount);
        assertTrue(credential.isDestroyed());
    }

    private static AuthenticatedMeetingApplicationService service(
            List<String> calls,
            IdentityProvider identity,
            FakeEntitlement entitlement,
            RecordingCreationUnitOfWork creation,
            MeetingCreateReplayLookup replay,
            UsageReserveLookup reserve,
            MeetingRecoveryLookup recovery,
            MeetingCreateOutcomeStore outcomes) {
        return new AuthenticatedMeetingApplicationService(
                DEPLOYMENT, identity, entitlement,
                () -> {
                    calls.add("meeting-id");
                    return "meeting-generated";
                }, replay, reserve, creation, recovery, outcomes);
    }

    private static IdentityVerificationRequest request(SecretCredential credential) {
        return new IdentityVerificationRequest(
                IdentityVerificationRequest.CredentialType.BEARER_TOKEN,
                credential, "audience", "client");
    }

    private static CreateMeetingIntent intent() {
        return new CreateMeetingIntent(
                SESSION, PROFILE, 1, Set.of("transcript.realtime"), ESTIMATE, KEY);
    }

    private static VerifiedIdentity identity() {
        return new VerifiedIdentity(new Subject(SUBJECT), Set.of(),
                Set.of(MeetingPermissions.CREATE), NOW, NOW.plusSeconds(300), null);
    }

    private static VerifiedIdentity identityWithoutCreatePermission() {
        return new VerifiedIdentity(new Subject(SUBJECT), Set.of(), Set.of(),
                NOW, NOW.plusSeconds(300), null);
    }

    private static MeetingGrant grant() {
        return new MeetingGrant("grant-test", DEPLOYMENT, SUBJECT, PROFILE, 1,
                Set.of("transcript.realtime"), Set.of("transcript.realtime"),
                3_600_000, NOW, NOW.plusSeconds(300));
    }

    private static UsageOperationResult reserveResult(String meetingId, String reservationId) {
        MeetingGrant grant = grant();
        UsageReservation reservation = UsageReservation.reserved(
                reservationId, grant, meetingId, ESTIMATE, NOW, NOW.plusSeconds(120));
        return UsageOperationResult.reserve(
                DEPLOYMENT, SUBJECT, KEY, MeetingRequestCanonicalizer.digest(intent()),
                grant.expiresAt(), AuthorizedProfileSnapshot.from(grant), reservation);
    }

    private static MeetingCommandResult createdResult(String meetingId) {
        return new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED,
                MeetingAggregate.MeetingSnapshot.owned(
                        meetingId, DEPLOYMENT, SESSION, SUBJECT, "grant-test",
                        NOW.plusSeconds(300), "reservation-first", PROFILE, 1,
                        MeetingState.CREATED, NOW, NOW));
    }

    private static MeetingCreateReplayLookup.Result failLookup() {
        throw new AssertionError("unexpected create replay lookup");
    }

    private static UsageReserveLookup.Result failReserveLookup() {
        throw new AssertionError("unexpected reserve replay lookup");
    }

    private static MeetingCreateOutcomeStore noOutcomeStore() {
        return new MeetingCreateOutcomeStore() {
            @Override
            public java.util.Optional<OutcomeRecord> find(String scope, String key) {
                return java.util.Optional.empty();
            }

            @Override
            public Reservation reserve(OutcomeRecord candidate) {
                throw new AssertionError("unexpected failure outcome reservation");
            }
        };
    }

    private static final class FakeEntitlement implements EntitlementProvider {
        private final List<String> calls;
        private MeetingGrant grant;
        private UsageOperationResult reserveResult;
        private UsageReservation cancelled;
        private CancelReservationRequest cancelRequest;
        private int cancelCount;

        private FakeEntitlement(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public EntitlementProviderResult<MeetingGrant> authorizeStart(
                com.czur.reference.application.port.entitlement.AuthorizeStartRequest request) {
            calls.add("authorize");
            return EntitlementProviderResult.success(grant == null ? grant() : grant);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> reserveUsage(
                ReserveUsageRequest request) {
            calls.add("reserve");
            return EntitlementProviderResult.success(reserveResult);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> commitUsage(
                com.czur.reference.application.port.entitlement.CommitUsageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> cancelReservation(
                CancelReservationRequest request) {
            cancelCount++;
            cancelRequest = request;
            return EntitlementProviderResult.success(
                    UsageOperationResult.terminal(request.deploymentScope(), request.subjectScope(),
                            UsageOperationScopes.CANCEL, request.idempotencyKey(),
                            request.requestDigest(), cancelled));
        }
    }

    private static final class RecordingCreationUnitOfWork
            implements OwnedMeetingCreationUnitOfWork {
        private final List<String> calls;
        private Result result;
        private CreateRequest request;

        private RecordingCreationUnitOfWork(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public Result create(CreateRequest request) {
            calls.add("create");
            this.request = request;
            return result;
        }
    }

    private static final class CapturingOutcomeStore implements MeetingCreateOutcomeStore {
        private ReservationStatus status = ReservationStatus.ACQUIRED;
        private MeetingCreateOutcome replayOutcome;
        private int reserveCount;

        @Override
        public java.util.Optional<OutcomeRecord> find(String scope, String key) {
            return java.util.Optional.empty();
        }

        @Override
        public Reservation reserve(OutcomeRecord candidate) {
            reserveCount++;
            OutcomeRecord record = replayOutcome == null
                    ? candidate
                    : new OutcomeRecord(candidate.scope(), candidate.key(),
                            candidate.requestDigest(), replayOutcome);
            return new Reservation(status, record);
        }
    }
}
