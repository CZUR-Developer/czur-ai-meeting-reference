package com.czur.reference.application.finalization;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.persistence.InMemoryWorkflowStepStore;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.artifact.ArtifactStore.CompleteStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.PrepareStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.StageStatus;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageActual;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.czur.reference.domain.capability.CapabilityIds.SUMMARY_STRUCTURED;
import static com.czur.reference.domain.capability.CapabilityIds.TRANSCRIPT_FINAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 完整 checkpoint 派生、稳定逻辑 identity 与 Admission Reservation 门禁。 */
class DeterministicMockFinalizationPlannerTest {
    private static final String MEETING_ID = "meeting-finalization-recovery";
    private static final String DEPLOYMENT_SCOPE = "deployment-finalization";
    private static final String SUBJECT_SCOPE = "subject-finalization";
    private static final Instant ACCEPTED_AT =
            Instant.parse("2026-09-28T03:00:00.123456Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void sameFrozenFactsDeriveByteIdenticalCompletePlan() {
        FinalizationInputFact input = input("sha256:source-a");
        FinalizationExecutionFact execution = execution(profile("mock-summary-v1"));
        UsageReservation reservation = reservation("reservation-a");

        var first = derive(input, execution, reservation);
        var replay = derive(input, execution, reservation);

        assertEquals(first, replay);
        assertEquals(first.workflowStep().canonicalPayload(),
                replay.workflowStep().canonicalPayload());
        assertEquals(first.workflowStep().contentDigest(),
                replay.workflowStep().contentDigest());
        assertEquals(2, first.artifacts().size());
        assertEquals(List.of("summary.structured", "transcript.final"),
                first.artifacts().stream().map(value -> value.spec().artifactType()).toList());
        assertEquals(ACCEPTED_AT, first.workflowStep().nextAttemptAt());
        assertEquals(first.finalUsage(), first.checkpoint().finalUsageFact());
        assertEquals(first.commitUsageRequest(),
                first.checkpoint().commitPlan().commitUsageRequest());
        assertEquals(1, first.commitUsageRequest().expectedVersion(),
                "Admission 必须冻结 bind 后行锁下观察到的 version");
    }

    @Test
    void inputExecutionAndReservationDriftKeepLogicalIdentityButConflict() throws Exception {
        FinalizationInputFact input = input("sha256:source-a");
        FinalizationExecutionFact originalExecution = execution(profile("mock-summary-v1"));
        var original = derive(input, originalExecution, reservation("reservation-a"));
        var inputDrift = derive(
                input("sha256:source-b"), originalExecution, reservation("reservation-a"));
        FinalizationInputFact acceptedAtDriftFact = new FinalizationInputFact(
                MEETING_ID, "input-binding-v1", 17, 65_432,
                "zh-CN", List.of("en-US"), ACCEPTED_AT.plusSeconds(1),
                "sha256:source-a");
        var acceptedAtDrift = derive(
                acceptedAtDriftFact, originalExecution, reservation("reservation-a"));
        var executionDrift = derive(
                input, execution(profile("mock-summary-v2")), reservation("reservation-a"));
        var reservationDrift = derive(
                input, originalExecution, reservation("reservation-b"));

        for (var drift : List.of(
                inputDrift, acceptedAtDrift, executionDrift, reservationDrift)) {
            assertEquals(original.workflowStep().key(), drift.workflowStep().key());
            assertNotEquals(original.workflowStep().contentDigest(),
                    drift.workflowStep().contentDigest());
            assertEquals(
                    original.artifacts().stream().map(value -> value.spec().artifactId()).toList(),
                    drift.artifacts().stream().map(value -> value.spec().artifactId()).toList());
            assertEquals(original.commitUsageRequest().idempotencyKey(),
                    drift.commitUsageRequest().idempotencyKey());
        }
        assertNotEquals(original.commitUsageRequest().requestDigest(),
                inputDrift.commitUsageRequest().requestDigest());
        assertNotEquals(original.commitUsageRequest().requestDigest(),
                acceptedAtDrift.commitUsageRequest().requestDigest());
        assertEquals(original.commitUsageRequest().requestDigest(),
                executionDrift.commitUsageRequest().requestDigest(),
                "Profile/Plan 由 checkpoint 冲突，不进入 Entitlement request digest");
        assertNotEquals(original.commitUsageRequest().requestDigest(),
                reservationDrift.commitUsageRequest().requestDigest());

        InMemoryWorkflowStepStore workflow = new InMemoryWorkflowStepStore();
        assertEquals(WorkflowStepStore.RegisterStatus.APPLIED,
                workflow.register(original.workflowStep()).status());
        assertEquals(WorkflowStepStore.RegisterStatus.CONFLICT,
                workflow.register(inputDrift.workflowStep()).status());
        assertEquals(WorkflowStepStore.RegisterStatus.CONFLICT,
                workflow.register(acceptedAtDrift.workflowStep()).status());
        assertEquals(WorkflowStepStore.RegisterStatus.CONFLICT,
                workflow.register(executionDrift.workflowStep()).status());
        assertEquals(WorkflowStepStore.RegisterStatus.CONFLICT,
                workflow.register(reservationDrift.workflowStep()).status());

        var artifactStore = new PersistentFileSystemMockArtifactStore(
                temporaryDirectory.resolve("artifacts"), 1024 * 1024);
        var winner = original.artifacts().getFirst();
        var intent = artifactStore.createWriteIntent(winner.spec()).intent();
        assertEquals(StageStatus.APPLIED,
                artifactStore.writeContent(intent, winner.openContent()).status());
        assertEquals(CompleteStatus.APPLIED,
                artifactStore.completeWrite(intent, winner.claim()).status());
        assertEquals(PrepareStatus.CONFLICT,
                artifactStore.createWriteIntent(inputDrift.artifacts().getFirst().spec()).status());
        assertEquals(PrepareStatus.CONFLICT,
                artifactStore.createWriteIntent(
                        executionDrift.artifacts().getFirst().spec()).status());
        assertEquals(PrepareStatus.REPLAYED,
                artifactStore.createWriteIntent(
                        reservationDrift.artifacts().getFirst().spec()).status(),
                "Reservation seed 不改变 Artifact spec");
    }

    @Test
    void reservationMustBeLockedReservedBoundToExactMeetingAndScopes() {
        FinalizationInputFact input = input("sha256:source-a");
        FinalizationExecutionFact execution = execution(profile("mock-summary-v1"));
        UsageReservation bound = reservation("reservation-a");
        UsageReservation unbound = initialReservation("reservation-unbound", MEETING_ID,
                DEPLOYMENT_SCOPE, SUBJECT_SCOPE);
        UsageReservation wrongMeeting = initialReservation(
                        "reservation-wrong-meeting", "another-meeting",
                        DEPLOYMENT_SCOPE, SUBJECT_SCOPE)
                .bind("another-meeting", ACCEPTED_AT.minusSeconds(20), 0);
        UsageReservation committed = bound.commit(
                new UsageActual(UsageUnit.MEETING_DURATION_MS, 65_432),
                ACCEPTED_AT.plusSeconds(1),
                bound.version());
        UsageReservation lockedVersionSeven = new UsageReservation(
                bound.reservationId(), bound.deploymentScope(), bound.subjectScope(),
                bound.grantId(), bound.expectedMeetingId(), bound.boundMeetingId(),
                bound.boundAt(), bound.reservedUsage(), null, bound.createdAt(),
                bound.expiresAt(), UsageReservation.State.RESERVED, 7,
                null, null, null);

        var versionSevenPlan = derive(input, execution, lockedVersionSeven);
        assertEquals(7, versionSevenPlan.commitUsageRequest().expectedVersion());
        assertEquals(derive(input, execution, bound).commitUsageRequest().idempotencyKey(),
                versionSevenPlan.commitUsageRequest().idempotencyKey());
        assertNotEquals(derive(input, execution, bound).commitUsageRequest().requestDigest(),
                versionSevenPlan.commitUsageRequest().requestDigest());

        assertThrows(IllegalArgumentException.class,
                () -> derive(input, execution, unbound));
        assertThrows(IllegalArgumentException.class,
                () -> derive(input, execution, wrongMeeting));
        assertThrows(IllegalArgumentException.class,
                () -> derive(input, execution, committed));
        assertThrows(IllegalArgumentException.class,
                () -> new DeterministicMockFinalizationPlanner().derive(
                        "wrong-deployment", SUBJECT_SCOPE,
                        input, execution, bound));
        assertThrows(IllegalArgumentException.class,
                () -> new DeterministicMockFinalizationPlanner().derive(
                        DEPLOYMENT_SCOPE, "wrong-subject",
                        input, execution, bound));
    }

    @Test
    void executableMappingsDeduplicateEnabledArtifacts() {
        ProductProfile enabled = enabledProfile();
        CapabilityPlan enabledPlan = new CapabilityPlanner().plan(
                new CapabilityDescriptor(
                        "mock-enabled", Set.of(TRANSCRIPT_FINAL, "summary.primary")),
                enabled,
                Set.of("summary.primary"),
                Set.of(TRANSCRIPT_FINAL, "summary.primary"),
                DeploymentLimits.NONE,
                5L);
        FinalizationExecutionFact execution = FinalizationExecutionFact.freeze(
                MEETING_ID, ACCEPTED_AT.minusSeconds(60), null, enabled, enabledPlan);

        var plan = derive(input("sha256:enabled"), execution, reservation("reservation-a"));

        assertEquals(List.of("summary.structured", "transcript.final"),
                plan.artifacts().stream().map(value -> value.spec().artifactType()).toList());
        assertEquals(2, plan.artifacts().size());

        ProductProfile disabledProfile = new ProductProfile(
                "disabled-artifact/mock-v1",
                1,
                Set.of(TRANSCRIPT_FINAL),
                Set.of("export.optional"),
                Map.of(), Map.of(), Map.of(),
                Map.of(
                        TRANSCRIPT_FINAL, List.of("mock-transcript-v1"),
                        "export.optional", List.of("mock-export-v1")),
                Map.of(
                        TRANSCRIPT_FINAL, List.of("transcript.final"),
                        "export.optional", List.of("unsupported.export")));
        CapabilityPlan disabledPlan = new CapabilityPlanner().plan(
                new CapabilityDescriptor("mock-disabled", Set.of(TRANSCRIPT_FINAL)),
                disabledProfile,
                Set.of(),
                Set.of(TRANSCRIPT_FINAL, "export.optional"),
                DeploymentLimits.NONE,
                6L);
        FinalizationExecutionFact disabledExecution = FinalizationExecutionFact.freeze(
                MEETING_ID, ACCEPTED_AT.minusSeconds(60), null,
                disabledProfile, disabledPlan);
        var enabledOnly = derive(
                input("sha256:disabled"), disabledExecution, reservation("reservation-a"));
        assertEquals(List.of("transcript.final"), enabledOnly.artifacts().stream()
                .map(value -> value.spec().artifactType()).toList());
    }

    @Test
    void unknownArtifactFailedPlanMismatchedMeetingAndSubMicrosecondInputFailClosed() {
        ProductProfile unknown = new ProductProfile(
                "unknown-artifact-profile", 1,
                Set.of(TRANSCRIPT_FINAL), Set.of(),
                Map.of(), Map.of(), Map.of(),
                Map.of(TRANSCRIPT_FINAL, List.of("mock-transcript")),
                Map.of(TRANSCRIPT_FINAL, List.of("export.file")));
        CapabilityPlan unknownPlan = new CapabilityPlanner().plan(
                new CapabilityDescriptor("unknown-mock", Set.of(TRANSCRIPT_FINAL)),
                unknown,
                Set.of(),
                Set.of(TRANSCRIPT_FINAL),
                DeploymentLimits.NONE,
                8L);
        FinalizationExecutionFact unknownExecution = FinalizationExecutionFact.freeze(
                MEETING_ID, ACCEPTED_AT.minusSeconds(60), null, unknown, unknownPlan);
        ProductProfile failedProfile = new ProductProfile(
                "failed-plan-profile", 1,
                Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED), Set.of(),
                Map.of(), Map.of(), Map.of(),
                Map.of(
                        TRANSCRIPT_FINAL, List.of("mock-transcript"),
                        SUMMARY_STRUCTURED, List.of("mock-summary")),
                Map.of(
                        TRANSCRIPT_FINAL, List.of("transcript.final"),
                        SUMMARY_STRUCTURED, List.of("summary.structured")));
        CapabilityPlan failedPlan = new CapabilityPlanner().plan(
                new CapabilityDescriptor("failed-mock", Set.of(TRANSCRIPT_FINAL)),
                failedProfile,
                Set.of(),
                Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED),
                DeploymentLimits.NONE,
                9L);
        FinalizationExecutionFact failedExecution = FinalizationExecutionFact.freeze(
                MEETING_ID, ACCEPTED_AT.minusSeconds(60), null,
                failedProfile, failedPlan);
        FinalizationInputFact subMicrosecond = new FinalizationInputFact(
                MEETING_ID, "input-binding-v1", 17, 65_432,
                "zh-CN", List.of("en-US"),
                Instant.parse("2026-09-28T03:00:00.123456789Z"), "sha256:source-a");
        FinalizationExecutionFact otherMeeting = FinalizationExecutionFact.freeze(
                "another-meeting", ACCEPTED_AT.minusSeconds(60), null,
                profile("mock-summary-v1"), capabilityPlan(profile("mock-summary-v1")));

        assertThrows(IllegalArgumentException.class,
                () -> derive(input("sha256:source-a"), unknownExecution,
                        reservation("reservation-a")));
        assertThrows(IllegalArgumentException.class,
                () -> derive(input("sha256:source-a"), failedExecution,
                        reservation("reservation-a")));
        assertThrows(IllegalArgumentException.class,
                () -> derive(subMicrosecond, execution(profile("mock-summary-v1")),
                        reservation("reservation-a")));
        assertThrows(IllegalArgumentException.class,
                () -> derive(input("sha256:source-a"), otherMeeting,
                        reservation("reservation-a")));
        assertFalse(derive(
                input("sha256:source-a"), execution(profile("mock-summary-v1")),
                reservation("reservation-a")).toString().contains(MEETING_ID));
    }

    private static DeterministicMockFinalizationPlanner.Plan derive(
            FinalizationInputFact input,
            FinalizationExecutionFact execution,
            UsageReservation reservation) {
        return new DeterministicMockFinalizationPlanner().derive(
                DEPLOYMENT_SCOPE, SUBJECT_SCOPE, input, execution, reservation);
    }

    static FinalizationInputFact input(String digest) {
        return new FinalizationInputFact(
                MEETING_ID,
                "input-binding-v1",
                17,
                65_432,
                "zh-CN",
                List.of("en-US"),
                ACCEPTED_AT,
                digest);
    }

    static FinalizationExecutionFact execution(ProductProfile profile) {
        return FinalizationExecutionFact.freeze(
                MEETING_ID,
                ACCEPTED_AT.minusSeconds(60),
                "季度会议",
                profile,
                capabilityPlan(profile));
    }

    static ProductProfile profile(String summaryWorkflow) {
        return new ProductProfile(
                "meeting-minutes/mock-v1",
                3,
                Set.of(TRANSCRIPT_FINAL),
                Set.of(SUMMARY_STRUCTURED),
                Map.of(SUMMARY_STRUCTURED, Set.of(TRANSCRIPT_FINAL)),
                Map.of(),
                Map.of(),
                Map.of(
                        TRANSCRIPT_FINAL, List.of("mock-transcript-v1"),
                        SUMMARY_STRUCTURED, List.of(summaryWorkflow)),
                Map.of(
                        TRANSCRIPT_FINAL, List.of("transcript.final"),
                        SUMMARY_STRUCTURED, List.of("summary.structured")));
    }

    static CapabilityPlan capabilityPlan(ProductProfile profile) {
        return new CapabilityPlanner().plan(
                new CapabilityDescriptor(
                        "deterministic-mock",
                        Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED)),
                profile,
                Set.of(SUMMARY_STRUCTURED),
                Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED),
                DeploymentLimits.NONE,
                7L);
    }

    static UsageReservation reservation(String reservationId) {
        return initialReservation(
                        reservationId, MEETING_ID, DEPLOYMENT_SCOPE, SUBJECT_SCOPE)
                .bind(MEETING_ID, ACCEPTED_AT.minusSeconds(20), 0);
    }

    private static UsageReservation initialReservation(
            String reservationId,
            String expectedMeetingId,
            String deploymentScope,
            String subjectScope) {
        Instant createdAt = ACCEPTED_AT.minusSeconds(30);
        MeetingGrant grant = new MeetingGrant(
                "grant-finalization",
                deploymentScope,
                subjectScope,
                "meeting-minutes/mock-v1",
                3,
                Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED),
                Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED),
                300_000,
                createdAt.minusSeconds(1),
                ACCEPTED_AT.plusSeconds(3600));
        return UsageReservation.reserved(
                reservationId,
                grant,
                expectedMeetingId,
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 120_000),
                createdAt,
                ACCEPTED_AT.plusSeconds(1800));
    }

    private static ProductProfile enabledProfile() {
        return new ProductProfile(
                "enabled/mock-v1",
                2,
                Set.of(TRANSCRIPT_FINAL),
                Set.of("summary.primary"),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(
                        TRANSCRIPT_FINAL, List.of("mock-transcript-v1"),
                        "summary.primary", List.of("mock-summary-primary-v1")),
                Map.of(
                        TRANSCRIPT_FINAL, List.of("transcript.final"),
                        "summary.primary", List.of("summary.structured")));
    }
}
