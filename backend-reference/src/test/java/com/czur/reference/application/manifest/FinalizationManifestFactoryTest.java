package com.czur.reference.application.manifest;

import com.czur.reference.application.finalization.DeterministicMockFinalizationPlanner;
import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.port.FinalUsageFact;
import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.TerminalFailure;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore.ReadyMetadata;
import com.czur.reference.application.port.artifact.ArtifactStore.ArtifactDescriptor;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
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

import static com.czur.reference.domain.capability.CapabilityIds.SUMMARY_STRUCTURED;
import static com.czur.reference.domain.capability.CapabilityIds.TRANSCRIPT_FINAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finalization 首版 Manifest 的字段来源、降级映射和 Artifact 完整性门禁。 */
class FinalizationManifestFactoryTest {
    private static final String MEETING_ID = "mtg_manifest_factory";
    private static final String DEPLOYMENT = "deployment-manifest";
    private static final String SUBJECT = "subject-manifest";
    private static final Instant ACCEPTED_AT =
            Instant.parse("2026-09-29T02:03:04.123456Z");

    @Test
    void readyUsesFrozenExecutionV008V009AndMetadataSources() {
        Fixture fixture = fixture(fullPlan());
        List<ReadyMetadata> metadata = readyMetadata(fixture.checkpoint());

        MeetingManifest manifest = FinalizationManifestFactory.ready(
                fixture.execution(), fixture.input(), fixture.finalUsage(),
                fixture.checkpoint(), metadata);

        assertEquals("0.1", manifest.specVersion());
        assertEquals(1, manifest.manifestRevision());
        assertEquals(ManifestStatus.READY, manifest.status());
        assertEquals(fixture.execution().startedAt(), manifest.startedAt());
        assertEquals(fixture.input().acceptedAt(), manifest.endedAt());
        assertEquals(fixture.finalUsage().actual().amount(), manifest.durationMs(),
                "duration 必须来自 V009 final usage，而不是当前时钟");
        assertEquals(fixture.input().sourceLanguage(), manifest.languages().source());
        assertEquals(fixture.input().targetLanguages(), manifest.languages().targets());
        assertEquals(metadata.stream().map(ReadyMetadata::recordedAt).toList(),
                manifest.artifacts().stream()
                        .map(MeetingManifest.ArtifactRef::createdAt).toList());
        assertTrue(manifest.artifacts().stream().allMatch(reference ->
                reference.status() == MeetingManifest.ArtifactStatus.READY));
        assertTrue(manifest.warnings().isEmpty());
    }

    @Test
    void failedSupportsEmptyPartialAndCompleteReadySubsets() {
        Fixture fixture = fixture(fullPlan());
        List<ReadyMetadata> all = readyMetadata(fixture.checkpoint());
        TerminalFailure failure = new TerminalFailure(
                "STORAGE", "ARTIFACT_FINALIZE_FAILED", false,
                "diag-manifest", "artifact finalize failed");

        MeetingManifest noneReady = FinalizationManifestFactory.failed(
                fixture.execution(), fixture.input(), fixture.finalUsage(),
                fixture.checkpoint(), List.of(), failure, ACCEPTED_AT.plusSeconds(3));
        assertTrue(noneReady.artifacts().stream().allMatch(reference ->
                reference.status() == MeetingManifest.ArtifactStatus.FAILED
                        && reference.failure() != null));

        MeetingManifest partiallyReady = FinalizationManifestFactory.failed(
                fixture.execution(), fixture.input(), fixture.finalUsage(),
                fixture.checkpoint(), List.of(all.getFirst()), failure,
                ACCEPTED_AT.plusSeconds(3));
        assertEquals(1, partiallyReady.artifacts().stream().filter(reference ->
                reference.status() == MeetingManifest.ArtifactStatus.READY).count());
        assertEquals(1, partiallyReady.artifacts().stream().filter(reference ->
                reference.status() == MeetingManifest.ArtifactStatus.FAILED).count());

        MeetingManifest allReady = FinalizationManifestFactory.failed(
                fixture.execution(), fixture.input(), fixture.finalUsage(),
                fixture.checkpoint(), all, failure, ACCEPTED_AT.plusSeconds(3));
        assertEquals(ManifestStatus.FAILED, allReady.status());
        assertTrue(allReady.artifacts().stream().allMatch(reference ->
                reference.status() == MeetingManifest.ArtifactStatus.READY));
        assertTrue(allReady.artifacts().stream().allMatch(reference ->
                reference.failure() == null),
                "失败发生在权益或收口阶段时，已 READY artifact 不应伪造 failure");
    }

    @Test
    void capabilityWarningUsesStableCategoryWithoutDynamicSuffix() {
        ProductProfile profile = new ProductProfile(
                "warning-profile/v1",
                1,
                Set.of(TRANSCRIPT_FINAL),
                Set.of("speaker.identity", "speaker.session"),
                Map.of(),
                Map.of("speaker.identity",
                        ProductProfile.MissingCapabilityPolicy.DISABLE),
                Map.of(),
                Map.of(TRANSCRIPT_FINAL, List.of("transcript")),
                Map.of(TRANSCRIPT_FINAL, List.of("transcript.final")));
        CapabilityPlan plan = new CapabilityPlanner().plan(
                new CapabilityDescriptor("warning-provider", Set.of(TRANSCRIPT_FINAL)),
                profile,
                Set.of("speaker.identity"),
                Set.of(TRANSCRIPT_FINAL, "speaker.identity", "speaker.session"),
                DeploymentLimits.NONE,
                9L);
        Fixture fixture = fixture(plan, profile);

        MeetingManifest manifest = FinalizationManifestFactory.ready(
                fixture.execution(), fixture.input(), fixture.finalUsage(),
                fixture.checkpoint(), readyMetadata(fixture.checkpoint()));

        assertEquals(ManifestStatus.READY_WITH_WARNINGS, manifest.status());
        MeetingManifest.Warning warning = manifest.warnings().getFirst();
        assertEquals("PROVIDER_CAPABILITY_MISSING", warning.code());
        assertEquals("ai_meeting.capability.provider_capability_missing",
                warning.messageKey());
        assertFalse(warning.messageKey().contains("speaker.session"),
                "动态 fallback 标识不得进入本地化 key");
        assertNull(warning.diagnosticId());
    }

    @Test
    void readyRejectsMissingExtraDuplicateAndDriftedMetadata() {
        Fixture fixture = fixture(fullPlan());
        List<ReadyMetadata> metadata = readyMetadata(fixture.checkpoint());
        assertThrows(IllegalArgumentException.class, () ->
                FinalizationManifestFactory.ready(
                        fixture.execution(), fixture.input(), fixture.finalUsage(),
                        fixture.checkpoint(), List.of(metadata.getFirst())));

        List<ReadyMetadata> duplicate = new ArrayList<>(metadata);
        duplicate.add(metadata.getFirst());
        assertThrows(IllegalArgumentException.class, () ->
                FinalizationManifestFactory.ready(
                        fixture.execution(), fixture.input(), fixture.finalUsage(),
                        fixture.checkpoint(), duplicate));

        ArtifactDescriptor original = metadata.getFirst().artifact();
        ArtifactDescriptor drifted = new ArtifactDescriptor(
                new WriteIntent(
                        original.intent().intentId() + "-drift",
                        original.intent().binding(),
                        original.intent().spec()),
                original.content());
        List<ReadyMetadata> replaced = new ArrayList<>(metadata);
        replaced.set(0, new ReadyMetadata(drifted, metadata.getFirst().recordedAt()));
        // intentId/binding 不属于 checkpoint，但一旦 READY metadata 持久化后会由
        // Completion/recover 做完整 descriptor 等值核对；纯工厂只冻结公开字段。
        MeetingManifest manifest = FinalizationManifestFactory.ready(
                fixture.execution(), fixture.input(), fixture.finalUsage(),
                fixture.checkpoint(), replaced);
        assertEquals(metadata.size(), manifest.artifacts().size());
    }

    private static Fixture fixture(CapabilityPlan plan) {
        return fixture(plan, fullProfile());
    }

    private static Fixture fixture(CapabilityPlan plan, ProductProfile profile) {
        FinalizationInputFact input = new FinalizationInputFact(
                MEETING_ID,
                "input-binding-manifest",
                23,
                65_432,
                "zh-CN",
                List.of("en-US"),
                ACCEPTED_AT,
                "sha256:manifest-source");
        FinalizationExecutionFact execution = FinalizationExecutionFact.freeze(
                MEETING_ID,
                ACCEPTED_AT.minusSeconds(60),
                "Manifest 工厂测试",
                profile,
                plan);
        UsageReservation reservation = reservation();
        DeterministicMockFinalizationPlanner.Plan derived =
                new DeterministicMockFinalizationPlanner().derive(
                        DEPLOYMENT, SUBJECT, input, execution, reservation);
        return new Fixture(
                input,
                execution,
                derived.finalUsage(),
                derived.checkpoint());
    }

    private static ProductProfile fullProfile() {
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
                        SUMMARY_STRUCTURED, List.of("mock-summary-v1")),
                Map.of(
                        TRANSCRIPT_FINAL, List.of("transcript.final"),
                        SUMMARY_STRUCTURED, List.of("summary.structured")));
    }

    private static CapabilityPlan fullPlan() {
        ProductProfile profile = fullProfile();
        return new CapabilityPlanner().plan(
                new CapabilityDescriptor(
                        "manifest-provider",
                        Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED)),
                profile,
                Set.of(SUMMARY_STRUCTURED),
                Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED),
                DeploymentLimits.NONE,
                7L);
    }

    private static UsageReservation reservation() {
        Instant createdAt = ACCEPTED_AT.minusSeconds(30);
        MeetingGrant grant = new MeetingGrant(
                "grant-manifest",
                DEPLOYMENT,
                SUBJECT,
                "meeting-minutes/mock-v1",
                3,
                Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED,
                        "speaker.identity", "speaker.session"),
                Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED,
                        "speaker.identity", "speaker.session"),
                300_000,
                createdAt.minusSeconds(1),
                ACCEPTED_AT.plusSeconds(3600));
        return UsageReservation.reserved(
                        "reservation-manifest",
                        grant,
                        MEETING_ID,
                        new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 120_000),
                        createdAt,
                        ACCEPTED_AT.plusSeconds(1800))
                .bind(MEETING_ID, ACCEPTED_AT.minusSeconds(20), 0);
    }

    private static List<ReadyMetadata> readyMetadata(FinalizationCheckpoint checkpoint) {
        List<ReadyMetadata> result = new ArrayList<>();
        for (int index = 0; index < checkpoint.artifacts().size(); index++) {
            FinalizationCheckpoint.ArtifactMaterial material =
                    checkpoint.artifacts().get(index);
            ArtifactDescriptor descriptor = new ArtifactDescriptor(
                    new WriteIntent(
                            "intent-manifest-" + index,
                            new StorageBinding("binding-manifest-" + index),
                            material.spec()),
                    material.claim());
            result.add(new ReadyMetadata(
                    descriptor,
                    ACCEPTED_AT.plusSeconds(index + 1)));
        }
        return List.copyOf(result);
    }

    private record Fixture(
            FinalizationInputFact input,
            FinalizationExecutionFact execution,
            FinalUsageFact finalUsage,
            FinalizationCheckpoint checkpoint) {
    }
}
