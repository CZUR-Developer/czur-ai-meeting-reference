package com.czur.reference.application.deletion;

import com.czur.reference.application.deletion.ArtifactDeletionPlan.SourceFinalizationWorkflow;
import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 删除计划纯值契约、身份交叉核对、排序和诊断脱敏门禁。 */
class ArtifactDeletionPlanTest {
    private static final String MEETING = "meeting-deletion-plan-v1";
    private static final Instant ACCEPTED_AT =
            Instant.parse("2026-09-29T06:07:08.123456Z");
    private static final String SOURCE_DIGEST = "sha256:" + "a".repeat(64);
    private static final String CHECKSUM_A = "sha256:" + "1".repeat(64);
    private static final String CHECKSUM_B = "sha256:" + "2".repeat(64);

    @Test
    void completePlanSortsTargetsAndFreezesAStableWorkflowIdentity() {
        ArtifactDeletionTarget transcript = target(
                "artifact-transcript", "transcript.final", 1, 120, CHECKSUM_B);
        ArtifactDeletionTarget summary = target(
                "artifact-summary", "summary.structured", 2, 80, CHECKSUM_A);
        ArrayList<ArtifactDeletionTarget> input = new ArrayList<>(
                List.of(transcript, summary));

        ArtifactDeletionPlan plan = new ArtifactDeletionPlan(
                MEETING, ACCEPTED_AT, 7, sourceWorkflow(), input);
        input.clear();

        assertEquals(List.of(summary, transcript), plan.targets());
        assertEquals(7, plan.sourceManifestRevision());
        assertEquals(sourceWorkflow(), plan.sourceFinalizationWorkflow());
        assertEquals(MEETING, plan.workflowKey().meetingId());
        assertEquals(ArtifactDeletionPlan.STEP_TYPE, plan.workflowKey().stepType());
        assertEquals(
                "artifact_delete_v1_"
                        + "97ef870b8eda827a7bce7a6b8e7b7e8923b49cc6473f7cf02867c5ab01208f5d",
                plan.workflowKey().stepKey());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.targets().add(summary));
    }

    @Test
    void strictEmptyPlanDoesNotInferBusinessState() {
        ArtifactDeletionPlan empty = new ArtifactDeletionPlan(
                MEETING, ACCEPTED_AT, null, null, List.of());

        assertTrue(empty.targets().isEmpty());
        assertEquals(null, empty.sourceManifestRevision());
        assertEquals(null, empty.sourceFinalizationWorkflow());
    }

    @Test
    void targetRequiresExactArtifactIdentityAndStrictUtf8() {
        WriteIntentSpec spec = spec(
                MEETING, "artifact-a", "summary.structured", 1, 10, CHECKSUM_A);

        assertEquals(spec, ArtifactDeletionTarget.fromSpec(spec).spec());
        assertInvalidTarget(() -> new ArtifactDeletionTarget("artifact-b", spec));
        assertInvalidTarget(() -> ArtifactDeletionTarget.fromSpec(spec(
                MEETING,
                "artifact-" + Character.toString((char) 0xd800),
                "summary.structured", 1, 10, CHECKSUM_A)));
    }

    @Test
    void crossMeetingDuplicateArtifactAndDuplicateTypeRevisionFailClosed() {
        ArtifactDeletionTarget a = target(
                "artifact-a", "summary.structured", 1, 10, CHECKSUM_A);
        ArtifactDeletionTarget sameId = new ArtifactDeletionTarget(
                "artifact-a",
                spec(MEETING, "artifact-a", "transcript.final", 1, 11, CHECKSUM_B));
        ArtifactDeletionTarget sameLogicalVersion = target(
                "artifact-b", "summary.structured", 1, 11, CHECKSUM_B);
        ArtifactDeletionTarget anotherMeeting = new ArtifactDeletionTarget(
                "artifact-c",
                spec("another-meeting", "artifact-c", "notes", 1, 11, CHECKSUM_B));

        assertInvalidPlan(() -> plan(List.of(a, sameId)));
        assertInvalidPlan(() -> plan(List.of(a, sameLogicalVersion)));
        assertInvalidPlan(() -> plan(List.of(a, anotherMeeting)));
    }

    @Test
    void sourceWorkflowMustBeTheFinalizationDefinitionForTheSameMeeting() {
        WorkflowStep.Key expected = FinalizationCheckpoint.workflowKey(MEETING);
        assertInvalidPlan(() -> new ArtifactDeletionPlan(
                MEETING, ACCEPTED_AT, 1,
                new SourceFinalizationWorkflow(
                        FinalizationCheckpoint.workflowKey("another-meeting"),
                        FinalizationCheckpoint.CHECKPOINT_TYPE,
                        SOURCE_DIGEST),
                List.of()));
        assertInvalidPlan(() -> new ArtifactDeletionPlan(
                MEETING, ACCEPTED_AT, 1,
                new SourceFinalizationWorkflow(
                        new WorkflowStep.Key(MEETING, "wrong-step", expected.stepKey()),
                        FinalizationCheckpoint.CHECKPOINT_TYPE,
                        SOURCE_DIGEST),
                List.of()));
        assertInvalidPlan(() -> new ArtifactDeletionPlan(
                MEETING, ACCEPTED_AT, 1,
                new SourceFinalizationWorkflow(
                        new WorkflowStep.Key(MEETING, expected.stepType(), "wrong-key"),
                        FinalizationCheckpoint.CHECKPOINT_TYPE,
                        SOURCE_DIGEST),
                List.of()));
        assertInvalidPlan(() -> new ArtifactDeletionPlan(
                MEETING, ACCEPTED_AT, 1,
                new SourceFinalizationWorkflow(
                        expected, "wrong-checkpoint/v1", SOURCE_DIGEST),
                List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceFinalizationWorkflow(
                        expected, FinalizationCheckpoint.CHECKPOINT_TYPE, "sha256:ABC"));
    }

    @Test
    void timeRevisionCountAndNullBoundariesFailClosed() {
        assertInvalidPlan(() -> new ArtifactDeletionPlan(
                MEETING, ACCEPTED_AT.plusNanos(1), null, null, List.of()));
        assertInvalidPlan(() -> new ArtifactDeletionPlan(
                MEETING, ACCEPTED_AT, 0, null, List.of()));
        assertInvalidPlan(() -> new ArtifactDeletionPlan(
                MEETING, ACCEPTED_AT, -1, null, List.of()));
        assertInvalidPlan(() -> new ArtifactDeletionPlan(
                MEETING, ACCEPTED_AT, 1, null, null));

        ArrayList<ArtifactDeletionTarget> tooMany = new ArrayList<>();
        for (int index = 0; index <= ArtifactDeletionPlan.MAX_TARGETS; index++) {
            tooMany.add(target(
                    "artifact-" + index,
                    "artifact.type." + index,
                    1,
                    index,
                    CHECKSUM_A));
        }
        assertInvalidPlan(() -> plan(tooMany));
    }

    @Test
    void valueEqualityIsStructuralAndDiagnosticsDoNotLeakSensitiveValues() {
        ArtifactDeletionPlan first = plan(List.of(target(
                "artifact-secret", "summary.secret", 1, 10, CHECKSUM_A)));
        ArtifactDeletionPlan second = plan(List.of(target(
                "artifact-secret", "summary.secret", 1, 10, CHECKSUM_A)));

        assertNotSame(first, second);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        String diagnostics = first + " " + first.targets().getFirst() + " "
                + first.sourceFinalizationWorkflow();
        for (String secret : new String[]{
                MEETING, "artifact-secret", "summary.secret", CHECKSUM_A, SOURCE_DIGEST}) {
            assertFalse(diagnostics.contains(secret));
        }

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ArtifactDeletionPlan(
                        "secret-meeting", ACCEPTED_AT, null, null,
                        List.of(target(
                                "artifact-secret", "summary.secret", 1, 10, CHECKSUM_A))));
        assertEquals("Artifact deletion plan is invalid", failure.getMessage());
        assertEquals(null, failure.getCause());
    }

    private static ArtifactDeletionPlan plan(List<ArtifactDeletionTarget> targets) {
        return new ArtifactDeletionPlan(
                MEETING, ACCEPTED_AT, 7, sourceWorkflow(), targets);
    }

    private static SourceFinalizationWorkflow sourceWorkflow() {
        return new SourceFinalizationWorkflow(
                FinalizationCheckpoint.workflowKey(MEETING),
                FinalizationCheckpoint.CHECKPOINT_TYPE,
                SOURCE_DIGEST);
    }

    private static ArtifactDeletionTarget target(
            String artifactId,
            String artifactType,
            int revision,
            long expectedSize,
            String checksum) {
        return ArtifactDeletionTarget.fromSpec(spec(
                MEETING, artifactId, artifactType, revision, expectedSize, checksum));
    }

    private static WriteIntentSpec spec(
            String meetingId,
            String artifactId,
            String artifactType,
            int revision,
            long expectedSize,
            String checksum) {
        return new WriteIntentSpec(
                artifactId, meetingId, artifactType, "application/json", "zh-CN",
                revision, expectedSize, checksum);
    }

    private static void assertInvalidPlan(org.junit.jupiter.api.function.Executable executable) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, executable);
        assertEquals("Artifact deletion plan is invalid", failure.getMessage());
        assertEquals(null, failure.getCause());
    }

    private static void assertInvalidTarget(org.junit.jupiter.api.function.Executable executable) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, executable);
        assertEquals("Artifact deletion target is invalid", failure.getMessage());
        assertEquals(null, failure.getCause());
    }
}
