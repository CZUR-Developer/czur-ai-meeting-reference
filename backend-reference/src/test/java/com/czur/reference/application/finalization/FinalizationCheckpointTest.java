package com.czur.reference.application.finalization;

import com.czur.reference.application.finalization.FinalizationCheckpoint.ArtifactMaterial;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.czur.reference.domain.capability.CapabilityIds.SUMMARY_STRUCTURED;
import static com.czur.reference.domain.capability.CapabilityIds.TRANSCRIPT_FINAL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** checkpoint 自包含恢复、确定性摘要和内容边界测试。 */
public class FinalizationCheckpointTest {
    private static final String MEETING_ID = "meeting-checkpoint-v1";
    private static final String DEPLOYMENT_SCOPE = "deployment-checkpoint";
    private static final String SUBJECT_SCOPE = "subject-checkpoint";
    private static final Instant ACCEPTED_AT =
            Instant.parse("2026-09-28T08:09:10.123456Z");

    @Test
    void checkpointAloneRestoresArtifactsWorkflowUsageAndCommitRequestExactly() throws Exception {
        var plan = plan(input("sha256:source-checkpoint"), reservation("reservation-a"));
        FinalizationCheckpoint checkpoint = plan.checkpoint();

        assertEquals(plan.finalUsage(), checkpoint.finalUsageFact());
        assertEquals(plan.commitUsageRequest(), checkpoint.commitUsageRequest());
        assertEquals(plan.workflowStep().key(), checkpoint.workflowKey());
        assertEquals(1, checkpoint.commitPlan().expectedVersion());
        assertEquals(
                "finalization-usage-v1:32213ed864a2e06a0c36bf0cdcf985ce"
                        + "5bfb09c4956a9bfe853ed38f80857be2",
                checkpoint.commitUsageRequest().idempotencyKey());
        assertEquals(
                "sha256:0de0d11a1b8c3bf4b815818d32df905c"
                        + "bfa1c8eab167a424dfbad67f256f7be9",
                checkpoint.commitUsageRequest().requestDigest());
        assertEquals(UsageUnit.MEETING_DURATION_MS,
                checkpoint.commitUsageRequest().actual().unit());
        assertEquals(54_321, checkpoint.commitUsageRequest().actual().amount());
        for (int index = 0; index < plan.artifacts().size(); index++) {
            ArtifactMaterial expected = plan.artifacts().get(index);
            ArtifactMaterial restored = checkpoint.artifacts().get(index);
            assertEquals(expected.spec(), restored.spec());
            assertEquals(expected.claim(), restored.claim());
            assertArrayEquals(expected.canonicalContentBytes(),
                    restored.canonicalContentBytes());
            try (var content = restored.openContent()) {
                assertArrayEquals(expected.canonicalContentBytes(), content.readAllBytes());
            }
        }
    }

    @Test
    void equivalentArtifactReorderingConvergesAndDefensiveCopiesHold() {
        FinalizationCheckpoint original =
                plan(input("sha256:source-checkpoint"), reservation("reservation-a"))
                        .checkpoint();
        List<ArtifactMaterial> reversed = new ArrayList<>(original.artifacts());
        java.util.Collections.reverse(reversed);
        FinalizationCheckpoint reordered = new FinalizationCheckpoint(
                original.meetingId(),
                original.inputBinding(),
                original.inputFactDigest(),
                original.executionFactDigest(),
                reversed,
                original.commitPlan());

        assertEquals(original, reordered);
        assertEquals(original.hashCode(), reordered.hashCode());

        byte[] callerCopy = original.artifacts().getFirst().canonicalContentBytes();
        byte first = callerCopy[0];
        callerCopy[0] ^= 0x7f;
        assertEquals(first, original.artifacts().getFirst().canonicalContentBytes()[0]);
        assertThrows(UnsupportedOperationException.class,
                () -> original.artifacts().add(original.artifacts().getFirst()));
    }

    @Test
    void inputDigestCoversEveryDurableFieldAndRejectsInvalidTextOrPrecision() {
        FinalizationInputFact original = input("sha256:source-checkpoint");
        String digest = FinalizationCheckpoint.inputFactDigest(original);
        assertTrue(digest.matches("sha256:[0-9a-f]{64}"));
        assertEquals(digest, FinalizationCheckpoint.inputFactDigest(original));

        List<FinalizationInputFact> drifts = List.of(
                new FinalizationInputFact(MEETING_ID, "different-binding", 8, 54_321,
                        "zh-CN", List.of("en-US", "ja-JP"), ACCEPTED_AT,
                        "sha256:source-checkpoint"),
                new FinalizationInputFact(MEETING_ID, "input-binding-checkpoint", 9, 54_321,
                        "zh-CN", List.of("en-US", "ja-JP"), ACCEPTED_AT,
                        "sha256:source-checkpoint"),
                new FinalizationInputFact(MEETING_ID, "input-binding-checkpoint", 8, 54_322,
                        "zh-CN", List.of("en-US", "ja-JP"), ACCEPTED_AT,
                        "sha256:source-checkpoint"),
                new FinalizationInputFact(MEETING_ID, "input-binding-checkpoint", 8, 54_321,
                        "en-US", List.of("en-US", "ja-JP"), ACCEPTED_AT,
                        "sha256:source-checkpoint"),
                new FinalizationInputFact(MEETING_ID, "input-binding-checkpoint", 8, 54_321,
                        "zh-CN", List.of("ja-JP", "en-US"), ACCEPTED_AT,
                        "sha256:source-checkpoint"),
                new FinalizationInputFact(MEETING_ID, "input-binding-checkpoint", 8, 54_321,
                        "zh-CN", List.of("en-US", "ja-JP"), ACCEPTED_AT.plusSeconds(1),
                        "sha256:source-checkpoint"),
                input("sha256:different-source"));
        for (FinalizationInputFact drift : drifts) {
            assertNotEquals(digest, FinalizationCheckpoint.inputFactDigest(drift));
        }

        FinalizationInputFact subMicrosecond = new FinalizationInputFact(
                MEETING_ID, "input-binding-checkpoint", 8, 54_321,
                "zh-CN", List.of("en-US", "ja-JP"),
                Instant.parse("2026-09-28T08:09:10.123456789Z"),
                "sha256:source-checkpoint");
        FinalizationInputFact invalidSurrogate = new FinalizationInputFact(
                MEETING_ID, "input-\ud800", 8, 54_321,
                "zh-CN", List.of("en-US"), ACCEPTED_AT,
                "sha256:source-checkpoint");
        assertThrows(IllegalArgumentException.class,
                () -> FinalizationCheckpoint.inputFactDigest(subMicrosecond));
        assertThrows(IllegalArgumentException.class,
                () -> FinalizationCheckpoint.inputFactDigest(invalidSurrogate));
    }

    @Test
    void artifactMaterialRejectsOversizeMismatchedClaimsAndMalformedUtf8() {
        ArtifactMaterial fixture =
                plan(input("sha256:source-checkpoint"), reservation("reservation-a"))
                        .artifacts().getFirst();
        byte[] oversized = new byte[FinalizationCheckpoint.MAX_ARTIFACT_CONTENT_BYTES + 1];
        Arrays.fill(oversized, (byte) 'a');
        ContentClaim oversizedClaim = new ContentClaim(
                oversized.length, FinalizationOperationIdentity.checksum(oversized));
        WriteIntentSpec oversizedSpec = copySpec(
                fixture.spec(), oversizedClaim.size(), oversizedClaim.checksum());
        byte[] malformed = {(byte) 0xc3, (byte) 0x28};
        ContentClaim malformedClaim = new ContentClaim(
                malformed.length, FinalizationOperationIdentity.checksum(malformed));

        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactMaterial(oversizedSpec, oversized, oversizedClaim));
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactMaterial(
                        fixture.spec(), fixture.canonicalContentBytes(),
                        new ContentClaim(fixture.claim().size(),
                                "sha256:" + "0".repeat(64))));
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactMaterial(
                        copySpec(fixture.spec(), malformedClaim.size(),
                                malformedClaim.checksum()),
                        malformed,
                        malformedClaim));
    }

    @Test
    void artifactMaterialRejectsEverySingleFieldMetadataTamper() {
        ArtifactMaterial fixture =
                plan(input("sha256:source-checkpoint"), reservation("reservation-a"))
                        .artifacts().getFirst();
        WriteIntentSpec original = fixture.spec();
        List<WriteIntentSpec> tampered = List.of(
                new WriteIntentSpec(
                        original.artifactId() + "-tampered",
                        original.meetingId(), original.artifactType(),
                        original.mediaType(), original.language(), original.revision(),
                        original.expectedSize(), original.expectedChecksum()),
                new WriteIntentSpec(
                        original.artifactId(), original.meetingId(), "transcript.final",
                        original.mediaType(), original.language(), original.revision(),
                        original.expectedSize(), original.expectedChecksum()),
                new WriteIntentSpec(
                        original.artifactId(), original.meetingId(), original.artifactType(),
                        "application/problem+json", original.language(), original.revision(),
                        original.expectedSize(), original.expectedChecksum()),
                new WriteIntentSpec(
                        original.artifactId(), original.meetingId(), original.artifactType(),
                        original.mediaType(), "en-US", original.revision(),
                        original.expectedSize(), original.expectedChecksum()),
                new WriteIntentSpec(
                        original.artifactId(), original.meetingId(), original.artifactType(),
                        original.mediaType(), original.language(), original.revision() + 1,
                        original.expectedSize(), original.expectedChecksum()));

        for (WriteIntentSpec changed : tampered) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ArtifactMaterial(
                            changed, fixture.canonicalContentBytes(), fixture.claim()));
        }
    }

    @Test
    void coordinatedContentSizeAndChecksumTamperCannotBypassSemanticChecks() {
        FinalizationCheckpoint checkpoint =
                plan(input("sha256:source-checkpoint"), reservation("reservation-a"))
                        .checkpoint();
        ArtifactMaterial fixture = checkpoint.artifacts().getFirst();
        MockFinalizationArtifactContent original = fixture.content();

        byte[] changedLanguage = new MockFinalizationArtifactContent(
                original.artifactType(),
                original.executionFingerprint(),
                "en-US",
                original.revision(),
                original.sourceFingerprint()).canonicalBytes();
        ContentClaim changedLanguageClaim = new ContentClaim(
                changedLanguage.length,
                FinalizationOperationIdentity.checksum(changedLanguage));
        WriteIntentSpec changedLanguageSpec = copySpec(
                fixture.spec(), changedLanguage.length, changedLanguageClaim.checksum());
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactMaterial(
                        changedLanguageSpec, changedLanguage, changedLanguageClaim));

        List<MockFinalizationArtifactContent> changedRootFingerprints = List.of(
                new MockFinalizationArtifactContent(
                        original.artifactType(),
                        original.executionFingerprint(),
                        original.language(),
                        original.revision(),
                        "0".repeat(64)),
                new MockFinalizationArtifactContent(
                        original.artifactType(),
                        "0".repeat(64),
                        original.language(),
                        original.revision(),
                        original.sourceFingerprint()));
        for (MockFinalizationArtifactContent changedContent : changedRootFingerprints) {
            byte[] changedBytes = changedContent.canonicalBytes();
            ContentClaim changedClaim = new ContentClaim(
                    changedBytes.length,
                    FinalizationOperationIdentity.checksum(changedBytes));
            ArtifactMaterial changedMaterial = new ArtifactMaterial(
                    copySpec(
                            fixture.spec(), changedBytes.length, changedClaim.checksum()),
                    changedBytes,
                    changedClaim);
            List<ArtifactMaterial> artifacts = new ArrayList<>(checkpoint.artifacts());
            artifacts.set(artifacts.indexOf(fixture), changedMaterial);

            assertThrows(IllegalArgumentException.class,
                    () -> new FinalizationCheckpoint(
                            checkpoint.meetingId(),
                            checkpoint.inputBinding(),
                            checkpoint.inputFactDigest(),
                            checkpoint.executionFactDigest(),
                            artifacts,
                            checkpoint.commitPlan()));
        }
    }

    @Test
    void mockArtifactContentCodecAcceptsOnlyCanonicalClosedShapeUtf8() {
        MockFinalizationArtifactContent content = new MockFinalizationArtifactContent(
                "summary.structured", "0".repeat(64), "zh-CN", 1, "1".repeat(64));
        byte[] canonicalBytes = content.canonicalBytes();
        String canonical = new String(canonicalBytes, StandardCharsets.UTF_8);

        MockFinalizationArtifactContent restored =
                MockFinalizationArtifactContent.decodeCanonical(canonicalBytes);
        assertEquals(content, restored);
        assertArrayEquals(canonicalBytes, restored.canonicalBytes());

        List<String> nonCanonical = List.of(
                canonical.replace(
                        "{\"artifactType\":\"summary.structured\","
                                + "\"executionFingerprint\":\"" + "0".repeat(64) + "\"",
                        "{\"executionFingerprint\":\"" + "0".repeat(64) + "\","
                                + "\"artifactType\":\"summary.structured\""),
                canonical.replace(",\"mock\":true", ",\"unknown\":false,\"mock\":true"),
                canonical.replace(",\"language\":\"zh-CN\"", ""),
                canonical.replace("\"language\":\"zh-CN\"",
                        "\"language\":\"zh\\u002dCN\""),
                canonical.replace("\"revision\":1", "\"revision\":01"),
                canonical.replace("\"language\":\"zh-CN\"",
                        "\"language\":\"\\ud800\""));
        for (String value : nonCanonical) {
            assertThrows(IllegalArgumentException.class,
                    () -> MockFinalizationArtifactContent.decodeCanonical(
                            value.getBytes(StandardCharsets.UTF_8)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> MockFinalizationArtifactContent.decodeCanonical(
                        new byte[]{(byte) 0xc3, (byte) 0x28}));
    }

    @Test
    void transcriptContainsStableReadableMockTextAndRejectsRehashedTampering() {
        FinalizationCheckpoint checkpoint =
                plan(input("sha256:source-checkpoint"), reservation("reservation-a"))
                        .checkpoint();
        ArtifactMaterial transcript = checkpoint.artifacts().stream()
                .filter(value -> "transcript.final".equals(value.spec().artifactType()))
                .findFirst().orElseThrow();
        ArtifactMaterial summary = checkpoint.artifacts().stream()
                .filter(value -> "summary.structured".equals(value.spec().artifactType()))
                .findFirst().orElseThrow();
        String canonical = transcript.canonicalContent();

        assertTrue(canonical.contains("\"text\":\"【模拟最终转写】大家好，会议现在开始。"
                + "这段文字仅供演示，并非实际音频识别结果。\""));
        assertFalse(summary.canonicalContent().contains("\"text\""),
                "summary 的既有内容形状不随最终转写扩展");
        assertEquals(transcript.canonicalContentBytes().length,
                transcript.spec().expectedSize());
        assertEquals(FinalizationOperationIdentity.checksum(
                transcript.canonicalContentBytes()), transcript.claim().checksum());
        assertEquals(transcript.content(), MockFinalizationArtifactContent.decodeCanonical(
                transcript.canonicalContentBytes()));

        for (String tampered : List.of(
                canonical.replace("大家好", "早上好"),
                canonical.replaceFirst(",\\\"text\\\":\\\"[^\\\"]+\\\"", ""),
                canonical.replace("\"text\":", "\"extra\":true,\"text\":"))) {
            byte[] bytes = tampered.getBytes(StandardCharsets.UTF_8);
            ContentClaim claim = new ContentClaim(bytes.length,
                    FinalizationOperationIdentity.checksum(bytes));
            WriteIntentSpec spec = copySpec(
                    transcript.spec(), bytes.length, claim.checksum());
            assertThrows(IllegalArgumentException.class,
                    () -> new ArtifactMaterial(spec, bytes, claim));
        }
    }

    @Test
    void checkpointDiagnosticsDoNotExposeProtectedValues() {
        FinalizationCheckpoint checkpoint =
                plan(input("sha256:source-checkpoint"), reservation("reservation-a"))
                        .checkpoint();
        var commitRequest = checkpoint.commitUsageRequest();
        String diagnostic = checkpoint.toString() + checkpoint.commitPlan()
                + checkpoint.commitPlan().identity()
                + commitRequest
                + checkpoint.artifacts().getFirst();
        for (String protectedValue : List.of(
                MEETING_ID,
                DEPLOYMENT_SCOPE,
                SUBJECT_SCOPE,
                commitRequest.operationScope(),
                checkpoint.inputBinding(),
                checkpoint.inputFactDigest(),
                checkpoint.executionFactDigest(),
                checkpoint.commitPlan().reservationId(),
                checkpoint.commitUsageRequest().idempotencyKey(),
                checkpoint.commitUsageRequest().requestDigest(),
                checkpoint.artifacts().getFirst().canonicalContent())) {
            assertFalse(diagnostic.contains(protectedValue));
        }
    }

    private static DeterministicMockFinalizationPlanner.Plan plan(
            FinalizationInputFact input,
            UsageReservation reservation) {
        ProductProfile profile = profile();
        CapabilityPlan capabilityPlan = new CapabilityPlanner().plan(
                new CapabilityDescriptor(
                        "checkpoint-mock", Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED)),
                profile,
                Set.of(SUMMARY_STRUCTURED),
                Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED),
                DeploymentLimits.NONE,
                12L);
        FinalizationExecutionFact execution = FinalizationExecutionFact.freeze(
                MEETING_ID,
                ACCEPTED_AT.minusSeconds(90),
                null,
                profile,
                capabilityPlan);
        return new DeterministicMockFinalizationPlanner().derive(
                DEPLOYMENT_SCOPE,
                SUBJECT_SCOPE,
                input,
                execution,
                reservation);
    }

    public static DeterministicMockFinalizationPlanner.Plan fixturePlan() {
        return plan(input("sha256:source-checkpoint"), reservation("reservation-a"));
    }

    private static FinalizationInputFact input(String digest) {
        return new FinalizationInputFact(
                MEETING_ID,
                "input-binding-checkpoint",
                8,
                54_321,
                "zh-CN",
                List.of("en-US", "ja-JP"),
                ACCEPTED_AT,
                digest);
    }

    private static ProductProfile profile() {
        return new ProductProfile(
                "meeting-minutes/checkpoint-v1",
                6,
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

    private static UsageReservation reservation(String reservationId) {
        Instant createdAt = ACCEPTED_AT.minusSeconds(60);
        MeetingGrant grant = new MeetingGrant(
                "grant-checkpoint",
                DEPLOYMENT_SCOPE,
                SUBJECT_SCOPE,
                "meeting-minutes/checkpoint-v1",
                6,
                Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED),
                Set.of(TRANSCRIPT_FINAL, SUMMARY_STRUCTURED),
                300_000,
                createdAt.minusSeconds(1),
                ACCEPTED_AT.plusSeconds(3600));
        return UsageReservation.reserved(
                        reservationId,
                        grant,
                        MEETING_ID,
                        new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 120_000),
                        createdAt,
                        ACCEPTED_AT.plusSeconds(1800))
                .bind(MEETING_ID, createdAt.plusSeconds(1), 0);
    }

    private static WriteIntentSpec copySpec(
            WriteIntentSpec source,
            long expectedSize,
            String expectedChecksum) {
        return new WriteIntentSpec(
                source.artifactId(), source.meetingId(), source.artifactType(),
                source.mediaType(), source.language(), source.revision(),
                expectedSize, expectedChecksum);
    }
}
