package com.czur.reference.application.command;

import com.czur.reference.application.port.CompleteMeetingCreateReplayLookup;
import com.czur.reference.application.port.CompleteMeetingCreationUnitOfWork;
import com.czur.reference.application.port.CreateRealtimeSessionAllocator;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.CancelReservationRequest;
import com.czur.reference.application.port.entitlement.CommitUsageRequest;
import com.czur.reference.application.port.entitlement.EntitlementProvider;
import com.czur.reference.application.port.entitlement.EntitlementProviderError;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageReserveLookup;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.identity.IdentityProviderError;
import com.czur.reference.application.port.identity.IdentityProviderResult;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 完整认证 Create 的短路、持久赢家、UNKNOWN 回查与凭据关闭门禁。 */
class AuthenticatedCompleteMeetingApplicationServiceTest {
    private static final String DEPLOYMENT = "deployment-secret";
    private static final String SUBJECT = "subject-secret";
    private static final String REALTIME = CapabilityIds.TRANSCRIPT_REALTIME;
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final UsageEstimate ESTIMATE =
            new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000);

    @Test
    void identityDenialAndMissingPermissionStopBeforeAnyFactReadAndCloseCredential() {
        var harness = new Harness();
        harness.identityResult = IdentityProviderResult.failure(new IdentityProviderError(
                IdentityProviderError.Code.INVALID_CREDENTIAL, false));
        var credential = secret();
        var denied = harness.service().create(verification(credential), harness.requestFact);
        assertInstanceOf(AuthenticatedCompleteMeetingCreateResult.IdentityFailure.class, denied);
        assertEquals(List.of("verify"), harness.calls);
        assertTrue(credential.isDestroyed());

        harness = new Harness();
        harness.identityResult = IdentityProviderResult.success(identity(Set.of()));
        credential = secret();
        var forbidden = harness.service().create(verification(credential), harness.requestFact);
        var failure = assertInstanceOf(
                AuthenticatedCompleteMeetingCreateResult.IdentityFailure.class, forbidden);
        assertEquals(IdentityProviderError.Code.PERMISSION_DENIED, failure.error().code());
        assertEquals(List.of("verify"), harness.calls);
        assertTrue(credential.isDestroyed());
    }

    @Test
    void credentialClosesEvenWhenProviderThrows() {
        var harness = new Harness();
        harness.throwDuringIdentity = true;
        var credential = secret();
        assertThrows(IllegalStateException.class,
                () -> harness.service().create(verification(credential), harness.requestFact));
        assertTrue(credential.isDestroyed());
        assertEquals(List.of("verify"), harness.calls);
    }

    @Test
    void v013ReplayIgnoresCurrentProfileAndNeverTouchesReserveOrToken() {
        var harness = new Harness();
        var stored = harness.storedResponse();
        harness.createReads.add(new CompleteMeetingCreateReplayLookup.Replayed(stored));
        harness.profile = new ProductProfile("changed-profile", 9,
                Set.of(REALTIME), Set.of(), Map.of(), Map.of(), Map.of());
        var credential = secret();
        var result = harness.service().create(verification(credential), harness.requestFact);
        var success = assertInstanceOf(AuthenticatedCompleteMeetingCreateResult.Success.class,
                result);
        assertEquals(AuthenticatedCompleteMeetingCreateResult.SuccessCode.REPLAYED,
                success.code());
        assertEquals(stored, success.response());
        assertEquals(List.of("verify", "create-replay"), harness.calls);
        assertTrue(credential.isDestroyed());
        assertFalse(result.toString().contains("stored-token-secret"));
    }

    @Test
    void v013ConflictInconsistentAndUnavailableAllShortCircuit() {
        var cases = List.<CompleteMeetingCreateReplayLookup.Result>of(
                new CompleteMeetingCreateReplayLookup.Conflict(),
                new CompleteMeetingCreateReplayLookup.Inconsistent(),
                new CompleteMeetingCreateReplayLookup.Unavailable());
        for (var read : cases) {
            var harness = new Harness();
            harness.createReads.add(read);
            var credential = secret();
            var result = harness.service().create(verification(credential), harness.requestFact);
            if (read instanceof CompleteMeetingCreateReplayLookup.Conflict) {
                assertInstanceOf(AuthenticatedCompleteMeetingCreateResult.Conflict.class, result);
            } else if (read instanceof CompleteMeetingCreateReplayLookup.Inconsistent) {
                assertInstanceOf(AuthenticatedCompleteMeetingCreateResult.Inconsistent.class,
                        result);
            } else {
                assertInstanceOf(AuthenticatedCompleteMeetingCreateResult.Unavailable.class,
                        result);
            }
            assertEquals(List.of("verify", "create-replay"), harness.calls);
            assertTrue(credential.isDestroyed());
        }
    }

    @Test
    void existingReserveWinnerSkipsNewGrantAndCandidateId() {
        var harness = new Harness();
        harness.reserveReads.add(new UsageReserveLookup.Replayed(harness.winner));
        var credential = secret();
        var result = harness.service().create(verification(credential), harness.requestFact);
        var success = assertInstanceOf(AuthenticatedCompleteMeetingCreateResult.Success.class,
                result);
        assertEquals(AuthenticatedCompleteMeetingCreateResult.SuccessCode.CREATED,
                success.code());
        assertEquals(List.of("verify", "create-replay", "reserve-lookup", "profile",
                "allocate", "uow"),
                harness.calls);
        assertEquals("mtg_first_winner", harness.createRequest.responseFact().meetingId());
        assertEquals(harness.winner, harness.createRequest.reserveResult());
        assertTrue(credential.isDestroyed());
    }

    @Test
    void firstReserveUsesTrustedEstimateAndReadBackWinnerNotProviderCandidate() {
        var harness = new Harness();
        harness.reserveReads.add(new UsageReserveLookup.NotFound());
        harness.reserveReads.add(new UsageReserveLookup.Replayed(harness.winner));
        var credential = secret();
        var result = harness.service().create(verification(credential), harness.requestFact);
        var success = assertInstanceOf(AuthenticatedCompleteMeetingCreateResult.Success.class,
                result);
        assertEquals(List.of("verify", "create-replay", "reserve-lookup", "profile", "authorize",
                "meeting-id", "reserve", "reserve-lookup", "allocate", "uow"), harness.calls);
        assertEquals(ESTIMATE, harness.authorizationRequest.estimatedUsage());
        assertEquals(ESTIMATE, harness.reserveRequest.estimate());
        assertEquals(CompleteMeetingReserveFacts.internalReserveKey(harness.requestFact),
                harness.reserveRequest.idempotencyKey());
        assertEquals(harness.requestFact.canonicalRequestDigest(),
                harness.reserveRequest.requestDigest());
        assertNotEquals(harness.reserveRequest.expectedMeetingId(),
                harness.createRequest.responseFact().meetingId());
        assertEquals("mtg_first_winner", success.response().meetingId());
        assertTrue(credential.isDestroyed());
    }

    @Test
    void persistedReserveIsReadBeforeProfileVersionAndCanUseHistoricalVersion() {
        var harness = new Harness();
        harness.reserveReads.add(new UsageReserveLookup.Replayed(harness.winner));
        harness.profile = new ProductProfile("standard", 2, Set.of(REALTIME), Set.of(),
                Map.of(), Map.of(), Map.of());
        var missing = harness.service().create(verification(secret()), harness.requestFact);
        var unavailable = assertInstanceOf(
                AuthenticatedCompleteMeetingCreateResult.Unavailable.class, missing);
        assertEquals(AuthenticatedCompleteMeetingCreateResult.Stage.PROFILE,
                unavailable.stage());
        assertEquals(List.of("verify", "create-replay", "reserve-lookup", "profile"),
                harness.calls);

        harness = new Harness();
        harness.reserveReads.add(new UsageReserveLookup.Replayed(harness.winner));
        harness.historicalProfile = harness.profile;
        harness.profile = new ProductProfile("standard", 2, Set.of(REALTIME), Set.of(),
                Map.of(), Map.of(), Map.of());
        var resumed = harness.service().create(verification(secret()), harness.requestFact);
        assertInstanceOf(AuthenticatedCompleteMeetingCreateResult.Success.class, resumed);
        assertEquals(List.of("verify", "create-replay", "reserve-lookup", "profile",
                "allocate", "uow"), harness.calls);
        assertFalse(harness.calls.contains("authorize"));
    }

    @Test
    void unitOfWorkReplayReturnsItsPersistentTokenNotCandidate() {
        var harness = new Harness();
        harness.reserveReads.add(new UsageReserveLookup.Replayed(harness.winner));
        var stored = harness.storedResponse();
        harness.createResult = new CompleteMeetingCreationUnitOfWork.Success(
                CompleteMeetingCreationUnitOfWork.SuccessCode.REPLAYED, stored);
        var result = harness.service().create(verification(secret()), harness.requestFact);
        var success = assertInstanceOf(AuthenticatedCompleteMeetingCreateResult.Success.class,
                result);
        assertEquals(AuthenticatedCompleteMeetingCreateResult.SuccessCode.REPLAYED,
                success.code());
        assertEquals("stored-token-secret", success.response().realtime().sessionToken());
        assertNotEquals(harness.createRequest.responseFact().realtime().sessionToken(),
                success.response().realtime().sessionToken());
        assertFalse(result.toString().contains("stored-token-secret"));
    }

    @Test
    void unitOfWorkIdempotencyConflictDoesNotReturnCandidateToken() {
        var harness = new Harness();
        harness.reserveReads.add(new UsageReserveLookup.Replayed(harness.winner));
        harness.createResult = new CompleteMeetingCreationUnitOfWork.Failure(
                CompleteMeetingCreationUnitOfWork.ErrorCode.IDEMPOTENCY_CONFLICT, false);
        var result = harness.service().create(verification(secret()), harness.requestFact);
        assertInstanceOf(AuthenticatedCompleteMeetingCreateResult.Conflict.class, result);
        assertFalse(result.toString().contains("candidate-token-secret"));
        assertEquals(1, harness.calls.stream().filter("create-replay"::equals).count());
        assertEquals(0, harness.cancelCalls);
    }

    @Test
    void authorizationDenialAfterMissStillChecksForConcurrentReserveWinner() {
        var harness = new Harness();
        harness.reserveReads.add(new UsageReserveLookup.NotFound());
        harness.reserveReads.add(new UsageReserveLookup.Replayed(harness.winner));
        harness.authorizationResult = EntitlementProviderResult.failure(
                new EntitlementProviderError(
                        EntitlementProviderError.Code.ENTITLEMENT_DENIED, false));
        var result = harness.service().create(verification(secret()), harness.requestFact);
        assertInstanceOf(AuthenticatedCompleteMeetingCreateResult.Success.class, result);
        assertEquals(List.of("verify", "create-replay", "reserve-lookup", "profile",
                "authorize", "reserve-lookup", "allocate", "uow"), harness.calls);
        assertFalse(harness.calls.contains("reserve"));
    }

    @Test
    void overlongGeneratedMeetingIdCannotReachEntitlementReserve() {
        var harness = new Harness();
        harness.reserveReads.add(new UsageReserveLookup.NotFound());
        harness.candidateMeetingId = "mtg_" + "a".repeat(1_100);
        var result = harness.service().create(verification(secret()), harness.requestFact);
        var failure = assertInstanceOf(
                AuthenticatedCompleteMeetingCreateResult.Inconsistent.class, result);
        assertEquals(AuthenticatedCompleteMeetingCreateResult.Stage.ENTITLEMENT,
                failure.stage());
        assertFalse(harness.calls.contains("reserve"));
        assertFalse(harness.calls.contains("allocate"));
    }

    @Test
    void grantOutsideProfileCannotCreatePermanentReserve() {
        var harness = new Harness();
        harness.reserveReads.add(new UsageReserveLookup.NotFound());
        var expanded = new MeetingGrant("expanded-grant", DEPLOYMENT, SUBJECT,
                "standard", 1, Set.of(REALTIME),
                Set.of(REALTIME, CapabilityIds.TRANSLATION_REALTIME), 3_600_000,
                NOW.minusSeconds(10), NOW.plusSeconds(600));
        harness.authorizationResult = EntitlementProviderResult.success(expanded);
        var result = harness.service().create(verification(secret()), harness.requestFact);
        var failure = assertInstanceOf(
                AuthenticatedCompleteMeetingCreateResult.Inconsistent.class, result);
        assertEquals(AuthenticatedCompleteMeetingCreateResult.Stage.ENTITLEMENT,
                failure.stage());
        assertFalse(harness.calls.contains("meeting-id"));
        assertFalse(harness.calls.contains("reserve"));
        assertFalse(harness.calls.contains("allocate"));
    }

    @Test
    void missingRequiredGrantFailsBeforeReserve() {
        var harness = new Harness();
        harness.reserveReads.add(new UsageReserveLookup.NotFound());
        harness.profile = new ProductProfile("standard", 1,
                Set.of(REALTIME, CapabilityIds.TRANSCRIPT_FINAL), Set.of(),
                Map.of(), Map.of(), Map.of());
        var result = harness.service().create(verification(secret()), harness.requestFact);
        var failure = assertInstanceOf(
                AuthenticatedCompleteMeetingCreateResult.PreparationFailure.class, result);
        assertEquals(CompleteMeetingResponseAssembler.FailureCode.CAPABILITY_UNAVAILABLE,
                failure.code());
        assertFalse(harness.calls.contains("meeting-id"));
        assertFalse(harness.calls.contains("reserve"));
    }

    @Test
    void throwingMeetingIdGeneratorReturnsFixedFailureWithoutReserve() {
        var harness = new Harness();
        harness.reserveReads.add(new UsageReserveLookup.NotFound());
        harness.throwDuringMeetingId = true;
        var credential = secret();
        var result = harness.service().create(verification(credential), harness.requestFact);
        var unavailable = assertInstanceOf(
                AuthenticatedCompleteMeetingCreateResult.Unavailable.class, result);
        assertEquals(AuthenticatedCompleteMeetingCreateResult.Stage.ENTITLEMENT,
                unavailable.stage());
        assertFalse(result.toString().contains("secret-generator-error"));
        assertFalse(harness.calls.contains("reserve"));
        assertTrue(credential.isDestroyed());
    }

    @Test
    void throwingProfileCatalogFailsClosedAfterReserveLookup() {
        var harness = new Harness();
        harness.reserveReads.add(new UsageReserveLookup.Replayed(harness.winner));
        harness.throwDuringProfileLookup = true;
        var credential = secret();
        var result = harness.service().create(verification(credential), harness.requestFact);
        var unavailable = assertInstanceOf(
                AuthenticatedCompleteMeetingCreateResult.Unavailable.class, result);
        assertEquals(AuthenticatedCompleteMeetingCreateResult.Stage.PROFILE,
                unavailable.stage());
        assertEquals(List.of("verify", "create-replay", "reserve-lookup", "profile"),
                harness.calls);
        assertFalse(result.toString().contains("secret-catalog-error"));
        assertTrue(credential.isDestroyed());
    }

    @Test
    void reserveClaimingSuccessWithoutReadableFactFailsBeforeTokenOrUow() {
        var harness = new Harness();
        harness.reserveReads.add(new UsageReserveLookup.NotFound());
        harness.reserveReads.add(new UsageReserveLookup.NotFound());
        var result = harness.service().create(verification(secret()), harness.requestFact);
        var failure = assertInstanceOf(
                AuthenticatedCompleteMeetingCreateResult.Inconsistent.class, result);
        assertEquals(AuthenticatedCompleteMeetingCreateResult.Stage.RESERVE_LOOKUP,
                failure.stage());
        assertFalse(harness.calls.contains("allocate"));
        assertFalse(harness.calls.contains("uow"));
    }

    @Test
    void unavailableReserveReadAndUnsupportedRealtimeCannotIssueToken() {
        var harness = new Harness();
        harness.reserveReads.add(new UsageReserveLookup.Unavailable(
                UsageReserveLookup.UnavailableReason.TRANSIENT_STORAGE_FAILURE, true));
        var unavailable = harness.service().create(verification(secret()), harness.requestFact);
        assertInstanceOf(AuthenticatedCompleteMeetingCreateResult.Unavailable.class, unavailable);
        assertEquals(List.of("verify", "create-replay", "reserve-lookup"), harness.calls);

        harness = new Harness();
        harness.reserveReads.add(new UsageReserveLookup.Replayed(harness.winner));
        harness.providerCapabilities = new CapabilityDescriptor(Set.of());
        var rejected = harness.service().create(verification(secret()), harness.requestFact);
        assertInstanceOf(AuthenticatedCompleteMeetingCreateResult.PreparationFailure.class,
                rejected);
        assertFalse(harness.calls.contains("allocate"));
        assertFalse(harness.calls.contains("uow"));
    }

    @Test
    void unknownCommitReadBackReturnsOnlyPersistentWinnerOrFixedFailure() {
        for (int scenario = 0; scenario < 5; scenario++) {
            var harness = new Harness();
            harness.reserveReads.add(new UsageReserveLookup.Replayed(harness.winner));
            harness.createResult = new CompleteMeetingCreationUnitOfWork.Failure(
                    CompleteMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN, true);
            if (scenario == 0) {
                harness.createReads.add(new CompleteMeetingCreateReplayLookup.NotFound());
                harness.createReads.add(new CompleteMeetingCreateReplayLookup.Replayed(
                        harness.storedResponse()));
            } else {
                harness.createReads.add(new CompleteMeetingCreateReplayLookup.NotFound());
                harness.createReads.add(switch (scenario) {
                    case 1 -> new CompleteMeetingCreateReplayLookup.Conflict();
                    case 2 -> new CompleteMeetingCreateReplayLookup.NotFound();
                    case 3 -> new CompleteMeetingCreateReplayLookup.Unavailable();
                    default -> new CompleteMeetingCreateReplayLookup.Inconsistent();
                });
            }
            var credential = secret();
            var result = harness.service().create(verification(credential),
                    harness.requestFact);
            assertEquals(List.of("verify", "create-replay", "reserve-lookup", "profile",
                    "allocate", "uow", "create-replay"), harness.calls);
            if (scenario == 0) {
                var success = assertInstanceOf(
                        AuthenticatedCompleteMeetingCreateResult.Success.class, result);
                assertEquals(harness.storedResponse().realtime().sessionToken(),
                        success.response().realtime().sessionToken());
                assertNotEquals(harness.createRequest.responseFact().realtime().sessionToken(),
                        success.response().realtime().sessionToken());
            } else if (scenario == 1) {
                assertInstanceOf(AuthenticatedCompleteMeetingCreateResult.Conflict.class, result);
            } else if (scenario == 4) {
                assertInstanceOf(AuthenticatedCompleteMeetingCreateResult.Inconsistent.class,
                        result);
            } else {
                var failure = assertInstanceOf(
                        AuthenticatedCompleteMeetingCreateResult.CreationFailure.class,
                        result);
                assertEquals(CompleteMeetingCreationUnitOfWork.ErrorCode.OUTCOME_UNKNOWN,
                        failure.code());
            }
            assertFalse(result.toString().contains("candidate-token-secret"));
            assertTrue(credential.isDestroyed());
            assertEquals(0, harness.cancelCalls);
        }
    }

    private static SecretCredential secret() {
        return SecretCredential.copyOf(new byte[]{1, 2, 3});
    }

    private static IdentityVerificationRequest verification(SecretCredential credential) {
        return new IdentityVerificationRequest(
                IdentityVerificationRequest.CredentialType.BEARER_TOKEN,
                credential, "meeting-api", "android-app");
    }

    private static VerifiedIdentity identity(Set<String> permissions) {
        return new VerifiedIdentity(new Subject(SUBJECT), Set.of(), permissions,
                NOW.minusSeconds(30), NOW.plusSeconds(300), null);
    }

    private static CreateMeetingRequestFact requestFact() {
        var snapshot = new CreateMeetingRequestSnapshot("client-session",
                new CreateMeetingRequestSnapshot.ProfileRef("standard", 1),
                new CreateMeetingRequestSnapshot.ClientDescriptor(
                        "app", "1", "v1", "device"), null, "Asia/Shanghai",
                new CreateMeetingRequestSnapshot.LanguageSelection("zh-CN", List.of()),
                AudioProfile.pcm16kMono(100), Set.of(REALTIME),
                new CreateMeetingRequestSnapshot.ArtifactPolicy(false, false),
                "create-external-key-12345");
        return new CreateMeetingRequestFact(snapshot,
                MeetingRequestCanonicalizer.digest(snapshot), 1);
    }

    private static MeetingGrant grant(CreateMeetingRequestFact requestFact) {
        return new MeetingGrant("grant-secret", DEPLOYMENT, SUBJECT,
                "standard", 1, requestFact.requestSnapshot().requestedCapabilities(),
                requestFact.requestSnapshot().requestedCapabilities(), 3_600_000,
                NOW.minusSeconds(10), NOW.plusSeconds(600));
    }

    private static UsageOperationResult winner(CreateMeetingRequestFact requestFact,
            MeetingGrant grant) {
        var reservation = UsageReservation.reserved("reservation-first", grant,
                "mtg_first_winner", ESTIMATE, NOW, NOW.plusSeconds(300));
        return UsageOperationResult.reserve(DEPLOYMENT, SUBJECT,
                CompleteMeetingReserveFacts.internalReserveKey(requestFact),
                requestFact.canonicalRequestDigest(), grant.expiresAt(),
                AuthorizedProfileSnapshot.from(grant), reservation);
    }

    private static final class Harness implements EntitlementProvider {
        private final List<String> calls = new ArrayList<>();
        private final ArrayDeque<CompleteMeetingCreateReplayLookup.Result> createReads =
                new ArrayDeque<>();
        private final ArrayDeque<UsageReserveLookup.Result> reserveReads = new ArrayDeque<>();
        private final CreateMeetingRequestFact requestFact = requestFact();
        private final MeetingGrant grant = grant(requestFact);
        private final UsageOperationResult winner = winner(requestFact, grant);
        private IdentityProviderResult<VerifiedIdentity> identityResult =
                IdentityProviderResult.success(identity(Set.of(MeetingPermissions.CREATE)));
        private EntitlementProviderResult<MeetingGrant> authorizationResult;
        private boolean throwDuringIdentity;
        private ProductProfile profile = new ProductProfile("standard", 1,
                Set.of(REALTIME), Set.of(), Map.of(), Map.of(), Map.of());
        private ProductProfile historicalProfile;
        private CapabilityDescriptor providerCapabilities =
                new CapabilityDescriptor(Set.of(REALTIME));
        private String candidateMeetingId = "mtg_candidate_loser";
        private boolean throwDuringMeetingId;
        private boolean throwDuringProfileLookup;
        private CompleteMeetingCreationUnitOfWork.Result createResult;
        private CompleteMeetingCreationUnitOfWork.CreateRequest createRequest;
        private AuthorizeStartRequest authorizationRequest;
        private ReserveUsageRequest reserveRequest;
        private int cancelCalls;

        private AuthenticatedCompleteMeetingApplicationService service() {
            CreateRealtimeSessionAllocator allocator = request -> {
                calls.add("allocate");
                return new CreateMeetingResponseFact.RealtimeSession(
                        URI.create("wss://example.test/v1/realtime/" + request.meetingId()),
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                        "candidate-token-secret", NOW.plusSeconds(60));
            };
            var assembler = new CompleteMeetingResponseAssembler(new CapabilityPlanner(),
                    allocator, new TrustedCreateRuntimeLimits(120_000, 3_200, 128, null));
            return new AuthenticatedCompleteMeetingApplicationService(DEPLOYMENT,
                    request -> {
                        calls.add("verify");
                        if (throwDuringIdentity) {
                            throw new IllegalStateException("secret-identity-provider-error");
                        }
                        return identityResult;
                    }, request -> {
                        calls.add("create-replay");
                        return createReads.isEmpty()
                                ? new CompleteMeetingCreateReplayLookup.NotFound()
                                : createReads.removeFirst();
                    }, request -> {
                        calls.add("reserve-lookup");
                        return reserveReads.isEmpty()
                                ? new UsageReserveLookup.NotFound()
                                : reserveReads.removeFirst();
                    }, this, () -> {
                        calls.add("meeting-id");
                        if (throwDuringMeetingId) {
                            throw new IllegalStateException("secret-generator-error");
                        }
                        return candidateMeetingId;
                    }, assembler, request -> {
                        calls.add("uow");
                        createRequest = request;
                        return createResult == null
                                ? new CompleteMeetingCreationUnitOfWork.Success(
                                        CompleteMeetingCreationUnitOfWork.SuccessCode.CREATED,
                                        request.responseFact())
                                : createResult;
                    }, ESTIMATE, (id, revision) -> {
                        calls.add("profile");
                        if (throwDuringProfileLookup) {
                            throw new IllegalStateException("secret-catalog-error");
                        }
                        if (id.equals(profile.id()) && revision == profile.revision()) {
                            return Optional.of(profile);
                        }
                        if (historicalProfile != null && id.equals(historicalProfile.id())
                                && revision == historicalProfile.revision()) {
                            return Optional.of(historicalProfile);
                        }
                        return Optional.empty();
                    }, providerCapabilities, DeploymentLimits.NONE);
        }

        private CreateMeetingResponseFact storedResponse() {
            var plan = new CapabilityPlanner().plan(providerCapabilities,
                    new ProductProfile("standard", 1, Set.of(REALTIME), Set.of(),
                            Map.of(), Map.of(), Map.of()),
                    Set.of(REALTIME), Set.of(REALTIME), DeploymentLimits.NONE);
            return new CreateMeetingResponseFact("mtg_first_winner", MeetingState.CREATED,
                    requestFact.requestSnapshot().profile(), Set.of(REALTIME),
                    plan.decisions(), requestFact.requestSnapshot().audioProfile(),
                    new CreateMeetingResponseFact.RealtimeSession(
                            URI.create("wss://example.test/v1/realtime/mtg_first_winner"),
                            CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                            "stored-token-secret", NOW.plusSeconds(60)),
                    new CreateMeetingResponseFact.Limits(120_000, 3_200, 128, null));
        }

        @Override
        public EntitlementProviderResult<MeetingGrant> authorizeStart(
                AuthorizeStartRequest request) {
            calls.add("authorize");
            authorizationRequest = request;
            return authorizationResult == null
                    ? EntitlementProviderResult.success(grant) : authorizationResult;
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> reserveUsage(
                ReserveUsageRequest request) {
            calls.add("reserve");
            reserveRequest = request;
            return EntitlementProviderResult.success(winner);
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> commitUsage(
                CommitUsageRequest request) {
            throw new AssertionError("Create must not commit usage");
        }

        @Override
        public EntitlementProviderResult<UsageOperationResult> cancelReservation(
                CancelReservationRequest request) {
            cancelCalls++;
            throw new AssertionError("Create must not cancel reservation");
        }
    }
}
