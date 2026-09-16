package com.czur.reference.domain.capability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.czur.reference.domain.capability.CapabilityDecision.Action.DISABLE;
import static com.czur.reference.domain.capability.CapabilityDecision.Action.ENABLE;
import static com.czur.reference.domain.capability.CapabilityDecision.Action.FAIL_MEETING;
import static com.czur.reference.domain.capability.CapabilityDecision.Action.USE_FALLBACK;
import static com.czur.reference.domain.capability.CapabilityIds.SPEAKER_DIARIZATION_SESSION;
import static com.czur.reference.domain.capability.CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING;
import static com.czur.reference.domain.capability.CapabilityIds.SUMMARY_STRUCTURED;
import static com.czur.reference.domain.capability.CapabilityIds.TRANSCRIPT_FINAL;
import static com.czur.reference.domain.capability.CapabilityIds.TRANSCRIPT_REALTIME;
import static com.czur.reference.domain.capability.CapabilityIds.TRANSLATION_REALTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CapabilityPlanner 的纯领域规则测试，不连接模型、网络或数据库。 */
class CapabilityPlannerTest {
    private static final Set<String> ALL_CAPABILITIES = Set.of(
            TRANSCRIPT_REALTIME,
            TRANSCRIPT_FINAL,
            TRANSLATION_REALTIME,
            SPEAKER_DIARIZATION_SESSION,
            SPEAKER_IDENTITY_CROSS_MEETING,
            SUMMARY_STRUCTURED);

    @Test
    void completeProviderEnablesRequiredAndRequestedOptionalCapabilities() {
        CapabilityPlan plan = new CapabilityPlanner().plan(
                new CapabilityDescriptor("complete-model", ALL_CAPABILITIES),
                fullProfile(),
                Set.of(TRANSLATION_REALTIME, SPEAKER_IDENTITY_CROSS_MEETING, SUMMARY_STRUCTURED),
                ALL_CAPABILITIES,
                DeploymentLimits.NONE);

        assertFalse(plan.failed());
        assertEquals(ENABLE, plan.decisionFor(TRANSCRIPT_REALTIME).action());
        assertEquals(ENABLE, plan.decisionFor(TRANSCRIPT_FINAL).action());
        assertEquals(ENABLE, plan.decisionFor(TRANSLATION_REALTIME).action());
        assertEquals(ENABLE, plan.decisionFor(SPEAKER_DIARIZATION_SESSION).action());
        assertEquals(ENABLE, plan.decisionFor(SPEAKER_IDENTITY_CROSS_MEETING).action());
        assertEquals(ENABLE, plan.decisionFor(SUMMARY_STRUCTURED).action());
        assertEquals(List.of("structured-summary"),
                plan.decisionFor(SUMMARY_STRUCTURED).affectedWorkflows());
        assertEquals(List.of("summary.structured"),
                plan.decisionFor(SUMMARY_STRUCTURED).producedArtifacts());
    }

    @Test
    void asrOnlyMeetingKeepsOptionalCapabilitiesDisabled() {
        CapabilityPlan plan = new CapabilityPlanner().plan(
                new CapabilityDescriptor("asr-only", Set.of(TRANSCRIPT_REALTIME, TRANSCRIPT_FINAL)),
                fullProfile(),
                Set.of(),
                Set.of(TRANSCRIPT_REALTIME, TRANSCRIPT_FINAL),
                DeploymentLimits.NONE);

        assertFalse(plan.failed());
        assertEquals(ENABLE, plan.decisionFor(TRANSCRIPT_REALTIME).action());
        assertEquals(ENABLE, plan.decisionFor(TRANSCRIPT_FINAL).action());
        assertEquals(DISABLE, plan.decisionFor(TRANSLATION_REALTIME).action());
        assertEquals(DISABLE, plan.decisionFor(SPEAKER_DIARIZATION_SESSION).action());
        assertEquals(DISABLE, plan.decisionFor(SPEAKER_IDENTITY_CROSS_MEETING).action());
        assertEquals(DISABLE, plan.decisionFor(SUMMARY_STRUCTURED).action());
    }

    @Test
    void capabilityOutsideProfileIsRejectedWithStableReasonCode() {
        CapabilityPlanningException failure = assertThrows(CapabilityPlanningException.class, () ->
                new CapabilityPlanner().plan(
                        new CapabilityDescriptor(ALL_CAPABILITIES),
                        fullProfile(),
                        Set.of("unknown.experimental"),
                        ALL_CAPABILITIES,
                        DeploymentLimits.NONE));

        assertEquals(CapabilityPlanningException.ReasonCode.CAPABILITY_NOT_ALLOWED,
                failure.reasonCode());
    }

    @Test
    void missingRequiredCapabilityFailsMeetingWithoutInventingFallback() {
        CapabilityPlan plan = new CapabilityPlanner().plan(
                new CapabilityDescriptor(Set.of(TRANSCRIPT_REALTIME)),
                fullProfile(),
                Set.of(),
                ALL_CAPABILITIES,
                DeploymentLimits.NONE);

        assertTrue(plan.failed());
        CapabilityDecision decision = plan.decisionFor(TRANSCRIPT_FINAL);
        assertEquals(FAIL_MEETING, decision.action());
        assertEquals(CapabilityDecision.Availability.UNAVAILABLE, decision.availability());
        assertEquals(null, decision.fallbackCapability());
    }

    @Test
    void crossMeetingIdentityFallsBackToSessionDiarization() {
        Set<String> providerCapabilities = Set.of(
                TRANSCRIPT_REALTIME,
                TRANSCRIPT_FINAL,
                SPEAKER_DIARIZATION_SESSION);
        CapabilityPlan plan = new CapabilityPlanner().plan(
                new CapabilityDescriptor(providerCapabilities),
                fullProfile(),
                Set.of(SPEAKER_IDENTITY_CROSS_MEETING),
                ALL_CAPABILITIES,
                DeploymentLimits.NONE);

        CapabilityDecision decision = plan.decisionFor(SPEAKER_IDENTITY_CROSS_MEETING);
        assertEquals(USE_FALLBACK, decision.action());
        assertEquals(SPEAKER_DIARIZATION_SESSION, decision.fallbackCapability());
        assertEquals(CapabilityDecision.Availability.DEGRADED, decision.availability());
        assertEquals(List.of("session-diarization"), decision.affectedWorkflows());
        assertEquals(List.of("speaker.diarization.session"), decision.producedArtifacts());
        assertEquals(ENABLE, plan.decisionFor(SPEAKER_DIARIZATION_SESSION).action());
    }

    @Test
    void capabilityCannotEnableWhenItsDependencyIsUnavailable() {
        ProductProfile profile = fullProfile();
        Set<String> providerCapabilities = Set.of(TRANSCRIPT_REALTIME, SUMMARY_STRUCTURED);
        CapabilityPlan plan = new CapabilityPlanner().plan(
                new CapabilityDescriptor(providerCapabilities),
                profile,
                Set.of(SUMMARY_STRUCTURED),
                ALL_CAPABILITIES,
                DeploymentLimits.NONE);

        assertEquals(FAIL_MEETING, plan.decisionFor(TRANSCRIPT_FINAL).action());
        assertEquals(DISABLE, plan.decisionFor(SUMMARY_STRUCTURED).action());
        assertTrue(plan.decisionFor(SUMMARY_STRUCTURED).warningCode().startsWith("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void deploymentLimitDisablesRequestedOptionalCapability() {
        CapabilityPlan plan = new CapabilityPlanner().plan(
                new CapabilityDescriptor(ALL_CAPABILITIES),
                fullProfile(),
                Set.of(TRANSLATION_REALTIME),
                ALL_CAPABILITIES,
                new DeploymentLimits(Set.of(TRANSLATION_REALTIME)));

        CapabilityDecision decision = plan.decisionFor(TRANSLATION_REALTIME);
        assertEquals(DISABLE, decision.action());
        assertEquals("DEPLOYMENT_LIMIT", decision.warningCode());
    }

    @Test
    void missingMeetingGrantDisablesRequestedOptionalCapability() {
        Set<String> grantsWithoutTranslation = Set.of(
                TRANSCRIPT_REALTIME,
                TRANSCRIPT_FINAL,
                SPEAKER_DIARIZATION_SESSION,
                SPEAKER_IDENTITY_CROSS_MEETING,
                SUMMARY_STRUCTURED);
        CapabilityPlan plan = new CapabilityPlanner().plan(
                new CapabilityDescriptor(ALL_CAPABILITIES),
                fullProfile(),
                Set.of(TRANSLATION_REALTIME),
                grantsWithoutTranslation,
                DeploymentLimits.NONE);

        CapabilityDecision decision = plan.decisionFor(TRANSLATION_REALTIME);
        assertEquals(DISABLE, decision.action());
        assertEquals("NOT_GRANTED", decision.warningCode());
    }

    @Test
    void unavailableFallbackDoesNotInventCrossMeetingIdentity() {
        CapabilityPlan plan = new CapabilityPlanner().plan(
                new CapabilityDescriptor(Set.of(TRANSCRIPT_REALTIME, TRANSCRIPT_FINAL)),
                fullProfile(),
                Set.of(SPEAKER_IDENTITY_CROSS_MEETING),
                ALL_CAPABILITIES,
                DeploymentLimits.NONE);

        CapabilityDecision decision = plan.decisionFor(SPEAKER_IDENTITY_CROSS_MEETING);
        assertEquals(DISABLE, decision.action());
        assertEquals(CapabilityDecision.Availability.UNAVAILABLE, decision.availability());
        assertEquals(null, decision.fallbackCapability());
        assertEquals("FALLBACK_UNAVAILABLE:" + SPEAKER_DIARIZATION_SESSION,
                decision.warningCode());
    }

    @Test
    void cyclicDependencyIsRejectedBeforePlanning() {
        CapabilityPlanningException failure = assertThrows(CapabilityPlanningException.class, () ->
                new ProductProfile(
                        "cycle/v1",
                        Set.of(TRANSCRIPT_REALTIME),
                        Set.of(TRANSLATION_REALTIME),
                        Map.of(
                                TRANSCRIPT_REALTIME, Set.of(TRANSLATION_REALTIME),
                                TRANSLATION_REALTIME, Set.of(TRANSCRIPT_REALTIME)),
                        Map.of(),
                        Map.of()));

        assertEquals(CapabilityPlanningException.ReasonCode.CYCLIC_DEPENDENCY,
                failure.reasonCode());
    }

    @Test
    void everyDecisionUsesOneRevisionAndNextPlanIncrementsIt() {
        CapabilityPlanner planner = new CapabilityPlanner();
        CapabilityDescriptor provider = new CapabilityDescriptor(ALL_CAPABILITIES);
        CapabilityPlan first = planner.plan(
                provider, fullProfile(), Set.of(), ALL_CAPABILITIES, DeploymentLimits.NONE, 7L);
        CapabilityPlan replay = planner.plan(
                provider, fullProfile(), Set.of(), ALL_CAPABILITIES, DeploymentLimits.NONE, 7L);
        CapabilityPlan second = planner.replan(
                first, provider, fullProfile(), Set.of(), ALL_CAPABILITIES, DeploymentLimits.NONE);

        assertEquals(7L, first.planRevision());
        assertEquals(first, replay);
        assertEquals(8L, second.planRevision());
        assertTrue(first.decisions().stream()
                .allMatch(decision -> decision.planRevision() == first.planRevision()));
        assertTrue(second.decisions().stream()
                .allMatch(decision -> decision.planRevision() == second.planRevision()));
        assertThrows(UnsupportedOperationException.class, () -> first.decisions().add(
                first.decisionFor(TRANSCRIPT_REALTIME)));
    }

    private static ProductProfile fullProfile() {
        return new ProductProfile(
                "meeting-minutes/v1",
                1,
                Set.of(TRANSCRIPT_REALTIME, TRANSCRIPT_FINAL),
                Set.of(
                        TRANSLATION_REALTIME,
                        SPEAKER_DIARIZATION_SESSION,
                        SPEAKER_IDENTITY_CROSS_MEETING,
                        SUMMARY_STRUCTURED),
                Map.of(
                        SPEAKER_IDENTITY_CROSS_MEETING, Set.of(SPEAKER_DIARIZATION_SESSION),
                        SUMMARY_STRUCTURED, Set.of(TRANSCRIPT_FINAL)),
                Map.of(
                        TRANSLATION_REALTIME, ProductProfile.MissingCapabilityPolicy.DISABLE,
                        SPEAKER_DIARIZATION_SESSION, ProductProfile.MissingCapabilityPolicy.DISABLE,
                        SPEAKER_IDENTITY_CROSS_MEETING, ProductProfile.MissingCapabilityPolicy.USE_FALLBACK,
                        SUMMARY_STRUCTURED, ProductProfile.MissingCapabilityPolicy.DISABLE),
                Map.of(SPEAKER_IDENTITY_CROSS_MEETING, SPEAKER_DIARIZATION_SESSION),
                Map.of(
                        TRANSCRIPT_REALTIME, List.of("realtime-transcript"),
                        TRANSCRIPT_FINAL, List.of("final-transcript"),
                        TRANSLATION_REALTIME, List.of("realtime-translation"),
                        SPEAKER_DIARIZATION_SESSION, List.of("session-diarization"),
                        SPEAKER_IDENTITY_CROSS_MEETING, List.of("cross-meeting-speaker-identity"),
                        SUMMARY_STRUCTURED, List.of("structured-summary")),
                Map.of(
                        TRANSCRIPT_REALTIME, List.of("transcript.realtime"),
                        TRANSCRIPT_FINAL, List.of("transcript.final"),
                        TRANSLATION_REALTIME, List.of("translation.realtime"),
                        SPEAKER_DIARIZATION_SESSION, List.of("speaker.diarization.session"),
                        SPEAKER_IDENTITY_CROSS_MEETING, List.of("speaker.identity.cross_meeting"),
                        SUMMARY_STRUCTURED, List.of("summary.structured")));
    }
}
