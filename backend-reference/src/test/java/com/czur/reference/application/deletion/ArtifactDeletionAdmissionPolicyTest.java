package com.czur.reference.application.deletion;

import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** T14-10-05 删除来源纯 Java 关闭式状态契约。 */
class ArtifactDeletionAdmissionPolicyTest {
    @Test
    void readyAndWarningRequireCompleteWorkflowAndExactMetadata() {
        for (var pair : new Object[][] {
                {MeetingState.READY, ManifestStatus.READY,
                        ArtifactDeletionAdmissionPolicy.Kind.READY},
                {MeetingState.READY_WITH_WARNINGS, ManifestStatus.READY_WITH_WARNINGS,
                        ArtifactDeletionAdmissionPolicy.Kind.READY_WITH_WARNINGS}}) {
            var result = ArtifactDeletionAdmissionPolicy.evaluate(new ArtifactDeletionAdmissionPolicy.Facts(
                    (MeetingState) pair[0], (ManifestStatus) pair[1], true, true,
                    WorkflowStep.Status.COMPLETED, true, true, true,
                    2, 2, true, true));
            assertEquals(new ArtifactDeletionAdmissionPolicy.Accepted((ArtifactDeletionAdmissionPolicy.Kind) pair[2]), result);
        }
        var drift = ArtifactDeletionAdmissionPolicy.evaluate(new ArtifactDeletionAdmissionPolicy.Facts(
                MeetingState.READY, ManifestStatus.READY, true, true,
                WorkflowStep.Status.COMPLETED, true, true, true,
                2, 1, false, true));
        assertEquals(new ArtifactDeletionAdmissionPolicy.Rejected(
                ArtifactDeletionAdmissionPolicy.Rejection.FACT_DRIFT), drift);
    }

    @Test
    void finalFailureKeepsFullCheckpointButAllowsReadySubset() {
        var accepted = ArtifactDeletionAdmissionPolicy.evaluate(new ArtifactDeletionAdmissionPolicy.Facts(
                MeetingState.FAILED, ManifestStatus.FAILED, true, true,
                WorkflowStep.Status.DEAD, true, true, true,
                2, 1, false, true));
        assertEquals(new ArtifactDeletionAdmissionPolicy.Accepted(
                ArtifactDeletionAdmissionPolicy.Kind.FINAL_FAILED), accepted);
        var overflow = ArtifactDeletionAdmissionPolicy.evaluate(new ArtifactDeletionAdmissionPolicy.Facts(
                MeetingState.FAILED, ManifestStatus.FAILED, true, true,
                WorkflowStep.Status.DEAD, true, true, true,
                1, 2, false, false));
        assertEquals(new ArtifactDeletionAdmissionPolicy.Rejected(
                ArtifactDeletionAdmissionPolicy.Rejection.FACT_DRIFT), overflow);
    }

    @Test
    void strictEarlyEmptyIsOnlyCancelledOrPreFinalizationFailed() {
        var accepted = ArtifactDeletionAdmissionPolicy.evaluate(new ArtifactDeletionAdmissionPolicy.Facts(
                MeetingState.CANCELLED, ManifestStatus.NOT_STARTED, false, false,
                null, false, false, false, 0, 0, false, false));
        assertEquals(new ArtifactDeletionAdmissionPolicy.Accepted(
                ArtifactDeletionAdmissionPolicy.Kind.EARLY_EMPTY), accepted);
        var failed = ArtifactDeletionAdmissionPolicy.evaluate(new ArtifactDeletionAdmissionPolicy.Facts(
                MeetingState.FAILED, ManifestStatus.NOT_STARTED, false, false,
                null, false, false, false, 0, 0, false, false));
        assertEquals(new ArtifactDeletionAdmissionPolicy.Accepted(
                ArtifactDeletionAdmissionPolicy.Kind.EARLY_EMPTY), failed);
    }

    @Test
    void generatingMissingManifestAndHalfWorkflowClose() {
        assertRejected(new ArtifactDeletionAdmissionPolicy.Facts(MeetingState.READY, ManifestStatus.GENERATING,
                true, true, WorkflowStep.Status.COMPLETED, true, true, true,
                1, 1, true, true), ArtifactDeletionAdmissionPolicy.Rejection.GENERATING);
        assertRejected(new ArtifactDeletionAdmissionPolicy.Facts(MeetingState.READY, ManifestStatus.READY,
                false, true, WorkflowStep.Status.COMPLETED, true, true, true,
                1, 1, true, true), ArtifactDeletionAdmissionPolicy.Rejection.MISSING_MANIFEST);
        assertRejected(new ArtifactDeletionAdmissionPolicy.Facts(MeetingState.READY, ManifestStatus.READY,
                true, true, WorkflowStep.Status.PENDING, true, true, true,
                1, 0, false, true), ArtifactDeletionAdmissionPolicy.Rejection.HALF_WORKFLOW);
        assertRejected(new ArtifactDeletionAdmissionPolicy.Facts(MeetingState.RUNNING, ManifestStatus.NOT_STARTED,
                false, false, null, false, false, false,
                0, 0, false, false), ArtifactDeletionAdmissionPolicy.Rejection.INVALID_MEETING_STATE);
    }

    private static void assertRejected(
            ArtifactDeletionAdmissionPolicy.Facts facts,
            ArtifactDeletionAdmissionPolicy.Rejection reason) {
        ArtifactDeletionAdmissionPolicy.Rejected result = assertInstanceOf(
                ArtifactDeletionAdmissionPolicy.Rejected.class,
                ArtifactDeletionAdmissionPolicy.evaluate(facts));
        assertEquals(reason, result.reason());
    }
}
