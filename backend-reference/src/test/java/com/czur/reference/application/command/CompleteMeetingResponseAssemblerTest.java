package com.czur.reference.application.command;

import com.czur.reference.application.http.CreateMeetingPublicResponseJson;
import com.czur.reference.application.port.CreateRealtimeSessionAllocator;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.protocol.json.CreateMeetingResponseFactJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 完整 Create 响应的能力计划、可信限制和拒绝前不签发门禁。 */
class CompleteMeetingResponseAssemblerTest {
    private static final String DEPLOYMENT = "deployment-secret";
    private static final String SUBJECT = "subject-secret";
    private static final String REALTIME = CapabilityIds.TRANSCRIPT_REALTIME;
    private static final String TRANSLATION = CapabilityIds.TRANSLATION_REALTIME;
    private static final String SUMMARY = CapabilityIds.SUMMARY_STRUCTURED;
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void asrOnlyProviderKeepsRealtimeAndDisablesOptionalTranslation() {
        var fixture = fixture(Set.of(REALTIME, TRANSLATION), 60_000);
        var allocator = new CountingAllocator();
        var result = assembler(allocator, 120_000, 3_200).assemble(input(
                fixture, profile(Set.of(REALTIME), Set.of(TRANSLATION)),
                Set.of(REALTIME), DeploymentLimits.NONE));
        var response = assertInstanceOf(CompleteMeetingResponseAssembler.Success.class,
                result).response();
        assertEquals("mtg_first_winner", response.meetingId());
        assertEquals(1, allocator.calls.get());
        assertEquals(1L, response.capabilityDecisions().getFirst().planRevision());
        assertEquals(CapabilityDecision.Action.ENABLE, response.capabilityDecisions().stream()
                .filter(decision -> REALTIME.equals(decision.capability()))
                .findFirst().orElseThrow().action());
        assertEquals(CapabilityDecision.Action.DISABLE, response.capabilityDecisions().stream()
                .filter(decision -> TRANSLATION.equals(decision.capability()))
                .findFirst().orElseThrow().action());
        assertEquals(fixture.request().requestSnapshot().audioProfile(),
                response.acceptedAudioProfile());
        assertEquals(120_000, response.limits().maxMeetingDurationMs(),
                "60 秒的 UsageEstimate 不是会议时长上限");
        assertEquals(3_200, response.limits().maxAudioFrameBytes());
        assertEquals(128, response.limits().eventReplayWindow());
        assertEquals(5_000L, response.limits().maxReplayAudioMs());
        assertEquals(Set.of(REALTIME, TRANSLATION), response.grantedCapabilities());
        assertEquals("mtg_first_winner", allocator.lastRequest.meetingId());
        assertEquals("reservation-first", allocator.lastRequest.reservationId());
        assertEquals(CreateMeetingResponseFactJson.encode(response),
                CreateMeetingResponseFactJson.encode(
                        CreateMeetingResponseFactJson.decode(
                                CreateMeetingResponseFactJson.encode(response))));
        assertFalse(result.toString().contains("local-token-secret"));
        assertFalse(result.toString().contains("deployment-secret"));
    }

    @Test
    void actualCreateProjectionClosesRequestedTranslationAndSummaryForAsrOnlyModel()
            throws Exception {
        Set<String> requested = Set.of(REALTIME, TRANSLATION, SUMMARY);
        var fixture = fixture(requested, 60_000);
        var result = assembler(new CountingAllocator(), 120_000, 3_200).assemble(input(
                fixture, profile(Set.of(REALTIME), Set.of(TRANSLATION, SUMMARY)),
                Set.of(REALTIME), DeploymentLimits.NONE));
        var response = assertInstanceOf(CompleteMeetingResponseAssembler.Success.class,
                result).response();
        var json = new ObjectMapper().readTree(CreateMeetingPublicResponseJson.encode(response));
        assertEquals(3, json.path("capabilityDecisions").size());
        for (String capability : Set.of(TRANSLATION, SUMMARY)) {
            var decision = java.util.stream.StreamSupport.stream(
                    json.path("capabilityDecisions").spliterator(), false)
                    .filter(item -> capability.equals(item.path("capability").asText()))
                    .findFirst().orElseThrow();
            assertEquals("UNAVAILABLE", decision.path("availability").asText());
            assertEquals("PROVIDER_CAPABILITY_MISSING",
                    decision.path("reasonCode").asText());
        }
        assertEquals("AVAILABLE", java.util.stream.StreamSupport.stream(
                json.path("capabilityDecisions").spliterator(), false)
                .filter(item -> REALTIME.equals(item.path("capability").asText()))
                .findFirst().orElseThrow().path("availability").asText());
    }

    @Test
    void optionalCrossMeetingIdentityUsesDeclaredFallbackWithoutInventingIdentity() {
        String identity = CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING;
        String diarization = CapabilityIds.SPEAKER_DIARIZATION_SESSION;
        var fixture = fixture(Set.of(REALTIME, identity),
                Set.of(REALTIME, identity, diarization), 60_000);
        var profile = new ProductProfile("standard", 1, Set.of(REALTIME),
                Set.of(identity, diarization), Map.of(),
                Map.of(identity, ProductProfile.MissingCapabilityPolicy.USE_FALLBACK),
                Map.of(identity, diarization),
                Map.of(diarization, List.of("session-diarization")),
                Map.of(diarization, List.of("speaker.diarization.session")));
        var allocator = new CountingAllocator();
        var response = assertInstanceOf(CompleteMeetingResponseAssembler.Success.class,
                assembler(allocator, 120_000, 3_200).assemble(input(
                        fixture, profile, Set.of(REALTIME, diarization),
                        DeploymentLimits.NONE))).response();
        var fallback = response.capabilityDecisions().stream()
                .filter(decision -> identity.equals(decision.capability()))
                .findFirst().orElseThrow();
        assertEquals(CapabilityDecision.Action.USE_FALLBACK, fallback.action());
        assertEquals(diarization, fallback.fallbackCapability());
        assertEquals(1L, fallback.planRevision());
        assertEquals(1, allocator.calls.get());
    }

    @Test
    void missingRealtimeFailsEvenIfProductMarksItOptional() {
        var fixture = fixture(Set.of(REALTIME), 60_000);
        var allocator = new CountingAllocator();
        var optionalRealtime = profile(Set.of(), Set.of(REALTIME));
        assertEquals(CompleteMeetingResponseAssembler.FailureCode.CAPABILITY_UNAVAILABLE,
                failure(assembler(allocator, 120_000, 3_200).assemble(input(
                        fixture, optionalRealtime, Set.of(), DeploymentLimits.NONE))).code());
        assertEquals(0, allocator.calls.get());
    }

    @Test
    void requiredCapabilityFailureAlsoFailsBeforeTokenAllocation() {
        var fixture = fixture(Set.of(REALTIME), 60_000);
        var allocator = new CountingAllocator();
        var requiredMore = profile(Set.of(REALTIME, CapabilityIds.TRANSCRIPT_FINAL), Set.of());
        assertEquals(CompleteMeetingResponseAssembler.FailureCode.CAPABILITY_UNAVAILABLE,
                failure(assembler(allocator, 120_000, 3_200).assemble(input(
                        fixture, requiredMore, Set.of(REALTIME), DeploymentLimits.NONE))).code());
        assertEquals(0, allocator.calls.get());
    }

    @Test
    void deploymentCanDisableRealtimeAndAudioBoundMustContainActualFrame() {
        var fixture = fixture(Set.of(REALTIME), 60_000);
        var allocator = new CountingAllocator();
        assertEquals(CompleteMeetingResponseAssembler.FailureCode.CAPABILITY_UNAVAILABLE,
                failure(assembler(allocator, 120_000, 3_200).assemble(input(
                        fixture, profile(Set.of(REALTIME), Set.of()), Set.of(REALTIME),
                        new DeploymentLimits(Set.of(REALTIME))))).code());
        assertEquals(CompleteMeetingResponseAssembler.FailureCode.AUDIO_UNSUPPORTED,
                failure(assembler(allocator, 120_000, 3_199).assemble(input(
                        fixture, profile(Set.of(REALTIME), Set.of()), Set.of(REALTIME),
                        DeploymentLimits.NONE))).code());
        assertEquals(0, allocator.calls.get());
    }

    @Test
    void checksWinnerAndProfileBeforeAllocatingAndMapsIssuerFailure() {
        var fixture = fixture(Set.of(REALTIME), 60_000);
        var allocator = new CountingAllocator();
        var profile = profile(Set.of(REALTIME), Set.of());
        var wrongIdentity = new CompleteMeetingResponseAssembler.Input(
                fixture.request(), fixture.winner(), DEPLOYMENT, "other-subject", profile,
                new CapabilityDescriptor(Set.of(REALTIME)), DeploymentLimits.NONE);
        assertEquals(CompleteMeetingResponseAssembler.FailureCode.WINNER_INCONSISTENT,
                failure(assembler(allocator, 120_000, 3_200).assemble(wrongIdentity)).code());
        var wrongProfile = new ProductProfile("other-profile", 1,
                Set.of(REALTIME), Set.of(), Map.of(), Map.of(), Map.of());
        assertEquals(CompleteMeetingResponseAssembler.FailureCode.PROFILE_MISMATCH,
                failure(assembler(allocator, 120_000, 3_200).assemble(input(
                        fixture, wrongProfile, Set.of(REALTIME), DeploymentLimits.NONE))).code());
        assertEquals(0, allocator.calls.get());
        allocator.fail = true;
        assertEquals(CompleteMeetingResponseAssembler.FailureCode.SESSION_UNAVAILABLE,
                failure(assembler(allocator, 120_000, 3_200).assemble(input(
                        fixture, profile, Set.of(REALTIME), DeploymentLimits.NONE))).code());
    }

    @Test
    void authorizationOutsideTrustedProfileFailsBeforeTokenAllocation() {
        var fixture = fixture(Set.of(REALTIME), Set.of(REALTIME, TRANSLATION), 60_000);
        var allocator = new CountingAllocator();
        var result = assembler(allocator, 120_000, 3_200).assemble(input(
                fixture, profile(Set.of(REALTIME), Set.of()),
                Set.of(REALTIME, TRANSLATION), DeploymentLimits.NONE));
        assertEquals(CompleteMeetingResponseAssembler.FailureCode.PROFILE_MISMATCH,
                failure(result).code());
        assertEquals(0, allocator.calls.get());
    }

    @Test
    void trustedRuntimeLimitsNeverExceedFirstAuthorizedProfile() {
        var limits = new TrustedCreateRuntimeLimits(7_200_000, 6_400, 128, null);
        assertEquals(3_600_000, limits.authorizedLimits(3_600_000)
                .maxMeetingDurationMs());
        assertEquals(120_000, limits.authorizedLimits(120_000)
                .maxMeetingDurationMs());
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedCreateRuntimeLimits(0, 6_400, 128, null));
        assertThrows(IllegalArgumentException.class,
                () -> limits.authorizedLimits(0));
    }

    private static CompleteMeetingResponseAssembler assembler(
            CreateRealtimeSessionAllocator allocator, long maxDurationMs, int maxFrameBytes) {
        return new CompleteMeetingResponseAssembler(new CapabilityPlanner(), allocator,
                new TrustedCreateRuntimeLimits(maxDurationMs, maxFrameBytes, 128, 5_000L));
    }

    private static CompleteMeetingResponseAssembler.Input input(Fixture fixture,
            ProductProfile profile, Set<String> providerCapabilities,
            DeploymentLimits limits) {
        return new CompleteMeetingResponseAssembler.Input(fixture.request(), fixture.winner(),
                DEPLOYMENT, SUBJECT, profile,
                new CapabilityDescriptor(providerCapabilities), limits);
    }

    private static CompleteMeetingResponseAssembler.Failure failure(
            CompleteMeetingResponseAssembler.Result result) {
        return assertInstanceOf(CompleteMeetingResponseAssembler.Failure.class, result);
    }

    private static ProductProfile profile(Set<String> required, Set<String> optional) {
        return new ProductProfile("standard", 1, required, optional,
                Map.of(), Map.of(), Map.of());
    }

    private static Fixture fixture(Set<String> requested, long estimateMs) {
        return fixture(requested, requested, estimateMs);
    }

    private static Fixture fixture(Set<String> requested, Set<String> granted,
            long estimateMs) {
        var snapshot = new CreateMeetingRequestSnapshot("client-session",
                new CreateMeetingRequestSnapshot.ProfileRef("standard", 1),
                new CreateMeetingRequestSnapshot.ClientDescriptor(
                        "app", "1", "v1", "device"), null, "Asia/Shanghai",
                new CreateMeetingRequestSnapshot.LanguageSelection("zh-CN", List.of()),
                AudioProfile.pcm16kMono(100), requested,
                new CreateMeetingRequestSnapshot.ArtifactPolicy(false, false),
                "create-external-key-12345");
        var request = new CreateMeetingRequestFact(snapshot,
                MeetingRequestCanonicalizer.digest(snapshot), 1);
        var grant = new MeetingGrant("grant-secret", DEPLOYMENT, SUBJECT,
                "standard", 1, requested, granted, 3_600_000,
                NOW.minusSeconds(10), NOW.plusSeconds(600));
        var reservation = UsageReservation.reserved("reservation-first", grant,
                "mtg_first_winner", new UsageEstimate(UsageUnit.MEETING_DURATION_MS, estimateMs),
                NOW, NOW.plusSeconds(300));
        var winner = UsageOperationResult.reserve(DEPLOYMENT, SUBJECT,
                CompleteMeetingReserveFacts.internalReserveKey(request),
                request.canonicalRequestDigest(), grant.expiresAt(),
                AuthorizedProfileSnapshot.from(grant), reservation);
        return new Fixture(request, winner);
    }

    private record Fixture(CreateMeetingRequestFact request, UsageOperationResult winner) {
    }

    private static final class CountingAllocator implements CreateRealtimeSessionAllocator {
        private final AtomicInteger calls = new AtomicInteger();
        private Request lastRequest;
        private boolean fail;

        @Override
        public CreateMeetingResponseFact.RealtimeSession allocate(Request request) {
            calls.incrementAndGet();
            lastRequest = request;
            if (fail) {
                throw new IllegalStateException("local-token-secret");
            }
            return new CreateMeetingResponseFact.RealtimeSession(
                    URI.create("wss://example.test/v1/realtime/" + request.meetingId()),
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                    "local-token-secret", NOW.plusSeconds(60));
        }
    }
}
