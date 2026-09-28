package com.czur.reference.application.finalization;

import com.czur.reference.application.artifact.filesystem.PersistentFileSystemMockArtifactStore;
import com.czur.reference.application.persistence.InMemoryWorkflowStepStore;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.WorkflowStepStore;
import com.czur.reference.application.port.artifact.ArtifactStore.CompleteStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.PrepareStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.StageStatus;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.capability.CapabilityPlanner;
import com.czur.reference.domain.capability.DeploymentLimits;
import com.czur.reference.domain.capability.ProductProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 确定性 Mock 收口值派生、显式能力映射和同 identity 漂移关闭门禁。 */
class DeterministicMockFinalizationPlannerTest {
    private static final Instant ACCEPTED_AT =
            Instant.parse("2026-09-28T03:00:00.123456Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void sameDurableInputProfileAndPlanDeriveByteIdenticalFacts() {
        DeterministicMockFinalizationPlanner planner =
                new DeterministicMockFinalizationPlanner();
        ProductProfile profile = profile("mock-summary-v1");
        CapabilityPlan capabilityPlan = capabilityPlan(profile);

        var first = planner.derive(input("sha256:source-a"), profile, capabilityPlan);
        var replay = planner.derive(input("sha256:source-a"), profile, capabilityPlan);

        assertEquals(first, replay);
        assertEquals(2, first.artifacts().size());
        assertEquals(List.of("summary.structured", "transcript.final"),
                first.artifacts().stream().map(value -> value.spec().artifactType()).toList());
        assertEquals(2, first.artifacts().stream()
                .map(value -> value.spec().artifactId()).distinct().count());
        assertEquals(2, first.artifacts().stream()
                .map(value -> value.claim().checksum()).distinct().count());
        assertEquals(ACCEPTED_AT, first.workflowStep().nextAttemptAt());
        assertEquals(ACCEPTED_AT, first.workflowStep().updatedAt());
        assertEquals(first.finalUsage().actual().amount(), input("sha256:source-a").audioDurationMs());
    }

    @Test
    void fixedFixtureFreezesIdsContentChecksumsAndWorkflowDefinition() {
        ProductProfile profile = profile("mock-summary-v1");
        var plan = new DeterministicMockFinalizationPlanner().derive(
                input("sha256:source-a"), profile, capabilityPlan(profile));

        assertEquals(
                "mock_finalize_v1_4bdd8a672b91b7f14c13e71b2c588513"
                        + "b905e86d118f56ae6cd9172d4f90e2a1",
                plan.workflowStep().key().stepKey());
        assertEquals(
                "sha256:c97ffbad2e31581a1133f12d4f5843319f1584185562c5dd127a327a92028c7b",
                plan.workflowStep().contentDigest());
        assertEquals(
                "art_mock_v1_3671370abfa25048812da8c8a5fcf291"
                        + "437fc6e8b668feafe50e8029067feb0a",
                plan.artifacts().get(0).spec().artifactId());
        assertEquals(
                "sha256:452e31e706d8d7dc846d81bc0b70af53373bc63077b5376b32e388abae42c9f9",
                plan.artifacts().get(0).claim().checksum());
        assertEquals(
                "art_mock_v1_580877f447eca8211daf35adb32e32b4"
                        + "cf55cd4de56d8cda21932a4913cafa36",
                plan.artifacts().get(1).spec().artifactId());
        assertEquals(
                "sha256:39b1a66552084283c777824bf5d66165462376127958f624f83421dfee5b0497",
                plan.artifacts().get(1).claim().checksum());
        assertEquals(
                "{\"artifactType\":\"summary.structured\",\"mock\":true,"
                        + "\"planRevision\":7,\"profileRevision\":3,"
                        + "\"schemaVersion\":\"mock-finalization/v1\","
                        + "\"sourceFingerprint\":\"4e63391a27cf566c1f4e4fce850c0993"
                        + "79dc8bbebbd643018a1f98bce9cabb6f\","
                        + "\"executionFingerprint\":\"bdefd3a06acb81a24de0c7666856fb05"
                        + "32f7cb65cff8a3bb7bbe59e43bd19ac7\"}\n",
                plan.artifacts().get(0).canonicalContent());
        assertEquals(
                "{\"artifactType\":\"transcript.final\",\"mock\":true,"
                        + "\"planRevision\":7,\"profileRevision\":3,"
                        + "\"schemaVersion\":\"mock-finalization/v1\","
                        + "\"sourceFingerprint\":\"4e63391a27cf566c1f4e4fce850c0993"
                        + "79dc8bbebbd643018a1f98bce9cabb6f\","
                        + "\"executionFingerprint\":\"bdefd3a06acb81a24de0c7666856fb05"
                        + "32f7cb65cff8a3bb7bbe59e43bd19ac7\"}\n",
                plan.artifacts().get(1).canonicalContent());
    }

    @Test
    void sourceOrExecutionDriftKeepsLogicalIdentityAndConflictsWithoutOverwrite()
            throws Exception {
        DeterministicMockFinalizationPlanner planner =
                new DeterministicMockFinalizationPlanner();
        ProductProfile originalProfile = profile("mock-summary-v1");
        ProductProfile driftedProfile = profile("mock-summary-v2");
        var original = planner.derive(
                input("sha256:source-a"), originalProfile, capabilityPlan(originalProfile));
        var sourceDrift = planner.derive(
                input("sha256:source-b"), originalProfile, capabilityPlan(originalProfile));
        var planDrift = planner.derive(
                input("sha256:source-a"), driftedProfile, capabilityPlan(driftedProfile));

        assertEquals(original.workflowStep().key(), sourceDrift.workflowStep().key());
        assertEquals(original.workflowStep().key(), planDrift.workflowStep().key());
        assertNotEquals(original.workflowStep().contentDigest(),
                sourceDrift.workflowStep().contentDigest());
        assertNotEquals(original.workflowStep().contentDigest(),
                planDrift.workflowStep().contentDigest());
        assertEquals(original.artifacts().stream().map(value -> value.spec().artifactId()).toList(),
                sourceDrift.artifacts().stream().map(value -> value.spec().artifactId()).toList());
        assertEquals(original.artifacts().stream().map(value -> value.spec().artifactId()).toList(),
                planDrift.artifacts().stream().map(value -> value.spec().artifactId()).toList());

        InMemoryWorkflowStepStore workflow = new InMemoryWorkflowStepStore();
        assertEquals(WorkflowStepStore.RegisterStatus.APPLIED,
                workflow.register(original.workflowStep()).status());
        assertEquals(WorkflowStepStore.RegisterStatus.CONFLICT,
                workflow.register(sourceDrift.workflowStep()).status());
        assertEquals(WorkflowStepStore.RegisterStatus.CONFLICT,
                workflow.register(planDrift.workflowStep()).status());
        assertEquals(original.workflowStep(), workflow.find(original.workflowStep().key()).orElseThrow());

        var artifactStore = new PersistentFileSystemMockArtifactStore(
                temporaryDirectory.resolve("artifacts"), 1024 * 1024);
        var originalArtifact = original.artifacts().getFirst();
        var intent = artifactStore.createWriteIntent(originalArtifact.spec()).intent();
        assertEquals(StageStatus.APPLIED,
                artifactStore.writeContent(intent, originalArtifact.openContent()).status());
        assertEquals(CompleteStatus.APPLIED,
                artifactStore.completeWrite(intent, originalArtifact.claim()).status());
        assertEquals(PrepareStatus.CONFLICT,
                artifactStore.createWriteIntent(
                        sourceDrift.artifacts().getFirst().spec()).status());
        assertEquals(PrepareStatus.CONFLICT,
                artifactStore.createWriteIntent(
                        planDrift.artifacts().getFirst().spec()).status());
    }

    @Test
    void executableDecisionsConsumeExplicitMappingsDeduplicateAndIgnoreDisabledArtifacts() {
        ProductProfile profile = fallbackProfile();
        Set<String> providerCapabilities = Set.of(TRANSCRIPT_FINAL, "summary.fallback");
        CapabilityPlan capabilityPlan = new CapabilityPlanner().plan(
                new CapabilityDescriptor("mock-fallback", providerCapabilities),
                profile,
                Set.of("summary.primary"),
                Set.of(TRANSCRIPT_FINAL, "summary.primary", "summary.fallback"),
                DeploymentLimits.NONE,
                5L);

        var plan = new DeterministicMockFinalizationPlanner().derive(
                input("sha256:fallback"), profile, capabilityPlan);

        assertEquals(List.of("summary.structured", "transcript.final"),
                plan.artifacts().stream().map(value -> value.spec().artifactType()).toList());
        assertEquals(2, plan.artifacts().size(),
                "USE_FALLBACK and fallback ENABLE may name the same explicit artifact");

        ProductProfile disabledProfile = new ProductProfile(
                "disabled-artifact/mock-v1",
                1,
                Set.of(TRANSCRIPT_FINAL),
                Set.of("export.optional"),
                Map.of(),
                Map.of(),
                Map.of(),
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
        var enabledOnly = new DeterministicMockFinalizationPlanner().derive(
                input("sha256:disabled"), disabledProfile, disabledPlan);

        assertEquals(List.of("transcript.final"), enabledOnly.artifacts().stream()
                .map(value -> value.spec().artifactType()).toList());
    }

    @Test
    void failedIncompleteUnknownOrSubMicrosecondInputsFailClosed() {
        ProductProfile profile = profile("mock-summary-v1");
        CapabilityPlan enabled = capabilityPlan(profile);
        DeterministicMockFinalizationPlanner planner =
                new DeterministicMockFinalizationPlanner();
        ProductProfile unknown = new ProductProfile(
                "unknown-artifact-profile", 1,
                Set.of(TRANSCRIPT_FINAL), Set.of(),
                Map.of(), Map.of(), Map.of(),
                Map.of(TRANSCRIPT_FINAL, List.of("mock-transcript")),
                Map.of(TRANSCRIPT_FINAL, List.of("export.file")));
        CapabilityPlan unknownPlan = new CapabilityPlanner().plan(
                new CapabilityDescriptor(Set.of(TRANSCRIPT_FINAL)), unknown,
                Set.of(), Set.of(TRANSCRIPT_FINAL), DeploymentLimits.NONE);
        FinalizationInputFact subMicrosecond = new FinalizationInputFact(
                "meeting-finalization-recovery", "input-binding-v1", 17, 65_432,
                "zh-CN", List.of("en-US"),
                Instant.parse("2026-09-28T03:00:00.123456789Z"), "sha256:source-a");

        assertThrows(IllegalArgumentException.class,
                () -> planner.derive(input("sha256:source-a"), unknown, unknownPlan));
        assertThrows(IllegalArgumentException.class,
                () -> planner.derive(subMicrosecond, profile, enabled));

        CapabilityPlan incomplete = new CapabilityPlan(
                enabled.planRevision(), List.of(enabled.decisions().getFirst()));
        assertThrows(IllegalArgumentException.class,
                () -> planner.derive(input("sha256:source-a"), profile, incomplete));
        assertFalse(planner.derive(input("sha256:source-a"), profile, enabled)
                .toString().contains("meeting-finalization-recovery"));
    }

    private static FinalizationInputFact input(String digest) {
        return new FinalizationInputFact(
                "meeting-finalization-recovery",
                "input-binding-v1",
                17,
                65_432,
                "zh-CN",
                List.of("en-US"),
                ACCEPTED_AT,
                digest);
    }

    private static ProductProfile profile(String summaryWorkflow) {
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

    private static CapabilityPlan capabilityPlan(ProductProfile profile) {
        return new CapabilityPlanner().plan(
                new CapabilityDescriptor("deterministic-mock",
                        Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED)),
                profile,
                Set.of(SUMMARY_STRUCTURED),
                Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED),
                DeploymentLimits.NONE,
                7L);
    }

    private static ProductProfile fallbackProfile() {
        return new ProductProfile(
                "fallback/mock-v1",
                2,
                Set.of(TRANSCRIPT_FINAL),
                Set.of("summary.primary", "summary.fallback"),
                Map.of(),
                Map.of("summary.primary", ProductProfile.MissingCapabilityPolicy.USE_FALLBACK),
                Map.of("summary.primary", "summary.fallback"),
                Map.of(
                        TRANSCRIPT_FINAL, List.of("mock-transcript-v1"),
                        "summary.primary", List.of("mock-summary-primary-v1"),
                        "summary.fallback", List.of("mock-summary-fallback-v1")),
                Map.of(
                        TRANSCRIPT_FINAL, List.of("transcript.final"),
                        "summary.primary", List.of("summary.structured"),
                        "summary.fallback", List.of("summary.structured")));
    }
}
