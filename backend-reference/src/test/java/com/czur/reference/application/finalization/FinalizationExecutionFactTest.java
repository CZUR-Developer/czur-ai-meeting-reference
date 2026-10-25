package com.czur.reference.application.finalization;

import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 收口执行事实的完整性、Profile/Plan 防漂移和 PostgreSQL 时间门禁。 */
class FinalizationExecutionFactTest {
    private static final String MEETING_ID = "meeting-secret-20260928";
    private static final String TITLE = "secret meeting title";
    private static final String TRANSCRIPT = "secret.capability.transcript";
    private static final String SUMMARY = "secret.capability.summary";
    private static final String FALLBACK = "secret.capability.fallback";
    private static final Instant STARTED_AT =
            Instant.parse("2026-09-28T03:02:01.123456Z");

    @Test
    void freezesCompleteProfileAndPlanWithoutAnyExternalLookup() {
        ProductProfile profile = profile();
        CapabilityPlan plan = plan(profile);

        FinalizationExecutionFact fact = FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, TITLE, profile, plan);
        ProductProfile restored = fact.restoreProductProfile();

        assertEquals(MEETING_ID, fact.meetingId());
        assertEquals(STARTED_AT, fact.startedAt());
        assertEquals(TITLE, fact.title());
        assertEquals(plan.planRevision(), fact.capabilityPlan().planRevision());
        assertEquals(plan.decisions().size(), fact.capabilityPlan().decisions().size());
        assertTrue(fact.capabilityPlan().decisions().containsAll(plan.decisions()));
        assertEquals(profile.id(), restored.id());
        assertEquals(profile.revision(), restored.revision());
        assertEquals(profile.requiredCapabilities(), restored.requiredCapabilities());
        assertEquals(profile.optionalCapabilities(), restored.optionalCapabilities());
        assertEquals(profile.dependencies(), restored.dependencies());
        assertEquals(profile.missingPolicies(), restored.missingPolicies());
        assertEquals(profile.fallbackCapabilities(), restored.fallbackCapabilities());
        assertEquals(profile.affectedWorkflows(), restored.affectedWorkflows());
        assertEquals(profile.producedArtifacts(), restored.producedArtifacts());

        FinalizationExecutionFact withoutTitle = FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, null, profile, plan);
        assertEquals(null, withoutTitle.title());
    }

    @Test
    void rejectsIncompleteDuplicateAndDriftedPlansFailClosed() {
        ProductProfile profile = profile();
        CapabilityPlan valid = plan(profile);

        CapabilityPlan incomplete = new CapabilityPlan(
                valid.planRevision(), valid.decisions().subList(0, 2));
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, TITLE, profile, incomplete));

        List<CapabilityDecision> duplicates = new ArrayList<>(valid.decisions());
        duplicates.add(valid.decisions().getFirst());
        CapabilityPlan duplicate = new CapabilityPlan(valid.planRevision(), duplicates);
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, TITLE, profile, duplicate));

        CapabilityDecision transcript = valid.decisions().stream()
                .filter(value -> value.capability().equals(TRANSCRIPT))
                .findFirst()
                .orElseThrow();
        CapabilityDecision mappingDrift = new CapabilityDecision(
                transcript.capability(),
                transcript.availability(),
                transcript.requiredByProfile(),
                transcript.action(),
                transcript.fallbackCapability(),
                List.of("secret.workflow.drifted"),
                transcript.producedArtifacts(),
                transcript.warningCode(),
                transcript.planRevision());
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID,
                STARTED_AT,
                TITLE,
                profile,
                replace(valid, TRANSCRIPT, mappingDrift)));

        CapabilityDecision requiredFlagDrift = new CapabilityDecision(
                transcript.capability(),
                transcript.availability(),
                false,
                transcript.action(),
                transcript.fallbackCapability(),
                transcript.affectedWorkflows(),
                transcript.producedArtifacts(),
                transcript.warningCode(),
                transcript.planRevision());
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID,
                STARTED_AT,
                TITLE,
                profile,
                replace(valid, TRANSCRIPT, requiredFlagDrift)));
    }

    @Test
    void rejectsFallbackPolicyActionAvailabilityAndDecisionListDrift() {
        ProductProfile profile = profile();
        CapabilityPlan valid = plan(profile);
        CapabilityDecision transcript = valid.decisionFor(TRANSCRIPT);
        CapabilityDecision summary = valid.decisionFor(SUMMARY);
        CapabilityDecision fallback = valid.decisionFor(FALLBACK);

        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, TITLE, profile,
                replace(valid, TRANSCRIPT, copyDecision(
                        transcript,
                        CapabilityDecision.Availability.DEGRADED,
                        CapabilityDecision.Action.ENABLE,
                        null,
                        transcript.affectedWorkflows(),
                        transcript.producedArtifacts()))));
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, TITLE, profile,
                replace(valid, SUMMARY, copyDecision(
                        summary,
                        CapabilityDecision.Availability.AVAILABLE,
                        CapabilityDecision.Action.USE_FALLBACK,
                        FALLBACK,
                        summary.affectedWorkflows(),
                        summary.producedArtifacts()))));
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, TITLE, profile,
                replace(valid, TRANSCRIPT, copyDecision(
                        transcript,
                        CapabilityDecision.Availability.AVAILABLE,
                        CapabilityDecision.Action.FAIL_MEETING,
                        null,
                        transcript.affectedWorkflows(),
                        transcript.producedArtifacts()))));
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, TITLE, profile,
                replace(valid, FALLBACK, copyDecision(
                        fallback,
                        CapabilityDecision.Availability.DEGRADED,
                        CapabilityDecision.Action.DISABLE,
                        null,
                        fallback.affectedWorkflows(),
                        fallback.producedArtifacts()))));

        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, TITLE, profile,
                replace(valid, TRANSCRIPT, copyDecision(
                        transcript,
                        CapabilityDecision.Availability.AVAILABLE,
                        CapabilityDecision.Action.DISABLE,
                        null,
                        transcript.affectedWorkflows(),
                        transcript.producedArtifacts()))));

        CapabilityDecision disabledFallbackTarget = copyDecision(
                fallback,
                CapabilityDecision.Availability.AVAILABLE,
                CapabilityDecision.Action.DISABLE,
                null,
                fallback.affectedWorkflows(),
                fallback.producedArtifacts());
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, TITLE, profile,
                replace(valid, FALLBACK, disabledFallbackTarget)));

        ProductProfile dependencyProfile = dependencyProfile();
        CapabilityPlan disabledDependency = new CapabilityPlan(31L, List.of(
                new CapabilityDecision(
                        SUMMARY,
                        CapabilityDecision.Availability.AVAILABLE,
                        true,
                        CapabilityDecision.Action.ENABLE,
                        null,
                        dependencyProfile.affectedWorkflowsOf(SUMMARY),
                        dependencyProfile.producedArtifactsOf(SUMMARY),
                        null,
                        31L),
                new CapabilityDecision(
                        FALLBACK,
                        CapabilityDecision.Availability.AVAILABLE,
                        false,
                        CapabilityDecision.Action.DISABLE,
                        null,
                        dependencyProfile.affectedWorkflowsOf(FALLBACK),
                        dependencyProfile.producedArtifactsOf(FALLBACK),
                        "NOT_REQUESTED",
                        31L)));
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, TITLE, dependencyProfile, disabledDependency));

        CapabilityDecision malformedWarning = new CapabilityDecision(
                transcript.capability(),
                transcript.availability(),
                transcript.requiredByProfile(),
                transcript.action(),
                transcript.fallbackCapability(),
                transcript.affectedWorkflows(),
                transcript.producedArtifacts(),
                "invalid-warning-\uD800",
                transcript.planRevision());
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, TITLE, profile,
                replace(valid, TRANSCRIPT, malformedWarning)));

        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, TITLE, profile,
                replace(valid, TRANSCRIPT, copyDecision(
                        transcript,
                        transcript.availability(),
                        transcript.action(),
                        null,
                        List.of("secret.workflow.duplicate", "secret.workflow.duplicate"),
                        transcript.producedArtifacts()))));
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, TITLE, profile,
                replace(valid, TRANSCRIPT, copyDecision(
                        transcript,
                        transcript.availability(),
                        transcript.action(),
                        null,
                        transcript.affectedWorkflows(),
                        List.of("secret.artifact.duplicate", "secret.artifact.duplicate")))));
    }

    @Test
    void enforcesWarningSemanticsAndAcceptsUnavailableDisableReason() {
        ProductProfile profile = profile();
        CapabilityPlan valid = plan(profile);
        CapabilityDecision transcript = valid.decisionFor(TRANSCRIPT);
        CapabilityDecision summary = valid.decisionFor(SUMMARY);

        assertFixedFailure(() -> freeze(profile, replace(
                valid, TRANSCRIPT, withWarning(transcript, "secret.warning.unexpected"))));
        assertFixedFailure(() -> freeze(
                profile, replace(valid, SUMMARY, withWarning(summary, null))));

        CapabilityPlanner planner = new CapabilityPlanner();
        Set<String> allCapabilities = Set.of(TRANSCRIPT, SUMMARY, FALLBACK);
        CapabilityPlan requiredFailPlan = planner.plan(
                new CapabilityDescriptor(Set.of(FALLBACK)),
                profile,
                Set.of(),
                allCapabilities,
                DeploymentLimits.NONE,
                33L);
        CapabilityDecision requiredFail = requiredFailPlan.decisionFor(TRANSCRIPT);
        assertFixedFailure(() -> freeze(profile, replace(
                requiredFailPlan, TRANSCRIPT, withWarning(requiredFail, null))));

        CapabilityPlan disabledPlan = planner.plan(
                new CapabilityDescriptor(allCapabilities),
                profile,
                Set.of(),
                allCapabilities,
                DeploymentLimits.NONE,
                34L);
        CapabilityDecision disabled = disabledPlan.decisionFor(SUMMARY);
        assertEquals(CapabilityDecision.Availability.AVAILABLE, disabled.availability());
        assertEquals(CapabilityDecision.Action.DISABLE, disabled.action());
        assertFixedFailure(() -> freeze(
                profile, replace(disabledPlan, SUMMARY, withWarning(disabled, null))));
        assertFixedFailure(() -> freeze(profile, replace(
                disabledPlan,
                SUMMARY,
                withWarning(disabled, "secret.warning.wrong-available-disable"))));

        String dynamicReason = "secret.warning.dynamic-unavailable";
        CapabilityDecision unavailableDisabled = withAvailabilityAndWarning(
                disabled, CapabilityDecision.Availability.UNAVAILABLE, dynamicReason);
        FinalizationExecutionFact fact = freeze(
                profile, replace(disabledPlan, SUMMARY, unavailableDisabled));
        assertEquals(dynamicReason,
                fact.capabilityPlan().decisionFor(SUMMARY).warningCode());
    }

    @Test
    void acceptsNativeFailAndDisabledPlansProducedByRealPlanner() {
        ProductProfile profile = profile();
        CapabilityPlanner planner = new CapabilityPlanner();
        Set<String> allCapabilities = Set.of(TRANSCRIPT, SUMMARY, FALLBACK);
        CapabilityPlan nativePlan = planner.plan(
                new CapabilityDescriptor(allCapabilities),
                profile,
                Set.of(SUMMARY),
                allCapabilities,
                DeploymentLimits.NONE,
                21L);
        CapabilityPlan requiredFailPlan = planner.plan(
                new CapabilityDescriptor(Set.of(FALLBACK)),
                profile,
                Set.of(),
                allCapabilities,
                DeploymentLimits.NONE,
                23L);
        CapabilityPlan disabledPlan = planner.plan(
                new CapabilityDescriptor(allCapabilities),
                profile,
                Set.of(),
                allCapabilities,
                DeploymentLimits.NONE,
                24L);

        freeze(profile, nativePlan);
        freeze(profile, requiredFailPlan);
        FinalizationExecutionFact fact = freeze(profile, disabledPlan);

        assertEquals(CapabilityDecision.Action.ENABLE,
                nativePlan.decisionFor(SUMMARY).action());
        assertEquals(CapabilityDecision.Action.FAIL_MEETING,
                requiredFailPlan.decisionFor(TRANSCRIPT).action());
        assertEquals(CapabilityDecision.Action.DISABLE,
                fact.capabilityPlan().decisionFor(SUMMARY).action());
        assertEquals(CapabilityDecision.Availability.AVAILABLE,
                fact.capabilityPlan().decisionFor(SUMMARY).availability());

        ProductProfile explicitRequiredDisablePolicy = new ProductProfile(
                "secret.profile/required-explicit-disable",
                profile.revision(),
                profile.requiredCapabilities(),
                profile.optionalCapabilities(),
                profile.dependencies(),
                Map.of(TRANSCRIPT, ProductProfile.MissingCapabilityPolicy.DISABLE),
                Map.of(),
                profile.affectedWorkflows(),
                profile.producedArtifacts());
        CapabilityPlan explicitRequiredFailPlan = planner.plan(
                new CapabilityDescriptor(Set.of(FALLBACK)),
                explicitRequiredDisablePolicy,
                Set.of(),
                allCapabilities,
                DeploymentLimits.NONE,
                28L);
        freeze(explicitRequiredDisablePolicy, explicitRequiredFailPlan);
        assertEquals(CapabilityDecision.Action.FAIL_MEETING,
                explicitRequiredFailPlan.decisionFor(TRANSCRIPT).action());

        ProductProfile optionalFailProfile = optionalFailProfile();
        CapabilityPlan optionalFailPlan = planner.plan(
                new CapabilityDescriptor(Set.of(TRANSCRIPT)),
                optionalFailProfile,
                Set.of(SUMMARY),
                Set.of(TRANSCRIPT, SUMMARY),
                DeploymentLimits.NONE,
                26L);
        freeze(optionalFailProfile, optionalFailPlan);
        assertEquals(CapabilityDecision.Action.FAIL_MEETING,
                optionalFailPlan.decisionFor(SUMMARY).action());
        assertFalse(optionalFailPlan.decisionFor(SUMMARY).requiredByProfile());

        ProductProfile dependencyProfile = dependencyProfile();
        Set<String> dependencyCapabilities = Set.of(SUMMARY, FALLBACK);
        CapabilityPlan dependencyPlan = planner.plan(
                new CapabilityDescriptor(dependencyCapabilities),
                dependencyProfile,
                Set.of(),
                dependencyCapabilities,
                DeploymentLimits.NONE,
                27L);
        freeze(dependencyProfile, dependencyPlan);
        assertEquals(CapabilityDecision.Action.ENABLE,
                dependencyPlan.decisionFor(SUMMARY).action());
        assertEquals(CapabilityDecision.Action.ENABLE,
                dependencyPlan.decisionFor(FALLBACK).action());
    }

    @Test
    void enforcesMicrosecondProtocolValuesAndRedactsDiagnostics() {
        ProductProfile profile = profile();
        CapabilityPlan plan = plan(profile);
        FinalizationExecutionFact fact = FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, TITLE, profile, plan);

        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID,
                Instant.parse("2026-09-28T03:02:01.123456789Z"),
                TITLE,
                profile,
                plan));
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                " ", STARTED_AT, TITLE, profile, plan));
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, "x".repeat(257), profile, plan));
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID,
                Instant.parse("+10000-01-01T00:00:00Z"),
                TITLE,
                profile,
                plan));
        String maximumUtf8MeetingId = "会".repeat(341) + "a";
        assertEquals(maximumUtf8MeetingId, FinalizationExecutionFact.freeze(
                maximumUtf8MeetingId, STARTED_AT, TITLE, profile, plan).meetingId());
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                "会".repeat(342), STARTED_AT, TITLE, profile, plan));
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                "invalid-\uD800", STARTED_AT, TITLE, profile, plan));
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, "invalid-title-\uD800", profile, plan));
        assertFixedFailure(() -> FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, "invalid-title-\uD801", profile, plan));

        String diagnostic = fact.toString();
        assertFalse(diagnostic.contains(MEETING_ID));
        assertFalse(diagnostic.contains(TITLE));
        assertFalse(diagnostic.contains("secret.capability"));
        assertFalse(diagnostic.contains("secret.workflow"));
        assertFalse(diagnostic.contains("secret.artifact"));
    }

    private static CapabilityPlan replace(
            CapabilityPlan original,
            String capability,
            CapabilityDecision replacement) {
        return new CapabilityPlan(
                original.planRevision(),
                original.decisions().stream()
                        .map(value -> value.capability().equals(capability)
                                ? replacement : value)
                        .toList());
    }

    private static CapabilityDecision copyDecision(
            CapabilityDecision original,
            CapabilityDecision.Availability availability,
            CapabilityDecision.Action action,
            String fallback,
            List<String> affectedWorkflows,
            List<String> producedArtifacts) {
        return new CapabilityDecision(
                original.capability(),
                availability,
                original.requiredByProfile(),
                action,
                fallback,
                affectedWorkflows,
                producedArtifacts,
                original.warningCode(),
                original.planRevision());
    }

    private static CapabilityDecision withWarning(
            CapabilityDecision original,
            String warningCode) {
        return new CapabilityDecision(
                original.capability(),
                original.availability(),
                original.requiredByProfile(),
                original.action(),
                original.fallbackCapability(),
                original.affectedWorkflows(),
                original.producedArtifacts(),
                warningCode,
                original.planRevision());
    }

    private static CapabilityDecision withAvailabilityAndWarning(
            CapabilityDecision original,
            CapabilityDecision.Availability availability,
            String warningCode) {
        return new CapabilityDecision(
                original.capability(),
                availability,
                original.requiredByProfile(),
                original.action(),
                original.fallbackCapability(),
                original.affectedWorkflows(),
                original.producedArtifacts(),
                warningCode,
                original.planRevision());
    }

    private static void assertFixedFailure(Runnable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertEquals("Finalization execution fact is invalid", failure.getMessage());
        assertEquals(null, failure.getCause());
        assertFalse(failure.toString().contains(MEETING_ID));
        assertFalse(failure.toString().contains(TITLE));
        assertFalse(failure.toString().contains("secret.capability"));
        assertFalse(failure.toString().contains("secret.workflow"));
    }

    private static FinalizationExecutionFact freeze(
            ProductProfile profile,
            CapabilityPlan plan) {
        return FinalizationExecutionFact.freeze(
                MEETING_ID, STARTED_AT, TITLE, profile, plan);
    }

    private static ProductProfile profile() {
        return new ProductProfile(
                "secret.profile/v5",
                5,
                Set.of(TRANSCRIPT),
                Set.of(SUMMARY, FALLBACK),
                Map.of(SUMMARY, Set.of(TRANSCRIPT)),
                Map.of(
                        SUMMARY, ProductProfile.MissingCapabilityPolicy.DISABLE,
                        FALLBACK, ProductProfile.MissingCapabilityPolicy.DISABLE),
                Map.of(),
                Map.of(
                        TRANSCRIPT, List.of("secret.workflow.transcript"),
                        SUMMARY, List.of("secret.workflow.summary"),
                        FALLBACK, List.of("secret.workflow.fallback")),
                Map.of(
                        TRANSCRIPT, List.of("secret.artifact.transcript"),
                        SUMMARY, List.of("secret.artifact.summary"),
                        FALLBACK, List.of("secret.artifact.fallback")));
    }

    private static CapabilityPlan plan(ProductProfile profile) {
        return new CapabilityPlan(11L, List.of(
                new CapabilityDecision(
                        TRANSCRIPT,
                        CapabilityDecision.Availability.AVAILABLE,
                        true,
                        CapabilityDecision.Action.ENABLE,
                        null,
                        profile.affectedWorkflowsOf(TRANSCRIPT),
                        profile.producedArtifactsOf(TRANSCRIPT),
                        null,
                        11L),
                new CapabilityDecision(
                        SUMMARY,
                        CapabilityDecision.Availability.UNAVAILABLE,
                        false,
                        CapabilityDecision.Action.DISABLE,
                        null,
                        profile.affectedWorkflowsOf(SUMMARY),
                        profile.producedArtifactsOf(SUMMARY),
                        "PROVIDER_CAPABILITY_MISSING",
                        11L),
                new CapabilityDecision(
                        FALLBACK,
                        CapabilityDecision.Availability.UNAVAILABLE,
                        false,
                        CapabilityDecision.Action.DISABLE,
                        null,
                        profile.affectedWorkflowsOf(FALLBACK),
                        profile.producedArtifactsOf(FALLBACK),
                        "PROVIDER_CAPABILITY_MISSING",
                        11L)));
    }

    private static ProductProfile optionalFailProfile() {
        return new ProductProfile(
                "secret.profile/optional-fail",
                1,
                Set.of(TRANSCRIPT),
                Set.of(SUMMARY),
                Map.of(),
                Map.of(SUMMARY, ProductProfile.MissingCapabilityPolicy.FAIL_MEETING),
                Map.of(),
                Map.of(
                        TRANSCRIPT, List.of("secret.workflow.transcript"),
                        SUMMARY, List.of("secret.workflow.summary")),
                Map.of(
                        TRANSCRIPT, List.of("secret.artifact.transcript"),
                        SUMMARY, List.of("secret.artifact.summary")));
    }

    private static ProductProfile dependencyProfile() {
        return new ProductProfile(
                "secret.profile/dependency",
                1,
                Set.of(SUMMARY),
                Set.of(FALLBACK),
                Map.of(SUMMARY, Set.of(FALLBACK)),
                Map.of(),
                Map.of(),
                Map.of(
                        SUMMARY, List.of("secret.workflow.summary"),
                        FALLBACK, List.of("secret.workflow.fallback")),
                Map.of(
                        SUMMARY, List.of("secret.artifact.summary"),
                        FALLBACK, List.of("secret.artifact.fallback")));
    }
}
