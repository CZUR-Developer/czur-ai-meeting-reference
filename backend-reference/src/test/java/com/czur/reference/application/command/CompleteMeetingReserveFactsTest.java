package com.czur.reference.application.command;

import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.model.AudioProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v2 内部预占键和首次赢家事实的纯 Java 契约。 */
class CompleteMeetingReserveFactsTest {
    private static final String DEPLOYMENT = "deployment-secret";
    private static final String SUBJECT = "subject-secret";
    private static final String KEY = "external-key-123456789";
    private static final Instant AT = Instant.parse("2030-01-01T00:00:00Z");
    private static final Set<String> CAPABILITIES = Set.of("transcript.realtime");

    @Test
    void internalKeyIsVersionedStableAndDependsOnlyOnExternalKey() {
        var original = fact("first title", "standard", CAPABILITIES, KEY);
        var changedBody = fact("another title", "standard", CAPABILITIES, KEY);
        var changedKey = fact("first title", "standard", CAPABILITIES,
                "another-external-key-123456789");
        String internal = CompleteMeetingReserveFacts.internalReserveKey(original);
        assertEquals("meeting.create.v2.reserve:"
                        + "dccea544b536c96e92746ce4c9d031bc"
                        + "f0fc49976e74f62ed5552f95c1c7511e",
                internal, "版本域与 UTF-8 字节编码不得在重构时漂移");
        assertEquals(internal, CompleteMeetingReserveFacts.internalReserveKey(original));
        assertEquals(internal, CompleteMeetingReserveFacts.internalReserveKey(changedBody));
        assertNotEquals(internal, CompleteMeetingReserveFacts.internalReserveKey(changedKey));
        assertNotEquals(KEY, internal);
        assertTrue(internal.startsWith("meeting.create.v2.reserve:"));
        assertFalse(internal.contains(KEY));
        assertFalse(internal.contains(original.canonicalRequestDigest()));
    }

    @Test
    void acceptsFirstWinnerWithoutComparingNewGrantOrCandidateMeetingId() {
        var request = fact(null, "standard", CAPABILITIES, KEY);
        var firstGrant = grant("grant-first", DEPLOYMENT, SUBJECT, "standard",
                CAPABILITIES, CAPABILITIES, 3_600_000, AT.plusSeconds(600));
        var winner = result(request, firstGrant,
                reservation(firstGrant, "mtg_persisted_winner", 60_000));

        // 新授权和候选会议可以与首次赢家不同；验证只能依赖首次结果。
        var loserGrant = grant("grant-after-retry", DEPLOYMENT, SUBJECT, "standard",
                CAPABILITIES, CAPABILITIES, 3_600_000, AT.plusSeconds(300));
        assertNotEquals(firstGrant.grantId(), loserGrant.grantId());
        assertNotEquals(firstGrant.expiresAt(), loserGrant.expiresAt());
        assertNotEquals("mtg_new_candidate", winner.reservation().expectedMeetingId());
        assertTrue(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                winner, request, DEPLOYMENT, SUBJECT));
        assertEquals("mtg_persisted_winner", winner.reservation().expectedMeetingId());
    }

    @Test
    void rejectsScopeKeyDigestProfileCapabilityAndInitialReservationDrift() {
        var request = fact(null, "standard", CAPABILITIES, KEY);
        var grant = grant("grant-first", DEPLOYMENT, SUBJECT, "standard",
                CAPABILITIES, CAPABILITIES, 3_600_000, AT.plusSeconds(600));
        var first = reservation(grant, "mtg_first", 60_000);
        var winner = result(request, grant, first);
        assertFalse(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                winner, request, "other-deployment", SUBJECT));
        assertFalse(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                winner, request, DEPLOYMENT, "other-subject"));
        assertFalse(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                winner, fact(null, "standard", CAPABILITIES, "different-key-123456789"),
                DEPLOYMENT, SUBJECT));
        assertFalse(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                winner, fact("changed", "standard", CAPABILITIES, KEY),
                DEPLOYMENT, SUBJECT));
        assertFalse(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                winner, fact(null, "other", CAPABILITIES, KEY),
                DEPLOYMENT, SUBJECT));
        assertFalse(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                winner, fact(null, "standard", Set.of("summary.structured"), KEY),
                DEPLOYMENT, SUBJECT));
        var wrongKey = UsageOperationResult.reserve(DEPLOYMENT, SUBJECT, KEY,
                request.canonicalRequestDigest(), grant.expiresAt(),
                AuthorizedProfileSnapshot.from(grant), first);
        assertFalse(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                wrongKey, request, DEPLOYMENT, SUBJECT));
        var wrongProfile = UsageOperationResult.reserve(DEPLOYMENT, SUBJECT,
                CompleteMeetingReserveFacts.internalReserveKey(request),
                request.canonicalRequestDigest(), grant.expiresAt(),
                new AuthorizedProfileSnapshot("other-profile", 1, CAPABILITIES,
                        CAPABILITIES, 3_600_000), first);
        assertFalse(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                wrongProfile, request, DEPLOYMENT, SUBJECT));
        var wrongRequestedCapability = UsageOperationResult.reserve(DEPLOYMENT, SUBJECT,
                CompleteMeetingReserveFacts.internalReserveKey(request),
                request.canonicalRequestDigest(), grant.expiresAt(),
                new AuthorizedProfileSnapshot("standard", 1,
                        Set.of("summary.structured"),
                        Set.of("summary.structured"), 3_600_000), first);
        assertFalse(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                wrongRequestedCapability, request, DEPLOYMENT, SUBJECT));
        var missingGrantedCapability = UsageOperationResult.reserve(DEPLOYMENT, SUBJECT,
                CompleteMeetingReserveFacts.internalReserveKey(request),
                request.canonicalRequestDigest(), grant.expiresAt(),
                new AuthorizedProfileSnapshot("standard", 1, CAPABILITIES,
                        Set.of("summary.structured"), 3_600_000), first);
        assertFalse(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                missingGrantedCapability, request, DEPLOYMENT, SUBJECT));
        var bound = result(request, grant, first.bind("mtg_first", AT.plusSeconds(10), 0));
        assertFalse(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                bound, request, DEPLOYMENT, SUBJECT));
        var wrongId = result(request, grant, reservation(grant, "invalid-id", 60_000));
        assertFalse(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                wrongId, request, DEPLOYMENT, SUBJECT));
        var legacy = UsageOperationResult.restoreLegacyV1Reserve(DEPLOYMENT, SUBJECT,
                CompleteMeetingReserveFacts.internalReserveKey(request),
                request.canonicalRequestDigest(), grant.expiresAt(), first);
        assertFalse(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                legacy, request, DEPLOYMENT, SUBJECT));
    }

    @Test
    void estimateIsFirstFactNotAResponseDurationLimit() {
        var request = fact(null, "standard", CAPABILITIES, KEY);
        var grant = grant("grant-first", DEPLOYMENT, SUBJECT, "standard",
                CAPABILITIES, CAPABILITIES, 3_600_000, AT.plusSeconds(600));
        var shortEstimate = result(request, grant, reservation(grant, "mtg_short", 60_000));
        assertTrue(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                shortEstimate, request, DEPLOYMENT, SUBJECT));
        assertNotEquals(shortEstimate.reservation().reservedUsage().amount(),
                shortEstimate.requireAuthorizedProfileSnapshot().maxDurationMs());

        var lowerProfileLimit = UsageOperationResult.reserve(DEPLOYMENT, SUBJECT,
                CompleteMeetingReserveFacts.internalReserveKey(request),
                request.canonicalRequestDigest(), grant.expiresAt(),
                new AuthorizedProfileSnapshot("standard", 1, CAPABILITIES,
                        CAPABILITIES, 30_000), shortEstimate.reservation());
        assertFalse(CompleteMeetingReserveFacts.isMatchingInitialWinner(
                lowerProfileLimit, request, DEPLOYMENT, SUBJECT));
    }

    private static CreateMeetingRequestFact fact(
            String title, String profileId, Set<String> capabilities, String key) {
        var snapshot = new CreateMeetingRequestSnapshot("client-session",
                new CreateMeetingRequestSnapshot.ProfileRef(profileId, 1),
                new CreateMeetingRequestSnapshot.ClientDescriptor(
                        "app", "1", "v1", "device"), title, "Asia/Shanghai",
                new CreateMeetingRequestSnapshot.LanguageSelection("zh-CN", List.of()),
                AudioProfile.pcm16kMono(100), capabilities,
                new CreateMeetingRequestSnapshot.ArtifactPolicy(false, false), key);
        return new CreateMeetingRequestFact(snapshot,
                MeetingRequestCanonicalizer.digest(snapshot), 1);
    }

    private static MeetingGrant grant(String id, String deployment, String subject,
            String profile, Set<String> requested, Set<String> granted,
            long maxDurationMs, Instant expiresAt) {
        return new MeetingGrant(id, deployment, subject, profile, 1, requested, granted,
                maxDurationMs, AT.minusSeconds(10), expiresAt);
    }

    private static UsageReservation reservation(MeetingGrant grant, String meetingId,
            long estimateMs) {
        return UsageReservation.reserved("reservation-first", grant, meetingId,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, estimateMs),
                AT, AT.plusSeconds(300));
    }

    private static UsageOperationResult result(CreateMeetingRequestFact request,
            MeetingGrant grant, UsageReservation reservation) {
        return UsageOperationResult.reserve(DEPLOYMENT, SUBJECT,
                CompleteMeetingReserveFacts.internalReserveKey(request),
                request.canonicalRequestDigest(), grant.expiresAt(),
                AuthorizedProfileSnapshot.from(grant), reservation);
    }
}
